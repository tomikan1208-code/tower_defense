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
    """カード配置と塔配置の共通部分。"""

    def __init__(self, rng: Optional[np.random.Generator] = None):
        self.rng = rng or np.random.default_rng()

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
            # ④ 最低限の送り（インカムは伸ばすが守りは崩さない）
            send = np.flatnonzero(obs["mask_send"][b])
            if len(send) and bd.coins[b] > 150:
                action["type"][b] = A_SEND
                action["send"][b] = send[0]


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
            # ① 送れるなら送る。**インカムが上がるものを優先**（ボスは 0 なので避ける）
            if len(send):
                gain = env.tables.at_income[env.env_of[b], send]
                best = send[np.lexsort((-env.tables.at_cost[env.env_of[b], send], -gain))[0]]
                action["type"][b] = A_SEND
                action["send"][b] = best
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


BOTS = {
    "random": RandomBot,
    "greedy_defense": GreedyDefenseBot,
    "income_push": IncomePushBot,
}


def make_bot(name: str, rng: Optional[np.random.Generator] = None) -> Bot:
    return BOTS[name](rng)
