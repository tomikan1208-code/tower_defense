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
    private List<Entity> bodies = List.of();
    private Entity label;

    /** 妨害者に黙らされている残り tick。 */
    private int disabledTicks;

    /**
     * 何段まで上げられるか。戦場（{@code Modifiers#maxTowerLevel}）が決める。
     * シングルは 3、対戦は 5。強化費の曲線もこの値で切り替わる。
     */
    private final int maxLevel;

    /** 監視塔からの上乗せ。塔を置くたびに戦場側が計算し直す。 */
    private double boostDamage;
    private double boostRate;

    /** 監視塔の傘。妨害者に黙らされる時間をこの割合だけ削る。1.0 なら効かない。 */
    private double disableResist;

    public TowerInstance(TowerKind kind, Vec2i origin, Rot rot, int cost) {
        this(kind, origin, rot, cost, TowerKind.MAX_LEVEL);
    }

    public TowerInstance(TowerKind kind, Vec2i origin, Rot rot, int cost, int maxLevel) {
        this.maxLevel = maxLevel;
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

    public int maxLevel() {
        return maxLevel;
    }

    public boolean maxed() {
        return level >= maxLevel;
    }

    public TowerKind.Stats stats() {
        return kind.statsAt(level, spec);
    }

    /** 最終段階で選んだ特化。未選択なら null。 */
    public TowerKind.Spec spec() {
        return spec;
    }

    /** いまの見た目。特化を選ぶとここが変わる。 */
    public Look look() {
        return kind.lookFor(spec);
    }

    /** 次の強化が「特化を選ぶ」段階か。 */
    public boolean nextIsSpecialization() {
        return level == maxLevel - 1;
    }

    public int nextUpgradeCost() {
        return maxed() ? -1 : kind.upgradeCost(level, maxLevel);
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
        return cooldown <= 0 && disabledTicks <= 0;
    }

    public void tickCooldown() {
        if (cooldown > 0) {
            cooldown--;
        }
        if (disabledTicks > 0) {
            disabledTicks--;
        }
    }

    /** 妨害者に黙らされる。深いほう（長いほう）を優先する。 */
    public void disable(int ticks) {
        disabledTicks = Math.max(disabledTicks, ticks);
    }

    public boolean disabled() {
        return disabledTicks > 0;
    }

    /**
     * 監視塔からの上乗せを設定する。
     *
     * <p>毎 tick 周りの塔を数え直すと塔の数の 2 乗になるので、
     * 塔が増減したときだけ戦場側から貼り直す。</p>
     */
    public void setBoost(double damage, double rate) {
        this.boostDamage = damage;
        this.boostRate = rate;
    }

    public double boostDamage() {
        return boostDamage;
    }

    public double boostRate() {
        return boostRate;
    }

    public boolean boosted() {
        return boostDamage > 0 || boostRate > 0;
    }

    /**
     * 監視塔の傘の厚さ。
     *
     * <p>重ねても厚くならない（戦場側が最大値を採る）。
     * 並べるだけで無効化できてしまうと、特化を選ぶ意味が無くなるため。</p>
     */
    public double disableResist() {
        return disableResist;
    }

    public void setDisableResist(double disableResist) {
        this.disableResist = disableResist;
    }

    /** 妨害を完全に弾けるか。 */
    public boolean disableImmune() {
        return disableResist >= 1.0;
    }

    public void resetCooldown(int ticks) {
        cooldown = ticks;
    }

    /**
     * 台座の上に立っている本体。見た目だけで、判定には使わない。
     * 「二段」の特化を選んだ塔は 2 体になる。
     */
    public List<Entity> bodies() {
        return bodies;
    }

    public void setBodies(List<Entity> bodies) {
        this.bodies = bodies;
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
