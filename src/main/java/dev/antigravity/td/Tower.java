package dev.antigravity.td;

import java.util.List;
import net.minestom.server.entity.Entity;
import net.minestom.server.coordinate.Pos;

public final class Tower {
    private static final int MAX_LEVEL = 5;

    private final TowerType type;
    private final Pos position;
    private final int originX;
    private final int originZ;
    private final List<Entity> visuals;
    private int level = 1;
    private int currentCooldown = 0;

    public Tower(TowerType type, Pos position, int originX, int originZ, List<Entity> visuals) {
        this.type = type;
        this.position = position;
        this.originX = originX;
        this.originZ = originZ;
        this.visuals = visuals;
    }

    public TowerType type() {
        return type;
    }

    public Pos position() {
        return position;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public List<Entity> visuals() {
        return visuals;
    }

    public int level() {
        return level;
    }

    public double range() {
        return rangeAt(level);
    }

    public double damage() {
        return damageAt(level);
    }

    public int cooldownTicks() {
        return cooldownAt(level);
    }

    public boolean canUpgrade() {
        return level < MAX_LEVEL;
    }

    public int upgradeCost() {
        return 12 + (level * 8);
    }

    public int nextUpgradeCost() {
        return canUpgrade() ? 12 + ((level + 1) * 8) : -1;
    }

    public void upgrade() {
        if (canUpgrade()) {
            level++;
        }
    }

    public double nextDamage() {
        return damageAt(Math.min(level + 1, MAX_LEVEL));
    }

    public int nextCooldownTicks() {
        return cooldownAt(Math.min(level + 1, MAX_LEVEL));
    }

    public double nextRange() {
        return rangeAt(Math.min(level + 1, MAX_LEVEL));
    }

    public String effectText() {
        return type.effectLabel() + " Lv" + level;
    }

    public String nextEffectText() {
        return type.effectLabel() + " Lv" + Math.min(level + 1, MAX_LEVEL);
    }

    public void cooldownTick() {
        if (currentCooldown > 0) {
            currentCooldown--;
        }
    }

    public boolean canFire() {
        return currentCooldown <= 0;
    }

    public void fire() {
        currentCooldown = cooldownTicks();
    }

    private double damageAt(int towerLevel) {
        return type.damage() + (towerLevel - 1) * 1.25;
    }

    private int cooldownAt(int towerLevel) {
        int base = type.cooldownTicks();
        int reduced = base - (towerLevel - 1) * 2;
        return Math.max(4, reduced);
    }

    private double rangeAt(int towerLevel) {
        return type.range() + (towerLevel - 1) * 0.7;
    }
}
