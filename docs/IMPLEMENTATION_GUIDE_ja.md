# 実装ガイド：Emberward → Minecraft への操作・UI移植

タワーディフェンス操作を Minestom/Minecraft 環境で実現するための統合ガイド。

---

## 概要：何ができるようになったか

### 取得した設計書

1. **[control_design_ja.md](control_design_ja.md)**
   - 操作フロー（数字キー + 右左クリック + R/B キー）
   - スコアボード・チャット UI
   - エラーハンドリング

2. **[tower_system_design_ja.md](tower_system_design_ja.md)**
   - タワー 14 種の基本スペック定義
   - 強化パス (Red/Blue x 3段階まで定義可)
   - 属性・状態異常システム
   - Relic 相互作用チェックリスト

3. **[block_placement_design_ja.md](block_placement_design_ja.md)**
   - ブロック 6 形状の定義
   - 配置可能判定ロジック
   - プレビュー・パーティクル表示
   - 配置制約・バランス設定

---

## 「何をどう操作するか」クイックリファレンス

### ブロック配置

```
ホットバースロット [1]-[8] = ブロック形状（最大8種、同形状はスタック）
              ↓
       数字キー「1-8」
              ↓
   ホットバーアクティブ表示
              ↓
      右クリック @配置位置
              ↓
  プレビュー表示（形状 + 敵導線）
              ↓
  マウスホイール / Q で90度回転
              ↓
  左クリック確定（在庫-1）
              ↓
  ブロック実装置 + スタック消費
```

### タワー配置

```
ホットバースロット [9] = モード切替（Block <-> Tower）
      ↓
ホットバースロット [1]-[8] = タワー種（最大8種）
              ↓
    数字キー「1-8」
              ↓
   タワー配置モード有効
              ↓
  ブロック上で 右クリック -> プレビュー
              ↓
  左クリック確定
              ↓
  タワー配置 + ゴール消費（タワー在庫は無限）
```

### タワー強化

```
配置済みタワーを右クリック
              ↓
  タワー強化GUIを開く
              ↓
  Red/Blue 強化アイコンをドラッグ
              ↓
  DROPで強化確定 + ゴール消費
              ↓
  Lv. 増加 + ステ更新
```

---

## ファイル依存関係・実装順序

```
[0] ホットバーモード切り替え基盤
    ├→ HotbarMode Enum (BLOCK_MODE / TOWER_MODE)
    ├→ HotbarModeManager
    ├→ Mode切り替えアイテム (スロット1,2,9)
    └→ ホットバー動的刷新システム

[1] 基本フレームワーク
    ├→ Zone / Stage / Match 管理
    ├→ Wave システム
    └→ Enemy Spawn / Movement

[2] UI 基盤
    ├→ Scoreboard (右側 Info 表示)
    ├→ Chat Message System
    └→ Hotbar Item Manager (Mode統合)

[3] ブロック配置システム
    (block_placement_design_ja.md)
    ├→ BlockShape 列挙 & Offset 計算
    ├→ BlockRotation Enum & BlockRotationHandler
    ├→ BlockPlacementManager
    ├→ canPlaceBlock() / placeBlock()
    ├→ PreviewRenderer (パーティクル)
    └→ EnemyPathVisualizer (敵導線表示)

[4] タワーシステム (tower_system_design_ja.md)
    ├→ TowerType Enum 定義
    ├→ TowerInstance クラス
    ├→ TowerPlacementManager
    ├→ InventoryGUIBuilder (upgrade GUI spec)
    ├→ TowerUpgradeManager
    └→ TowerAttackController (クールダウン・攻撃)

[5] ゲームロジック統合
    ├→ GameStateManager (準備←→ウェーブ)
    ├→ EconomyManager (ゴール管理)
    └→ WaveController

[6] 操作入力処理 (control_design_ja.md)
    ├→ Hotkey Listener (1-6キー + Mode切り替え)
    ├→ PlayerInteractListener (右左クリック)
  ├→ BlockRotationListener (マウスホイール/Q)
    ├→ InventoryGUIListener (ドラッグ・ドロップ)
    └→ Error Message Formatter
```

---

## 段階別実装チェックリスト

### ステップ 0：ホットバーモード切り替え基盤（1日）

```
□ HotbarMode Enum 定義
  ├ BLOCK_MODE （ブロック配置用）
  └ TOWER_MODE （タワー配置用）
□ HotbarModeManager クラス
  ├ getActiveMode() : HotbarMode
  ├ switchMode() : void
  └ getHotbarContents() : ItemStack[]
□ Mode切り替えアイテム
  ├ スロット1-8: 現在モードの選択アイテム
  └ スロット9: Mode Toggle アイテム（Block <-> Tower）
□ ホットバー動的刷新
  ├ BLOCK_MODE 時: スロット1-8に ブロック形状（最大8種）表示
  ├ TOWER_MODE 時: スロット1-8に タワー種（最大8種）表示
  └ UIクライアント側同期
□ TEST: Mode切り替えがスムーズ、ホットバー内容が正しく更新されるか
```

### ステップ 1：基本ブロック配置・回転・導線表示（2-3日）

```
□ BlockShape 定義（6種類）
□ BlockOffset 計算ロジック
□ BlockRotation Enum （0°/90°/180°/270°）
□ BlockRotationHandler
  ├ applyRotation() メソッド（行列座標変換）
  └ 回転入力処理（マウスホイール/Qキー）
□ EnemyPathVisualizer
  ├ visualizeEnemyPath() （配置前のルート表示）
  ├ canEnemyPassThrough() （敵通路妥当性判定）
  └ renderPathParticles() （導線パーティクル）
□ BlockPlacementManager スケルトン
□ canPlaceBlock() チェック（4項目）
  ├ マップ内範囲
  ├ 重配置
  ├ 地形衝突
  └ 敵ルート衝突（EnemyPathVisualizer 統合）
□ PreviewRenderer （回転・導線表示対応）
□ TEST: 正常配置 + エラー表示 + 回転 + 導線表示が連動するか
```

### ステップ 2：タワー基本配置とGUI強化（2-3日）

```
□ TowerType Enum （Basic, Cannon, Frost など 5種）
□ InventoryGUIBuilder クラス
  ├ buildUpgradeGUI()
  │  └ 灰色ガラス板フレーム（9x3）
  │  └ 緑色ガラス板パネル（中央）
  ├ addRedUpgradeIcon() （左上アイコン）
  ├ addBlueUpgradeIcon() （右上アイコン）
  └ addDescriptionPane() （説明テキスト）
□ TowerUpgradeGUIListener
  ├ ドラッグ検出 (アイテム掴み)
  ├ DROP 検出 (左ボタン解放で確定)
  └ アニメーション (掴むアイテムの回転エフェクト)
□ TowerInstance クラス
□ TowerPlacementManager
□ ホットバースロット1-8からの選択・配置
□ Red/Blue 強化パス実装
  ├ 強化パス計算（Lv 段階ごと）
  ├ ゴール消費
  └ ステータス更新
□ TEST: 配置＋強化GUIが表示 + ドラッグ確定 + Lv更新
```

### ステップ 3：属性・攻撃ロジック（2-3日）

```
□ TowerElement Enum （Fire, Ice, Electric など 6種）
□ TowerAttackController
  ├ クールダウン管理
  ├ 射程判定
  ├ ターゲット選択
  └ ダメージ適用
□ StatusEffect (Burn, Chill, Charge 等)
  ├ 継続ダメージ計算
  ├ 状態異常の相互作用判定
  └ 3秒 Tick ごとの更新
□ TEST: 敵に攻撃が当たり、属性効果が適用されるか
```

### ステップ 4：UI・操作系統合（1-2日）

```
□ Hotkeyリスナー（1-8キー + Mode切り替え 9キー右クリック）
□ PlayerInteractListener（右左クリック）
□ BlockRotationListener (マウスホイール/Qキー)
□ InventoryGUIListener （ドラッグ・ドロップ検出）
□ スコアボード　右側情報パネル
□ チャット通知フォーマッタ
□ ERROR 表示システム
□ TEST: 全操作が遅延・ズレなく応答するか
```

### ステップ 5：ゲーム統合・バランス（2-3日）

```
□ GameStateManager （準備 ← Wave進行中 → Wave完了）
□ EconomyManager （ゴール収支表示）
□ Wave Generator （敵出現スケジュール）
□ ビジュアルフィードバック
  ├ プレビューパーティクル
  ├ 敵にダメージ時エフェクト
  └ タワー攻撃ビジュアル
□ TEST: 準備30秒 → Wave → クリア フロー一周
```

### ステップ 6：ポーランシング（3-5日）

```
□ Relic システム（条件絞込みロジック）
□ 複数属性コンボ判定
□ カスタム名前タグ表示
□ ホットキーリマップUI
□ バランス調整（ダメージ・コスト値）
□ デバッグコマンド群
□ TEST: 複合攻撃・Relic効果が正しくトリガーするか
```

---

## 主要タスク分割案（チーム開発向け）

| 担当 | タスク | 依存関係 | 期間 |
|------|--------|---------|------|
| **A** | ホットバーMode切り替え | 基本フレーム | 1日 |
| **B** | ブロック配置・回転・導線 | **A完了後** | 2.5日 |
| **C** | タワー配置・GUI強化 | **A完了後** | 2.5日 |
| **D** | 属性・攻撃ロジック | **C完了後** | 2日 |
| **E** | UI・操作入力 | **B, C 並行** | 1.5日 |
| **F** | ゲーム統合 | **B, C, D完了後** | 2.5日 |
| **G** | ポーランシング | **F完了後** | 3日 |

**Total**: 15営業日（3週間）

---

## 実装の落とし穴・よくある質問

### Q1: ブロック配置で段差をどう許可するか

**A**: BlockOffset の計算時に、Y軸も含める か、配置時に Y レベル一致チェックを行う。
通常は「同じY」or「±1 ブロック段差」までに制限。

```java
int levelDiff = Math.abs(loc.getBlockY() - anchor.getBlockY());
if (levelDiff > 1) return false; // 拒否
```

### Q2: タワーのクールダウン表示は必要か

**A**: 不要（当初）。攻撃が可視的に見えれば、プレイヤーは自動判別できる。
ポーランシング段階で、ホバーUI等で詳細表示を追加。

### Q3: 敵ルート衝突判定は準備フェーズでやるべき？

**A**: ブロック配置時にリアルタイム判定が実装される（EnemyPathVisualizer）。
敵が通過不可なパターンは **警告表示** して配置を許可するか、配置を拒否するかはGameBalanceで決定。

### Q4: Red/Blue 強化パスを3段階じゃなく無限にしたい

**A**: TowerInstance に `maxLevelRed`, `maxLevelBlue` フィールド追加。  
タワー種ごとで上限を定義。

### Q5: Relic ドロップはどこで発動するか

**A**: wave クリア時のカード選択画面で Relic 3択を提示。  
選択後、TowerInstance の Relic 効果フラグを ON。

---

## デバッグ用コマンド案

```
/td admin setgold <player> <amount>
  → プレイヤーのゴール設定（テスト用）

/td debug showblocks
  → 全配置ブロック表示（コンソール出力）

/td admin skipwave
  → 現在のWave をスキップ

/td admin tower <type>
  → 指定タイプのタワーを next placement に

/td admin clear
  → ステージ内全ブロック・タワー削除

/td stats <player>
  → プレイヤーの現在GoldHP等を表示
```

---

## 参照ドキュメント関連図

```
┌─────────────────────────────────────────────────────┐
│  config_ja.md (Emberward ルール概要)               │
│  → 属性定義・属性コンボの基礎知識                  │
└────────────────────┬────────────────────────────────┘
                     ↓
    ┌────────────────────────────────────┐
    │ tower_system_design_ja.md          │
    ├────────────────────────────────────┤
    │ • TowerType 定義                   │
    │ • 強化パス (Red/Blue x3)          │
    │ • 属性・コンボ相互作用             │
    │ • Relic 統合ポイント               │
    └────────────────────────────────────┘
                     ↓
   ┌─────────────────────────────────────────┐
   │ block_placement_design_ja.md            │
   ├─────────────────────────────────────────┤
   │ • BlockShape 定義 (6種)               │
   │ • 配置判定ロジック                    │
   │ • プレビュー・パーティクル           │
   └─────────────────────────────────────────┘
            ↓                          ↓
   ┌──────────────────┐     ┌──────────────────┐
   │control_design    │     │ game_state_      │
   │_ja.md            │     │ machine.md       │
   ├──────────────────┤     │ (開発中)        │
   │• Hotkey (1-6)   │     │• Wave Logic      │
   │• 右左Click      │     │• Economy Game    │
   │•R/B Menu        │     │• Scoring        │
   │• UI Layout      │     └──────────────────┘
   └──────────────────┘
```

---

## 実装進捗テンプレート

```markdown
# 実装進捗 Week X

## 完了
- [x] BlockShape 定義
- [x] BlockPlacementManager スケルトン
- [ ] ...

## 進行中
- [ ] TowerInstance クラス実装
- [ ] 属性システム設計

## 次週タスク
- [ ] デバッグ環境構築
- [ ] Relic ドロップロジック設計
```

---

## チェックリスト：実装開始前の確認事項

- [ ] Minestom/Bukkit API に習熟しているか
- [ ] 既存 `src/main/java/` の構造を把握したか
- [ ] `GameFlowController.java` の役割を理解したか
- [ ] UI用 Scoreboard ライブラリの選定は済んだか（FastBoard等）
- [ ] データ永続化は不要か（セッション限りでOK）
- [ ] テストフレームワーク（JUnit等）の導入状況は
- [ ] エラーハンドリングの統一フォーマットは定義したか

---

## 次のステップ

1. **短期（1週間）**: 上記ステップ1-4 を完了
2. **中期（2週間）**: ステップ5-6 でゲーム完成度向上
3. **長期**: 追加属性・高度な Relic システム、AI敵等の拡張

---

**作成日**: 2026-04-02  
**対象プロジェクト**: Minestom Tower Defense (Emberward 風)  
**参照ドキュメント**:
- [docs/emberward_rule_summary_ja.md](emberward_rule_summary_ja.md)
- [docs/control_design_ja.md](control_design_ja.md)
- [docs/tower_system_design_ja.md](tower_system_design_ja.md)
- [docs/block_placement_design_ja.md](block_placement_design_ja.md)
