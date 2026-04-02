package dev.antigravity.td;

import net.minestom.server.entity.EntityType;

public enum EnemyType {
    /**
     * 通常敵：バランスの取れた敵
     * HP: 中程度、速度: 標準
     */
    NORMAL("通常敵", EntityType.ZOMBIE, 1.0, 1.0, 3, 1),

    /**
     * 早い敵：高速だが脆い
     * HP: 低い、速度: 高速
     */
    FAST("早い敵", EntityType.RABBIT, 0.65, 1.6, 2, 2),

    /**
     * 装甲敵：耐久性が高い
     * HP: 高い、速度: 遅い
     */
    ARMORED("装甲敵", EntityType.IRON_GOLEM, 2.5, 0.5, 5, 3),

    /**
     * ボス敵：非常に強力
     * HP: 極めて高い、速度: 中程度
     */
    BOSS("ボス敵", EntityType.WITHER, 5.0, 0.7, 20, 10);

    private final String displayName;
    private final EntityType entityType;
    private final double hpMultiplier;
    private final double speedMultiplier;
    private final int baseGoldReward;
    private final int spawnWeight;

    EnemyType(
            String displayName,
            EntityType entityType,
            double hpMultiplier,
            double speedMultiplier,
            int baseGoldReward,
            int spawnWeight
    ) {
        this.displayName = displayName;
        this.entityType = entityType;
        this.hpMultiplier = hpMultiplier;
        this.speedMultiplier = speedMultiplier;
        this.baseGoldReward = baseGoldReward;
        this.spawnWeight = spawnWeight;
    }

    public String displayName() {
        return displayName;
    }

    public EntityType entityType() {
        return entityType;
    }

    public double hpMultiplier() {
        return hpMultiplier;
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    public int baseGoldReward() {
        return baseGoldReward;
    }

    public int spawnWeight() {
        return spawnWeight;
    }

    /**
     * ウェーブと層に応じたHPを計算
     * Wave: ウェーブ番号（1から始まる）
     * Layer: 層番号（1から始まる）
     * 
     * スケーリング：
     * - Base: 敵タイプごとの基本値(12.0 * hpMultiplier)
     * - Wave: 各Waveで+16% (1.16倍)
     * - Layer: 各層で+22% (1.22倍)
     */
    public double calculateMaxHp(int wave, int layer) {
        double base = 12.0 * hpMultiplier;
        double waveScaling = Math.pow(1.16, wave - 1);
        double layerScaling = Math.pow(1.22, layer - 1);
        return base * waveScaling * layerScaling;
    }

    /**
     * ウェーブと層に応じた速度を計算（pathProgressの増加速度）
     * 
     * スケーリング：
     * - Base: 敵タイプごとの基本値(0.03 * speedMultiplier)
     * - Wave: 各Waveで+7% (1.07倍)
     * - Layer: 各層で+9% (1.09倍)
     */
    public double calculateSpeed(int wave, int layer) {
        double base = 0.03 * speedMultiplier;
        double waveScaling = Math.pow(1.07, wave - 1);
        double layerScaling = Math.pow(1.09, layer - 1);
        return base * waveScaling * layerScaling;
    }

    /**
     * ウェーブと層に応じたゴール報酬を計算
     * 
     * 計算式：
     * - Base: baseGoldReward（敵タイプごと）
     * - Wave補正: +wave（キャップ: +10）
     * - Layer補正: +(layer-1)*3（キャップ: +20）
     */
    public int calculateGoldReward(int wave, int layer) {
        int waveBonus = Math.min(wave, 10);
        int layerBonus = Math.min((layer - 1) * 3, 20);
        return baseGoldReward + waveBonus + layerBonus;
    }
}
