# -*- coding: utf-8 -*-
"""MAZEWARD VERSUS AI 学習コントロールパネル。

【設計の要】データの出どころを 2 つに分ける:
  1. ディスクの JSON ログ … 世代ごとの確定値。プロセスが死んでも残る。これが「正」
  2. 標準出力のパース   … まだ JSON に書かれていない進行中の分。ライブ表示専用
  混ぜてはいけない。2 は 1 で上書きされる前提の一時的な値。

テンプレート（ai-training-dashboard スキル）に、このゲーム固有の 2 タブを足してある。

- **対局ビューア** … 盤面・タワー・経路・送り履歴をテキストで再生する
- **バランス** … balance_analyzer のレポートと推奨値、`balance.py` への適用ボタン
"""

import ast
import io
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time

from flask import Flask, Response, jsonify, request, send_from_directory

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

_BASE = os.path.dirname(os.path.abspath(__file__))
_AI = os.path.join(os.path.dirname(_BASE), "ai")
sys.path.insert(0, _AI)
sys.path.insert(0, _BASE)
import colab_remote  # noqa: E402


# ══════════════════════════════════════════════════
# ■ プロジェクト設定（ここだけ書き換える）
# ══════════════════════════════════════════════════
APP_TITLE = "MAZEWARD VERSUS AI 学習コントロールパネル"
PORT = 5557
ENV_PREFIX = "MAZEWARD"
TRAIN_SCRIPT = os.path.join(_AI, "train.py")
LOG_FILE = os.path.join(_AI, "models", "ppo_log.json")
BALANCE_FILE = os.path.join(_AI, "balance.py")

MODES = {
    "リーグ自己対戦": [],
    "カリキュラムなし": [],
}
MODE_ENV = {
    "リーグ自己対戦": {f"{ENV_PREFIX}_CURRICULUM": "1"},
    "カリキュラムなし": {f"{ENV_PREFIX}_CURRICULUM": "0"},
}

# 指標。key はログのキー、fmt は 'rate'(0〜1を%表示) / 'float' / 'int'
METRICS = [
    # --- 本当に強くなったか（最重要） ---
    {"key": "win_vs_random", "label": "vs ランダム 勝率", "fmt": "rate"},
    {"key": "win_vs_best", "label": "vs 過去最強 勝率", "fmt": "rate"},
    {"key": "elo", "label": "Elo", "fmt": "int"},
    # --- 学習が進んでいるか ---
    {"key": "loss", "label": "Loss", "fmt": "float"},
    {"key": "kl", "label": "KL 発散", "fmt": "float"},
    {"key": "entropy", "label": "エントロピー", "fmt": "float"},
    # --- 速度・規模 ---
    {"key": "fps", "label": "毎秒ステップ数", "fmt": "int"},
    {"key": "seconds_per_gen", "label": "1 世代の実時間(秒)", "fmt": "float"},
    {"key": "games_finished", "label": "決着した対局数", "fmt": "int"},
    # --- ゲーム内時間（1 ステップ = ゲーム内 1 秒） ---
    {"key": "game_minutes", "label": "1 世代のゲーム内時間(分)", "fmt": "float"},
    {"key": "game_hours_total", "label": "ゲーム内 累計(時間)", "fmt": "float"},
    {"key": "match_completion", "label": "試合完了率(世代の区切り)", "fmt": "rate"},
    {"key": "timeout_rate", "label": "時間切れで終わった割合", "fmt": "rate"},
    {"key": "updates", "label": "1 世代の更新回数", "fmt": "int"},
    # --- ゲームが成立しているか ---
    {"key": "finish_rate", "label": "決着率(脱落者が出た割合)", "fmt": "rate"},
    {"key": "avg_turn", "label": "平均決着ステップ", "fmt": "int"},
    # --- MAZEWARD 固有 ---
    {"key": "avg_path_length", "label": "平均経路長", "fmt": "float"},
    {"key": "avg_tower_passes", "label": "射程通過回数", "fmt": "float"},
    {"key": "card_usage_rate", "label": "カード消化率", "fmt": "rate"},
    {"key": "sends_per_game", "label": "1 試合の送り数", "fmt": "float"},
    {"key": "leaks_per_game", "label": "1 試合の漏れ数", "fmt": "float"},
    {"key": "avg_income_final", "label": "最終インカム", "fmt": "float"},
    {"key": "avg_coin_efficiency", "label": "コイン効率", "fmt": "float"},
    {"key": "tower_count_final", "label": "最終タワー数", "fmt": "float"},
    {"key": "tower_avg_level", "label": "タワー平均レベル", "fmt": "float"},
    # --- 人数 / 相手 ---
    {"key": "num_players", "label": "平均人数", "fmt": "float"},
    {"key": "counter_push_rate", "label": "カウンタープッシュ率", "fmt": "rate"},
    {"key": "pressured_finish_rate", "label": "追い込み決着率", "fmt": "rate"},
]

# 「名前 → 数値」の入れ子辞書で記録している指標
DICT_METRICS = ["win_rate_by_players", "tower_type_rates", "send_type_rates", "leak_type_rates"]
# ══════════════════════════════════════════════════


app = Flask(__name__, static_folder="web", static_url_path="/static")

_NUM_KEYS = tuple(m["key"] for m in METRICS) + ("num_envs", "value_loss")


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


def python_exe():
    """子プロセスを起動する Python。exe 化しても学習が起動できるように。"""
    if getattr(sys, "frozen", False):
        return (os.environ.get(f"{ENV_PREFIX}_PYTHON")
                or shutil.which("python") or shutil.which("py") or "python")
    return sys.executable


class TrainingManager:
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
        cmd = [python_exe(), TRAIN_SCRIPT] + MODES.get(mode, [])
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
                cmd, cwd=_AI, env=env,
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


manager = TrainingManager()


# ══════════════════════════════════════════════════
# バランス診断
# ══════════════════════════════════════════════════
class BalanceService:
    """診断は数十秒かかるので、裏で走らせて結果をキャッシュする。"""

    def __init__(self):
        self.report = None
        self.running = False
        self.error = None
        self.updated = None
        self._lock = threading.Lock()

    def start(self, simulate=True):
        with self._lock:
            if self.running:
                return False, "診断を実行中です"
            self.running = True
            self.error = None
        threading.Thread(target=self._run, args=(simulate,), daemon=True).start()
        return True, "診断を開始しました"

    def _run(self, simulate):
        try:
            import importlib
            import balance
            import balance_analyzer
            importlib.reload(balance)
            importlib.reload(balance_analyzer)
            report = balance_analyzer.analyse(simulate=simulate)
            with self._lock:
                self.report = report.as_dict()
                self.updated = time.strftime("%Y-%m-%d %H:%M:%S")
        except Exception as e:  # noqa: BLE001
            import traceback
            with self._lock:
                self.error = f"{e}\n{traceback.format_exc()}"
        finally:
            with self._lock:
                self.running = False

    def state(self):
        with self._lock:
            return {"running": self.running, "report": self.report,
                    "error": self.error, "updated": self.updated}


balance_service = BalanceService()


def apply_recommendation(path, value):
    """``balance.py`` の数値を 1 つ書き換える。

    落とし穴対策を 3 つとも入れてある（pitfalls.md）。

    1. **宣言型へ強制** … 現在の値が整数なら整数で書き戻す。
       ``6`` を ``6.0`` にすると ``range()`` が TypeError で落ちる
    2. **退避する時点でも型を強制** … これを忘れると「元に戻す」が壊れ続ける
    3. **書いた直後に ast.parse で検証** し、不正なら元のファイルへ差し戻す
    """
    with open(BALANCE_FILE, encoding="utf-8") as f:
        original = f.read()

    num = r"-?\d+(?:\.\d+)?"
    text = None
    old_raw = None

    m = re.fullmatch(r"TOWERS\['(\w+)'\]\.(\w+)", path)
    if m:
        key, field = m.group(1), m.group(2)
        entry = re.search(rf'"{key}": TowerDef\((.*?)\n    \),',
                          original, re.S)
        if not entry:
            entry = re.search(rf'"{key}": TowerDef\((.*?)\)\),', original, re.S)
        if not entry:
            return False, f"balance.py に {key} の定義が見つかりません"
        block = entry.group(0)
        fm = re.search(rf"\b{field}\s*=\s*({num})", block)
        if not fm:
            return False, f"{key} に {field} の指定がありません"
        old_raw = fm.group(1)
        new_raw = _match_type(old_raw, value)
        new_block = block[:fm.start(1)] + new_raw + block[fm.end(1):]
        text = original.replace(block, new_block, 1)

    if text is None:
        m = re.fullmatch(r"ATTACKERS\['(\w+)'\]\.(\w+)", path)
        if m:
            key, field = m.group(1), m.group(2)
            order = ["cost", "income_gain", "stock_cost", "unlock_income", "hp"]
            if field not in order:
                return False, f"{field} は書き換えに対応していません"
            idx = order.index(field)
            entry = re.search(
                rf'"{key}":\s*AttackerDef\(\s*"[^"]*",\s*"[^"]*",\s*'
                + r",\s*".join([f"({num})"] * 5) + r"\s*\)", original)
            if not entry:
                return False, f"balance.py に {key} の定義が見つかりません"
            old_raw = entry.group(idx + 1)
            new_raw = _match_type(old_raw, value)
            s, e = entry.span(idx + 1)
            text = original[:s] + new_raw + original[e:]

    if text is None:
        m = re.fullmatch(r"(?:balance\.)?([A-Z][A-Z_0-9]*)", path)
        if m:
            name = m.group(1)
            cm = re.search(rf"^{name}\s*=\s*({num})\s*$", original, re.M)
            if not cm:
                return False, f"balance.py に定数 {name} が見つかりません"
            old_raw = cm.group(1)
            new_raw = _match_type(old_raw, value)
            s, e = cm.span(1)
            text = original[:s] + new_raw + original[e:]

    if text is None:
        m = re.fullmatch(r"ECONOMY\.(\w+)", path)
        if m:
            field = m.group(1)
            fm = re.search(rf"^\s*{field}:\s*(int|float)\s*=\s*([^\n#]+)",
                           original, re.M)
            if not fm:
                return False, f"Economy に {field} がありません"
            old_raw = fm.group(2).strip()
            new_raw = _match_type("0" if fm.group(1) == "int" else "0.0", value)
            s, e = fm.span(2)
            text = original[:s] + new_raw + original[e:]

    if text is None:
        return False, f"対応していない書き換え先です: {path}"

    with open(BALANCE_FILE, "w", encoding="utf-8") as f:
        f.write(text)

    # 3. 構文と型を検証し、壊れていたら差し戻す
    try:
        ast.parse(text)
        import importlib
        import balance
        importlib.reload(balance)
    except Exception as e:  # noqa: BLE001
        with open(BALANCE_FILE, "w", encoding="utf-8") as f:
            f.write(original)
        return False, f"書き換えに失敗したので元に戻しました: {e}"
    return True, f"{path} を {old_raw} → {value} に変更しました"


def _match_type(old_raw, value):
    """元の値が整数なら整数で、小数なら小数で書き戻す。"""
    if re.fullmatch(r"-?\d+", old_raw.strip()):
        return str(int(round(float(value))))
    return repr(round(float(value), 4))


# ══════════════════════════════════════════════════
# 対局ビューア
# ══════════════════════════════════════════════════
class ReplayService:
    """1 試合を回して、盤面・タワー・経路・送り履歴をテキストで残す。"""

    def __init__(self):
        self.replay = None
        self.running = False
        self.error = None
        self._lock = threading.Lock()

    def start(self, players=2, checkpoint=None, frames=14):
        with self._lock:
            if self.running:
                return False, "対局を生成中です"
            self.running = True
            self.error = None
        threading.Thread(target=self._run, args=(players, checkpoint, frames),
                         daemon=True).start()
        return True, "対局の生成を開始しました"

    def _run(self, players, checkpoint, frames):
        try:
            self.replay = build_replay(players, checkpoint, frames)
        except Exception as e:  # noqa: BLE001
            import traceback
            with self._lock:
                self.error = f"{e}\n{traceback.format_exc()}"
        finally:
            with self._lock:
                self.running = False

    def state(self):
        with self._lock:
            return {"running": self.running, "replay": self.replay,
                    "error": self.error}


def build_replay(players=2, checkpoint=None, frames=14):
    """対局を 1 つ回してフレーム列を作る。

    学習済みチェックポイントがあればそれを、無ければ貪欲ボットを使う。
    """
    import numpy as np
    from mazeward_env.bots_heuristic import empty_action, make_bot
    from mazeward_env.env import VersusEnv
    from mazeward_env.rules import EnvConfig
    import balance as B

    env = VersusEnv(EnvConfig(num_envs=1, players_choices=(players,),
                              board_size=21, max_ticks=20 * 60 * 10,
                              randomize=0.0, seed=int(time.time()) % 10000))
    obs = env.observe()
    seats = [i for i in range(players)]

    net = None
    if checkpoint:
        import torch
        from policy import Policy
        path = os.path.join(_AI, "models", checkpoint)
        if os.path.exists(path):
            net = Policy()
            net.load_state_dict(torch.load(path, map_location="cpu",
                                           weights_only=False)["net"])
            net.eval()

    bots = {s: make_bot("greedy_defense" if s % 2 == 0 else "income_push",
                        np.random.default_rng(s)) for s in seats}
    boards = np.arange(players)

    limit = env.cfg.max_ticks // env.cfg.decision_ticks
    every = max(1, limit // frames)
    out_frames = []
    sends = []
    last_sends = env.stat_sends.copy()

    for step in range(limit):
        action = empty_action(env.n)
        if net is not None:
            import torch
            from train import act as policy_act
            policy_act(net, env, obs, boards, action)
        else:
            for s in seats:
                bots[s].act(env, obs, np.array([s]), action)
        obs, reward, done, infos = env.step(action)

        new = env.stat_sends - last_sends
        for s in seats:
            if new[s] > 0:
                kind = int(action["send"][s])
                sec = int(env.env_tick[0] / 20)
                sends.append({
                    "tick": int(env.env_tick[0]),
                    "second": sec,
                    "clock": "%d:%02d" % (sec // 60, sec % 60),
                    "player": f"P{s + 1}",
                    "kind": B.ATTACKERS[B.ATTACKER_ORDER[kind]].name_jp,
                    "cost": B.ATTACKERS[B.ATTACKER_ORDER[kind]].cost,
                })
        last_sends = env.stat_sends.copy()

        if step % every == 0 or infos:
            out_frames.append(_snapshot(env, seats, step))
        if infos:
            break

    return {
        "players": players,
        "source": checkpoint or "貪欲ボット（守り） vs 送り特化ボット",
        "legend": ". 空き / # 壁 / % 岩 / S 出現 / C 拠点 / + 経路 / e 敵 / "
                  "英字 タワー（大文字 = 強化済み）",
        "frames": out_frames,
        "sends": sends[-200:],
    }


def _snapshot(env, seats, step):
    import balance as B
    boards = []
    for s in seats:
        towers = {}
        for slot in range(int(env.boards.tw_count[s])):
            kind = int(env.boards.tw_kind[s, slot])
            if kind < 0:
                continue
            name = B.TOWERS[B.TOWER_ORDER[kind]].name_jp
            lv = int(env.boards.tw_level[s, slot]) + 1
            towers[f"{name} Lv{lv}"] = towers.get(f"{name} Lv{lv}", 0) + 1
        boards.append({
            "player": f"P{s + 1}",
            "board": env.ascii_board(s),
            "lives": int(env.boards.lives[s]),
            "max_lives": int(env.boards.max_lives[s]),
            "coins": int(env.boards.coins[s]),
            "income": int(env.boards.income[s]),
            "stock": int(env.boards.stock[s]),
            "towers": towers,
            "tower_count": int(env.boards.tw_count[s]),
            "path_length": round(float(env.ground_len[s]), 1),
            "tower_passes": int(env.tower_passes[s]),
            "enemies": int(env.boards.en_count[s]),
            "hand": int(env.hand_n[s]),
        })
    second = int(env.env_tick[0] / 20)
    return {"step": step, "second": second,
            "clock": "%d:%02d" % (second // 60, second % 60),
            "boards": boards}


replay_service = ReplayService()


# ══════════════════════════════════════════════════
# ルート
# ══════════════════════════════════════════════════
@app.route("/")
def index():
    return send_from_directory("web", "index.html")


@app.route("/api/config")
def api_config():
    return jsonify({"title": APP_TITLE, "modes": list(MODES),
                    "metrics": METRICS, "dict_metrics": DICT_METRICS})


@app.route("/api/status")
def api_status():
    if colab_remote.enabled():
        try:
            payload = colab_remote.status()
            payload.setdefault("runtime", "colab")
            payload.setdefault("runtime_label", "Colab")
            return jsonify(payload)
        except Exception as e:
            return jsonify({"is_running": False, "mode": None, "live": None, "latest": None,
                            "gen_count": 0, "pace_sec": None,
                            "runtime": "colab", "runtime_label": "Colab",
                            "logs": [{"tag": "error", "text": f"Colab に接続できません: {e}",
                                      "time": time.strftime("%H:%M:%S")}],
                            "log_version": f"err-{time.time():.3f}"
                            })
    payload = manager.status()
    payload.setdefault("runtime", "local")
    payload.setdefault("runtime_label", "Local")
    return jsonify(payload)


@app.route("/api/history")
def api_history():
    if colab_remote.enabled():
        return jsonify({"records": colab_remote.history()})
    return jsonify({"records": manager.records()})


@app.route("/api/colab/state")
def api_colab_state():
    cfg = colab_remote.load_config()
    return jsonify({"url": cfg.get("url", ""), "enabled": bool(cfg.get("enabled")),
                    "ok": bool(cfg.get("ok")), "token": cfg.get("token", ""), "last_check": cfg.get("last_check"),
                    "last_error": cfg.get("last_error", "")})


@app.route("/api/colab/config", methods=["POST"])
def api_colab_config():
    d = request.json or {}
    cfg = colab_remote.load_config()
    if "url" in d:
        cfg["url"] = (d["url"] or "").strip().rstrip("/")
    if "enabled" in d:
        cfg["enabled"] = bool(d["enabled"])
    if "token" in d:
        cfg["token"] = (d["token"] or "").strip()
    colab_remote.save_config(cfg)
    return jsonify({
        "ok": True,
        "config": {
            "url": cfg["url"],
            "enabled": cfg["enabled"],
            "token": cfg.get("token", ""),
        },
    })


@app.route("/api/colab/test", methods=["POST"])
def api_colab_test():
    return jsonify(colab_remote.check_test())


@app.route("/api/start", methods=["POST"])
def api_start():
    d = request.json or {}
    if colab_remote.enabled():
        return jsonify(colab_remote.start(d))
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


@app.route("/api/stop", methods=["POST"])
def api_stop():
    if colab_remote.enabled():
        return jsonify(colab_remote.stop())
    ok, msg = manager.stop()
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/balance")
def api_balance():
    return jsonify(balance_service.state())


@app.route("/api/balance/run", methods=["POST"])
def api_balance_run():
    d = request.json or {}
    ok, msg = balance_service.start(simulate=bool(d.get("simulate", True)))
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/balance/apply", methods=["POST"])
def api_balance_apply():
    d = request.json or {}
    path, value = d.get("path"), d.get("value")
    if not path or value is None:
        return jsonify({"ok": False, "message": "path と value が必要です"})
    ok, msg = apply_recommendation(path, value)
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/balance/sync")
def api_balance_sync():
    """balance.py と Java enum の突き合わせ。"""
    try:
        import importlib
        import balance
        import sync_balance
        importlib.reload(balance)
        importlib.reload(sync_balance)
        return jsonify(sync_balance.run().as_dict())
    except Exception as e:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(e)})


@app.route("/api/replay")
def api_replay():
    return jsonify(replay_service.state())


@app.route("/api/replay/run", methods=["POST"])
def api_replay_run():
    d = request.json or {}
    ok, msg = replay_service.start(int(d.get("players", 2)),
                                   d.get("checkpoint") or None)
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/checkpoints")
def api_checkpoints():
    folder = os.path.join(_AI, "models")
    files = []
    if os.path.isdir(folder):
        files = sorted(f for f in os.listdir(folder) if f.endswith(".pt"))
    return jsonify({"checkpoints": files})


@app.route("/api/stream")
def api_stream():
    """状態が変わったときだけ送る。判定には進捗の値そのものを含める
    （ログ件数だけだと上限到達後に更新が止まる）。"""
    def status_provider():
        if colab_remote.enabled():
            return colab_remote.status()
        return manager.status()

    def generate():
        last = None
        while True:
            try:
                st = status_provider()
            except Exception as e:
                st = {"is_running": False, "mode": None, "live": None, "latest": None,
                      "gen_count": 0, "pace_sec": None,
                      "logs": [{"tag":"error", "text": f"Colab に接続できません: {e}",
                                "time": time.strftime("%H:%M:%S")}],
                      "log_version": f"err-{time.time():.3f}"}
            live = st.get("live") or {}
            sig = (st.get("is_running"), len(st.get("logs") or []), st.get("log_version"),
                   live.get("step"), live.get("games_finished"), live.get("fps"))
            if sig != last:
                last = sig
                yield f"data: {json.dumps(st, ensure_ascii=False)}\n\n"
            time.sleep(0.5)
    return Response(generate(), mimetype="text/event-stream")


import drive_sync


@app.route("/api/drive/state")
def api_drive_state():
    return jsonify(drive_sync.get_state())


@app.route("/api/drive/connect", methods=["POST"])
def api_drive_connect():
    ok, msg = drive_sync.connect_async()
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/drive/disconnect", methods=["POST"])
def api_drive_disconnect():
    ok, msg = drive_sync.disconnect()
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/drive/sync", methods=["POST"])
def api_drive_sync():
    d = request.json or {}
    action = d.get("action", "download")
    include_pts = bool(d.get("include_checkpoints", False))
    ok, msg = drive_sync.run_async(action, include_checkpoints=include_pts)
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/drive/upload_code", methods=["POST"])
def api_drive_upload_code():
    ok, msg = drive_sync.run_async("upload_code")
    return jsonify({"ok": ok, "message": msg})


@app.route("/api/drive/config", methods=["POST"])
def api_drive_config():
    d = request.json or {}
    cfg = drive_sync.load_config()
    if "folder" in d:
        cfg["folder"] = d["folder"]
    drive_sync.save_config(cfg)
    return jsonify({"ok": True, "config": cfg})


if __name__ == "__main__":
    print(f"  {APP_TITLE}\n  http://localhost:{PORT}")
    app.run(host="127.0.0.1", port=PORT, debug=False, threaded=True)

