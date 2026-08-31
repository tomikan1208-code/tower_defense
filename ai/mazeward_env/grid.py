# -*- coding: utf-8 -*-
"""盤面。 ``core/Grid.java`` / ``core/CellType.java`` / ``stage/StageGenerator.java`` の移植。

Java 版は ``CellType`` の enum を並べた 1 次元配列だが、こちらは
**numpy の uint8 配列**にしてある。理由は 2 つ。

1. 「その形をその角度で置けるセル」を求める処理が、シフトして AND を取る
   だけの配列演算になる。行動マスクは毎ステップ・全盤面ぶん必要なので、
   ここが Python ループだと学習が回らない。
2. 観測のチャンネルにそのまま流し込める。

判定の意味は Java と 1 対 1 で対応させてある（``walkable`` / ``tower_base`` /
``buildable`` の 3 つの性質）。
"""

from __future__ import annotations

from collections import deque
from typing import List, Optional, Tuple

import numpy as np

import balance as B
from .shapes import Shape

# ---- セル種別 (CellType.java) --------------------------------------
OPEN = 0     # 空き地。歩ける・カードを置ける
WALL = 1     # プレイヤーが置いた壁。タワーの土台
ROCK = 2     # 初期地形の岩。無料の土台
SPAWN = 3    # 敵の出現地点。歩けるがカードは置けない
CORE = 4     # 拠点。歩ける
BORDER = 5   # 盤外

CELL_COUNT = 6

# 性質テーブル。index = セル種別
WALKABLE = np.array([1, 0, 0, 1, 1, 0], dtype=bool)
TOWER_BASE = np.array([0, 1, 1, 0, 0, 0], dtype=bool)
BUILDABLE = np.array([1, 0, 0, 0, 0, 0], dtype=bool)

#: 8 近傍。展開順は Java と同じ（北→北東→東→…）。経路の見た目が変わるので固定
STEP_DX = (0, 1, 1, 1, 0, -1, -1, -1)
STEP_DZ = (-1, -1, 0, 1, 1, 1, 0, -1)


class Grid:
    """1 つの島の盤面。``cells[z, x]`` で引く（Java の ``z * width + x`` と同じ並び）。"""

    __slots__ = ("width", "height", "cells", "spawns", "core_cells",
                 "_walk", "version")

    def __init__(self, width: int, height: int):
        self.width = width
        self.height = height
        self.cells = np.full((height, width), OPEN, dtype=np.uint8)
        self.spawns: List[Tuple[int, int]] = []
        self.core_cells: List[Tuple[int, int]] = []
        self._walk: Optional[np.ndarray] = None
        #: 盤面が変わるたびに増える。マスクのキャッシュ判定に使う
        self.version = 0

    # ---------------------------------------------------------------- 複製
    def copy(self) -> "Grid":
        g = Grid(self.width, self.height)
        g.cells = self.cells.copy()
        g.spawns = list(self.spawns)
        g.core_cells = list(self.core_cells)
        g.version = self.version
        return g

    # ---------------------------------------------------------------- 参照
    def in_bounds(self, x: int, z: int) -> bool:
        return 0 <= x < self.width and 0 <= z < self.height

    def get(self, x: int, z: int) -> int:
        if not self.in_bounds(x, z):
            return BORDER
        return int(self.cells[z, x])

    def set(self, x: int, z: int, value: int) -> None:
        if self.in_bounds(x, z):
            self.cells[z, x] = value
            self._walk = None
            self.version += 1

    @property
    def walk(self) -> np.ndarray:
        """歩けるかどうかの bool 盤面。set のたびに無効化して作り直す。"""
        if self._walk is None:
            self._walk = WALKABLE[self.cells]
        return self._walk

    def tower_base_mask(self) -> np.ndarray:
        return TOWER_BASE[self.cells]

    def buildable_mask(self) -> np.ndarray:
        return BUILDABLE[self.cells]

    def core_center(self) -> Tuple[float, float]:
        """コア 2x2 の中心。 (Grid#coreCenterX / coreCenterZ)"""
        if not self.core_cells:
            return (self.width / 2.0, self.height / 2.0)
        cx, cz = self.core_cells[0]
        return (cx + 1.0, cz + 1.0)

    def count_open(self) -> int:
        return int((self.cells == OPEN).sum())

    # ---------------------------------------------------------------- 構築
    def add_spawn(self, x: int, z: int) -> None:
        self.cells[z, x] = SPAWN
        self.spawns.append((x, z))
        self._walk = None

    def set_core(self, top_left_x: int, top_left_z: int) -> None:
        """コアは 2x2。 (Grid#setCore)"""
        self.core_cells.clear()
        for dz in range(2):
            for dx in range(2):
                x, z = top_left_x + dx, top_left_z + dz
                self.cells[z, x] = CORE
                self.core_cells.append((x, z))
        self._walk = None

    # ---------------------------------------------------------------- 移動
    def can_step(self, x: int, z: int, dx: int, dz: int) -> bool:
        """斜めは両隣が空いているときだけ。 (Grid#canStep)

        角抜けを禁じているので「斜めに並べた壁は隙間なく塞がる」ことが保証される。
        迷路として成立するかどうかがここ 1 箇所に懸かっている。
        """
        nx, nz = x + dx, z + dz
        if not self.in_bounds(nx, nz) or not self.walk[nz, nx]:
            return False
        if dx != 0 and dz != 0:
            return bool(self.walk[z, x + dx] and self.walk[z + dz, x])
        return True

    def reachable(self, from_x: int, from_z: int,
                  goals: Optional[List[Tuple[int, int]]] = None) -> bool:
        """到達できるかだけを幅優先で調べる軽量版。 (Grid#reachable)

        配置判定は非常に高頻度で呼ばれるうえ、経路の**形**は要らないので
        Theta* ではなく単純な塗りつぶしを使う。
        """
        goals = self.core_cells if goals is None else goals
        if not goals or not self.in_bounds(from_x, from_z):
            return False
        walk = self.walk
        if not walk[from_z, from_x]:
            return False
        w, h = self.width, self.height
        goal_set = {(gx, gz) for gx, gz in goals}
        visited = np.zeros((h, w), dtype=bool)
        visited[from_z, from_x] = True
        queue = deque([(from_x, from_z)])
        while queue:
            x, z = queue.popleft()
            if (x, z) in goal_set:
                return True
            for i in range(8):
                dx, dz = STEP_DX[i], STEP_DZ[i]
                nx, nz = x + dx, z + dz
                if nx < 0 or nz < 0 or nx >= w or nz >= h:
                    continue
                if visited[nz, nx] or not walk[nz, nx]:
                    continue
                if dx and dz and not (walk[z, nx] and walk[nz, x]):
                    continue
                visited[nz, nx] = True
                queue.append((nx, nz))
        return False

    def all_spawns_connected(self) -> bool:
        return all(self.reachable(sx, sz) for sx, sz in self.spawns)

    # ---------------------------------------------------------------- 配置
    def check_placement(self, shape: Shape, ox: int, oz: int, rot: int) -> str:
        """カードを置けるか。置けるなら ``""``。 (Grid#checkPlacement)

        「置いた結果どこかのスポーンからコアへ行けなくなる」配置は弾く。
        完全封鎖のルールを差し替えたくなったらここだけ変えればよい。
        """
        target = shape.cells_at(ox, oz, rot)
        xs, zs = target[:, 0], target[:, 1]
        if (xs < 0).any() or (zs < 0).any() or \
           (xs >= self.width).any() or (zs >= self.height).any():
            return "OUT_OF_BOUNDS"
        if not BUILDABLE[self.cells[zs, xs]].all():
            return "OCCUPIED"

        saved = self.cells[zs, xs].copy()
        self.cells[zs, xs] = WALL
        self._walk = None
        connected = self.all_spawns_connected()
        self.cells[zs, xs] = saved
        self._walk = None
        return "" if connected else "WOULD_BLOCK"

    def place(self, shape: Shape, ox: int, oz: int, rot: int) -> np.ndarray:
        """壁として確定させる。呼ぶ前に :meth:`check_placement` を通すこと。"""
        target = shape.cells_at(ox, oz, rot)
        self.cells[target[:, 1], target[:, 0]] = WALL
        self._walk = None
        self.version += 1
        return target

    def is_tower_base_for(self, shape: Shape, ox: int, oz: int, rot: int) -> bool:
        target = shape.cells_at(ox, oz, rot)
        xs, zs = target[:, 0], target[:, 1]
        if (xs < 0).any() or (zs < 0).any() or \
           (xs >= self.width).any() or (zs >= self.height).any():
            return False
        return bool(TOWER_BASE[self.cells[zs, xs]].all())


# ════════════════════════════════════════════════════════════════════
# 形状マスク（行動マスクの中核）
# ════════════════════════════════════════════════════════════════════
def footprint_ok_mask(base: np.ndarray, shape: Shape, rot: int) -> np.ndarray:
    """``origin`` をそこに置いたとき、形の全セルが ``base`` を満たすか。

    形のセル数ぶんシフトして AND を取るだけ。形は最大 6 セルなので、
    盤面 1 枚につき 6 回の配列演算で済む。**候補セルを 1 つずつ試す実装だと
    ここが O(セル数 x 形のセル数) の Python ループになり、学習が回らない。**

    :param base: (H, W) の bool。「そのセル単体なら OK か」
    :return: (H, W) の bool。True の位置を原点にすれば全セル OK
    """
    h, w = base.shape
    out = np.ones((h, w), dtype=bool)
    for dx, dz in shape.cells[rot]:
        shifted = np.zeros((h, w), dtype=bool)
        # origin (x, z) に置くと (x+dx, z+dz) を占める → base を左上へずらす
        src = base[dz:, dx:] if (dz or dx) else base
        shifted[:h - dz, :w - dx] = src
        out &= shifted
    return out


# ════════════════════════════════════════════════════════════════════
# 盤面生成 (stage/StageGenerator.java)
# ════════════════════════════════════════════════════════════════════
def _edge_cell(edge: int, w: int, h: int, rng) -> Tuple[int, int]:
    if edge == 0:
        return (2 + rng.integers(w - 4), 0)
    if edge == 1:
        return (w - 1, 2 + rng.integers(h - 4))
    if edge == 2:
        return (2 + rng.integers(w - 4), h - 1)
    return (0, 2 + rng.integers(h - 4))


def _too_close_to_key(grid: Grid, x: int, z: int, radius: int) -> bool:
    for sx, sz in grid.spawns:
        if abs(x - sx) + abs(z - sz) <= radius:
            return True
    for cx, cz in grid.core_cells:
        if abs(x - cx) + abs(z - cz) <= radius:
            return True
    return False


def _attempt(cfg: B.BoardConfig, rng) -> Optional[Grid]:
    size = cfg.size
    grid = Grid(size, size)

    core_x = int(np.clip(size // 2 - 1 + rng.integers(3) - 1, 3, size - 5))
    core_z = int(np.clip(size // 2 - 1 + rng.integers(3) - 1, 3, size - 5))
    grid.set_core(core_x, core_z)

    edges = [0, 1, 2, 3]
    rng.shuffle(edges)
    for i in range(cfg.spawns):
        sx, sz = _edge_cell(edges[i], size, size, rng)
        if grid.get(int(sx), int(sz)) != OPEN:
            return None
        grid.add_spawn(int(sx), int(sz))

    # 岩＝無料の壁であり、迷路の起点。小さな塊で撒く
    budget = int(size * size * cfg.rock_ratio)
    placed = 0
    guard = 0
    while placed < budget and guard < budget * 12:
        guard += 1
        bx = 2 + int(rng.integers(size - 4))
        bz = 2 + int(rng.integers(size - 4))
        blob = 1 + int(rng.integers(4))
        for _ in range(blob):
            if placed >= budget:
                break
            cx = bx + int(rng.integers(3)) - 1
            cz = bz + int(rng.integers(3)) - 1
            if not grid.in_bounds(cx, cz) or grid.get(cx, cz) != OPEN:
                continue
            if _too_close_to_key(grid, cx, cz, 2):
                continue
            grid.cells[cz, cx] = ROCK
            grid._walk = None
            placed += 1

    return grid if _validate(grid, cfg) else None


def _validate(grid: Grid, cfg: B.BoardConfig) -> bool:
    if not grid.spawns or not grid.all_spawns_connected():
        return False
    # 経路は任意角度なので長さをマス数で語っても意味がない。
    # 「スポーンとコアが直線距離で十分離れているか」で迷路の余地を保証する
    min_sep = max(grid.width, grid.height) * cfg.min_separation_ratio
    ccx, ccz = grid.core_center()
    for sx, sz in grid.spawns:
        if ((sx + 0.5 - ccx) ** 2 + (sz + 0.5 - ccz) ** 2) ** 0.5 < min_sep:
            return False
    return grid.count_open() / (grid.width * grid.height) >= cfg.min_open_ratio


def _fallback(cfg: B.BoardConfig) -> Grid:
    size = cfg.size
    grid = Grid(size, size)
    grid.set_core(size // 2 - 1, size // 2 - 1)
    grid.add_spawn(2, 0)
    if cfg.spawns > 1:
        grid.add_spawn(size - 3, size - 1)
    return grid


def generate_board(cfg: B.BoardConfig, rng) -> Grid:
    """生成 → 経路検証 → 不正なら再生成。 (StageGenerator#generate)

    対戦は**全員が同一シードの同じ地形**なので、1 度作って全島へ複製する。
    """
    for _ in range(64):
        grid = _attempt(cfg, rng)
        if grid is not None:
            return grid
    return _fallback(cfg)
