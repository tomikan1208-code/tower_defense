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
 *
 * <p>後半の 6 種は {@link Trait} で能力を持つ。こちらは
 * 「長い迷路 + 高 DPS の塔を並べる」という <b>防衛側の最適解そのもの</b> を
 * 崩しにいく役割で、対戦の送り合いで効いてくる。</p>
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
            1400, 0.032, 8, 200, 10, 0.85, 0.0, NamedTextColor.DARK_RED),

    // ---------------------------------------------------------------- 能力持ち

    /** 通り道のタワーを黙らせる。火力を 1 箇所に固めるほど、まとめて止まる。 */
    SAPPER("妨害者", EntityType.CREEPER, MoveMode.GROUND,
            56, 0.062, 1, 11, 1, 0.0, 0.0, NamedTextColor.DARK_GREEN,
            Trait.sapper(3.5, 40)),

    /** 被弾すると経路の先へ飛ぶ。1 点集中のキルゾーンを飛び越える。 */
    BLINKER("瞬移体", EntityType.ENDERMAN, MoveMode.GROUND,
            74, 0.058, 0, 13, 2, 0.4, 0.0, NamedTextColor.LIGHT_PURPLE,
            Trait.blink(3.5, 45)),

    /** 周囲の味方の被ダメージを 35% 減らす。数字を並べるだけでは溶けなくなる。 */
    AEGIS("庇護者", EntityType.PIGLIN_BRUTE, MoveMode.GROUND,
            150, 0.040, 4, 22, 2, 0.5, 0.0, NamedTextColor.BLUE,
            Trait.ward(5.5, 0.35)),

    /** 倒すと 2 体に分かれる。単体火力だけの構成を咎める。 */
    SPLITTER("分裂体", EntityType.SLIME, MoveMode.GROUND,
            96, 0.050, 2, 10, 2, 0.0, 0.0, NamedTextColor.GREEN,
            Trait.split(2)),

    /** 燃えず、減速も効かない。炎と氷に寄せた構成を咎める。 */
    EMBERLING("熱塊", EntityType.BLAZE, MoveMode.GROUND,
            110, 0.070, 3, 18, 2, 1.0, 0.0, NamedTextColor.GOLD,
            Trait.fireproof()),

    /**
     * 倒れても一度だけ出発点へ戻り、そのとき守り手のライフ上限を 1 奪う。
     * 「倒しさえすれば損はない」という前提を崩すための存在。
     */
    REAPER("終焉騎", EntityType.WITHER_SKELETON, MoveMode.GROUND,
            260, 0.048, 6, 46, 3, 0.6, 0.0, NamedTextColor.DARK_PURPLE,
            Trait.reaper(1));

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
    private final Trait trait;

    EnemyKind(String displayName, EntityType entityType, MoveMode moveMode,
              double baseHp, double baseSpeed, double armor, int goldReward, int leakDamage,
              double slowResist, double healPerSecond, TextColor color) {
        this(displayName, entityType, moveMode, baseHp, baseSpeed, armor, goldReward,
                leakDamage, slowResist, healPerSecond, color, Trait.NONE);
    }

    EnemyKind(String displayName, EntityType entityType, MoveMode moveMode,
              double baseHp, double baseSpeed, double armor, int goldReward, int leakDamage,
              double slowResist, double healPerSecond, TextColor color, Trait trait) {
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
        this.trait = trait;
    }

    /** 特殊能力。持たない敵は {@link Trait#NONE}。 */
    public Trait trait() {
        return trait;
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
