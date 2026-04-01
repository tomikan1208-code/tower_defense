# Minestom RogueLike Tower Defense (Emberward inspired)

Minestomで動く、ローグライク要素付きTower Defenseの土台プロジェクトです。

## 現在の機能

- Minestomサーバー起動
- フラットワールド生成
- チャットコマンドでウェーブ開始と塔設置
- 敵のルート移動、塔の自動攻撃、ゴールドと拠点HP管理

## コマンド

- `!start`: ウェーブ開始
- `!tower`: 足元に塔を設置
- `!state`: 状態表示

## 実行

前提:

- Java 25以上

Windows (PowerShell) 例:

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```

起動:

**Windows**: `run.bat` をダブルクリック、または PowerShell から:

```powershell
.\start-server.ps1
```

## 次の実装候補

1. タワーをカード/抽選で獲得するローグライク進行
2. ラウンド終了時の報酬選択UI (Inventory GUI)
3. タイル編集と経路変形（Emberward風）
4. 複数敵種と状態異常（毒・スロー）
5. 既存セーブデータとメタ進行
