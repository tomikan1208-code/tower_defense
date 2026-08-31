# -*- coding: utf-8 -*-
"""形状と回転。 ``core/Shape.java`` / ``core/Rot.java`` / ``core/Shapes.java`` の移植。

回転は 4 方向ぶんを**起動時に一度だけ**計算して固定配列にしておく。
学習中は「その形をその角度で置けるセルの集合」を毎ステップ大量に問い合わせる
ので、そこで回転計算が走ると全体の律速になる。
"""

from __future__ import annotations

from typing import Dict, List, Tuple

import numpy as np

import balance as B

ROTS = 4  # R0 / R90 / R180 / R270


def _rotate(cell: Tuple[int, int], rot: int) -> Tuple[int, int]:
    """原点まわりの右回り。 (Rot#apply)"""
    x, z = cell
    if rot == 0:
        return (x, z)
    if rot == 1:
        return (-z, x)
    if rot == 2:
        return (-x, -z)
    return (z, -x)


def _normalize(cells: List[Tuple[int, int]]) -> List[Tuple[int, int]]:
    """最小 x / 最小 z を 0 に寄せ、(z, x) の辞書順に並べる。 (Shape#normalize)"""
    min_x = min(c[0] for c in cells)
    min_z = min(c[1] for c in cells)
    out = [(c[0] - min_x, c[1] - min_z) for c in cells]
    out.sort(key=lambda c: (c[1], c[0]))
    return out


class Shape:
    """1 つの形。4 方向ぶんのセル配列と外接矩形を持つ。"""

    __slots__ = ("id", "cells", "width", "height", "center_offset", "size")

    def __init__(self, shape_id: str, raw: Tuple[Tuple[int, int], ...]):
        self.id = shape_id
        self.cells: List[np.ndarray] = []
        self.width: List[int] = []
        self.height: List[int] = []
        self.center_offset: List[Tuple[int, int]] = []
        for rot in range(ROTS):
            norm = _normalize([_rotate(c, rot) for c in raw])
            arr = np.array(norm, dtype=np.int32)      # (n, 2) = (x, z)
            self.cells.append(arr)
            w = int(arr[:, 0].max()) + 1
            h = int(arr[:, 1].max()) + 1
            self.width.append(w)
            self.height.append(h)
            # カーソルを形の中心に合わせる (Shape#centerOffset)
            self.center_offset.append((-((w - 1) // 2), -((h - 1) // 2)))
        self.size = len(raw)

    def cells_at(self, origin_x: int, origin_z: int, rot: int) -> np.ndarray:
        """原点を足したワールドセル座標 (n, 2)。"""
        return self.cells[rot] + np.array([origin_x, origin_z], dtype=np.int32)

    def origin_for(self, cursor_x: int, cursor_z: int, rot: int) -> Tuple[int, int]:
        dx, dz = self.center_offset[rot]
        return (cursor_x + dx, cursor_z + dz)

    def ascii(self, rot: int) -> List[str]:
        """対局ビューアに出す小さなプレビュー。"""
        w, h = self.width[rot], self.height[rot]
        filled = [[False] * w for _ in range(h)]
        for x, z in self.cells[rot]:
            filled[z][x] = True
        return ["".join("#" if filled[z][x] else "." for x in range(w))
                for z in range(h)]


SHAPES: Dict[str, Shape] = {
    key: Shape(key, cells) for key, cells in B.SHAPE_CELLS.items()
}

#: 手札に出てくる形の並び順。観測での one-hot 化に使うので固定する
SHAPE_ORDER: Tuple[str, ...] = tuple(B.SHAPE_CELLS.keys())
SHAPE_INDEX: Dict[str, int] = {k: i for i, k in enumerate(SHAPE_ORDER)}

#: タワーの形（TowerKind.shape）。塔の設置マスク計算で引く
TOWER_SHAPES: Dict[str, Shape] = {
    key: SHAPES[t.shape] for key, t in B.TOWERS.items()
}


def footprint_offsets(shape: Shape, rot: int) -> np.ndarray:
    """(n, 2) の相対セル。マスク計算のシフト量として使う。"""
    return shape.cells[rot]
