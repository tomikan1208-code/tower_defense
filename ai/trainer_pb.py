# -*- coding: utf-8 -*-
"""学習スクリプトと GUI をつなぐ窓口。**この 2 つだけで会話する。**

1. 世代ごとに追記される **JSON ログ** … グラフと表の正。プロセスが死んでも残る
2. 進捗を伝える **標準出力の 1 行** … JSON に書かれる前を埋めるライブ表示

``ai-training-dashboard`` スキルの ``references/trainer-contract.md`` に従う。
特に踏みやすい落とし穴を 3 つ、ここで潰してある。

- **``\\r`` を使わない。** パイプで読む GUI には行が届かず、世代が終わるまで
  1 文字も出ない。``isatty`` で出し分ける
- **``encoding="utf-8"`` と ``ensure_ascii=False``。** Windows 既定の cp932 だと
  日本語ラベルが入った瞬間に落ちる
- **例外を握りつぶさない。** 終了コードで失敗を伝える（:func:`run_guarded`）
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import time
import traceback
from typing import Any, Callable, Dict, List, Optional

# Colab では Drive、ローカルでは models/ を正本にする
DRIVE_DATA_DIR = "/content/drive/MyDrive/mazeward_checkpoints"
_LOCAL_BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")

#: JSON ログに残す世代数の上限
MAX_LOG_ENTRIES = 2000


def ensure_utf8_stdout() -> None:
    """Windows のコンソールでも日本語が化けないようにする。"""
    for stream in ("stdout", "stderr"):
        s = getattr(sys, stream, None)
        if s is not None and hasattr(s, "reconfigure"):
            try:
                s.reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass


def effective_dir() -> str:
    """Colab なら Drive、ローカルなら ``models/``。"""
    return DRIVE_DATA_DIR if os.path.isdir(DRIVE_DATA_DIR) else _LOCAL_BASE


def persist_data_file(local_path: str) -> None:
    """保存時に呼ぶ。永続フォルダへコピーする。

    同一パスを弾いておくと **ローカル実行では自動的に何もしない** ので、
    Colab とローカルで分岐を書き分けずに済む。
    """
    dest = os.path.join(effective_dir(), os.path.basename(local_path))
    if os.path.abspath(local_path) == os.path.abspath(dest):
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        shutil.copy2(local_path, dest)
    except OSError:
        pass          # 永続化に失敗しても学習は止めない


def recover_data_file(local_path: str) -> None:
    """起動時に呼ぶ。永続フォルダの方を正本として復元する。"""
    src = os.path.join(effective_dir(), os.path.basename(local_path))
    if os.path.abspath(local_path) == os.path.abspath(src):
        return
    if os.path.exists(src):
        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        try:
            shutil.copy2(src, local_path)
        except OSError:
            pass


class ProgressLogger:
    """世代ログの追記と進捗行の出力。"""

    def __init__(self, save_dir: str, model_name: str = "ppo",
                 min_interval: float = 0.5):
        self.save_dir = save_dir
        self.model_name = model_name
        self.log_file = os.path.join(save_dir, f"{model_name}_log.json")
        self.min_interval = min_interval
        self._last_emit = 0.0
        os.makedirs(save_dir, exist_ok=True)
        recover_data_file(self.log_file)

    # ---------------------------------------------------------------- ①
    def log_generation(self, episode: int, metrics: Dict[str, Any]) -> None:
        """1 世代終わるごとに 1 件追記する。"""
        logs: List[dict] = []
        if os.path.exists(self.log_file):
            try:
                with open(self.log_file, "r", encoding="utf-8") as f:
                    logs = json.load(f)
            except (json.JSONDecodeError, OSError, UnicodeDecodeError):
                logs = []          # 壊れていたら捨てて続行（学習は止めない）
        if not isinstance(logs, list):
            logs = []
        entry = {"episode": episode,
                 "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")}
        entry.update(metrics)
        logs.append(entry)
        logs = logs[-MAX_LOG_ENTRIES:]
        tmp = self.log_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(logs, f, indent=2, ensure_ascii=False)
        os.replace(tmp, self.log_file)      # 途中で落ちても壊れたJSONを残さない
        persist_data_file(self.log_file)

    # ---------------------------------------------------------------- ②
    def progress(self, episode: int, step: int, max_steps: int,
                 done: Optional[int] = None, total: Optional[int] = None,
                 rate: Optional[float] = None, speed: Optional[float] = None,
                 force: bool = False) -> None:
        """進捗行。**必ず改行で終える。**

        出す間隔は時間ベース。ステップ数刻みだと刻みを過ぎたあとに
        表示が止まって見える（``100/120`` のまま固まる）。
        最終ステップは ``force=True`` で必ず出す。
        """
        now = time.time()
        if not force and now - self._last_emit < self.min_interval:
            return
        self._last_emit = now
        msg = f"[Progress] Gen {episode} | Step {step}/{max_steps}"
        if done is not None and total is not None:
            msg += f" | Done: {done}/{total}"
            if rate is not None:
                msg += f" ({rate * 100:.1f}%)"
        if speed is not None:
            msg += f" | Speed: {speed:.1f}"
        if sys.stdout.isatty():
            sys.stdout.write("\r" + msg)     # 人が見るときだけ上書き
            sys.stdout.flush()
        else:
            print(msg, flush=True)           # GUI が読むときは必ず改行

    def generation_line(self, episode: int, metrics: Dict[str, Any]) -> None:
        """世代の確定値を 1 行で出す。GUI はこれをライブ値として拾う。"""
        if sys.stdout.isatty():
            print()
        parts = []
        for key, value in metrics.items():
            if isinstance(value, float):
                parts.append(f"{key}: {value:.4f}")
            elif isinstance(value, (int, str)):
                parts.append(f"{key}: {value}")
        print(f"[Gen {episode}] " + " | ".join(parts), flush=True)


# ---------------------------------------------------------------- ③
def run_guarded(main: Callable[[], Any]) -> None:
    """例外を握りつぶさず、終了コードで失敗を伝える。

    握りつぶして 0 を返すと、GUI も自動改善ループも「成功したが指標が
    変わらない」と誤認して、壊れた設定のまま延々と空回りする。
    """
    ensure_utf8_stdout()
    try:
        main()
    except KeyboardInterrupt:
        print("\n中断しました", flush=True)
        sys.exit(130)
    except Exception:
        traceback.print_exc()
        print("学習が失敗しました", flush=True)
        sys.exit(1)


# ---------------------------------------------------------------- ④
def env_int(name: str, default: int) -> int:
    """設定は環境変数で受け取る（GUI からも Colab からも同じ手が使える）。"""
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return int(float(raw))
    except ValueError:
        return default


def env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def auto_num_envs() -> int:
    """並列環境数を実機に合わせて決める。

    トレーナー契約の VRAM 表は「環境が GPU 上で回る」前提だが、
    **この環境は numpy で CPU 側を回している**ので律速は CPU。
    GPU は方策の前後だけなので、VRAM ではなく論理コア数から決める。
    実測（RTX 3050 / 12 コア）で 1 島あたり 4700 step/s だったので、
    1 世代が数十秒に収まる規模を既定にしてある。
    """
    try:
        import os as _os
        cores = len(_os.sched_getaffinity(0))    # type: ignore[attr-defined]
    except AttributeError:
        cores = os.cpu_count() or 4
    return max(8, min(96, cores * 4))
