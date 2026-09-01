# -*- coding: utf-8 -*-
"""Colab リモート制御（ngrok）— ローカルGUIから Colab の学習を操作する窓口。

Colab ノートブックが `ai/mazeward_colab_control.py`（Flask）を ngrok で公開し、
手元の GUI はここが発行する HTTP 要求をその URL へ送ります。

- 設定は `dashboard/colab_remote.json` に保存（url / enabled）
- 依存を増やさないため標準ライブラリのみ（urllib）で通信する

GUI側の dashboard_server.py から使われる:
    if colab_remote.enabled():
        return jsonify(colab_remote.start(request.json))
"""

import json
import os
import time
import urllib.error
import urllib.request

_BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(_BASE, "colab_remote.json")
_TIMEOUT = 6.0

_DEFAULTS = {
    "url": "",          # 例: https://xxxx.ngrok.io
    "token": "",        # Colab 側 API.txt のトークン（認証）
    "enabled": False,   # これを ON にすると学習の開始/停止/状況が Colab に向かう
    "ok": False,        # 最後の接続テストの結果
    "last_check": None,
    "last_error": "",
}


def load_config():
    cfg = dict(_DEFAULTS)
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                stored = json.load(f)
            if isinstance(stored, dict):
                cfg.update(stored)
        except (json.JSONDecodeError, OSError):
            pass
    return cfg


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def base_url():
    return (load_config().get("url") or "").strip().rstrip("/")


def enabled():
    cfg = load_config()
    return bool(cfg.get("enabled") and cfg.get("url"))


def _request(method, path, body=None, timeout=_TIMEOUT):
    url = base_url() + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    token = load_config().get("token", "").strip()
    if token:
        headers["X-API-Token"] = token
    req = urllib.request.Request(
        url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def health():
    """ngrok URL が制御サーバーを指しているか確認する。"""
    if not base_url():
        return {"ok": False, "error": "URL が未設定です"}
    try:
        st = _request("GET", "/healthz", timeout=_TIMEOUT)
        ok = bool(st and st.get("ok"))
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": str(e)}
    return {"ok": ok, "service": (st or {}).get("service") if ok else None}


def check_test():
    """接続テストを実行して結果を設定ファイルに覚えておく。"""
    res = health()
    cfg = load_config()
    cfg["ok"] = bool(res.get("ok"))
    cfg["last_check"] = time.strftime("%Y-%m-%d %H:%M:%S")
    cfg["last_error"] = "" if res.get("ok") else str(res.get("error", ""))
    save_config(cfg)
    res.setdefault("message", "" if res.get("ok") else cfg["last_error"])
    return res


# ── GUI が呼ぶ操作 ──
def start(body):
    return _request("POST", "/colab/start", body)


def stop():
    return _request("POST", "/colab/stop", {})


def status():
    return _request("GET", "/colab/status")


def history():
    d = _request("GET", "/colab/history")
    return d.get("records", []) if isinstance(d, dict) else []