# Block Placement System 詳細設計

Embryword風のテトリス型ブロック配置をMinecraftで実装するための仕様。

---

## 1. ブロック形状の定義

### 1.1 基本形状一覧（Tetromino派生）

```
T-Block (4ブロック)         L-Block (3ブロック)
  ■■■                        ■
    ■                          ■
                               ■■

L-Block反転 (3ブロック)    Square (4ブロック)
    ■                        ■■
    ■                        ■■
  ■■

I-Block (3ブロック)        Straight-4 (4ブロック)
  ■■■                      ■■■■

Plus/Cross (5ブロック)     Custom-L2 (3ブロック)
    ■                         ■■
  ■■■                         ■
    ■
```

### 1.2 BlockShape クラス定義

```java
public enum BlockShape {
    T_BLOCK(
        "T-Block",
        new BlockOffset[] {
            new BlockOffset(0, 0),   // 中心
            new BlockOffset(-1, 0),  // 左
            new BlockOffset(1, 0),   // 右
            new BlockOffset(0, 1)    // 上
        }
    ),
    L_BLOCK(
        "L-Block",
        new BlockOffset[] {
            new BlockOffset(0, 0),
            new BlockOffset(0, 1),
            new BlockOffset(1, 1)
        }
    ),
    SQUARE_BLOCK(
        "Square",
        new BlockOffset[] {
            new BlockOffset(0, 0),
            new BlockOffset(1, 0),
            new BlockOffset(0, 1),
            new BlockOffset(1, 1)
        }
    ),
    PLUS_BLOCK(
        "Cross",
        new BlockOffset[] {
            new BlockOffset(0, 0),   // 中心
            new BlockOffset(0, 1),   // 上
            new BlockOffset(0, -1),  // 下
            new BlockOffset(-1, 0),  // 左
            new BlockOffset(1, 0)    // 右
        }
    ),
    STRAIGHT_3(
        "I-Block (3x1)",
        new BlockOffset[] {
            new BlockOffset(0, 0),
            new BlockOffset(1, 0),
            new BlockOffset(2, 0)
        }
    ),
    STRAIGHT_4(
        "I-Block (4x1)",
        new BlockOffset[] {
            new BlockOffset(0, 0),
            new BlockOffset(1, 0),
            new BlockOffset(2, 0),
            new BlockOffset(3, 0)
        }
    );

    private final String displayName;
    private final BlockOffset[] offsets;

    BlockShape(String displayName, BlockOffset[] offsets) {
        this.displayName = displayName;
        this.offsets = offsets;
    }

    public int getBlockCount() {
        return offsets.length;
    }

    public BlockOffset[] getOffsets() {
        return offsets;
    }
}

public record BlockOffset(int dx, int dz) {}
```

---

## 2. ブロック選択ドラフトシステム

### 2.1 ドラフトの流れ

```
ウェーブ開始 30秒前
  ↓
ゲームサーバーが最大8種類の BlockShape をランダム選択
  ↓
プレイヤーに通知 + ホットバースロット 1-8 に "ブロック形状" アイテム化
  ↓
プレイヤーが配置を開始
  ↓
配置完了時に、該当スロットのスタック数を1消費
```

補足:
- 同一形状ブロックは同一スロットでスタック（例: T字 x4）
- 同時に保持できるブロック形状は最大8種類

### 2.2 GameDraftManager の実装案

```java
public class GameDraftManager {
    private Map<BlockShape, Integer> currentBlockStacks = new LinkedHashMap<>();
    
    public void generateDraft() {
        // 最大8種類まで重複なしでランダム選択
        currentBlockStacks = new LinkedHashMap<>();
        while (currentBlockStacks.size() < 8) {
            BlockShape shape = BlockShape.values()[
                (int) (Math.random() * BlockShape.values().length)
            ];
            if (!currentBlockStacks.containsKey(shape)) {
                int initialCount = 2 + (int) (Math.random() * 3);
                currentBlockStacks.put(shape, initialCount);
            }
        }
        
        // プレイヤーに通知
        broadcastDraft();
        assignToHotbar();
    }
    
    private void assignToHotbar() {
        for (Player player : getActivePlayers()) {
            // スロット 1..8 にアイテム割り当て
            int slot = 0;
            for (Map.Entry<BlockShape, Integer> entry : currentBlockStacks.entrySet()) {
                player.getInventory().setItemStack(
                    slot++,
                    createBlockItem(entry.getKey(), entry.getValue())
                );
            }
        }
    }
    
    private ItemStack createBlockItem(BlockShape shape, int count) {
        ItemStack item = new ItemStack(Material.DIRT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(shape.getDisplayName() + " x" + count);
        meta.setLore(Arrays.asList(
            "Block Count: " + shape.getBlockCount(),
            "形状: " + getShapePreview(shape)
        ));
        item.setItemMeta(meta);
        return item;
    }
}
```

---

## 3. ブロック配置フェーズ

### 3.1 配置可能判定ロジック

配置時に以下を確認：

```
1. マップ内範囲チェック
   ├─ 全ブロックが ステージバウンダリ内か
   └─ OUT → エラー "マップ外です"

2. 地形衝突チェック
   ├─ 同じY層 or 接続可能な階段か
   └─ 不可能 → エラー "ここに配置できません"

3. 重配置チェック
   ├─ 既に置かれたブロックと重複していないか
   └─ 既に存在 → エラー "ここは既にブロックがあります"

4. 敵ルート衝突チェック（オプション）
   ├─ 敵スポーン・ゴール直近でないか
   └─ 危険 → 警告 "敵がスポーンできない可能性があります"
```

### 3.2 BlockPlacementManager の実装案

```java
public class BlockPlacementManager {
    private Map<UUID, BlockShape> playerSelectedShapes = new HashMap<>();
    private Set<Location> occupiedLocations = new HashSet<>();
    private Stage currentStage;
    
    public boolean canPlaceBlock(Player player, Location anchor, BlockShape shape) {
        // 配置対象ブロック群を計算
        List<Location> targetLocs = calculateBlockLocations(anchor, shape);
        
        // 1. マップ内チェック
        for (Location loc : targetLocs) {
            if (!isWithinStageBounds(loc)) {
                player.sendMessage(Component.text("[ERROR] マップ外です"));
                return false;
            }
        }
        
        // 2. 既存ブロック衝突チェック
        for (Location loc : targetLocs) {
            if (occupiedLocations.contains(loc)) {
                player.sendMessage(Component.text("[ERROR] ここは既にブロックがあります"));
                return false;
            }
        }
        
        // 3. 地形レベル一致チェック（同じY、または階段状）
        int baseY = anchor.getBlockY();
        for (Location loc : targetLocs) {
            int levelDiff = Math.abs(loc.getBlockY() - baseY);
            if (levelDiff > 1) { // 1ブロック段差まで許可
                player.sendMessage(Component.text("[ERROR] 段差が大きすぎます"));
                return false;
            }
        }
        
        return true;
    }
    
    public void placeBlock(Player player, Location anchor, BlockShape shape) {
        List<Location> targetLocs = calculateBlockLocations(anchor, shape);
        
        // ブロック配置
        for (Location loc : targetLocs) {
            currentStage.setBlockAt(loc, Material.DIRT); // 仮の見た目
            occupiedLocations.add(loc);
        }
        
        // ホットバー在庫を1消費
        consumeBlockStack(player, shape, 1);
        
        // メッセージ
        player.sendMessage(Component.text(
            "[BLOCK] " + shape.getDisplayName() + " 配置完了"
        ));
        
        // UI 更新
        updateScoreboard(player);
    }
    
    private List<Location> calculateBlockLocations(Location anchor, BlockShape shape) {
        List<Location> result = new ArrayList<>();
        for (BlockOffset offset : shape.getOffsets()) {
            Location loc = anchor.clone();
            loc.add(offset.dx(), 0, offset.dz());
            result.add(loc);
        }
        return result;
    }
    
    private boolean isWithinStageBounds(Location loc) {
        // ステージの定義範囲内チェック（例: 20x20）
        Region bounds = currentStage.getRegion();
        return bounds.isInside(loc);
    }
}
```

---

## 4. プレビュー表示

### 4.1 プレビューパーティクル

```java
public class PreviewRenderer {
    public void showPreview(Player player, Location anchor, BlockShape shape, boolean isValid) {
        List<Location> targetLocs = calculateBlockLocations(anchor, shape);
        
        // 配置可能なら緑、不可なら赤
        Particle particleType = isValid ? Particle.HAPPY_VILLAGER : Particle.SMOKE;
        Color color = isValid ? Color.GREEN : Color.RED;
        
        for (Location loc : targetLocs) {
            // パーティクル表示
            player.getWorld().spawnParticle(
                particleType,
                loc.add(0.5, 0.5, 0.5),
                10,
                0.2, 0.5, 0.2,
                0.0
            );
            
            // 半透明ブロック表示（オプション）
            if (isValid) {
                player.sendBlockChange(loc, Material.LIME_CONCRETE.asBlockData());
            } else {
                player.sendBlockChange(loc, Material.RED_CONCRETE.asBlockData());
            }
        }
    }
    
    public void hidePreview(Player player, List<Location> previewLocs) {
        for (Location loc : previewLocs) {
            // ブロック表示をクライアント側でリセット
            player.sendBlockChange(loc, currentStage.getBlockAt(loc));
        }
    }
}
```

---

## 5. ブロック配置の制約・ゲームバランス

### 5.1 配置数制限

```
各ステージごとにブロック数上限を決定：

NORMAL難易度:
    最大8種類の形状を配布
    例: T-Block x3, L-Block x2, Square x2, Straight-4 x1

ELITE難易度:
  配置できるブロック数を削減
  または形状が複雑化

BOSS難易度:
  非常に限定的
  または配置不可ステージも存在

全難易度共通:
    - 同一形状はスタック管理
    - 配置時に在庫を1消費
    - 在庫0の形状は選択不可
```

### 5.2 配置戦略への影響

```
例: 敵が直進するルート上に L字を配置
  → 敵が階段状に上って通過するまでに時間稼ぎ
  → その間にタワーが敵を処理

例: Plus形状で十字マップを壊す
  → 敵が複数ルートに分散
  → タワー範囲が効きにくくなるトレードオフ
```

---

## 6. ブロック回転機構

### 6.1 回転状態の管理

```java
public enum BlockRotation {
    ROTATION_0(0),      // オリジナル
    ROTATION_90(90),    // 時計回り 90度
    ROTATION_180(180),  // 時計回り 180度
    ROTATION_270(270);  // 時計回り 270度
    
    private final int degrees;
    
    BlockRotation(int degrees) {
        this.degrees = degrees;
    }
    
    public BlockRotation rotateClockwise() {
        return switch (this) {
            case ROTATION_0 -> ROTATION_90;
            case ROTATION_90 -> ROTATION_180;
            case ROTATION_180 -> ROTATION_270;
            case ROTATION_270 -> ROTATION_0;
        };
    }
    
    public BlockRotation rotateCounterClockwise() {
        return switch (this) {
            case ROTATION_0 -> ROTATION_270;
            case ROTATION_90 -> ROTATION_0;
            case ROTATION_180 -> ROTATION_90;
            case ROTATION_270 -> ROTATION_180;
        };
    }
}
```

### 6.2 回転操作フロー

プレイヤーがプレビュー表示中に回転操作：
- マウスホイール UP/DOWN: 時計回り/反時計回り 90度ずつ回転
- または Q キー (反時計回り)

技術注記:
- サーバー実装での Eキー直接取得は難しく、インベントリ操作と競合しやすい
- 実運用は「マウスホイール + Q」または「Sneak+右クリック」を推奨

```java
public class BlockRotationHandler {
    private BlockRotation currentRotation = BlockRotation.ROTATION_0;
    
    public void handleRotateInput(Player player, boolean clockwise) {
        if (clockwise) {
            currentRotation = currentRotation.rotateClockwise();
        } else {
            currentRotation = currentRotation.rotateCounterClockwise();
        }
        
        // プレビューを再計算して表示
        refreshPreview(player);
        
        // パーティクル: 回転演出
        player.getWorld().spawnParticle(
            Particle.ASH,
            player.getLocation(),
            20, 0.5, 0.5, 0.5, 0.05
        );
    }
    
    public List<BlockOffset> applyRotation(BlockShape shape, BlockRotation rotation) {
        BlockOffset[] original = shape.getOffsets();
        BlockOffset[] rotated = new BlockOffset[original.length];
        
        for (int i = 0; i < original.length; i++) {
            BlockOffset offset = original[i];
            rotated[i] = switch (rotation) {
                case ROTATION_0 -> offset;
                case ROTATION_90 -> 
                    new BlockOffset(-offset.dz(), offset.dx()); // (x, z) → (-z, x)
                case ROTATION_180 -> 
                    new BlockOffset(-offset.dx(), -offset.dz()); // (x, z) → (-x, -z)
                case ROTATION_270 -> 
                    new BlockOffset(offset.dz(), -offset.dx()); // (x, z) → (z, -x)
            };
        }
        return Arrays.asList(rotated);
    }
    
    private void refreshPreview(Player player) {
        // 現在の配置位置でプレビューを更新
        // プレイヤーの向いている方向を取得
        Location anchor = calculateAnchorFromPlayerLook(player);
        List<Location> previewLocs = calculateBlockLocations(
            anchor, 
            selectedShape, 
            currentRotation
        );
        
        // プレビュー再描画（古いプレビューは消去）
        showPreview(player, previewLocs);
    }
}
```

### 6.3 回転対応のプレビュー更新

```java
public void showPreview(Player player, List<Location> targetLocs, boolean isValid) {
    Particle particleType = isValid ? Particle.HAPPY_VILLAGER : Particle.SMOKE;
    
    for (Location loc : targetLocs) {
        // 回転されたプレビューを表示
        player.getWorld().spawnParticle(
            particleType,
            loc.add(0.5, 0.5, 0.5),
            10, 0.2, 0.5, 0.2, 0.0
        );
        
        // 半透明ブロック（回転後の形状）
        Material material = isValid ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        player.sendBlockChange(loc, material.asBlockData());
    }
}
```

---

## 7. 敵導線可視化システム

### 7.1 パスフィンディング統合

ブロック配置時に敵のルート計算を自動更新：

```java
public class EnemyPathVisualizer {
    private final PathfindingEngine pathfinder;
    
    public void visualizeEnemyPath(Player player, List<Location> newBlockLocs) {
        // 1. 仮配置
        Location[] tempBlocks = placeBlocksTemporarily(newBlockLocs);
        
        // 2. 敵スポーン地点からゴール地点までのルート計算
        Location spawn = currentStage.getEnemySpawn();
        Location goal = currentStage.getGoal();
        
        List<Location> pathRoute = pathfinder.findPath(spawn, goal);
        
        // 3. ルートを可視化
        renderPathParticles(player, pathRoute);
        
        // 4. 仮配置を取り消す
        removeTemporaryBlocks(tempBlocks);
    }
    
    private void renderPathParticles(Player player, List<Location> pathRoute) {
        // ルート上に青色パーティクル表示
        for (Location pathLoc : pathRoute) {
            player.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER,
                pathLoc.add(0.5, 0.5, 0.5),
                15,
                0.3, 0.3, 0.3,
                0.0
            );
        }
        
        // スポーン＆ゴール地点を強調
        player.getWorld().spawnParticle(
            Particle.GLOW,
            spawn.add(0.5, 1, 0.5), 20, 0.2, 0.2, 0.2, 0.0
        );
        player.getWorld().spawnParticle(
            Particle.GLOW,
            goal.add(0.5, 1, 0.5), 20, 0.2, 0.2, 0.2, 0.0
        );
    }
}
```

### 7.2 敵ルート衝突判定

配置前に敵が通過可能かチェック：

```java
public boolean canEnemyPassThrough(List<Location> newBlockLocs) {
    // 仮配置
    for (Location loc : newBlockLocs) {
        tempStage.setBlockAt(loc, Material.DIRT);
    }
    
    // パスフィンディング実行
    List<Location> path = pathfinder.findPath(spawn, goal);
    
    // 仮配置取り消し
    for (Location loc : newBlockLocs) {
        tempStage.setBlockAt(loc, Material.AIR);
    }
    
    // ルートが存在するかチェック
    return path != null && !path.isEmpty();
}
```

### 7.3 配置否判定フロー

```
プレイヤーが左クリック（配置確定）
  ↓
canEnemyPassThrough() を実行
  ↓
敵がスポーン ← ゴール可能？
  ├─ YES: 配置可能 → 緑色表示
  └─ NO: 敵がブロック → エラー
            「敵が通過できなくなる可能性があります」
            
備考: ホットバーにチェックボックス追加
  □敵通路チェック (デフォルト ON)
      OFF: ダムじゃなくルート衝突を許可
```

### 7.4 複雑なマップの導線処理

複数のルートが存在する場合、ビジュアルフィードバック：

```java
public void visualizeMultiplePaths(Player player) {
    List<List<Location>> allPaths = pathfinder.findAllPossiblePaths(spawn, goal);
    
    // 複数ルートを異なる色で表示
    Color[] colors = {Color.BLUE, Color.CYAN, Color.GREEN};
    
    for (int i = 0; i < allPaths.size(); i++) {
        Color currentColor = colors[i % colors.length];
        for (Location loc : allPaths.get(i)) {
            // 色付きパーティクル
            player.spawnParticle(
                Particle.REDSTONE,
                loc.add(0.5, 0.5, 0.5),
                1,
                new Particle.DustOptions(currentColor, 1.0f)
            );
        }
    }
}
```

---

## 8. ブロックの視覚的バリエーション

### 8.1 属性別配色（オプション）

```java
public enum BlockMaterial {
    BASE(Material.DIRT, "基盤"),
    FIRE(Material.TERRACOTTA, "火"),
    ICE(Material.ICE, "氷"),
    ELECTRIC(Material.GLOWSTONE, "電気"),
    POISON(Material.SLIME_BLOCK, "毒"),
    ARCANE(Material.AMETHYST_BLOCK, "秘術");
}
```

ブロック配置時に、タワーの属性に合わせてブロック色が変わる（デコレーション）。

```java
public void placeBlockWithTheme(Location loc, BlockShape shape, TowerElement element) {
    Material blockMat = switch (element) {
        case FIRE -> Material.TERRACOTTA;
        case ICE -> Material.ICE;
        case ELECTRIC -> Material.GLOWSTONE;
        default -> Material.DIRT;
    };
    stage.setBlockAt(loc, blockMat);
}
```

---

## 9. デバッグ用：ブロック配置リセット

```java
public class BlockDebugManager {
    public void clearAllBlocks(Player player) {
        // 全配置ブロック削除（テスト用）
        for (Location loc : occupiedLocations) {
            stage.setBlockAt(loc, Material.AIR);
        }
        occupiedLocations.clear();
        player.sendMessage(Component.text("[DEBUG] ブロック配置をリセット"));
    }
    
    public void printBlockLayout() {
        // コンソール出力: 現在のブロック配置図
        System.out.println("=== Current Block Layout ===");
        for (Location loc : occupiedLocations) {
            System.out.println(loc.toString());
        }
    }
}
```

---

## 10. 実装フェーズ分割

### Phase 1: 基本配置（必須）
- [ ] BlockShape 定義（6種類）
- [ ] BlockPlacementManager の核実装
- [ ] canPlaceBlock() ロジック
- [ ] placeBlock() 実行
- [ ] ホットバー管理

### Phase 2: UI/表示（重要）
- [ ] プレビューパーティクル
- [ ] 配置可能/不可の色分け表示
- [ ] 配置完了メッセージ

### Phase 3: 制約の厳格化
- [ ] 敵ルートとブロック衝突判定
- [ ] ステージごとの配置数上限管理
- [ ] 難易度別のブロック種選別

### Phase 4: ポーランシング（後付け）
- [ ] ブロック視覚的テーマ（属性色）
- [ ] 段差配置の自然さ改善
- [ ] デバッグコマンド

---

## 11. 配置システムの全フロー図

```
┌─────────────────────────────┐
│ Wave 開始 30秒前            │
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ generateDraft()             │
│ 3つのShape をランダム選択    │
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ assignToHotbar()            │
│ スロット 1,2,3 にアイテム配置│
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ プレイヤー配置開始          │
│ 数字キー1, 2, または 3      │
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ 右クリック @注視ブロック    │
│ showPreview()               │
│ placeBlock パーティクル表示 │
└──────────┬──────────────────┘
           ↓
        Valid?
       /      \
    YES      NO
     ↓        ↓
  左ク     キャンセル
   ↓        推奨
┌─────────────────────────────┐
│ placeBlock()                │
│ 実ブロック配置              │
│ ホットバース消費            │
└──────────┬──────────────────┘
           ↓
┌─────────────────────────────┐
│ 全ブロック配置完了？        │
└──────────┬──────────────────┘
        /      \
      NO      YES
       ↓        ↓
  次へ    準備完了
          ↓
       Wave 1 開始
```

---

## 10. テストケース

| ケース | 入力 | 期待値 | 備考 |
|--------|------|--------|------|
| 正常配置 | T字を敵ルート上に | ブロック配置 + ホットバー消費 | 基本系 |
| 範囲外 | マップ端4マスに | ERROR表示 | 判定継続 |
| 重配置 | 既配置ブロック上に | ERROR表示 | キャンセル |
| 段差配置 | Y軸±2差のL字 | ERROR表示 | 1ブロック段差までOK |
| 複数種同時管理 | Slot1,2,3を順次配置 | 全て成功 | ホットバー効率 |

---

## 11. 参考：Emberwardでのブロック運用パターン

```
【早期戦略】
- テンプレート的にT字を敵進行ルート上に配置
- 敵が階段を上るまでのフリータイムを稼ぐ

【中期戦略】
- ブロック数が限定的 → 敵が回り込める経路も用意
- タワー配置でカバー

【後期戦略】
- 複雑な迷路化は辞め、タワー攻撃力で押し切り

【Boss戦】
- ブロック配置を最小化 → タワー完全特化構築
- または全カードをボス向け系に取り替え
```
