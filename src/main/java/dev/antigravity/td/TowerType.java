package dev.antigravity.td;

import java.util.Locale;
import net.minestom.server.entity.EntityType;

public enum TowerType {
    BASIC("basic", "基本タワー", 1, 1, 8.0, 20, 4.0, 15, EntityType.SKELETON, 1.0, "単体", "バランス型の基本タワー。\n射程8、連射速度1秒、攻撃力4。\n単体攻撃で着実にダメージを与える。"),
    FLAMETHROWER("flame", "火炎放射タワー", 1, 1, 7.0, 8, 2.0, 20, EntityType.BLAZE, 1.0, "火炎", "素早い火炎攻撃を仕掛ける。\n射程7、連射速度0.4秒、攻撃力2。\n高速連射で複数敵に対応可能。"),
    FROST("frost", "フロストタワー", 1, 1, 9.0, 24, 3.0, 20, EntityType.SNOW_GOLEM, 1.0, "冷気", "冷気でスローダメージを与える。\n射程9、連射速度1.2秒、攻撃力3。\n敵の移動速度を低下させる効果。"),
    LIGHTNING_BALL("ball", "雷球タワー", 1, 2, 10.0, 26, 5.0, 35, EntityType.VEX, 1.35, "電撃", "複数敵に電撃を放つ。\nサイズ1x2、射程10、連射速度1.3秒、攻撃力5。\n電撃効果で敵を麻痺させる。"),
    POISON("poison", "毒タワー", 1, 3, 9.0, 18, 2.5, 35, EntityType.CAVE_SPIDER, 1.55, "毒", "毒のダメージを与え続ける。\nサイズ1x3、射程9、連射速度0.9秒、攻撃力2.5。\n毒効果で時間経過とともにダメージ蓄積。"),
    SNOWBALL("snowball", "スノーボールタワー", 2, 2, 11.0, 30, 6.5, 45, EntityType.IRON_GOLEM, 1.8, "氷結", "大型のスノーボール発射。\nサイズ2x2、射程11、連射速度1.5秒、攻撃力6.5。\n最強の威力で敵を凍結させる。高コスト。");

    private final String key;
    private final String displayName;
    private final int sizeX;
    private final int sizeZ;
    private final double range;
    private final int cooldownTicks;
    private final double damage;
    private final int cost;
    private final EntityType entityType;
    private final double visualScale;
    private final String effectLabel;
    private final String description;

    TowerType(
            String key,
            String displayName,
            int sizeX,
            int sizeZ,
            double range,
            int cooldownTicks,
            double damage,
            int cost,
            EntityType entityType,
            double visualScale,
            String effectLabel,
            String description) {
        this.key = key;
        this.displayName = displayName;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.range = range;
        this.cooldownTicks = cooldownTicks;
        this.damage = damage;
        this.cost = cost;
        this.entityType = entityType;
        this.visualScale = visualScale;
        this.effectLabel = effectLabel;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public double range() {
        return range;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public double damage() {
        return damage;
    }

    public int cost() {
        return cost;
    }

    public EntityType entityType() {
        return entityType;
    }

    public double visualScale() {
        return visualScale;
    }

    public String effectLabel() {
        return effectLabel;
    }

    public String description() {
        return description;
    }

    public ProjectileColor projectileColor() {
        return switch (effectLabel) {
            case "火炎" -> ProjectileColor.FLAME;
            case "冷気" -> ProjectileColor.FROST;
            case "電撃" -> ProjectileColor.LIGHTNING;
            case "毒" -> ProjectileColor.POISON;
            case "氷結" -> ProjectileColor.SNOWBALL;
            default -> ProjectileColor.BASIC;
        };
    }

    public static TowerType fromToken(String token) {
        if (token == null || token.isBlank()) {
            return BASIC;
        }

        String normalized = token.toLowerCase(Locale.ROOT);
        for (TowerType type : values()) {
            if (type.key.equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    public static String usageKeys() {
        StringBuilder out = new StringBuilder();
        for (TowerType type : values()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(type.key);
        }
        return out.toString();
    }

    public enum ProjectileColor {
        BASIC(0.8f, 0.8f, 0.8f),              // グレー
        FLAME(1.0f, 0.5f, 0.0f),              // オレンジ/赤
        FROST(0.3f, 0.7f, 1.0f),              // 水色
        LIGHTNING(1.0f, 1.0f, 0.3f),          // 黄色
        POISON(0.4f, 1.0f, 0.2f),             // 緑
        SNOWBALL(0.9f, 0.95f, 1.0f);          // 淡白/水色

        public final float r;
        public final float g;
        public final float b;

        ProjectileColor(float r, float g, float b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }
}