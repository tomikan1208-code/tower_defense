# -*- coding: utf-8 -*-
"""観測の組み立て。

**設計の核: 生の絶対値をほとんど入れない。**
「弓塔のコストは 30」を覚えた方策は、コストを 35 に変えた瞬間に壊れる。
代わりに「いちばん安い塔は何秒ぶんのインカムで買えるか」のような **比** を渡す。
数値を変えても比の意味は変わらないので、**再学習なしである程度動く**。
ドメインランダム化 (:func:`balance.randomized_balance`) と対で効く。

**ただし「良い塔かどうか」の合成値は渡さない。**
以前は塔の特徴の先頭 2 つが ``DPS / コスト`` だった。その DPS は
``ダメージ x 20 / クールダウン`` で、splash・chain・slow・burn・支援効果を
一切含まない見かけの値だったので、観測は

===========  =====================
弓塔          0.389（1 位）
弩塔          0.360（貫通 3 体を無視して 2 位）
雷塔          0.231（連鎖 3 体を無視）
砲塔          0.118（splash を無視）
呪詛塔/監視塔  **0.000**
===========  =====================

と言っていた。壊れていた旧報酬（コイン獲得を最大化する形になっていた）と
噛み合って、方策は弓と弩しか建てなくなっていた。
**比を渡すことと、価値の合成を渡すことは別**で、後者は設計者の答えの押し付けになる。
いまは生の性能と土台の形だけを渡し、合成は方策に任せる。

盤面チャンネルの選び方
----------------------
射程カバレッジのヒートマップ (:data:`CH_COVERAGE`) が肝。
「蛇行させて一つの塔の射程を何度も通す」というこのゲームの核心を、
CNN が直接見られる形にしてある。経路チャンネルと重ねれば
「どこを通れば何基に撃たれるか」がそのまま画像になる。

もう 1 つの肝が :data:`CH_PAD_NOW` と :data:`CH_PAD_GAIN`
(:func:`grid.big_pad_masks`)。大型塔は 2x2 や 1x3 の土台を要求するのに、
塔の設置マスクは「いま置けるか」しか伝えない。**「あと 1 マスで土台になる」**
はここでしか読めない。ベンチの実測では、迷路だけ土台重視にすると漏れが倍に
悪化し、塔だけ大型にすると土台不足で 24 枠中 15 基しか建たない。
**両方を同時に変えて初めて漏れが半分以下になる**ので、報酬の勾配だけで
この谷を渡るのは難しい。

相手の観測（§4-4）
------------------
実ゲームで **本当に見える情報だけ** を全開示する。相手の島へは飛んで
見に行けるので、迷路の形・タワーの配置・強化状態は実質公開情報。
見えないのはコイン・インカム・ストック・手札で、これらは
**送りの履歴からの推定値**だけを渡す。
部分観測にすると PPO が「見えない相手の状態の推定」まで同時に学ぶことになり
収束が遅くなるので、取れる情報は全部渡して MDP に近づけている。

人数が 2〜8 で変わるので、相手は **最大人数ぶんの固定スロット + マスク**
にしてある。ピクセルではなく要約特徴（経路長・塔の本数と平均 Lv・カバレッジ）
にするのは学習速度のためではなく、**観測次元の肥大で探索とメモリが悪化するのを
避けるため**。情報は落とさず形だけ圧縮している。
"""

from __future__ import annotations

from typing import Dict

import numpy as np

import balance as B

# ---- 盤面チャンネル ------------------------------------------------
CH_IN_BOARD = 0     # 盤面の内側か（小さい盤面をパディングするため）
CH_WALKABLE = 1     # 地上の敵が通れる
CH_BUILDABLE = 2    # カードを置ける（＝ OPEN）
CH_TOWER_BASE = 3   # 塔を載せられる（WALL / ROCK）
CH_ROCK = 4         # 初期地形の岩＝無料の土台
CH_SPAWN = 5
CH_CORE = 6
CH_TOWER = 7        # 塔が占有しているセル
CH_COVERAGE = 8     # 何基の塔の射程に入っているか（正規化）
CH_PATH = 9         # 地上の敵の経路
CH_FLIGHT = 10      # 飛行敵の直線ルート（迷路を無視する）
CH_ENEMY = 11       # 敵の数（正規化）
CH_ENEMY_HP = 12    # 敵の HP 比の合計
CH_ENEMY_THREAT = 13  # コアへの近さで重み付けした敵の圧力
#: いま大型塔（2x2 / 1x3）を載せられる土台
CH_PAD_NOW = 14
#: **あと 1 マス置けば**大型塔の土台になる、カードを置けるセル
CH_PAD_GAIN = 15
N_CHANNELS = 16

#: 相手 1 人ぶんの特徴数
OPP_FEATURES = 14
#: 塔 1 種ぶんの特徴数
TOWER_FEATURES = 19
#: 送り 1 種ぶんの特徴数
ATTACK_FEATURES = 9

N_TOWER = len(B.TOWER_ORDER)
N_ATTACK = len(B.ATTACKER_ORDER)

#: 経済・状況のスカラー（比率）
BASE_SCALARS = 30

SCALAR_DIM = (BASE_SCALARS + N_TOWER * TOWER_FEATURES
              + N_ATTACK * ATTACK_FEATURES)


def _footprint_table() -> np.ndarray:
    """塔ごとの土台の形。``(K, 2)`` = (セル数, 長辺)。

    塔の形は 4 種類しかなく、この 2 つで一意に区別できる::

        DOT (1x1) = (1, 1)   I2 (1x2) = (2, 2)
        I3  (1x3) = (3, 3)   O  (2x2) = (4, 2)

    ドメインランダム化は数値を揺らすが形は変えないので、起動時に一度作れば足りる。
    """
    out = np.zeros((N_TOWER, 2), dtype=np.float32)
    for k, key in enumerate(B.TOWER_ORDER):
        cells = B.SHAPE_CELLS[B.TOWERS[key].shape]
        w = max(c[0] for c in cells) + 1
        h = max(c[1] for c in cells) + 1
        out[k] = (len(cells), max(w, h))
    return out


FOOTPRINT = _footprint_table()


def _safe_div(a, b, cap: float = 10.0):
    """0 割りを避けつつ、外れ値でネットワークが壊れないよう頭を打つ。"""
    return np.clip(a / np.maximum(b, 1e-6), -cap, cap)


class ObservationBuilder:
    """観測バッファを使い回して組み立てる。

    毎ステップ ``(B, 14, 27, 27)`` を確保し直すと GC が効いてくるので、
    バッファは 1 度だけ作って ``fill`` で上書きする。
    """

    def __init__(self, n_boards: int, board_size: int, max_towers: int):
        self.n = n_boards
        self.size = board_size
        self.max_towers = max_towers
        self.grid = np.zeros((n_boards, N_CHANNELS, B.MAX_BOARD, B.MAX_BOARD),
                             dtype=np.float32)
        self.scalar = np.zeros((n_boards, SCALAR_DIM), dtype=np.float32)
        self.opponents = np.zeros((n_boards, B.MAX_PLAYERS, OPP_FEATURES),
                                  dtype=np.float32)
        self.opp_mask = np.zeros((n_boards, B.MAX_PLAYERS), dtype=np.float32)

    # ---------------------------------------------------------------- 盤面
    # 静的チャンネル・塔・経路は env 側が (B, ...) の配列を丸ごと差し込む。
    # 島ごとに 1 枚ずつ書き込むメソッドを持っていたが、1 ステップで島の数だけ
    # Python ループが回るので、まとめて代入する形に置き換えた。
    def fill_enemies(self, positions: np.ndarray, alive: np.ndarray,
                     hp_ratio: np.ndarray, threat: np.ndarray,
                     size: int) -> None:
        """敵の 3 チャンネルを一括で作る（全島まとめて）。"""
        self.grid[:, CH_ENEMY] = 0.0
        self.grid[:, CH_ENEMY_HP] = 0.0
        self.grid[:, CH_ENEMY_THREAT] = 0.0
        if not alive.any():
            return
        b, e = np.nonzero(alive)
        x = np.clip(positions[b, e, 0].astype(np.int32), 0, size - 1)
        z = np.clip(positions[b, e, 1].astype(np.int32), 0, size - 1)
        np.add.at(self.grid, (b, CH_ENEMY, z, x), 0.25)
        np.add.at(self.grid, (b, CH_ENEMY_HP, z, x), hp_ratio[b, e] * 0.25)
        np.add.at(self.grid, (b, CH_ENEMY_THREAT, z, x), threat[b, e] * 0.25)


def build_unit_features(tables, env_of: np.ndarray, income: np.ndarray,
                        coins: np.ndarray, stock: np.ndarray,
                        board_size: int,
                        opponents_alive: np.ndarray) -> np.ndarray:
    """塔と送りの「性能 / コスト」特徴。 (§4-3)

    **数値を変えても壊れない**ための中心部分。実効 DPS も送りの効用も、
    絶対値ではなくコストとの比で渡す。塔のコストを 30 → 35 に変えたら
    この比が下がるので、方策は「割に合わなくなった」ことを観測から読める。
    """
    n = len(env_of)
    env = env_of
    # 撃破報酬の総量は人数によらず一定なので、1 体あたりの取り分は
    # 「いま何人に湧くか」だけで決まる (AttackerKind#KILL_REWARD_TOTAL)
    opponents = np.maximum(1.0, opponents_alive.astype(np.float32))
    out = np.zeros((n, N_TOWER * TOWER_FEATURES + N_ATTACK * ATTACK_FEATURES),
                   dtype=np.float32)

    # --- 塔 ---
    # **合成値は渡さない。** Lv0 と Lv3 の両方を見せるのは残す
    # （「いま買える強さ」と「伸びしろ」は別の判断だから）。
    cost = tables.tw_cost[env].astype(np.float32)           # (n, K)
    top = B.MAX_TOWER_LEVEL
    full_cost = (cost + tables.tw_upgrade[env, :, :top].sum(axis=2)).astype(np.float32)
    rate0 = B.TICKS_PER_SECOND / np.maximum(tables.tw_cooldown[env, :, 0, 0], 1.0)
    rate3 = B.TICKS_PER_SECOND / np.maximum(tables.tw_cooldown[env, :, top, 1], 1.0)
    shape = cost.shape

    feats = [
        # 経済
        cost / 100.0,
        _safe_div(cost, income[:, None]) / 10.0,            # 何秒ぶんのインカムか
        full_cost / 400.0,
        # 攻撃力（ダメージと発射レートを分けて渡す。掛けるかどうかは方策が決める）
        tables.tw_damage[env, :, 0, 0] / 50.0,
        tables.tw_damage[env, :, top, 1] / 50.0,
        rate0 / 2.0,                                        # 発 / 秒
        rate3 / 2.0,
        tables.tw_range[env, :, 0, 0] / board_size,
        tables.tw_range[env, :, top, 1] / board_size,
        # 単発ダメージに乗らない効果。ここが無いと支援・妨害塔が「性能 0」に見える
        tables.tw_splash[env, :, 0, 0] / 4.0,
        tables.tw_chain[env, :, 0, 0].astype(np.float32) / 8.0,
        tables.tw_slow[env, :, 0, 0],
        tables.tw_burn[env, :, 0, 0] / 20.0,
        tables.tw_vuln[env, :, 0, 0],                       # 呪詛（旧観測に無かった）
        tables.tw_boost_dmg[env, :, 0, 0],                  # 支援・与ダメ（同上）
        tables.tw_boost_rate[env, :, 0, 0],                 # 支援・連射（同上）
        tables.tw_banish[env, :, 0, 0],                     # 送還（同上）
        # 土台の形。塔の設置マスクは「いま置けるか」しか伝えないので、
        # 「この塔は 2x2 を要求する」という**ルールそのもの**を明示する
        np.broadcast_to(FOOTPRINT[None, :, 0] / 4.0, shape),
        np.broadcast_to(FOOTPRINT[None, :, 1] / 3.0, shape),
    ]
    tower_block = np.stack(feats, axis=2).reshape(n, -1)
    out[:, :N_TOWER * TOWER_FEATURES] = tower_block

    # --- 送りモンスター ---
    a_cost = tables.at_cost[env].astype(np.float32)          # (n, A)
    a_hp = tables.at_hp[env]
    body = tables.at_body                                    # (A,)
    speed = tables.en_speed[env][:, body] * B.TICKS_PER_SECOND
    # リーク効用: 硬さ x 速さ。相手の防衛を抜けてコアに届く見込み
    leak_utility = a_hp * speed
    feats_a = [
        _safe_div(a_hp, a_cost) / 3.0,                       # HP / コスト
        _safe_div(speed, a_cost) * 10.0,                     # 速度 / コスト
        _safe_div(leak_utility, a_cost) / 10.0,              # リーク効用 / コスト
        _safe_div(tables.at_income[env], a_cost) * 20.0,     # インカム増 / コスト
        tables.at_unlock[env] / 300.0,                       # 解禁インカム
        # 防御側の回収率。総量は固定なので、いま何人に湧くかで 1 体あたりが決まる
        _safe_div(tables.at_reward_total[env],
                  a_cost * opponents[:, None]),
        tables.at_stock[env] / 5.0,
        (income[:, None] >= tables.at_unlock[env]).astype(np.float32),
        ((coins[:, None] >= a_cost)
         & (stock[:, None] >= tables.at_stock[env])).astype(np.float32),
    ]
    out[:, N_TOWER * TOWER_FEATURES:] = np.stack(feats_a, axis=2).reshape(n, -1)
    return out


def observation_spec() -> Dict[str, tuple]:
    """ネットワーク側が入力の形を知るための仕様。"""
    return {
        "grid": (N_CHANNELS, B.MAX_BOARD, B.MAX_BOARD),
        "scalar": (SCALAR_DIM,),
        "opponents": (B.MAX_PLAYERS, OPP_FEATURES),
        "opp_mask": (B.MAX_PLAYERS,),
    }
