package dev.antigravity.mazeward.enemy;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;

/**
 * 出現中の敵 1 体。
 *
 * <p>セル単位ではなく <b>折れ線を距離で補間</b> して歩くので、
 * 減速をかけても曲がり角でもカクつかない。</p>
 */
public final class EnemyInstance {

    private static final int NAME_REFRESH_TICKS = 4;

    private final EnemyKind kind;
    private final Entity body;
    private final double maxHp;
    private final int goldReward;

    // 戦闘中に壁が増えると経路が引き直されるので final にできない
    private List<Pos> waypoints;
    private double[] cumulative;
    private double totalLength;

    private double hp;
    private double progress;
    private int slowTicks;
    private double slowFactor;
    private int burnTicks;
    private double burnDps;
    private int nameTimer;
    private boolean leaked;

    /** 呪詛による被ダメージ増加。 */
    private double vulnerability;
    private int vulnerableTicks;

    /** 庇護オーラによる被ダメージ軽減。毎オーラ tick で貼り直される。 */
    private double ward;
    private int wardTicks;

    /** 瞬移体が続けて飛ばないようにする間隔。 */
    private int blinkCooldown;

    /** 見せ場（瞬移・送還・復活）が起きたことを戦場側へ伝えるフラグ。 */
    private boolean blinked;
    private boolean banished;

    private int revivesLeft;

    // 1 発ごとに数字を出すとエンティティが溢れるので、少しの間ぶんを足し合わせてから出す
    private double pendingDamage;
    private TextColor pendingColor;

    public EnemyInstance(EnemyKind kind, Entity body, List<Pos> waypoints, double maxHp, int goldReward) {
        this.kind = kind;
        this.body = body;
        this.maxHp = maxHp;
        this.hp = maxHp;
        this.goldReward = goldReward;
        this.revivesLeft = kind.trait().revives();
        applyWaypoints(waypoints);
    }

    private void applyWaypoints(List<Pos> path) {
        this.waypoints = path;
        this.cumulative = new double[path.size()];
        double total = 0.0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).distance(path.get(i));
            cumulative[i] = total;
        }
        this.totalLength = total;
        this.progress = 0.0;
    }

    /**
     * 経路を引き直す。戦闘中にプレイヤーが壁を足したときに呼ばれる。
     *
     * <p>新しい折れ線は「敵のいまの座標」から始まっている必要がある。
     * 進行度は 0 に戻るが、タワーの狙いは {@link #remaining()}（コアまでの残距離）で
     * 判定しているので、引き直しても優先順位は狂わない。</p>
     */
    public void repath(List<Pos> newWaypoints) {
        if (newWaypoints.size() < 2) {
            return;
        }
        applyWaypoints(newWaypoints);
    }

    public EnemyKind kind() {
        return kind;
    }

    /** いま辿っている折れ線。分裂した子に同じ経路を渡すのに使う。 */
    public List<Pos> waypoints() {
        return waypoints;
    }

    /** 経路上の任意の位置へ移す。分裂の子を親の位置から歩かせるのに使う。 */
    public void advanceTo(double distance) {
        progress = Math.max(0.0, Math.min(totalLength, distance));
    }

    public Entity body() {
        return body;
    }

    public double hp() {
        return hp;
    }

    public double maxHp() {
        return maxHp;
    }

    public int goldReward() {
        return goldReward;
    }

    public boolean alive() {
        return hp > 0.0;
    }

    public boolean leaked() {
        return leaked;
    }

    /** 経路上の進行度 0.0〜1.0。 */
    public double progressRatio() {
        return totalLength <= 0.0 ? 1.0 : Math.min(1.0, progress / totalLength);
    }

    public double travelled() {
        return progress;
    }

    /**
     * コアまでの残り距離。タワーは「これが小さい敵＝最も危険な敵」を狙う。
     * 進行距離ではなく残距離で見ているので、経路を引き直しても優先順位が壊れない。
     */
    public double remaining() {
        return Math.max(0.0, totalLength - progress);
    }

    public Pos position() {
        return positionAt(progress);
    }

    // ---------------------------------------------------------------- 状態異常

    public void applySlow(double factor, int ticks) {
        double effective = factor * (1.0 - kind.slowResist());
        if (effective <= 0.0) {
            return;
        }
        if (effective >= slowFactor) {
            slowFactor = effective;
            slowTicks = Math.max(slowTicks, ticks);
        } else {
            slowTicks = Math.max(slowTicks, ticks / 2);
        }
    }

    public void applyBurn(double dps, int ticks) {
        double effective = dps * (1.0 - kind.trait().burnResist());
        if (effective <= 0.0) {
            return;
        }
        burnDps = Math.max(burnDps, effective);
        burnTicks = Math.max(burnTicks, ticks);
    }

    /** 呪詛。被ダメージが増える。深いほうを優先し、持続は長いほうを取る。 */
    public void applyVulnerability(double amount, int ticks) {
        if (amount <= 0.0) {
            return;
        }
        vulnerability = Math.max(vulnerability, amount);
        vulnerableTicks = Math.max(vulnerableTicks, ticks);
    }

    /**
     * 庇護オーラ。
     *
     * <p>毎回上書きするのではなく期限つきで貼る。庇護者が倒れた瞬間に
     * 軽減が消えないと「庇護者から狙う」という判断が成立しないので、
     * 持続はオーラの更新間隔ぶんだけにしてある。</p>
     */
    public void applyWard(double reduction, int ticks) {
        if (reduction <= 0.0) {
            return;
        }
        ward = Math.max(ward, reduction);
        wardTicks = Math.max(wardTicks, ticks);
    }

    public boolean warded() {
        return wardTicks > 0 && ward > 0.0;
    }

    public boolean vulnerable() {
        return vulnerableTicks > 0 && vulnerability > 0.0;
    }

    /**
     * 経路を戻す（送還塔）。
     *
     * <p>倒すのではなく <b>もう一度キルゾーンを通させる</b> ための効果。
     * 出発点より手前へは戻さない。</p>
     */
    public boolean pushBack(double distance) {
        if (distance <= 0.0 || progress <= 0.0) {
            return false;
        }
        progress = Math.max(0.0, progress - distance);
        banished = true;
        return true;
    }

    public boolean consumeBlinked() {
        boolean value = blinked;
        blinked = false;
        return value;
    }

    public boolean consumeBanished() {
        boolean value = banished;
        banished = false;
        return value;
    }

    /**
     * 倒れる代わりに出発点へ戻れるなら戻る。
     *
     * @return 戻ったなら true。false ならそのまま死ぬ
     */
    public boolean tryRevive() {
        if (revivesLeft <= 0) {
            return false;
        }
        revivesLeft--;
        hp = maxHp;
        progress = 0.0;
        slowTicks = 0;
        slowFactor = 0.0;
        burnTicks = 0;
        burnDps = 0.0;
        return true;
    }

    public boolean slowed() {
        return slowTicks > 0;
    }

    public boolean burning() {
        return burnTicks > 0;
    }

    /**
     * 装甲・呪詛・庇護を通したうえで、実際に削れたぶんを返す。
     *
     * <p>順番に意味がある。装甲は固定引き算なので先に引き、
     * そのあとで呪詛（増）と庇護（減）を掛ける。逆にすると、
     * 装甲の高い敵に呪詛をかけたときの伸びが不自然に大きくなる。</p>
     */
    public double damage(double raw) {
        double applied = Math.max(1.0, raw - kind.armor());
        if (vulnerableTicks > 0) {
            applied *= 1.0 + vulnerability;
        }
        if (wardTicks > 0) {
            applied *= 1.0 - ward;
        }
        applied = Math.max(0.5, applied);
        hp -= applied;
        tryBlink();
        return applied;
    }

    /** 被弾したら経路の先へ飛ぶ。生きているあいだだけ。 */
    private void tryBlink() {
        if (!kind.trait().blinks() || blinkCooldown > 0 || hp <= 0.0) {
            return;
        }
        blinkCooldown = kind.trait().blinkCooldown();
        // コアの直前までしか飛べない。飛んだだけで漏れるのは理不尽
        progress = Math.min(Math.max(0.0, totalLength - 1.0),
                progress + kind.trait().blinkDistance());
        blinked = true;
    }

    /**
     * 表示待ちのダメージを足す。色は「いちばん大きく削った属性」のものを残すので、
     * 数字を見ればどの塔が主力になっているかが分かる。
     */
    public void addPendingDamage(double amount, TextColor color) {
        if (color != null && (pendingColor == null || amount > pendingDamage / 2.0)) {
            pendingColor = color;
        }
        pendingDamage += amount;
    }

    public boolean hasPendingDamage() {
        return pendingDamage >= 0.5;
    }

    public double takePendingDamage() {
        double value = pendingDamage;
        pendingDamage = 0;
        return value;
    }

    public TextColor pendingColor() {
        return pendingColor == null ? NamedTextColor.WHITE : pendingColor;
    }

    /** 装甲を無視する継続ダメージ（燃焼など）。 */
    public void damageDirect(double amount) {
        hp -= amount;
    }

    public void heal(double amount) {
        hp = Math.min(maxHp, hp + amount);
    }

    // ---------------------------------------------------------------- 毎 tick

    public void tick() {
        if (blinkCooldown > 0) {
            blinkCooldown--;
        }
        if (vulnerableTicks > 0 && --vulnerableTicks == 0) {
            vulnerability = 0.0;
        }
        if (wardTicks > 0 && --wardTicks == 0) {
            ward = 0.0;
        }
        if (burnTicks > 0) {
            burnTicks--;
            damageDirect(burnDps / 20.0);
            if (burnTicks == 0) {
                burnDps = 0.0;
            }
        }

        double speed = kind.baseSpeed();
        if (slowTicks > 0) {
            slowTicks--;
            speed *= (1.0 - slowFactor);
            if (slowTicks == 0) {
                slowFactor = 0.0;
            }
        }

        progress += speed;
        if (progress >= totalLength) {
            progress = totalLength;
            leaked = true;
        }
    }

    /** エンティティの見た目を実位置に同期する。 */
    public void syncBody() {
        Pos target = positionAt(progress);
        Pos current = body.getPosition();
        double dx = target.x() - current.x();
        double dz = target.z() - current.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        body.teleport(target.withYaw(yaw));

        if (nameTimer-- <= 0) {
            nameTimer = NAME_REFRESH_TICKS;
            body.setCustomName(nameComponent());
            body.setCustomNameVisible(true);
        }
    }

    private Component nameComponent() {
        int filled = (int) Math.round(10.0 * Math.max(0.0, hp) / maxHp);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? '|' : '.');
        }
        Component name = Component.text(kind.displayName() + " ", kind.color())
                .append(Component.text(bar.toString(),
                        filled > 5 ? NamedTextColor.GREEN : filled > 2 ? NamedTextColor.YELLOW : NamedTextColor.RED));
        if (slowTicks > 0) {
            name = name.append(Component.text(" ❄", NamedTextColor.AQUA));
        }
        if (burnTicks > 0) {
            name = name.append(Component.text(" ✹", NamedTextColor.GOLD));
        }
        if (wardTicks > 0) {
            name = name.append(Component.text(" ⛨", NamedTextColor.BLUE));
        }
        if (vulnerableTicks > 0) {
            name = name.append(Component.text(" ☠", NamedTextColor.LIGHT_PURPLE));
        }
        if (revivesLeft > 0) {
            name = name.append(Component.text(" ✦", NamedTextColor.DARK_PURPLE));
        }
        return name;
    }

    private Pos positionAt(double distance) {
        if (waypoints.size() == 1 || distance <= 0.0) {
            return waypoints.get(0);
        }
        if (distance >= totalLength) {
            return waypoints.get(waypoints.size() - 1);
        }
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high - 1) {
            int mid = (low + high) >>> 1;
            if (cumulative[mid] <= distance) {
                low = mid;
            } else {
                high = mid;
            }
        }
        Pos a = waypoints.get(low);
        Pos b = waypoints.get(low + 1);
        double segment = cumulative[low + 1] - cumulative[low];
        double t = segment <= 1e-9 ? 0.0 : (distance - cumulative[low]) / segment;
        return new Pos(
                a.x() + (b.x() - a.x()) * t,
                a.y() + (b.y() - a.y()) * t,
                a.z() + (b.z() - a.z()) * t);
    }
}
