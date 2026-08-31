# -*- coding: utf-8 -*-
"""環境の刻みと打ち切り条件。**ゲームの数値ではなくシミュレータの設定**を置く。

数値（塔・敵・経済）は :mod:`balance` にあり、ここには「どれくらいの粒度で
回すか」だけを置く。混ぜるとバランス調整者がどちらを触ればよいのか分からなくなる。

刻みの決め方（なぜこの値か）
----------------------------
``DECISION_TICKS`` = 20 (1.0 秒)
    人が対戦で 1 秒に 2 手も 3 手も打つことはない。0.5 秒刻みにするとエピソード長
    が倍になり、サンプル数と学習時間もそのまま倍になる割に、方策の質は上がらない。

``COMBAT_DT`` = 4 tick (0.2 秒)
    戦闘だけは実ゲームと同じ式で回す必要がある。1 tick 刻みは正確だが 5 倍重い。
    4 tick 刻みでも **DPS は完全に保たれる**（1 サブステップ中に撃てる回数を
    ``floor(蓄積 / クールダウン)`` で数え、余りを次へ繰り越すため）。
    ずれるのは「狙いを 0.2 秒ごとにしか変えない」ことだけで、
    最速の塔（連射弓 cd=4 tick）でも 1 サブステップ 1 発なので実害がない。
    ``COMBAT_DT=1`` にすれば完全一致で、忠実度の検証に使える。
"""

from __future__ import annotations

from dataclasses import dataclass

import balance as B

#: 1 意思決定 = 何ゲーム tick か
DECISION_TICKS = 20
#: 戦闘サブステップの刻み（tick）
COMBAT_DT = 4

#: 折れ線の最大ウェイポイント数。迷路が育つと曲がり角が増えるので余裕を持つ
MAX_WAYPOINTS = 64


@dataclass
class EnvConfig:
    """1 バッチぶんの環境設定。GUI からは環境変数経由で渡ってくる。"""

    num_envs: int = 32
    #: 各試合の人数。``None`` なら ``players_choices`` から抽選する
    num_players: int = 2
    #: 抽選する人数の候補（カリキュラムで広げる）
    players_choices: tuple = (2,)
    #: 盤面の一辺。``balance.BOARD.size`` を上書きする（カリキュラム用）
    board_size: int = 21
    #: 使える送りモンスターを安い順に何種類までに絞るか（カリキュラム用）
    attacker_limit: int = len(B.ATTACKER_ORDER)
    #: ドメインランダム化の強さ（0 で無効）
    randomize: float = 0.0
    #: 意思決定 1 回のゲーム tick 数
    decision_ticks: int = DECISION_TICKS
    #: 戦闘サブステップの tick 数
    combat_dt: int = COMBAT_DT
    #: 打ち切り（引き分け）までのゲーム tick 数。既定は 20 分
    max_ticks: int = 20 * 60 * 20
    seed: int = 0

    @property
    def steps_per_episode(self) -> int:
        return self.max_ticks // self.decision_ticks


#: カリキュラム。``train.py`` が世代の進み具合で切り替える。
#:
#: **``max_ticks`` がいちばん効く。** 1 試合が 20 分（1200 ステップ）だと、
#: 128 ステップのロールアウトには勝敗が 1 度も入らない。すると PPO は
#: 「勝った / 負けた」を一度も見ないまま整形報酬だけで学ぶことになる
#: （実際に 8 世代回して ``games_finished: 0`` のまま伸びなかった）。
#: 序盤は 4 分まで詰めて、**1 世代のあいだに必ず決着を経験させる**。
CURRICULUM = (
    # (名前, board_size, players_choices, attacker_limit, max_ticks)
    ("基礎 2人 15x15 走狗のみ 4分", 15, (2,), 2, 20 * 60 * 4),
    ("拡張 2人 17x17 中盤まで 6分", 17, (2,), 6, 20 * 60 * 6),
    ("多人数 2-4人 21x21 全種 8分", 21, (2, 3, 4), 12, 20 * 60 * 8),
    ("完全 2-8人 21x21 全種 12分", 21, (2, 3, 4, 5, 6, 8), 12, 20 * 60 * 12),
)


def curriculum_stage(generation: int, total: int) -> int:
    """世代の進み具合から段階を決める。

    学習全体を 4 段階に等分する。以前は「4 割で最終段階へ」にしていたが、
    それだと短い試合を経験する世代が少なすぎて、勝敗の信号が入る前に
    20 分マッチへ移ってしまった。**基礎を十分に踏ませてから広げる。**
    """
    if total <= 1:
        return len(CURRICULUM) - 1
    ratio = generation / max(1.0, float(total))
    return min(len(CURRICULUM) - 1, int(ratio * len(CURRICULUM)))


def apply_curriculum(cfg: EnvConfig, stage: int) -> EnvConfig:
    name, size, players, limit, ticks = CURRICULUM[min(stage, len(CURRICULUM) - 1)]
    cfg.board_size = size
    cfg.players_choices = players
    cfg.attacker_limit = limit
    cfg.max_ticks = ticks
    return cfg
