# -*- coding: utf-8 -*-
"""学習した方策を Minecraft サーバーへ貸し出すブリッジ。

    python mc_brain.py [--port 25577] [--model models/ppo_latest.pt] [--greedy]

Minecraft 側（``dev.antigravity.mazeward.ai.BrainClient``）が試合の状態を
1 行の JSON で投げてくるので、**学習と同じ観測**（:mod:`mazeward_env.mc_snapshot`）
に変換して方策を引き、選んだ手を 1 行ずつ返す。

なぜこうしたか
--------------
方策そのものは小さな CNN なので Java でも動かせる。難しいのは方策ではなく
**観測**で、その定義は :mod:`mazeward_env.observation` と :mod:`mazeward_env.env`
に 1 本だけある。Java へ写すと学習側を触るたびに 2 箇所を直す必要が生まれ、
**片方だけ直しても何も壊れない**（AI が少し弱くなるだけ）。
壊れ方が静かなものは、実装量が多くても 1 本に寄せたほうがいい。

副作用として、**学習中のチェックポイントをそのまま遊べる**ようになった。
``models/ppo_latest.pt`` を指したまま世代が進めば、次に繋いだ試合から
新しい方策が相手になる。

プロトコル（1 行 1 メッセージ）
------------------------------
=====================  ===========================================
``req <番号> <JSON>``  状態。返事は下の 2 つ
``act <番号> <席> …``  1 手。席ごとに 1 行
``end <番号>``         その要求の終わり
``err <番号> <理由>``  作れなかった（Java 側は貪欲ボットで続ける）
``hello <名前>``       接続直後に 1 度だけ。サイドバーに出る名前
=====================  ===========================================
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import socketserver
import sys
import threading
import traceback
from typing import Dict, List

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import balance as B                                        # noqa: E402
import trainer_pb as pb                                     # noqa: E402
from mazeward_env.env import (A_CARD, A_SELL, A_SEND, A_SKIP, A_TOWER,  # noqa: E402
                              A_UPGRADE, ACTION_HEADS)
from mazeward_env.mc_snapshot import SnapshotEncoder        # noqa: E402
from policy import Policy, masked_logits                    # noqa: E402

# **必ず最初に呼ぶ。** Minecraft サーバーから起動されると標準出力は
# 親コンソールの符号化（日本語 Windows なら cp932）になり、"—" のような
# 文字を 1 つ出した瞬間に UnicodeEncodeError で **ブリッジごと落ちる**。
# 落ちた側は貪欲ボットに切り替わるだけなので、画面には
# 「AI が弱い」としか出ず、原因に辿り着けない（実際に踏んだ）。
pb.ensure_utf8_stdout()

DEVICE = torch.device("cpu")
"""**CPU で動かす。** 1 秒に 1 回・最大 8 島の前向き計算に GPU を占有すると、
裏で学習を回しているときにそちらが遅くなる。実測でも 1 回 2〜4ms で足りる。"""


class Brain:
    """方策 1 つぶん。接続をまたいで使い回す。

    **試合中でもモデルを差し替えられる。** Minecraft 側からモデルを選ぶとき、
    ブリッジを立て直していると torch の読み込みで毎回数秒待つことになる。
    重みだけ入れ替えれば 0.2 秒で済む（観測の作りは変わらないため）。
    """

    def __init__(self, model_path: str, greedy: bool = False):
        self.encoder = SnapshotEncoder()
        self.greedy = greedy
        self.lock = threading.Lock()
        self.net = Policy().to(DEVICE)
        self.model_dir = os.path.dirname(os.path.abspath(model_path))
        self.model_path = os.path.abspath(model_path)
        self.loaded = False
        self.label = self._load(model_path)
        self.net.eval()

    def _load(self, path: str) -> str:
        """重みを読む。失敗しても例外は投げず、ラベルに理由を残す。"""
        self.loaded = False
        if not os.path.isfile(path):
            print(f"[brain] {path} が無いので初期値のまま動かします（弱いです）")
            return "未学習の方策（重みが無い）"
        state = torch.load(path, map_location=DEVICE, weights_only=False)
        weights = state.get("net", state) if isinstance(state, dict) else state
        try:
            self.net.load_state_dict(weights)
        except RuntimeError as mismatch:
            # 観測の作りを変えると入力次元が変わり、古い重みは形が合わなくなる。
            # ここで落とすと Minecraft 側は「AI が繋がらない」としか分からないので、
            # 理由を出して貪欲ボットに任せる（試合は成立する）
            print(f"[brain] {path} は今の観測と形が合いません: {mismatch}")
            print("[brain] 観測か行動の定義を変えたあとは学習をやり直してください")
            return "形の合わない方策（貪欲ボットに任せます）"
        generation = state.get("gen") if isinstance(state, dict) else None
        name = os.path.basename(path)
        self.model_path = os.path.abspath(path)
        self.loaded = True
        print(f"[brain] {path} を読み込みました"
              + (f"（第 {generation} 世代）" if generation is not None else ""))
        return name + (f" gen{generation}" if generation is not None else "")

    # ---------------------------------------------------------------- 差し替え
    def use(self, name: str) -> str:
        """``models/`` の中のファイル名を指定して読み直す。

        **ディレクトリの外は見に行かない。** ネットワーク越しに来た文字列を
        そのままパスとして開くと、任意のファイルを読ませられてしまう。
        """
        safe = os.path.basename(name.strip())
        if not safe.endswith(".pt"):
            safe += ".pt"
        path = os.path.join(self.model_dir, safe)
        if not os.path.isfile(path):
            raise FileNotFoundError(safe)
        with self.lock:
            self.label = self._load(path)
        return self.label

    def list_models(self) -> List[str]:
        """``models/`` にある重みの一覧（新しい順）。"""
        if not os.path.isdir(self.model_dir):
            return []
        files = [f for f in os.listdir(self.model_dir) if f.endswith(".pt")]
        files.sort(key=lambda f: os.path.getmtime(os.path.join(self.model_dir, f)),
                   reverse=True)
        return files

    # ---------------------------------------------------------------- 推論
    @torch.no_grad()
    def decide(self, snap: dict) -> List[str]:
        """1 局面ぶんの行動を、送り返す行のリストで返す。"""
        with self.lock:
            obs = self.encoder.encode(snap)
            boards = self.encoder.boards
            ask = [int(s) for s in snap.get("ask", [])
                   if 0 <= int(s) < len(boards)]
            if not ask:
                return []

            tensors = {
                key: torch.as_tensor(obs[key], device=DEVICE)
                for key in ("grid", "scalar", "opponents", "opp_mask")
            }
            logits, _ = self.net(tensors)

            n = len(boards)
            action = {name: np.zeros(n, dtype=np.int64) for name in ACTION_HEADS}
            action["type"] = self._pick(logits["type"], obs["mask_type"])
            action["card"] = self._pick(logits["card"], obs["mask_card"])
            action["tower"] = self._pick(logits["tower"], obs["mask_tower"])
            unit_mask = np.where((action["type"] == A_UPGRADE)[:, None],
                                 obs["mask_unit_upgrade"], obs["mask_unit_sell"])
            action["unit"] = self._pick(logits["unit"], unit_mask)
            action["send"] = self._pick(logits["send"], obs["mask_send"])
            action["send_n"] = self._pick(logits["send_n"], obs["mask_send_n"])
            action["spec"] = self._pick(
                logits["spec"], np.ones((n, ACTION_HEADS["spec"]), dtype=bool))
            # セルのマスクは選んだ形に依存するので、ここだけ後から作る
            cell_mask = self.encoder.cell_mask(
                boards, action["type"], action["card"], action["tower"])
            action["cell"] = self._pick(logits["cell"], cell_mask)

            return [f"{seat} {self._verb(action, seat)}" for seat in ask]

    def _pick(self, logits: torch.Tensor, mask: np.ndarray) -> np.ndarray:
        ml = masked_logits(logits, torch.as_tensor(mask, device=DEVICE))
        if self.greedy:
            return ml.argmax(dim=-1).cpu().numpy()
        return torch.distributions.Categorical(logits=ml).sample().cpu().numpy()

    @staticmethod
    def _verb(action: Dict[str, np.ndarray], seat: int) -> str:
        """行動を Java が読む 1 行にする。"""
        kind = int(action["type"][seat])
        cell = int(action["cell"][seat])
        x, z = cell % B.MAX_BOARD, cell // B.MAX_BOARD
        if kind == A_CARD:
            choice = int(action["card"][seat])
            return f"card {choice // 4} {choice % 4} {x} {z}"
        if kind == A_TOWER:
            choice = int(action["tower"][seat])
            return f"tower {B.TOWER_ORDER[choice // 4]} {choice % 4} {x} {z}"
        if kind == A_UPGRADE:
            return f"upgrade {int(action['unit'][seat])} {int(action['spec'][seat])}"
        if kind == A_SELL:
            return f"sell {int(action['unit'][seat])}"
        if kind == A_SEND:
            # 体数は 1 始まり。1 のときも明示して送る（Java 側は省略も許す）
            count = int(action["send_n"][seat]) + 1
            return f"send {B.ATTACKER_ORDER[int(action['send'][seat])]} {count}"
        return "skip"


class Handler(socketserver.StreamRequestHandler):
    """1 接続。Minecraft サーバーは 1 つなので、実際にはほぼ 1 本しか来ない。"""

    def handle(self) -> None:
        brain: Brain = self.server.brain
        peer = self.client_address
        print(f"[brain] 接続 {peer[0]}:{peer[1]}")
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._send(f"hello {brain.label}")
        try:
            for raw in self.rfile:
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                if line == "ping":
                    self._send("hello " + brain.label)
                    continue
                if line == "models":
                    for name in brain.list_models():
                        self._send("model " + name)
                    self._send("endmodels")
                    continue
                if line.startswith("use "):
                    self._use(brain, line[4:])
                    continue
                if not line.startswith("req "):
                    continue
                head, _, payload = line[4:].partition(" ")
                self._answer(brain, head.strip(), payload)
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            print(f"[brain] 切断 {peer[0]}:{peer[1]}")

    def _use(self, brain: Brain, name: str) -> None:
        """モデルを差し替える。成功しても失敗しても、必ず結果を返す。

        黙って失敗すると Minecraft 側には「選んだのに変わらない」としか見えない。
        """
        try:
            label = brain.use(name)
        except FileNotFoundError as missing:
            self._send(f"err 0 モデルが見つかりません: {missing}")
            return
        except Exception as exception:                      # noqa: BLE001
            traceback.print_exc()
            self._send(f"err 0 モデルを読めません: {exception}")
            return
        self._send("hello " + label)

    def _answer(self, brain: Brain, request_id: str, payload: str) -> None:
        try:
            actions = brain.decide(json.loads(payload))
        except Exception as exception:                      # noqa: BLE001
            # ここで落とすと試合が止まる。理由を返して貪欲ボットに任せる
            traceback.print_exc()
            self._send(f"err {request_id} {type(exception).__name__}: {exception}")
            return
        for action in actions:
            self._send(f"act {request_id} {action}")
        self._send(f"end {request_id}")

    def _send(self, line: str) -> None:
        self.wfile.write((line + "\n").encode("utf-8"))
        self.wfile.flush()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def selftest(brain: Brain) -> int:
    """Java 抜きで往復を確かめる。空の盤面 2 島から 1 手ずつ引く。"""
    size = 21
    cells = ["." * size for _ in range(size)]
    cells[1] = "." * 10 + "S" + "." * (size - 11)
    cells[size - 2] = "." * 10 + "C" + "." * (size - 11)
    flat = "".join(cells)

    def board(seat: int) -> dict:
        return {
            "seat": seat, "name": f"AI{seat}", "alive": True,
            "coins": 200, "income": 20, "stock": 30, "lives": 20, "maxLives": 20,
            "steps": 5, "invalid": 1,
            "sends": {"d10": 0.0, "d30": 0.0, "total": 0, "lastCost": 0, "income": 0},
            "hand": ["I3", "L", "O"], "pile": 11,
            "size": size, "cells": flat,
            "spawns": [[10, 1]], "core": [10, size - 2],
            "paths": [[[10, 1], [10, size - 2]]],
            "towers": [], "enemies": [],
        }

    snap = {
        "v": 1,
        "match": {"tick": 1300, "prepTicks": 1200, "suddenDeath": 18000,
                  "cardInterval": 600, "handLimit": 6, "maxTowers": 24,
                  "players": 2},
        "boards": [board(0), board(1)],
        "ask": [0, 1],
    }
    obs = brain.encoder.encode(snap)
    print("[selftest] grid", obs["grid"].shape, "scalar", obs["scalar"].shape,
          "opponents", obs["opponents"].shape)
    print("[selftest] 打てる行動種別", obs["mask_type"][0].astype(int))
    for _ in range(5):
        print("[selftest] 行動:", brain.decide(snap))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="MAZEWARD 対戦 AI ブリッジ")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("MAZEWARD_BRAIN_PORT", 25577)))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--model", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "models", "ppo_latest.pt"))
    parser.add_argument("--greedy", action="store_true",
                        help="いちばん確率の高い手だけを打つ（強いが単調になる）")
    parser.add_argument("--selftest", action="store_true",
                        help="Minecraft 無しで往復だけ確認する")
    args = parser.parse_args()

    brain = Brain(args.model, greedy=args.greedy)
    if args.selftest:
        return selftest(brain)

    server = Server((args.host, args.port), Handler)
    server.brain = brain
    # 記号は ASCII に留める。ensure_utf8_stdout が効かない環境
    # （再設定できない標準出力）でも、ここで落ちないようにするため
    print(f"[brain] {args.host}:{args.port} で待機中 / モデル {brain.label}")
    print("[brain] Minecraft 側でロビーの「AI」を選ぶと繋がります。Ctrl+C で終了")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[brain] 終了します")
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
