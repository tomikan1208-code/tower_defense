# Minestom RogueLike Tower Defense

Minestomで動作するローグライク系 Tower Defense の開発リポジトリです。

## 現在の実装状態（2026-04-02時点更新）

- ロビー、ロードマップ、ステージ遷移
- ロードマップ層進行（クリア層の解放）
- デッキ選択GUI（3候補、詳細表示、確定）
- ステージ準備時間（30秒）
- **ウェーブ進行、複数敵タイプ、タワー自動攻撃**
- **敵システム（通常敵、早い敵、装甲敵、ボス敵）**
  - ウェーブと層に応じた敵のHP・速度・報酬スケーリング
  - Wave進行に応じた敵構成の動的変化
  - 敵タイプごとのコスト（撃破報酬）
- ゴールド経済（初期50、敵タイプごとの報酬、Waveクリア報酬あり）
- タワー6種（サイズ1x1/1x2/1x3/2x2対応）
- ステージ中ホットバー操作
	- スロット1: タワー選択
	- スロット2: ブロック選択
	- 各選択画面から「戻る」でカテゴリ選択に復帰

## コマンド

- `!lobby`: ロビーへ移動
- `!roadmap`: ロードマップへ移動
- `!start`: ステージ内でWave開始
- `!state`: ステージ内状態表示
- `!deck <1-3>`: デッキ確定
- `!tower <type>`: 指定タワー設置（`basic|flame|frost|ball|poison|snowball`）

## 実行

前提:

- Java 25以上

Windows (PowerShell) 例:

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```

起動:

- `run.bat` を実行
- または以下を実行

```powershell
.\start-server.ps1
```

## ドキュメント

- 仕様整理: `SPEC.md`
- 補助資料: `docs/`
