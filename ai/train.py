# -*- coding: utf-8 -*-
"""MAZEWARD VERSUS の学習。**PPO ＋ リーグ自己対戦 ＋ 模倣学習の初期化。**

設計の要
--------
**迷路・タワー・送りを 1 つの方策で結合学習する。**
「塔だけ」「送りだけ」といった単独学習モードは意図的に用意していない。
3 つは同じコインと同じカードを奪い合っているので、分けて学ばせると
配分の判断そのものが消えてしまう（:mod:`policy` の説明も参照）。

学習を速くするための順序（プロファイル済み）
--------------------------------------------
1. **模倣学習（BC）で初期化** … 基準ボットの手を教師にして actor を先に
   温める。ランダム初期方策の迷走を丸ごと省ける。BC 後は entropy ボーナスと
   clipping を効かせて、ヒューリスティックの癖に張り付かないようにする
2. **環境のベクトル化** … 全島を 1 本の numpy 配列で一括計算（:mod:`mazeward_env.combat`）
3. **不要な計算を削る** … 弾を持たない / 経路の引き直しは壁を置いたときだけ /
   敵の枠を毎ステップ前詰めして距離行列を小さく保つ
4. **カリキュラム** … 小さい盤面・少人数・基本の敵から始める（:mod:`mazeward_env.rules`）

Numba / C++ 化には進んでいない。cProfile で測った結果、いまの律速は
Theta* と観測の組み立てで、どちらも「呼ぶ回数を減らす」ほうが効いたため。
本当に足りなくなったら ``Grid → combat → observation`` を同じ step/reset API で
差し替えられるようにしてある。

設定は環境変数で渡す（``references/trainer-contract.md`` §4）。
"""

from __future__ import annotations

import itertools
import json
import math
import os
import random
import signal
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import balance as B                                          # noqa: E402
import trainer_pb as pb                                      # noqa: E402
from mazeward_env.bots_heuristic import empty_action, make_bot  # noqa: E402
from mazeward_env.env import (A_CARD, A_SELL, A_SEND, A_SKIP, A_TOWER,   # noqa: E402
                              A_UPGRADE, ACTION_HEADS, VersusEnv)
from mazeward_env.observation import (N_CHANNELS, OPP_FEATURES,   # noqa: E402
                                      SCALAR_DIM)
from mazeward_env.rules import (CURRICULUM, EnvConfig, apply_curriculum,  # noqa: E402
                                curriculum_stage)
from mazeward_env import reward as R                          # noqa: E402
from policy import OBS_SCALE, Policy, masked_logits           # noqa: E402

PREFIX = "MAZEWARD"
BASE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(BASE, "models")
LOG_NAME = "ppo"

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# **分布の引数検証を切る。** 学習ループでは同じ形の logits を毎 step 何万回も
# 通すので、simplex 判定と shape 検証が純粋な税になる（実測で act が 12.7 →
# 9.8 ms）。壊れた logits は NaN として loss に出るので、検証で守る意味が薄い
torch.distributions.Distribution.set_default_validate_args(False)
if DEVICE.type == "cuda":
    # 盤面 CNN は形が固定なので、最初の数回でいちばん速い実装を選ばせる
    torch.backends.cudnn.benchmark = True
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True

def _amp_setting() -> Tuple[Optional["torch.dtype"], bool]:
    """自動混合精度の型を GPU 世代から決める。``(dtype, スケーラが要るか)``。

    **``torch.cuda.is_bf16_supported()`` を信じてはいけない。** 既定引数が
    ``including_emulation=True`` なので、Turing (T4) のように bf16 の
    ハードウェア対応が無い GPU でも **エミュレーションで True を返す**。
    掴むと fp32 より遅くなる。Colab の無料枠は T4 なので実害が出る。

    - compute capability 8.0 以上 (Ampere / Ada / Hopper) … bf16。
      指数部が fp32 と同じなので ``GradScaler`` が要らない
    - 7.x (Volta / Turing = V100 / T4) … fp16。fp16 のテンソルコアは速いが
      指数部が狭いので勾配が 0 に潰れる。``GradScaler`` が要る
    - それ未満 … 混合精度を使わない
    """
    if DEVICE.type != "cuda":
        return None, False
    major = torch.cuda.get_device_capability()[0]
    if major >= 8:
        return torch.bfloat16, False
    if major == 7:
        return torch.float16, True
    return None, False


AMP_DTYPE, AMP_NEEDS_SCALER = _amp_setting()
AMP_OK = AMP_DTYPE is not None
#: fp16 のときだけ使う。スケールは学習中に自動調整されるので使い回す
_SCALER = (torch.amp.GradScaler("cuda") if AMP_NEEDS_SCALER else None)


# ════════════════════════════════════════════════════════════════════
# 設定
# ════════════════════════════════════════════════════════════════════
#: 世代数に上限が無いとき、カリキュラムを 1 周させる世代数。
#: 4 段階 x 10 世代。GUI の既定（20 世代）より長いのは、上限なしで回すなら
#: 「基礎を十分に踏ませてから広げる」余裕があるから。
DEFAULT_CURRICULUM_GENS = 40


@dataclass
class TrainConfig:
    #: 回す世代数。**0 以下なら上限なし**で、停止要求（GUI の停止ボタン、
    #: または Ctrl+C）が来るまで回り続ける。
    max_gens: int = pb.env_int(f"{PREFIX}_MAX_GENS", 20)
    #: カリキュラムを何世代かけて 1 周させるか。0 なら :attr:`max_gens`。
    #:
    #: **なぜ分けるか。** 以前は世代数がそのまま分母だったので、長く回そうと
    #: 大きい値を入れると ``ratio = gen / max_gens`` がいつまでも小さいまま、
    #: 第 1 段階（15x15・2人・走狗のみ）から一生上がらなかった。逆に再開時は
    #: ``gen`` だけが伸びて分母は 1 回ぶんのままなので、2 回目以降はいきなり
    #: 最終段階に貼り付いた。**分母は実行回数と無関係に決める。**
    curriculum_gens: int = pb.env_int(f"{PREFIX}_CURRICULUM_GENS", 0)
    #: 同時に走らせる試合の枠数。**0 を渡したときだけ**実機から自動で決める
    #: (:func:`trainer_pb.auto_num_envs`)。既定を 64 にしてあるのは、
    #: 12 コアの実測で総スループットが 48 枠あたりで頭打ちになり、
    #: そこから先は「1 世代あたりの対局数」が増えるだけだから。
    #: バランス判断のサンプル数はそのぶん増える
    num_envs: int = pb.env_int(f"{PREFIX}_NUM_ENVS", 64)
    rollout: int = pb.env_int(f"{PREFIX}_ROLLOUT", 96)
    epochs: int = pb.env_int(f"{PREFIX}_EPOCHS", 3)
    minibatch: int = pb.env_int(f"{PREFIX}_MINIBATCH", 1024)
    lr: float = pb.env_float(f"{PREFIX}_LR", 2.5e-4)
    #: 割引率。既定は :data:`reward.GAMMA`（1 つしか無い値なので、
    #: 環境変数で上書きしたら整形側にも代入し直す。:func:`_sync_gamma`）
    gamma: float = pb.env_float(f"{PREFIX}_GAMMA", R.GAMMA)
    gae_lambda: float = pb.env_float(f"{PREFIX}_GAE", 0.95)
    clip: float = pb.env_float(f"{PREFIX}_CLIP", 0.2)
    entropy: float = pb.env_float(f"{PREFIX}_ENTROPY", 0.02)
    value_coef: float = pb.env_float(f"{PREFIX}_VALUE_COEF", 0.5)
    max_grad_norm: float = pb.env_float(f"{PREFIX}_GRAD_NORM", 0.5)
    #: ドメインランダム化の強さ。**既定は 0（無効）**。
    #: 揺らしたままだと、指標の変化がバランス調整のせいなのか
    #: ランダム化のせいなのか切り分けられない
    randomize: float = pb.env_float(f"{PREFIX}_RANDOMIZE", 0.0)
    bc_batches: int = pb.env_int(f"{PREFIX}_BC_BATCHES", 60)
    eval_every: int = pb.env_int(f"{PREFIX}_EVAL_EVERY", 5)
    eval_games: int = pb.env_int(f"{PREFIX}_EVAL_GAMES", 12)
    #: リーグで基準ボットが相手になる確率
    bot_ratio: float = pb.env_float(f"{PREFIX}_BOT_RATIO", 0.35)
    seed: int = pb.env_int(f"{PREFIX}_SEED", 0)
    curriculum: int = pb.env_int(f"{PREFIX}_CURRICULUM", 1)
    #: 相手を席ごとに引くか（0 = 従来どおり試合ごと）。
    #: :func:`assign_controllers` を参照
    seat_opponents: int = pb.env_int(f"{PREFIX}_SEAT_OPPONENTS", 1)
    #: 1 世代で同時に相手にする過去チェックポイントの数。
    #: :meth:`League.refresh_past_pool` を参照
    past_slots: int = pb.env_int(f"{PREFIX}_PAST_SLOTS", 2)
    #: PPO 更新を bf16 の自動混合精度で回すか。0 で従来どおり fp32。
    #: 実測（RTX 3050・学習島 225）で 1 チャンク 9.3 → 4.9 秒
    amp: int = pb.env_int(f"{PREFIX}_AMP", 1)

    # ---- 世代の区切り（ゲーム内時間と試合完了率で決める） ----
    #: ここまでは「全部の試合が終わる」ことを待つ（ゲーム内・分）
    gen_early_minutes: float = pb.env_float(f"{PREFIX}_GEN_EARLY_MIN", 20.0)
    #: ここを超えたら完了率に関わらず打ち切る（ゲーム内・分）
    gen_max_minutes: float = pb.env_float(f"{PREFIX}_GEN_MAX_MIN", 30.0)
    #: 要求する完了率。**最初からこの値で判定する。**
    #:
    #: 以前は 1.0 で、``gen_early_minutes``（20 分）を過ぎるまで
    #: 「全枠が 1 試合以上を終える」ことを待っていた。人数は 2〜8 人で
    #: 抽選されるので、**8 人戦の 1 枠が世代全体の長さを決めてしまう**。
    #: 実測では 1 枠あたり平均 3.1 試合が終わっているのに、世代は上限の
    #: 30 分まで走り切っていた（``games_finished: 198`` / 64 枠）
    gen_finish_early: float = pb.env_float(f"{PREFIX}_GEN_FINISH_EARLY", 0.9)
    #: 30 分を過ぎたあとに要求する完了率
    gen_finish_late: float = pb.env_float(f"{PREFIX}_GEN_FINISH_LATE", 0.9)
    #: **1 試合の時間切れ（ゲーム内・分）。0 = 時間切れなし。**
    #:
    #: 0 なら世代の打ち切り (:attr:`gen_max_minutes`) まで走るので、
    #: 試合は「誰かが脱落して決着した」ときだけ終わる。勝手に打ち切らない。
    #: カリキュラムの試合上限より優先される。
    #:
    #: 既定を 0 にしてある理由: 上限 12 分だとサドンデス（15 分で漏れダメージ
    #: 2 倍）が**一度も発動しない**。ゲーム側が用意している膠着打破の仕組みを
    #: 殺したまま「時間切れ＝完了」と数えていた。
    match_max_min: float = pb.env_float(f"{PREFIX}_MATCH_MAX_MIN", 0.0)

    def match_max_ticks(self) -> int:
        """1 試合の上限 tick。0 指定なら世代の打ち切りまで走らせる。"""
        minutes = self.match_max_min if self.match_max_min > 0 else self.gen_max_minutes
        return int(minutes * 60 * B.TICKS_PER_SECOND)

    def curriculum_total(self) -> int:
        """カリキュラムの分母。上限なしのときは既定の長さに落とす。"""
        if self.curriculum_gens > 0:
            return self.curriculum_gens
        if self.max_gens > 0:
            return self.max_gens
        return DEFAULT_CURRICULUM_GENS


#: 停止要求。GUI の停止ボタンと Ctrl+C の両方がこれを立てる。
#: **プロセスを即殺さずフラグにする**のは、世代の途中で殺すとその世代ぶんの
#: 学習が ``ppo_latest.pt`` に入らないまま消えるため。塊の切れ目で抜けて、
#: いつもどおり保存してから終わる。
STOP = threading.Event()


def _on_stop(signum, frame) -> None:      # noqa: ARG001
    if STOP.is_set():
        # 2 度目は待たない。1 度目のあと保存で固まったときの逃げ道
        print("停止要求（2 回目）。ただちに終了します", flush=True)
        raise SystemExit(1)
    STOP.set()
    print("停止要求を受けました。いまの塊を終えて保存してから終了します",
          flush=True)


def install_stop_handlers() -> None:
    """止められる口をすべて塞ぐ。**受け取れないシグナルは黙って飛ばす。**

    Windows の ``SIGTERM`` は ``TerminateProcess`` で配送されず捕まえられない
    ので、ダッシュボード側は ``CTRL_BREAK_EVENT``（= ``SIGBREAK``）を送る。
    """
    for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
        sig = getattr(signal, name, None)
        if sig is None:
            continue
        try:
            signal.signal(sig, _on_stop)
        except (ValueError, OSError):
            pass


def generation_done(game_minutes: float, completion: float,
                    cfg: TrainConfig) -> Tuple[bool, str]:
    """世代を打ち切るか。**ゲーム内時間と試合完了率で決める。**

    固定ステップ数で切ると、試合の途中で世代が変わる。すると
    「勝った / 負けた」を含まない世代ができ、``games_finished: 0`` の
    世代が交互に並ぶ（実際にそうなっていた）。**試合の切れ目で区切る**ほうが、
    1 世代 = 意味のあるひとまとまり になる。

    :param game_minutes: この世代でシミュレートしたゲーム内の分数
    :param completion: 1 試合以上を完了した島（試合）の割合
    """
    if game_minutes >= cfg.gen_max_minutes:
        return True, f"ゲーム内 {cfg.gen_max_minutes:.0f} 分超え（打ち切り）"
    if game_minutes >= cfg.gen_early_minutes:
        if completion >= cfg.gen_finish_late:
            return True, (f"ゲーム内 {cfg.gen_early_minutes:.0f} 分以上 かつ "
                          f"完了率 {completion:.0%}")
        return False, ""
    if completion >= cfg.gen_finish_early:
        return True, (f"ゲーム内 {game_minutes:.1f} 分で完了率 "
                      f"{completion:.0%}")
    return False, ""


# ════════════════════════════════════════════════════════════════════
# 観測とバッファ
# ════════════════════════════════════════════════════════════════════
def to_uint8(grid: np.ndarray) -> np.ndarray:
    """観測をロールアウトに溜めるための量子化。

    (T, B, 14, 27, 27) を float32 で持つと 1 世代で 1GB を超える。
    値域は 0〜4 程度なので 1/64 刻みの uint8 で十分。
    """
    return np.clip(grid * OBS_SCALE, 0, 255).astype(np.uint8)


class Rollout:
    """PPO 用の経験バッファ。学習担当の島ぶんだけ溜める。"""

    def __init__(self, steps: int, n: int):
        self.steps, self.n = steps, n
        # **観測の定数から引くこと。** ここに数字を直接書くと、観測を
        # 1 本増やした瞬間に「形が合わない」で学習だけが落ちる
        # （実際に 14 と 210 が固定で書かれていて踏んだ）
        self.grid = np.zeros((steps, n, N_CHANNELS, B.MAX_BOARD, B.MAX_BOARD),
                             np.uint8)
        self.scalar = np.zeros((steps, n, SCALAR_DIM), np.float32)
        self.opponents = np.zeros((steps, n, B.MAX_PLAYERS, OPP_FEATURES),
                                  np.float32)
        self.opp_mask = np.zeros((steps, n, B.MAX_PLAYERS), np.float32)
        self.masks = {k: np.zeros((steps, n, v), bool)
                      for k, v in ACTION_HEADS.items()}
        self.actions = {k: np.zeros((steps, n), np.int64) for k in ACTION_HEADS}
        self.logp = np.zeros((steps, n), np.float32)
        self.value = np.zeros((steps, n), np.float32)
        self.reward = np.zeros((steps, n), np.float32)
        self.done = np.zeros((steps, n), bool)
        self.valid = np.zeros((steps, n), bool)


# ════════════════════════════════════════════════════════════════════
# 行動の引き方（条件付きサンプリング）
# ════════════════════════════════════════════════════════════════════
def obs_to_torch(obs: Dict[str, np.ndarray], idx: np.ndarray) -> Dict[str, torch.Tensor]:
    return {
        "grid": torch.as_tensor(obs["grid"][idx], device=DEVICE),
        "scalar": torch.as_tensor(obs["scalar"][idx], device=DEVICE),
        "opponents": torch.as_tensor(obs["opponents"][idx], device=DEVICE),
        "opp_mask": torch.as_tensor(obs["opp_mask"][idx], device=DEVICE),
    }


@torch.no_grad()
def act(net: Policy, env: VersusEnv, obs: Dict[str, np.ndarray],
        boards: np.ndarray, action: Dict[str, np.ndarray],
        greedy: bool = False):
    """方策から 1 手引いて ``action`` に書き込む。

    ``cell`` のマスクだけは選んだ形に依存するので、``type`` と
    ``card``/``tower`` を引いたあとに環境へ問い合わせる。
    forward は 1 回で、逐次なのはマスク作りとサンプリングだけ。
    """
    if len(boards) == 0:
        return None
    net.eval()
    tobs = obs_to_torch(obs, boards)
    logits, value = net(tobs)

    def pick(name: str, mask_np: np.ndarray) -> Tuple[np.ndarray, torch.Tensor, torch.Tensor]:
        # **分布オブジェクトを作らない**（:meth:`Policy.sample`）。
        # 以前はサンプリング用と log 確率用に ``Categorical`` を 2 回作っていて、
        # ヘッド 7 本ぶんで 14 個できていた
        mask = torch.as_tensor(mask_np, device=DEVICE)
        a, logp = net.sample(logits[name], mask, greedy)
        return a.cpu().numpy(), logp, mask

    stored_masks: Dict[str, np.ndarray] = {}
    a_type, lp_type, m_type = pick("type", obs["mask_type"][boards])
    stored_masks["type"] = obs["mask_type"][boards]
    logp = lp_type

    a_card, lp_card, _ = pick("card", obs["mask_card"][boards])
    a_tower, lp_tower, _ = pick("tower", obs["mask_tower"][boards])
    unit_mask = np.where((a_type == A_UPGRADE)[:, None],
                         obs["mask_unit_upgrade"][boards],
                         obs["mask_unit_sell"][boards])
    a_unit, lp_unit, _ = pick("unit", unit_mask)
    a_send, lp_send, _ = pick("send", obs["mask_send"][boards])
    a_send_n, lp_send_n, _ = pick("send_n", obs["mask_send_n"][boards])
    spec_mask = np.ones((len(boards), ACTION_HEADS["spec"]), bool)
    a_spec, lp_spec, _ = pick("spec", spec_mask)

    stored_masks["card"] = obs["mask_card"][boards]
    stored_masks["tower"] = obs["mask_tower"][boards]
    stored_masks["unit"] = unit_mask
    stored_masks["send"] = obs["mask_send"][boards]
    stored_masks["send_n"] = obs["mask_send_n"][boards]
    stored_masks["spec"] = spec_mask

    action["type"][boards] = a_type
    action["card"][boards] = a_card
    action["tower"][boards] = a_tower
    action["unit"][boards] = a_unit
    action["send"][boards] = a_send
    action["send_n"][boards] = a_send_n
    action["spec"][boards] = a_spec

    cell_mask_all = env.cell_mask(action["type"], action["card"], action["tower"])
    cell_mask = cell_mask_all[boards]
    a_cell, lp_cell, _ = pick("cell", cell_mask)
    action["cell"][boards] = a_cell
    stored_masks["cell"] = cell_mask

    use_cell = torch.as_tensor((a_type == A_CARD) | (a_type == A_TOWER),
                               device=DEVICE).float()
    logp = (logp
            + lp_card * torch.as_tensor(a_type == A_CARD, device=DEVICE).float()
            + lp_tower * torch.as_tensor(a_type == A_TOWER, device=DEVICE).float()
            + lp_cell * use_cell
            + lp_unit * torch.as_tensor((a_type == A_UPGRADE) | (a_type == A_SELL),
                                        device=DEVICE).float()
            + lp_spec * torch.as_tensor(a_type == A_UPGRADE, device=DEVICE).float()
            + lp_send * torch.as_tensor(a_type == A_SEND, device=DEVICE).float()
            + lp_send_n * torch.as_tensor(a_type == A_SEND, device=DEVICE).float())

    actions_np = {"type": a_type, "card": a_card, "tower": a_tower,
                  "cell": a_cell, "unit": a_unit, "spec": a_spec, "send": a_send,
                  "send_n": a_send_n}
    return stored_masks, actions_np, logp.cpu().numpy(), value.cpu().numpy()


# ════════════════════════════════════════════════════════════════════
# 対戦相手の割り当て（リーグ）
# ════════════════════════════════════════════════════════════════════
class League:
    """自分・過去のチェックポイント・基準ボットのプール。

    自己対戦だけだと「自分だけ強くなったつもり」に陥る。**動かない基準**を
    必ず混ぜて、強くなったかを外から測れるようにする。

    塔の構成が違う 3 種 (``arrow_spam`` / ``big_tower`` / ``splash_mix``) を
    入れてあるのは、**相手の塔構成が偏っていると方策もそこに閉じる**ため。
    防衛ベンチ (:mod:`tower_bench`) で測ると、弓だけ 24 基（漏れ率 65%）より
    大型塔を混ぜた構成（49%）のほうが明確に強いのに、旧リーグの相手は
    全員が弓しか建てなかった。**倒すべき相手が弓しか知らなければ、
    弓に勝てるだけの方策で止まる。**
    """

    def __init__(self, cfg: TrainConfig, rng: random.Random):
        self.cfg = cfg
        self.rng = rng
        self.checkpoints: List[str] = []
        self.elo = 1000.0
        self.bots = ("random", "greedy_defense", "income_push",
                     "arrow_spam", "big_tower", "splash_mix")
        #: この世代で相手に使う過去チェックポイント。:meth:`refresh_past_pool`
        self.past_pool: List[str] = []

    def refresh_past_pool(self) -> None:
        """この世代で使う過去の自分を ``cfg.past_slots`` 個だけ選ぶ。

        **凍結ネットは 1 つごとに forward が 1 本増える。** 過去 8 個を
        全部相手にすると、1 step あたり小さなバッチの GPU 呼び出しが 8 本
        走る。計測では ``act`` は 1 回あたり約 11 ms の固定費（カーネル起動と
        Python ディスパッチ）を払っていて、島数を増やしてもここは減らない。
        つまり **相手の種類数はそのまま実時間の税**になる。

        世代ごとに引き直すので、リーグ全体で見れば過去 8 個すべてが相手に
        なる。1 世代の中で同時に何種類と当たるかだけを絞っている。
        """
        k = max(1, int(getattr(self.cfg, "past_slots", 2)))
        if len(self.checkpoints) <= k:
            self.past_pool = list(self.checkpoints)
        else:
            self.past_pool = self.rng.sample(self.checkpoints, k)

    def sample_opponent(self) -> str:
        """相手を 1 つ引く。**``bot_ratio`` を額面どおりに守る。**

        以前は ``or not self.checkpoints`` が付いていて、チェックポイントが
        できる第 ``eval_every`` 世代までは **100% がヒューリスティックボット**
        になっていた。ボットの手番は方策の推論より一桁重い（カード配置を
        Theta* で探索するため）ので、いちばん遅い序盤に、いちばん重い相手を、
        全席に置いていたことになる。過去の自分がまだ居ないなら
        **自己対戦へ落とす**のが正しい代替で、ボットへ落とす理由は無い。
        """
        if self.rng.random() < self.cfg.bot_ratio:
            return self.rng.choice(self.bots)
        pool = self.past_pool or self.checkpoints
        if pool and self.rng.random() < 0.5:
            return "past:" + self.rng.choice(pool)
        return "self"

    def add_checkpoint(self, path: str) -> None:
        self.checkpoints.append(path)
        self.checkpoints = self.checkpoints[-8:]


def load_frozen(league: "League", frozen: Dict[str, "Policy"]) -> None:
    """リーグの過去チェックポイントを読み直す。

    **再開時に必ず呼ぶ。** `league.checkpoints` はチェックポイントに保存され
    復元されるが、重み本体はメモリ上にしか無い。読み直しを忘れると
    「過去の自分」を相手に選んだ瞬間に KeyError で落ちる（実際に踏んだ）。
    """
    frozen.clear()
    for path in league.checkpoints:
        if not os.path.exists(path):
            continue
        try:
            net = Policy().to(DEVICE)
            net.load_state_dict(torch.load(path, map_location=DEVICE,
                                           weights_only=False)["net"])
            net.eval()
            frozen["past:" + path] = net
        except (OSError, KeyError, RuntimeError) as e:
            print(f"警告: チェックポイントを読めませんでした {path}: {e}", flush=True)


def assign_controllers(env: VersusEnv, league: League,
                       rng: random.Random) -> Dict[str, np.ndarray]:
    """席ごとに操作者を決める。席 0 は必ず学習中の方策。

    **相手は試合単位ではなく席単位で引く。**

    以前は試合ごとに相手を 1 つ引いて席 1 以降の全部に適用していた。
    8 人戦でボットを引くと 7 席がボットになり、``bot_ratio`` が 0.35 でも
    **実際にボットが占める島は 79%** だった（実測: 有効 225 島のうち学習側
    48 島、残り 177 島がボット）。シミュレーションの費用は全島ぶん払うのに、
    PPO に入るのは 48 島ぶんだけになる。

    席ごとに引けば ``bot_ratio`` がそのまま「ボットが占める席の割合」になり、
    同じ計算量から取れる学習サンプルが増える。1 つの試合の中に基準ボットと
    過去の自分が混ざることになるが、**混ざったほうがリーグとしては素直**で、
    「相手 8 人全員が同じ弓スパム」のような偏った盤面も消える。

    :envvar:`MAZEWARD_SEAT_OPPONENTS` を 0 にすると試合単位の抽選に戻る。
    """
    kinds: Dict[str, List[int]] = {}
    learner: List[int] = []
    per_seat = bool(getattr(league.cfg, "seat_opponents", 1))
    league.refresh_past_pool()
    for e in range(env.n_envs):
        players = int(env.env_players[e])
        match_opponent = None if per_seat else league.sample_opponent()
        for seat in range(players):
            b = e * env.seats + seat
            if seat == 0:
                learner.append(b)
                continue
            opponent = (league.sample_opponent() if per_seat
                        else match_opponent)
            if opponent == "self":
                learner.append(b)
            else:
                kinds.setdefault(opponent, []).append(b)
    out = {k: np.array(v, dtype=np.int64) for k, v in kinds.items()}
    out["learner"] = np.array(learner, dtype=np.int64)
    return out


# ════════════════════════════════════════════════════════════════════
# ロールアウト
# ════════════════════════════════════════════════════════════════════
def collect_chunk(net: Policy, env: VersusEnv, obs, cfg: TrainConfig,
                  controllers: Dict[str, np.ndarray], buf: "Rollout",
                  logger: pb.ProgressLogger, gen: int,
                  frozen: Dict[str, Policy], bots: Dict[str, object],
                  base_steps: int, max_steps: int, t0: float,
                  done_so_far: int):
    """``cfg.rollout`` ステップぶん集める（PPO 更新 1 回ぶんの塊）。

    世代の区切りはゲーム内時間で決めるので（:func:`generation_done`）、
    1 世代は塊を何回か繰り返したものになる。**塊ごとに区切るのはメモリのため。**
    ゲーム内 1 時間ぶんを一度に溜めると観測だけで 2GB を超える。
    """
    learner = controllers["learner"]
    finished: List[dict] = []

    for t in range(cfg.rollout):
        action = empty_action(env.n)
        # --- 相手 ---
        for name, boards in controllers.items():
            if name == "learner" or len(boards) == 0:
                continue
            net_or_none = frozen.get(name) if name.startswith("past:") else None
            if net_or_none is not None:
                act(net_or_none, env, obs, boards, action)
            else:
                # チェックポイントが読めなかった場合は基準ボットで代替する。
                # ここで落とすと、過去の重みが 1 つ壊れているだけで
                # 学習全体が止まってしまう
                bots.get(name, bots["random"]).act(env, obs, boards, action)
        # --- 学習側 ---
        masks, actions, logp, value = act(net, env, obs, learner, action)

        buf.grid[t] = to_uint8(obs["grid"][learner])
        buf.scalar[t] = obs["scalar"][learner]
        buf.opponents[t] = obs["opponents"][learner]
        buf.opp_mask[t] = obs["opp_mask"][learner]
        for k in ACTION_HEADS:
            buf.masks[k][t] = masks[k]
            buf.actions[k][t] = actions[k]
        buf.logp[t] = logp
        buf.value[t] = value
        buf.valid[t] = env.active[learner] & env.boards.alive[learner]

        obs, reward, done, infos = env.step(action)
        buf.reward[t] = reward[learner]
        buf.done[t] = done[learner]
        finished.extend(infos)

        if t % 4 == 0 or t == cfg.rollout - 1:
            elapsed = max(time.time() - t0, 1e-6)
            steps = base_steps + t + 1
            logger.progress(gen, steps, max_steps,
                            done=done_so_far + len(finished), total=env.n_envs,
                            rate=(done_so_far + len(finished)) / max(env.n_envs, 1),
                            speed=steps * len(learner) / elapsed,
                            force=(t == cfg.rollout - 1))

    with torch.no_grad():
        _, last_value = net(obs_to_torch(obs, learner))
    return obs, finished, last_value.cpu().numpy()


def compute_gae(buf: Rollout, last_value: np.ndarray, cfg: TrainConfig):
    adv = np.zeros_like(buf.reward)
    gae = np.zeros(buf.n, np.float32)
    next_value = last_value
    for t in reversed(range(buf.steps)):
        not_done = (~buf.done[t]).astype(np.float32)
        delta = buf.reward[t] + cfg.gamma * next_value * not_done - buf.value[t]
        gae = delta + cfg.gamma * cfg.gae_lambda * not_done * gae
        adv[t] = gae
        next_value = buf.value[t]
    return adv, adv + buf.value


# ════════════════════════════════════════════════════════════════════
# PPO 更新
# ════════════════════════════════════════════════════════════════════
def ppo_update(net: Policy, opt, buf: Rollout, adv, ret, cfg: TrainConfig):
    flat = lambda a: a.reshape(-1, *a.shape[2:])          # noqa: E731
    valid = flat(buf.valid)
    idx_all = np.flatnonzero(valid)
    if len(idx_all) < 8:
        return {"loss": 0.0, "kl": 0.0, "entropy": 0.0, "value_loss": 0.0}

    grid_t = torch.as_tensor(flat(buf.grid), device=DEVICE)
    scalar_t = torch.as_tensor(flat(buf.scalar), device=DEVICE)
    opponents_t = torch.as_tensor(flat(buf.opponents), device=DEVICE)
    opp_mask_t = torch.as_tensor(flat(buf.opp_mask), device=DEVICE)
    masks_t = {k: torch.as_tensor(flat(v), device=DEVICE) for k, v in buf.masks.items()}
    actions_t = {k: torch.as_tensor(flat(v), device=DEVICE) for k, v in buf.actions.items()}
    old_logp_t = torch.as_tensor(flat(buf.logp), device=DEVICE)
    adv_f, ret_f = flat(adv), flat(ret)
    adv_f = (adv_f - adv_f[idx_all].mean()) / (adv_f[idx_all].std() + 1e-8)
    adv_t = torch.as_tensor(adv_f, device=DEVICE)
    ret_t = torch.as_tensor(ret_f, device=DEVICE)

    stats = {"loss": [], "kl": [], "entropy": [], "value_loss": []}
    use_amp = bool(cfg.amp) and AMP_OK
    net.train()
    for _ in range(cfg.epochs):
        np.random.shuffle(idx_all)
        for start in range(0, len(idx_all), cfg.minibatch):
            mb = idx_all[start:start + cfg.minibatch]
            if len(mb) < 8:
                continue
            mb_t = torch.as_tensor(mb, device=DEVICE)
            obs_mb = {
                "grid": grid_t[mb_t],
                "scalar": scalar_t[mb_t],
                "opponents": opponents_t[mb_t],
                "opp_mask": opp_mask_t[mb_t],
            }
            m_mb = {k: v[mb_t] for k, v in masks_t.items()}
            a_mb = {k: v[mb_t] for k, v in actions_t.items()}
            if use_amp:
                # **損失の計算は fp32 のまま。** autocast は行列積と畳み込みだけを
                # 低精度に落とし、logsumexp や loss は fp32 で回る
                with torch.autocast("cuda", dtype=AMP_DTYPE):
                    logp, ent, value = net.evaluate(obs_mb, m_mb, a_mb)
                logp, ent, value = logp.float(), ent.float(), value.float()
            else:
                logp, ent, value = net.evaluate(obs_mb, m_mb, a_mb)

            old = old_logp_t[mb_t]
            advantage = adv_t[mb_t]
            target = ret_t[mb_t]

            ratio = torch.exp(logp - old)
            pg = -torch.min(ratio * advantage,
                            torch.clamp(ratio, 1 - cfg.clip, 1 + cfg.clip) * advantage).mean()
            vloss = F.mse_loss(value, target)
            entropy = ent.mean()
            loss = pg + cfg.value_coef * vloss - cfg.entropy * entropy

            opt.zero_grad(set_to_none=True)
            if use_amp and _SCALER is not None:
                # fp16 は勾配が 0 に潰れるのでスケールしてから逆伝播する。
                # **勾配クリップの前に必ず戻すこと**（スケールしたまま
                # ノルムを測ると閾値の意味が変わる）
                _SCALER.scale(loss).backward()
                _SCALER.unscale_(opt)
                torch.nn.utils.clip_grad_norm_(net.parameters(), cfg.max_grad_norm)
                _SCALER.step(opt)
                _SCALER.update()
            else:
                loss.backward()
                torch.nn.utils.clip_grad_norm_(net.parameters(), cfg.max_grad_norm)
                opt.step()

            with torch.no_grad():
                kl = (old - logp).mean().item()
            stats["loss"].append(loss.item())
            stats["kl"].append(kl)
            stats["entropy"].append(entropy.item())
            stats["value_loss"].append(vloss.item())
    return {k: float(np.mean(v)) if v else 0.0 for k, v in stats.items()}


# ════════════════════════════════════════════════════════════════════
# 模倣学習（BC）
# ════════════════════════════════════════════════════════════════════
def behaviour_clone(net: Policy, opt, cfg: TrainConfig, rng: random.Random,
                    logger: pb.ProgressLogger) -> float:
    """基準ボットの手を教師にして actor を温める。

    ランダム初期方策は最初の数万ステップを「何が起きているのか分からない」
    まま溶かす。貪欲ボットの手をまず真似させると、そこを丸ごと省ける。
    **BC のあとは entropy ボーナスと clipping で癖から離れさせる**ので、
    ヒューリスティックの上限に張り付くことはない。
    """
    if cfg.bc_batches <= 0:
        return 0.0
    env = VersusEnv(EnvConfig(num_envs=8, players_choices=(2,), board_size=15,
                              attacker_limit=6, max_ticks=20 * 60 * 6,
                              seed=cfg.seed + 991))
    obs = env.observe()
    teachers = [make_bot("greedy_defense", np.random.default_rng(cfg.seed)),
                make_bot("income_push", np.random.default_rng(cfg.seed + 1))]
    seats = np.arange(env.n) % env.seats
    groups = [np.flatnonzero((seats == i) & env.active) for i in range(2)]

    losses: List[float] = []
    net.train()
    for it in range(cfg.bc_batches):
        action = empty_action(env.n)
        for bot, boards in zip(teachers, groups):
            bot.act(env, obs, boards, action)
        boards = np.concatenate(groups)
        cell_mask = env.cell_mask(action["type"], action["card"], action["tower"])

        tobs = obs_to_torch(obs, boards)
        logits, _ = net(tobs)
        a = {k: torch.as_tensor(action[k][boards], device=DEVICE)
             for k in ACTION_HEADS}
        masks = {
            "type": obs["mask_type"][boards],
            "card": obs["mask_card"][boards],
            "tower": obs["mask_tower"][boards],
            "unit": np.where((action["type"][boards] == A_UPGRADE)[:, None],
                             obs["mask_unit_upgrade"][boards],
                             obs["mask_unit_sell"][boards]),
            "send": obs["mask_send"][boards],
            "send_n": obs["mask_send_n"][boards],
            "spec": np.ones((len(boards), ACTION_HEADS["spec"]), bool),
            "cell": cell_mask[boards],
        }
        loss = torch.zeros((), device=DEVICE)
        a_type = action["type"][boards]
        use = {"type": np.ones(len(boards), bool),
               "card": a_type == A_CARD,
               "tower": a_type == A_TOWER,
               "cell": (a_type == A_CARD) | (a_type == A_TOWER),
               "unit": (a_type == A_UPGRADE) | (a_type == A_SELL),
               "spec": a_type == A_UPGRADE,
               "send": a_type == A_SEND,
               "send_n": a_type == A_SEND}
        for name, sel in use.items():
            if not sel.any():
                continue
            rows = torch.as_tensor(np.flatnonzero(sel), device=DEVICE)
            ml = masked_logits(logits[name][rows],
                               torch.as_tensor(masks[name][sel], device=DEVICE))
            loss = loss + F.cross_entropy(ml, a[name][rows])

        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(net.parameters(), cfg.max_grad_norm)
        opt.step()
        losses.append(loss.item())

        obs, _, _, _ = env.step(action)
        if it % 8 == 0 or it == cfg.bc_batches - 1:
            logger.progress(0, it + 1, cfg.bc_batches,
                            force=(it == cfg.bc_batches - 1))
    return float(np.mean(losses)) if losses else 0.0


# ════════════════════════════════════════════════════════════════════
# 評価
# ════════════════════════════════════════════════════════════════════
@torch.no_grad()
def play_match(net: Policy, opponent, players: int, games: int,
               cfg: TrainConfig, seed: int, opponent_net: Optional[Policy] = None):
    """学習中の方策 vs 相手。**席 0 が学習側**。

    :return: (勝率, 決着率, カウンタープッシュ率, 追い込み決着率, 統計)
    """
    env = VersusEnv(EnvConfig(num_envs=games, players_choices=(players,),
                              board_size=21, max_ticks=20 * 60 * 12,
                              randomize=0.0, seed=seed))
    obs = env.observe()
    seats = np.arange(env.n) % env.seats
    mine = np.flatnonzero((seats == 0) & env.active)
    theirs = np.flatnonzero((seats != 0) & env.active)

    results: List[dict] = []
    steps = 0
    limit = env.cfg.max_ticks // env.cfg.decision_ticks + 5
    while len(results) < games and steps < limit:
        action = empty_action(env.n)
        act(net, env, obs, mine, action, greedy=False)
        if opponent_net is not None:
            act(opponent_net, env, obs, theirs, action)
        else:
            opponent.act(env, obs, theirs, action)
        obs, _, _, infos = env.step(action)
        steps += 1
        results.extend(infos)

    results = results[:games] or [{}]
    wins = decided = pressured = pressured_done = 0
    cp_c = cp_h = 0.0
    agg = {k: [] for k in ("path_length", "tower_passes", "towers", "income",
                           "sends", "leaks", "coins_earned", "steps")}
    for info in results:
        if not info:
            continue
        rank = info["ranks"]
        if rank[0] == 0 and (rank == 0).sum() == 1:
            wins += 1
        if info["decided"]:
            decided += 1
        if info["pressured"]:
            pressured += 1
            if info["decided"]:
                pressured_done += 1
        cp_c += info["cp_chances"]
        cp_h += info["cp_hits"]
        for k in agg:
            agg[k].append(info[k])
    n = max(len(results), 1)
    stats = {k: float(np.mean(v)) if v else 0.0 for k, v in agg.items()}
    stats["card_usage_rate"] = float(np.mean(
        [i["cards_played"] / max(i["cards_drawn"], 1) for i in results if i]) or 0.0)
    return (wins / n, decided / n, (cp_h / cp_c) if cp_c else 0.0,
            (pressured_done / pressured) if pressured else 0.0, stats)


def evaluate(net: Policy, cfg: TrainConfig, league: League,
             best_net: Optional[Policy], gen: int) -> Dict[str, object]:
    """metrics.md に沿った評価。``win_vs_random`` → ``win_vs_best`` → ``elo``。"""
    out: Dict[str, object] = {}
    rng = np.random.default_rng(cfg.seed + gen)

    bot = make_bot("random", rng)
    wr, fin, cp, pf, stats = play_match(net, bot, 2, cfg.eval_games, cfg,
                                        cfg.seed + gen * 17)
    out["win_vs_random"] = wr
    out["finish_rate"] = fin
    out["counter_push_rate"] = cp
    out["pressured_finish_rate"] = pf
    out.update({
        "avg_path_length": stats["path_length"],
        "avg_tower_passes": stats["tower_passes"],
        "tower_count_final": stats["towers"],
        "avg_income_final": stats["income"],
        "sends_per_game": stats["sends"],
        "leaks_per_game": stats["leaks"],
        "card_usage_rate": stats["card_usage_rate"],
        "avg_turn": stats["steps"],
        "avg_coin_efficiency": stats["coins_earned"] / max(stats["sends"], 1.0),
    })

    # 過去最強との対戦。**これが本命**（50% 付近で拮抗、55% 超で更新）
    if best_net is not None:
        wb, _, _, _, _ = play_match(net, None, 2, max(6, cfg.eval_games // 2),
                                    cfg, cfg.seed + gen * 31, opponent_net=best_net)
        out["win_vs_best"] = wb
        # Elo: 期待勝率との差ぶんだけ動かす（相手は固定なので単純な更新でよい）
        expected = 0.5
        league.elo += 24.0 * (wb - expected)
    else:
        out["win_vs_best"] = 0.5
    out["elo"] = league.elo

    # **方策に依存しない定点観測。** 学習ログの指標は方策が変わるから動くので、
    # バランスが変わったのか方策が変わったのかを切り分けられない。
    # 固定のボットで同じ金・同じ波を受けさせて漏れ率だけを測ると、
    # 方策と無関係にバランスの変化だけが出る（tower_bench の防衛試験）
    try:
        import tower_bench
        probe = {}
        for name in ("arrow_spam", "big_tower", "support_mix"):
            r = tower_bench.defense_run(name, games=2, minutes=4.0,
                                        seed=cfg.seed + gen)
            probe[name] = round(r["leak_rate"], 4)
        out["defense_probe"] = probe
    except Exception as exception:                          # noqa: BLE001
        # 定点観測が落ちても学習は続ける。指標が 1 つ欠けるだけ
        print(f"警告: 定点観測に失敗しました: {exception}", flush=True)

    # 人数別の勝率。**人数で最適戦略が変わる**ので入れ子 dict で残す
    by_players: Dict[str, float] = {}
    for players in (2, 4, 8):
        w, _, _, _, _ = play_match(net, make_bot("greedy_defense", rng), players,
                                   max(4, cfg.eval_games // 3), cfg,
                                   cfg.seed + gen * 53 + players)
        by_players[f"{players}p"] = w
    out["win_rate_by_players"] = by_players
    return out


# ════════════════════════════════════════════════════════════════════
# メイン
# ════════════════════════════════════════════════════════════════════
def main() -> None:
    cfg = TrainConfig()
    os.makedirs(MODEL_DIR, exist_ok=True)
    logger = pb.ProgressLogger(MODEL_DIR, LOG_NAME)
    rng = random.Random(cfg.seed)
    np.random.seed(cfg.seed)
    torch.manual_seed(cfg.seed)

    install_stop_handlers()

    num_envs = cfg.num_envs or pb.auto_num_envs()
    gens_label = "上限なし（停止するまで）" if cfg.max_gens <= 0 else str(cfg.max_gens)
    print(f"MAZEWARD VERSUS 学習  device={DEVICE}  同時試合={num_envs}  "
          f"世代={gens_label}  カリキュラム={cfg.curriculum_total()} 世代で 1 周  "
          f"ランダム化={cfg.randomize:.2f}", flush=True)
    print(f"世代の区切り: ゲーム内 {cfg.gen_early_minutes:.0f} 分以下なら完了率 "
          f"{cfg.gen_finish_early:.0%} / {cfg.gen_early_minutes:.0f} 分以上なら "
          f"{cfg.gen_finish_late:.0%} / {cfg.gen_max_minutes:.0f} 分超で打ち切り",
          flush=True)
    from mazeward_env import pathfinder as _pf
    print("経路探索: " + ("numba (JIT)" if _pf._FAST is not None else
                         "純 Python  ※ pip install numba で約 2 割速くなります"),
          flush=True)
    if DEVICE.type == "cuda":
        amp_name = {torch.bfloat16: "bf16 混合精度", torch.float16: "fp16 混合精度"}
        mode = amp_name.get(AMP_DTYPE, "fp32") if (cfg.amp and AMP_OK) else "fp32"
        print(f"PPO 更新: {mode}  ({torch.cuda.get_device_name(0)} / "
              f"compute {'.'.join(map(str, torch.cuda.get_device_capability()))})",
              flush=True)
    else:
        print("PPO 更新: fp32 (CPU)", flush=True)
    print("1 試合の時間切れ: "
          + (f"{cfg.match_max_min:.0f} 分" if cfg.match_max_min > 0
             else f"なし（世代の打ち切り {cfg.gen_max_minutes:.0f} 分まで走る）"),
          flush=True)

    # **割引率は 1 つしか無い。** ポテンシャル整形 (reward.GAMMA) と PPO
    # (cfg.gamma) がずれると「方策を変えない」保証が崩れるので、
    # 環境変数で上書きされていたら整形側へ代入し直す
    if abs(R.GAMMA - cfg.gamma) > 1e-12:
        print(f"整形報酬の割引率を {R.GAMMA} → {cfg.gamma} に揃えました", flush=True)
        R.GAMMA = cfg.gamma

    net = Policy().to(DEVICE)
    opt = torch.optim.Adam(net.parameters(), lr=cfg.lr, eps=1e-5)
    league = League(cfg, rng)
    best_net: Optional[Policy] = None
    best_score = -1e9
    game_minutes_total = 0.0        # ゲーム内の累計経過時間（分）
    frozen: Dict[str, Policy] = {}
    bots = {name: make_bot(name, np.random.default_rng(cfg.seed + i))
            for i, name in enumerate(league.bots)}

    resume = os.path.join(MODEL_DIR, "ppo_latest.pt")
    pb.recover_data_file(resume)
    start_gen = 1
    # **バランスの骨格が変わると、そもそも形が合わない。**
    # 送りの種類数は行動ヘッド send の出力数に、塔のレベル数と送りの種類数は
    # 観測のスカラー次元に直結している。放っておくと放置学習が
    # 再開直後の RuntimeError で死ぬので、ここで検知して作り直す。
    stale = None
    if os.path.exists(resume):
        state = torch.load(resume, map_location=DEVICE, weights_only=False)
        saved_fp = state.get("balance_fingerprint")
        # **環境より先に判定する。** 環境を作るのは 1 世代目の直前なので、
        # ここでは既定バランスの指紋を使う（ドメインランダム化を掛けても
        # 指紋を比べる基準は既定値のまま——揺らした値で比べたら毎回違う）
        current_fp = B.default_balance().fingerprint()
        try:
            if saved_fp is not None and saved_fp != current_fp:
                raise RuntimeError(
                    f"バランスの指紋が違う（保存 {saved_fp} / 現在 {current_fp}）")
            net.load_state_dict(state["net"])
            opt.load_state_dict(state["opt"])
        except (RuntimeError, KeyError, ValueError) as exc:
            stale = resume + ".stale"
            os.replace(resume, stale)
            print(f"※ 既存モデルを引き継げませんでした: {exc}", flush=True)
            print(f"   観測や行動の形が変わっています（送り {len(B.ATTACKER_ORDER)} 種 / "
                  f"塔 {B.MAX_TOWER_LEVEL} 段 / スカラー {SCALAR_DIM} 次元）", flush=True)
            print(f"   旧モデルは {os.path.basename(stale)} に退避し、"
                  f"第 1 世代から学習し直します", flush=True)
            print("   経緯は docs/VERSUS_ECONOMY_ja.md", flush=True)
            net = Policy().to(DEVICE)
            opt = torch.optim.Adam(net.parameters(), lr=cfg.lr, eps=1e-5)
    if stale is None and os.path.exists(resume):
        start_gen = state.get("gen", 0) + 1
        league.elo = state.get("elo", 1000.0)
        game_minutes_total = state.get("game_minutes_total", 0.0)
        league.checkpoints = state.get("checkpoints", [])
        load_frozen(league, frozen)
        print(f"再開: 第 {start_gen} 世代から"
              f"（過去チェックポイント {len(frozen)} 個を復元）", flush=True)
    else:
        bc_loss = behaviour_clone(net, opt, cfg, rng, logger)
        print(f"模倣学習で初期化しました (loss={bc_loss:.3f})", flush=True)

    env_cfg = EnvConfig(num_envs=num_envs, randomize=cfg.randomize,
                        seed=cfg.seed + 1)
    if cfg.curriculum:
        apply_curriculum(env_cfg,
                         curriculum_stage(start_gen - 1, cfg.curriculum_total()))
    # カリキュラムが決めた試合上限を、利用者の設定で上書きする。
    # **試合を時間で勝手に切らない**のが既定（match_max_min = 0）
    env_cfg.max_ticks = cfg.match_max_ticks()
    env = VersusEnv(env_cfg)
    obs = env.observe()
    last_fingerprint = env.balances[0].fingerprint()

    # 上限なし（max_gens <= 0）なら数え続ける。終わりは停止要求だけが決める
    gen_iter = (itertools.count(start_gen) if cfg.max_gens <= 0
                else range(start_gen, start_gen + cfg.max_gens))
    gen = start_gen - 1
    for gen in gen_iter:
        gen_t0 = time.time()
        stage = (curriculum_stage(gen - 1, cfg.curriculum_total())
                 if cfg.curriculum else len(CURRICULUM) - 1)
        wanted = CURRICULUM[stage][0] if cfg.curriculum else "固定"
        if cfg.curriculum and (env.cfg.board_size != CURRICULUM[stage][1]
                               or tuple(env.cfg.players_choices)
                               != tuple(CURRICULUM[stage][2])):
            apply_curriculum(env.cfg, stage)
            env.cfg.max_ticks = cfg.match_max_ticks()
            env = VersusEnv(env.cfg)
            obs = env.observe()

        # --- 世代 = 試合の切れ目まで塊を繰り返す ---
        controllers = assign_controllers(env, league, rng)
        n_learner = len(controllers["learner"])
        buf = Rollout(cfg.rollout, n_learner)      # 塊ごとに使い回す
        ticks_per_step = env.cfg.decision_ticks
        max_steps = int(cfg.gen_max_minutes * 60 * B.TICKS_PER_SECOND
                        / ticks_per_step)
        max_chunks = max(1, math.ceil(max_steps / cfg.rollout))

        infos: List[dict] = []
        env_done_once = np.zeros(env.n_envs, dtype=bool)
        chunk_losses: List[Dict[str, float]] = []
        steps_total = 0
        stop_reason = ""

        for chunk in range(max_chunks):
            obs, chunk_infos, last_value = collect_chunk(
                net, env, obs, cfg, controllers, buf, logger, gen,
                frozen, bots, steps_total, max_steps, gen_t0,
                int(env_done_once.sum()))
            steps_total += cfg.rollout
            infos.extend(chunk_infos)
            for info in chunk_infos:
                env_done_once[info["env"]] = True

            adv, ret = compute_gae(buf, last_value, cfg)
            # **PPO 更新のあいだ進捗行が止まる。** 収集中しか progress を
            # 出していなかったので、GUI も端末も「固まった」ように見えていた。
            # 実測では 1 チャンクあたり fp32 で約 9 秒 / 混合精度で約 4.6 秒
            # （学習島 225・rollout 96・minibatch 1024・epochs 3 = 66 回の更新）
            upd_t0 = time.time()
            print(f"[Update] 第 {gen} 世代 チャンク {chunk + 1}/{max_chunks} "
                  f"学習中 … 学習島 {n_learner} x {cfg.rollout} step", flush=True)
            chunk_losses.append(ppo_update(net, opt, buf, adv, ret, cfg))
            print(f"[Update] 第 {gen} 世代 チャンク {chunk + 1}/{max_chunks} "
                  f"完了 ({time.time() - upd_t0:.1f} 秒)", flush=True)

            game_minutes = (steps_total * ticks_per_step
                            / B.TICKS_PER_SECOND / 60.0)
            completion = float(env_done_once.mean())
            stop, reason = generation_done(game_minutes, completion, cfg)
            if stop:
                stop_reason = reason
                break
            # **塊の切れ目で抜ける。** ここまでの更新は済んでいるので、
            # このあとの保存にちゃんと入る
            if STOP.is_set():
                stop_reason = "停止要求"
                break
        else:
            stop_reason = f"塊の上限 {max_chunks} に到達"

        losses = {k: float(np.mean([c[k] for c in chunk_losses]))
                  for k in chunk_losses[0]} if chunk_losses else {
            "loss": 0.0, "kl": 0.0, "entropy": 0.0, "value_loss": 0.0}

        seconds = time.time() - gen_t0
        steps_done = steps_total * n_learner
        game_minutes = steps_total * ticks_per_step / B.TICKS_PER_SECOND / 60.0
        game_minutes_total += game_minutes
        metrics: Dict[str, object] = {
            "loss": losses["loss"],
            "kl": losses["kl"],
            "entropy": losses["entropy"],
            "value_loss": losses["value_loss"],
            "fps": steps_done / max(seconds, 1e-6),
            "seconds_per_gen": seconds,
            "games_finished": len(infos),
            "num_envs": env.n_envs,
            # ゲーム内でどれだけ時間が経ったか（1 ステップ = ゲーム内 1 秒）
            "game_minutes": round(game_minutes, 2),
            "game_hours_total": round(game_minutes_total / 60.0, 3),
            "match_completion": round(float(env_done_once.mean()), 4),
            "updates": len(chunk_losses),
            "stop_reason": stop_reason,
            "num_players": float(np.mean([i["players"] for i in infos])) if infos else 0.0,
            "curriculum": wanted,
            "balance_fingerprint": env.balances[0].fingerprint(),
        }
        if infos:
            # ---- バランス用の指標は「各枠の最初の 1 試合」だけで取る ----
            # **固定のゲーム内時間で区切ると、短い試合ほど多くサンプルに入る。**
            # 長さ L の試合は 1/L の重みで入るので、決着の速い試合が過大に出る。
            # 学習データは全部使う（PPO は試合の切れ目を気にしないし、
            # 全枠の決着を待つと実測で 31% が遊休になる）が、
            # **「典型的な 1 試合はどうだったか」を測る側は 1 枠 1 票**にする
            seen: set = set()
            fair = []
            for info in infos:
                e = int(info["env"])
                if e not in seen:
                    seen.add(e)
                    fair.append(info)
            metrics["fair_games"] = len(fair)

            metrics["finish_rate"] = float(np.mean([i["decided"] for i in infos]))
            # 「時間切れで打ち切られた割合」。ここが高いと、試合が
            # 実力で決着せずタイマーで終わっていることになる
            metrics["timeout_rate"] = float(np.mean([i["timeout"] for i in infos]))
            metrics["avg_turn"] = float(np.mean([i["steps"] for i in infos]))
            metrics["avg_path_length"] = float(np.mean([i["path_length"] for i in infos]))
            metrics["avg_tower_passes"] = float(np.mean([i["tower_passes"] for i in infos]))
            metrics["sends_per_game"] = float(np.mean([i["sends"] for i in infos]))
            metrics["leaks_per_game"] = float(np.mean([i["leaks"] for i in infos]))
            metrics["tower_count_final"] = float(np.mean([i["towers"] for i in infos]))
            metrics["avg_income_final"] = float(np.mean([i["income"] for i in infos]))
            metrics["tower_avg_level"] = float(np.mean([i.get("tower_avg_level", 0.0) for i in infos]))
            
            # 辞書型集計 (平均化)
            for dict_key in ("tower_type_rates", "send_type_rates", "leak_type_rates"):
                merged = {}
                for info in fair:
                    sub = info.get(dict_key, {})
                    for k, v in sub.items():
                        merged[k] = merged.get(k, 0.0) + v / len(fair)
                metrics[dict_key] = merged

            # ---- 勝った側 / 負けた側で分ける ----------------------
            # **平均だけだと「どの構成が勝つのか」を後から問えない。**
            # バランス判断で見たいのはここで、平均は勝者と敗者を混ぜてしまう
            # ---- 分布。**平均だけだと形が消える** --------------------
            # 大勝ちと大負けを繰り返す戦略と、いつも引き分ける戦略が
            # 平均では同じに見える。バランスを見るときはここが要る
            for key, vals in (("leaks", [i["leaks"] for i in fair]),
                              ("towers", [i["towers"] for i in fair]),
                              ("income", [i["income"] for i in fair]),
                              ("path_length", [i["path_length"] for i in fair]),
                              ("ticks", [i["ticks"] for i in fair])):
                p10, p50, p90 = np.percentile(vals, [10, 50, 90])
                metrics[f"{key}_p10"] = float(p10)
                metrics[f"{key}_p50"] = float(p50)
                metrics[f"{key}_p90"] = float(p90)

            rows = [ps for i in fair for ps in i.get("per_seat", [])]
            for label, sel in (("winner", [r for r in rows if r["won"]]),
                               ("loser", [r for r in rows if not r["won"]])):
                if not sel:
                    continue
                for key in ("towers", "tower_avg_level", "income", "sends",
                            "leaks", "path_length", "path_threat", "kills"):
                    metrics[f"{label}_{key}"] = float(np.mean([r[key] for r in sel]))
                rates = [r["leak_rate"] for r in sel if r["leak_rate"] is not None]
                if rates:
                    metrics[f"{label}_leak_rate"] = float(np.mean(rates))
                total = sum(sum(r["tower_counts"].values()) for r in sel)
                mix = {}
                for r in sel:
                    for k, v in r["tower_counts"].items():
                        mix[k] = mix.get(k, 0.0) + v / max(total, 1)
                metrics[f"{label}_tower_mix"] = mix

            # ---- 塔の種類ごとの功績 ------------------------------
            # **バランス判断の本命。** 構成比だけだと「よく建てられている」しか
            # 分からず、その塔が実際に仕事をしたかが残らない。監視塔と呪詛塔は
            # 自分では 1 ダメージも出さないので、構成比では永久に評価できない
            dmg_sum: Dict[str, float] = {}
            kill_sum: Dict[str, float] = {}
            built_sum: Dict[str, float] = {}
            for r in rows:
                for k, v in r.get("damage_by_kind", {}).items():
                    dmg_sum[k] = dmg_sum.get(k, 0.0) + v
                for k, v in r.get("kills_by_kind", {}).items():
                    kill_sum[k] = kill_sum.get(k, 0.0) + v
                for k, v in r.get("tower_counts", {}).items():
                    built_sum[k] = built_sum.get(k, 0.0) + v
            grand = sum(dmg_sum.values())
            if grand > 0:
                metrics["damage_share_by_kind"] = {k: v / grand
                                                   for k, v in dmg_sum.items()}
                # **1 本あたりの与ダメージ。** 「その塔は建てる価値があるか」に
                # いちばん近い数字で、コストで割ればそのまま釣り合いの指標になる
                metrics["damage_per_tower_by_kind"] = {
                    k: dmg_sum[k] / built_sum[k]
                    for k in dmg_sum if built_sum.get(k, 0) > 0}
                metrics["kills_by_kind"] = kill_sum

            # 送りの種類ごとの突破率。「送って通るのか」を種類別に残す
            bt: Dict[str, List[float]] = {}
            for i in fair:
                for k, v in i.get("breakthrough_rates", {}).items():
                    if v is not None:
                        bt.setdefault(k, []).append(v)
            if bt:
                metrics["breakthrough_rates"] = {k: float(np.mean(v))
                                                 for k, v in bt.items()}
            # 敵の同時上限で捨てた湧き。0 でないと守りが不当に強く見える
            metrics["spawn_dropped"] = float(np.mean(
                [i.get("spawn_dropped", 0.0) for i in infos]))

            drawn = sum(i["cards_drawn"] for i in infos)
            metrics["card_usage_rate"] = (sum(i["cards_played"] for i in infos)
                                          / max(drawn, 1.0))

        # 学習中にバランスが変わったら記録に残す（何が原因で挙動が変わったか
        # 後から追えないと、指標の揺れをすべて学習のせいにしてしまう）
        fingerprint = env.balances[0].fingerprint()
        if fingerprint != last_fingerprint and cfg.randomize == 0:
            metrics["balance_changed"] = True
            last_fingerprint = fingerprint

        last_gen = cfg.max_gens > 0 and gen == start_gen + cfg.max_gens - 1
        if not STOP.is_set() and (gen % cfg.eval_every == 0 or last_gen):
            metrics.update(evaluate(net, cfg, league, best_net, gen))
            score = float(metrics.get("win_vs_random", 0.0))
            if score > best_score:
                best_score = score
                best_net = Policy().to(DEVICE)
                best_net.load_state_dict(net.state_dict())
                best_net.eval()
                path = os.path.join(MODEL_DIR, f"ppo_gen{gen}.pt")
                torch.save({"net": net.state_dict()}, path)
                pb.persist_data_file(path)
                league.add_checkpoint(path)
                load_frozen(league, frozen)

        logger.generation_line(gen, {k: v for k, v in metrics.items()
                                     if isinstance(v, (int, float, str))})
        logger.log_generation(gen, metrics)

        torch.save({"net": net.state_dict(), "opt": opt.state_dict(), "gen": gen,
                    "elo": league.elo, "checkpoints": league.checkpoints,
                    "balance_fingerprint": B.default_balance().fingerprint(),
                    "game_minutes_total": game_minutes_total}, resume)
        pb.persist_data_file(resume)

        if STOP.is_set():
            break

    if STOP.is_set():
        print(f"停止要求により第 {gen} 世代で終了しました（保存済み）", flush=True)
    else:
        print("学習を完了しました", flush=True)


if __name__ == "__main__":
    pb.run_guarded(main)
