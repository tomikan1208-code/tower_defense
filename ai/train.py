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

import json
import math
import os
import random
import sys
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
from mazeward_env.rules import (CURRICULUM, EnvConfig, apply_curriculum,  # noqa: E402
                                curriculum_stage)
from policy import OBS_SCALE, Policy, masked_logits           # noqa: E402

PREFIX = "MAZEWARD"
BASE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(BASE, "models")
LOG_NAME = "ppo"

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


# ════════════════════════════════════════════════════════════════════
# 設定
# ════════════════════════════════════════════════════════════════════
@dataclass
class TrainConfig:
    max_gens: int = pb.env_int(f"{PREFIX}_MAX_GENS", 20)
    num_envs: int = pb.env_int(f"{PREFIX}_NUM_ENVS", 0)     # 0 = 自動
    rollout: int = pb.env_int(f"{PREFIX}_ROLLOUT", 96)
    epochs: int = pb.env_int(f"{PREFIX}_EPOCHS", 3)
    minibatch: int = pb.env_int(f"{PREFIX}_MINIBATCH", 1024)
    lr: float = pb.env_float(f"{PREFIX}_LR", 2.5e-4)
    gamma: float = pb.env_float(f"{PREFIX}_GAMMA", 0.997)
    gae_lambda: float = pb.env_float(f"{PREFIX}_GAE", 0.95)
    clip: float = pb.env_float(f"{PREFIX}_CLIP", 0.2)
    entropy: float = pb.env_float(f"{PREFIX}_ENTROPY", 0.02)
    value_coef: float = pb.env_float(f"{PREFIX}_VALUE_COEF", 0.5)
    max_grad_norm: float = pb.env_float(f"{PREFIX}_GRAD_NORM", 0.5)
    randomize: float = pb.env_float(f"{PREFIX}_RANDOMIZE", 0.20)
    bc_batches: int = pb.env_int(f"{PREFIX}_BC_BATCHES", 60)
    eval_every: int = pb.env_int(f"{PREFIX}_EVAL_EVERY", 5)
    eval_games: int = pb.env_int(f"{PREFIX}_EVAL_GAMES", 12)
    #: リーグで基準ボットが相手になる確率
    bot_ratio: float = pb.env_float(f"{PREFIX}_BOT_RATIO", 0.35)
    seed: int = pb.env_int(f"{PREFIX}_SEED", 0)
    curriculum: int = pb.env_int(f"{PREFIX}_CURRICULUM", 1)

    # ---- 世代の区切り（ゲーム内時間と試合完了率で決める） ----
    #: ここまでは「全部の試合が終わる」ことを待つ（ゲーム内・分）
    gen_early_minutes: float = pb.env_float(f"{PREFIX}_GEN_EARLY_MIN", 30.0)
    #: ここを超えたら完了率に関わらず打ち切る（ゲーム内・分）
    gen_max_minutes: float = pb.env_float(f"{PREFIX}_GEN_MAX_MIN", 60.0)
    #: 早い段階で要求する完了率
    gen_finish_early: float = pb.env_float(f"{PREFIX}_GEN_FINISH_EARLY", 1.0)
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
        self.grid = np.zeros((steps, n, 14, B.MAX_BOARD, B.MAX_BOARD), np.uint8)
        self.scalar = np.zeros((steps, n, 210), np.float32)
        self.opponents = np.zeros((steps, n, B.MAX_PLAYERS, 14), np.float32)
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
        mask = torch.as_tensor(mask_np, device=DEVICE)
        ml = masked_logits(logits[name], mask)
        if greedy:
            a = ml.argmax(dim=-1)
        else:
            a = torch.distributions.Categorical(logits=ml).sample()
        dist = torch.distributions.Categorical(logits=ml)
        return a.cpu().numpy(), dist.log_prob(a), mask

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
    spec_mask = np.ones((len(boards), ACTION_HEADS["spec"]), bool)
    a_spec, lp_spec, _ = pick("spec", spec_mask)

    stored_masks["card"] = obs["mask_card"][boards]
    stored_masks["tower"] = obs["mask_tower"][boards]
    stored_masks["unit"] = unit_mask
    stored_masks["send"] = obs["mask_send"][boards]
    stored_masks["spec"] = spec_mask

    action["type"][boards] = a_type
    action["card"][boards] = a_card
    action["tower"][boards] = a_tower
    action["unit"][boards] = a_unit
    action["send"][boards] = a_send
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
            + lp_send * torch.as_tensor(a_type == A_SEND, device=DEVICE).float())

    actions_np = {"type": a_type, "card": a_card, "tower": a_tower,
                  "cell": a_cell, "unit": a_unit, "spec": a_spec, "send": a_send}
    return stored_masks, actions_np, logp.cpu().numpy(), value.cpu().numpy()


# ════════════════════════════════════════════════════════════════════
# 対戦相手の割り当て（リーグ）
# ════════════════════════════════════════════════════════════════════
class League:
    """自分・過去のチェックポイント・基準ボットのプール。

    自己対戦だけだと「自分だけ強くなったつもり」に陥る。**動かない基準**
    （ランダム・貪欲・送り特化）を必ず混ぜて、強くなったかを外から測れるようにする。
    """

    def __init__(self, cfg: TrainConfig, rng: random.Random):
        self.cfg = cfg
        self.rng = rng
        self.checkpoints: List[str] = []
        self.elo = 1000.0
        self.bots = ("random", "greedy_defense", "income_push")

    def sample_opponent(self) -> str:
        if self.rng.random() < self.cfg.bot_ratio or not self.checkpoints:
            return self.rng.choice(self.bots)
        if self.rng.random() < 0.5:
            return "self"
        return "past:" + self.rng.choice(self.checkpoints)

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
    """席ごとに操作者を決める。席 0 は必ず学習中の方策。"""
    kinds: Dict[str, List[int]] = {}
    learner: List[int] = []
    for e in range(env.n_envs):
        players = int(env.env_players[e])
        opponent = league.sample_opponent()
        for seat in range(players):
            b = e * env.seats + seat
            if seat == 0 or opponent == "self":
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

    grid = flat(buf.grid)
    scalar = flat(buf.scalar)
    opponents = flat(buf.opponents)
    opp_mask = flat(buf.opp_mask)
    masks = {k: flat(v) for k, v in buf.masks.items()}
    actions = {k: flat(v) for k, v in buf.actions.items()}
    old_logp = flat(buf.logp)
    adv_f, ret_f = flat(adv), flat(ret)
    adv_f = (adv_f - adv_f[idx_all].mean()) / (adv_f[idx_all].std() + 1e-8)

    stats = {"loss": [], "kl": [], "entropy": [], "value_loss": []}
    net.train()
    for _ in range(cfg.epochs):
        np.random.shuffle(idx_all)
        for start in range(0, len(idx_all), cfg.minibatch):
            mb = idx_all[start:start + cfg.minibatch]
            if len(mb) < 8:
                continue
            obs_mb = {
                "grid": torch.as_tensor(grid[mb], device=DEVICE),
                "scalar": torch.as_tensor(scalar[mb], device=DEVICE),
                "opponents": torch.as_tensor(opponents[mb], device=DEVICE),
                "opp_mask": torch.as_tensor(opp_mask[mb], device=DEVICE),
            }
            m_mb = {k: torch.as_tensor(v[mb], device=DEVICE) for k, v in masks.items()}
            a_mb = {k: torch.as_tensor(v[mb], device=DEVICE) for k, v in actions.items()}
            logp, ent, value = net.evaluate(obs_mb, m_mb, a_mb)

            old = torch.as_tensor(old_logp[mb], device=DEVICE)
            advantage = torch.as_tensor(adv_f[mb], device=DEVICE)
            target = torch.as_tensor(ret_f[mb], device=DEVICE)

            ratio = torch.exp(logp - old)
            pg = -torch.min(ratio * advantage,
                            torch.clamp(ratio, 1 - cfg.clip, 1 + cfg.clip) * advantage).mean()
            vloss = F.mse_loss(value, target)
            entropy = ent.mean()
            loss = pg + cfg.value_coef * vloss - cfg.entropy * entropy

            opt.zero_grad(set_to_none=True)
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
               "send": a_type == A_SEND}
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

    num_envs = cfg.num_envs or pb.auto_num_envs()
    print(f"MAZEWARD VERSUS 学習  device={DEVICE}  同時試合={num_envs}  "
          f"世代={cfg.max_gens}  ランダム化={cfg.randomize:.2f}", flush=True)
    print(f"世代の区切り: ゲーム内 {cfg.gen_early_minutes:.0f} 分以下なら完了率 "
          f"{cfg.gen_finish_early:.0%} / {cfg.gen_early_minutes:.0f} 分以上なら "
          f"{cfg.gen_finish_late:.0%} / {cfg.gen_max_minutes:.0f} 分超で打ち切り",
          flush=True)
    print("1 試合の時間切れ: "
          + (f"{cfg.match_max_min:.0f} 分" if cfg.match_max_min > 0
             else f"なし（世代の打ち切り {cfg.gen_max_minutes:.0f} 分まで走る）"),
          flush=True)

    net = Policy().to(DEVICE)
    opt = torch.optim.Adam(net.parameters(), lr=cfg.lr, eps=1e-5)
    league = League(cfg, rng)
    best_net: Optional[Policy] = None
    best_score = -1e9
    game_minutes_total = 0.0        # ゲーム内の累計経過時間（分）
    frozen: Dict[str, Policy] = {}
    bots = {name: make_bot(name, np.random.default_rng(cfg.seed + i))
            for i, name in enumerate(("random", "greedy_defense", "income_push"))}

    resume = os.path.join(MODEL_DIR, "ppo_latest.pt")
    pb.recover_data_file(resume)
    start_gen = 1
    if os.path.exists(resume):
        state = torch.load(resume, map_location=DEVICE, weights_only=False)
        net.load_state_dict(state["net"])
        opt.load_state_dict(state["opt"])
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
        apply_curriculum(env_cfg, curriculum_stage(start_gen - 1, cfg.max_gens))
    # カリキュラムが決めた試合上限を、利用者の設定で上書きする。
    # **試合を時間で勝手に切らない**のが既定（match_max_min = 0）
    env_cfg.max_ticks = cfg.match_max_ticks()
    env = VersusEnv(env_cfg)
    obs = env.observe()
    last_fingerprint = env.balances[0].fingerprint()

    for gen in range(start_gen, start_gen + cfg.max_gens):
        gen_t0 = time.time()
        stage = curriculum_stage(gen - 1, cfg.max_gens) if cfg.curriculum else len(CURRICULUM) - 1
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
            chunk_losses.append(ppo_update(net, opt, buf, adv, ret, cfg))

            game_minutes = (steps_total * ticks_per_step
                            / B.TICKS_PER_SECOND / 60.0)
            completion = float(env_done_once.mean())
            stop, reason = generation_done(game_minutes, completion, cfg)
            if stop:
                stop_reason = reason
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
            drawn = sum(i["cards_drawn"] for i in infos)
            metrics["card_usage_rate"] = (sum(i["cards_played"] for i in infos)
                                          / max(drawn, 1.0))

        # 学習中にバランスが変わったら記録に残す（何が原因で挙動が変わったか
        # 後から追えないと、指標の揺れをすべて学習のせいにしてしまう）
        fingerprint = env.balances[0].fingerprint()
        if fingerprint != last_fingerprint and cfg.randomize == 0:
            metrics["balance_changed"] = True
            last_fingerprint = fingerprint

        if gen % cfg.eval_every == 0 or gen == start_gen + cfg.max_gens - 1:
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
                    "game_minutes_total": game_minutes_total}, resume)
        pb.persist_data_file(resume)

    print("学習を完了しました", flush=True)


if __name__ == "__main__":
    pb.run_guarded(main)
