# -*- coding: utf-8 -*-
"""基準ボット 3 種。``dev/VersusSim.java`` の貪欲戦略を移植したもの。

用途は 3 つある。

1. **下限の確認**（``win_vs_random``）。ランダムに勝てないなら学習は壊れている
2. **リーグ学習の初期対戦相手**。自己対戦だけだと「自分だけ強くなったつもり」
   に陥るので、動かない基準を必ず混ぜる
3. **模倣学習（BC）の教師**。ランダム初期方策の迷走を丸ごと省ける

``greedy_defense`` と ``income_push`` は **戦略の両極** になるよう作ってある。
この 2 つの勝率が 40〜60% に収まっているかがバランス診断の対称性チェック
（:mod:`balance_analyzer`）になる。
"""

from __future__ import annotations

from typing import Dict, List, Optional

import numpy as np

import balance as B
from . import pathfinder as pf
from .env import (A_CARD, A_SEND, A_SKIP, A_TOWER, A_UPGRADE, CELL_HEAD,
                  HAND_LIMIT, N_ACTION_TYPES)
from .shapes import SHAPES, SHAPE_ORDER, TOWER_SHAPES

#: 貪欲ボットが 1 回の設置で試す候補の数。実測で Theta* は 0.24ms なので
#: ここを増やすと素直に遅くなる。経路の近くだけを候補にして数を抑える
GREEDY_CANDIDATES = 8
#: 候補セルを経路からどれだけ離れたところまで拾うか
CANDIDATE_RADIUS = 3


def empty_action(n: int) -> Dict[str, np.ndarray]:
    return {
        "type": np.full(n, A_SKIP, dtype=np.int64),
        "card": np.zeros(n, dtype=np.int64),
        "tower": np.zeros(n, dtype=np.int64),
        "cell": np.zeros(n, dtype=np.int64),
        "unit": np.zeros(n, dtype=np.int64),
        "spec": np.zeros(n, dtype=np.int64),
        "send": np.zeros(n, dtype=np.int64),
        # 0 = 1 体。まとめ送りの体数 - 1
        "send_n": np.zeros(n, dtype=np.int64),
    }


class Bot:
    """全ボットの共通形。方策と差し替えられるよう、行動辞書だけを返す。"""

    name = "bot"

    def act(self, env, obs: Dict[str, np.ndarray], boards: np.ndarray,
            action: Dict[str, np.ndarray]) -> None:
        raise NotImplementedError


class RandomBot(Bot):
    """マスクの中から一様に選ぶ。**下限の確認用**。"""

    name = "random"

    def __init__(self, rng: Optional[np.random.Generator] = None):
        self.rng = rng or np.random.default_rng()

    def _pick(self, mask: np.ndarray, boards: np.ndarray) -> np.ndarray:
        m = mask[boards].astype(np.float64)
        m = np.where(m.sum(axis=1, keepdims=True) > 0, m, 1.0)
        c = np.cumsum(m, axis=1)
        r = self.rng.random((len(boards), 1)) * c[:, -1:]
        return (c < r).sum(axis=1)

    def act(self, env, obs, boards, action) -> None:
        if len(boards) == 0:
            return
        action["type"][boards] = self._pick(obs["mask_type"], boards)
        action["card"][boards] = self._pick(obs["mask_card"], boards)
        action["tower"][boards] = self._pick(obs["mask_tower"], boards)
        unit = obs["mask_unit_upgrade"] | obs["mask_unit_sell"]
        action["unit"][boards] = self._pick(unit, boards)
        action["send"][boards] = self._pick(obs["mask_send"], boards)
        action["spec"][boards] = self.rng.integers(0, 2, len(boards))
        cells = env.cell_mask(action["type"], action["card"], action["tower"])
        action["cell"][boards] = self._pick(cells, boards)


class _GreedyBase(Bot):
    """カード配置と塔配置の共通部分。

    経済の判断（送るか・貯めるか・何を送るか）もここに置く。
    **全ボットで共通にしておくこと。** ここが違うと、対称性チェックが
    「守りの差」ではなく「攻めの差」を測ってしまう。

    絶対値のコイン閾値は置かない
    ----------------------------
    対戦の経済は指数で伸びる（開幕インカム 5 → 20 分で 1 万台）。
    「コインが 400 を超えたら送る」のような定数を置くと、
    **数分で常に真になって以降その分岐が死ぬ**。
    旧実装がそうなっていて、実測すると 16 分時点のインカムが 410
    （設計上は 5,474）＝ 経済の 8% しか使えず、
    18 段ある送りの梯子の下から 5 段目より上を一度も撃たなかった。

    判定はすべて **「いま比べたい相手のコスト」に対する比** で書く。
    そうしておけば数値を何倍に振っても勝手に追従する。
    """

    #: 守りに取っておく額。「次に建てる / 強化する 1 手」の何倍か
    defence_reserve_ratio: float = 1.0
    #: 送りを完全に封じる。防衛ベンチ（``tower_bench``）が「同じ金・同じ波」を
    #: 作るために使う。インカムを自分で伸ばされると条件が揃わなくなる
    no_send: bool = False
    #: 「インカムを伸ばす送り」から「重い送り」へ切り替える閾値。
    #: 解禁済みでいちばん高いインカムモブ何体ぶんのコインを持っているかで測る
    pressure_ratio: float = 3.0

    def __init__(self, rng: Optional[np.random.Generator] = None):
        self.rng = rng or np.random.default_rng()

    # -- 経済 --------------------------------------------------------
    def _rival_income(self, env, b: int) -> float:
        """同じ試合の他プレイヤーの平均インカム。

        「インカムが伸び悩んでいるか」を **絶対値の目標ではなく相手との比較**
        で測るために使う。固定目標（旧: 60）は数分で通過して意味を失う。
        """
        bd = env.boards
        e = env.env_of[b]
        same = (env.env_of == e) & env.active & bd.alive
        same[b] = False
        if not same.any():
            return float(bd.income[b])
        return float(bd.income[same].mean())

    def _defence_reserve(self, env, b: int) -> float:
        """次に守りへ使いたい 1 手ぶんの額。これを残した上でのみ送る。

        **定額の予備費にしない。** 強化費は 1 段ごとに 2.6 倍なので、
        定額だと終盤には実質ゼロになり、守りが一切伸びなくなる。
        """
        bd, t = env.boards, env.tables
        e = env.env_of[b]
        built = int(bd.tw_count[b])
        if built < int(t.max_towers[e]):
            return float(t.tw_cost[e].min()) * self.defence_reserve_ratio
        lv = bd.tw_level[b, :built]
        room = lv < B.MAX_TOWER_LEVEL
        if not room.any():
            return 0.0
        kinds = np.maximum(bd.tw_kind[b, :built][room], 0)
        return float(t.tw_upgrade[e, kinds, lv[room]].min()) * self.defence_reserve_ratio

    def _invest_send(self, env, b: int, send: np.ndarray,
                     budget: Optional[float] = None) -> Optional[int]:
        """インカム投資として送るものを選ぶ。

        **「毎秒 1 回しか送れない」ことから選び方が決まる。**
        ストックは毎秒 1 回復し、どのモンスターも消費 1 なので、
        送れる回数は 1 秒 1 回で頭打ち。一方コインは毎秒

            income / 収入間隔(秒)

        だけ増える。だから **その額を 1 回で使い切れる、いちばん高い
        インカムモブ** が最適になる。

        - これより安いものを選ぶ → 送る回数が足りずコインが余る
        - これより高いものを選ぶ → 貯める時間ができて送る回数が減り、
          しかも梯子は上ほどインカム比率が悪いので二重に損

        梯子は飛び飛びなので、収入ぴったりで切ると
        「次の段には届かないが今の段では使い切れない」帯が生まれる。
        そこで **貯まったコインは収入 1 回ぶんの時間で吐き出す** ぶんを足す。

        序盤（インカム 5 = 毎秒 0.5 コイン）はどれも届かないので、
        いちばん安いものへ落ちる。**定数を 1 つも置かずに梯子を登れる**のが要点で、
        「ストックが余っているか」で判定した実装は毎秒 1 回しか行動しない以上
        ストックが減らず、20 分ずっと走狗だけを 13,637 体送って終わった。

        :param budget: 使ってよい上限。守りの予備費を引いた残りを渡す
        """
        if self.no_send:
            return None
        e = env.env_of[b]
        cost = env.tables.at_cost[e, send].astype(np.float64)
        gain = env.tables.at_income[e, send].astype(np.float64)
        ok = gain > 0                       # 災厄・終焉騎はインカムを生まない
        if budget is not None:
            ok &= cost <= budget
        idx = np.flatnonzero(ok)
        if len(idx) == 0:
            return None
        # 毎秒使える額 = 収入 / 収入間隔。それに加えて、
        # **使い切れずに貯まったぶんは収入 1 回ぶんの時間で吐き出す。**
        # 梯子は飛び飛びなので、収入ぴったりで頭打ちにすると
        # 「次の段には届かないが今の段では使い切れない」帯でコインが死蔵される
        # （実測で 18 分時点に 10,484 コイン眠っていた）
        interval = max(1.0, float(env.tables.income_interval[e]) / B.TICKS_PER_SECOND)
        budget_rate = (float(env.boards.income[b])
                       + float(env.boards.coins[b])) / interval
        ceiling = max(budget_rate, float(cost[idx].min()))
        fit = idx[cost[idx] <= ceiling]
        return int(send[fit[int(np.argmax(cost[fit]))]])

    def _send_count(self, env, b: int, kind: int,
                    budget: Optional[float] = None) -> int:
        """その種類を何体まとめて送るか。

        **まとめ送りが入ったので、送りに 1 手まるごと使う必要がなくなった。**
        買えるだけ送って、残りの手を建設へ回す。
        持続レートはストック回復（毎秒 1）で決まるので、
        まとめても総数は増えない — 増えるのは「空く手数」のほう。
        """
        e = env.env_of[b]
        cost = max(1, int(env.tables.at_cost[e, kind]))
        stock_cost = max(1, int(env.tables.at_stock[e, kind]))
        coins = float(env.boards.coins[b]) if budget is None else budget
        n = min(int(coins) // cost,
                int(env.boards.stock[b]) // stock_cost,
                B.MAX_SEND_BATCH)
        return max(1, n)

    def _emit_send(self, env, b: int, action, kind: int,
                   budget: Optional[float] = None) -> None:
        """送りの行動を書き込む（体数つき）。"""
        action["type"][b] = A_SEND
        action["send"][b] = kind
        action["send_n"][b] = self._send_count(env, b, kind, budget) - 1

    def _pressure_send(self, env, b: int, send: np.ndarray,
                       budget: Optional[float] = None) -> Optional[int]:
        """削り切る用。いちばん重いものを選ぶ（インカムは見ない）。"""
        if self.no_send:
            return None
        e = env.env_of[b]
        cost = env.tables.at_cost[e, send].astype(np.float64)
        idx = (np.flatnonzero(cost <= budget) if budget is not None
               else np.arange(len(send)))
        if len(idx) == 0:
            return None
        return int(send[idx[int(np.argmax(cost[idx]))]])

    def _should_pressure(self, env, b: int, send: np.ndarray) -> bool:
        """「もう伸ばさずに押す」へ切り替えるか。

        解禁済みでいちばん高いインカムモブを何体ぶん持っているかで測る。
        **梯子そのものを物差しにする**ので、値段表を書き換えても追従する。
        """
        e = env.env_of[b]
        gain = env.tables.at_income[e, send]
        cost = env.tables.at_cost[e, send]
        top = cost[gain > 0]
        if len(top) == 0:
            return True
        return float(env.boards.coins[b]) > float(top.max()) * self.pressure_ratio

    # -- カード: 経路がいちばん伸びる置き方を探す --------------------
    def best_card(self, env, b: int) -> Optional[tuple]:
        """(手札スロット, 回転, セル) を返す。置ける場所が無ければ None。

        候補を全セル x 全回転で回すと 1 回の設置に数千回の Theta* が要る。
        **経路の近くだけ**を候補にすると、質を落とさずに数十分の 1 になる
        （経路から離れた壁は経路長を変えない）。
        """
        grid = env.grids[b]
        hand_n = int(env.hand_n[b])
        if hand_n == 0:
            return None
        path = env.path_cell_list[b]
        if len(path) == 0:
            return None
        base_len = env.ground_len[b]

        near = set()
        for x, z in path:
            for dx in range(-CANDIDATE_RADIUS, CANDIDATE_RADIUS + 1):
                for dz in range(-CANDIDATE_RADIUS, CANDIDATE_RADIUS + 1):
                    cx, cz = int(x) + dx, int(z) + dz
                    if grid.in_bounds(cx, cz) and env.base_build[b, cz, cx]:
                        near.add((cx, cz))
        if not near:
            return None
        cand = list(near)
        self.rng.shuffle(cand)

        best = None
        best_gain = 0.0
        tried = 0
        slot = int(self.rng.integers(hand_n))
        shape = SHAPES[SHAPE_ORDER[int(env.hand[b, slot])]]
        for cx, cz in cand:
            if tried >= GREEDY_CANDIDATES:
                break
            rot = int(self.rng.integers(4))
            ox, oz = shape.origin_for(cx, cz, rot)
            if grid.check_placement(shape, ox, oz, rot):
                continue
            tried += 1
            target = shape.cells_at(ox, oz, rot)
            saved = grid.cells[target[:, 1], target[:, 0]].copy()
            grid.cells[target[:, 1], target[:, 0]] = 1     # WALL
            grid._walk = None
            length = sum(pf.find(grid, sp).length for sp in grid.spawns)
            grid.cells[target[:, 1], target[:, 0]] = saved
            grid._walk = None
            gain = length - base_len
            if gain > best_gain:
                best_gain = gain
                best = (slot, rot, oz * B.MAX_BOARD + ox)
        return best

    # -- 塔: 経路の近くの土台に置く ----------------------------------
    def best_tower(self, env, b: int, kind: int) -> Optional[int]:
        """経路をいちばん多く射程に収める土台セルを返す。"""
        shape = TOWER_SHAPES[B.TOWER_ORDER[kind]]
        base = env.base_tower[b]
        path = env.path_cell_list[b]
        if len(path) == 0:
            return None
        rng_ = float(env.tables.tw_range[env.env_of[b], kind, 0, 0])

        ys, xs = np.nonzero(base)
        if len(xs) == 0:
            return None
        # 射程内に入る経路セルの数で採点する（＝ちゃんと仕事をする場所）
        d2 = ((xs[:, None] + 0.5 - (path[None, :, 0] + 0.5)) ** 2
              + (ys[:, None] + 0.5 - (path[None, :, 1] + 0.5)) ** 2)
        score = (d2 <= rng_ ** 2).sum(axis=1)
        for idx in np.argsort(-score):
            if score[idx] == 0:
                break
            ox, oz = int(xs[idx]), int(ys[idx])
            if env.grids[b].is_tower_base_for(shape, ox, oz, 0) \
                    and not env.tower_occ[b, shape.cells_at(ox, oz, 0)[:, 1],
                                          shape.cells_at(ox, oz, 0)[:, 0]].any():
                return oz * B.MAX_BOARD + ox
        return None


class GreedyDefenseBot(_GreedyBase):
    """「経路が最も長くなる置き方」＋弓塔を並べ、送りは最小コストのみ。

    守り一辺倒の極。この戦略が強すぎるなら、送りのリターンかリークダメージが
    足りていない（:mod:`balance_analyzer` の対称性チェックで見る）。
    """

    name = "greedy_defense"

    def act(self, env, obs, boards, action) -> None:
        for b in boards:
            b = int(b)
            mask = obs["mask_type"][b]
            bd = env.boards
            # ① 手札があれば経路を伸ばす
            if mask[A_CARD]:
                found = self.best_card(env, b)
                if found is not None:
                    slot, rot, cell = found
                    action["type"][b] = A_CARD
                    action["card"][b] = slot * 4 + rot
                    action["cell"][b] = cell
                    continue
            # ② 弓塔を経路沿いに並べる
            if mask[A_TOWER]:
                cell = self.best_tower(env, b, 0)
                if cell is not None:
                    action["type"][b] = A_TOWER
                    action["tower"][b] = 0 * 4 + 0
                    action["cell"][b] = cell
                    continue
            # ③ 余ったら強化
            if mask[A_UPGRADE]:
                up = np.flatnonzero(obs["mask_unit_upgrade"][b])
                if len(up):
                    action["type"][b] = A_UPGRADE
                    action["unit"][b] = up[int(bd.tw_level[b, up].argmin())]
                    action["spec"][b] = 0
                    continue
            # ④ 守りに使う 1 手ぶんを残した上で、余りだけ送る。
            #    **絶対値の閾値（旧: コイン 150）は置かない。** 強化費は
            #    1 段ごとに 2.6 倍なので、定額の予備費は終盤に実質ゼロになる
            send = np.flatnonzero(obs["mask_send"][b])
            if len(send):
                spare = float(bd.coins[b]) - self._defence_reserve(env, b)
                pick = (self._invest_send(env, b, send, budget=spare)
                        if spare > 0 else None)
                if pick is not None:
                    self._emit_send(env, b, action, pick, budget=spare)


class IncomePushBot(_GreedyBase):
    """序盤から送り最優先でインカムを伸ばす。攻め一辺倒の極。

    「送りスパムが過剰に美味しい」バランスならこれが勝ちすぎる。
    """

    name = "income_push"

    def act(self, env, obs, boards, action) -> None:
        bd = env.boards
        for b in boards:
            b = int(b)
            mask = obs["mask_type"][b]
            send = np.flatnonzero(obs["mask_send"][b])
            # ① 送れるなら送る。インカムが伸びるものを、
            #    ストックの余り具合で選び分ける（:meth:`_invest_send`）
            if len(send):
                pick = self._invest_send(env, b, send)
                if pick is None:                 # インカムが伸びるものが無い
                    pick = self._pressure_send(env, b, send)
                if pick is not None:
                    self._emit_send(env, b, action, pick)
                    continue
            # ② 送れないときだけ最低限の守り
            if mask[A_TOWER] and bd.tw_count[b] < 8:
                cell = self.best_tower(env, b, 0)
                if cell is not None:
                    action["type"][b] = A_TOWER
                    action["tower"][b] = 0
                    action["cell"][b] = cell
                    continue
            if mask[A_CARD] and env.hand_n[b] >= 4:
                found = self.best_card(env, b)
                if found is not None:
                    slot, rot, cell = found
                    action["type"][b] = A_CARD
                    action["card"][b] = slot * 4 + rot
                    action["cell"][b] = cell


# ════════════════════════════════════════════════════════════════════
# 塔の構成だけが違う 3 種  —  「弓スパムが本当に強いのか」の切り分け用
# ════════════════════════════════════════════════════════════════════
#: 2x2 の土台を 1 枚作れたら、経路が何マス伸びたのと同じ価値と見なすか。
#: 経路長と土台作りは競合するので、天秤の重みをここで明示する
PAD_WEIGHT = 6.0

ARROW = B.TOWER_ORDER.index("ARROW")
FROST = B.TOWER_ORDER.index("FROST")
HEXER = B.TOWER_ORDER.index("HEXER")
CANNON = B.TOWER_ORDER.index("CANNON")
TESLA = B.TOWER_ORDER.index("TESLA")
WATCHTOWER = B.TOWER_ORDER.index("WATCHTOWER")
BALLISTA = B.TOWER_ORDER.index("BALLISTA")


def count_pads(base: np.ndarray) -> int:
    """``base`` の中に 2x2 の空き土台がいくつ取れるか（重なりは数える）。"""
    return int((base[:-1, :-1] & base[1:, :-1] & base[:-1, 1:] & base[1:, 1:]).sum())


class _CompositionBot(_GreedyBase):
    """迷路の作り方と経済は共通で、**塔の選び方だけ**が違うボット。

    「弓スパムが強いのはゲームバランスがそうだからか、AI が下手なだけか」を
    切り分けるための比較対象なので、**比べたい 1 点以外を揃える**のが要件。
    カード配置・強化・送りはすべて同じ手順を通り、違うのは

    - ``tower_order``: どの塔をどの順で建てたいか
    - ``want_pads``: カードで 2x2 の土台を作りにいくか

    の 2 つだけにしてある。
    """

    #: 建てたい塔の構成比。``(塔, 重み)`` で指定する。
    #: 「何基ごとに 1 基」ではなく重みにしてあるのは、``(ARROW, 1)`` のような
    #: 「全部これ」と ``(FROST, 3)`` のような「たまにこれ」が混ざると
    #: 前者が常に条件を満たして後者が一度も選ばれないから（実際にそうなった）
    tower_plan: tuple = ((ARROW, 1),)
    #: カードで 2x2 の土台を作りにいくか
    want_pads: bool = False
    #: 大型塔が置けないとき弓に逃げる下限。コインがこの倍数を超えたら妥協する
    fallback_ratio: float = 2.0
    # ---- 経済は :class:`_GreedyBase` の共通ルールを使う ----------------
    # 守るだけ・強化するだけにすると、インカムが 5 のまま伸びず、
    # 誰も削られないまま 20 分が終わる（実際に全部引き分けになった）ので、
    # 「相手のインカムに遅れていたら送りを優先する」だけは全員に入れてある。

    # -- カード: 経路長（＋ 2x2 の土台）がいちばん増える置き方 --------
    def best_card(self, env, b: int) -> Optional[tuple]:
        """:meth:`_GreedyBase.best_card` と同じ探索。採点だけ差し替える。"""
        grid = env.grids[b]
        hand_n = int(env.hand_n[b])
        if hand_n == 0:
            return None
        path = env.path_cell_list[b]
        if len(path) == 0:
            return None
        base_len = env.ground_len[b]
        base_pads = count_pads(env.base_tower[b]) if self.want_pads else 0

        near = set()
        for x, z in path:
            for dx in range(-CANDIDATE_RADIUS, CANDIDATE_RADIUS + 1):
                for dz in range(-CANDIDATE_RADIUS, CANDIDATE_RADIUS + 1):
                    cx, cz = int(x) + dx, int(z) + dz
                    if grid.in_bounds(cx, cz) and env.base_build[b, cz, cx]:
                        near.add((cx, cz))
        if not near:
            return None
        cand = list(near)
        self.rng.shuffle(cand)

        best, best_score, tried = None, 0.0, 0
        slot = int(self.rng.integers(hand_n))
        shape = SHAPES[SHAPE_ORDER[int(env.hand[b, slot])]]
        for cx, cz in cand:
            if tried >= GREEDY_CANDIDATES:
                break
            rot = int(self.rng.integers(4))
            ox, oz = shape.origin_for(cx, cz, rot)
            if grid.check_placement(shape, ox, oz, rot):
                continue
            tried += 1
            target = shape.cells_at(ox, oz, rot)
            saved = grid.cells[target[:, 1], target[:, 0]].copy()
            grid.cells[target[:, 1], target[:, 0]] = 1     # WALL
            grid._walk = None
            score = sum(pf.find(grid, sp).length for sp in grid.spawns) - base_len
            if self.want_pads:
                # 置いたセルは塔の土台になる。2x2 が何枚増えるかを数える
                pads = env.base_tower[b].copy()
                pads[target[:, 1], target[:, 0]] = True
                score += PAD_WEIGHT * (count_pads(pads) - base_pads)
            grid.cells[target[:, 1], target[:, 0]] = saved
            grid._walk = None
            if score > best_score:
                best_score = score
                best = (slot, rot, oz * B.MAX_BOARD + ox)
        return best

    # -- 塔: 構成比にしたがって「次に建てたい塔」を決める --------------
    def next_tower(self, env, b: int) -> Optional[tuple]:
        """``(塔, セル)``。建てられるものが無ければ None。"""
        bd, e = env.boards, env.env_of[b]
        built = int(bd.tw_count[b])
        coins = float(bd.coins[b])
        # 構成比からいちばん足りていない塔を優先する
        total_w = sum(w for _, w in self.tower_plan)
        deficits = []
        for kind, weight in self.tower_plan:
            have = int((bd.tw_kind[b, :built] == kind).sum())
            deficits.append(((built + 1) * weight / total_w - have, kind))
        wish = [kind for _, kind in sorted(deficits, key=lambda d: -d[0])]

        cheapest = min(float(env.tables.tw_cost[e, k]) for k, _ in self.tower_plan)
        for kind in wish:
            if coins < float(env.tables.tw_cost[e, kind]):
                continue
            cell = self.best_tower(env, b, kind)
            if cell is not None:
                return kind, cell
        # 望みの塔が置けない。土台が育つのを待ちたいが、コインが余るなら妥協する
        if coins > cheapest * self.fallback_ratio:
            cell = self.best_tower(env, b, ARROW)
            if cell is not None:
                return ARROW, cell
        return None

    def act(self, env, obs, boards, action) -> None:
        bd = env.boards
        for b in boards:
            b = int(b)
            mask = obs["mask_type"][b]
            send = np.flatnonzero(obs["mask_send"][b])
            # ① 手札があれば迷路を伸ばす（カードは 30 秒に 1 枚しか来ない）
            if mask[A_CARD]:
                found = self.best_card(env, b)
                if found is not None:
                    slot, rot, cell = found
                    action["type"][b] = A_CARD
                    action["card"][b] = slot * 4 + rot
                    action["cell"][b] = cell
                    continue
            # ② 相手のインカムに遅れているうちは送りを投資として優先する。
            #    ここが無いとインカムが 5 のまま止まり、試合が動かない。
            #    **絶対値の目標（旧: 60）は置かない。** 経済は指数で伸びるので、
            #    固定目標は数分で通過して分岐が死ぬ
            if len(send) and bd.income[b] < self._rival_income(env, b):
                pick = self._invest_send(env, b, send)
                if pick is not None:
                    self._emit_send(env, b, action, pick)
                    continue
            # ③ 塔を建てる
            if mask[A_TOWER]:
                found = self.next_tower(env, b)
                if found is not None:
                    kind, cell = found
                    action["type"][b] = A_TOWER
                    action["tower"][b] = kind * 4
                    action["cell"][b] = cell
                    continue
            # ④ 守りの 1 手ぶんを残した余りは送りへ。
            #    全部強化に吸わせると誰も攻めなくなる
            if len(send):
                spare = float(bd.coins[b]) - self._defence_reserve(env, b)
                if spare > 0:
                    pick = (self._pressure_send(env, b, send, budget=spare)
                            if self._should_pressure(env, b, send)
                            else self._invest_send(env, b, send, budget=spare))
                    if pick is not None:
                        self._emit_send(env, b, action, pick, budget=spare)
                        continue
            # ⑤ それでも余ったら強化
            if mask[A_UPGRADE]:
                up = np.flatnonzero(obs["mask_unit_upgrade"][b])
                if len(up):
                    action["type"][b] = A_UPGRADE
                    action["unit"][b] = up[int(bd.tw_level[b, up].argmin())]
                    action["spec"][b] = 0


class ArrowSpamBot(_CompositionBot):
    """弓塔だけを 24 基並べる。**いまの学習済み方策がやっていること**の再現。"""

    name = "arrow_spam"
    tower_plan = ((ARROW, 1),)
    want_pads = False


class BigTowerBot(_CompositionBot):
    """**2x2 の土台を意図して作り**、砲塔・雷塔・監視塔を建てる。

    構成比は「砲塔 2 : 雷塔 2 : 監視塔 1 : 弩塔 1」。監視塔を混ぜてあるのは、
    大型塔でまとめるならバフが乗る密度になるはずだから。
    """

    name = "big_tower"
    tower_plan = ((CANNON, 3), (TESLA, 3), (WATCHTOWER, 1), (BALLISTA, 2))
    want_pads = True


class SupportMixBot(_CompositionBot):
    """弓を主軸に、氷塔と呪詛塔を混ぜる。

    「経路踏破時の累積被ダメージ」で測ると、弓を 8 基置いた盤面の 9 基目は
    **氷塔が 1 コインあたり 1 位**（弓の 1.8 倍）になる。その予測が実際の戦闘でも
    当たるのかを確かめる枠。
    """

    name = "support_mix"
    tower_plan = ((ARROW, 5), (FROST, 3), (HEXER, 1))
    want_pads = False


class PierceMixBot(_CompositionBot):
    """弓 ＋ 弩塔だけ。**1x3 の土台しか要らない**版の「大型塔」。

    ``big_tower`` が強かったとき、それが 2x2（範囲・連鎖）のおかげなのか、
    単に貫通と射程 12 のおかげなのかを分けるための枠。
    1x3 は 2x2 より土台を作りやすいので、こちらで足りるなら話が変わる。
    """

    name = "pierce_mix"
    tower_plan = ((ARROW, 2), (BALLISTA, 3))
    want_pads = False


class SplashMixBot(_CompositionBot):
    """砲塔と雷塔だけ。**2x2 の純粋形**。弓には一切逃げない。"""

    name = "splash_mix"
    tower_plan = ((CANNON, 1), (TESLA, 1))
    want_pads = True
    fallback_ratio = 1e9        # 弓に妥協しない


class ArrowPadsBot(_CompositionBot):
    """弓塔だけ。ただし **カードは 2x2 の土台ができるように置く**。

    ``big_tower`` は塔の構成と迷路の作り方の**両方**が ``arrow_spam`` と違う。
    どちらが効いているのかを分けるための対照群で、迷路だけ ``big_tower`` 側、
    塔だけ ``arrow_spam`` 側にしてある。
    """

    name = "arrow_pads"
    tower_plan = ((ARROW, 1),)
    want_pads = True


BOTS = {
    "random": RandomBot,
    "greedy_defense": GreedyDefenseBot,
    "income_push": IncomePushBot,
    "arrow_spam": ArrowSpamBot,
    "big_tower": BigTowerBot,
    "support_mix": SupportMixBot,
    "pierce_mix": PierceMixBot,
    "splash_mix": SplashMixBot,
    "arrow_pads": ArrowPadsBot,
}


def make_bot(name: str, rng: Optional[np.random.Generator] = None) -> Bot:
    return BOTS[name](rng)
