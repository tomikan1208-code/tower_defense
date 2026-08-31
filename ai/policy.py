# -*- coding: utf-8 -*-
"""方策ネットワーク。**1 つの共有バックボーン ＋ 複数ヘッド。**

なぜ 1 本にまとめるのか
----------------------
迷路（カード）・タワー・送りは **同じコインと同じカードを奪い合っている**。
3 つを別々のネットワークにすると、それぞれが「自分の担当だけ最適」に
振る舞い、配分の判断が誰にも学習されない。だから

- バックボーンは 1 つ（盤面 CNN ＋ スカラー MLP ＋ 相手エンコーダ）
- ヘッドだけ分岐
- **価値関数（Critic）は 1 つ**

にしてある。報酬も 1 本なので、「壁に使うか塔に使うか送るか」が
そのまま 1 つの価値の中で比較される。

行動の引き方（条件付き）
------------------------
1 ステップにつき 1 手なので、まず ``type`` を引き、その種別で必要な
ヘッドだけを引く。使わなかったヘッドは log 確率にも entropy にも含めない
（含めると、その手を選んでいないのに勾配が流れる）。

``cell`` のマスクは選んだ形に依存するので、``type`` と ``card``/``tower`` を
引いたあとに環境へ問い合わせて作る。順番に引く必要があるのはこの 1 箇所だけで、
ネットワークの forward は 1 回で済む。
"""

from __future__ import annotations

from typing import Dict, Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

import balance as B
from mazeward_env.env import (A_CARD, A_SELL, A_SEND, A_SKIP, A_TOWER,
                              A_UPGRADE, ACTION_HEADS)
from mazeward_env.observation import (N_CHANNELS, OPP_FEATURES, SCALAR_DIM)

#: 観測を uint8 で溜めるときの倍率（policy 側で戻す）
OBS_SCALE = 64.0

NEG_INF = -1e9


def masked_logits(logits: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    """禁止手を softmax から外す。全部禁止なら 1 つだけ通して NaN を防ぐ。"""
    mask = mask.bool()
    empty = ~mask.any(dim=-1, keepdim=True)
    mask = mask | (empty & (torch.arange(logits.shape[-1], device=logits.device) == 0))
    return logits.masked_fill(~mask, NEG_INF)


class Backbone(nn.Module):
    """盤面 CNN ＋ スカラー ＋ 相手の 3 つを 1 本のベクトルに畳む。"""

    def __init__(self, width: int = 64, hidden: int = 256):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(N_CHANNELS, 32, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(32, 48, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(48, width, 3, padding=1), nn.ReLU(inplace=True),
        )
        self.scalar = nn.Sequential(
            nn.Linear(SCALAR_DIM, 192), nn.ReLU(inplace=True),
            nn.Linear(192, 128), nn.ReLU(inplace=True),
        )
        # 相手は最大人数ぶんの固定スロット + マスク。人数が 2〜8 で変わっても
        # 観測の形が変わらないようにし、平均と最大の両方で集約する
        # （平均 = 全体の状況、最大 = いちばん危ない/弱い 1 人）
        self.opp = nn.Sequential(
            nn.Linear(OPP_FEATURES, 64), nn.ReLU(inplace=True),
            nn.Linear(64, 64), nn.ReLU(inplace=True),
        )
        self.opp_out = nn.Linear(128, 64)
        self.fuse = nn.Sequential(
            nn.Linear(width * 2 + 128 + 64, hidden), nn.ReLU(inplace=True),
            nn.Linear(hidden, hidden), nn.ReLU(inplace=True),
        )
        self.width = width
        self.hidden = hidden

    def forward(self, grid: torch.Tensor, scalar: torch.Tensor,
                opponents: torch.Tensor, opp_mask: torch.Tensor):
        spatial = self.conv(grid)                       # (B, W, H, W)
        pooled = torch.cat([spatial.mean(dim=(2, 3)),
                            spatial.amax(dim=(2, 3))], dim=1)
        s = self.scalar(scalar)

        o = self.opp(opponents)                         # (B, P, 64)
        m = opp_mask.unsqueeze(-1)
        denom = m.sum(dim=1).clamp(min=1.0)
        o_mean = (o * m).sum(dim=1) / denom
        o_max = (o + (m - 1.0) * 1e9).amax(dim=1)
        o = self.opp_out(torch.cat([o_mean, o_max], dim=1))

        fused = self.fuse(torch.cat([pooled, s, o], dim=1))
        return spatial, fused


class Policy(nn.Module):
    """複数ヘッド方策 + 1 つの価値関数。"""

    def __init__(self, width: int = 64, hidden: int = 256):
        super().__init__()
        self.body = Backbone(width, hidden)
        self.head_type = nn.Linear(hidden, ACTION_HEADS["type"])
        self.head_card = nn.Linear(hidden, ACTION_HEADS["card"])
        self.head_tower = nn.Linear(hidden, ACTION_HEADS["tower"])
        self.head_unit = nn.Linear(hidden, ACTION_HEADS["unit"])
        self.head_spec = nn.Linear(hidden, ACTION_HEADS["spec"])
        self.head_send = nn.Linear(hidden, ACTION_HEADS["send"])
        # セルヘッドだけは盤面の解像度を保ったまま出す。
        # 全結合で 729 個を出すと「どこに置くか」の空間構造が壊れる
        self.cell_film = nn.Linear(hidden, width)
        self.cell_conv = nn.Sequential(
            nn.Conv2d(width, 32, 3, padding=1), nn.ReLU(inplace=True),
            nn.Conv2d(32, 1, 1),
        )
        self.value = nn.Linear(hidden, 1)

    def forward(self, obs: Dict[str, torch.Tensor]):
        grid = obs["grid"]
        if grid.dtype == torch.uint8:
            grid = grid.float() / OBS_SCALE
        spatial, fused = self.body(grid, obs["scalar"], obs["opponents"],
                                   obs["opp_mask"])
        cell_map = self.cell_conv(
            F.relu(spatial + self.cell_film(fused)[:, :, None, None]))
        logits = {
            "type": self.head_type(fused),
            "card": self.head_card(fused),
            "tower": self.head_tower(fused),
            "unit": self.head_unit(fused),
            "spec": self.head_spec(fused),
            "send": self.head_send(fused),
            "cell": cell_map.flatten(1),
        }
        return logits, self.value(fused).squeeze(-1)

    # ---------------------------------------------------------------- 集計
    @staticmethod
    def _logp_entropy(logits: torch.Tensor, mask: torch.Tensor,
                      action: torch.Tensor):
        dist = torch.distributions.Categorical(logits=masked_logits(logits, mask))
        return dist.log_prob(action), dist.entropy()

    def evaluate(self, obs: Dict[str, torch.Tensor],
                 masks: Dict[str, torch.Tensor],
                 actions: Dict[str, torch.Tensor]):
        """保存した行動の log 確率・entropy・価値を計算し直す（PPO の更新用）。

        **使わなかったヘッドは足さない。** 例えば SEND を選んだステップで
        ``cell`` の log 確率まで足すと、置いてもいないセルの選び方に
        勾配が流れて学習が濁る。
        """
        logits, value = self.forward(obs)
        a_type = actions["type"]
        logp, ent = self._logp_entropy(logits["type"], masks["type"], a_type)

        def add(name: str, use: torch.Tensor):
            nonlocal logp, ent
            lp, e = self._logp_entropy(logits[name], masks[name], actions[name])
            use = use.float()
            logp = logp + lp * use
            ent = ent + e * use

        is_card = a_type == A_CARD
        is_tower = a_type == A_TOWER
        add("card", is_card)
        add("tower", is_tower)
        add("cell", is_card | is_tower)
        add("unit", (a_type == A_UPGRADE) | (a_type == A_SELL))
        add("spec", a_type == A_UPGRADE)
        add("send", a_type == A_SEND)
        return logp, ent, value


def uniform_masks(n: int, device) -> Dict[str, torch.Tensor]:
    return {k: torch.ones(n, v, dtype=torch.bool, device=device)
            for k, v in ACTION_HEADS.items()}
