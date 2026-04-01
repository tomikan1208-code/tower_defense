# Tower System データ構造 & 強化パス設計

Emberwardのタワー運用メカニクス（属性・強化分岐・効果）をMinecraftで実装するためのモデル定義。

---

## 1. タワー基本構造

### 1.1 タワー定義：列挙型パターン

```java
public enum TowerType {
    // 1x1 基本タワー群
    BASIC("Basic Tower", 1, 1, 4, 8, 20, TowerElement.NORMAL, 15),
    SCRAP("Scrap Tower", 1, 1, 3, 6, 15, TowerElement.NORMAL, 10),
    DICE("Dice Tower", 1, 1, 2, 8, 25, TowerElement.NORMAL, 20),
    
    // 1x1 属性タワー
    FLAMETHROWER("Flamethrower", 1, 1, 5, 10, 40, TowerElement.FIRE, 50),
    FROST("Frost Tower", 1, 1, 4, 9, 30, TowerElement.ICE, 40),
    LASER("Laser Tower", 1, 1, 6, 7, 30, TowerElement.ELECTRIC, 25),
    DART("Dart Tower", 1, 1, 3, 6, 25, TowerElement.NORMAL, 15),
    ARCANE_MISSILE("Arcane Missile", 1, 1, 4, 8, 25, TowerElement.ARCANE, 25),
    
    // 2x2 大型タワー
    FIREBALL("Fireball Tower", 2, 2, 8, 12, 50, TowerElement.FIRE, 85),
    SNIPER("Sniper Tower", 2, 2, 10, 5, 40, TowerElement.NORMAL, 70),
    SNOWBALL("Snowball Tower", 2, 2, 6, 10, 35, TowerElement.ICE, 50),
    
    // 3x3 巨大タワー
    EARTHQUAKE("Earthquake Tower", 3, 3, 10, 15, 45, TowerElement.ELECTRIC, 60),
    BOMB("Bomb Tower", 3, 3, 12, 10, 50, TowerElement.FIRE, 60);

    private final String displayName;
    private final int sizeX, sizeY;
    private final int baseDamage;
    private final int baseRange;
    private final int attackCooldown; // Tick
    private final TowerElement defaultElement;
    private final int baseCost;

    TowerType(String displayName, int sizeX, int sizeY, int dmg, int range, 
              int cooldown, TowerElement element, int cost) {
        this.displayName = displayName;
        // ...
    }
}
```

### 1.2 用語定義

```
Level: タワーの強化段階。初期 Lv.1
      Lv.1 → Lv.2 → Lv.3 いずれかの強化パス進行
Red Upgrade: ダメージ/効果強化パス
Blue Upgrade: 範囲/速度強化パス
```

---

## 2. 属性（Element）システム

### 2.1 属性列挙

```java
public enum TowerElement {
    NORMAL("Normal", 0xFFFFFF),
    FIRE("Fire", 0xFF6600),
    ICE("Ice", 0x00CCFF),
    ELECTRIC("Electric", 0xFFFF00),
    POISON("Poison", 0x00CC00),
    ARCANE("Arcane", 0xFF00FF);

    private final String name;
    private final int color;
}
```

### 2.2 状態異常と属性の対応

```
Fire → Burn (持続ダメージ, ~100dmg or 200dmg/s)
Ice   → Chill (移動速度低下 25%)
Electric → Charge (蓄積, 満タンでスタン 0.5秒)
Poison → Poison (継続ダメージ, 蓄積量に応じて Tick増加)
Arcane → Crit (高HPで自動Crit, 可能性範囲に依存)
```

### 2.3 属性相互作用（重要）

```
Poison + Fire: 爆発ダメージ化
Poison + Electric: Tick増加 + Charge加速
Ice + Electric: Chill消費で Charge増幅
Arcane + 他: Crit時にランダム状態異常付与
```

---

## 3. タワーステート（配置後の状態管理）

### 3.1 TowerInstance クラス設計

```java
public class TowerInstance {
    // ID・配置情報
    private UUID towerUUID;
    private TowerType type;
    private Location location; // マップ内座標
    
    // 現在状態
    private int currentLevel = 1;
    private TowerElement currentElement; // 属性が変わる場合を想定
    private int currentDamage;
    private int currentRange;
    private int currentCooldown; // Tick
    
    // 強化パス履歴
    private List<String> upgradePath = new ArrayList<>(); // ["RED", "RED", "BLUE"]
    
    // 視認化用
    private Entity displayEntity; // ArmorStand, Marker等
    private int attackTickCounter = 0;
    
    // 強化状態
    private boolean canUpgradeRed = true;
    private boolean canUpgradeBlue = true;
    
    // 前フレーム敵ターゲット
    private List<Entity> recentTargets = new ArrayList<>();
    
    // Getter / Setter
    public void upgradeRed() {
        upgradePath.add("RED");
        currentLevel++;
        applyRedUpgradeEffect();
        updateDisplay();
    }
    
    public void upgradeBlue() {
        upgradePath.add("BLUE");
        currentLevel++;
        applyBlueUpgradeEffect();
        updateDisplay();
    }
    
    public void attack(Entity target) {
        // ダメージ + 属性効果適用
        target.damage(currentDamage, DamageType.GENERIC);
        applyElementEffect(target, currentElement);
    }
}
```

---

## 4. 強化パス定義表

### 4.1 Basic Tower の強化例

```
基本スペック:
  Dmg: 4, Range: 8, Cooldown: 20Tick, Element: NORMAL

━━━ RED パス（ダメージ強化）
Lv.1 → Lv.2 (Red-1):
  Dmg: 4 → 6
  Cost: 15G
  Effect: " ダメージ +2"

Lv.2 → Lv.3 (Red-2):
  Dmg: 6 → 8
  Cost: 15G
  Effect: "ダメージ +2"

Lv.3 → Lv.4 (Red-3):
  Dmg: 8 → 10
  Cost: 20G
  Effect: "ダメージ +2"

━━━ BLUE パス（範囲強化）
Lv.1 → Lv.2 (Blue-1):
  Range: 8 → 10
  Cooldown: 20 → 15
  Cost: 15G
  Effect: "範囲 +2, 速度 + 25%"

Lv.2 → Lv.3 (Blue-2):
  Range: 10 → 12
  Cooldown: 15 → 12
  Cost: 15G
  Effect: "範囲 +2, 速度 + 20%"

Lv.3 → Lv.4 (Blue-3):
  Range: 12 → 14
  Cooldown: 12 → 10
  Cost: 20G
  Effect: "範囲 +2, 速度 + 20%"
```

### 4.2 DICE Tower の強化例（特殊）

```
特殊メカニクス: サイコロ(1-6)でダメージが変動

基本:
  Dmg: 1-6（サイコロ値）
  Range: 8
  Cooldown: 25Tick

RED パス（出目統制）:
  - 1が出にくくなる（再ロール）
  - コスト段階的増加

BLUE パス（属性付与 - Elemental Dice Relic連携）:
  - 各サイコロ値に属性付与
  - 1,2 → Ice, 3 → Poison, 4 → Electric, 5 → Fire, 6 → Arcane
  - 属性によるステータス変化
```

### 4.3 複数形状の強化互換性

```
Scrap Tower (1x1, NORMAL):
  RED: ダメージ強化
  BLUE: 弾薬追加 +15 (初期30, Blue強化で45, 60, 75)
  特殊: 弾薬切れで自動破壊

Flamethrower (1x1, FIRE):
  RED: ダメージ強化（Burn発生確率向上）
  BLUE: 範囲/クールダウン改善
```

---

## 5. タワーの相互作用（Relic 想定）

### 5.1 Scrap系 Relic

```
Scrap Master's Note:
  条件: Scrap Tower がロードアウト内
  効果: 全 Scrap Tower の弾薬 +50% (30→45)

Scrap Master's Wrench:
  条件: Scrap Tower がロードアウト内
  効果: Scrap Tower 破壊時→ ランダムに3つのタワーが回復
```

### 5.2 Dice系 Relic

```
Remote Dice:
  条件: Dice Tower がロードアウト内
  効果: 初期ウェーブで全 Dice Tower の出目が1→再ロール

Elemental Dice:
  条件: Dice Tower がロードアウト内
  効果: Dice Tower が属性ダメージ付与（出目依存）
```

### 5.3 属性相互作用 Relic

```
Volatile Poison:
  効果: 毒状態の敵が Fire 属性ダメージを受ける
  → 毒を爆発ダメージに変換 (25% area explosion)

Frostfire:
  効果: Fire ダメージタイプが変更
  → Fire Tower 攻撃速度 -20%
  → Fire ダメージが Chill Effect を付与

Amplify Poison:
  効果: 毒状態の敵が Electric ダメージを受ける
  → Poison Tick増加（3秒間）
  → Charge 蓄積加速
```

---

## 6. 実装モデル（Minestom）

### 6.1 配置フェーズでの TowerInstance 生成

```java
public class TowerPlacementManager {
    public void placeTower(Player player, Location loc, TowerType type) {
        // コスト確認
        int cost = type.getCost();
        if (getPlayerGold(player) < cost) {
            player.sendMessage(Component.text("[ERROR] ゴール不足"));
            return;
        }
        
        // インスタンス生成
        TowerInstance tower = new TowerInstance(
            UUID.randomUUID(),
            type,
            loc,
            type.getDefaultElement()
        );
        
        // マップに登録
        gameInstance.addTower(tower);
        
        // コスト消費
        consumeGold(player, cost);
        
        // 表示更新
        tower.updateDisplay();
        player.sendMessage(Component.text(
            "[TOWER] " + type.getDisplayName() + " 配置完了 (Cost: " + cost + "G)"
        ));
    }
}
```

### 6.2 強化フェーズでの 状態更新

```java
public class TowerUpgradeManager {
    public void upgradeRed(Player player, TowerInstance tower) {
        // 強化後の新ステータス計算
        int newDamage = tower.calculateRedUpgradeEffect();
        
        // Relic 相互作用チェック
        if (hasRelic(player, "Elemental Dice") && tower.getType() == TowerType.DICE) {
            tower.setCurrentElement(selectRandomElement());
        }
        
        // ステータス反映
        tower.setCurrentDamage(newDamage);
        tower.getUpgradePath().add("RED");
        tower.setCurrentLevel(tower.getCurrentLevel() + 1);
        
        // コスト消費（段階的）
        int upgradeCost = calculateUpgradeCost(tower);
        consumeGold(player, upgradeCost);
        
        // UI更新
        tower.updateDisplay();
        updateScoreboard(player);
        player.sendMessage(Component.text(
            "[TOWER] Red強化完了 Dmg: " + tower.getBaseStats().dmg + 
            " → " + newDamage
        ));
    }
    
    private int calculateUpgradeCost(TowerInstance tower) {
        int baseUpgradeCost = 15;
        int level = tower.getCurrentLevel();
        // Lv.3 → 4 は +20G など
        return level >= 3 ? baseUpgradeCost + 5 : baseUpgradeCost;
    }
}
```

### 6.3 ウェーブ中の攻撃ロジック

```java
public class TowerAttackController {
    public void tickTower(TowerInstance tower, List<Entity> allEnemies) {
        // 攻撃クールダウン処理
        tower.incrementTickCounter();
        if (tower.getAttackTickCounter() < tower.getCurrentCooldown()) {
            return;
        }
        
        // 射程内の敵を探す
        List<Entity> targets = findTargetsInRange(tower, allEnemies);
        if (targets.isEmpty()) return;
        
        // 最初の敵（または近い敵）をターゲット
        Entity target = selectTarget(tower, targets);
        
        // 攻撃実行
        tower.attack(target);
        
        // 属性効果適用
        applyStatusEffect(target, tower.getCurrentElement());
        
        // クールダウンリセット
        tower.setAttackTickCounter(0);
        
        // Relic 効果チェック
        if (hasRelic("Spark Amplifier") && hasStatusEffect(target, StatusEffect.BURNING)) {
            target.addEffect(new PotionEffect(PotionEffectType.WITHER, 100, 0));
        }
    }
    
    private void applyStatusEffect(Entity target, TowerElement element) {
        switch (element) {
            case FIRE -> {
                if (Math.random() < 0.05) { // 5% Burn 확률
                    target.addEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 0));
                }
            }
            case ICE -> {
                target.addEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2));
            }
            case ELECTRIC -> {
                increaseCharge(target, tower.getCurrentDamage());
                if (getCharge(target) >= 100) {
                    target.addEffect(new PotionEffect(PotionEffectType.BLINDNESS, 10, 0));
                    resetCharge(target);
                }
            }
            case POISON -> {
                incrementPoison(target, tower.getCurrentDamage());
            }
            // ...
        }
    }
}
```

---

## 7. 実装チェックリスト

### フェーズ1: 基本タワー実装
- [ ] Basic Tower
- [ ] Scrap Tower
- [ ] Dice Tower
- [ ] 強化パス (Red/Blue x 2段階)

### フェーズ2: 属性タワー実装
- [ ] Flamethrower (Fire)
- [ ] Frost Tower (Ice)
- [ ] Laser Tower (Electric)
- [ ] Dart Tower (Poison)
- [ ] Arcane Missile (Arcane)

### フェーズ3: 大型タワー実装
- [ ] Fireball Tower (2x2)
- [ ] Sniper Tower (2x2)
- [ ] Earthquake Tower (3x3)

### フェーズ4: 相互作用
- [ ] Relic 効果判定処理
- [ ] 属性コンボ判定
- [ ] 状態異常スタック管理

### フェーズ5: ポーランシング
- [ ] クールダウン表示
- [ ] ターゲット表示
- [ ] 強化可能状態UI更新

---

## 8. 参考：属性効果の数値

### Status Effect 対応表

```
Burn → PotionEffectType.WITHER + 継続ダメージ計算式
      (max(100, 1% of MaxHP) per sec, 0.25 for Boss)

Chill → PotionEffectType.SLOWNESS (Lvl 1 = 25% 低下)

Charge → カスタム属性（Plugin側で管理）
        到達: 100%, スタンは BLINDNESS Lvl 1 で表現

Poison → カスタム属性（Plugin側で管理）
        Tick Rate 増加で継続ダメージ加速

Arcane Crit → ダメージ計算時に 2倍化（高HP時自動）
```

---

## 9. 今後の拡張予定

- [ ] Talent Tree システム（キャラごとの強化パス分岐）
- [ ] Upgrading Chain（1タワーから派生強化）
- [ ] Synergy System（複数タワーの属性組み合わせボーナス）
- [ ] Special Tower Events（Boss Wave 限定強化等）
