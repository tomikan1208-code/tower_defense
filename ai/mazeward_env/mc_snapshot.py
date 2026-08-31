# -*- coding: utf-8 -*-
"""Minecraft サーバーから届いた盤面を、学習と同じ観測に変換する。

**なぜ Java 側で観測を作らないのか**
------------------------------------
観測の定義は :mod:`observation` と :mod:`env` に 1 本だけある。
14 チャンネルの意味、30 個のスカラーの並び、塔 8 特徴 x 9 種、送り 9 特徴 x 12 種、
相手 14 特徴 x 8 席 —— これを Java へ書き写すと、**学習側の数値を 1 つ変えるたびに
2 箇所を直さないと静かにずれる**。ずれても例外は出ない。AI が少し弱くなるだけで、
誰も原因に辿り着けない。だから Java からは「盤面・塔・敵・財布」という
事実だけを受け取り、観測の組み立てはここ（＝学習と同じコード）でやる。

このモジュールは :class:`env.VersusEnv` の
``_recompute_path`` / ``_recompute_towers`` / ``_fill_scalars`` /
``_fill_opponents`` / ``action_masks`` を **状態の出どころだけ差し替えた**もの。
式は写経ではなく、可能な限り同じ関数（:func:`observation.build_unit_features`,
:func:`pathfinder.traversed_cells`）を呼んでいる。

スナップショットの形（Java の ``ai/MatchSnapshot.java`` と対）
-------------------------------------------------------------
``{"v":1, "match":{...}, "boards":[{...}], "ask":[席番号]}``

座標はすべてセル座標。盤面は ``cells`` に 1 文字ずつ
（``.``=OPEN ``#``=WALL ``R``=ROCK ``S``=SPAWN ``C``=CORE ``B``=BORDER）。
"""

from __future__ import annotations

from typing import Dict, List, Sequence

import numpy as np

import balance as B
from . import pathfinder as pf
from .combat import BalanceTables, N_ATTACK, N_TOWER
from .env import (A_CARD, A_SELL, A_SEND, A_SKIP, A_TOWER, A_UPGRADE,
                  CARD_HEAD, HAND_LIMIT, N_ACTION_TYPES, TOWER_HEAD,
                  footprint_ok_batch)
from .grid import BORDER, CORE, OPEN, ROCK, SPAWN, WALL
from .observation import (CH_COVERAGE, CH_FLIGHT, CH_PATH, CH_TOWER,
                          ObservationBuilder, OPP_FEATURES, _safe_div,
                          build_unit_features)
from .shapes import SHAPE_INDEX, SHAPE_ORDER, SHAPES, TOWER_SHAPES

#: 盤面の文字とセル種別の対応。Java の ``MatchSnapshot#symbol`` と対
CELL_SYMBOLS = {
    ord("."): OPEN,
    ord("#"): WALL,
    ord("R"): ROCK,
    ord("S"): SPAWN,
    ord("C"): CORE,
    ord("B"): BORDER,
}

#: 塔の名前 → 番号。Java の enum 名がそのまま来る（``sync_balance`` が一致を見ている）
TOWER_INDEX = {key: i for i, key in enumerate(B.TOWER_ORDER)}


def _decode_cells(text: str, size: int) -> np.ndarray:
    """1 文字ずつの盤面を ``cells[z, x]`` へ。並びは Java の ``z * width + x``。"""
    raw = np.frombuffer(text.encode("ascii"), dtype=np.uint8)
    out = np.full(size * size, BORDER, dtype=np.uint8)
    for symbol, value in CELL_SYMBOLS.items():
        out[raw == symbol] = value
    return out.reshape(size, size)


class Board:
    """1 島ぶんの派生量。**環境の 1 席と同じ意味**を持つ。"""

    __slots__ = ("cells", "size", "static", "base_build", "base_tower",
                 "tower_occ", "coverage", "path_mask", "flight_mask",
                 "path_cells", "ground_len", "tower_passes", "hand", "hand_n",
                 "pile_n", "coins", "income", "stock", "lives", "max_lives",
                 "alive", "tw_kind", "tw_level", "tw_up_cost", "tw_count",
                 "my_dps", "enemy_hp", "enemy_n", "enemies", "sends",
                 "steps", "invalid", "name")


class SnapshotEncoder:
    """スナップショット → 観測とマスク。1 試合ぶんをまとめて処理する。

    バッファはインスタンスに持たず毎回作る。実ゲームは 1 秒に 1 回・
    最大 8 島なので、確保のコストは無視できる（学習は毎秒 4,700 島ステップ）。
    """

    def __init__(self, balance: B.Balance = None):
        self.balance = balance or B.default_balance()
        self.tables = BalanceTables([self.balance])
        self.card_shapes = [SHAPES[s] for s in SHAPE_ORDER]
        self.tower_shapes = [TOWER_SHAPES[k] for k in B.TOWER_ORDER]
        self.lib_size = len(B.STARTER_DECK)
        self._boards: List[Board] = []

    # ================================================================ 入口
    def encode(self, snap: dict) -> Dict[str, np.ndarray]:
        """観測・マスク・補助情報を作る。

        :return: ``policy`` に渡せる ``grid`` / ``scalar`` / ``opponents`` /
                 ``opp_mask`` と、各 ``mask_*``。
        """
        raw_boards = snap["boards"]
        n = len(raw_boards)
        size = max(int(b.get("size", 0)) for b in raw_boards)
        boards = [self._board(raw, size) for raw in raw_boards]

        obs = ObservationBuilder(n, size, int(self.tables.max_towers[0]))
        self._fill_grid(obs, boards, size)
        scalar = self._scalars(snap, boards, size)
        obs.scalar[:] = scalar
        self._fill_opponents(obs, boards, size)

        out = {
            "grid": obs.grid,
            "scalar": obs.scalar,
            "opponents": obs.opponents,
            "opp_mask": obs.opp_mask,
        }
        out.update(self._masks(snap, boards))
        self._boards = boards
        return out

    # ================================================================ 島 1 つ
    def _board(self, raw: dict, size: int) -> Board:
        bd = Board()
        bd.name = raw.get("name", "?")
        bd.size = size
        bd.coins = float(raw.get("coins", 0))
        bd.income = float(raw.get("income", 0))
        bd.stock = float(raw.get("stock", 0))
        bd.lives = float(raw.get("lives", 0))
        bd.max_lives = float(raw.get("maxLives", 0))
        bd.alive = bool(raw.get("alive", False))
        bd.sends = raw.get("sends", {})
        bd.steps = float(raw.get("steps", 0))
        bd.invalid = float(raw.get("invalid", 0))

        hand = raw.get("hand", [])
        bd.hand = [SHAPE_INDEX[s] for s in hand if s in SHAPE_INDEX]
        bd.hand_n = len(bd.hand)
        bd.pile_n = float(raw.get("pile", 0))

        board_size = int(raw.get("size", 0))
        cells = np.full((size, size), BORDER, dtype=np.uint8)
        if board_size:
            cells[:board_size, :board_size] = _decode_cells(raw["cells"], board_size)
        bd.cells = cells

        # --- 静的チャンネル (env._recompute_path) ---
        walk = (cells == OPEN) | (cells == SPAWN) | (cells == CORE)
        base = (cells == WALL) | (cells == ROCK)
        static = np.zeros((7, B.MAX_BOARD, B.MAX_BOARD), dtype=np.float32)
        static[0, :size, :size] = 1.0
        static[1, :size, :size] = walk
        static[2, :size, :size] = cells == OPEN
        static[3, :size, :size] = base
        static[4, :size, :size] = cells == ROCK
        static[5, :size, :size] = cells == SPAWN
        static[6, :size, :size] = cells == CORE
        bd.static = static

        bd.base_build = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        bd.base_build[:size, :size] = cells == OPEN
        raw_tower_base = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        raw_tower_base[:size, :size] = base

        # --- 経路と飛行ルート ---
        path_mask = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        flight_mask = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        path_cells: List[np.ndarray] = []
        total_len = 0.0
        for way in raw.get("paths", []):
            points = [(int(p[0]), int(p[1])) for p in way]
            if len(points) < 2:
                continue
            cells_on = np.array(pf.traversed_cells(points), dtype=np.int32)
            cells_on = cells_on[(cells_on[:, 0] < size) & (cells_on[:, 1] < size)]
            path_cells.append(cells_on)
            path_mask[cells_on[:, 1], cells_on[:, 0]] = True
            total_len += float(np.sum(np.hypot(
                np.diff([p[0] for p in points]), np.diff([p[1] for p in points]))))
        core = raw.get("core", [0, 0])
        for spawn in raw.get("spawns", []):
            fl = np.array(pf.traversed_cells(
                [(int(spawn[0]), int(spawn[1])), (int(core[0]), int(core[1]))]),
                dtype=np.int32)
            fl = fl[(fl[:, 0] < size) & (fl[:, 1] < size)]
            flight_mask[fl[:, 1], fl[:, 0]] = True
        bd.path_mask = path_mask
        bd.flight_mask = flight_mask
        bd.ground_len = total_len
        path_all = (np.concatenate(path_cells) if path_cells
                    else np.zeros((0, 2), np.int32))
        bd.path_cells = path_all

        # --- 塔: 占有・カバレッジ・通過回数 (env._recompute_towers) ---
        towers = raw.get("towers", [])
        occ = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        kinds, levels, up_costs = [], [], []
        dps = 0.0
        centers, ranges = [], []
        for tower in towers:
            for cx, cz in tower.get("cells", []):
                if 0 <= cx < size and 0 <= cz < size:
                    occ[cz, cx] = True
            kinds.append(TOWER_INDEX.get(tower.get("kind", ""), 0))
            levels.append(int(tower.get("level", 0)))
            up_costs.append(int(tower.get("upCost", -1)))
            centers.append((float(tower.get("cx", 0.0)), float(tower.get("cz", 0.0))))
            ranges.append(float(tower.get("range", 0.0)))
            cooldown = max(1.0, float(tower.get("cooldown", 1)))
            dps += float(tower.get("damage", 0.0)) * B.TICKS_PER_SECOND / cooldown
        bd.tower_occ = occ
        bd.base_tower = raw_tower_base & ~occ
        bd.tw_kind = np.array(kinds, dtype=np.int32)
        bd.tw_level = np.array(levels, dtype=np.int32)
        bd.tw_up_cost = np.array(up_costs, dtype=np.int32)
        bd.tw_count = len(towers)
        bd.my_dps = dps

        cov = np.zeros((B.MAX_BOARD, B.MAX_BOARD), dtype=np.float32)
        passes = 0.0
        if towers:
            tx = np.array([c[0] for c in centers], dtype=np.float32)
            tz = np.array([c[1] for c in centers], dtype=np.float32)
            rng = np.array(ranges, dtype=np.float32)
            grid_x = np.arange(size, dtype=np.float32) + 0.5
            cx = np.broadcast_to(grid_x[None, :], (size, size))
            cz = np.broadcast_to(grid_x[:, None], (size, size))
            d2 = ((cx[None] - tx[:, None, None]) ** 2
                  + (cz[None] - tz[:, None, None]) ** 2)
            inside = d2 <= (rng ** 2)[:, None, None]
            cov[:size, :size] = inside.sum(axis=0)
            if len(path_all):
                on = inside[:, path_all[:, 1], path_all[:, 0]]
                prev = np.zeros_like(on)
                prev[:, 1:] = on[:, :-1]
                passes = float((on & ~prev).sum())
        bd.coverage = cov
        bd.tower_passes = passes

        # --- 敵 ---
        enemies = raw.get("enemies", [])
        bd.enemies = enemies
        bd.enemy_n = float(len(enemies))
        bd.enemy_hp = float(sum(float(e.get("hp", 0.0)) for e in enemies))
        return bd

    # ================================================================ 盤面
    def _fill_grid(self, obs: ObservationBuilder, boards: Sequence[Board],
                   size: int) -> None:
        for b, bd in enumerate(boards):
            obs.grid[b, 0:7] = bd.static
            obs.grid[b, CH_TOWER] = bd.tower_occ
            # 4 基で 1.0。密なキルゾーンが飽和して見えないと
            # 「もう 1 基足すか、広げるか」が学べない (env.observe)
            obs.grid[b, CH_COVERAGE] = np.minimum(bd.coverage / 4.0, 2.0)
            obs.grid[b, CH_PATH] = bd.path_mask
            obs.grid[b, CH_FLIGHT] = bd.flight_mask

        n = len(boards)
        max_e = max(1, max(len(bd.enemies) for bd in boards))
        pos = np.zeros((n, max_e, 2), dtype=np.float32)
        alive = np.zeros((n, max_e), dtype=bool)
        hp_ratio = np.zeros((n, max_e), dtype=np.float32)
        threat = np.zeros((n, max_e), dtype=np.float32)
        for b, bd in enumerate(boards):
            for i, enemy in enumerate(bd.enemies):
                pos[b, i, 0] = float(enemy.get("x", 0.0))
                pos[b, i, 1] = float(enemy.get("z", 0.0))
                alive[b, i] = True
                hp_ratio[b, i] = (float(enemy.get("hp", 0.0))
                                  / max(1e-6, float(enemy.get("maxHp", 1.0))))
                threat[b, i] = min(1.0, max(0.0, float(enemy.get("progress", 0.0))))
        obs.fill_enemies(pos, alive, hp_ratio, threat, size)

    # ================================================================ スカラー
    def _scalars(self, snap: dict, boards: Sequence[Board], size: int) -> np.ndarray:
        t = self.tables
        n = len(boards)
        env = np.zeros(n, dtype=np.int64)
        meta = snap["match"]
        tick = float(meta.get("tick", 0))
        prep = float(meta.get("prepTicks", t.prep_ticks[0]))
        sudden = float(meta.get("suddenDeath", t.sudden_death[0]))
        card_interval = float(meta.get("cardInterval", t.card_interval[0]))
        max_towers = float(meta.get("maxTowers", t.max_towers[0]))
        players = float(meta.get("players", n))

        coins = np.array([bd.coins for bd in boards], dtype=np.float32)
        income = np.array([bd.income for bd in boards], dtype=np.float32)
        stock = np.array([bd.stock for bd in boards], dtype=np.float32)
        lives = np.array([bd.lives for bd in boards], dtype=np.float32)
        max_lives = np.array([bd.max_lives for bd in boards], dtype=np.float32)
        alive = np.array([bd.alive for bd in boards], dtype=np.float32)
        tw_count = np.array([bd.tw_count for bd in boards], dtype=np.float32)
        hand_n = np.array([bd.hand_n for bd in boards], dtype=np.float32)
        pile_n = np.array([bd.pile_n for bd in boards], dtype=np.float32)
        ground_len = np.array([bd.ground_len for bd in boards], dtype=np.float32)
        passes = np.array([bd.tower_passes for bd in boards], dtype=np.float32)
        cov_sum = np.array([bd.coverage.sum() for bd in boards], dtype=np.float32)
        my_dps = np.array([bd.my_dps for bd in boards], dtype=np.float32)
        enemy_hp = np.array([bd.enemy_hp for bd in boards], dtype=np.float32)
        enemy_n = np.array([bd.enemy_n for bd in boards], dtype=np.float32)
        steps = np.array([bd.steps for bd in boards], dtype=np.float32)
        invalid = np.array([bd.invalid for bd in boards], dtype=np.float32)
        d10 = np.array([bd.sends.get("d10", 0.0) for bd in boards], dtype=np.float32)
        sends_total = np.array([bd.sends.get("total", 0) for bd in boards],
                               dtype=np.float32)

        income_safe = np.maximum(income, 1.0)
        cheap_tower = t.tw_cost[env].min(axis=1).astype(np.float32)
        unlocked = income[:, None] >= t.at_unlock[env]
        send_cost = np.where(unlocked, t.at_cost[env], 10 ** 6).min(axis=1).astype(np.float32)
        mean_up = np.array([self._mean_upgrade(bd) for bd in boards], dtype=np.float32)
        rank = self._rank(boards)

        col = [
            _safe_div(coins, send_cost),
            income / 50.0,
            _safe_div(cheap_tower, income_safe),
            _safe_div(send_cost, income_safe),
            _safe_div(mean_up, income_safe),
            _safe_div(t.at_reward[env].mean(axis=1), t.at_cost[env].mean(axis=1)),
            hand_n / HAND_LIMIT,
            pile_n / self.lib_size,
            np.full(n, min(1.0, (tick % card_interval) / max(card_interval, 1.0)),
                    dtype=np.float32),
            stock / np.maximum(t.max_stock[env], 1),
            lives / np.maximum(t.start_lives[env], 1),
            max_lives / np.maximum(t.start_lives[env], 1),
            np.full(n, min(2.0, tick / max(sudden, 1.0)), dtype=np.float32),
            np.full(n, 1.0 if tick >= sudden else 0.0, dtype=np.float32),
            np.full(n, 1.0 if tick < prep else 0.0, dtype=np.float32),
            np.full(n, min(1.0, max(0.0, 1.0 - tick / max(prep, 1.0))), dtype=np.float32),
            tw_count / max(max_towers, 1.0),
            np.full(n, players / B.MAX_PLAYERS, dtype=np.float32),
            np.full(n, alive.sum() / max(players, 1.0), dtype=np.float32),
            rank / max(players, 1.0),
            ground_len / (size * 3.0),
            passes / np.maximum(tw_count, 1) / 4.0,
            _safe_div(my_dps * 10.0, enemy_hp),
            enemy_n / 10.0,
            np.log1p(np.maximum(coins, 0)) / 8.0,
            np.log1p(np.maximum(income, 0)) / 6.0,
            d10 / 3.0,
            sends_total / 30.0,
            cov_sum / (size ** 2),
            np.clip(invalid / np.maximum(steps, 1.0), 0, 1),
        ]
        block = np.nan_to_num(np.stack(col, axis=1).astype(np.float32),
                              nan=0.0, posinf=10.0, neginf=-10.0)
        units = np.nan_to_num(
            build_unit_features(t, env, income, coins, stock, size),
            nan=0.0, posinf=10.0, neginf=-10.0)
        return np.concatenate([block, units], axis=1)

    def _mean_upgrade(self, bd: Board) -> float:
        """次の強化コストの平均。塔が無ければ 0（env と同じ）。"""
        if bd.tw_count == 0:
            return 0.0
        t = self.tables
        level = np.minimum(bd.tw_level, B.MAX_TOWER_LEVEL - 1)
        return float(t.tw_upgrade[0, bd.tw_kind, level].mean())

    @staticmethod
    def _rank(boards: Sequence[Board]) -> np.ndarray:
        """生存 → ライフ → ライフ上限の順位（0 が首位）。 (env._current_rank)"""
        key = np.array([(1e6 if bd.alive else 0.0) + bd.lives * 1e3 + bd.max_lives
                        for bd in boards], dtype=np.float64)
        order = np.argsort(-key)
        rank = np.empty(len(boards), dtype=np.float32)
        rank[order] = np.arange(len(boards), dtype=np.float32)
        return rank

    # ================================================================ 相手
    def _fill_opponents(self, obs: ObservationBuilder, boards: Sequence[Board],
                        size: int) -> None:
        t = self.tables
        n = len(boards)
        start_lives = max(1.0, float(t.start_lives[0]))
        max_towers = max(1.0, float(t.max_towers[0]))
        feats = np.zeros((n, OPP_FEATURES), dtype=np.float32)
        for b, bd in enumerate(boards):
            avg_level = (float(bd.tw_level.sum()) / max(1, bd.tw_count)
                         if bd.tw_count else 0.0)
            sends = bd.sends
            feats[b] = [
                bd.lives / start_lives,
                bd.max_lives / start_lives,
                1.0 if bd.alive else 0.0,
                bd.tw_count / max_towers,
                avg_level / B.MAX_TOWER_LEVEL,
                bd.ground_len / (size * 3.0),
                bd.tower_passes / 20.0,
                float(bd.coverage.sum()) / (size ** 2),
                float(sends.get("d10", 0.0)) / 3.0,
                float(sends.get("d30", 0.0)) / 6.0,
                float(sends.get("lastCost", 0)) / 400.0,
                float(sends.get("total", 0)) / 30.0,
                (float(t.start_income[0]) + float(sends.get("income", 0))) / 100.0,
                0.0,
            ]
        obs.opponents[:] = 0.0
        obs.opp_mask[:] = 0.0
        for seat in range(n):
            # 自分を先頭に、残りを席順で並べる（席番号に意味を持たせない）
            order = [seat] + [i for i in range(n) if i != seat]
            obs.opponents[seat, :n] = feats[order]
            obs.opponents[seat, 0, OPP_FEATURES - 1] = 1.0
            obs.opp_mask[seat, :n] = 1.0

    # ================================================================ マスク
    def _masks(self, snap: dict, boards: Sequence[Board]) -> Dict[str, np.ndarray]:
        t = self.tables
        n = len(boards)
        env = np.zeros(n, dtype=np.int64)
        meta = snap["match"]
        preparing = float(meta.get("tick", 0)) < float(meta.get("prepTicks", 0))
        max_towers = int(meta.get("maxTowers", t.max_towers[0]))
        unit_head = int(t.max_towers[0])

        coins = np.array([bd.coins for bd in boards], dtype=np.float32)
        income = np.array([bd.income for bd in boards], dtype=np.float32)
        stock = np.array([bd.stock for bd in boards], dtype=np.float32)
        live = np.array([bd.alive for bd in boards], dtype=bool)

        mask_card = np.zeros((n, CARD_HEAD), dtype=bool)
        for b, bd in enumerate(boards):
            mask_card[b] = np.repeat(np.arange(HAND_LIMIT) < bd.hand_n, 4)

        mask_tower = np.zeros((n, TOWER_HEAD), dtype=bool)
        room = np.array([bd.tw_count < max_towers for bd in boards])[:, None]
        affordable = coins[:, None] >= t.tw_cost[env]
        base_tower = np.stack([bd.base_tower for bd in boards])
        cache: Dict[tuple, np.ndarray] = {}
        for k, shape in enumerate(self.tower_shapes):
            for rot in range(4):
                key = tuple(map(tuple, shape.cells[rot]))
                hit = cache.get(key)
                if hit is None:
                    hit = footprint_ok_batch(base_tower, shape, rot).any(axis=(1, 2))
                    cache[key] = hit
                mask_tower[:, k * 4 + rot] = hit
        mask_tower &= np.repeat(affordable & room, 4, axis=1)

        mask_up = np.zeros((n, unit_head), dtype=bool)
        mask_sell = np.zeros((n, unit_head), dtype=bool)
        for b, bd in enumerate(boards):
            for i in range(min(bd.tw_count, unit_head)):
                mask_sell[b, i] = True
                cost = int(bd.tw_up_cost[i])
                mask_up[b, i] = cost >= 0 and bd.coins >= cost

        unlocked = income[:, None] >= t.at_unlock[env]
        can_pay = ((coins[:, None] >= t.at_cost[env])
                   & (stock[:, None] >= t.at_stock[env]))
        mask_send = unlocked & can_pay & (not preparing)

        mask_type = np.zeros((n, N_ACTION_TYPES), dtype=bool)
        mask_type[:, A_CARD] = mask_card.any(axis=1)
        mask_type[:, A_TOWER] = mask_tower.any(axis=1)
        mask_type[:, A_UPGRADE] = mask_up.any(axis=1)
        mask_type[:, A_SELL] = mask_sell.any(axis=1)
        mask_type[:, A_SEND] = mask_send.any(axis=1)
        mask_type &= live[:, None]
        mask_type[:, A_SKIP] = True

        return {
            "mask_type": mask_type,
            "mask_card": mask_card & live[:, None],
            "mask_tower": mask_tower & live[:, None],
            "mask_unit_upgrade": mask_up & live[:, None],
            "mask_unit_sell": mask_sell & live[:, None],
            "mask_send": mask_send & live[:, None],
        }

    # ================================================================ セルマスク
    def cell_mask(self, boards: Sequence[Board], action_type: np.ndarray,
                  card_choice: np.ndarray, tower_choice: np.ndarray) -> np.ndarray:
        """選んだ形と角度に対して置ける原点。 (env.cell_mask)

        形が手札のどれなのかは席ごとに違うので、ここだけは席ごとに回す。
        実ゲームは最大 8 席なので、まとめる意味がない。
        """
        n = len(boards)
        out = np.zeros((n, B.MAX_BOARD, B.MAX_BOARD), dtype=bool)
        for b, bd in enumerate(boards):
            kind = int(action_type[b])
            if kind == A_CARD:
                slot, rot = int(card_choice[b]) // 4, int(card_choice[b]) % 4
                if slot >= bd.hand_n:
                    continue
                shape = self.card_shapes[bd.hand[slot]]
                out[b] = footprint_ok_batch(bd.base_build[None], shape, rot)[0]
            elif kind == A_TOWER:
                k, rot = int(tower_choice[b]) // 4, int(tower_choice[b]) % 4
                shape = self.tower_shapes[k]
                out[b] = footprint_ok_batch(bd.base_tower[None], shape, rot)[0]
        flat = out.reshape(n, -1)
        # 1 つも無いと softmax が壊れる (env.cell_mask と同じ逃げ)
        flat[~flat.any(axis=1), 0] = True
        return flat

    @property
    def boards(self) -> List[Board]:
        """直前の :meth:`encode` で作った島。行動の解釈に使う。"""
        return self._boards
