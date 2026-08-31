# -*- coding: utf-8 -*-
"""報酬。**勝敗が主軸で、整形は主軸を壊さない程度に小さく。**

なぜ整形を入れるのか
--------------------
勝敗だけだと、1 試合 15 分・900 手のあいだ報酬が一切来ない。序盤の
「壁をどこに置くか」が勝敗に効くまでの距離が遠すぎて、PPO では学習が進まない。
そこで **「効いている手」が即座に分かる指標** を小さく足す。

なぜこの 3 つなのか
-------------------
- ``coverage``: このゲームの核心は「経路の長さ」ではなく「形」。
  蛇行させて一つの塔の射程を何度も通させるほど投資効率が上がる。
  経路長だけを褒めると、塔の射程から外れた無駄に長い迷路を作る。
- ``idle``: 経路から外れた場所に塔を建てる癖を咎める。
- ``hand``: カードを使わずに手札上限で止まる停滞を防ぐ。
  カードは 30 秒に 1 枚しか来ないので、上限で溢れさせるのは純粋な損失。

**生存ボーナスは付けない。** 「脱落しなければ得」にすると、送らずに
引きこもるだけの手抜きが強くなり、送り合いというゲームの中心が消える。
"""

from __future__ import annotations

import numpy as np

# ---- 主軸 ----------------------------------------------------------
WIN = 1.0
LOSE = -1.0
#: FFA の順位報酬。1 位 +1.0 / 2 位 +0.2 / 3 位 0 / 以降 -0.2
FFA_RANK_REWARD = (1.0, 0.2, 0.0)
FFA_RANK_TAIL = -0.2

COIN_GAIN = 0.001        # 毎ステップのコイン獲得
LIFE_LOST = -0.5         # ライフ 1 につき
#: 送りが相手のコアに届いて **自分の** ライフが戻ったぶん。
#: 減少と対称にしてある。上限を超えられないので稼ぎ続けることはできず、
#: 「削られたぶんを攻めで取り返す」動きだけが報われる
LIFE_REGAINED = 0.5
MAX_LIFE_STOLEN = -0.5   # 終焉騎にライフ上限を奪われた

# ---- 整形（合計で主軸の 10% 以下に収める） --------------------------
COVERAGE_GAIN = 0.01     # 射程カバレッジ重み付き経路長の増分
IDLE_PENALTY = -0.01     # 塔の遊休率
HAND_FULL_PENALTY = -0.005  # 手札が上限のまま余している歩数
#: 空振り（マスクを通ったが「経路が塞がる」等で弾かれた手）。
#: 全セル x 全形の封鎖判定はコストが高すぎてマスクに入れられないので、
#: 選ばれた 1 手だけ検査して弾く。その学習信号として最小限の罰を置く。
INVALID_ACTION = -0.002


def rank_reward(rank: np.ndarray, players: np.ndarray) -> np.ndarray:
    """順位に応じた終端報酬。1 対 1 はそのまま勝敗になる。"""
    out = np.full(rank.shape, FFA_RANK_TAIL, dtype=np.float32)
    for i, value in enumerate(FFA_RANK_REWARD):
        out = np.where(rank == i, value, out)
    # 2 人戦は 1 位 +1 / 2 位 -1（順位報酬ではなく勝敗）
    duel = players <= 2
    out = np.where(duel, np.where(rank == 0, WIN, LOSE), out)
    return out


def step_reward(coins_gained: np.ndarray, lives_lost: np.ndarray,
                max_lives_lost: np.ndarray, coverage_delta: np.ndarray,
                idle_rate: np.ndarray, hand_full: np.ndarray,
                invalid: np.ndarray,
                lives_gained: np.ndarray) -> np.ndarray:
    """1 ステップぶんの途中報酬。

    :param coverage_delta: 正規化済みの「射程カバレッジ重み付き経路長」の増分
    :param idle_rate: 射程内に敵が来なかった塔の割合 0..1
    :param hand_full: 手札が上限のまま何も置かなかったか
    :param invalid: このステップで空振りした手の数
    :param lives_gained: 送りが通って取り戻したライフ
    """
    return (COIN_GAIN * coins_gained
            + LIFE_LOST * lives_lost
            + LIFE_REGAINED * lives_gained
            + MAX_LIFE_STOLEN * max_lives_lost
            + COVERAGE_GAIN * coverage_delta
            + IDLE_PENALTY * idle_rate
            + HAND_FULL_PENALTY * hand_full
            + INVALID_ACTION * invalid).astype(np.float32)
