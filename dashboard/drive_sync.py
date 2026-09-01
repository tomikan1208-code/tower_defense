# -*- coding: utf-8 -*-
"""
Google Drive 同期 — Colab で学習したデータをローカルGUIに取り込む
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Colab の学習は Drive の `mazeward_checkpoints` フォルダにデータを残す。
ここではそのフォルダから学習ログ・チェックポイント情報をダウンロードし、
`colab_data/` に置く（ローカルの models/ は上書きしない）。

必要なもの:
  1. pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib
  2. Google Cloud Console で OAuth クライアントID（種類: デスクトップアプリ）を作り、
     JSONを `credentials.json` としてこのフォルダに置く
  3. GUI の「Drive連携」から接続 → ブラウザで許可（初回のみ）

トークンは `drive_token.json` に保存される。
"""

import io
import os
import sys
import json
import time
import threading

_BASE = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(_BASE)

CREDENTIALS_PATH = os.path.join(_BASE, 'credentials.json')
TOKEN_PATH = os.path.join(_BASE, 'drive_token.json')
COLAB_DATA_DIR = os.path.join(_BASE, 'colab_data')
CONFIG_PATH = os.path.join(_BASE, 'drive_config.json')

# Colab 側の保存先（train.py / trainer_pb.py の DRIVE_DATA_DIR と対応）
DEFAULT_FOLDER = 'mazeward_checkpoints'

# 読み書き両方するため drive スコープが必要
SCOPES = ['https://www.googleapis.com/auth/drive']

# 同期対象
SYNC_SUFFIXES = ('_log.json',)
SYNC_NAMES = ('ppo_log.json', 'balance.py')

# ── コード同期 ──
DEFAULT_CODE_FOLDER = 'mazeward_colab_rl_ai'

CODE_LOCAL_ONLY = {'dashboard', 'build', '.gradle', '.git'}
CODE_SECRETS = {'.env', 'credentials.json', 'drive_token.json', 'drive_config.json'}
CODE_EXTRA = ('requirements.txt', 'balance.py')


def code_files():
    """Drive のコードフォルダへ送るファイルの一覧を (ローカルパス, 相対パス) で返す。"""
    files = []
    ai_dir = os.path.join(_PROJECT_ROOT, 'ai')
    if os.path.isdir(ai_dir):
        for root, _, fnames in os.walk(ai_dir):
            if '__pycache__' in root:
                continue
            for name in fnames:
                if name.endswith('.py') or name in CODE_EXTRA:
                    path = os.path.join(root, name)
                    rel = os.path.relpath(path, _PROJECT_ROOT).replace('\\', '/')
                    files.append((path, rel))
    return files


def libs_available():
    try:
        import googleapiclient  # noqa: F401
        import google_auth_oauthlib  # noqa: F401
        return True
    except ImportError:
        return False


def load_config():
    cfg = {'folder': DEFAULT_FOLDER}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding='utf-8') as f:
                stored = json.load(f)
            if isinstance(stored, dict):
                cfg.update(stored)
        except (json.JSONDecodeError, OSError):
            pass
    return cfg


def save_config(cfg):
    with open(CONFIG_PATH, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


# ── 状態（GUIがポーリングして進捗を見る） ──────────────
_state = {
    'connecting': False,
    'busy': False,
    'message': '',
    'error': '',
    'last_sync': None,
    'files': [],
}
_lock = threading.Lock()


def _set(**kwargs):
    with _lock:
        _state.update(kwargs)


def get_state():
    with _lock:
        state = dict(_state)
    cfg = load_config()
    state.update({
        'libs_available': libs_available(),
        'has_credentials': os.path.exists(CREDENTIALS_PATH),
        'credentials_path': CREDENTIALS_PATH,
        'connected': os.path.exists(TOKEN_PATH),
        'folder': cfg.get('folder', DEFAULT_FOLDER),
        'colab_dir': os.path.relpath(COLAB_DATA_DIR, _BASE),
        'local_files': _list_local_colab_files(),
    })
    return state


def _list_local_colab_files():
    if not os.path.isdir(COLAB_DATA_DIR):
        return []
    out = []
    for name in sorted(os.listdir(COLAB_DATA_DIR)):
        path = os.path.join(COLAB_DATA_DIR, name)
        if os.path.isfile(path):
            out.append({
                'name': name,
                'size': os.path.getsize(path),
                'modified': time.strftime('%Y-%m-%d %H:%M:%S',
                                          time.localtime(os.path.getmtime(path))),
            })
    return out


# ── 認証 ──────────────────────────────────────
def _load_credentials():
    """保存済みトークンを読む。失効していればリフレッシュする。"""
    from google.oauth2.credentials import Credentials
    from google.auth.transport.requests import Request

    if not os.path.exists(TOKEN_PATH):
        return None
    creds = Credentials.from_authorized_user_file(TOKEN_PATH, SCOPES)
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
        with open(TOKEN_PATH, 'w', encoding='utf-8') as f:
            f.write(creds.to_json())
    return creds if creds and creds.valid else None


def connect_blocking():
    """ブラウザを開いてOAuth同意を取り、トークンを保存する（初回のみ）。"""
    from google_auth_oauthlib.flow import InstalledAppFlow

    if not os.path.exists(CREDENTIALS_PATH):
        raise RuntimeError(
            'credentials.json が見つかりません。Google Cloud Console で '
            'OAuthクライアントID（デスクトップアプリ）を作成し、ダウンロードした '
            'JSONを credentials.json としてこのフォルダに置いてください。')

    flow = InstalledAppFlow.from_client_secrets_file(CREDENTIALS_PATH, SCOPES)
    creds = flow.run_local_server(port=0, prompt='consent',
                                  authorization_prompt_message='')
    with open(TOKEN_PATH, 'w', encoding='utf-8') as f:
        f.write(creds.to_json())
    return creds


def _explain_auth_error(exc):
    text = str(exc)
    if 'access_denied' in text or 'AccessDenied' in type(exc).__name__:
        return (
            'Google に拒否されました（403 access_denied）。'
            'OAuth同意画面の「テストユーザー」に自分のGoogleアカウントが登録されていません。\n'
            '→ Google Cloud Console でテストユーザーに自分のGmailアドレスを追加してください。')
    if 'invalid_client' in text:
        return ('credentials.json のクライアントIDが無効です。'
                '種類が「デスクトップアプリ」のOAuthクライアントIDか確認してください。')
    return text


def connect_async():
    if _state['connecting']:
        return False, '接続処理を実行中です'

    def run():
        _set(connecting=True, error='', message='ブラウザで許可を待っています...')
        try:
            connect_blocking()
            _set(message='接続しました')
        except Exception as e:
            _set(error=_explain_auth_error(e), message='')
        finally:
            _set(connecting=False)

    threading.Thread(target=run, daemon=True).start()
    return True, 'ブラウザで許可してください'


def disconnect():
    if os.path.exists(TOKEN_PATH):
        os.remove(TOKEN_PATH)
    _set(message='接続を解除しました', error='')
    return True, '接続を解除しました'


def _service():
    from googleapiclient.discovery import build
    creds = _load_credentials()
    if not creds:
        raise RuntimeError('Drive に接続されていません。先に「接続」してください。')
    return build('drive', 'v3', credentials=creds, cache_discovery=False)


def _escape(name):
    return name.replace('\\', '\\\\').replace("'", "\\'")


def find_folder(service, name):
    query = ("mimeType='application/vnd.google-apps.folder' and trashed=false "
             f"and name='{_escape(name)}'")
    res = service.files().list(q=query, fields='files(id,name)', pageSize=10).execute()
    files = res.get('files', [])
    return files[0]['id'] if files else None


def list_folder(service, folder_id):
    files, token = [], None
    while True:
        res = service.files().list(
            q=f"'{folder_id}' in parents and trashed=false",
            fields='nextPageToken, files(id,name,size,modifiedTime,mimeType)',
            pageSize=200, pageToken=token).execute()
        files += res.get('files', [])
        token = res.get('nextPageToken')
        if not token:
            break
    return files


def _wanted(name):
    return name in SYNC_NAMES or name.endswith(SYNC_SUFFIXES)


def download_blocking(include_checkpoints=False):
    from googleapiclient.http import MediaIoBaseDownload

    service = _service()
    folder = load_config().get('folder', DEFAULT_FOLDER)
    folder_id = find_folder(service, folder)
    if not folder_id:
        raise RuntimeError(f'Drive に「{folder}」フォルダが見つかりません。'
                           'Colabで一度学習を実行すると作成されます。')

    os.makedirs(COLAB_DATA_DIR, exist_ok=True)
    got = []
    for item in list_folder(service, folder_id):
        name = item['name']
        if item.get('mimeType') == 'application/vnd.google-apps.folder':
            continue
        if not _wanted(name) and not (include_checkpoints and name.endswith('.pt')):
            continue
        dest = os.path.join(COLAB_DATA_DIR, name)
        request = service.files().get_media(fileId=item['id'])
        buf = io.BytesIO()
        downloader = MediaIoBaseDownload(buf, request)
        done = False
        while not done:
            _, done = downloader.next_chunk()
        with open(dest, 'wb') as f:
            f.write(buf.getvalue())
        got.append({'name': name, 'size': len(buf.getvalue()),
                    'modified': item.get('modifiedTime', '')})
        _set(message=f'{name} を取得しました')
    return got


def upload_blocking(paths):
    from googleapiclient.http import MediaFileUpload

    service = _service()
    folder = load_config().get('folder', DEFAULT_FOLDER)
    folder_id = find_folder(service, folder)
    if not folder_id:
        meta = {'name': folder, 'mimeType': 'application/vnd.google-apps.folder'}
        folder_id = service.files().create(body=meta, fields='id').execute()['id']

    existing = {f['name']: f['id'] for f in list_folder(service, folder_id)}
    sent = []
    for path in paths:
        if not os.path.exists(path):
            continue
        name = os.path.basename(path)
        media = MediaFileUpload(path, resumable=False)
        if name in existing:
            service.files().update(fileId=existing[name], media_body=media).execute()
        else:
            service.files().create(body={'name': name, 'parents': [folder_id]},
                                   media_body=media, fields='id').execute()
        sent.append(name)
        _set(message=f'{name} を送信しました')
    return sent


def run_async(action, **kwargs):
    if _state['busy']:
        return False, '同期処理を実行中です'

    def run():
        _set(busy=True, error='', message='開始しています...')
        try:
            if action == 'download':
                files = download_blocking(kwargs.get('include_checkpoints', False))
                _set(files=files, message=f'{len(files)} 件を取得しました',
                     last_sync=time.strftime('%Y-%m-%d %H:%M:%S'))
            else:
                sent = upload_blocking(kwargs.get('paths', []))
                _set(message=f'{len(sent)} 件を送信しました',
                     last_sync=time.strftime('%Y-%m-%d %H:%M:%S'))
        except Exception as e:
            _set(error=str(e), message='')
        finally:
            _set(busy=False)

    threading.Thread(target=run, daemon=True).start()
    return True, '開始しました'
