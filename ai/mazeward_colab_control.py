# -*- coding: utf-8 -*-
"""MAZEWARD — Colab 上の学習を外から操作するための制御サーバー。

手元のPCのコントロールパネル(GUI)が、このサーバーへ HTTP で要求を送り、
Colab 上で動く train.py を開始/停止し、進捗(生成ログと ppo_log.json)を
受け取ります。トンネルは Colab ノートブックが ngrok で張ります。



【GUIとの契約 — dashboard/dashboard_server.py の TrainingManager と揃える】
- /colab/status と /colab/history は、ローカルGUIの
  manager.status() / manager.records() と同じ JSON 構造を返す。
  GUIは「ローカル実行」と「Colabリモート」を透過的に切り替えるので、
  ここだけ表示形式が変わると GUI が壊れる。
- /colab/start が受け取る body はローカルGUIの /api/start と同じ形
  ({mode, gens, num_envs, randomize, gen_early, gen_max, finish_early,
   finish_late, match_max})。





【永続化】
train.py (trainer_pb.py) が世代ログとチェックポイントを
/content/drive/MyDrive/mazeward_checkpoints に書き出すので、
ランタイムが切れても続きから再開できます。



起動(ノートブック内で):
    python ai/mazeward_colab_control.py --port 5558
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time

try:
    from flask import Flask, jsonify, request
except ImportError:  # pragma: no cover
    print("Flask がありません: Colab で `!pip install flask` を実行してください", file=sys.stderr)
    raise

AI_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(AI_DIR, "models")
LOG_FILE = os.path.join(MODEL_DIR, "ppo_log.json")

ENV_PREFIX = "MAZEWARD"
TRAIN_SCRIPT = os.path.join(AI_DIR, "train.py")

MODES = {"リーグ自己対戦": [], "カリキュラムなし": []}
MODE_ENV = {
    "リーグ自己対戦": {f"{ENV_PREFIX}_CURRICULUM": "1"},
    "カリキュラムなし": {f"{ENV_PREFIX}_CURRICULUM": "0"},
}

# dashboard_server.py の METRICS の key 一覧と同じ並び。
_NUM_KEYS = (
    "win_vs_random", "win_vs_best", "elo", "loss", "kl", "entropy",
    "fps", "seconds_per_gen", "games_finished", "game_minutes",
    "game_hours_total", "match_completion", "timeout_rate", "updates",
    "finish_rate", "avg_turn", "avg_path_length", "avg_tower_passes",
    "card_usage_rate", "sends_per_game", "leaks_per_game", "avg_income_final",
    "avg_coin_efficiency", "tower_count_final", "tower_avg_level",
    "num_players", "counter_push_rate", "pressured_finish_rate",
    "num_envs", "value_loss",
)
DICT_METRICS = ("win_rate_by_players", "tower_type_rates",
                "send_type_rates", "leak_type_rates")


def _read_json(path, default):
    if not os.path.exists(path):
        return default
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError, UnicodeDecodeError):
        return default


def _mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return 0.0

class ColabTrainingManager:
    def __init__(self):
        self.process = None
        self.is_running = False
        self.current_mode = None
        self.logs = []
        self.live = None
        self.live_stats = []
        self._lock = threading.Lock()
        self._log_cache = {"mtime": -1, "records": []}
        self._merged_sig = None
        self._merged_cache = []

    # ── 起動・停止 ──
    def start(self, mode, gens, num_envs, extra=None):
        if self.is_running:
            return False, "既に学習中です"
        cmd = [sys.executable, TRAIN_SCRIPT] + MODES.get(mode, [])
        env = os.environ.copy()
        env["PYTHONIOENCODING"] = "utf-8"
        env[f"{ENV_PREFIX}_MAX_GENS"] = str(gens)
        if num_envs:
            env[f"{ENV_PREFIX}_NUM_ENVS"] = str(int(num_envs))
        env.update(MODE_ENV.get(mode, {}))
        for key, value in (extra or {}).items():
            env[f"{ENV_PREFIX}_{key}"] = str(value)
        try:
            self.process = subprocess.Popen(
                cmd, cwd=AI_DIR, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", bufsize=1)
        except OSError as e:
            return False, f"プロセス起動に失敗: {e}"

        self.is_running = True
        self.current_mode = mode
        self.logs, self.live, self.live_stats = [], None, []
        self._add_log("info", f"学習を開始します（{mode}）")
        threading.Thread(target=self._read_output, daemon=True).start()
        return True, "学習を開始しました"

    def stop(self):
        if not self.is_running or not self.process:
            return False, "学習は実行されていません"
        self.process.terminate()
        self._add_log("warn", "停止を要求しました")
        return True, "停止しました"

    # ── 標準出力の読み取り ──
    def _read_output(self):
        for line in iter(self.process.stdout.readline, ""):
            line = line.rstrip("\n")
            if not line:
                continue
            # 進捗行はタイルに出すだけで生ログには残さない（毎ステップ出るため）
            if self._parse(line):
                continue
            tag = ("error" if ("失敗" in line or "Traceback" in line
                               or "Error" in line)
                   else "warn" if "警告" in line else "info")
            self._add_log(tag, line)

        code = self.process.wait()
        self._add_log("success" if code == 0 else "error",
                      f"学習が終了しました (exit={code})")
        self.is_running = False

    def _add_log(self, tag, text):
        with self._lock:
            self.logs.append({"tag": tag, "text": text,
                              "time": time.strftime("%H:%M:%S")})
            self.logs = self.logs[-2000:]

    _RE_PROGRESS = re.compile(
        r"\[Progress\](?: Gen (\d+) \|)? Step (\d+)/(\d+)"
        r"(?: \| Done: (\d+)/(\d+))?(?: \(([\d.]+)%\))?(?: \| Speed: ([\d.]+))?")
    _RE_GEN = re.compile(r"\[Gen (\d+)\]")

    def _parse(self, line):
        m = self._RE_PROGRESS.search(line)
        if m:
            with self._lock:
                self.live = {
                    "episode": int(m.group(1)) if m.group(1) else None,
                    "phase": "train" if m.group(1) and int(m.group(1)) > 0 else "pretest",
                    "step": int(m.group(2)), "max_steps": int(m.group(3)),
                    "games_finished": int(m.group(4)) if m.group(4) else None,
                    "num_envs": int(m.group(5)) if m.group(5) else None,
                    "finish_rate": float(m.group(6)) if m.group(6) else None,
                    "fps": float(m.group(7)) if m.group(7) else None,
                }
            return True

        m = self._RE_GEN.search(line)
        if m:
            entry = {"episode": int(m.group(1))}
            for key in _NUM_KEYS:
                mv = re.search(rf"{key}[:=]\s*(-?[\d.]+)", line, re.I)
                if mv:
                    entry[key] = float(mv.group(1))
            with self._lock:
                self.live_stats.append(entry)
                self.live_stats = self.live_stats[-500:]
        return False

    # ── 世代データ（ディスクが正） ──
    def _disk_records(self):
        mtime = _mtime(LOG_FILE)
        if self._log_cache["mtime"] == mtime:
            return self._log_cache["records"]
        records = []
        for item in _read_json(LOG_FILE, []):
            if not isinstance(item, dict) or "episode" not in item:
                continue
            rec = {"episode": item["episode"], "timestamp": item.get("timestamp")}
            for key in _NUM_KEYS:
                v = item.get(key)
                rec[key] = v if isinstance(v, (int, float)) else None
            for key in DICT_METRICS:
                if isinstance(item.get(key), dict):
                    rec[key] = {k: v for k, v in item[key].items()
                                if isinstance(v, (int, float))}
            rec["curriculum"] = item.get("curriculum")
            rec["balance_fingerprint"] = item.get("balance_fingerprint")
            records.append(rec)
        records.sort(key=lambda r: r["episode"] or 0)
        self._log_cache = {"mtime": mtime, "records": records}
        return records

    def records(self):
        with self._lock:
            sig = (len(self.live_stats),
                   json.dumps(self.live_stats[-1], sort_keys=True)
                   if self.live_stats else "")
        sig = (_mtime(LOG_FILE), sig)
        if self._merged_sig == sig:
            return self._merged_cache

        by_ep = {r["episode"]: dict(r) for r in self._disk_records()}
        with self._lock:
            live_stats = list(self.live_stats)
        for entry in live_stats:
            target = by_ep.setdefault(entry["episode"], {"episode": entry["episode"]})
            for k, v in entry.items():
                if target.get(k) is None:
                    target[k] = v
            target.setdefault("live", True)

        merged = [by_ep[e] for e in sorted(by_ep)]
        self._merged_sig, self._merged_cache = sig, merged
        return merged

    @staticmethod
    def _pace(records, window=10):
        stamps = [r.get("timestamp") for r in records[-(window + 1):]
                  if r.get("timestamp")]
        if len(stamps) < 2:
            return None
        try:
            fmt = "%Y-%m-%d %H:%M:%S"
            deltas = [time.mktime(time.strptime(b, fmt))
                      - time.mktime(time.strptime(a, fmt))
                      for a, b in zip(stamps, stamps[1:])]
        except ValueError:
            return None
        good = [d for d in deltas if 0 < d < 86400]
        return round(sum(good) / len(good), 1) if good else None

    def status(self):
        records = self.records()
        latest = records[-1] if records else None
        with self._lock:
            logs, live = self.logs[-200:], (dict(self.live) if self.live else None)
        return {
            "is_running": self.is_running,
            "mode": self.current_mode,
            "live": live,
            "latest": latest,
            "gen_count": len(records),
            "pace_sec": self._pace(records),
            "logs": logs,
            "log_version": f"{_mtime(LOG_FILE):.3f}:{len(records)}",
}
app = Flask(__name__)
manager = ColabTrainingManager()


def _load_token():
    """API トークン（承認）。未設定なら認証なし。源泉は環境変数→ ai/API.txt。"""
    t = os.environ.get(f"{ENV_PREFIX}_COLAB_TOKEN", "").strip()
    if not t:
        api_txt = os.path.join(AI_DIR, "API.txt")
        if os.path.exists(api_txt):
            try:
                with open(api_txt, encoding="utf-8") as f:
                    t = f.read().strip()
            except OSError:
                pass
    return t or None


AUTH_TOKEN = _load_token()


@app.before_request
def _require_token():
    """トークンが設定されている場合、全 API に X-API-Token を要求する。"""
    if AUTH_TOKEN is None:
        return None
    if request.headers.get("X-API-Token") != AUTH_TOKEN:
        return jsonify({"ok": False, "error": "unauthorized"}), 401
    return None


@app.get("/healthz")
def healthz():
    return jsonify({"ok": True, "service": "mazeward_colab_control", "version": 1})


@app.post("/colab/start")
def colab_start():
    d = request.json or {}
    extra = {"RANDOMIZE": d.get("randomize", 0.20)}
    for key, env_key in (("gen_early", "GEN_EARLY_MIN"),
                         ("gen_max", "GEN_MAX_MIN"),
                         ("finish_early", "GEN_FINISH_EARLY"),
                         ("finish_late", "GEN_FINISH_LATE"),
                         ("match_max", "MATCH_MAX_MIN")):
        if d.get(key) is not None:
            extra[env_key] = d[key]
    ok, msg = manager.start(d.get("mode") or next(iter(MODES)),
                            d.get("gens", 20), d.get("num_envs") or 0, extra)
    return jsonify({"ok": ok, "message": msg})


@app.post("/colab/stop")
def colab_stop():
    ok, msg = manager.stop()
    return jsonify({"ok": ok, "message": msg})


@app.get("/colab/status")
def colab_status():
    return jsonify(manager.status())


@app.get("/colab/history")
def colab_history():
    return jsonify({"records": manager.records()})


def main() -> None:
    ap = argparse.ArgumentParser(description="MAZEWARD Colab 制御サーバー")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=5558)
    args = ap.parse_args()
    print(f"MAZEWARD Colab 制御サーバー → http://{args.host}:{args.port}",
          flush=True)
    app.run(host=args.host, port=args.port, debug=False, threaded=True)


if __name__ == "__main__":
    main()