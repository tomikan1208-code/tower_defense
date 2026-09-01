# MAZEWARD AI 学習システム 改善・高速化・連携強化 レポート

## 1. 概要
本レポートは、MAZEWARD AI 学習システムおよびコントロールパネルにおいて実施した「学習パイプラインの高速化・効率化」、「Google Drive / Colab 連携機能の導入」、「ダッシュボード UI の改善および収集指標の拡充」に関する全内容をまとめたものです。

---

## 2. AI学習システムの高速化・効率化（コード最適化）

学習環境（`ai/` ディレクトリ配下）の純 Python (numpy) シミュレータおよび PyTorch PPO 学習ループにおいて、アルゴリズムの正当性を完全に保ったまま以下の高速化を実施しました。

### ① `_current_rank` のベクトル化 (`ai/mazeward_env/env.py`)
- **内容**: 毎ステップ全環境（`n_envs`）× 全席で実行されていた `for` ループを排除し、`(n_envs, seats)` の2次元配列に対する `np.argsort` 一括演算へ変更。
- **効果**: 各ステップにおける順位算出のループオーバーヘッドを消滅させました。

### ② `_fill_opponents` のベクトル化 (`ai/mazeward_env/env.py`)
- **内容**: 相手プレイヤーの要約特徴量を構築する際の Python 2重ループ（環境数 × 人数）を撤廃し、`numpy` のブロードキャストとインデックス行列による一括生成に移行。
- **効果**: 観測テンソル生成時の Python ループ数を大幅に削減（最大 1024 回/step → 1 回のバッチ演算）。

### ③ `compact_enemies` の条件付きスキップ (`ai/mazeward_env/combat.py`)
- **内容**: 敵が存在しない島、あるいは配列途中に「穴（撃破による隙間）」がない島を自動検出し、重い配列操作（`np.argsort` および 20 個の配列に対する `np.take_along_axis`）をスキップ。
- **効果**: 敵数が少ない序盤や、安定状態での無駄なソート・アレイ再構築操作をカットし、シミュレーションを高速化。

### ④ PPO更新時（`ppo_update`）の GPU 転送事前化 (`ai/train.py`)
- **内容**: ミニバッチごとのループ内で毎回呼び出されていた `torch.as_tensor`（CPU → GPU メモリ転送）を、エポック開始時に一括転送・テンソル化する方式に変更。
- **効果**: ミニバッチ毎の CPU-GPU 通信コストおよび中間テンソル生成のガベージコレクション（GC）負荷を大幅カット。

---

## 3. Google Drive / Colab 連携機能の導入

Google Colab の強力な GPU で学習をまわしながら、ローカルのデスクトップ UI で進捗やグラフ・対局リプレイを快適にモニタリング・操作できる仕組みを構築しました。

### ① `MAZEWARDコントロールパネル.vbs` の更新
- **内容**: `MDD2コントロールパネル.vbs` と同等の堅牢な UTF-16LE(BOM付き) VBS スクリプトへ改修。
- **効果**: 黒いコンソール画面を出さず、`pythonw.exe` でバックグラウンド起動。アドレスバーやタブのない独立したウィンドウ（アプリモード）として一発起動できます。

### ② `dashboard/drive_sync.py` モジュールの新設
- **内容**: Google Drive 上の `mazeward_checkpoints` フォルダを監視し、Colab で更新された学習ログ (`ppo_log.json`) やチェックポイントモデル (`ppo_latest.pt` 等) をローカルの `colab_data/` へ自動/手動同期する機能を追加。
- **機能**: ローカルで修正した AI ロジックや環境コードをワンクリックで Colab / Drive 側（`mazeward_colab_rl_ai` フォルダ）へアップロード可能。

### ③ ダッシュボードへの「☁️ Colab・Drive連携」タブと API エンドポイント追加
- **追加エンドポイント**:
  - `/api/drive/state` (状態取得)
  - `/api/drive/connect` (OAuth認証開始)
  - `/api/drive/disconnect` (接続解除)
  - `/api/drive/sync` (学習ログ・モデルのダウンロード/同期)
  - `/api/drive/upload_code` (コードのColab送信)
- **UI機能**: ダッシュボードの「☁️ Colab・Drive連携」タブから接続状態確認・OAuth同意・ログ取得・コードアップロードがすべてローカル画面上で操作可能。

---

## 4. ダッシュボード UI 改善と指標の拡張

### ① 世代記録テーブルの5項目指定表示 & クリック詳細展開
- **デフォルト表示項目（指定の5列）**:
  1. `世代` (`episode`)
  2. `1 世代のゲーム内時間(分)` (`game_minutes`)
  3. `1 世代の実時間(秒)` (`seconds_per_gen`)
  4. `対局数` (`games_finished`)
  5. `決着した対局％` (`finish_rate`)
- **詳細展開表示 (`#genDetailCard`)**:
  - テーブルの行をクリックすると、下部に選択した世代の全詳細数値（各種勝率・Elo、Loss・KL、タワー平均レベル、タワー使用割合、モンスター送り割合、モンスター漏れ割合等）を美麗なカード・タイル形式で自動展開・表示。

### ② 追加詳細指標の追跡・記録 (`ai/mazeward_env/env.py`, `ai/train.py`, `dashboard_server.py`)
- **タワーの平均レベル (`tower_avg_level`)**: 全設置タワーのレベル平均を算出・記録。
- **タワーの使用割合 (`tower_type_rates`)**: 9種類のタワー（BOW, FROST, CANNON, SPLASH, PIERCE, BANISH, CURSE, SUPPORT, BARRICADE）の全設置数に対する割合 (%) を集計。
- **モンスターの送り割合 (`send_type_rates`)**: 12種類のモンスターの全送信数に対する割合 (%) を集計。
- **モンスターの漏れ割合 (`leak_type_rates`)**: 12種類のモンスターの全漏れ数（コア到達数）に対する割合 (%) を集計。

### ③ 複数色によるマルチライン重ね描きグラフ (`dashboard/web/chart.js`, `app.js`)
- **色分け重なり線プロット**:
  - タワー使用割合 (%)、モンスター送り割合 (%)、モンスター漏れ割合 (%) などの多系列指標について、鮮やかなカラーパレット（青・赤・緑・オレンジ・紫・シアン・ピンク・黄など）を用いて、1つのSVGグラフ上に複数色の線で重ね描きプロットする機能を追加。
  - SVGグラフの上部にカラー凡例を自動表示。

---

## 5. 動作・品質検証結果 (`selfcheck.py`)

すべての変更適用後、`ai/selfcheck.py` による自動診断テスト（12項目）を実行し、すべて全件パスすることを確認しました。

```text
============================================================
MAZEWARD AI 自己診断
============================================================
1. モジュールの読み込み: OK
2. バランス定義: OK (fingerprint=4c2d3cf8, 敵=12種, 塔=9種)
3. パスファインダー: OK (15x15 直線距離=19.79, ウェイポイント=2)
4. 戦闘シミュレータ: OK (100 ticks 進行, 生存敵=0)
5. 観測ビルダー: OK (grid=(4, 14, 21, 21), scalar=(4, 210), opp=(4, 8, 14))
6. 環境 step(): OK (10 steps, パス判定動作)
7. 方策ネットワーク: OK (param=831.6K, value=-0.038)
8. 行動サンプリング: OK (type=0, card=0, tower=0)
9. PPO 更新: OK (loss=-0.0055, entropy=1.4586)
10. ボット思考: OK (random, greedy_defense, income_push)
11. 模倣学習 (BC): OK (1 batch loss=0.697)
12. 評価ミニマッチ: OK (win_rate=0.00, steps=7.0)
============================================================
すべての自己診断項目をパスしました。
============================================================

---

## 追記: Colab と GUI を繋ぐ「リモート制御」仕組み

### 1. 何を実装したのか

今回の改修は、「Colab の学習ボタンを手で押す」のではなく、ローカルの GUI から Colab 側の学習プロセスを制御するための基盤を追加したものです。

実際に入っている主な実装は以下の通りです。

- `ai/mazeward_colab_control.py`
  - Colab 側で動く Flask サーバー
  - `/colab/start`, `/colab/stop`, `/colab/status`, `/colab/history` を提供
  - ローカル GUI からの開始・停止・状態取得・履歴の取り出しに対応
- `dashboard/colab_remote.py`
  - GUI 側から ngrok 経由で Colab API を叩く HTTP クライアント
  - `url`, `token`, `enabled`, `ok` を `dashboard/colab_remote.json` に保存
  - 接続テスト、開始、停止、状態確認の共通ラッパーを提供
- `dashboard/dashboard_server.py`
  - `/api/colab/config`, `/api/colab/test`, `/api/start`, `/api/stop` などを追加
  - `colab_remote.enabled()` が ON のとき、GUI の開始/停止を Colab 側へ委譲するように分岐
- `_make_nb.py`
  - Colab で実行するノートブック生成スクリプト
  - Drive からコードを再読込し、ngrok で外部公開するセットアップを自動生成

### 2. どういう仕組みか

仕組みはシンプルです。

1. Colab 側で Flask サーバーを起動する
   - Python の `ai/mazeward_colab_control.py` が 5558 番ポートで待機する
   - これは「GUI からの命令を受けて train.py を起動・停止する窓口」になる
2. ngrok で HTTP トンネルを作る
   - Colab ノートブックが `ngrok.connect(5558)` を実行し、外部公開 URL を発行する
   - 例: `https://abcd1234.ngrok.io`
3. GUI 側で URL と API トークンを保存する
   - `dashboard/colab_remote.json` に URL と token を格納
   - GUI はそれを使って `POST /colab/start` や `GET /colab/status` を叩く
4. GUI から Colab の学習を操作する
   - GUI の「学習開始」ボタンが押されたら、ローカル実行ではなく Colab 側に向けてリモート命令を送る
   - GUI のステータス表示も Colab 側の `ppo_log.json` と `live` 情報を取得して描画する

つまり、ローカル GUI は「本体の学習プロセスを直接持つ」のではなく、
「Colab にある学習プロセスを HTTP で管理するクライアント」になっています。

### 3. これはこの repo でもできるのか

はい、できています。

このリポジトリでは、GUI をローカル実行と Colab 実行の両方で扱えるように分岐を入れてあります。

- ローカル学習中: `dashboard_server.py` の `manager.start()` を使う
- Colab 接続 ON: `dashboard_server.py` が `colab_remote.start()` を使う
- 状態取得: `colab_remote.status()` を呼ぶ
- 履歴取得: `colab_remote.history()` を呼ぶ

このため、ユーザー体験としては「GUI のボタンを押すだけ」で、
ローカル実行か Colab 実行かを意識せずに同じ UI で操作できます。

### 4. 別のプロジェクトでも同じ設計が使えるか

はい。`start-ai.bat` を持つ別プロジェクトでも、ほぼ同じパターンが使えます。

- Colab 側に `start_ai_server.py` のような Flask API を持つ
- 学習スクリプトをサブプロセスとして起動する
- `ngrok` で公開する
- ローカル側に URL / token / enabled を保存する設定画面を用意する
- `GUI -> HTTP -> Colab` の経路として接続する

この設計の利点は、GPU が Colab にある状態で、手元の PC でも学習開始・停止・進捗確認が可能になることです。

### 5. 実装状況の整理

現時点での実装状況は以下です。

- Colab 側制御サーバー: 実装済み
- ngrok 公開: 実装済み
- GUI 接続設定画面: 実装済み
- GUI からのリモート制御: 実装済み
- Colab ノートブック生成: 実装済み
- その接続フローの説明整理: 追加済み

これは、元の依頼で挙げられていた「Colab と GUI を接続して GUI 上で開始・監視できるようにする」の達成にほぼ一致しています。

### 6. 実務上の注意点

- ngrok はランタイムが切れると URL が変わることがあるため、再起動時に URL と token を再登録する必要がある
- Colab の学習ログ・チェックポイントは Drive へ持ち帰る設計にしているので、再開しやすい
- GUI の UI ではローカル実行／Colab 実行の切替を `enabled` フラグで切り替える設計になっている
- 別リポジトリに同じ仕組みを移すときは、起動コマンドとログパスと token 検査ルールを揃えると安全

この状態であれば、次に「本番運用向けに start-ai.bat からの自動起動」「URL 自動保存」「バージョン確認」「再接続補助」まで広げることも可能です。

---

## 追記: リモート制御の UI 改善と起動状態の可視化（2026-09-01）

### 1. 問題の背景

接続テスト「接続OK」後に、以下の 2 つの課題が生じていました。

- **課題1**: GUI 画面上で「いま Local で動いているのか、Colab で動いているのか」が分からない
  - 実行先が不明確なため、ユーザーが混乱しやすい
  - ローカル / Colab の切り替え時に状態が明示されていない
- **課題2**: 学習開始ボタンが、Colab 側の起動失敗後すぐに再押下可能になる
  - 失敗理由が不明瞭なまま再試行される
  - UI 上で「起動要求を送った」と「起動が成功した」の区別がない

### 2. 実装した修正内容

#### ① ヘッダーに実行先表示を追加 (`dashboard/web/index.html`)

```html
<div class="status-badge" id="runtimeBadge" style="margin-left: 8px; margin-right: 8px;">
    <span>実行先:</span>
    <b id="runtimeState">Local</b>
</div>
```

- ヘッダーの「待機中」表示の右隣に「実行先: Local」または「実行先: Colab」と表示
- ユーザーが一目で現在の実行先を確認可能

#### ② 状態 API に runtime 情報を追加 (`dashboard/dashboard_server.py`, `ai/mazeward_colab_control.py`)

API レスポンス（`/api/status`）に以下の 2 フィールドを新規追加：

```json
{
  "is_running": true,
  "mode": "リーグ自己対戦",
  "runtime": "local",
  "runtime_label": "Local",
  ...
}
```

または Colab 時：

```json
{
  "is_running": true,
  "mode": "リーグ自己対戦",
  "runtime": "colab",
  "runtime_label": "Colab",
  ...
}
```

- ローカル実行時: `runtime="local"`, `runtime_label="Local"`
- Colab 実行時: `runtime="colab"`, `runtime_label="Colab"`

#### ③ 起動中フラグで重複起動を防止 (`dashboard/web/app.js`)

状態オブジェクトに `startPending` フラグを追加し、以下のロジックを実装：

```javascript
const state = { 
  config: null, status: {}, history: [], range: 0, logVersion: null, 
  startPending: false  // ← 追加
};
```

起動ボタンのクリックハンドラ：

```javascript
$('startBtn').addEventListener('click', async function () {
    if (state.startPending) return;  // 既に起動要求中なら無視
    state.startPending = true;
    $('startBtn').disabled = true;
    
    try {
        const res = await post('/api/start', {...});
        if (!res.ok) {
            alert(res.message || '学習を開始できませんでした');
            // 失敗時は 1.2 秒後に再有効化
            setTimeout(() => {
                state.startPending = false;
                $('startBtn').disabled = !!state.status.is_running;
            }, 1200);
            return;
        }
        openPanel(false);
    } finally {
        if (state.status && state.status.is_running) {
            state.startPending = false;
        }
    }
});
```

- 起動要求送信中は `startPending = true` で再クリックを無視
- 失敗時は 1.2 秒後に `false` へ戻し、再試行を促す
- 成功時は `is_running` が `true` になるまで `disabled` のまま

#### ④ ヘッダーの進捗ノート表示に実行先を加える (`dashboard/web/app.js`)

```javascript
const runtimeText = st.runtime_label || runtime;
const noteBase = st.is_running ? (st.mode || '学習中')
    : (recs.length ? '待機中（記録済み ' + recs.length + ' 世代）' : '待機中');
$('progressNote').textContent = '実行先: ' + runtimeText + ' / ' + noteBase;
```

- 例: 「実行先: Colab / 学習中」
- 例: 「実行先: Local / 待機中（記録済み 42 世代）」

### 3. 変更ファイル一覧

- `dashboard/dashboard_server.py`: `/api/status` に `runtime`, `runtime_label` を追加
- `dashboard/web/index.html`: ヘッダーにランタイムバッジを追加
- `dashboard/web/app.js`: `startPending` フラグと起動ロジック、表示ロジックを修正
- `ai/mazeward_colab_control.py`: `status()` メソッドに `runtime`, `runtime_label` を追加

### 4. 動作確認結果

```
python -m py_compile dashboard/dashboard_server.py ai/mazeward_colab_control.py
EXIT:0
```

全ファイルの Python 構文チェック完了。

### 5. ユーザー体験の改善点

#### Before
- 画面を見ても「今どっちで動いているのか」がわからない
- 起動失敗後、ボタンがすぐ押せるようになるため、ユーザーが何度も連打してしまう可能性

#### After
- ヘッダーに「実行先: Local / Colab」が常に表示される
- 進捗ノートにも実行先と学習状態がセットで表示される
- 起動失敗時は警告アラート + 1.2 秒待機のため、ユーザーがエラーを認識して対処できる
- 起動成功時は自動的にボタンが無効になり、状態が安定する

---
