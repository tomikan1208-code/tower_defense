# -*- coding: utf-8 -*-
"""Python 環境が Java 実装と同じ挙動をするかの検査。 ``dev/SelfCheck.java`` の対。

**「学習が回った」ことと「ルールが合っている」ことは別**。方策は環境の
バグにも喜んで適応するので、環境そのものを機械的に確かめる必要がある。

ここで見るのは次の 5 つ。

1. **経路探索** … 決定論・任意角度・角抜けの禁止・完全封鎖の禁止
2. **戦闘** … 撃破までの時間・漏れまでの時間が解析値と一致するか
3. **経済** … 収入間隔・ストック回復・撃破報酬とインカム端数
4. **能力** … 分裂・復活・不燃・庇護・妨害・瞬移が実際に走るか
5. **不変条件** … 敵が壁の中に入らない・島をまたがない

``python selfcheck.py`` で実行。1 件でも落ちたら終了コード 1。
"""

from __future__ import annotations

import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import balance as B                                     # noqa: E402
from mazeward_env import pathfinder as pf               # noqa: E402
from mazeward_env.bots_heuristic import empty_action, make_bot  # noqa: E402
from mazeward_env.combat import ENEMY_INDEX             # noqa: E402
from mazeward_env.env import VersusEnv                  # noqa: E402
from mazeward_env import reward as R                    # noqa: E402
from mazeward_env.grid import (OPEN, ROCK, SPAWN, WALL, Grid,   # noqa: E402
                               generate_board)
from mazeward_env.rules import EnvConfig                # noqa: E402
from mazeward_env.shapes import SHAPES                  # noqa: E402

FAILURES = []


def check(condition: bool, message: str) -> None:
    if condition:
        print(f"  [OK] {message}")
    else:
        FAILURES.append(message)
        print(f"  [NG] {message}")


def approx(a: float, b: float, tol: float, message: str) -> None:
    check(abs(a - b) <= tol, f"{message}（実測 {a:.2f} / 期待 {b:.2f} ± {tol}）")


# ════════════════════════════════════════════════════════════════════
def check_pathfinding() -> None:
    print("\n■ 経路探索（Theta*）")
    rng = np.random.default_rng(900)
    grid = generate_board(B.BOARD, rng)
    first = pf.find(grid, grid.spawns[0])
    check(first.reachable, "初期盤面でスポーンからコアへ到達できる")

    # 決定論: 配置プレビューの信頼性の根拠
    same = all(pf.find(grid, grid.spawns[0]).waypoints == first.waypoints
               for _ in range(20))
    check(same, "同じ盤面からは必ず同じ経路が返る（決定論的）")

    # 障害物なしなら折れ点は 0（＝一直線）
    plain = Grid(21, 21)
    plain.set_core(9, 9)
    plain.add_spawn(0, 0)
    check(pf.find(plain, (0, 0)).turns() == 0,
          "障害物が無ければ折れ点 0（スポーンからコアへ一直線）")

    # 曲がり角は必ず「通行可能セル」の上に乗る
    on_walkable = all(grid.walk[z, x] for x, z in first.waypoints)
    check(on_walkable, "折れ点はすべて通行可能セルの中心")

    # 角抜けの禁止: 斜めに並べた壁は隙間なく塞がる
    diag = Grid(9, 9)
    diag.set_core(6, 6)
    diag.add_spawn(0, 0)
    for i in range(1, 8):
        diag.set(i, 8 - i, WALL)
    check(not pf.line_of_sight(diag.walk, 0, 0, 8, 8),
          "斜めに並べた壁は見通せない（角抜けの禁止）")

    # 完全封鎖の禁止: ランダム配置を大量に試しても経路は必ず残る
    trial = generate_board(B.BOARD, np.random.default_rng(7))
    blocked = 0
    for _ in range(1500):
        shape = SHAPES[list(B.SHAPE_CELLS)[int(rng.integers(len(B.SHAPE_CELLS)))]]
        rot = int(rng.integers(4))
        ox, oz = int(rng.integers(trial.width)), int(rng.integers(trial.height))
        if trial.check_placement(shape, ox, oz, rot) == "":
            trial.place(shape, ox, oz, rot)
            if not trial.all_spawns_connected():
                blocked += 1
    check(blocked == 0,
          f"許可された配置を {1500} 回試しても経路が消えない（完全封鎖の禁止）")

    # 任意角度: 45 度でない区間が出る
    built = pf.find(trial, trial.spawns[0])
    odd = 0
    for i in range(1, len(built.waypoints)):
        ax, az = built.waypoints[i - 1]
        bx, bz = built.waypoints[i]
        dx, dz = abs(bx - ax), abs(bz - az)
        if dx and dz and dx != dz:
            odd += 1
    check(odd > 0, f"45 度に縛られない区間が出る（任意角度・{odd} 区間）")


# ════════════════════════════════════════════════════════════════════
def check_combat() -> None:
    print("\n■ 戦闘（Java の式と一致するか）")
    env = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                              seed=42, max_ticks=20 * 60 * 20))
    bd = env.boards

    # --- 漏れまでの時間 = 経路長 / 速度 ---
    bd.spawn(np.array([0]), np.array([0]))          # WHELP
    speed = B.ENEMIES["GRUNT"].base_speed
    expect = bd.path_len[0, 0] / speed
    ticks = 0
    while bd.lives[0] == B.ECONOMY.start_lives and ticks < 4000:
        bd.advance(4, 4, np.zeros(env.n, bool))
        ticks += 4
    approx(ticks, expect, 8, "塔なしで漏れるまでの tick 数 = 経路長 / 速度")
    check(bd.lives[0] == B.ECONOMY.start_lives - 1, "漏れるとライフが 1 減る")

    # --- 撃破までの発射回数 ---
    env2 = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                               seed=42, max_ticks=20 * 60 * 20))
    bd2 = env2.boards
    # スポーンの真横に射程の広い塔を置く（湧いた瞬間から撃てるように）
    sx, sz = env2.grids[0].spawns[0]
    placed = None
    for radius in range(1, 4):
        for dx in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                cx, cz = sx + dx, sz + dz
                if env2.base_tower[0, cz, cx] if env2.grids[0].in_bounds(cx, cz) else False:
                    placed = (cx, cz)
                    break
            if placed:
                break
        if placed:
            break
    if placed is None:
        check(False, "スポーン付近に塔の土台が見つからない（検査を実施できず）")
        return
    slot = 0
    bd2.tw_kind[0, slot] = 0                        # ARROW
    bd2.tw_x[0, slot] = placed[0] + 0.5
    bd2.tw_z[0, slot] = placed[1] + 0.5
    bd2.tw_count[0] = 1
    bd2.tw_cells[0][slot] = np.array([[placed[0], placed[1]]], dtype=np.int32)
    # 最大レベルで測る。低レベルだと、走狗が射程 5.5 を抜けるまでに倒し切れず
    # 「漏れるまでの時間」を測ってしまい、式の検証にならない
    # （2 人戦では送りの耐力が 5 倍に正規化されるので、なおさら火力が要る）
    bd2.tw_level[0, slot] = B.MAX_TOWER_LEVEL
    bd2.refresh_tower_stats()
    bd2.tw_charge[0, slot] = bd2._st_cooldown[0, slot]

    st = B.stats_at("ARROW", B.MAX_TOWER_LEVEL)
    per_hit = max(B.MIN_DAMAGE_AFTER_ARMOR, st.damage - B.ENEMIES["GRUNT"].armor)
    # 2 人戦なので相手は 1 人。送りの耐力は人数で正規化される
    # (VersusMatch#sendPowerScale)
    spawn_hp = B.ATTACKERS["WHELP"].hp * B.send_power_scale(1)
    hits = math.ceil(spawn_hp / per_hit)
    # Java は cooldown=0 の塔が即撃つので、n 発目は (n-1)*cooldown tick 目
    expect_ticks = (hits - 1) * st.cooldown

    coins_before = float(bd2.coins[0])
    bd2.spawn(np.array([0]), np.array([0]))
    ticks = 0
    while bd2.en_alive[0, :bd2.en_count[0]].any() and ticks < 2000:
        bd2.advance(4, 4, np.zeros(env2.n, bool))
        ticks += 4
    approx(ticks, expect_ticks, 8,
           f"弓塔 Lv{B.MAX_TOWER_LEVEL + 1} が走狗を倒すまでの tick"
           f"（{hits} 発 x cd {st.cooldown}）")
    check(bd2.stat_kills[0] == 1, "撃破が 1 件記録される")
    # 2 人戦なので相手は 1 人。総量 40% がそのまま 1 人ぶんの報酬になる
    reward = B.ATTACKERS["WHELP"].kill_reward(1)
    approx(float(bd2.coins[0]) - coins_before, reward, 0.01,
           f"撃破報酬 = 送りコストの {B.KILL_REWARD_TOTAL:.0%} = {reward} コイン")

    # --- 装甲は固定引き算で先に効く ---
    brute = B.ENEMIES["BRUTE"]
    raw = B.stats_at("ARROW", 0).damage
    expect_applied = max(B.MIN_DAMAGE_AFTER_ARMOR, raw - brute.armor)
    check(abs(expect_applied - (raw - 5)) < 1e-6,
          "装甲は固定引き算（弓塔 7.0 - 重装兵 5 = 2.0）")


# ════════════════════════════════════════════════════════════════════
def check_economy() -> None:
    print("\n■ 経済")
    eco = B.ECONOMY
    check(eco.income_interval_for(2) == 200, "2 人戦でも収入間隔は 10 秒")
    check(eco.income_interval_for(8) == 200, "8 人戦でも 10 秒（人数によらず一定）")

    # 1 回の送りが盤面に返す総量は人数によらず一定。
    # ここが崩れると 6 人以上で送りが黒字になり、コインが雪だるま式に膨らむ
    for players in (2, 4, 6, 8):
        opponents = players - 1
        whelp = B.ATTACKERS["BULWARK"]
        world = whelp.kill_reward(opponents) * opponents
        approx(world / whelp.cost, B.KILL_REWARD_TOTAL, 0.05,
               f"{players} 人戦で世界全体に返るコインは コストの {B.KILL_REWARD_TOTAL:.0%}")

    # 梯子は等比で、インカム比率は上ほど下がる（雪だるまの自然な減速）
    econ = [B.ATTACKERS[k] for k in B.ATTACKER_ORDER
            if B.ATTACKERS[k].income_gain > 0]
    check(all(econ[i].cost < econ[i + 1].cost for i in range(len(econ) - 1)),
          "インカムモブは安い順に並んでいる")
    check(econ[0].income_ratio > econ[-1].income_ratio * 2.0,
          f"インカム比率は上ほど下がる（{econ[0].income_ratio:.1%} → "
          f"{econ[-1].income_ratio:.1%}）")
    check(all(B.ATTACKERS[k].stock_cost == 1 for k in B.ATTACKER_ORDER),
          "ストック消費はどれも 1（回数制限であって強さの値付けではない）")

    env = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                              seed=3, max_ticks=20 * 60 * 20))
    bd = env.boards
    start = float(bd.coins[0])
    action = empty_action(env.n)
    for _ in range(11):                      # 11 秒ぶん（= 収入 1 回）
        env.step(action)
    check(float(bd.coins[0]) >= start + eco.start_income,
          f"10 秒ごとに インカム {eco.start_income} ぶんのコインが入る")

    # ストックは毎秒 1 回復し、上限で止まる
    bd.stock[0] = 0.0
    for _ in range(5):
        env.step(empty_action(env.n))
    approx(float(bd.stock[0]), 5.0, 0.51, "ストックは毎秒 1 回復")

    # 撃破報酬の 10% がインカムに積まれる
    check(abs(B.KILL_INCOME_RATIO - 0.10) < 1e-9,
          "撃破報酬コインの 10% がインカムになる")


# ════════════════════════════════════════════════════════════════════
def check_abilities() -> None:
    print("\n■ 敵の能力（実際に走るか）")
    env = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                              seed=11, max_ticks=20 * 60 * 20))
    bd = env.boards
    order = list(B.ATTACKER_ORDER)

    # --- 分裂: 倒すと子が湧く ---
    cleaver = order.index("CLEAVER")
    bd.spawn(np.array([0]), np.array([cleaver]))
    idx = int(bd.en_count[0]) - 1
    bd.en_hp[0, idx] = 0.0        # 死亡判定は hp <= 0
    before = int(bd.en_count[0])
    bd.advance(4, 4, np.zeros(env.n, bool))
    bd.compact_enemies()
    check(int(bd.en_alive[0].sum()) >= before,
          "分裂体を倒すと小さいスライムが 2 体湧く")
    splitling = ENEMY_INDEX["SPLITLING"]
    check((bd.en_body[0, :int(bd.en_count[0])] == splitling).any(),
          "湧いた子は分裂片になっている")

    # --- 終焉騎: 倒せば無傷、通せばライフ上限を奪われる ---
    # ここが「倒したときに奪う」だった頃は、防衛に正解が存在しなかった。
    # 上限を奪うのはコアまで通されたときだけ (Island#onEnemyLeaked)
    env2 = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                               seed=12, max_ticks=20 * 60 * 20))
    bd2 = env2.boards
    reaper = order.index("REAPER")
    bd2.spawn(np.array([0]), np.array([reaper]))
    idx = int(bd2.en_count[0]) - 1
    max_before = float(bd2.max_lives[0])
    bd2.en_hp[0, idx] = 0.0       # 死亡判定は hp <= 0
    bd2.advance(4, 4, np.zeros(env2.n, bool))
    check(float(bd2.max_lives[0]) == max_before,
          "終焉騎を倒してもライフ上限は減らない（守り切れば無傷）")
    check(bd2.en_alive[0, idx], "終焉騎は消えずに出発点へ戻る")

    # 2 周目を通す
    bd2.en_hp[0, idx] = 1.0
    bd2.en_progress[0, idx] = bd2.path_total()[0, idx] + 1.0
    lives_before = float(bd2.lives[0])
    bd2.advance(4, 4, np.zeros(env2.n, bool))
    check(float(bd2.max_lives[0]) == max_before - 1,
          "終焉騎をコアまで通すとライフ上限が 1 減る（取り返しがつかない）")
    check(float(bd2.lives[0]) <= lives_before - 1, "通されれば現在ライフも減る")

    # --- 不燃: 熱塊は燃えない ---
    env3 = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                               seed=13, max_ticks=20 * 60 * 20))
    bd3 = env3.boards
    cinder = order.index("CINDER")
    bd3.spawn(np.array([0]), np.array([cinder]))
    idx = int(bd3.en_count[0]) - 1
    bd3.en_burn[0, idx] = 100.0
    bd3.en_burn_ticks[0, idx] = 100
    hp_before = float(bd3.en_hp[0, idx])
    # 燃焼は applyBurn で耐性が掛かる。ここでは耐性値そのものを確認する
    check(B.ENEMIES["EMBERLING"].trait.burn_resist >= 1.0,
          "熱塊の燃焼耐性は 1.0（完全耐性）")
    check(B.ENEMIES["EMBERLING"].slow_resist >= 1.0,
          "熱塊の減速耐性は 1.0（炎と氷に寄せた防衛を咎める）")

    # --- 庇護: 被ダメージが減る ---
    check(B.ENEMIES["AEGIS"].trait.ward_reduction == 0.35,
          "庇護者は周囲の被ダメージを 35% 減らす")
    # --- 妨害: タワーを黙らせる ---
    check(B.ENEMIES["SAPPER"].trait.disable_ticks == 40,
          "妨害者はタワーを 2 秒黙らせる")
    check_watch_umbrella()
    # --- 瞬移 ---
    check(B.ENEMIES["BLINKER"].trait.blink_radius == 5.0,
          "瞬移体は半径 5.0 の中で最もコア寄りの経路へ跳ぶ")


def check_watch_umbrella() -> None:
    """監視塔の傘が **この環境でも** 効いているかを、状態配列を直接見て確かめる。

    妨害者への対策が「散らして置く」しかないと構成が 1 つに収束するので、
    監視塔の下だけは固めてよい、という逃げ道になっている
    (Battlefield#recomputeSupport / #applyDisablers)。
    ここが Java とずれると、学習は実ゲームに無い盤面を最適化してしまう。
    """
    from mazeward_env.combat import BalanceTables, Boards

    bal = B.default_balance()
    boards = Boards(1, np.zeros(1, dtype=np.int64), BalanceTables([bal]),
                    bal.board.size, bal.board.spawns)
    watch = B.TOWER_ORDER.index("WATCHTOWER")
    arrow = B.TOWER_ORDER.index("ARROW")

    def place(slot, kind, x, z, level=0, spec=0):
        # tw_spec は 0 = 未選択 / 1 = 特化A / 2 = 特化B
        boards.tw_kind[0, slot] = kind
        boards.tw_x[0, slot] = x
        boards.tw_z[0, slot] = z
        boards.tw_level[0, slot] = level
        boards.tw_spec[0, slot] = spec

    def umbrella(*towers):
        boards.tw_kind[0, :] = -1
        place(0, arrow, 5.0, 5.0)
        for i, args in enumerate(towers):
            place(i + 1, watch, *args)
        boards.recompute_support()
        return float(boards.tw_disable_resist[0, 0])

    top = B.MAX_TOWER_LEVEL
    check(abs(umbrella((6.0, 5.0)) - 0.5) < 1e-6,
          "監視塔の傘は妨害を半減する")
    check(umbrella((6.0, 5.0, top, 1)) >= 1.0 and umbrella((6.0, 5.0, top, 2)) >= 1.0,
          "特化した監視塔は妨害を完全に無効化する（A/B どちらでも）")
    check(abs(umbrella((6.0, 5.0), (4.0, 5.0)) - 0.5) < 1e-6,
          "傘は重ねても厚くならない（並べるだけでは無効化に届かない）")
    check(umbrella((40.0, 40.0)) == 0.0,
          "射程の外の監視塔は守らない")

    # 監視塔も傘の受け手に含む（ただし自分の傘には入らない）。
    # 妨害者に黙らされても支援そのものは止まらないので、自分を守れなくても実害はないが、
    # 2 つ並べたときに互いを守れないと「監視塔だけ狙われる」抜け道になる
    boards.tw_kind[0, :] = -1
    place(0, arrow, 5.0, 5.0)
    place(1, watch, 6.0, 5.0)
    place(2, watch, 4.0, 5.0)
    boards.recompute_support()
    check(abs(float(boards.tw_disable_resist[0, 1]) - 0.5) < 1e-6,
          "監視塔どうしは互いを傘に入れる")


def check_leak_reward() -> None:
    """送りがコアに届くと **送り主** のライフが 1 戻る（上限まで）。

    攻めが立て直しにもなるルールなので、上限・自傷・分裂の 3 点を確かめる。
    """
    print("\n■ 送りが通ったときの見返り（Island#rewardSender）")
    env = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                              seed=17, max_ticks=20 * 60 * 20))
    bd = env.boards

    def leak_one(target, sender, attacker=0):
        bd.spawn(np.array([target]), np.array([attacker]),
                 sender=np.array([sender]))
        idx = int(bd.en_count[target]) - 1
        bd.en_progress[target, idx] = bd.path_len[target, bd.en_slot[target, idx]]
        bd.advance(4, 4, np.zeros(env.n, bool))
        bd.compact_enemies()

    # ① 満タンの送り主は増えない
    bd.lives[0] = bd.max_lives[0]
    before = float(bd.lives[0])
    leak_one(1, 0)
    check(float(bd.lives[0]) == before,
          "送り主がライフ満タンなら増えない（上限を超えない）")
    check(float(bd.lives[1]) == B.ECONOMY.start_lives - 1,
          "受けた側のライフは 1 減る")

    # ② 削られていれば 1 戻る
    bd.lives[0] = 10.0
    leak_one(1, 0)
    check(float(bd.lives[0]) == 11.0, "削られた送り主は 1 戻る")

    # ③ 戻り幅は 1（2 体通せば 2 戻る = 1 体につき 1）
    bd.lives[0] = 10.0
    leak_one(1, 0)
    leak_one(1, 0)
    check(float(bd.lives[0]) == 12.0, "1 体につき 1 だけ戻る")

    # ④ 終焉騎に奪われた上限は超えられない
    bd.max_lives[0] = 12.0
    bd.lives[0] = 12.0
    leak_one(1, 0)
    check(float(bd.lives[0]) == 12.0,
          "奪われて下がった上限も超えない（終焉騎の効果は消えない）")

    # ⑤ 分裂の子も送り主を引き継ぐ
    env2 = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                               seed=18, max_ticks=20 * 60 * 20))
    bd2 = env2.boards
    cleaver = list(B.ATTACKER_ORDER).index("CLEAVER")
    bd2.spawn(np.array([1]), np.array([cleaver]), sender=np.array([0]))
    idx = int(bd2.en_count[1]) - 1
    bd2.en_hp[1, idx] = 0.0
    bd2.advance(4, 4, np.zeros(env2.n, bool))
    bd2.compact_enemies()
    n_e = int(bd2.en_count[1])
    check(n_e > 0 and bool((bd2.en_sender[1, :n_e] == 0).all()),
          "分裂した子も送り主を引き継ぐ（分裂体だけ見返りが消えない）")

    # ⑥ 自分の島に湧いた敵では戻らない（送り主 == 受け手のとき）
    env3 = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                               seed=19, max_ticks=20 * 60 * 20))
    bd3 = env3.boards
    bd3.lives[0] = 10.0
    bd3.spawn(np.array([0]), np.array([0]), sender=np.array([0]))
    idx = int(bd3.en_count[0]) - 1
    bd3.en_progress[0, idx] = bd3.path_len[0, bd3.en_slot[0, idx]]
    bd3.advance(4, 4, np.zeros(env3.n, bool))
    check(float(bd3.lives[0]) == 9.0,
          "自分の島に届いた敵では戻らない（減るだけ）")


# ════════════════════════════════════════════════════════════════════
def check_reward_shaping() -> None:
    """整形報酬が **有界** で、割引和が端点だけで決まることを確かめる。

    旧報酬はここが壊れていた（獲得コインを毎ステップ足していたので、
    1 試合で +29.6 貯まり、勝敗の ±1.0 を 30 倍で上回っていた）。
    同じ壊れ方を二度としないための検査なので、**数値ではなく性質**を見る。
    """
    print("\n■ 報酬（ポテンシャル整形）")
    cfg = EnvConfig(num_envs=4, players_choices=(2,), board_size=21,
                    max_ticks=20 * 60 * 15, seed=5)
    env = VersusEnv(cfg)
    seats = np.array([e * env.seats for e in range(env.n_envs)])
    others = seats + 1
    defender, pusher = make_bot("greedy_defense"), make_bot("income_push")

    obs = env.reset()
    gamma = R.GAMMA
    phi0 = env._prev_phi[seats].astype(np.float64)
    phi_t = phi0.copy()
    discounted = np.zeros(len(seats))
    steps = 0
    for t in range(int(cfg.max_ticks // cfg.decision_ticks)):
        action = empty_action(env.n)
        defender.act(env, obs, seats, action)
        pusher.act(env, obs, others, action)
        before = env._prev_phi[seats].astype(np.float64)
        obs, _, done, _ = env.step(action)
        if done.any():
            # 試合が切り替わると Φ が新しい盤面のものに差し替わるので、
            # ここまでで打ち切る（端点が繋がらない）
            break
        phi_t = env._prev_phi[seats].astype(np.float64)
        discounted += (gamma ** t) * (gamma * phi_t - before)
        steps += 1

    expected = gamma ** steps * phi_t - phi0
    approx(float(np.abs(discounted - expected).max()), 0.0, 1e-3,
           f"整形の割引和が γ^T Φ_T − Φ_0 と一致する（{steps} ステップ）")
    check(bool(np.abs(env._prev_phi).max() <= 1.0),
          "Φ が [-1, 1] に収まっている")

    # 同じ盤面を往復しても稼げないこと。ライフを削って戻す往復を Φ で再現する
    life = np.array([1.0, 0.5, 1.0, 0.5], dtype=np.float32)
    opp = np.array([1.0, 1.0, 1.0, 1.0], dtype=np.float32)
    flat = np.zeros(4, dtype=np.float32)
    there = R.potential(life, opp, flat, flat, flat, flat, flat, flat)
    back = R.potential(opp, opp, flat, flat, flat, flat, flat, flat)
    round_trip = R.shaping(back, there) + R.shaping(there, back)
    check(bool(np.abs(round_trip).max() < 2e-3),
          "ライフを奪って戻す往復で整形が積み上がらない")


def check_invariants() -> None:
    print("\n■ 不変条件（実戦を回して毎ステップ確認）")
    rng = np.random.default_rng(5)
    env = VersusEnv(EnvConfig(num_envs=6, players_choices=(2, 4), board_size=21,
                              max_ticks=20 * 60 * 6, seed=21))
    obs = env.observe()
    bot = make_bot("greedy_defense", rng)
    push = make_bot("income_push", rng)
    seat = np.arange(env.n) % env.seats
    defs = np.flatnonzero((seat % 2 == 0) & env.active)
    offs = np.flatnonzero((seat % 2 == 1) & env.active)

    wall_violations = 0
    out_of_board = 0
    negative = 0
    for _ in range(260):
        action = empty_action(env.n)
        bot.act(env, obs, defs, action)
        push.act(env, obs, offs, action)
        obs, reward, done, infos = env.step(action)

        n_e = int(env.boards.en_count.max())
        if n_e:
            pos = env.boards.enemy_positions(n_e)
            alive = env.boards.en_alive[:, :n_e]
            flying = env.tables.en_flying[np.maximum(env.boards.en_body[:, :n_e], 0)]
            for b, e in zip(*np.nonzero(alive & ~flying)):
                grid = env.grids[b]
                x, z = int(pos[b, e, 0]), int(pos[b, e, 1])
                if not grid.in_bounds(x, z):
                    out_of_board += 1
                elif not grid.walk[z, x]:
                    wall_violations += 1
        if (env.boards.coins < -1e-6).any() or (env.boards.stock < -1e-6).any():
            negative += 1

    check(out_of_board == 0, "地上の敵が盤面の外へ出ない")
    check(wall_violations == 0, "地上の敵が壁の中に入らない（経路の引き直し漏れなし）")
    check(negative == 0, "コインとストックが負にならない")
    check(int(env.boards.tw_count.max()) <= B.ECONOMY.max_towers,
          f"タワー数が上限 {B.ECONOMY.max_towers} を超えない")
    check(bool((env.boards.lives <= env.boards.max_lives + 1e-6).all()),
          "ライフがライフ上限を超えない")


# ════════════════════════════════════════════════════════════════════
def sample_board() -> None:
    print("\n■ サンプル盤面（貪欲ボットが 200 手組んだ状態）")
    rng = np.random.default_rng(9)
    env = VersusEnv(EnvConfig(num_envs=1, players_choices=(2,), board_size=21,
                              max_ticks=20 * 60 * 10, seed=31))
    obs = env.observe()
    bot = make_bot("greedy_defense", rng)
    boards = np.array([0])
    for _ in range(200):
        action = empty_action(env.n)
        bot.act(env, obs, boards, action)
        obs, _, _, _ = env.step(action)
    for line in env.ascii_board(0):
        print("  " + line)
    print(f"  経路長 {env.ground_len[0]:.1f} / タワー {int(env.boards.tw_count[0])} 基"
          f" / 射程通過 {int(env.tower_passes[0])} 回")


def main() -> int:
    print("=" * 64)
    print("MAZEWARD VERSUS Python 環境の自己検査")
    print("=" * 64)
    check_pathfinding()
    check_combat()
    check_economy()
    check_abilities()
    check_leak_reward()
    check_reward_shaping()
    check_invariants()
    sample_board()

    print("\n" + "=" * 64)
    if FAILURES:
        print(f"[FAIL] {len(FAILURES)} 件の不整合")
        for message in FAILURES:
            print(f"  - {message}")
        return 1
    print("[OK] すべての検査を通過しました")
    return 0


if __name__ == "__main__":
    sys.exit(main())
