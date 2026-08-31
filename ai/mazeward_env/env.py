# -*- coding: utf-8 -*-
"""MAZEWARD VERSUS の学習環境。``versus/VersusMatch.java`` + ``Island.java`` の移植。

**1 つの方策で迷路・タワー・送りを同時に決める。**
3 つを別々のモデルに分けると、同じコイン・同じカードを奪い合っているという
このゲームの中心が消える。だから行動は 1 本の行動空間にまとめてあり、
「塔だけ学習する」「送りだけ学習する」モードは **存在しない**。

盤面の並び
----------
``board = env * MAX_PLAYERS + seat``。人数は試合ごとに 2〜8 で変わるが、
席は最大人数ぶん確保してマスクで扱う。人数ごとに配列の形が変わると
バッチにまとめられないため。

行動空間（§5-1）
----------------
1 ステップにつき 1 手。まず種別を選び、その種別に必要なパラメータだけを引く。

===============  ==========================================
``type``         SKIP / CARD / TOWER / UPGRADE / SELL / SEND
``card``         手札 6 枠 x 回転 4
``tower``        塔 9 種 x 回転 4
``cell``         置き先（形の **原点** セル）
``unit``         強化・売却する塔
``spec``         最終段階の 2 択
``send``         送るモンスター 12 種
===============  ==========================================

``cell`` のマスクだけは選んだ形に依存するので、種別と形を引いたあとに
:meth:`cell_mask` で作る。**「置いたら経路が塞がる」判定はコストが高いので
マスクに含めず、選ばれた 1 手だけ検査する**（失敗したら空振り＋小さな罰）。
全セル x 全形について封鎖判定を回すと 1 ステップで数万回の BFS になる。
"""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Tuple

import numpy as np

import balance as B
from . import pathfinder as pf
from . import reward as R
from .combat import BalanceTables, Boards, N_ATTACK, N_TOWER
from .grid import CORE, OPEN, ROCK, SPAWN, WALL, Grid, generate_board
from .observation import (OPP_FEATURES, SCALAR_DIM, ObservationBuilder,
                          _safe_div, build_unit_features)
from .rules import EnvConfig
from .shapes import SHAPES, SHAPE_INDEX, SHAPE_ORDER, TOWER_SHAPES

# ---- 行動種別 ------------------------------------------------------
A_SKIP, A_CARD, A_TOWER, A_UPGRADE, A_SELL, A_SEND = range(6)
N_ACTION_TYPES = 6

HAND_LIMIT = B.ECONOMY.hand_limit
CARD_HEAD = HAND_LIMIT * 4
TOWER_HEAD = N_TOWER * 4
CELL_HEAD = B.MAX_BOARD * B.MAX_BOARD
SPEC_HEAD = 2
SEND_HEAD = N_ATTACK
UNIT_HEAD = B.ECONOMY.max_towers

#: カウンタープッシュを「直後」と見なす時間（10 秒）
COUNTER_PUSH_TICKS = 20 * 10
#: 「弱っている相手」と見なすライフ比
PRESSURE_LIFE_RATIO = 0.30

ACTION_HEADS = {
    "type": N_ACTION_TYPES,
    "card": CARD_HEAD,
    "tower": TOWER_HEAD,
    "cell": CELL_HEAD,
    "unit": UNIT_HEAD,
    "spec": SPEC_HEAD,
    "send": SEND_HEAD,
}


def footprint_ok_batch(base: np.ndarray, shape, rot: int) -> np.ndarray:
    """(k, H, W) の盤面すべてについて、形が収まる原点を求める。

    形のセル数ぶんシフトして AND を取るだけ。候補セルを 1 つずつ試す実装だと
    1 ステップに数万回の Python ループになる。
    """
    k, h, w = base.shape
    out = np.ones((k, h, w), dtype=bool)
    for dx, dz in shape.cells[rot]:
        shifted = np.zeros((k, h, w), dtype=bool)
        shifted[:, :h - dz, :w - dx] = base[:, dz:, dx:]
        out &= shifted
    return out


class VersusEnv:
    """``cfg.num_envs`` 試合を同時に回すベクトル化環境。"""

    def __init__(self, cfg: EnvConfig):
        self.cfg = cfg
        self.rng = np.random.default_rng(cfg.seed)
        self.n_envs = cfg.num_envs
        self.seats = B.MAX_PLAYERS
        self.n = self.n_envs * self.seats
        self.size = cfg.board_size
        self.max_towers = UNIT_HEAD

        self.env_of = np.repeat(np.arange(self.n_envs), self.seats)
        self.seat_of = np.tile(np.arange(self.seats), self.n_envs)

        self.balances: List[B.Balance] = [B.default_balance()
                                          for _ in range(self.n_envs)]
        self.tables = BalanceTables(self.balances)
        self.boards = Boards(self.n, self.env_of, self.tables,
                             self.size, B.BOARD.spawns)
        self.obs = ObservationBuilder(self.n, self.size, self.max_towers)

        self.grids: List[Optional[Grid]] = [None] * self.n
        self.library = np.array([SHAPE_INDEX[s] for s in B.STARTER_DECK],
                                dtype=np.int16)
        self.lib_size = len(self.library)
        self._card_shapes = [SHAPES[s] for s in SHAPE_ORDER]
        self._tower_shapes = [TOWER_SHAPES[k] for k in B.TOWER_ORDER]

        self._alloc_state()
        self.reset()

    # ================================================================ 状態
    def _alloc_state(self) -> None:
        n, e = self.n, self.n_envs
        self.active = np.zeros(n, dtype=bool)          # その席に人がいるか
        self.env_players = np.full(e, 2, dtype=np.int32)
        self.env_tick = np.zeros(e, dtype=np.int64)
        self.env_income_timer = np.zeros(e, dtype=np.int64)
        self.env_card_timer = np.zeros(e, dtype=np.int64)
        self.env_done = np.zeros(e, dtype=bool)
        self.env_episode = np.zeros(e, dtype=np.int64)
        self.env_pressured = np.zeros(e, dtype=bool)

        self.hand = np.full((n, HAND_LIMIT), -1, dtype=np.int16)
        self.hand_n = np.zeros(n, dtype=np.int32)
        self.pile = np.zeros((n, self.lib_size), dtype=np.int16)
        self.pile_n = np.zeros(n, dtype=np.int32)
        self.stock_progress = np.zeros(n, dtype=np.float32)

        # 送りの履歴（実ゲームでもチャットで全員に流れる公開情報）
        self.last_send_tick = np.full(n, -10 ** 9, dtype=np.int64)
        self.send_decay10 = np.zeros(n, dtype=np.float32)
        self.send_decay30 = np.zeros(n, dtype=np.float32)
        self.sends_total = np.zeros(n, dtype=np.float32)
        self.last_send_cost = np.zeros(n, dtype=np.float32)
        self.sent_income = np.zeros(n, dtype=np.float32)

        # 派生キャッシュ（壁・塔が変わったときだけ作り直す）
        self.path_mask = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        self.flight_mask = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        self.tower_occ = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        self.coverage = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=np.float32)
        self.base_build = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        self.base_tower = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        self._raw_tower_base = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        self.static_ch = np.zeros((n, 7, B.MAX_BOARD, B.MAX_BOARD), dtype=np.float32)
        # セル中心の座標。射程カバレッジで毎回 mgrid を作ると効いてくる
        ys, xs = np.mgrid[0:B.MAX_BOARD, 0:B.MAX_BOARD]
        self._cell_cx = (xs + 0.5).astype(np.float32)
        self._cell_cz = (ys + 0.5).astype(np.float32)
        self.ground_len = np.zeros(n, dtype=np.float32)
        self.tower_passes = np.zeros(n, dtype=np.float32)
        self.weighted_path = np.zeros(n, dtype=np.float32)
        self.path_cell_list: List[np.ndarray] = [np.zeros((0, 2), np.int32)] * n

        # 指標
        self.stat_cards_drawn = np.zeros(n, dtype=np.float32)
        self.stat_cards_played = np.zeros(n, dtype=np.float32)
        self.stat_sends = np.zeros(n, dtype=np.float32)
        self.stat_invalid = np.zeros(n, dtype=np.float32)
        self.stat_steps = np.zeros(n, dtype=np.float32)
        self.cp_chances = np.zeros(n, dtype=np.float32)
        self.cp_hits = np.zeros(n, dtype=np.float32)
        self.final_rank = np.zeros(n, dtype=np.int32)

        self._prev_weighted = np.zeros(n, dtype=np.float32)
        self._acted = np.zeros(n, dtype=bool)
        self._invalid_step = np.zeros(n, dtype=np.float32)
        self._masks: Dict[str, np.ndarray] = {}

    # ================================================================ reset
    def reset(self) -> Dict[str, np.ndarray]:
        for e in range(self.n_envs):
            self._reset_env(e)
        self.boards.refresh_tower_stats()
        return self.observe()

    def _reset_env(self, e: int) -> None:
        cfg = self.cfg
        players = int(self.rng.choice(cfg.players_choices))
        self.env_players[e] = players
        self.env_tick[e] = 0
        self.env_income_timer[e] = 0
        self.env_card_timer[e] = 0
        self.env_done[e] = False
        self.env_pressured[e] = False
        self.env_episode[e] += 1

        # ドメインランダム化はここで 1 回だけ。**同じ試合の全員が同じ数値**
        # でないとゲームとして成立しないので、環境単位で振る
        bal = (B.randomized_balance(self.rng, cfg.randomize)
               if cfg.randomize > 0 else B.default_balance())
        bal.board.size = self.size
        self.balances[e] = bal
        self._refresh_tables_for(e, bal)

        # 全員まったく同じ地形（同一シードで生成して複製する）
        seed = int(self.rng.integers(1 << 30))
        base = generate_board(bal.board, np.random.default_rng(seed))
        for seat in range(self.seats):
            self._reset_board(e * self.seats + seat, e, base, seat < players)

    def _refresh_tables_for(self, e: int, bal: B.Balance) -> None:
        """1 環境ぶんのバランス表を差し替える。表全体を作り直すと
        リセットのたびに全環境ぶん再計算することになるので、行だけ入れ替える。"""
        one = BalanceTables([bal])
        for name in ("tw_damage", "tw_range", "tw_cooldown", "tw_splash",
                     "tw_chain", "tw_slow", "tw_slow_ticks", "tw_burn",
                     "tw_burn_ticks", "tw_banish", "tw_vuln", "tw_vuln_ticks",
                     "tw_boost_dmg", "tw_boost_rate", "tw_cost", "tw_upgrade",
                     "tw_invested", "at_cost", "at_income", "at_stock",
                     "at_unlock", "at_hp", "at_reward", "en_speed", "en_armor",
                     "en_slow_resist", "en_heal", "start_coins", "start_income",
                     "start_lives", "max_stock", "max_towers", "prep_ticks",
                     "income_interval", "min_income_interval", "full_lobby",
                     "income_step", "stock_interval", "sudden_death",
                     "card_interval", "hand_limit", "start_hand"):
            getattr(self.tables, name)[e] = getattr(one, name)[0]

    def _reset_board(self, b: int, e: int, base: Grid, active: bool) -> None:
        bd, t = self.boards, self.tables
        self.active[b] = active
        self.grids[b] = base.copy()

        bd.tw_kind[b] = -1
        for arr in (bd.tw_level, bd.tw_spec, bd.tw_x, bd.tw_z, bd.tw_invested,
                    bd.tw_charge, bd.tw_disabled, bd.tw_boost_dmg,
                    bd.tw_boost_rate):
            arr[b] = 0
        bd.tw_count[b] = 0
        bd.tw_cells[b] = [None] * bd.max_towers

        bd.en_alive[b] = False
        bd.en_body[b] = -1
        bd.en_attacker[b] = -1
        bd.en_hp[b] = 0.0
        bd.en_max_hp[b] = 1.0
        bd.en_progress[b] = 0.0
        bd.en_sender[b] = -1
        bd.en_count[b] = 0

        bd.coins[b] = t.start_coins[e]
        bd.income[b] = t.start_income[e]
        bd.lives[b] = t.start_lives[e] if active else 0
        bd.max_lives[b] = t.start_lives[e]
        bd.stock[b] = t.max_stock[e]
        bd.income_progress[b] = 0.0
        bd.alive[b] = active
        for name in ("stat_leaks", "stat_kills", "stat_coins_earned",
                     "stat_idle_slots", "stat_tower_slots", "stat_max_life_lost",
                     "stat_life_regained", "stat_breakthrough"):
            getattr(bd, name)[b] = 0

        # デッキ: ライブラリを切って山札にし、開幕の手札を引く
        pile = self.library.copy()
        self.rng.shuffle(pile)
        self.pile[b] = pile
        self.pile_n[b] = self.lib_size
        self.hand[b] = -1
        self.hand_n[b] = 0
        self._draw(np.array([b]), int(t.start_hand[e]))

        self.stock_progress[b] = 0.0
        self.last_send_tick[b] = -10 ** 9
        for name in ("send_decay10", "send_decay30", "sends_total",
                     "last_send_cost", "sent_income", "stat_cards_played",
                     "stat_sends", "stat_invalid", "stat_steps",
                     "cp_chances", "cp_hits"):
            getattr(self, name)[b] = 0.0
        self.stat_cards_drawn[b] = float(t.start_hand[e])
        self.final_rank[b] = 0

        self.tower_occ[b] = False
        self.coverage[b] = 0.0
        self._recompute_board(b)
        self._prev_weighted[b] = self.weighted_path[b]

    # ================================================================ 派生量
    def _recompute_board(self, b: int, walls_changed: bool = True) -> None:
        """派生量を作り直す。**壁か塔が変わったときだけ**呼ぶ。

        壁と塔で必要な再計算が違うので分けてある。塔を 1 基置いただけで
        Theta* を引き直すのは純粋な無駄で、プロファイルではここが
        全体の半分を占めていた（塔の設置は壁より頻度が高い）。
        """
        if walls_changed:
            self._recompute_path(b)
        self._recompute_towers(b)

    def _recompute_path(self, b: int) -> None:
        """経路・飛行ルート・静的チャンネル・設置マスクの土台。壁が変わったときだけ。"""
        grid = self.grids[b]
        bd = self.boards
        size = grid.width
        s_count = len(grid.spawns)
        core = grid.core_center()

        self.path_mask[b] = False
        self.flight_mask[b] = False
        total_len = 0.0
        cells_all: List[np.ndarray] = []

        for s, spawn in enumerate(grid.spawns):
            res = pf.find(grid, spawn)
            if res.reachable:
                bd.set_path(b, s, res.waypoints)
                total_len += res.length
                cells = np.array(pf.traversed_cells(res.waypoints), dtype=np.int32)
                cells_all.append(cells)
                self.path_mask[b, cells[:, 1], cells[:, 0]] = True
            bd.set_flight(b, s_count + s, spawn, core)
            fl = np.array(pf.traversed_cells(
                [spawn, (int(core[0]), int(core[1]))]), dtype=np.int32)
            fl = fl[(fl[:, 0] < size) & (fl[:, 1] < size)]
            self.flight_mask[b, fl[:, 1], fl[:, 0]] = True

        self.ground_len[b] = total_len
        self.path_cell_list[b] = (np.concatenate(cells_all) if cells_all
                                  else np.zeros((0, 2), np.int32))

        cells = grid.cells
        h, w = cells.shape
        walk = (cells == OPEN) | (cells == SPAWN) | (cells == CORE)
        base = (cells == WALL) | (cells == ROCK)
        self.base_build[b] = False
        self.base_build[b, :h, :w] = cells == OPEN

        st = self.static_ch[b]
        st[:] = 0.0
        st[0, :h, :w] = 1.0
        st[1, :h, :w] = walk
        st[2, :h, :w] = cells == OPEN
        st[3, :h, :w] = base
        st[4, :h, :w] = cells == ROCK
        st[5, :h, :w] = cells == SPAWN
        st[6, :h, :w] = cells == CORE
        self._raw_tower_base[b] = False
        self._raw_tower_base[b, :h, :w] = base

    def _recompute_towers(self, b: int) -> None:
        """塔の占有・射程カバレッジ・通過回数。塔か壁が変わったときに呼ぶ。"""
        bd = self.boards
        grid = self.grids[b]
        size = grid.width
        path_cells = self.path_cell_list[b]

        live = np.flatnonzero(bd.tw_kind[b] >= 0)
        occ = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        for slot in live:
            cells = bd.tw_cells[b][slot]
            if cells is not None:
                occ[cells[:, 1], cells[:, 0]] = True
        self.tower_occ[b] = occ
        self.base_tower[b] = self._raw_tower_base[b] & ~occ

        cov = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=np.float32)
        passes = 0.0
        weighted = float(len(path_cells))
        if len(live):
            tx, tz = bd.tw_x[b, live], bd.tw_z[b, live]
            rng_ = bd._st_range[b, live]
            cx, cz = self._cell_cx[:size, :size], self._cell_cz[:size, :size]
            d2 = ((cx[None] - tx[:, None, None]) ** 2
                  + (cz[None] - tz[:, None, None]) ** 2)
            inside = d2 <= (rng_ ** 2)[:, None, None]
            cov[:size, :size] = inside.sum(axis=0)
            if len(path_cells):
                # 経路を順にたどり、外→内に変わった回数を数える。
                # 「長いだけ」ではなく「一つの塔の射程を何度も通る蛇行」を測る指標
                on = inside[:, path_cells[:, 1], path_cells[:, 0]]   # (T, L)
                prev = np.zeros_like(on)
                prev[:, 1:] = on[:, :-1]
                passes = float((on & ~prev).sum())
                weighted = float((1.0 + on.sum(axis=0)).sum())
        self.coverage[b] = cov
        self.tower_passes[b] = passes
        self.weighted_path[b] = weighted

    # ================================================================ カード
    def _draw(self, boards: np.ndarray, count: int = 1) -> None:
        """カードを引く。山札が尽きたらライブラリを切り直す。 (Deck#drawOne)

        **対戦のカードは「有限」ではなく「30 秒に 1 枚」の流量制限**である
        （山札は再シャッフルされる）。希少なのは枚数ではなく時間。
        """
        for b in boards:
            b = int(b)
            limit = int(self.tables.hand_limit[self.env_of[b]])
            for _ in range(count):
                if self.hand_n[b] >= limit:
                    break
                if self.pile_n[b] <= 0:
                    pile = self.library.copy()
                    self.rng.shuffle(pile)
                    self.pile[b] = pile
                    self.pile_n[b] = self.lib_size
                self.pile_n[b] -= 1
                self.hand[b, self.hand_n[b]] = self.pile[b, self.pile_n[b]]
                self.hand_n[b] += 1
                self.stat_cards_drawn[b] += 1

    def _remove_card(self, b: int, slot: int) -> None:
        n = int(self.hand_n[b])
        self.hand[b, slot:n - 1] = self.hand[b, slot + 1:n]
        self.hand[b, n - 1] = -1
        self.hand_n[b] = n - 1

    # ================================================================ マスク
    def action_masks(self) -> Dict[str, np.ndarray]:
        bd, t = self.boards, self.tables
        env = self.env_of
        live = self.active & bd.alive & ~self.env_done[env]

        mask_card = np.repeat(
            np.arange(HAND_LIMIT)[None, :] < self.hand_n[:, None], 4, axis=1)

        room = (bd.tw_count < t.max_towers[env])[:, None]
        affordable = bd.coins[:, None] >= t.tw_cost[env]
        # 土台が 1 箇所も無ければその塔は置けない。塔 9 種 x 回転 4 = 36 通りを
        # 素直に回すと (n, 27, 27) の判定を 36 回やることになるが、実際の形は
        # DOT / I2 / I3 / O の 4 つしかなく、回転で一致するものも多い。
        # **正規化したセル集合をキーに使い回す**と 36 → 6 回まで減る
        cache: Dict[tuple, np.ndarray] = {}
        base_free = np.zeros((self.n, TOWER_HEAD), dtype=bool)
        for k, shape in enumerate(self._tower_shapes):
            for rot in range(4):
                key = tuple(map(tuple, shape.cells[rot]))
                hit = cache.get(key)
                if hit is None:
                    hit = footprint_ok_batch(self.base_tower, shape,
                                             rot).any(axis=(1, 2))
                    cache[key] = hit
                base_free[:, k * 4 + rot] = hit
        mask_tower = np.repeat(affordable & room, 4, axis=1) & base_free

        has_tower = bd.tw_kind >= 0
        can_upgrade = has_tower & (bd.tw_level < B.MAX_TOWER_LEVEL)
        next_cost = t.tw_upgrade[env[:, None], np.maximum(bd.tw_kind, 0),
                                 np.minimum(bd.tw_level, B.MAX_TOWER_LEVEL - 1)]
        mask_up = can_upgrade & (bd.coins[:, None] >= next_cost)

        unlocked = bd.income[:, None] >= t.at_unlock[env]
        can_pay = ((bd.coins[:, None] >= t.at_cost[env])
                   & (bd.stock[:, None] >= t.at_stock[env]))
        preparing = (self.env_tick[env] < t.prep_ticks[env])[:, None]
        limit = np.arange(N_ATTACK)[None, :] < self.cfg.attacker_limit
        mask_send = unlocked & can_pay & ~preparing & limit

        mask_type = np.zeros((self.n, N_ACTION_TYPES), dtype=bool)
        mask_type[:, A_SKIP] = True
        mask_type[:, A_CARD] = mask_card.any(axis=1)
        mask_type[:, A_TOWER] = mask_tower.any(axis=1)
        mask_type[:, A_UPGRADE] = mask_up.any(axis=1)
        mask_type[:, A_SELL] = has_tower.any(axis=1)
        mask_type[:, A_SEND] = mask_send.any(axis=1)
        mask_type &= live[:, None]
        mask_type[:, A_SKIP] = True

        self._masks = {
            "type": mask_type,
            "card": mask_card & live[:, None],
            "tower": mask_tower & live[:, None],
            "unit_upgrade": mask_up & live[:, None],
            "unit_sell": has_tower & live[:, None],
            "send": mask_send & live[:, None],
        }
        return self._masks

    def cell_mask(self, action_type: np.ndarray, card_choice: np.ndarray,
                  tower_choice: np.ndarray) -> np.ndarray:
        """選んだ形と角度に対して置ける原点セル。 (B, CELL_HEAD)

        (形, 角度) でグループ化して一括計算する。1 島ずつ回すと 1 ステップに
        数百回の Python ループになる。
        """
        out = np.zeros((self.n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)

        want = action_type == A_CARD
        if want.any():
            slots = np.minimum(card_choice // 4, HAND_LIMIT - 1)
            ids = np.where(want & (card_choice // 4 < self.hand_n),
                           self.hand[np.arange(self.n), slots], -1)
            self._group_mask(out, want & (ids >= 0), ids, card_choice % 4,
                             self.base_build, self._card_shapes)

        want = action_type == A_TOWER
        if want.any():
            self._group_mask(out, want, tower_choice // 4, tower_choice % 4,
                             self.base_tower, self._tower_shapes)

        flat = out.reshape(self.n, -1)
        # 置ける場所が無い盤面でも 1 つは True にしておく（softmax が壊れる）
        flat[~flat.any(axis=1), 0] = True
        return flat

    @staticmethod
    def _group_mask(out, want, ids, rots, base, shape_list) -> None:
        for sid in np.unique(ids[want]):
            if sid < 0:
                continue
            shape = shape_list[int(sid)]
            for rot in range(4):
                sel = np.flatnonzero(want & (ids == sid) & (rots == rot))
                if len(sel):
                    out[sel] = footprint_ok_batch(base[sel], shape, rot)

    # ================================================================ step
    def step(self, action: Dict[str, np.ndarray]):
        bd, t = self.boards, self.tables
        env = self.env_of
        cfg = self.cfg
        dt = cfg.decision_ticks

        live = self.active & bd.alive & ~self.env_done[env]
        coins_before = bd.coins.copy()
        lives_before = bd.lives.copy()
        max_lives_before = bd.max_lives.copy()
        idle_before = bd.stat_idle_slots.copy()
        slots_before = bd.stat_tower_slots.copy()

        a_type = np.where(live, action["type"], A_SKIP)
        self._acted[:] = False
        self._invalid_step[:] = 0.0
        walls: List[int] = []      # 壁が変わった島（Theta* を引き直す）
        towers: List[int] = []     # 塔だけ変わった島（カバレッジのみ）
        reanchor: List[Tuple[int, np.ndarray]] = []

        self._do_cards(a_type == A_CARD, action, walls, reanchor)
        self._do_towers(a_type == A_TOWER, action, towers)
        self._do_upgrades(a_type == A_UPGRADE, action, towers)
        self._do_sells(a_type == A_SELL, action, towers)
        sent = self._do_sends(a_type == A_SEND, action)

        if walls or towers:
            changed = np.unique(np.array(walls + towers, dtype=np.int64))
            bd.recompute_support(changed)
            bd.refresh_tower_stats()
            wall_set = set(walls)
            for b in changed:
                self._recompute_board(int(b), walls_changed=int(b) in wall_set)
        # 経路を引き直したあとに、敵を新しい折れ線へ乗せ換える
        for b, old_pos in reanchor:
            for s in range(len(self.grids[b].spawns)):
                bd.reanchor(b, s, old_pos)

        # ---- 時間を進める ----
        self._tick_economy(dt)
        sudden = (self.env_tick[env] >= t.sudden_death[env]) & live
        bd.advance(dt, cfg.combat_dt, sudden)
        bd.compact_enemies()
        self.env_tick += dt
        self.stat_steps[live] += 1.0

        rewards, dones, infos = self._settle(
            live, coins_before, lives_before, max_lives_before,
            idle_before, slots_before, sent)
        return self.observe(), rewards, dones, infos

    # ---------------------------------------------------------------- 行動
    def _do_cards(self, mask, action, dirty, reanchor) -> None:
        """障害物カードを置く。**壁は撤去できない**ので、置いた瞬間に確定する。

        戦闘中は追加で 2 条件（敵の上に置かない・敵を閉じ込めない）を課す。
        (Battlefield#cardPlacementError)
        """
        boards = np.flatnonzero(mask)
        if len(boards) == 0:
            return
        bd = self.boards
        positions = bd.enemy_positions(int(bd.en_count.max()))
        for b in boards:
            b = int(b)
            slot, rot = int(action["card"][b]) // 4, int(action["card"][b]) % 4
            if slot >= self.hand_n[b]:
                self._fail(b)
                continue
            shape = self._card_shapes[int(self.hand[b, slot])]
            cell = int(action["cell"][b])
            ox, oz = cell % B.MAX_BOARD, cell // B.MAX_BOARD
            grid = self.grids[b]
            if grid.check_placement(shape, ox, oz, rot):
                self._fail(b)
                continue
            target = shape.cells_at(ox, oz, rot)
            if self._enemy_blocks(b, grid, target, positions):
                self._fail(b)
                continue

            reanchor.append((b, positions[b].copy()))
            grid.place(shape, ox, oz, rot)
            self._remove_card(b, slot)
            self.stat_cards_played[b] += 1
            self._acted[b] = True
            dirty.append(b)

    def _fail(self, b: int) -> None:
        self.stat_invalid[b] += 1
        self._invalid_step[b] += 1

    def _enemy_blocks(self, b: int, grid: Grid, target: np.ndarray,
                      positions: np.ndarray) -> bool:
        """敵が出ているときの追加条件。 (Battlefield#liveEnemyPlacementError)"""
        bd = self.boards
        n_e = int(bd.en_count[b])
        if n_e == 0:
            return False
        ground = ~self.tables.en_flying[np.maximum(bd.en_body[b, :n_e], 0)]
        idx = np.flatnonzero(bd.en_alive[b, :n_e] & ground)
        if len(idx) == 0:
            return False
        cells = positions[b, idx].astype(np.int32)
        occupied = {(int(x), int(z)) for x, z in cells}
        if any((int(x), int(z)) in occupied for x, z in target):
            return True

        saved = grid.cells[target[:, 1], target[:, 0]].copy()
        grid.cells[target[:, 1], target[:, 0]] = WALL
        grid._walk = None
        trapped = False
        for x, z in cells:
            cx = int(np.clip(x, 0, grid.width - 1))
            cz = int(np.clip(z, 0, grid.height - 1))
            if not grid.walk[cz, cx]:
                cx, cz = self._nearest_walkable(grid, cx, cz)
            if not grid.reachable(cx, cz):
                trapped = True
                break
        grid.cells[target[:, 1], target[:, 0]] = saved
        grid._walk = None
        return trapped

    @staticmethod
    def _nearest_walkable(grid: Grid, x: int, z: int) -> Tuple[int, int]:
        for r in range(1, 4):
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    nx, nz = x + dx, z + dz
                    if grid.in_bounds(nx, nz) and grid.walk[nz, nx]:
                        return nx, nz
        return x, z

    def _do_towers(self, mask, action, dirty) -> None:
        boards = np.flatnonzero(mask)
        if len(boards) == 0:
            return
        bd, t = self.boards, self.tables
        for b in boards:
            b = int(b)
            choice = int(action["tower"][b])
            kind, rot = choice // 4, choice % 4
            shape = self._tower_shapes[kind]
            cell = int(action["cell"][b])
            ox, oz = cell % B.MAX_BOARD, cell // B.MAX_BOARD
            grid = self.grids[b]
            e = self.env_of[b]
            cost = float(t.tw_cost[e, kind])
            if (bd.tw_count[b] >= t.max_towers[e] or bd.coins[b] < cost
                    or not grid.is_tower_base_for(shape, ox, oz, rot)):
                self._fail(b)
                continue
            cells = shape.cells_at(ox, oz, rot)
            if self.tower_occ[b, cells[:, 1], cells[:, 0]].any():
                self._fail(b)
                continue

            slot = int(bd.tw_count[b])
            bd.coins[b] -= cost
            bd.tw_kind[b, slot] = kind
            bd.tw_level[b, slot] = 0
            bd.tw_spec[b, slot] = 0
            bd.tw_x[b, slot] = cells[:, 0].mean() + 0.5
            bd.tw_z[b, slot] = cells[:, 1].mean() + 0.5
            bd.tw_invested[b, slot] = int(cost)
            bd.tw_charge[b, slot] = t.tw_cooldown[e, kind, 0, 0]
            bd.tw_disabled[b, slot] = 0.0
            bd.tw_cells[b][slot] = cells
            bd.tw_count[b] = slot + 1
            self._acted[b] = True
            dirty.append(b)

    def _do_upgrades(self, mask, action, dirty) -> None:
        boards = np.flatnonzero(mask)
        if len(boards) == 0:
            return
        bd, t = self.boards, self.tables
        for b in boards:
            b = int(b)
            slot = int(action["unit"][b])
            if slot >= bd.tw_count[b] or bd.tw_kind[b, slot] < 0:
                self._fail(b)
                continue
            level = int(bd.tw_level[b, slot])
            if level >= B.MAX_TOWER_LEVEL:
                self._fail(b)
                continue
            e = self.env_of[b]
            cost = float(t.tw_upgrade[e, bd.tw_kind[b, slot], level])
            if bd.coins[b] < cost:
                self._fail(b)
                continue
            bd.coins[b] -= cost
            bd.tw_level[b, slot] = level + 1
            bd.tw_invested[b, slot] += int(cost)
            # 最終段階では特化を 1 つ選ぶ（同じ塔が 2 つの別物に分かれる）
            if level + 1 == B.MAX_TOWER_LEVEL:
                bd.tw_spec[b, slot] = int(action["spec"][b]) + 1
            self._acted[b] = True
            dirty.append(b)

    def _do_sells(self, mask, action, dirty) -> None:
        boards = np.flatnonzero(mask)
        if len(boards) == 0:
            return
        bd = self.boards
        for b in boards:
            b = int(b)
            slot = int(action["unit"][b])
            if slot >= bd.tw_count[b] or bd.tw_kind[b, slot] < 0:
                self._fail(b)
                continue
            bd.coins[b] += round(float(bd.tw_invested[b, slot]) * B.SELL_REFUND)
            last = int(bd.tw_count[b]) - 1
            for arr in (bd.tw_kind, bd.tw_level, bd.tw_spec, bd.tw_x, bd.tw_z,
                        bd.tw_invested, bd.tw_charge, bd.tw_disabled,
                        bd.tw_boost_dmg, bd.tw_boost_rate):
                arr[b, slot] = arr[b, last]
            bd.tw_cells[b][slot] = bd.tw_cells[b][last]
            bd.tw_kind[b, last] = -1
            bd.tw_cells[b][last] = None
            bd.tw_count[b] = last
            self._acted[b] = True
            dirty.append(b)

    def _do_sends(self, mask, action) -> np.ndarray:
        """送りは **生き残っている自分以外の全員に同時** に飛ぶ。
        相手を選べないので、判断は「いつ・何を送るか」だけになる。"""
        bd, t = self.boards, self.tables
        sent = np.zeros(self.n, dtype=bool)
        boards = np.flatnonzero(mask)
        if len(boards) == 0:
            return sent

        kinds = action["send"][boards].astype(np.int64)
        env = self.env_of[boards]
        ok = ((bd.income[boards] >= t.at_unlock[env, kinds])
              & (bd.stock[boards] >= t.at_stock[env, kinds])
              & (bd.coins[boards] >= t.at_cost[env, kinds])
              & (self.env_tick[env] >= t.prep_ticks[env]))
        for b in boards[~ok]:
            self._fail(int(b))
        boards, kinds, env = boards[ok], kinds[ok], env[ok]
        if len(boards) == 0:
            return sent

        bd.coins[boards] -= t.at_cost[env, kinds]
        bd.stock[boards] -= t.at_stock[env, kinds]
        bd.income[boards] += t.at_income[env, kinds]
        sent[boards] = True
        self._acted[boards] = True
        self.stat_sends[boards] += 1.0
        self.sends_total[boards] += 1.0
        self.last_send_tick[boards] = self.env_tick[env]
        self.last_send_cost[boards] = t.at_cost[env, kinds]
        self.sent_income[boards] += t.at_income[env, kinds]
        self.send_decay10[boards] += 1.0
        self.send_decay30[boards] += 1.0

        targets: List[int] = []
        kind_list: List[int] = []
        senders: List[int] = []
        for b, k in zip(boards, kinds):
            e = int(self.env_of[b])
            base = e * self.seats
            for seat in range(int(self.env_players[e])):
                other = base + seat
                if other == b or not bd.alive[other] or not self.active[other]:
                    continue
                targets.append(other)
                kind_list.append(int(k))
                # 誰の送りかを敵に持たせる。コアまで通ったときに
                # 送った側のライフが 1 戻るので、精算に要る
                senders.append(int(b))
        if targets:
            bd.spawn(np.array(targets, dtype=np.int64),
                     np.array(kind_list, dtype=np.int64),
                     sender=np.array(senders, dtype=np.int64))
        return sent

    # ---------------------------------------------------------------- 時間
    def _tick_economy(self, dt: int) -> None:
        bd, t = self.boards, self.tables
        env = self.env_of

        # ストックは毎秒 1 回復（撃ちっぱなしを防ぐ）
        self.stock_progress += dt / np.maximum(t.stock_interval[env], 1)
        whole = np.floor(self.stock_progress)
        bd.stock = np.minimum(t.max_stock[env], bd.stock + whole)
        self.stock_progress -= whole

        # 収入。間隔は生存者数で変わる（少人数ほど速い）ので剰余ではなく
        # 専用タイマーで数える。 (VersusMatch#incomeInterval)
        alive_count = np.zeros(self.n_envs, dtype=np.int32)
        np.add.at(alive_count, env, (bd.alive & self.active).astype(np.int32))
        a = np.clip(alive_count, 2, t.full_lobby)
        interval = np.maximum(t.min_income_interval,
                              t.income_interval - (t.full_lobby - a) * t.income_step)
        self.env_income_timer += dt
        pay = self.env_income_timer >= interval
        if pay.any():
            self.env_income_timer[pay] = 0
            paid = pay[env] & bd.alive & self.active
            bd.coins[paid] += bd.income[paid]

        # カードは一定時間ごとに 1 枚だけ。無制限に配ると壁が資源でなくなる
        self.env_card_timer += dt
        give = self.env_card_timer >= t.card_interval
        if give.any():
            self.env_card_timer[give] = 0
            self._draw(np.flatnonzero(give[env] & bd.alive & self.active), 1)

        # 送り履歴の減衰（相手の観測に使う）
        self.send_decay10 *= math.exp(-dt / 200.0)
        self.send_decay30 *= math.exp(-dt / 600.0)

    # ---------------------------------------------------------------- 決着
    def _settle(self, live, coins_before, lives_before, max_lives_before,
                idle_before, slots_before, sent):
        bd, t = self.boards, self.tables
        env = self.env_of
        cfg = self.cfg

        coins_gained = np.maximum(0.0, bd.coins - coins_before)
        lives_lost = np.maximum(0.0, lives_before - bd.lives)
        # 送りが通ってライフが戻ったぶん。減少と対称に扱う
        lives_gained = np.maximum(0.0, bd.lives - lives_before)
        max_lost = np.maximum(0.0, max_lives_before - bd.max_lives)
        slots = np.maximum(bd.stat_tower_slots - slots_before, 1.0)
        idle_rate = (bd.stat_idle_slots - idle_before) / slots
        hand_full = ((self.hand_n >= t.hand_limit[env]) & ~self._acted
                     & live).astype(np.float32)
        cov_delta = (self.weighted_path - self._prev_weighted) / max(self.size * 4.0, 1.0)
        self._prev_weighted = self.weighted_path.copy()

        rewards = R.step_reward(coins_gained, lives_lost, max_lost, cov_delta,
                                idle_rate, hand_full, self._invalid_step,
                                lives_gained)
        rewards = np.where(live, rewards, 0.0).astype(np.float32)

        # カウンタープッシュの機会と実行を数える（指標。報酬にはしない）
        cheapest_ok = ((bd.income[:, None] >= t.at_unlock[env])
                       & (bd.coins[:, None] >= t.at_cost[env])
                       & (bd.stock[:, None] >= t.at_stock[env])).any(axis=1)
        chance = live & self._opponent_sent_recently() & cheapest_ok
        self.cp_chances[chance] += 1.0
        self.cp_hits[chance & sent] += 1.0

        if sent.any():
            weak = self._weak_opponent_present()
            hit = np.unique(env[sent & weak])
            if len(hit):
                self.env_pressured[hit] = True

        alive_count = np.zeros(self.n_envs, dtype=np.int32)
        np.add.at(alive_count, env, (bd.alive & self.active).astype(np.int32))
        timeout = self.env_tick >= cfg.max_ticks
        finished = (~self.env_done) & ((alive_count <= 1) | timeout)

        dones = np.zeros(self.n, dtype=bool)
        infos: List[dict] = []
        if finished.any():
            for e in np.flatnonzero(finished):
                e = int(e)
                infos.append(self._finish_env(e, rewards, bool(timeout[e])))
                dones[e * self.seats:(e + 1) * self.seats] = \
                    self.active[e * self.seats:(e + 1) * self.seats]
            for e in np.flatnonzero(finished):
                self._reset_env(int(e))
            self.boards.refresh_tower_stats()
        return rewards, dones, infos

    def _opponent_sent_recently(self) -> np.ndarray:
        """直近 10 秒以内に **誰か他の生存者が送ったか**。

        「相手が送った直後 ≒ 防御にリソースを割いている」をネットワークが
        読めるようにするための材料。カウンタープッシュは強制せず、
        有効なら勝率が上がるので方策のほうが勝手に覚える。
        """
        recent = ((self.env_tick[self.env_of] - self.last_send_tick)
                  <= COUNTER_PUSH_TICKS)
        recent &= self.active & self.boards.alive
        total = np.zeros(self.n_envs, dtype=np.int32)
        np.add.at(total, self.env_of, recent.astype(np.int32))
        return (total[self.env_of] - recent.astype(np.int32)) > 0

    def _weak_opponent_present(self) -> np.ndarray:
        bd = self.boards
        ratio = bd.lives / np.maximum(self.tables.start_lives[self.env_of], 1)
        weak = (ratio <= PRESSURE_LIFE_RATIO) & bd.alive & self.active
        cnt = np.zeros(self.n_envs, dtype=np.int32)
        np.add.at(cnt, self.env_of, weak.astype(np.int32))
        return (cnt[self.env_of] - weak.astype(np.int32)) > 0

    def _finish_env(self, e: int, rewards: np.ndarray, timeout: bool) -> dict:
        """終端報酬（順位）を配り、1 試合ぶんの指標をまとめる。"""
        bd = self.boards
        base = e * self.seats
        players = int(self.env_players[e])
        seats = np.arange(base, base + players)

        # 順位: 生存 → ライフ → ライフ上限。同点は同順位（引き分け）
        key = (bd.alive[seats].astype(np.float64) * 1e6
               + bd.lives[seats] * 1e3 + bd.max_lives[seats])
        order = np.argsort(-key)
        rank = np.empty(players, dtype=np.int32)
        rank[order] = np.arange(players)
        for i in range(1, players):
            if key[order[i]] == key[order[i - 1]]:
                rank[order[i]] = rank[order[i - 1]]

        term = R.rank_reward(rank, np.full(players, players))
        decided = rank.max() > 0
        if timeout and not decided:
            term[:] = 0.0            # 誰も脱落せず時間切れ = 引き分け
        rewards[seats] += term
        self.final_rank[seats] = rank

        return {
            "env": e,
            "episode": int(self.env_episode[e]),
            "players": players,
            "timeout": bool(timeout),
            "decided": bool(decided),
            "ticks": int(self.env_tick[e]),
            "ranks": rank.copy(),
            "seats": seats.copy(),
            "path_length": float(self.ground_len[seats].mean()),
            "tower_passes": float(self.tower_passes[seats].mean()),
            "towers": float(bd.tw_count[seats].mean()),
            "income": float(bd.income[seats].mean()),
            "sends": float(self.stat_sends[seats].mean()),
            "leaks": float(bd.stat_leaks[seats].mean()),
            "breakthroughs": float(bd.stat_breakthrough[seats].mean()),
            "life_regained": float(bd.stat_life_regained[seats].mean()),
            "kills": float(bd.stat_kills[seats].mean()),
            "coins_earned": float(bd.stat_coins_earned[seats].mean()),
            "cards_played": float(self.stat_cards_played[seats].sum()),
            "cards_drawn": float(self.stat_cards_drawn[seats].sum()),
            "cp_chances": float(self.cp_chances[seats].sum()),
            "cp_hits": float(self.cp_hits[seats].sum()),
            "invalid": float(self.stat_invalid[seats].sum()),
            "steps": float(self.stat_steps[seats].mean()),
            "pressured": bool(self.env_pressured[e]),
            "balance": self.balances[e].fingerprint(),
        }

    # ================================================================ 観測
    def observe(self) -> Dict[str, np.ndarray]:
        bd = self.boards
        o = self.obs
        g = o.grid
        g[:, 0:7] = self.static_ch
        g[:, 7] = self.tower_occ
        # 4 基で 1.0 になるよう正規化。密なキルゾーンが飽和して見えないと
        # 「もう 1 基足すか、別の場所へ広げるか」の判断が学べない
        g[:, 8] = np.minimum(self.coverage / 4.0, 2.0)
        g[:, 9] = self.path_mask
        g[:, 10] = self.flight_mask

        n_e = int(bd.en_count.max()) if self.n else 0
        if n_e:
            pos = bd.enemy_positions(n_e)
            alive = bd.en_alive[:, :n_e]
            hp_ratio = bd.en_hp[:, :n_e] / np.maximum(bd.en_max_hp[:, :n_e], 1e-6)
            total = np.maximum(bd.path_total()[:, :n_e], 1e-6)
            threat = np.clip(bd.en_progress[:, :n_e] / total, 0.0, 1.0)
            o.fill_enemies(pos, alive, hp_ratio, threat, self.size)
        else:
            zero = np.zeros((self.n, 1), dtype=np.float32)
            o.fill_enemies(np.zeros((self.n, 1, 2), np.float32),
                           np.zeros((self.n, 1), bool), zero, zero, self.size)

        self._fill_scalars()
        self._fill_opponents()
        masks = self.action_masks()
        return {
            "grid": o.grid,
            "scalar": o.scalar,
            "opponents": o.opponents,
            "opp_mask": o.opp_mask,
            **{f"mask_{k}": v for k, v in masks.items()},
        }

    def _fill_scalars(self) -> None:
        bd, t = self.boards, self.tables
        env = self.env_of
        s = self.obs.scalar

        income = np.maximum(bd.income, 1.0)
        cheap_tower = t.tw_cost[env].min(axis=1).astype(np.float32)
        unlocked = bd.income[:, None] >= t.at_unlock[env]
        send_cost = np.where(unlocked, t.at_cost[env],
                             10 ** 6).min(axis=1).astype(np.float32)
        up_cost = t.tw_upgrade[env[:, None], np.maximum(bd.tw_kind, 0),
                               np.minimum(bd.tw_level, B.MAX_TOWER_LEVEL - 1)]
        has = bd.tw_kind >= 0
        mean_up = (np.where(has, up_cost, 0).sum(axis=1)
                   / np.maximum(has.sum(axis=1), 1))

        alive_count = np.zeros(self.n_envs, dtype=np.float32)
        np.add.at(alive_count, env, (bd.alive & self.active).astype(np.float32))
        players = self.env_players[env].astype(np.float32)

        n_e = int(bd.en_count.max()) if self.n else 0
        if n_e:
            enemy_hp = np.where(bd.en_alive[:, :n_e], bd.en_hp[:, :n_e], 0.0).sum(axis=1)
            enemy_n = bd.en_alive[:, :n_e].sum(axis=1).astype(np.float32)
        else:
            enemy_hp = np.zeros(self.n, dtype=np.float32)
            enemy_n = np.zeros(self.n, dtype=np.float32)
        # 「溶けるか漏れるか」の見積り: 自陣 DPS x 10 秒 / 湧いている敵の合計 HP
        my_dps = np.where(has, bd._st_damage * B.TICKS_PER_SECOND
                          / np.maximum(bd._st_cooldown, 1.0), 0.0).sum(axis=1)

        col = [
            _safe_div(bd.coins, send_cost),
            bd.income / 50.0,
            _safe_div(cheap_tower, income),
            _safe_div(send_cost, income),
            _safe_div(mean_up, income),
            _safe_div(t.at_reward[env].mean(axis=1), t.at_cost[env].mean(axis=1)),
            self.hand_n / HAND_LIMIT,
            self.pile_n / self.lib_size,
            np.clip(self.env_card_timer[env] / np.maximum(t.card_interval[env], 1), 0, 1),
            bd.stock / np.maximum(t.max_stock[env], 1),
            bd.lives / np.maximum(t.start_lives[env], 1),
            bd.max_lives / np.maximum(t.start_lives[env], 1),
            np.clip(self.env_tick[env] / np.maximum(t.sudden_death[env], 1), 0, 2),
            (self.env_tick[env] >= t.sudden_death[env]).astype(np.float32),
            (self.env_tick[env] < t.prep_ticks[env]).astype(np.float32),
            np.clip(1.0 - self.env_tick[env] / np.maximum(t.prep_ticks[env], 1), 0, 1),
            bd.tw_count / np.maximum(t.max_towers[env], 1),
            players / B.MAX_PLAYERS,
            alive_count[env] / np.maximum(players, 1),
            self._current_rank() / np.maximum(players, 1),
            self.ground_len / (self.size * 3.0),
            self.tower_passes / np.maximum(bd.tw_count, 1) / 4.0,
            _safe_div(my_dps * 10.0, enemy_hp),
            enemy_n / 10.0,
            np.log1p(np.maximum(bd.coins, 0)) / 8.0,
            np.log1p(np.maximum(bd.income, 0)) / 6.0,
            self.send_decay10 / 3.0,
            self.stat_sends / 30.0,
            self.coverage.sum(axis=(1, 2)) / (self.size ** 2),
            np.clip(self.stat_invalid / np.maximum(self.stat_steps, 1.0), 0, 1),
        ]
        block = np.stack(col, axis=1).astype(np.float32)
        s[:, :30] = np.nan_to_num(block, nan=0.0, posinf=10.0, neginf=-10.0)
        s[:, 30:] = np.nan_to_num(
            build_unit_features(t, env, bd.income, bd.coins, bd.stock, self.size),
            nan=0.0, posinf=10.0, neginf=-10.0)

    def _current_rank(self) -> np.ndarray:
        bd = self.boards
        key = bd.alive.astype(np.float64) * 1e6 + bd.lives * 1e3 + bd.max_lives
        rank = np.zeros(self.n, dtype=np.float32)
        for e in range(self.n_envs):
            base = e * self.seats
            p = int(self.env_players[e])
            order = np.argsort(-key[base:base + p])
            r = np.empty(p, dtype=np.float32)
            r[order] = np.arange(p)
            rank[base:base + p] = r
        return rank

    def _fill_opponents(self) -> None:
        """相手 1 人ぶんの要約特徴。**実ゲームで見える情報だけ**を渡す。

        見える: ライフ・ライフ上限・脱落・迷路の形（→ 経路長とカバレッジ）・
                塔の数と平均 Lv・送りの履歴（チャットで全員に流れる）
        見えない: コイン・インカム・ストック・手札 → 送りの履歴からの **推定** のみ
        """
        bd, t = self.boards, self.tables
        env = self.env_of
        o = self.obs
        o.opponents[:] = 0.0
        o.opp_mask[:] = 0.0

        avg_level = (np.where(bd.tw_kind >= 0, bd.tw_level, 0).sum(axis=1)
                     / np.maximum(bd.tw_count, 1))
        feats = np.stack([
            bd.lives / np.maximum(t.start_lives[env], 1),
            bd.max_lives / np.maximum(t.start_lives[env], 1),
            bd.alive.astype(np.float32),
            bd.tw_count / np.maximum(t.max_towers[env], 1),
            avg_level / B.MAX_TOWER_LEVEL,
            self.ground_len / (self.size * 3.0),
            self.tower_passes / 20.0,
            self.coverage.sum(axis=(1, 2)) / (self.size ** 2),
            self.send_decay10 / 3.0,                 # 直近 10 秒の送り
            self.send_decay30 / 6.0,                 # 直近 30 秒の送り
            self.last_send_cost / 400.0,             # 高コスト = 潤沢だと推定できる
            self.sends_total / 30.0,
            (t.start_income[env] + self.sent_income) / 100.0,  # インカムの推定
            np.zeros(self.n, dtype=np.float32),      # 自分かどうか
        ], axis=1).astype(np.float32)

        for e in range(self.n_envs):
            base = e * self.seats
            p = int(self.env_players[e])
            block = feats[base:base + p]
            for seat in range(p):
                b = base + seat
                # 自分を先頭に、残りを席順で並べる（席番号に意味を持たせない）
                order = [seat] + [i for i in range(p) if i != seat]
                o.opponents[b, :p] = block[order]
                o.opponents[b, 0, OPP_FEATURES - 1] = 1.0
                o.opp_mask[b, :p] = 1.0

    # ================================================================ 補助
    def ascii_board(self, b: int) -> List[str]:
        """対局ビューア用の文字盤面。"""
        grid = self.grids[b]
        bd = self.boards
        chars = {OPEN: ".", WALL: "#", ROCK: "%", SPAWN: "S", CORE: "C"}
        rows = [[chars.get(int(v), "?") for v in row] for row in grid.cells]
        for x, z in self.path_cell_list[b]:
            if rows[z][x] == ".":
                rows[z][x] = "+"
        for slot in range(int(bd.tw_count[b])):
            cells = bd.tw_cells[b][slot]
            if cells is None:
                continue
            letter = B.TOWER_ORDER[int(bd.tw_kind[b, slot])][0]
            lv = int(bd.tw_level[b, slot])
            for x, z in cells:
                rows[z][x] = letter.lower() if lv == 0 else letter
        n_e = int(bd.en_count[b])
        if n_e:
            pos = bd.enemy_positions(n_e)[b]
            for i in range(n_e):
                if not bd.en_alive[b, i]:
                    continue
                x, z = int(pos[i, 0]), int(pos[i, 1])
                if 0 <= x < grid.width and 0 <= z < grid.height:
                    rows[z][x] = "e"
        return ["".join(r) for r in rows]
