# -*- coding: utf-8 -*-
"""ローカルと Colab で **同じ学習を続ける**ための受け渡し。

いままでは両者が別々に走っていた。ローカルは ``ai/models/`` にだけ書き、
Colab は Drive の ``mazeward_checkpoints/`` にだけ書くので、世代番号も重みも
食い違っていく。同じゲームを 2 つの独立した学習が別々に覚えている状態だった。

ここでやること
--------------
**Drive を 1 本の正本にして、バトンを渡す。**

- ``pull()``  … Drive の続きをローカルへ持ってくる（Colab で回した続きをローカルで）
- ``push()``  … ローカルの続きを Drive へ置く（ローカルの続きを Colab で）

``train.py`` はチェックポイントの ``gen`` から再開するので、受け渡した時点で
**世代番号がそのまま続く**。別々の 2 本ではなく 1 本の学習になる。

安全のために
------------
どちらも **相手を上書きする**操作なので、

- 受け取り側のほうが進んでいる場合は既定で拒否する（``confirm`` が要る）
- ログは捨てずに **世代番号で突き合わせて統合**する
  （同じ世代なら記録が充実しているほうを採る）
- 上書き前にバックアップを取る

同時に両方で学習しないこと。世代番号が衝突して、どちらの記録か分からなくなる。
"""

from __future__ import annotations

import io
import json
import os
import shutil
import time
from typing import Dict, List, Optional, Tuple

import drive_sync

_BASE = os.path.dirname(os.path.abspath(__file__))
LOCAL_MODELS = os.path.join(os.path.dirname(_BASE), "ai", "models")
LOG_NAME = "ppo_log.json"
CKPT_NAME = "ppo_latest.pt"
BACKUP_DIR = os.path.join(LOCAL_MODELS, "_backup")


# ════════════════════════════════════════════════════════════════════
# ログの読み書きと統合
# ════════════════════════════════════════════════════════════════════
def read_log(path: str) -> List[dict]:
    if not os.path.exists(path):
        return []
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return [r for r in data if isinstance(r, dict) and "episode" in r]
    except (json.JSONDecodeError, OSError, UnicodeDecodeError):
        return []


def merge_logs(a: List[dict], b: List[dict]) -> List[dict]:
    """世代番号で突き合わせて統合する。

    同じ世代が両方にある場合は **記録が充実しているほう**（キーが多いほう）を
    採る。評価は数世代ごとにしか走らないので、片方にしか ``win_vs_best`` が
    無い、といったことが普通に起きる。
    """
    merged: Dict[int, dict] = {}
    for rec in list(a) + list(b):
        ep = rec.get("episode")
        if not isinstance(ep, int):
            continue
        cur = merged.get(ep)
        if cur is None or len(rec) > len(cur):
            merged[ep] = rec
    return [merged[k] for k in sorted(merged)]


def _last_episode(records: List[dict]) -> int:
    return max((r["episode"] for r in records if isinstance(r.get("episode"), int)),
               default=0)


def _last_timestamp(records: List[dict]) -> str:
    for rec in reversed(records):
        if rec.get("timestamp"):
            return str(rec["timestamp"])
    return ""


# ════════════════════════════════════════════════════════════════════
# Drive 側の読み書き
# ════════════════════════════════════════════════════════════════════
def _folder(service):
    folder = drive_sync.load_config().get("folder", drive_sync.DEFAULT_FOLDER)
    fid = drive_sync.find_folder(service, folder)
    if not fid:
        raise RuntimeError(
            f"Drive に「{folder}」フォルダがありません。"
            "Colab で一度学習を回すと作られます。")
    return fid, folder


def _drive_files(service, folder_id) -> Dict[str, dict]:
    return {f["name"]: f for f in drive_sync.list_folder(service, folder_id)
            if f.get("mimeType") != "application/vnd.google-apps.folder"}


def _download(service, file_id) -> bytes:
    from googleapiclient.http import MediaIoBaseDownload
    buf = io.BytesIO()
    downloader = MediaIoBaseDownload(buf, service.files().get_media(fileId=file_id))
    done = False
    while not done:
        _, done = downloader.next_chunk()
    return buf.getvalue()


def _upload(service, folder_id, existing: Dict[str, dict], path: str, name: str):
    from googleapiclient.http import MediaFileUpload
    media = MediaFileUpload(path, resumable=False)
    if name in existing:
        service.files().update(fileId=existing[name]["id"], media_body=media).execute()
    else:
        service.files().create(body={"name": name, "parents": [folder_id]},
                               media_body=media, fields="id").execute()


# ════════════════════════════════════════════════════════════════════
# 状態
# ════════════════════════════════════════════════════════════════════
def state() -> dict:
    """ローカルと Drive のどちらが進んでいるかを返す。"""
    local_log = read_log(os.path.join(LOCAL_MODELS, LOG_NAME))
    out = {
        "local": {
            "generation": _last_episode(local_log),
            "records": len(local_log),
            "updated": _last_timestamp(local_log),
            "checkpoint": os.path.exists(os.path.join(LOCAL_MODELS, CKPT_NAME)),
        },
        "drive": {"generation": 0, "records": 0, "updated": "",
                  "checkpoint": False, "available": False},
        "error": "",
    }
    if not drive_sync.libs_available():
        out["error"] = "Google API のライブラリが入っていません"
        return out
    try:
        service = drive_sync._service()
        folder_id, folder = _folder(service)
        files = _drive_files(service, folder_id)
        out["drive"]["folder"] = folder
        out["drive"]["checkpoint"] = CKPT_NAME in files
        if LOG_NAME in files:
            data = json.loads(_download(service, files[LOG_NAME]["id"]).decode("utf-8"))
            recs = [r for r in data if isinstance(r, dict) and "episode" in r]
            out["drive"]["generation"] = _last_episode(recs)
            out["drive"]["records"] = len(recs)
            out["drive"]["updated"] = _last_timestamp(recs)
        out["drive"]["available"] = True
    except Exception as e:  # noqa: BLE001
        out["error"] = str(e)
    return out


def _advice(st: dict) -> str:
    lg, dg = st["local"]["generation"], st["drive"]["generation"]
    if not st["drive"]["available"]:
        return "Drive を確認できません"
    if lg == dg == 0:
        return "どちらにも記録がありません"
    if dg > lg:
        return f"Colab が {dg - lg} 世代進んでいます → 「Colab の続きをローカルで」"
    if lg > dg:
        return f"ローカルが {lg - dg} 世代進んでいます → 「ローカルの続きを Colab で」"
    return "同じ世代です。どちらから再開しても続きになります"


# ════════════════════════════════════════════════════════════════════
# 受け渡し
# ════════════════════════════════════════════════════════════════════
def _backup(path: str) -> Optional[str]:
    if not os.path.exists(path):
        return None
    os.makedirs(BACKUP_DIR, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(BACKUP_DIR, f"{stamp}-{os.path.basename(path)}")
    shutil.copy2(path, dest)
    return dest


def pull(confirm: bool = False) -> Tuple[bool, str]:
    """Drive（Colab）の続きをローカルへ持ってくる。"""
    st = state()
    if not st["drive"]["available"]:
        return False, st["error"] or "Drive に接続できません"
    if st["drive"]["generation"] == 0 and not st["drive"]["checkpoint"]:
        return False, "Drive に学習データがありません"
    if st["local"]["generation"] > st["drive"]["generation"] and not confirm:
        return False, (f"ローカルのほうが進んでいます"
                       f"（ローカル {st['local']['generation']} 世代 / "
                       f"Drive {st['drive']['generation']} 世代）。"
                       "上書きしてよければ確認のうえ実行してください")

    service = drive_sync._service()
    folder_id, _ = _folder(service)
    files = _drive_files(service, folder_id)
    os.makedirs(LOCAL_MODELS, exist_ok=True)
    done = []

    # ログは統合する（どちらの記録も捨てない）
    if LOG_NAME in files:
        local_path = os.path.join(LOCAL_MODELS, LOG_NAME)
        _backup(local_path)
        remote = json.loads(_download(service, files[LOG_NAME]["id"]).decode("utf-8"))
        merged = merge_logs(read_log(local_path), remote)
        with open(local_path, "w", encoding="utf-8") as f:
            json.dump(merged, f, indent=2, ensure_ascii=False)
        done.append(f"ログを統合（{len(merged)} 世代）")

    # チェックポイントは Drive のものを正本にする（world: 続きから回すため）
    for name in (CKPT_NAME,):
        if name in files:
            local_path = os.path.join(LOCAL_MODELS, name)
            _backup(local_path)
            with open(local_path, "wb") as f:
                f.write(_download(service, files[name]["id"]))
            done.append(name)

    if not done:
        return False, "取り込む対象がありませんでした"
    return True, "取り込みました: " + " / ".join(done)


def push(confirm: bool = False) -> Tuple[bool, str]:
    """ローカルの続きを Drive へ置く（Colab がここから再開する）。"""
    st = state()
    if not st["drive"]["available"]:
        return False, st["error"] or "Drive に接続できません"
    local_log_path = os.path.join(LOCAL_MODELS, LOG_NAME)
    local_ckpt = os.path.join(LOCAL_MODELS, CKPT_NAME)
    if not os.path.exists(local_ckpt) and not os.path.exists(local_log_path):
        return False, "ローカルに学習データがありません"
    if st["drive"]["generation"] > st["local"]["generation"] and not confirm:
        return False, (f"Drive のほうが進んでいます"
                       f"（Drive {st['drive']['generation']} 世代 / "
                       f"ローカル {st['local']['generation']} 世代）。"
                       "上書きすると Colab の記録が消えます")

    service = drive_sync._service()
    folder_id, _ = _folder(service)
    files = _drive_files(service, folder_id)
    done = []

    # ログは Drive 側と統合してから上げる
    if os.path.exists(local_log_path):
        remote = []
        if LOG_NAME in files:
            try:
                remote = json.loads(
                    _download(service, files[LOG_NAME]["id"]).decode("utf-8"))
            except Exception:  # noqa: BLE001
                remote = []
        merged = merge_logs(read_log(local_log_path), remote)
        tmp = local_log_path + ".merged"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(merged, f, indent=2, ensure_ascii=False)
        _upload(service, folder_id, files, tmp, LOG_NAME)
        shutil.move(tmp, local_log_path)
        done.append(f"ログを統合（{len(merged)} 世代）")

    if os.path.exists(local_ckpt):
        _upload(service, folder_id, files, local_ckpt, CKPT_NAME)
        done.append(CKPT_NAME)

    return True, "送りました: " + " / ".join(done)


def full_state() -> dict:
    st = state()
    st["advice"] = _advice(st)
    return st
