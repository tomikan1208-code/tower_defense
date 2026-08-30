package dev.antigravity.mazeward.tower;

import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Vec2i;
import java.util.List;
import net.minestom.server.entity.Entity;

/** 設置済みタワー 1 基。判定ロジックは持たず、状態だけを持つ。 */
public final class TowerInstance {

    private final TowerKind kind;
    private final Vec2i origin;
    private final Rot rot;
    private final List<Vec2i> footprint;
    private final double centerX;
    private final double centerZ;

    private int level;
    private TowerKind.Spec spec;
    private int investedGold;
    private int cooldown;
    private Entity label;

    public TowerInstance(TowerKind kind, Vec2i origin, Rot rot, int cost) {
        this.kind = kind;
        this.origin = origin;
        this.rot = rot;
        this.footprint = kind.shape().cellsAt(origin, rot);
        this.investedGold = cost;

        double sumX = 0;
        double sumZ = 0;
        for (Vec2i cell : footprint) {
            sumX += cell.x() + 0.5;
            sumZ += cell.z() + 0.5;
        }
        this.centerX = sumX / footprint.size();
        this.centerZ = sumZ / footprint.size();
    }

    public TowerKind kind() {
        return kind;
    }

    public Vec2i origin() {
        return origin;
    }

    public Rot rot() {
        return rot;
    }

    public List<Vec2i> footprint() {
        return footprint;
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public int level() {
        return level;
    }

    public boolean maxed() {
        return level >= TowerKind.MAX_LEVEL;
    }

    public TowerKind.Stats stats() {
        return kind.statsAt(level, spec);
    }

    /** 最終段階で選んだ特化。未選択なら null。 */
    public TowerKind.Spec spec() {
        return spec;
    }

    /** 次の強化が「特化を選ぶ」段階か。 */
    public boolean nextIsSpecialization() {
        return level == TowerKind.MAX_LEVEL - 1;
    }

    public int nextUpgradeCost() {
        return maxed() ? -1 : kind.upgradeCost(level);
    }

    public void upgrade(int cost, TowerKind.Spec chosen) {
        level++;
        investedGold += cost;
        if (chosen != null) {
            spec = chosen;
        }
    }

    public int sellValue() {
        return (int) Math.round(investedGold * 0.6);
    }

    public boolean ready() {
        return cooldown <= 0;
    }

    public void tickCooldown() {
        if (cooldown > 0) {
            cooldown--;
        }
    }

    public void resetCooldown(int ticks) {
        cooldown = ticks;
    }

    public Entity label() {
        return label;
    }

    public void setLabel(Entity label) {
        this.label = label;
    }

    public double distanceTo(double x, double z) {
        double dx = centerX - x;
        double dz = centerZ - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
