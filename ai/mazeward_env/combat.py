# -*- coding: utf-8 -*-
"""戦闘のベクトル化シミュレータ。 ``stage/Battlefield.java`` ＋
``enemy/EnemyInstance.java`` ＋ ``tower/TowerInstance.java`` の移植。

設計の要
--------
**盤面を 1 つずつ回さない。** ``B = 環境数 x 人数`` 個の島を 1 本の numpy 配列に
詰めて、全部まとめて 1 ステップ進める。塔 x 敵の距離行列を一括で作るのが要で、
ここを Python ループにすると学習が回らない。

**弾を持たない。** 実ゲームの弾は見た目だけで当たり判定を持たないので
（``Shot.java``）、学習環境からは完全に外してある。射程・命中の扱いは
射程判定そのもので保たれる。

**経路の引き直しは壁を置いたときだけ。** Java と同じ設計。毎 tick 全敵ぶん
Theta* を引き直すことは絶対にしない。

実ゲームとの差（意図的な近似。すべてここに列挙する）
--------------------------------------------------
1. 1 サブステップ（``COMBAT_DT`` tick）の中で同じ塔が複数回撃つ場合、
   狙いは 1 回しか選び直さない。**DPS は完全に保たれる**（撃てる回数を
   ``floor(蓄積 / クールダウン)`` で数え、余りを次へ繰り越す）。
   最速の塔でも 1 サブステップ 2 発までなので、過剰攻撃の差は無視できる。
2. 壁を置いたあとの敵の再経路は、Java が「敵の居るセルから Theta* を引き直す」
   のに対し、こちらは **新しい折れ線へ最近点で乗せ換える**（:meth:`reanchor`）。
   見た目も所要時間もほぼ同じで、敵 1 体ごとの Theta* が消える。
3. 敵のオーラ（回復・庇護）と妨害は、20 tick ごとの離散適用ではなく
   毎サブステップの連続適用にしてある。1 秒あたりの回復量は完全に同じ。
4. 瞬移の跳び先探索は 0.5 ブロック刻みではなく 64 分割。
   経路の残りが 32 ブロック以下なら Java より細かい。
"""

from __future__ import annotations

from typing import List, Optional, Sequence, Tuple

import numpy as np

import balance as B
from . import pathfinder as pf
from .rules import MAX_WAYPOINTS

# ---- 攻撃方式 / 狙い方の数値化 --------------------------------------
STYLE_SINGLE, STYLE_SPLASH, STYLE_CHAIN, STYLE_PIERCE = 0, 1, 2, 3
STYLE_AURA, STYLE_BANISH, STYLE_CURSE, STYLE_SUPPORT = 4, 5, 6, 7
STYLE_ID = {"SINGLE": 0, "SPLASH": 1, "CHAIN": 2, "PIERCE": 3,
            "AURA": 4, "BANISH": 5, "CURSE": 6, "SUPPORT": 7}

TGT_FIRST, TGT_UNAFFECTED, TGT_TOUGHEST = 0, 1, 2
TGT_DENSEST, TGT_FARTHEST, TGT_NONE = 3, 4, 5
TARGET_ID = {"FIRST": 0, "UNAFFECTED": 1, "TOUGHEST": 2,
             "DENSEST": 3, "FARTHEST": 4, "NONE": 5}

ENEMY_ORDER: Tuple[str, ...] = tuple(B.ENEMIES.keys())
ENEMY_INDEX = {k: i for i, k in enumerate(ENEMY_ORDER)}
SPLITLING_ID = ENEMY_INDEX["SPLITLING"]

N_TOWER = len(B.TOWER_ORDER)
N_ATTACK = len(B.ATTACKER_ORDER)
N_ENEMY = len(ENEMY_ORDER)
N_LEVEL = B.MAX_TOWER_LEVEL + 1
N_SPEC = 3  # 0 = 未選択, 1 = 特化A, 2 = 特化B

#: 1 サブステップで同じ塔が撃てる最大回数（暴走防止の上限）
MAX_SHOTS_PER_SUBSTEP = 4
#: 連鎖の最大跳躍数（拡散 Lv3 で 3+1+3 = 7）
MAX_CHAIN_HOPS = 8
#: 瞬移の跳び先を探すときの分割数
BLINK_SAMPLES = 64


def alloc_slots(counts: np.ndarray, boards: np.ndarray) -> np.ndarray:
    """同じ島が複数回出てきても衝突しない書き込み先を返す。

    送りは**生存者全員に同時に飛ぶ**ので、1 ステップの中で同じ島が何度も
    受け取る。単に ``counts[boards]`` を使うと全員が同じ枠へ上書きされて
    敵が消える（実際に踏んだ）。重複の順位ぶんだけずらす。
    """
    if len(boards) == 0:
        return np.zeros(0, dtype=np.int64)
    order = np.argsort(boards, kind="stable")
    sorted_b = boards[order]
    positions = np.arange(len(boards))
    first = np.empty(len(boards), dtype=bool)
    first[0] = True
    first[1:] = sorted_b[1:] != sorted_b[:-1]
    start = np.maximum.accumulate(np.where(first, positions, -1))
    rank_sorted = positions - start
    rank = np.empty(len(boards), dtype=np.int64)
    rank[order] = rank_sorted
    return counts[boards] + rank


# ════════════════════════════════════════════════════════════════════
# バランス表
# ════════════════════════════════════════════════════════════════════
class BalanceTables:
    """:class:`balance.Balance` を numpy のルックアップ表へ変換したもの。

    ドメインランダム化で環境ごとに数値が違うので、戦闘ループから
    ``balance`` モジュールを直接読むことはできない。エピソード開始時に
    一度だけ表を作り、以降は gather するだけにする。
    """

    def __init__(self, balances: Sequence[B.Balance]):
        n = len(balances)
        self.n_envs = n
        shape = (n, N_TOWER, N_LEVEL, N_SPEC)
        f = lambda: np.zeros(shape, dtype=np.float32)   # noqa: E731

        self.tw_damage, self.tw_range = f(), f()
        self.tw_cooldown = np.ones(shape, dtype=np.float32)
        self.tw_splash, self.tw_slow, self.tw_slow_ticks = f(), f(), f()
        self.tw_burn, self.tw_burn_ticks = f(), f()
        self.tw_banish, self.tw_vuln, self.tw_vuln_ticks = f(), f(), f()
        self.tw_boost_dmg, self.tw_boost_rate = f(), f()
        self.tw_resist = f()
        self.tw_chain = np.zeros(shape, dtype=np.int32)

        self.tw_cost = np.zeros((n, N_TOWER), dtype=np.int32)
        self.tw_upgrade = np.zeros((n, N_TOWER, N_LEVEL), dtype=np.int32)
        self.tw_invested = np.zeros((n, N_TOWER, N_LEVEL), dtype=np.int32)

        for e, bal in enumerate(balances):
            for k, key in enumerate(B.TOWER_ORDER):
                self.tw_cost[e, k] = bal.towers[key].base_cost
                total = bal.towers[key].base_cost
                for lv in range(N_LEVEL):
                    self.tw_invested[e, k, lv] = total
                    if lv < B.MAX_TOWER_LEVEL:
                        cost = bal.upgrade_cost(key, lv)
                        self.tw_upgrade[e, k, lv] = cost
                        total += cost
                for lv in range(N_LEVEL):
                    for sp in range(N_SPEC):
                        st = bal.tower_stats(key, lv, None if sp == 0 else sp - 1)
                        self.tw_damage[e, k, lv, sp] = st.damage
                        self.tw_range[e, k, lv, sp] = st.range
                        self.tw_cooldown[e, k, lv, sp] = st.cooldown
                        self.tw_splash[e, k, lv, sp] = st.splash_radius
                        self.tw_chain[e, k, lv, sp] = st.chain_targets
                        self.tw_slow[e, k, lv, sp] = st.slow_factor
                        self.tw_slow_ticks[e, k, lv, sp] = st.slow_ticks
                        self.tw_burn[e, k, lv, sp] = st.burn_dps
                        self.tw_burn_ticks[e, k, lv, sp] = st.burn_ticks
                        self.tw_banish[e, k, lv, sp] = st.effect.banish_targets
                        self.tw_vuln[e, k, lv, sp] = st.effect.vulnerability
                        self.tw_vuln_ticks[e, k, lv, sp] = st.effect.vulnerability_ticks
                        self.tw_boost_dmg[e, k, lv, sp] = st.effect.boost_damage
                        self.tw_boost_rate[e, k, lv, sp] = st.effect.boost_rate
                        self.tw_resist[e, k, lv, sp] = st.effect.disable_resist

        self.tw_style = np.array([STYLE_ID[B.TOWERS[k].style]
                                  for k in B.TOWER_ORDER], dtype=np.int32)
        self.tw_target = np.array([TARGET_ID[B.TOWERS[k].targeting]
                                   for k in B.TOWER_ORDER], dtype=np.int32)
        self.tw_passive = self.tw_style == STYLE_SUPPORT

        # --- 送りモンスター ---
        self.at_cost = np.zeros((n, N_ATTACK), dtype=np.int32)
        self.at_income = np.zeros((n, N_ATTACK), dtype=np.int32)
        self.at_stock = np.zeros((n, N_ATTACK), dtype=np.int32)
        self.at_unlock = np.zeros((n, N_ATTACK), dtype=np.int32)
        self.at_hp = np.zeros((n, N_ATTACK), dtype=np.float32)
        # **報酬は総量で持つ。** 1 体あたりの額は「何人に湧いたか」で割って決まるので、
        # ここで確定させられない (AttackerKind#killReward)
        self.at_reward_total = np.zeros((n, N_ATTACK), dtype=np.float32)
        for e, bal in enumerate(balances):
            for a, key in enumerate(B.ATTACKER_ORDER):
                d = bal.attackers[key]
                self.at_cost[e, a] = d.cost
                self.at_income[e, a] = d.income_gain
                self.at_stock[e, a] = d.stock_cost
                self.at_unlock[e, a] = d.unlock_income
                self.at_hp[e, a] = d.hp
                self.at_reward_total[e, a] = d.cost * B.KILL_REWARD_TOTAL
        self.at_body = np.array([ENEMY_INDEX[B.ATTACKERS[k].body]
                                 for k in B.ATTACKER_ORDER], dtype=np.int32)

        # --- 敵の体 ---
        self.en_speed = np.zeros((n, N_ENEMY), dtype=np.float32)
        self.en_armor = np.zeros((n, N_ENEMY), dtype=np.float32)
        self.en_slow_resist = np.zeros((n, N_ENEMY), dtype=np.float32)
        self.en_heal = np.zeros((n, N_ENEMY), dtype=np.float32)
        for e, bal in enumerate(balances):
            for i, key in enumerate(ENEMY_ORDER):
                d = bal.enemies[key]
                self.en_speed[e, i] = d.base_speed
                self.en_armor[e, i] = d.armor
                self.en_slow_resist[e, i] = d.slow_resist
                self.en_heal[e, i] = d.heal_per_second

        def trait(fn, dtype=np.float32):
            return np.array([fn(B.ENEMIES[k]) for k in ENEMY_ORDER], dtype=dtype)

        self.en_flying = trait(lambda d: d.flying, bool)
        self.en_boss = trait(lambda d: d.boss, bool)
        self.en_disable_r = trait(lambda d: d.trait.disable_radius)
        self.en_disable_t = trait(lambda d: d.trait.disable_ticks)
        self.en_blink_r = trait(lambda d: d.trait.blink_radius)
        self.en_blink_cd = trait(lambda d: d.trait.blink_cooldown)
        self.en_ward_r = trait(lambda d: d.trait.ward_radius)
        self.en_ward_red = trait(lambda d: d.trait.ward_reduction)
        self.en_split = trait(lambda d: d.trait.split_count, np.int32)
        self.en_burn_resist = trait(lambda d: d.trait.burn_resist)
        self.en_revives = trait(lambda d: d.trait.revives, np.int32)
        # **コアまで通されたときにだけ**ライフ上限を奪う体 (balance.MAX_LIFE_STEALERS)
        self.en_steals_max_life = np.array(
            [B.ATTACKERS[k].body for k in B.MAX_LIFE_STEALERS
             if k in B.ATTACKERS], dtype=object)
        self.en_steals_max_life = np.array(
            [k in set(self.en_steals_max_life) for k in ENEMY_ORDER], dtype=bool)

        # --- 経済 ---
        def eco(fn, dtype=np.int32):
            return np.array([fn(b.economy) for b in balances], dtype=dtype)

        self.start_coins = eco(lambda c: c.start_coins)
        self.start_income = eco(lambda c: c.start_income)
        self.start_lives = eco(lambda c: c.start_lives)
        self.max_stock = eco(lambda c: c.max_stock)
        self.max_towers = eco(lambda c: c.max_towers)
        self.prep_ticks = eco(lambda c: c.prep_ticks)
        self.income_interval = eco(lambda c: c.income_interval)
        self.stock_interval = eco(lambda c: c.stock_interval)
        self.sudden_death = eco(lambda c: c.sudden_death_ticks)
        self.card_interval = eco(lambda c: c.card_interval)
        self.hand_limit = eco(lambda c: c.hand_limit)
        self.start_hand = eco(lambda c: c.start_hand)


# ════════════════════════════════════════════════════════════════════
# 島の集合
# ════════════════════════════════════════════════════════════════════
class Boards:
    """``n_boards`` 個の島の全状態と戦闘ループ。

    「島」＝ 1 人ぶんの盤面。``board = env * players + seat`` の順に並ぶ。
    """

    def __init__(self, n_boards: int, env_of_board: np.ndarray,
                 tables: BalanceTables, board_size: int, n_spawns: int,
                 max_enemies: int = B.MAX_ENEMIES):
        self.n = n_boards
        self.env_of = env_of_board.astype(np.int64)
        self.tables = tables
        self.size = board_size
        self.n_spawns = n_spawns
        self.max_enemies = max_enemies
        self.max_towers = int(tables.max_towers.max())

        T, E = self.max_towers, max_enemies
        z = lambda *s: np.zeros(s, dtype=np.float32)   # noqa: E731
        zi = lambda *s: np.zeros(s, dtype=np.int32)    # noqa: E731

        # --- 塔 ---
        self.tw_kind = np.full((n_boards, T), -1, dtype=np.int32)
        self.tw_level = zi(n_boards, T)
        self.tw_spec = zi(n_boards, T)         # 0 = 未選択, 1/2 = 特化
        self.tw_x, self.tw_z = z(n_boards, T), z(n_boards, T)
        self.tw_invested = zi(n_boards, T)
        self.tw_charge = z(n_boards, T)        # クールダウンの蓄積
        self.tw_disabled = z(n_boards, T)      # 妨害されている残り tick
        self.tw_boost_dmg = z(n_boards, T)
        self.tw_boost_rate = z(n_boards, T)
        self.tw_disable_resist = z(n_boards, T)   # 監視塔の傘（妨害の軽減率）
        self.tw_count = zi(n_boards)
        #: 塔が乗っているセル（売却で戻すため）。可変長なので Python 側で持つ
        self.tw_cells: List[List[Optional[np.ndarray]]] = [
            [None] * T for _ in range(n_boards)]

        # 実効性能のキャッシュ（塔が変わったときだけ作り直す）
        self._st_damage = z(n_boards, T)
        self._st_range = z(n_boards, T)
        self._st_cooldown = np.ones((n_boards, T), dtype=np.float32)
        self._st_splash = z(n_boards, T)
        self._st_chain = zi(n_boards, T)
        self._st_slow = z(n_boards, T)
        self._st_slow_ticks = z(n_boards, T)
        self._st_burn = z(n_boards, T)
        self._st_burn_ticks = z(n_boards, T)
        self._st_banish = z(n_boards, T)
        self._st_vuln = z(n_boards, T)
        self._st_vuln_ticks = z(n_boards, T)
        self._st_style = np.full((n_boards, T), -1, dtype=np.int32)
        self._st_target = np.full((n_boards, T), TGT_NONE, dtype=np.int32)

        # --- 湧かせ待ち行列（まとめ送り用）---
        #
        # **まとめて送られたぶんを同座標に一度に湧かせてはいけない。**
        # 範囲攻撃と連鎖が 1 塊に当たるので、実際より柔らかく見える。
        # 戦闘サブステップ 1 回（COMBAT_DT = 4 tick = 0.2 秒）につき
        # 1 体ずつ出すことで、人間が送りメニューを連打したのと同じ間隔になる。
        #
        # 幅は「1 回の意思決定で同じ島へ送ってくる相手の最大数」＝ 席数 - 1。
        # 余裕を見て MAX_PLAYERS 分だけ確保する（1 島あたり 8 スロット）。
        self.pend_kind = np.full((n_boards, B.MAX_PLAYERS), -1, dtype=np.int32)
        self.pend_sender = np.full((n_boards, B.MAX_PLAYERS), -1, dtype=np.int32)
        self.pend_left = np.zeros((n_boards, B.MAX_PLAYERS), dtype=np.int32)
        self.pend_spawn = np.zeros((n_boards, B.MAX_PLAYERS), dtype=np.int32)

        # --- 敵 ---
        self.en_alive = np.zeros((n_boards, E), dtype=bool)
        self.en_body = np.full((n_boards, E), -1, dtype=np.int32)
        self.en_attacker = np.full((n_boards, E), -1, dtype=np.int32)
        self.en_hp = z(n_boards, E)
        self.en_max_hp = np.ones((n_boards, E), dtype=np.float32)
        self.en_progress = z(n_boards, E)
        self.en_reward = zi(n_boards, E)
        self.en_slot = zi(n_boards, E)         # 経路スロット = spawn + S*flying
        self.en_slow_ticks, self.en_slow = z(n_boards, E), z(n_boards, E)
        self.en_burn_ticks, self.en_burn = z(n_boards, E), z(n_boards, E)
        #: 燃焼を付けた塔の種類。継続ダメージの功績をそこへ返すため
        self.en_burn_src = np.full((n_boards, E), -1, dtype=np.int32)
        #: 最後に当てた塔の種類。撃破をどの種類の手柄にするか
        self.en_last_hit_kind = np.full((n_boards, E), -1, dtype=np.int32)
        self.en_vuln_ticks, self.en_vuln = z(n_boards, E), z(n_boards, E)
        self.en_ward_ticks, self.en_ward = z(n_boards, E), z(n_boards, E)
        self.en_blink_cd = z(n_boards, E)
        self.en_revives = zi(n_boards, E)
        self.en_hit = np.zeros((n_boards, E), dtype=bool)
        #: **誰が送った敵か**（島の番号）。送られた敵でなければ -1。
        #: コアに届いたとき送り主のライフを 1 戻すので、精算に要る
        self.en_sender = np.full((n_boards, E), -1, dtype=np.int32)
        self.en_count = zi(n_boards)

        # --- 経路: スロット 0..S-1 が地上、S..2S-1 が飛行の直線 ---
        slots = n_spawns * 2
        self.path_pts = z(n_boards, slots, MAX_WAYPOINTS, 2)
        self.path_cum = z(n_boards, slots, MAX_WAYPOINTS)
        self.path_n = zi(n_boards, slots)
        self.path_len = z(n_boards, slots)

        # --- 資源 ---
        self.coins = z(n_boards)
        self.income = z(n_boards)
        self.lives = z(n_boards)
        self.max_lives = z(n_boards)
        self.stock = z(n_boards)
        self.income_progress = z(n_boards)
        self.alive = np.ones(n_boards, dtype=bool)

        # --- 統計（報酬と指標のため） ---
        self.stat_leaks = zi(n_boards)
        self.stat_kills = zi(n_boards)
        self.stat_coins_earned = z(n_boards)
        #: 敵がいるのに射程内へ来なかった塔の延べ数（敵がいない時間は数えない）
        self.stat_idle_slots = z(n_boards)
        #: 上の分母。敵がいるサブステップの塔の延べ数
        self.stat_tower_slots = z(n_boards)
        self.stat_max_life_lost = z(n_boards)
        self.stat_life_regained = z(n_boards)  # 送りが通って取り戻したライフ
        self.stat_breakthrough = z(n_boards)   # 送りが相手のコアに届いた回数
        #: この島に湧かせようとした敵の数と、**上限で捨てた**数。
        #: 捨てた敵は漏れないので、数えないと守りが強く見える
        self.stat_spawn_asked = z(n_boards)
        self.stat_spawn_dropped = z(n_boards)
        #: **塔の種類ごとの与ダメージ功績。** バランス判断の本命。
        #: 範囲・連鎖・貫通・送還・燃焼の巻き添えぶんも全部ここに入る。
        #: 監視塔のバフと呪詛塔のデバフで増えたぶんは、撃った塔ではなく
        #: **バフ / デバフを出した塔の功績**として振り分ける
        self.stat_damage_by_kind = np.zeros((n_boards, N_TOWER), dtype=np.float32)
        #: 種類ごとの撃破数（とどめを刺した塔）
        self.stat_kills_by_kind = np.zeros((n_boards, N_TOWER), dtype=np.float32)

    # ---------------------------------------------------------------- 経路
    def set_path(self, board: int, slot: int,
                 waypoints: Sequence[Tuple[int, int]]) -> None:
        """折れ線を書き込む。ウェイポイントが多すぎる場合は間引く。"""
        pts = pf.to_points(waypoints)
        if len(pts) > MAX_WAYPOINTS:
            keep = np.linspace(0, len(pts) - 1, MAX_WAYPOINTS).astype(int)
            pts = pts[keep]
        n = len(pts)
        self.path_n[board, slot] = n
        if n == 0:
            self.path_len[board, slot] = 0.0
            return
        cum = pf.cumulative(pts)
        self.path_pts[board, slot, :n] = pts
        self.path_cum[board, slot, :n] = cum
        # 端より先を参照しても壊れないよう、余りは終点で埋める
        self.path_pts[board, slot, n:] = pts[-1]
        self.path_cum[board, slot, n:] = cum[-1]
        self.path_len[board, slot] = cum[-1]

    def set_flight(self, board: int, slot: int, spawn: Tuple[int, int],
                   core: Tuple[float, float]) -> None:
        """飛行敵の直線ルート。迷路を完全に無視する。"""
        pts = np.array([[spawn[0] + 0.5, spawn[1] + 0.5], list(core)],
                       dtype=np.float32)
        cum = pf.cumulative(pts)
        self.path_n[board, slot] = 2
        self.path_pts[board, slot, :2] = pts
        self.path_cum[board, slot, :2] = cum
        self.path_pts[board, slot, 2:] = pts[-1]
        self.path_cum[board, slot, 2:] = cum[-1]
        self.path_len[board, slot] = cum[-1]

    def reanchor(self, board: int, slot: int, old_pos: np.ndarray) -> None:
        """折れ線を差し替えたあと、敵を **最近点** で新しい経路へ乗せ換える。

        Java は敵 1 体ごとに Theta* を引き直すが、それでは壁を 1 枚置くたびに
        敵の数だけ経路探索が走る。見た目も所要時間もほぼ変わらないので、
        新しい折れ線上の最も近い点へ進行度を移す方式にした。
        """
        mask = self.en_alive[board] & (self.en_slot[board] == slot)
        idx = np.flatnonzero(mask)
        # old_pos は「生きている敵の幅」で渡ってくる（enemy_positions の
        # 高速版）。詰め済みなのでそれを超える枠は必ず死んでいる
        idx = idx[idx < len(old_pos)]
        if len(idx) == 0:
            return
        n = int(self.path_n[board, slot])
        if n < 2:
            return
        pts = self.path_pts[board, slot, :n]
        cum = self.path_cum[board, slot, :n]
        seg_a, seg_b = pts[:-1], pts[1:]
        d = seg_b - seg_a
        denom = np.maximum((d * d).sum(axis=1), 1e-9)
        p = old_pos[idx]
        t = np.clip((((p[:, None, :] - seg_a[None]) * d[None]).sum(axis=2)
                     / denom[None]), 0.0, 1.0)
        proj = seg_a[None] + d[None] * t[:, :, None]
        best = ((proj - p[:, None, :]) ** 2).sum(axis=2).argmin(axis=1)
        rows = np.arange(len(idx))
        self.en_progress[board, idx] = (cum[best]
                                        + t[rows, best] * np.sqrt(denom[best]))

    # ---------------------------------------------------------------- 位置
    def enemy_positions(self, e_eff: Optional[int] = None) -> np.ndarray:
        """生きている敵の (B, E, 2) 座標。折れ線を進行度で補間する。
        (EnemyInstance#positionAt)

        **経路スロットごとに分けて計算する。** 敵 1 体ずつに折れ線を
        gather すると (B, E, W) の巨大配列ができてここが全体の最大の
        コストになる（プロファイルで実測）。スロットは「地上 x スポーン数」
        ＋「飛行 x スポーン数」しかないので、スロット単位で回すほうが軽い。
        """
        E = int(self.en_count.max()) if e_eff is None else e_eff
        if E <= 0:
            return np.zeros((self.n, 0, 2), dtype=np.float32)
        out = np.zeros((self.n, E, 2), dtype=np.float32)
        slot_all = self.en_slot[:, :E]
        prog_all = self.en_progress[:, :E]

        for s in range(self.path_n.shape[1]):
            take = slot_all == s
            if not take.any():
                continue
            cum = self.path_cum[:, s]                 # (B, W)
            pts = self.path_pts[:, s]                 # (B, W, 2)
            limit = np.maximum(self.path_n[:, s] - 2, 0)[:, None]
            prog = np.clip(prog_all, 0.0, self.path_len[:, s][:, None])
            idx = np.clip((prog[:, :, None] >= cum[:, None, :]).sum(axis=2) - 1,
                          0, limit)
            ca = np.take_along_axis(cum, idx, axis=1)
            cb = np.take_along_axis(cum, idx + 1, axis=1)
            a = np.take_along_axis(pts, idx[:, :, None], axis=1)
            b = np.take_along_axis(pts, (idx + 1)[:, :, None], axis=1)
            t = np.clip((prog - ca) / np.maximum(cb - ca, 1e-9), 0.0, 1.0)
            pos = a + (b - a) * t[:, :, None]
            out[take] = pos[take]
        return out

    def remaining(self) -> np.ndarray:
        """コアまでの残り距離。塔の狙いはこれで決まる（進行距離ではない）ので、
        経路を引き直しても優先順位が壊れない。 (EnemyInstance#remaining)"""
        rows = np.arange(self.n)[:, None]
        return np.maximum(0.0, self.path_len[rows, self.en_slot] - self.en_progress)

    def path_total(self) -> np.ndarray:
        rows = np.arange(self.n)[:, None]
        return self.path_len[rows, self.en_slot]

    # ---------------------------------------------------------------- 塔
    def refresh_tower_stats(self) -> None:
        """レベル・特化・監視塔の上乗せを解決して実効性能を作り直す。

        塔が増減・強化されたときだけ変わるので、意思決定ごとに 1 回で足りる。
        毎サブステップ引き直すのは無駄。 (Battlefield#resolvedStats)
        """
        t = self.tables
        env = self.env_of[:, None]
        kind = np.maximum(self.tw_kind, 0)
        lv, sp = self.tw_level, self.tw_spec
        live = self.tw_kind >= 0

        self._st_damage = t.tw_damage[env, kind, lv, sp] * (1.0 + self.tw_boost_dmg)
        self._st_range = t.tw_range[env, kind, lv, sp]
        rate = np.minimum(self.tw_boost_rate, B.SUPPORT_RATE_CAP)
        self._st_cooldown = np.maximum(
            B.MIN_COOLDOWN, np.round(t.tw_cooldown[env, kind, lv, sp] * (1.0 - rate)))
        self._st_splash = t.tw_splash[env, kind, lv, sp]
        self._st_chain = t.tw_chain[env, kind, lv, sp]
        self._st_slow = t.tw_slow[env, kind, lv, sp]
        self._st_slow_ticks = t.tw_slow_ticks[env, kind, lv, sp]
        self._st_burn = t.tw_burn[env, kind, lv, sp]
        self._st_burn_ticks = t.tw_burn_ticks[env, kind, lv, sp]
        self._st_banish = t.tw_banish[env, kind, lv, sp]
        self._st_vuln = t.tw_vuln[env, kind, lv, sp]
        self._st_vuln_ticks = t.tw_vuln_ticks[env, kind, lv, sp]
        # 素の（バフを受けていない）性能。功績を分けるときの分母になる
        self._st_damage_raw = t.tw_damage[env, kind, lv, sp]
        self._st_cooldown_raw = np.maximum(B.MIN_COOLDOWN,
                                           t.tw_cooldown[env, kind, lv, sp])
        # 「バフを出しているのは誰か」「デバフを出しているのは誰か」を
        # 種類ごとの比率にしておく。功績はここに沿って返す
        self._credit_boost = self._supply_share(live, kind,
                                                t.tw_boost_dmg[env, kind, lv, sp]
                                                + t.tw_boost_rate[env, kind, lv, sp])
        self._credit_vuln = self._supply_share(live, kind,
                                               t.tw_vuln[env, kind, lv, sp])

        self._st_style = np.where(live, t.tw_style[kind], -1)
        self._st_target = np.where(live, t.tw_target[kind], TGT_NONE)

    def recompute_support(self, boards: Optional[np.ndarray] = None) -> None:
        """監視塔の効果を各塔に焼き込む。 (Battlefield#recomputeSupport)

        毎 tick 周りを数え直すと塔の数の 2 乗になるうえ、塔が動かない限り
        結果は変わらない。塔が増減・強化されたときだけ呼ぶ。
        監視塔どうしは強化し合わない（雪だるま式に伸びると配分の判断が消える）。
        """
        sel = np.arange(self.n) if boards is None else np.unique(boards)
        if len(sel) == 0:
            return
        t = self.tables
        env = self.env_of[sel][:, None]
        kind = np.maximum(self.tw_kind[sel], 0)
        lv, sp = self.tw_level[sel], self.tw_spec[sel]
        live = self.tw_kind[sel] >= 0
        passive = live & t.tw_passive[kind]

        src_range = t.tw_range[env, kind, lv, sp]
        src_dmg = t.tw_boost_dmg[env, kind, lv, sp]
        src_rate = t.tw_boost_rate[env, kind, lv, sp]
        src_resist = t.tw_resist[env, kind, lv, sp]

        x, z = self.tw_x[sel], self.tw_z[sel]
        d = np.sqrt((x[:, :, None] - x[:, None, :]) ** 2
                    + (z[:, :, None] - z[:, None, :]) ** 2)     # [受け手, 出し手]
        eye = np.eye(self.max_towers, dtype=bool)[None]
        gives = (d <= src_range[:, None, :]) & passive[:, None, :] & ~eye
        receives = live & ~passive
        self.tw_boost_dmg[sel] = np.where(
            receives, (gives * src_dmg[:, None, :]).sum(axis=2), 0.0)
        self.tw_boost_rate[sel] = np.where(
            receives, np.minimum((gives * src_rate[:, None, :]).sum(axis=2),
                                 B.SUPPORT_RATE_CAP), 0.0)
        # 妨害の傘だけは足さずに「いちばん厚い 1 枚」を採る。
        # 足すと監視塔を 2 つ並べるだけで無効化に届き、特化を選ぶ意味が消える。
        # 受け手は監視塔自身も含む（真っ先に黙らされるようでは対策にならない）
        self.tw_disable_resist[sel] = np.where(
            live, (gives * src_resist[:, None, :]).max(axis=2), 0.0)

    # ---------------------------------------------------------------- 敵の投入
    def spawn(self, boards: np.ndarray, attacker: np.ndarray,
              spawn_index: Optional[np.ndarray] = None,
              sender: Optional[np.ndarray] = None) -> None:
        """送られてきたモンスターを湧かせる。 (Island#receive)

        1 回の送りにつき相手 1 人あたり 1 体。上限に達している島は取りこぼす
        （実ゲームに上限はないが、観測を固定長にするための保護）。

        :param sender: 送り主の島番号。コアまで通ったときに **送った側の
            ライフが 1 戻る** ので、誰の送りだったかを持っておく必要がある
        """
        if len(boards) == 0:
            return
        slots = alloc_slots(self.en_count, boards)
        ok = slots < self.max_enemies
        np.add.at(self.stat_spawn_asked, boards, 1.0)
        np.add.at(self.stat_spawn_dropped, boards[~ok], 1.0)
        if getattr(self, "env_ref", None) is not None:
            np.add.at(self.env_ref.stat_spawned_by_kind,
                      (boards, attacker.astype(np.int64)), 1.0)
        boards, attacker, slots = boards[ok], attacker[ok], slots[ok]
        if len(boards) == 0:
            return
        if spawn_index is None:
            spawn_index = np.zeros(len(boards), dtype=np.int32)
        else:
            spawn_index = spawn_index[ok]
        sender = (np.full(len(boards), -1, dtype=np.int32) if sender is None
                  else sender[ok].astype(np.int32))

        t = self.tables
        env = self.env_of[boards]
        body = t.at_body[attacker]

        # 耐力も撃破報酬も「何人に湧いたか」で正規化する。
        # 送りは生存者全員に飛ぶので、守り手が浴びる量は人数に比例するのに
        # 塔の数は変わらない (VersusMatch#REFERENCE_OPPONENTS)
        n_env = t.at_reward_total.shape[0]
        alive_per_env = np.bincount(self.env_of[self.alive],
                                    minlength=n_env).astype(np.float32)
        opponents = np.maximum(1.0, alive_per_env[env] - 1.0)
        hp = t.at_hp[env, attacker] * (
            (B.REFERENCE_OPPONENTS / opponents) ** B.SEND_POWER_EXPONENT)

        self.en_alive[boards, slots] = True
        self.en_body[boards, slots] = body
        self.en_attacker[boards, slots] = attacker
        self.en_hp[boards, slots] = hp
        self.en_max_hp[boards, slots] = hp
        self.en_progress[boards, slots] = 0.0
        # 撃破報酬も同じく人数で割る。割らないと返るコインの総量が
        # 20% x (人数-1) になり、6 人以上で払った額より返る額が多くなる
        self.en_reward[boards, slots] = np.maximum(
            1, np.rint(t.at_reward_total[env, attacker] / opponents)).astype(np.int32)
        self.en_slot[boards, slots] = spawn_index + self.n_spawns * t.en_flying[body]
        for arr in (self.en_slow_ticks, self.en_slow, self.en_burn_ticks,
                    self.en_burn, self.en_vuln_ticks, self.en_vuln,
                    self.en_ward_ticks, self.en_ward, self.en_blink_cd):
            arr[boards, slots] = 0.0
        self.en_burn_src[boards, slots] = -1
        self.en_last_hit_kind[boards, slots] = -1
        self.en_revives[boards, slots] = t.en_revives[body]
        self.en_sender[boards, slots] = sender
        np.maximum.at(self.en_count, boards, slots + 1)

    def queue_sends(self, boards: np.ndarray, attacker: np.ndarray,
                    count: np.ndarray, sender: np.ndarray,
                    spawn_index: Optional[np.ndarray] = None) -> None:
        """まとめ送りを待ち行列へ積む。**1 体目はここでは出さない。**

        取り出しは :meth:`_release_pending` が戦闘サブステップごとに行う。

        同じ島に同じ意思決定で複数人から届くことがあるので、
        島ごとに空いているスロットへ入れる。空きが無ければ捨てる
        （席数 - 1 しか同時には来ないので、実際には起きない）。
        """
        if len(boards) == 0:
            return
        if spawn_index is None:
            spawn_index = np.zeros(len(boards), dtype=np.int32)
        for b, k, n, snd, sp in zip(boards, attacker, count, sender, spawn_index):
            b = int(b)
            free = np.flatnonzero(self.pend_left[b] <= 0)
            if len(free) == 0:
                continue
            slot = int(free[0])
            self.pend_kind[b, slot] = int(k)
            self.pend_sender[b, slot] = int(snd)
            self.pend_left[b, slot] = int(n)
            self.pend_spawn[b, slot] = int(sp)

    def _release_pending(self) -> None:
        """待ち行列から 1 島 1 スロットにつき 1 体ずつ湧かせる。"""
        if not self.pend_left.any():
            return
        rows, slots = np.nonzero(self.pend_left > 0)
        self.spawn(rows.astype(np.int64),
                   self.pend_kind[rows, slots].astype(np.int64),
                   sender=self.pend_sender[rows, slots].astype(np.int64),
                   spawn_index=self.pend_spawn[rows, slots].astype(np.int32))
        self.pend_left[rows, slots] -= 1
        done = self.pend_left[rows, slots] <= 0
        if done.any():
            self.pend_kind[rows[done], slots[done]] = -1
            self.pend_sender[rows[done], slots[done]] = -1

    def compact_enemies(self) -> None:
        """生きている敵を前に詰める。死んだ枠を放置すると距離行列の幅が
        減らないので、意思決定ごとに 1 回だけ整理する。**これが効くから
        序盤の 1 ステップが数十倍速い。**"""
        max_e = int(self.en_count.max())
        if max_e == 0:
            return
        
        # 整理が必要な島（途中にFalseがある島）のみフィルタリング
        # 前方がTrueで後方がFalseの境界が en_alive.sum() と一致していなければ穴がある
        counts = self.en_alive[:, :max_e].sum(axis=1)
        self.en_count = counts.astype(np.int32)
        if max_e == 0:
            return

        # 穴がある（途中にDeadがある）島を特定
        # prefixが全てTrueでない島があるか
        # 簡易判定: 前から counts[b] 個が全て True かどうか
        grid_slice = self.en_alive[:, :max_e]
        rows = np.arange(self.n)
        # counts[b] が 0 の島、または全スロットが埋まっている島は除外できる
        needs_compact = (counts > 0) & (counts < max_e)
        if not needs_compact.any():
            return

        order = np.argsort(~grid_slice[needs_compact], axis=1, kind="stable")
        sub_idx = np.flatnonzero(needs_compact)
        
        for name in ("en_alive", "en_body", "en_attacker", "en_hp", "en_max_hp",
                     "en_progress", "en_reward", "en_slot", "en_slow_ticks",
                     "en_slow", "en_burn_ticks", "en_burn", "en_vuln_ticks",
                     "en_vuln", "en_ward_ticks", "en_ward", "en_blink_cd",
                     "en_revives", "en_hit", "en_sender",
                     "en_burn_src", "en_last_hit_kind"):
            arr = getattr(self, name)
            arr[sub_idx, :max_e] = np.take_along_axis(arr[sub_idx, :max_e], order, axis=1)

    # ---------------------------------------------------------------- 戦闘
    def advance(self, ticks: int, dt: int, sudden_death: np.ndarray) -> None:
        """``ticks`` ゲーム tick ぶん戦闘を進める。

        :param sudden_death: (B,) bool。漏らしたときのライフ減少が倍になるか
        """
        remaining = ticks
        while remaining > 0:
            step = min(dt, remaining)
            self._substep(step, sudden_death)
            remaining -= step

    def _substep(self, dt: int, sudden_death: np.ndarray) -> None:
        # まとめ送りの待ち行列から 1 体ずつ出す。**戦闘より先**に出すことで、
        # 出たその瞬間から撃たれる（Java の湧きと同じ扱いになる）
        self._release_pending()
        e_eff = int(self.en_count.max()) if self.n else 0
        if e_eff == 0:
            self._tick_towers_idle(dt)
            return

        sl = slice(0, e_eff)
        self.en_hit[:, sl] = False

        # ① 敵の状態と移動
        self._tick_enemy_status(dt, sl)
        self._move_enemies(dt, sl)
        pos = self.enemy_positions(e_eff)

        # ② 敵のオーラ（回復・庇護）と妨害
        self._apply_enemy_auras(dt, sl, pos)
        self._apply_disablers(sl, pos)

        # ③ 塔の射撃
        self._fire_towers(dt, sl, pos)

        # ④ 瞬移（被弾した瞬移体だけ）
        self._apply_blinks(sl)

        # ⑤ 撃破・漏れの後始末
        self._resolve_deaths(sl)
        self._resolve_leaks(sl, sudden_death)
        self.alive = self.lives > 0

    # -- ① --------------------------------------------------------------
    def _tick_enemy_status(self, dt: int, sl: slice) -> None:
        alive = self.en_alive[:, sl]
        self.en_blink_cd[:, sl] = np.maximum(0.0, self.en_blink_cd[:, sl] - dt)

        self.en_vuln_ticks[:, sl] = np.maximum(0.0, self.en_vuln_ticks[:, sl] - dt)
        self.en_vuln[:, sl] *= (self.en_vuln_ticks[:, sl] > 0)
        self.en_ward_ticks[:, sl] = np.maximum(0.0, self.en_ward_ticks[:, sl] - dt)
        self.en_ward[:, sl] *= (self.en_ward_ticks[:, sl] > 0)

        # 燃焼は装甲を無視する継続ダメージ。**功績は火を付けた塔に返す**
        ticks = np.minimum(self.en_burn_ticks[:, sl], dt)
        burn_dmg = np.where(
            alive, self.en_burn[:, sl] / B.TICKS_PER_SECOND * ticks, 0.0)
        src = self.en_burn_src[:, sl]
        bb, ee = np.nonzero((burn_dmg > 0) & (src >= 0))
        if len(bb):
            np.add.at(self.stat_damage_by_kind, (bb, src[bb, ee]),
                      burn_dmg[bb, ee])
        self.en_hp[:, sl] -= np.where(
            alive, self.en_burn[:, sl] / B.TICKS_PER_SECOND * ticks, 0.0)
        self.en_burn_ticks[:, sl] = np.maximum(0.0, self.en_burn_ticks[:, sl] - dt)
        self.en_burn[:, sl] *= (self.en_burn_ticks[:, sl] > 0)

    def _move_enemies(self, dt: int, sl: slice) -> None:
        env = self.env_of[:, None]
        body = np.maximum(self.en_body[:, sl], 0)
        speed = self.tables.en_speed[env, body]
        slowed = np.minimum(self.en_slow_ticks[:, sl], dt)
        moved = speed * (slowed * (1.0 - self.en_slow[:, sl]) + (dt - slowed))
        alive = self.en_alive[:, sl] & (self.en_hp[:, sl] > 0)
        self.en_progress[:, sl] += np.where(alive, moved, 0.0)
        self.en_slow_ticks[:, sl] = np.maximum(0.0, self.en_slow_ticks[:, sl] - dt)
        self.en_slow[:, sl] *= (self.en_slow_ticks[:, sl] > 0)

    # -- ② --------------------------------------------------------------
    def _apply_enemy_auras(self, dt: int, sl: slice, pos: np.ndarray) -> None:
        """回復と庇護。どちらも「オーラを出している個体を先に潰す」判断を作る。

        庇護は上書きではなく期限つきで貼るので、庇護者が落ちればすぐ切れる。
        """
        env = self.env_of[:, None]
        body = np.maximum(self.en_body[:, sl], 0)
        heal = self.tables.en_heal[env, body]
        ward_r = self.tables.en_ward_r[body]
        if not ((heal > 0).any() or (ward_r > 0).any()):
            return
        alive = self.en_alive[:, sl] & (self.en_hp[:, sl] > 0)
        ward_red = self.tables.en_ward_red[body]

        d = np.sqrt(((pos[:, :, None, :] - pos[:, None, :, :]) ** 2).sum(axis=3))
        eye = np.eye(pos.shape[1], dtype=bool)[None]
        pair = alive[:, :, None] & alive[:, None, :] & ~eye   # [出し手, 受け手]

        if (heal > 0).any():
            hits = pair & (heal[:, :, None] > 0) & (d <= B.HEAL_AURA_RADIUS)
            if hits.any():
                gained = (hits * heal[:, :, None]).sum(axis=1) * (dt / B.HEAL_INTERVAL)
                self.en_hp[:, sl] = np.minimum(self.en_max_hp[:, sl],
                                               self.en_hp[:, sl] + gained)
        if (ward_r > 0).any():
            hits = pair & (ward_r[:, :, None] > 0) & (d <= ward_r[:, :, None])
            has = hits.any(axis=1)
            if has.any():
                best = (hits * ward_red[:, :, None]).max(axis=1)
                self.en_ward[:, sl] = np.where(
                    has, np.maximum(self.en_ward[:, sl], best), self.en_ward[:, sl])
                self.en_ward_ticks[:, sl] = np.where(
                    has, np.maximum(self.en_ward_ticks[:, sl], B.WARD_TICKS),
                    self.en_ward_ticks[:, sl])

    def _apply_disablers(self, sl: slice, pos: np.ndarray) -> None:
        """妨害者が近くの塔を黙らせる。火力を 1 箇所に固めるほど、
        1 体でまとめて止められる。 (Battlefield#applyDisablers)"""
        body = np.maximum(self.en_body[:, sl], 0)
        radius = self.tables.en_disable_r[body]
        if not (radius > 0).any():
            return
        ticks = self.tables.en_disable_t[body]
        active = self.en_alive[:, sl] & (self.en_hp[:, sl] > 0) & (radius > 0)
        d = np.sqrt((self.tw_x[:, :, None] - pos[:, None, :, 0]) ** 2
                    + (self.tw_z[:, :, None] - pos[:, None, :, 1]) ** 2)
        hit = (d <= radius[:, None, :]) & active[:, None, :] \
            & (self.tw_kind >= 0)[:, :, None]
        incoming = np.where(hit, ticks[:, None, :], 0.0).max(axis=2)
        # 監視塔の傘のぶんだけ短くなる。完全無効なら 0 tick＝そもそも黙らない
        incoming = incoming * (1.0 - self.tw_disable_resist)
        self.tw_disabled = np.maximum(self.tw_disabled, incoming)

    # -- ③ --------------------------------------------------------------
    def _tick_towers_idle(self, dt: int) -> None:
        """敵が 1 体もいないときのクールダウン進行だけ。"""
        blocked = np.minimum(self.tw_disabled, dt)
        self.tw_disabled = np.maximum(0.0, self.tw_disabled - dt)
        live = self.tw_kind >= 0
        self.tw_charge = np.where(
            live, np.minimum(self.tw_charge + (dt - blocked), self._st_cooldown),
            self.tw_charge)
        # 遊休は数えない。敵が 1 体もいない時間まで数えると、
        # 「相手が攻めてこない試合」への定額税になり、自分では避けられない

    def _fire_towers(self, dt: int, sl: slice, pos: np.ndarray) -> None:
        live = (self.tw_kind >= 0) & self.alive[:, None]
        blocked = np.minimum(self.tw_disabled, dt)
        self.tw_disabled = np.maximum(0.0, self.tw_disabled - dt)
        if not live.any():
            return

        cd = np.maximum(self._st_cooldown, 1.0)
        charge = np.where(live, self.tw_charge + (dt - blocked), 0.0)
        shots = np.minimum(np.floor(charge / cd), MAX_SHOTS_PER_SUBSTEP)
        self.tw_charge = np.minimum(charge - shots * cd, cd)

        alive_e = self.en_alive[:, sl] & (self.en_hp[:, sl] > 0)
        if not alive_e.any():
            return          # 敵がいない＝遊休の判定対象外（上と同じ理由）

        dist = np.sqrt((self.tw_x[:, :, None] - pos[:, None, :, 0]) ** 2
                       + (self.tw_z[:, :, None] - pos[:, None, :, 1]) ** 2)
        in_range = (dist <= self._st_range[:, :, None]) & alive_e[:, None, :] \
            & live[:, :, None]

        # 遊休率＝「敵がいるのに射程内へ一度も来ない塔」の割合。整形報酬に使う指標で、
        # 経路から外れた場所に塔を建てる癖を咎める。
        # 分母もこの枝でしか増えないので、敵がいない時間は率の計算から丸ごと外れる
        has_target = in_range.any(axis=2)
        self.stat_tower_slots += live.sum(axis=1)
        self.stat_idle_slots += (live & ~has_target).sum(axis=1)

        style = self._st_style
        fire = (shots > 0) & live
        self._fire_aura(fire & (style == STYLE_AURA), dist, alive_e)
        self._fire_curse(fire & (style == STYLE_CURSE), dist, alive_e)

        aim = fire & ~np.isin(style, (STYLE_AURA, STYLE_CURSE, STYLE_SUPPORT, -1))
        if not aim.any():
            return
        target = self._select_targets(sl, pos, dist, in_range, alive_e, aim)
        valid = target >= 0
        if not valid.any():
            return
        idx = np.maximum(target, 0)
        dmg = self._st_damage * shots

        for style_id, handler in ((STYLE_SINGLE, self._hit_single),
                                  (STYLE_SPLASH, self._hit_splash),
                                  (STYLE_CHAIN, self._hit_chain),
                                  (STYLE_PIERCE, self._hit_pierce),
                                  (STYLE_BANISH, self._hit_banish)):
            mask = valid & aim & (style == style_id)
            if mask.any():
                handler(mask, idx, dmg, sl, pos, dist, alive_e)

    def _select_targets(self, sl, pos, dist, in_range, alive_e, aim) -> np.ndarray:
        """射程内から 1 体選ぶ。**選び方は塔ごとに違う**。 (Battlefield#findTarget)

        全部の塔が「コアに近い敵」を撃つと、種類を変えて並べても弾が同じ 1 体に
        集まり、溶けかけの敵に過剰攻撃を重ねて後ろは素通りになる。

        同点はコアに近いほうを採る。Java は score の差が 1e-9 を超えたときだけ
        score を優先するので、残距離に 1e-12 を掛けて 1 本のキーにまとめている。
        """
        mode = self._st_target
        remain = self.remaining()[:, sl]
        affected = ((self.en_slow_ticks[:, sl] > 0)
                    | (self.en_burn_ticks[:, sl] > 0)
                    | (self.en_vuln_ticks[:, sl] > 0))

        score = np.zeros(dist.shape, dtype=np.float32)
        m = mode[:, :, None]
        if (mode == TGT_UNAFFECTED).any():
            score = np.where(m == TGT_UNAFFECTED,
                             (~affected)[:, None, :].astype(np.float32), score)
        if (mode == TGT_TOUGHEST).any():
            score = np.where(m == TGT_TOUGHEST, self.en_hp[:, sl][:, None, :], score)
        if (mode == TGT_DENSEST).any():
            dd = np.sqrt(((pos[:, :, None, :] - pos[:, None, :, :]) ** 2).sum(axis=3))
            crowd = ((dd <= B.CROWD_RADIUS) & alive_e[:, None, :]).sum(axis=2)
            score = np.where(m == TGT_DENSEST,
                             crowd[:, None, :].astype(np.float32), score)
        if (mode == TGT_FARTHEST).any():
            score = np.where(m == TGT_FARTHEST, dist, score)

        ok = in_range & aim[:, :, None]
        # 送還は 60 秒に 1 度きり。出発点にいる敵を撃つと空撃ちになる
        at_spawn = self.en_progress[:, sl] <= 0.0
        ok = ok & ~((self._st_style == STYLE_BANISH)[:, :, None] & at_spawn[:, None, :])

        key = np.where(ok, score - remain[:, None, :] * 1e-12, -np.inf)
        best = key.argmax(axis=2)
        found = np.take_along_axis(ok, best[:, :, None], axis=2)[:, :, 0]
        return np.where(found, best, -1)

    # -- 当て方 ----------------------------------------------------------
    def _supply_share(self, live: np.ndarray, kind: np.ndarray,
                      supply: np.ndarray) -> np.ndarray:
        """島ごとに「その効果を出している塔の種類」の比率 (n, N_TOWER)。

        撃った瞬間には「誰のバフか」が分からない（``tw_boost_dmg`` は
        射程内の支援塔ぶんを合算した値なので）。島の中でその効果を供給している
        塔の量に比例して返すのがいちばん素直で、供給源が 1 種類なら厳密になる。
        """
        out = np.zeros((self.n, N_TOWER), dtype=np.float32)
        amount = np.where(live, supply, 0.0)
        b, slot = np.nonzero(amount > 0)
        if len(b):
            np.add.at(out, (b, kind[b, slot]), amount[b, slot])
        total = out.sum(axis=1, keepdims=True)
        return np.divide(out, total, out=np.zeros_like(out), where=total > 0)

    def _apply_damage(self, board, enemy, raw, tower=None) -> None:
        """装甲 → 呪詛 → 庇護 の順に通す。 (EnemyInstance#damage)

        順番に意味がある。装甲は固定引き算なので先に引き、そのあとで
        呪詛（増）と庇護（減）を掛ける。逆にすると装甲の高い敵に呪詛をかけた
        ときの伸びが不自然に大きくなる。

        ``tower`` を渡すと **与ダメージの功績**を種類ごとに数える
        (:meth:`_credit_damage`)。範囲・連鎖・貫通・送還の巻き添えも
        すべてここを通るので、渡しさえすれば全部数えられる。
        """
        if len(board) == 0:
            return
        env = self.env_of[board]
        body = np.maximum(self.en_body[board, enemy], 0)
        applied = np.maximum(B.MIN_DAMAGE_AFTER_ARMOR,
                             raw - self.tables.en_armor[env, body])
        vuln = self.en_vuln[board, enemy]
        applied *= (1.0 + vuln)
        applied *= (1.0 - self.en_ward[board, enemy])
        applied = np.maximum(B.MIN_DAMAGE_APPLIED, applied)
        np.add.at(self.en_hp, (board, enemy), -applied)
        self.en_hit[board, enemy] = True
        if tower is not None:
            self.en_last_hit_kind[board, enemy] = self.tw_kind[board, tower]
            self._credit_damage(board, tower, applied, vuln)

    def _credit_damage(self, board, tower, applied, vuln) -> None:
        """与ダメージを **撃った塔・バフ・デバフ** に分けて数える。

        監視塔は自分では 1 ダメージも出さないのに、周りの塔の火力を底上げする。
        呪詛塔も同じ。**撃った塔に全部つけると、この 2 種は永久に功績 0 になる**
        ので、増えたぶんを供給元へ返す。

        1 発の被ダメージは ``素の火力 x 支援倍率 x 呪詛倍率`` に分解できる。
        増えたぶん（積 − 1）を 2 つの要因へ配るとき、交差項 ``a*b`` は
        どちらの手柄とも言えないので **半分ずつ**にした（2 人ゲームの
        Shapley 値と同じ配り方）。
        """
        boost = self.tw_boost_dmg[board, tower]
        cd_raw = self._st_cooldown_raw[board, tower]
        cd_eff = np.maximum(self._st_cooldown[board, tower], 1.0)
        # 支援は「1 発の威力」と「撃つ回数」の両方を増やす。総ダメージへの
        # 寄与は両者の積になる
        m_support = (1.0 + boost) * (cd_raw / cd_eff)
        m_curse = 1.0 + vuln
        base = applied / np.maximum(m_support * m_curse, 1e-6)

        a, c = m_support - 1.0, m_curse - 1.0
        cross = 0.5 * a * c
        np.add.at(self.stat_damage_by_kind,
                  (board, np.maximum(self.tw_kind[board, tower], 0)), base)
        extra_s = base * (a + cross)
        if (extra_s > 0).any():
            np.add.at(self.stat_damage_by_kind, board,
                      self._credit_boost[board] * extra_s[:, None])
        extra_c = base * (c + cross)
        if (extra_c > 0).any():
            np.add.at(self.stat_damage_by_kind, board,
                      self._credit_vuln[board] * extra_c[:, None])

    def _apply_on_hit(self, board, enemy, tower) -> None:
        """命中時の減速・燃焼。 (Battlefield#hit)"""
        if len(board) == 0:
            return
        env = self.env_of[board]
        body = np.maximum(self.en_body[board, enemy], 0)

        slow = self._st_slow[board, tower]
        eff = slow * (1.0 - self.tables.en_slow_resist[env, body])
        ok = np.flatnonzero(eff > 0)
        if len(ok):
            bb, ee = board[ok], enemy[ok]
            ticks = self._st_slow_ticks[board, tower][ok]
            deeper = eff[ok] >= self.en_slow[bb, ee]
            # 深いほうを優先し、浅ければ持続だけ半分もらう
            self.en_slow[bb[deeper], ee[deeper]] = eff[ok][deeper]
            self.en_slow_ticks[bb[deeper], ee[deeper]] = np.maximum(
                self.en_slow_ticks[bb[deeper], ee[deeper]], ticks[deeper])
            sh = ~deeper
            self.en_slow_ticks[bb[sh], ee[sh]] = np.maximum(
                self.en_slow_ticks[bb[sh], ee[sh]], ticks[sh] / 2.0)

        burn = self._st_burn[board, tower] * (1.0 - self.tables.en_burn_resist[body])
        ok = np.flatnonzero(burn > 0)
        if len(ok):
            bb, ee = board[ok], enemy[ok]
            deeper = burn[ok] >= self.en_burn[bb, ee]
            self.en_burn[bb, ee] = np.maximum(self.en_burn[bb, ee], burn[ok])
            self.en_burn_ticks[bb, ee] = np.maximum(
                self.en_burn_ticks[bb, ee], self._st_burn_ticks[board, tower][ok])
            self.en_burn_src[bb[deeper], ee[deeper]] = self.tw_kind[
                board[ok][deeper], tower[ok][deeper]]

    def _hit_single(self, mask, idx, dmg, sl, pos, dist, alive_e) -> None:
        b, t = np.nonzero(mask)
        e = idx[b, t]
        self._apply_damage(b, e, dmg[b, t], t)
        self._apply_on_hit(b, e, t)

    def _hit_splash(self, mask, idx, dmg, sl, pos, dist, alive_e) -> None:
        b, t = np.nonzero(mask)
        e = idx[b, t]
        base = dmg[b, t]
        self._apply_damage(b, e, base, t)
        self._apply_on_hit(b, e, t)
        impact = pos[b, e]
        d = np.sqrt(((pos[b] - impact[:, None, :]) ** 2).sum(axis=2))
        others = np.arange(pos.shape[1])[None] != e[:, None]
        hit = (d <= self._st_splash[b, t][:, None]) & alive_e[b] & others
        kk, ee = np.nonzero(hit)
        if len(kk):
            self._apply_damage(b[kk], ee, base[kk] * B.SPLASH_FALLOFF, t[kk])
            self._apply_on_hit(b[kk], ee, t[kk])

    def _hit_chain(self, mask, idx, dmg, sl, pos, dist, alive_e) -> None:
        """最初の対象から近くの敵へ連鎖する。蛇行迷路で経路が並ぶほど強い。"""
        b, t = np.nonzero(mask)
        k, n_e = len(b), pos.shape[1]
        hops = np.minimum(self._st_chain[b, t], MAX_CHAIN_HOPS)
        damage = dmg[b, t].astype(np.float32).copy()
        current = idx[b, t].copy()
        already = np.zeros((k, n_e), dtype=bool)
        active = hops > 0
        cell = pos[b]                                    # (k, E, 2)

        for hop in range(MAX_CHAIN_HOPS):
            active &= hop < hops
            ai = np.flatnonzero(active)
            if len(ai) == 0:
                break
            cur = current[ai]
            self._apply_damage(b[ai], cur, damage[ai], t[ai])
            self._apply_on_hit(b[ai], cur, t[ai])
            already[ai, cur] = True

            origin = cell[ai, cur]
            d = np.sqrt(((cell[ai] - origin[:, None, :]) ** 2).sum(axis=2))
            still = self.en_alive[b[ai], :n_e] & (self.en_hp[b[ai], :n_e] > 0)
            ok = (d < B.CHAIN_RADIUS) & still & ~already[ai]
            nxt = np.where(ok, d, np.inf).argmin(axis=1)
            found = ok[np.arange(len(ai)), nxt]
            current[ai] = nxt
            damage[ai] *= B.CHAIN_FALLOFF
            active[ai] = found

    def _hit_pierce(self, mask, idx, dmg, sl, pos, dist, alive_e) -> None:
        """砲身から狙った敵へ引いた線の上にいる敵を貫く。"""
        b, t = np.nonzero(mask)
        e = idx[b, t]
        base = dmg[b, t]
        self._apply_damage(b, e, base, t)
        self._apply_on_hit(b, e, t)

        muzzle = np.stack([self.tw_x[b, t], self.tw_z[b, t]], axis=1)
        ab = pos[b, e] - muzzle
        length_sq = np.maximum((ab * ab).sum(axis=1), 1e-9)
        rel = pos[b] - muzzle[:, None, :]
        proj = np.clip((rel * ab[:, None, :]).sum(axis=2) / length_sq[:, None], 0.0, 1.0)
        foot = muzzle[:, None, :] + ab[:, None, :] * proj[:, :, None]
        d = np.sqrt(((pos[b] - foot) ** 2).sum(axis=2))

        others = np.arange(pos.shape[1])[None] != e[:, None]
        hit = (d <= B.PIERCE_WIDTH) & alive_e[b] & others
        extra = np.maximum(self._st_chain[b, t] - 1, 0)
        rank = np.argsort(np.argsort(np.where(hit, d, np.inf), axis=1), axis=1)
        hit &= rank < extra[:, None]
        kk, ee = np.nonzero(hit)
        if len(kk):
            self._apply_damage(b[kk], ee, base[kk] * B.PIERCE_FALLOFF, t[kk])
            self._apply_on_hit(b[kk], ee, t[kk])

    def _hit_banish(self, mask, idx, dmg, sl, pos, dist, alive_e) -> None:
        """出発点へ送り返す。倒すのではなく **迷路をもう一周させる**。"""
        b, t = np.nonzero(mask)
        e = idx[b, t]
        base = dmg[b, t]
        n_e = pos.shape[1]
        self._apply_damage(b, e, base, t)
        self._apply_on_hit(b, e, t)
        alive_now = self.en_hp[b, e] > 0
        self.en_progress[b[alive_now], e[alive_now]] = 0.0

        extra = np.maximum(np.round(self._st_banish[b, t]).astype(np.int64) - 1, 0)
        if not (extra > 0).any():
            return
        # コアに近い敵から順に道連れにする（いちばん漏れそうな敵から戻す）
        remain = self.remaining()[:, sl]
        others = np.arange(n_e)[None] != e[:, None]
        pool = (dist[b, t] <= self._st_range[b, t][:, None]) & alive_e[b] & others \
            & (self.en_progress[b, :n_e] > 0.0)
        rank = np.argsort(np.argsort(np.where(pool, remain[b], np.inf), axis=1), axis=1)
        sel = pool & (rank < extra[:, None])
        kk, ee = np.nonzero(sel)
        if len(kk):
            self._apply_damage(b[kk], ee, base[kk], t[kk])
            self._apply_on_hit(b[kk], ee, t[kk])
            still = self.en_hp[b[kk], ee] > 0
            self.en_progress[b[kk][still], ee[still]] = 0.0

    def _fire_aura(self, mask, dist, alive_e) -> None:
        """火炉。弾を撃たず、射程内を燃焼帯にする。"""
        if not mask.any():
            return
        b, t = np.nonzero(mask)
        hit = (dist[b, t] <= self._st_range[b, t][:, None]) & alive_e[b]
        kk, ee = np.nonzero(hit)
        if not len(kk):
            return
        body = np.maximum(self.en_body[b[kk], ee], 0)
        burn = self._st_burn[b, t][kk] * (1.0 - self.tables.en_burn_resist[body])
        ok = np.flatnonzero(burn > 0)
        if not len(ok):
            return
        bb, e2 = b[kk][ok], ee[ok]
        self.en_burn[bb, e2] = np.maximum(self.en_burn[bb, e2], burn[ok])
        self.en_burn_ticks[bb, e2] = np.maximum(
            self.en_burn_ticks[bb, e2], self._st_burn_ticks[b, t][kk][ok])

    def _fire_curse(self, mask, dist, alive_e) -> None:
        """呪詛塔。削らずに射程内の敵の被ダメージを増やす。"""
        if not mask.any():
            return
        b, t = np.nonzero(mask)
        hit = (dist[b, t] <= self._st_range[b, t][:, None]) & alive_e[b]
        kk, ee = np.nonzero(hit)
        if not len(kk):
            return
        bb = b[kk]
        self.en_vuln[bb, ee] = np.maximum(self.en_vuln[bb, ee],
                                          self._st_vuln[b, t][kk])
        self.en_vuln_ticks[bb, ee] = np.maximum(
            self.en_vuln_ticks[bb, ee], self._st_vuln_ticks[b, t][kk])

    # -- ④ --------------------------------------------------------------
    def _apply_blinks(self, sl: slice) -> None:
        """被弾した瞬移体が壁を跨いで先の通路へ跳ぶ。

        経路上を決まった距離だけ進むのではなく、**半径の中に入っている経路の
        うちいちばんコアに近い点**を選ぶ。曲がりくねらせた迷路ほど 1 回の瞬移で
        稼がれるので、「壁で距離を伸ばす」一本槍への答えになっている。
        """
        body = np.maximum(self.en_body[:, sl], 0)
        radius = self.tables.en_blink_r[body]
        can = (self.en_hit[:, sl] & self.en_alive[:, sl] & (self.en_hp[:, sl] > 0)
               & (radius > 0) & (self.en_blink_cd[:, sl] <= 0))
        b, e = np.nonzero(can)
        if len(b) == 0:
            return

        slot = self.en_slot[b, e]
        prog = self.en_progress[b, e]
        # コアの直前までしか候補にしない（跳んだだけで漏れるのは理不尽）
        limit = np.maximum(0.0, self.path_len[b, slot] - 1.0)
        span = limit - prog
        keep = span > 0
        if not keep.any():
            return
        b, e, slot, prog, span = b[keep], e[keep], slot[keep], prog[keep], span[keep]

        frac = np.linspace(0.0, 1.0, BLINK_SAMPLES, dtype=np.float32)[None, :]
        cand = prog[:, None] + span[:, None] * frac
        here = self._sample_path(b, slot, prog[:, None])[:, 0]
        pts = self._sample_path(b, slot, cand)
        d = np.sqrt(((pts - here[:, None, :]) ** 2).sum(axis=2))
        r = self.tables.en_blink_r[np.maximum(self.en_body[b, e], 0)][:, None]
        best = np.where(d <= r, cand, -np.inf).max(axis=1)

        moved = best > prog
        if not moved.any():
            return
        bb, ee = b[moved], e[moved]
        self.en_progress[bb, ee] = best[moved]
        self.en_blink_cd[bb, ee] = self.tables.en_blink_cd[
            np.maximum(self.en_body[bb, ee], 0)]

    def _sample_path(self, board: np.ndarray, slot: np.ndarray,
                     progress: np.ndarray) -> np.ndarray:
        """(k, S) の進行度 → (k, S, 2) の座標。"""
        cum = self.path_cum[board, slot]        # (k, W)
        pts = self.path_pts[board, slot]        # (k, W, 2)
        n = self.path_n[board, slot]
        idx = np.clip((progress[:, :, None] >= cum[:, None, :]).sum(axis=2) - 1,
                      0, np.maximum(n - 2, 0)[:, None])       # (k, S)
        ca = np.take_along_axis(cum, idx, axis=1)
        cb = np.take_along_axis(cum, idx + 1, axis=1)
        a = np.take_along_axis(pts, idx[:, :, None], axis=1)   # (k, S, 2)
        bb = np.take_along_axis(pts, (idx + 1)[:, :, None], axis=1)
        t = np.clip((progress - ca) / np.maximum(cb - ca, 1e-9), 0.0, 1.0)[:, :, None]
        return a + (bb - a) * t

    # -- ⑤ --------------------------------------------------------------
    def _resolve_deaths(self, sl: slice) -> None:
        dead = self.en_alive[:, sl] & (self.en_hp[:, sl] <= 0)
        if not dead.any():
            return
        b, e = np.nonzero(dead)

        # 終焉騎は倒れる代わりに出発点へ戻る。報酬も出さない。
        # **ここでライフ上限は奪わない。** 倒しても上限が減るなら防衛に正解が
        # 存在せず、タワーディフェンスとして成立しない。上限を奪うのは
        # 「コアまで通されたとき」だけ (Island#onEnemyLeaked)
        revive = self.en_revives[b, e] > 0
        if revive.any():
            rb, re = b[revive], e[revive]
            self.en_revives[rb, re] -= 1
            self.en_hp[rb, re] = self.en_max_hp[rb, re]
            self.en_progress[rb, re] = 0.0
            for arr in (self.en_slow_ticks, self.en_slow,
                        self.en_burn_ticks, self.en_burn):
                arr[rb, re] = 0.0

        gone = ~revive
        if not gone.any():
            return
        gb, ge = b[gone], e[gone]
        killer = self.en_last_hit_kind[gb, ge]
        hit = killer >= 0
        if hit.any():
            np.add.at(self.stat_kills_by_kind, (gb[hit], killer[hit]), 1.0)
        reward = self.en_reward[gb, ge].astype(np.float32)
        np.add.at(self.coins, gb, reward)
        np.add.at(self.stat_coins_earned, gb, reward)
        np.add.at(self.stat_kills, gb, 1)
        # 撃破報酬コインの 10% がインカムになる。端数は貯めて 1 に届いたら加算
        np.add.at(self.income_progress, gb, reward * B.KILL_INCOME_RATIO)
        whole = np.floor(self.income_progress)
        self.income += whole
        self.income_progress -= whole

        split = self.tables.en_split[np.maximum(self.en_body[gb, ge], 0)]
        self.en_alive[gb, ge] = False
        sp = split > 0
        if sp.any():
            self._spawn_splits(gb[sp], ge[sp], split[sp])

    def _spawn_splits(self, board: np.ndarray, enemy: np.ndarray,
                      count: np.ndarray) -> None:
        """分裂の子は **親が居た地点から** 歩かせる。出発点に戻すと
        「倒したのに一番遠くから来直す」ことになり、分裂が単なる時間稼ぎになる。"""
        for i in range(int(count.max())):
            take = count > i
            b, e = board[take], enemy[take]
            slots = alloc_slots(self.en_count, b)
            ok = slots < self.max_enemies
            b, e, slots = b[ok], e[ok], slots[ok]
            if len(b) == 0:
                continue
            hp = self.en_max_hp[b, e] * B.SPLIT_HP_RATIO
            self.en_alive[b, slots] = True
            self.en_body[b, slots] = SPLITLING_ID
            self.en_attacker[b, slots] = self.en_attacker[b, e]
            self.en_hp[b, slots] = hp
            self.en_max_hp[b, slots] = hp
            self.en_reward[b, slots] = np.maximum(
                1, self.en_reward[b, e] // B.SPLIT_REWARD_DIVISOR)
            self.en_slot[b, slots] = self.en_slot[b, e]
            # 割れた子も「送られてきた敵」のまま。親だけが送り主を持つと、
            # 分裂体を送ったときだけコア到達の見返りが消えてしまう
            self.en_sender[b, slots] = self.en_sender[b, e]
            # 少しずらして出すと重なって 1 体に見えるのを防げる
            self.en_progress[b, slots] = np.maximum(
                0.0, self.en_progress[b, e] - 0.9 * i)
            for arr in (self.en_slow_ticks, self.en_slow, self.en_burn_ticks,
                        self.en_burn, self.en_vuln_ticks, self.en_vuln,
                        self.en_ward_ticks, self.en_ward, self.en_blink_cd):
                arr[b, slots] = 0.0
            self.en_burn_src[b, slots] = -1
            self.en_last_hit_kind[b, slots] = -1
            self.en_revives[b, slots] = 0
            np.maximum.at(self.en_count, b, slots + 1)

    def _resolve_leaks(self, sl: slice, sudden_death: np.ndarray) -> None:
        total = self.path_total()[:, sl]
        leaked = (self.en_alive[:, sl] & (self.en_hp[:, sl] > 0)
                  & (self.en_progress[:, sl] >= total))
        if not leaked.any():
            return
        b, e = np.nonzero(leaked)
        np.subtract.at(self.lives, b, np.where(sudden_death[b], 2.0, 1.0))
        self.lives = np.maximum(0.0, self.lives)
        np.add.at(self.stat_leaks, b, 1)

        # 終焉騎だけは、通されるとライフ上限そのものを持っていく。
        # 倒し切れば無傷／通せば取り返しがつかない (Island#onEnemyLeaked)
        steal = self.tables.en_steals_max_life[np.maximum(self.en_body[b, e], 0)]
        if steal.any():
            sb = b[steal]
            np.subtract.at(self.max_lives, sb, 1.0)
            np.add.at(self.stat_max_life_lost, sb, 1.0)
            self.max_lives = np.maximum(0.0, self.max_lives)
            self.lives = np.minimum(self.lives, self.max_lives)
        att = np.maximum(self.en_attacker[b, e], 0)
        # 呼び出し元(env)の stat_leaks_by_kind に加算するため、boards の参照から追記
        if hasattr(self, 'env_ref') and self.env_ref is not None:
            np.add.at(self.env_ref.stat_leaks_by_kind, (b, att), 1.0)

        # 通した送り主にライフを 1 返す。**上限は超えない。** (Island#rewardSender)
        # 送りが「相手を削る」だけでなく「自分の立て直し」にもなるので、
        # 削られた側が攻めに転じる道ができる。
        sender = self.en_sender[b, e]
        pay = (sender >= 0) & (sender != b) & self.alive[np.maximum(sender, 0)]
        if pay.any():
            who = sender[pay]
            # 上限に張り付いていれば増えない。まとめて足してから上限で切るのは、
            # 1 体ずつ上限付きで足すのと同じ結果になる
            room = self.max_lives[who] > self.lives[who]
            np.add.at(self.stat_breakthrough, who, 1.0)
            gained = np.zeros(self.n, dtype=np.float32)
            np.add.at(gained, who[room], float(B.LEAK_LIFE_REWARD))
            before = self.lives.copy()
            self.lives = np.minimum(self.max_lives, self.lives + gained)
            self.stat_life_regained += self.lives - before

        # 災厄はコアに触れても消えない。出発点へ戻り、倒し切るまで何周でも来る。
        # HP も状態異常もそのまま残す（全快させると削った時間が丸ごと無駄になる）。
        # 周回するたびに送り主のライフが戻るのは Java と同じ挙動
        boss = self.tables.en_boss[np.maximum(self.en_body[b, e], 0)]
        self.en_progress[b[boss], e[boss]] = 0.0
        self.en_alive[b[~boss], e[~boss]] = False
