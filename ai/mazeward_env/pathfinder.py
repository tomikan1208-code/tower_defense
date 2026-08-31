# -*- coding: utf-8 -*-
"""任意角度経路探索 Theta*。 ``core/PathFinder.java`` の移植。

グリッド A* だと敵がマス目に沿ってカクカク動く。このゲームは
**曲がり角が「障害物に接した通行可能セルの中心」になる任意角度の折れ線**
であることが迷路設計の前提なので、A* ではなく Theta* を使う。
迷路の「長さ」ではなく「形」が効くのはこの性質のおかげ。

移植で守ったこと
----------------
- **展開順を固定**（北→北東→東→…）。順序を変えると同じ盤面から違う経路が出る
- **同値の決着を f → h → 挿入順**にする。プレビューの信頼性の根拠が決定性なので
- **角抜けの禁止**を斜め移動と見通し判定の両方で統一する

ここを numpy でベクトル化しなかったのは、優先度付きキューが本質的に逐次だから。
代わりに「**壁を置いたときだけ**呼ぶ」という Java 側と同じ設計にして、
呼ばれる回数のほうを減らしてある（毎 tick 全敵ぶん引き直すことは絶対にしない）。
"""

from __future__ import annotations

import heapq
import math
from typing import List, Optional, Sequence, Tuple

import numpy as np

from .grid import Grid

DX = (0, 1, 1, 1, 0, -1, -1, -1)
DZ = (-1, -1, 0, 1, 1, 1, 0, -1)
EPSILON = 1e-9


class PathResult:
    """折れ線と実距離。 (core/PathResult.java)

    :param waypoints: 曲がり角のセル座標。実際の通過点はセル中心 (x+0.5, z+0.5)
    :param length: ユークリッド長（マス数ではない）
    """

    __slots__ = ("waypoints", "length", "reachable")

    def __init__(self, waypoints: List[Tuple[int, int]], length: float,
                 reachable: bool):
        self.waypoints = waypoints
        self.length = length
        self.reachable = reachable

    @staticmethod
    def unreachable() -> "PathResult":
        return PathResult([], 0.0, False)

    def turns(self) -> int:
        return max(0, len(self.waypoints) - 2)


_UNREACHABLE = PathResult([], 0.0, False)


def line_of_sight(walk: np.ndarray, x0: int, z0: int, x1: int, z1: int) -> bool:
    """2 セル中心を結ぶ直線が通行可能セルだけを通るか。 (PathFinder#lineOfSight)

    線分が触れるセルをすべて拾う「スーパーカバー」で走査する。ちょうど格子点
    （4 セルの角）を通るときは斜めに角を抜けたことになるので、両隣が空いている
    場合だけ許す。これがないと敵が壁の角にめり込んで見える。
    """
    h, w = walk.shape
    if not (0 <= x0 < w and 0 <= z0 < h and 0 <= x1 < w and 0 <= z1 < h):
        return False
    if not walk[z0, x0] or not walk[z1, x1]:
        return False
    if x0 == x1 and z0 == z1:
        return True

    dx, dz = x1 - x0, z1 - z0
    step_x = (dx > 0) - (dx < 0)
    step_z = (dz > 0) - (dz < 0)
    abs_x, abs_z = abs(dx), abs(dz)

    x, z = x0, z0
    error = abs_x - abs_z
    double_x, double_z = abs_x * 2, abs_z * 2

    remaining = 1 + abs_x + abs_z
    while remaining > 0:
        remaining -= 1
        if not (0 <= x < w and 0 <= z < h) or not walk[z, x]:
            return False
        if error > 0:
            x += step_x
            error -= double_z
        elif error < 0:
            z += step_z
            error += double_x
        else:
            # 格子点をちょうど通過 = 斜めに角を抜ける。両隣が空いていなければ不可。
            # 盤外は Java の Grid#get と同じく BORDER（通行不可）として扱う
            nx, nz = x + step_x, z + step_z
            if not (0 <= nx < w and walk[z, nx]):
                return False
            if not (0 <= nz < h and walk[nz, x]):
                return False
            x += step_x
            z += step_z
            error += double_x - double_z
            remaining -= 1
    return True


def find(grid: Grid, start: Tuple[int, int],
         goals: Optional[Sequence[Tuple[int, int]]] = None) -> PathResult:
    """スポーンからコアまでの折れ線。 (PathFinder#find)"""
    goals = grid.core_cells if goals is None else goals
    sx, sz = start
    if not grid.in_bounds(sx, sz) or not goals:
        return _UNREACHABLE
    walk = grid.walk
    if not walk[sz, sx]:
        return _UNREACHABLE

    w, h = grid.width, grid.height
    cell_count = w * h

    is_goal = np.zeros(cell_count, dtype=bool)
    goal_list: List[Tuple[int, int]] = []
    for gx, gz in goals:
        if grid.in_bounds(gx, gz):
            is_goal[gz * w + gx] = True
            goal_list.append((gx, gz))
    if not goal_list:
        return _UNREACHABLE

    g_score = [math.inf] * cell_count
    parent = [-1] * cell_count
    closed = bytearray(cell_count)

    def heuristic(x: int, z: int) -> float:
        best = math.inf
        for gx, gz in goal_list:
            d = math.hypot(x - gx, z - gz)
            if d < best:
                best = d
        return best

    start_index = sz * w + sx
    g_score[start_index] = 0.0
    parent[start_index] = start_index

    seq = 0
    start_h = heuristic(sx, sz)
    open_heap: List[Tuple[float, float, int, int]] = [(start_h, start_h, seq, start_index)]
    seq += 1

    while open_heap:
        _, _, _, index = heapq.heappop(open_heap)
        if closed[index]:
            continue
        closed[index] = 1

        if is_goal[index]:
            return _rebuild(parent, index, w, g_score[index])

        cx, cz = index % w, index // w
        parent_index = parent[index]
        px, pz = parent_index % w, parent_index // w
        g_parent = g_score[parent_index]
        g_current = g_score[index]

        for d in range(8):
            dx, dz = DX[d], DZ[d]
            nx, nz = cx + dx, cz + dz
            if nx < 0 or nz < 0 or nx >= w or nz >= h or not walk[nz, nx]:
                continue
            if dx and dz and not (walk[cz, nx] and walk[nz, cx]):
                continue
            neighbour = nz * w + nx
            if closed[neighbour]:
                continue

            # Theta* の肝: 親から直接見通せるなら、いま展開しているノードを
            # 飛ばして親から直線でつなぐ。結果が任意角度の直線になる
            if line_of_sight(walk, px, pz, nx, nz):
                tentative = g_parent + math.hypot(nx - px, nz - pz)
                new_parent = parent_index
            else:
                tentative = g_current + math.hypot(dx, dz)
                new_parent = index

            if tentative < g_score[neighbour] - EPSILON:
                g_score[neighbour] = tentative
                parent[neighbour] = new_parent
                hh = heuristic(nx, nz)
                heapq.heappush(open_heap, (tentative + hh, hh, seq, neighbour))
                seq += 1

    return _UNREACHABLE


def _rebuild(parent: List[int], goal_index: int, width: int,
             length: float) -> PathResult:
    reversed_cells: List[Tuple[int, int]] = []
    index = goal_index
    while True:
        reversed_cells.append((index % width, index // width))
        nxt = parent[index]
        if nxt == index or nxt < 0:
            break
        index = nxt
    reversed_cells.reverse()
    return PathResult(reversed_cells, length, True)


def polyline_length(waypoints: Sequence[Tuple[int, int]]) -> float:
    """折れ線の全長。 (PathFinder#polylineLength)"""
    total = 0.0
    for i in range(1, len(waypoints)):
        ax, az = waypoints[i - 1]
        bx, bz = waypoints[i]
        total += math.hypot(bx - ax, bz - az)
    return total


def traversed_cells(waypoints: Sequence[Tuple[int, int]]) -> List[Tuple[int, int]]:
    """折れ線が実際に通過するセル。経路のラスタライズ（観測チャンネル）に使う。
    (PathFinder#traversedCells)"""
    cells: List[Tuple[int, int]] = []
    if not waypoints:
        return cells
    cells.append(tuple(waypoints[0]))
    for i in range(1, len(waypoints)):
        _append_segment(cells, waypoints[i - 1], waypoints[i])
    return cells


def _append_segment(out: List[Tuple[int, int]], a: Tuple[int, int],
                    b: Tuple[int, int]) -> None:
    step_x = (b[0] > a[0]) - (b[0] < a[0])
    step_z = (b[1] > a[1]) - (b[1] < a[1])
    abs_x, abs_z = abs(b[0] - a[0]), abs(b[1] - a[1])
    x, z = a
    error = abs_x - abs_z
    double_x, double_z = abs_x * 2, abs_z * 2
    remaining = 1 + abs_x + abs_z
    while remaining > 0:
        remaining -= 1
        if not out or out[-1] != (x, z):
            out.append((x, z))
        if abs_x == 0 and abs_z == 0:
            return
        if error > 0:
            x += step_x
            error -= double_z
        elif error < 0:
            z += step_z
            error += double_x
        else:
            x += step_x
            z += step_z
            error += double_x - double_z
            remaining -= 1


def to_points(waypoints: Sequence[Tuple[int, int]]) -> np.ndarray:
    """セル座標 → 通過点（セル中心）の (n, 2) float 配列。"""
    if not waypoints:
        return np.zeros((0, 2), dtype=np.float32)
    arr = np.asarray(waypoints, dtype=np.float32) + 0.5
    return arr


def cumulative(points: np.ndarray) -> np.ndarray:
    """折れ線の各点までの累積距離 (n,)。 (EnemyInstance#applyWaypoints)"""
    if len(points) == 0:
        return np.zeros(0, dtype=np.float32)
    seg = np.linalg.norm(np.diff(points, axis=0), axis=1)
    out = np.zeros(len(points), dtype=np.float32)
    out[1:] = np.cumsum(seg)
    return out
