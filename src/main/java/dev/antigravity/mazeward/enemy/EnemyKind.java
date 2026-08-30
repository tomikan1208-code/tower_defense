package dev.antigravity.mazeward.enemy;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.entity.EntityType;

/**
 * 敵定義。<b>敵を増やすときはここに enum 定数を 1 つ足すだけでよい。</b>
 *
 * <p>種類の選び方には意図がある。「経路を伸ばす」一本槍を成立させないために、
 * FLYER（迷路無視）・BRUTE（固定装甲）・HEALER（範囲火力要求）の 3 種で
 * それぞれ別の対策を強制する。</p>
 */
public enum EnemyKind {

    GRUNT("徘徊者", EntityType.ZOMBIE, MoveMode.GROUND,
            34, 0.055, 0, 6, 1, 0.0, 0.0, NamedTextColor.GRAY),

    RUNNER("疾走者", EntityType.SPIDER, MoveMode.GROUND,
            20, 0.115, 0, 5, 1, 0.15, 0.0, NamedTextColor.GREEN),

    BRUTE("重装兵", EntityType.IRON_GOLEM, MoveMode.GROUND,
            130, 0.035, 5, 16, 3, 0.35, 0.0, NamedTextColor.DARK_AQUA),

    FLYER("浮遊体", EntityType.PHANTOM, MoveMode.FLYING,
            62, 0.075, 0, 12, 2, 0.0, 0.0, NamedTextColor.LIGHT_PURPLE),

    HEALER("祈祷師", EntityType.EVOKER, MoveMode.GROUND,
            70, 0.045, 2, 18, 2, 0.0, 6.0, NamedTextColor.GOLD),

    BOSS("災厄", EntityType.RAVAGER, MoveMode.GROUND,
            1400, 0.032, 8, 200, 10, 0.85, 0.0, NamedTextColor.DARK_RED);

    private final String displayName;
    private final EntityType entityType;
    private final MoveMode moveMode;
    private final double baseHp;
    private final double baseSpeed;
    private final double armor;
    private final int goldReward;
    private final int leakDamage;
    private final double slowResist;
    private final double healPerSecond;
    private final TextColor color;

    EnemyKind(String displayName, EntityType entityType, MoveMode moveMode,
              double baseHp, double baseSpeed, double armor, int goldReward, int leakDamage,
              double slowResist, double healPerSecond, TextColor color) {
        this.displayName = displayName;
        this.entityType = entityType;
        this.moveMode = moveMode;
        this.baseHp = baseHp;
        this.baseSpeed = baseSpeed;
        this.armor = armor;
        this.goldReward = goldReward;
        this.leakDamage = leakDamage;
        this.slowResist = slowResist;
        this.healPerSecond = healPerSecond;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    public EntityType entityType() {
        return entityType;
    }

    public MoveMode moveMode() {
        return moveMode;
    }

    public boolean flying() {
        return moveMode == MoveMode.FLYING;
    }

    /** ブロック / tick。 */
    public double baseSpeed() {
        return baseSpeed;
    }

    /** 固定軽減。低ダメージ高速タワーだけの構成を通さないためのパラメータ。 */
    public double armor() {
        return armor;
    }

    public int leakDamage() {
        return leakDamage;
    }

    /** 減速耐性 0.0〜1.0。1.0 で完全耐性。 */
    public double slowResist() {
        return slowResist;
    }

    public double healPerSecond() {
        return healPerSecond;
    }

    public boolean healer() {
        return healPerSecond > 0.0;
    }

    public boolean boss() {
        return this == BOSS;
    }

    public TextColor color() {
        return color;
    }

    /** 層とウェーブに応じた最大 HP。 */
    public double hpAt(int layer, int wave, double difficulty) {
        double layerScale = 1.0 + 0.32 * (layer - 1);
        double waveScale = 1.0 + 0.16 * (wave - 1);
        return baseHp * layerScale * waveScale * difficulty;
    }

    public int goldAt(int layer) {
        return (int) Math.round(goldReward * (1.0 + 0.07 * (layer - 1)));
    }
}
