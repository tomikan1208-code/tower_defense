# -*- coding: utf-8 -*-
"""ブリッジの観測が、学習の観測と一致するかを機械的に確かめる。

    python mc_parity.py

**この検査が無いと、この方式の意味が半分無くなる。**
:mod:`mazeward_env.mc_snapshot` は「Minecraft から届いた事実」から観測を組む。
学習側（:class:`mazeward_env.env.VersusEnv`）は自分の内部配列から組む。
入口が違うだけで出口は同じでなければならないのに、ずれても例外は出ない
（AI が少し弱くなるだけ）。だから **同じ局面を両方に通して 1 要素ずつ比べる**。

やり方: 学習環境を数十ステップ回して局面を作り、その内部状態を
「Java が送ってくる形」のスナップショットへ書き出してから、
:class:`SnapshotEncoder` に通して ``env.observe()`` と突き合わせる。
ここが通れば、あとは Java が同じスナップショットを作れているかだけになる
（そちらは ``gradle aiSim`` が確かめる）。
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import balance as B                                        # noqa: E402
from mazeward_env import pathfinder as pf                   # noqa: E402
from mazeward_env.bots_heuristic import (GreedyDefenseBot, IncomePushBot,  # noqa: E402
                                         empty_action)
from mazeward_env.env import VersusEnv                      # noqa: E402
from mazeward_env.grid import CORE, OPEN, ROCK, SPAWN, WALL  # noqa: E402
from mazeward_env.mc_snapshot import SnapshotEncoder        # noqa: E402
from mazeward_env.rules import EnvConfig                    # noqa: E402
from mazeward_env.shapes import SHAPE_ORDER                 # noqa: E402

SYMBOL = {OPEN: ".", WALL: "#", ROCK: "R", SPAWN: "S", CORE: "C"}

failures = 0


def check(condition: bool, message: str) -> None:
    global failures
    if not condition:
        failures += 1
        print("  [NG] " + message)


def snapshot_from_env(env: VersusEnv, e: int = 0) -> dict:
    """学習環境の内部状態を「Java が送ってくる形」に書き出す。

    ここで env の配列を直接読んでいるのは検査だからで、
    実運用では同じ形の JSON が Minecraft から届く。
    """
    bd, t = env.boards, env.tables
    seats = int(env.env_players[e])
    size = env.size
    boards = []
    for seat in range(seats):
        b = e * env.seats + seat
        grid = env.grids[b]
        cells = "".join(SYMBOL.get(int(v), "B")
                        for v in grid.cells.reshape(-1))

        paths = []
        for spawn in grid.spawns:
            res = pf.find(grid, spawn)
            if res.reachable:
                paths.append([[int(x), int(z)] for x, z in res.waypoints])

        towers = []
        for slot in range(int(bd.tw_count[b])):
            kind = int(bd.tw_kind[b, slot])
            if kind < 0:
                continue
            level = int(bd.tw_level[b, slot])
            cells_t = bd.tw_cells[b][slot]
            towers.append({
                "kind": B.TOWER_ORDER[kind],
                "level": level,
                "spec": int(bd.tw_spec[b, slot]) - 1,
                "cx": float(bd.tw_x[b, slot]),
                "cz": float(bd.tw_z[b, slot]),
                "range": float(bd._st_range[b, slot]),
                "damage": float(bd._st_damage[b, slot]),
                "cooldown": float(bd._st_cooldown[b, slot]),
                "upCost": (-1 if level >= B.MAX_TOWER_LEVEL
                           else int(t.tw_upgrade[e, kind, level])),
                "cells": [[int(x), int(z)] for x, z in cells_t],
            })

        enemies = []
        n_e = int(bd.en_count[b])
        if n_e:
            pos = bd.enemy_positions(n_e)
            total = np.maximum(bd.path_total()[b, :n_e], 1e-6)
            for i in range(n_e):
                if not bd.en_alive[b, i]:
                    continue
                enemies.append({
                    "x": float(pos[b, i, 0]),
                    "z": float(pos[b, i, 1]),
                    "hp": float(bd.en_hp[b, i]),
                    "maxHp": float(bd.en_max_hp[b, i]),
                    "progress": float(np.clip(bd.en_progress[b, i] / total[i], 0, 1)),
                })

        hand = [SHAPE_ORDER[int(env.hand[b, i])] for i in range(int(env.hand_n[b]))]
        boards.append({
            "seat": seat,
            "name": f"P{seat}",
            "alive": bool(bd.alive[b]),
            "coins": float(bd.coins[b]),
            "income": float(bd.income[b]),
            "stock": float(bd.stock[b]),
            "lives": float(bd.lives[b]),
            "maxLives": float(bd.max_lives[b]),
            "steps": float(env.stat_steps[b]),
            "invalid": float(env.stat_invalid[b]),
            "sends": {
                "d10": float(env.send_decay10[b]),
                "d30": float(env.send_decay30[b]),
                "total": float(env.sends_total[b]),
                "lastCost": float(env.last_send_cost[b]),
                "income": float(env.sent_income[b]),
            },
            "hand": hand,
            "pile": int(env.pile_n[b]),
            "size": size,
            "cells": cells,
            "spawns": [[int(x), int(z)] for x, z in grid.spawns],
            "core": [int(grid.core_center()[0]), int(grid.core_center()[1])],
            "paths": paths,
            "towers": towers,
            "enemies": enemies,
        })

    return {
        "v": 1,
        "match": {
            "tick": int(env.env_tick[e]),
            "prepTicks": int(t.prep_ticks[e]),
            "suddenDeath": int(t.sudden_death[e]),
            "cardInterval": int(t.card_interval[e]),
            "handLimit": int(t.hand_limit[e]),
            "maxTowers": int(t.max_towers[e]),
            "players": seats,
        },
        "boards": boards,
        "ask": list(range(seats)),
    }


def compare(name: str, mine: np.ndarray, theirs: np.ndarray,
            tol: float = 1e-3) -> None:
    if mine.shape != theirs.shape:
        check(False, f"{name}: 形が違う {mine.shape} vs {theirs.shape}")
        return
    diff = np.abs(mine.astype(np.float64) - theirs.astype(np.float64))
    worst = float(diff.max()) if diff.size else 0.0
    if worst <= tol:
        print(f"  [OK] {name}  最大差 {worst:.2e}")
        return
    where = np.unravel_index(int(np.argmax(diff)), diff.shape)
    check(False, f"{name}: 最大差 {worst:.4f} @ {where} "
                 f"(ブリッジ {mine[where]:.4f} / 学習 {theirs[where]:.4f})")


def main() -> int:
    cfg = EnvConfig(num_envs=1, num_players=2, players_choices=(2,),
                    board_size=17, seed=7, max_ticks=20 * 60 * 12)
    env = VersusEnv(cfg)
    rng = np.random.default_rng(3)
    # 片方は守り、片方は送り優先。**敵が湧いていない局面で比べても
    # 敵の 3 チャンネルを 1 度も検査しないことになる**ので、送る側を必ず混ぜる
    bots = [GreedyDefenseBot(rng), IncomePushBot(rng)]

    obs = env.observe()
    seat_boards = [np.array([seat]) for seat in range(2)]
    for step in range(400):
        action = empty_action(env.n)
        for seat, bot in enumerate(bots):
            bot.act(env, obs, seat_boards[seat], action)
        obs, *_ = env.step(action)
        # 壁・塔・敵・送りがひととおり出ている局面で止める
        if step > 120 and env.boards.en_alive[:2].any():
            break

    env_obs = env.observe()
    snap = snapshot_from_env(env)
    seats = len(snap["boards"])
    print(f"== 局面: {seats} 人 / {int(env.env_tick[0])} tick / "
          f"塔 {[len(b['towers']) for b in snap['boards']]} 基 / "
          f"敵 {[len(b['enemies']) for b in snap['boards']]} 体 ==")

    encoder = SnapshotEncoder()
    mine = encoder.encode(snap)

    compare("盤面チャンネル", mine["grid"], env_obs["grid"][:seats])
    compare("スカラー", mine["scalar"], env_obs["scalar"][:seats])
    compare("相手の要約", mine["opponents"], env_obs["opponents"][:seats])
    compare("相手マスク", mine["opp_mask"], env_obs["opp_mask"][:seats])
    for key in ("mask_type", "mask_card", "mask_tower", "mask_send",
                "mask_unit_upgrade", "mask_unit_sell"):
        compare(key, mine[key].astype(np.float32),
                env_obs[key][:seats].astype(np.float32))

    # チャンネルごとの内訳。ずれたときにどこを直せばよいか分かるように出す
    if failures:
        names = ["内側", "歩ける", "置ける", "土台", "岩", "湧き", "コア", "塔",
                 "射程", "経路", "飛行", "敵", "敵HP", "脅威"]
        for c, label in enumerate(names):
            diff = np.abs(mine["grid"][:, c] - env_obs["grid"][:seats, c]).max()
            if diff > 1e-3:
                print(f"    ずれたチャンネル {c} ({label}): 最大差 {diff:.4f}")
        diff = np.abs(mine["scalar"] - env_obs["scalar"][:seats]).max(axis=0)
        for i in np.flatnonzero(diff > 1e-3):
            print(f"    ずれたスカラー {i}: 最大差 {diff[i]:.4f}")

    if failures == 0:
        print("[OK] ブリッジの観測は学習の観測と一致しました")
        return 0
    print(f"[FAIL] 不一致 {failures} 件")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
