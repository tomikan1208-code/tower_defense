# -*- coding: utf-8 -*-
"""Theta* と到達判定の numba 版。**Python 版と 1 対 1 で一致させてある。**

なぜここだけ JIT なのか
----------------------
プロファイルで律速だったのは ``line_of_sight`` / ``reachable`` / ``find`` の
3 つだけで、いずれも **優先度付きキューと逐次ループ**が本体。numpy では
ベクトル化できず、GPU にも載らない（:mod:`combat` のような配列演算と違う）。
実測では Theta* が 223us → 7.4us（**30 倍**）、到達判定 BFS が 128us →
2.6us（**49 倍**）で、学習ループ全体では 147ms → 74ms/step になった。

C++ 拡張にしても速度はほぼ同じで、Windows の MSVC と Colab のビルドが
増えるだけなので採らなかった。numba なら ``pip install numba`` だけで済む。

**出力は Python 版と完全一致させること。** 経路の形が 1 マスでも違うと
Java 版とのパリティ検証 (:mod:`mc_parity`) が壊れる。ランダム盤面 240 ケースで
折れ線・長さ・到達可否のすべてが一致することを確認してある
（:mod:`selfcheck` に回帰として入れてある）。

numba が無い環境ではこのモジュールの import が失敗し、:mod:`pathfinder` と
:mod:`grid` が純 Python 版へ自動で落ちる。**遅くなるだけで結果は変わらない。**
"""

import numpy as np
from numba import njit

#: :mod:`pathfinder` / :mod:`grid` がこれを見て差し替える
AVAILABLE = True

DX = np.array((0, 1, 1, 1, 0, -1, -1, -1), dtype=np.int64)
DZ = np.array((-1, -1, 0, 1, 1, 1, 0, -1), dtype=np.int64)
EPS = 1e-9


@njit(cache=True, inline="always")
def _los(walk, x0, z0, x1, z1):
    h, w = walk.shape
    if not (0 <= x0 < w and 0 <= z0 < h and 0 <= x1 < w and 0 <= z1 < h):
        return False
    if not walk[z0, x0] or not walk[z1, x1]:
        return False
    if x0 == x1 and z0 == z1:
        return True
    dx = x1 - x0; dz = z1 - z0
    step_x = (1 if dx > 0 else 0) - (1 if dx < 0 else 0)
    step_z = (1 if dz > 0 else 0) - (1 if dz < 0 else 0)
    ax = abs(dx); az = abs(dz)
    x = x0; z = z0
    error = ax - az
    d2x = ax * 2; d2z = az * 2
    remaining = 1 + ax + az
    while remaining > 0:
        remaining -= 1
        if not (0 <= x < w and 0 <= z < h) or not walk[z, x]:
            return False
        if error > 0:
            x += step_x; error -= d2z
        elif error < 0:
            z += step_z; error += d2x
        else:
            nx = x + step_x; nz = z + step_z
            if not (0 <= nx < w and walk[z, nx]):
                return False
            if not (0 <= nz < h and walk[nz, x]):
                return False
            x += step_x; z += step_z
            error += d2x - d2z
            remaining -= 1
    return True


@njit(cache=True)
def reachable(walk, from_x, from_z, goals):
    h, w = walk.shape
    if not (0 <= from_x < w and 0 <= from_z < h):
        return False
    if not walk[from_z, from_x]:
        return False
    is_goal = np.zeros(h * w, np.uint8)
    for i in range(goals.shape[0]):
        gx = goals[i, 0]; gz = goals[i, 1]
        if 0 <= gx < w and 0 <= gz < h:
            is_goal[gz * w + gx] = 1
    visited = np.zeros(h * w, np.uint8)
    queue = np.empty(h * w, np.int64)
    head = 0; tail = 0
    s = from_z * w + from_x
    visited[s] = 1; queue[tail] = s; tail += 1
    while head < tail:
        idx = queue[head]; head += 1
        x = idx % w; z = idx // w
        if is_goal[idx]:
            return True
        for d in range(8):
            nx = x + DX[d]; nz = z + DZ[d]
            if nx < 0 or nz < 0 or nx >= w or nz >= h:
                continue
            ni = nz * w + nx
            if visited[ni] or not walk[nz, nx]:
                continue
            if DX[d] != 0 and DZ[d] != 0 and not (walk[z, nx] and walk[nz, x]):
                continue
            visited[ni] = 1
            queue[tail] = ni; tail += 1
    return False


@njit(cache=True)
def _push(hf, hh, hs, hi, size, f, hv, seq, idx):
    i = size
    hf[i] = f; hh[i] = hv; hs[i] = seq; hi[i] = idx
    size += 1
    while i > 0:
        p = (i - 1) // 2
        if (hf[p] > hf[i] or (hf[p] == hf[i] and (hh[p] > hh[i] or
            (hh[p] == hh[i] and hs[p] > hs[i])))):
            hf[p], hf[i] = hf[i], hf[p]
            hh[p], hh[i] = hh[i], hh[p]
            hs[p], hs[i] = hs[i], hs[p]
            hi[p], hi[i] = hi[i], hi[p]
            i = p
        else:
            break
    return size


@njit(cache=True)
def _pop(hf, hh, hs, hi, size):
    top = hi[0]
    size -= 1
    hf[0] = hf[size]; hh[0] = hh[size]; hs[0] = hs[size]; hi[0] = hi[size]
    i = 0
    while True:
        l = 2 * i + 1; r = l + 1; m = i
        if l < size and (hf[l] < hf[m] or (hf[l] == hf[m] and (hh[l] < hh[m] or
                         (hh[l] == hh[m] and hs[l] < hs[m])))):
            m = l
        if r < size and (hf[r] < hf[m] or (hf[r] == hf[m] and (hh[r] < hh[m] or
                         (hh[r] == hh[m] and hs[r] < hs[m])))):
            m = r
        if m == i:
            break
        hf[m], hf[i] = hf[i], hf[m]
        hh[m], hh[i] = hh[i], hh[m]
        hs[m], hs[i] = hs[i], hs[m]
        hi[m], hi[i] = hi[i], hi[m]
        i = m
    return top, size


@njit(cache=True)
def find(walk, sx, sz, goals, out_path):
    """戻り値: (経路点数, 長さ, 到達可否)。out_path に (x, z) を書く。"""
    h, w = walk.shape
    n = h * w
    if not (0 <= sx < w and 0 <= sz < h) or goals.shape[0] == 0:
        return 0, 0.0, False
    if not walk[sz, sx]:
        return 0, 0.0, False
    is_goal = np.zeros(n, np.uint8)
    ng = 0
    gxs = np.empty(goals.shape[0], np.int64)
    gzs = np.empty(goals.shape[0], np.int64)
    for i in range(goals.shape[0]):
        gx = goals[i, 0]; gz = goals[i, 1]
        if 0 <= gx < w and 0 <= gz < h:
            is_goal[gz * w + gx] = 1
            gxs[ng] = gx; gzs[ng] = gz; ng += 1
    if ng == 0:
        return 0, 0.0, False

    g_score = np.full(n, np.inf)
    parent = np.full(n, -1, np.int64)
    closed = np.zeros(n, np.uint8)
    cap = 8 * n + 16
    hf = np.empty(cap); hh = np.empty(cap)
    hs = np.empty(cap, np.int64); hi = np.empty(cap, np.int64)
    size = 0

    si = sz * w + sx
    g_score[si] = 0.0
    parent[si] = si
    best = np.inf
    for k in range(ng):
        d = np.sqrt(float((sx - gxs[k])**2 + (sz - gzs[k])**2))
        if d < best:
            best = d
    seq = 0
    size = _push(hf, hh, hs, hi, size, best, best, seq, si); seq += 1

    while size > 0:
        idx, size = _pop(hf, hh, hs, hi, size)
        if closed[idx]:
            continue
        closed[idx] = 1
        if is_goal[idx]:
            # 復元
            cnt = 0
            j = idx
            while True:
                out_path[cnt, 0] = j % w
                out_path[cnt, 1] = j // w
                cnt += 1
                nx = parent[j]
                if nx == j or nx < 0:
                    break
                j = nx
            for a in range(cnt // 2):
                b = cnt - 1 - a
                t0 = out_path[a, 0]; t1 = out_path[a, 1]
                out_path[a, 0] = out_path[b, 0]; out_path[a, 1] = out_path[b, 1]
                out_path[b, 0] = t0; out_path[b, 1] = t1
            return cnt, g_score[idx], True

        cx = idx % w; cz = idx // w
        pi = parent[idx]
        px = pi % w; pz = pi // w
        gp = g_score[pi]; gc = g_score[idx]
        for d in range(8):
            nx = cx + DX[d]; nz = cz + DZ[d]
            if nx < 0 or nz < 0 or nx >= w or nz >= h or not walk[nz, nx]:
                continue
            if DX[d] != 0 and DZ[d] != 0 and not (walk[cz, nx] and walk[nz, cx]):
                continue
            nb = nz * w + nx
            if closed[nb]:
                continue
            if _los(walk, px, pz, nx, nz):
                tent = gp + np.sqrt(float((nx - px)**2 + (nz - pz)**2))
                npar = pi
            else:
                tent = gc + np.sqrt(float(DX[d]**2 + DZ[d]**2))
                npar = idx
            if tent < g_score[nb] - EPS:
                g_score[nb] = tent
                parent[nb] = npar
                bh = np.inf
                for k in range(ng):
                    dd = np.sqrt(float((nx - gxs[k])**2 + (nz - gzs[k])**2))
                    if dd < bh:
                        bh = dd
                if size + 1 >= cap:
                    continue
                size = _push(hf, hh, hs, hi, size, tent + bh, bh, seq, nb); seq += 1
    return 0, 0.0, False


# ════════════════════════════════════════════════════════════════════
# 呼び出し側のラッパ（Grid / PathResult を知っているのはここだけ）
# ════════════════════════════════════════════════════════════════════
#: 復元用の作業領域。盤面は最大 27x27 なので経路長もそれ以下
_PATH_BUF = np.zeros((32 * 32, 2), dtype=np.int64)


def _goal_array(grid, goals):
    src = grid.core_cells if goals is None else goals
    if len(src) == 0:
        return None
    return np.asarray(src, dtype=np.int64)


def find_fast(grid, start, goals, unreachable, make_result):
    """:func:`pathfinder.find` の中身。結果の型は呼び出し側から受け取る。"""
    gl = _goal_array(grid, goals)
    if gl is None:
        return unreachable
    count, length, ok = find(grid.walk, int(start[0]), int(start[1]),
                             gl, _PATH_BUF)
    if not ok:
        return unreachable
    pts = _PATH_BUF[:count]
    return make_result([(int(x), int(z)) for x, z in pts], float(length), True)


def reachable_fast(grid, from_x, from_z, goals):
    """:meth:`grid.Grid.reachable` の中身。"""
    gl = _goal_array(grid, goals)
    if gl is None:
        return False
    return bool(reachable(grid.walk, int(from_x), int(from_z), gl))
