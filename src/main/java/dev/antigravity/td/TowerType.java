package dev.antigravity.td;

import java.util.Locale;
import net.minestom.server.entity.EntityType;

public enum TowerType {
    BASIC("basic", "基本タワー", 1, 1, 8.0, 20, 4.0, 15, EntityType.SKELETON, 1.0, "単体"),
    FLAMETHROWER("flame", "火炎放射タワー", 1, 1, 7.0, 8, 2.0, 20, EntityType.BLAZE, 1.0, "火炎"),
    FROST("frost", "フロストタワー", 1, 1, 9.0, 24, 3.0, 20, EntityType.SNOW_GOLEM, 1.0, "冷気"),
    LIGHTNING_BALL("ball", "雷球タワー", 1, 2, 10.0, 26, 5.0, 35, EntityType.VEX, 1.35, "電撃"),
    POISON("poison", "毒タワー", 1, 3, 9.0, 18, 2.5, 35, EntityType.CAVE_SPIDER, 1.55, "毒"),
    SNOWBALL("snowball", "スノーボールタワー", 2, 2, 11.0, 30, 6.5, 45, EntityType.IRON_GOLEM, 1.8, "氷結");

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
            String effectLabel) {
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
}