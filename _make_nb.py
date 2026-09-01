# -*- coding: utf-8 -*-
"""MAZEWARD Colab ノートブック生成ビルダー。"""
import json
import io

cells = []


def md(src):
    cells.append({"cell_type": "markdown", "metadata": {}, "source": src.splitlines(keepends=True)})


def code(src):
    cells.append({"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": src.splitlines(keepends=True)})


md(r'''# MAZEWARD Colab 制御サーバー（ngrok）

このノートブックは Colab の GPU で学習（train.py）を回し、手元のコントロールパネル（GUI）から開始・停止・状況を見られるようにするための「制御サーバー」を立ち上げます。



**上から順に実行するだけ**です。



| セル | 内容 |

|---|---|

| ①セットアップ | Driveからコードを取得し更新、APIトークン（ai/API.txt）を発行/再利用 |

| ②制御サーバー起動 | Flask（ai/mazeward_colab_control.py）を 5558 で起動 |

| ③ngrok 公開 | 外から届く https://xxxx.ngrok.io を発行 |

| ④接続確認 | healthz を叩いて疎通確認 |



最後のセルが **PC 側 GUI に貼る値（ngrok URL と API トークン）**を出します。





> ⚠️ ngrok 無料版は authtoken が要ります。左の鍵アイコン（シークレット）に `NGROK_AUTHTOKEN` として登録しておいてください。





> ランタイムが切れるとトンネルは消えますが、学習自体は Drive（`mazeward_checkpoints`）に保存されるので続きから再開できます。
''')


code(r'''#@title ① セットアップ（毎回最初に実行）
import os, shutil, secrets
from google.colab import drive

DRIVE_FOLDER = "mazeward_colab_rl_ai"      # Drive 上のコードフォルダ名
COLAB_DIR   = "/content/mazeward_colab_rl_ai"

drive.mount("/content/drive", force_remount=True)
src = f"/content/drive/MyDrive/{DRIVE_FOLDER}"
assert os.path.exists(src), f"Drive に {src} がありません。あらかじめ GUI の「📤 コードを Colab へ送信」を実行してください"

# 最新コードへ更新（毎回コピー）
if os.path.exists(COLAB_DIR:
    shutil.rmtree(COLAB_DIR)
shutil.copytree(src, COLAB_DIR, ignore=shutil.ignore_patterns("models", "replays", "__pycache__", "*.ipynb", "API.txt"))
print("コードを更新しました:", COLAB_DIR)

# API トークン: 初回のみ発行し Drive に保存（次回から同じものを使い回す）
api_src = os.path.join(src, "ai", "API.txt")
api_dst = os.path.join(COLAB_DIR, "ai", "API.txt")
if os.path.exists(api_src:
    token = open(api_src, encoding="utf-8").read().strip()
else:
    token = secrets.token_hex(16)
    os.makedirs(os.path.dirname(api_src], exist_ok=True)
    with open(api_src, "w", encoding="utf-8") as f:
        f.write(token + "\n")
with open(api_dst, "w", encoding="utf-8") as f:
    f.write(token + "\n")
print("API トークン:", token[:8] + "…")
''')

code(r'''#@title ② 制御サーバーを起動（Flask）
import subprocess, os, sys, time
COLAB_DIR = "/content/mazeward_colab_rl_ai"
log = open("/content/mazeward_control.log", "w", encoding="utf-8")
proc = subprocess.Popen(
    [sys.executable, f"{COLAB_DIR}/ai/mazeward_colab_control.py", "--port", "5558"],
    cwd=COLAB_DIR, stdout=log, stderr=subprocess.STDOUT,
)
time.sleep(3)
print("起動中… ログ: /content/mazeward_control.log（pid", proc.pid, "）")
''')

code(r'''#@title ③ ngrok で公開（https://xxxx.ngrok.io）
import os, time, getpass, subprocess
try:
    from pyngrok import ngrok
except ImportError:
    subprocess.run(["pip", "install", "-q", "pyngrok"], check=True)
    from pyngrok import ngrok

auth = os.environ.get("NGROK_AUTHTOKEN") or ""
if not auth:
    auth = getpass.getpass("ngrok authtoken（未設定。https://dashboard.ngrok.com で取得）: ")
if auth:
    ngrok.set_auth_token(auth)

tunnel = ngrok.connect(5558)
public = tunnel.public_url if tunnel else None
print("=" * 64)
print("PC側GUIに貼る値（☁️ Colab・Drive連携 タブ → Colab リモート制御）")
print("  ngrok URL :", public or "(取得失敗)")
token = open("/content/mazeward_colab_rl_ai/ai/API.txt", encoding="utf-8").read().strip()
print("  API トークン:", token)
print("=" * 64)
''')

code(r'''#@title ④ 接続確認
import requests
public = globals().get("public") or ""
token = open("/content/mazeward_colab_rl_ai/ai/API.txt", encoding="utf-8").read().strip()
if not public:
    print("先に③ を実行してください")
else:
    r = requests.get(public + "/healthz", headers={"X-API-Token": token}, timeout=10)
    print("healthz:", r.status_code, r.text[:200])
    if r.status_code == 200:
        print("OK — PC側 GUI の ☁️ タブ で URL・トークンを保存 → 📡接続テスト → ON にしてください")
''')

md(r'''## GUI側の手順
1. MAZEWARDコントロールパネル.vbs を起動します。
2. 「☁️ Colab・Drive連携」タブの下にある「🎛 Colab リモート制御」カードを開きます。
3. ④ で出た ngrok URL と API トークンを貼り、「接続設定を保存」。
4.「📡 接続テスト」→ ✅ 接続OK になれば「GUI の 開始/停止 を Colab へ向ける」を ON にします。
5. 「▶ 学習開始」で Colab の学習が始まり、この GUI で状況・グラフが見られます。

> ローカル学習に戻すときは、チェックを外して「接続設定を保存」してください。
''')

nb = {"cells": cells, "metadata": {"colab": {"provenance": []}, "kernelspec": {"display_name": "Python 3", "name": "python3"}, "language_info": {"name": "python"}}, "nbformat": 4, "nbformat_minor": 0}
io.open(r"ai\mazeward_colab_notebook.ipynb", "w", encoding="utf-8").write(json.dumps(nb, ensure_ascii=False, indent=1))
print("written cells:", len(cells))