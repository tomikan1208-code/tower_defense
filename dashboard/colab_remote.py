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
    headers = {
        "Content-Type": "application/json",
        # ngrok の無料プランは、素の要求に「アクセスしようとしています」という
        # HTML の警告ページを返す。このヘッダが無いと JSON ではなく HTML が
        # 返り、json.loads が「Expecting value: line 1 column 1」で落ちる。
        # 接続テストも学習開始も、これが原因で全部失敗していた。
        "ngrok-skip-browser-warning": "true",
        "User-Agent": "mazeward-dashboard/1",
    }
    token = load_config().get("token", "").strip()
    if token:
        headers["X-API-Token"] = token
    req = urllib.request.Request(
        url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        head = " ".join(raw.lstrip()[:80].split())
        raise RuntimeError(
            "JSON が返ってきません（ngrok の警告ページか、URL が制御サーバーを"
            f"指していない可能性）: {head}") from None


def describe_error(e) -> str:
    """例外を、利用者が次に何をすればよいか分かる文言に変える。"""
    if isinstance(e, urllib.error.HTTPError):
        if e.code == 401:
            return "認証に失敗しました（API トークンが Colab 側と一致していません）"
        return f"HTTP {e.code} {e.reason}"
    if isinstance(e, urllib.error.URLError):
        return f"接続できません（URL・ngrok の起動を確認）: {e.reason}"
    if isinstance(e, TimeoutError):
        return "応答がありません（タイムアウト）"
    return str(e)


def safe(fn, *args, **kwargs):
    """GUI から呼ぶ操作は **例外を投げない**。

    投げると Flask が 500 を返し、画面側は JSON を読めずに黙って失敗する。
    「開始ボタンを押しても何も起きない」の原因がこれだった。
    """
    try:
        result = fn(*args, **kwargs)
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "message": "Colab: " + describe_error(e),
                "error": describe_error(e)}
    if isinstance(result, dict):
        result.setdefault("ok", True)
        return result
    return {"ok": True, "result": result}


def health():
    """ngrok URL が制御サーバーを指しているか確認する。"""
    if not base_url():
        return {"ok": False, "error": "URL が未設定です"}
    try:
        st = _request("GET", "/healthz", timeout=_TIMEOUT)
        ok = bool(st and st.get("ok"))
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": describe_error(e)}
    return {"ok": ok, "service": (st or {}).get("service") if ok else None,
            "error": "" if ok else "制御サーバーが ok を返しません"}


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
    return _request("POST", "/colab/start", body, timeout=30.0)


def stop():
    return _request("POST", "/colab/stop", {}, timeout=20.0)


def status():
    return _request("GET", "/colab/status")


def history():
    d = _request("GET", "/colab/history")
    return d.get("records", []) if isinstance(d, dict) else []

def state():
    """ヘッダー表示用のまとめ。**「向き先」と「実際に繋がっているか」は別物**
    なので両方返す（有効にしただけで繋がったと誤解しないように）。"""
    cfg = load_config()
    return {
        "url": cfg.get("url", ""),
        "enabled": bool(cfg.get("enabled") and cfg.get("url")),
        "ok": bool(cfg.get("ok")),
        "last_check": cfg.get("last_check"),
        "last_error": cfg.get("last_error", ""),
    }
