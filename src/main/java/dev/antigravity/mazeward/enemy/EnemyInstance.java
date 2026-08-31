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

    /** 瞬移先を探すときに経路を刻む間隔（ブロック）。細かすぎても見た目は変わらない。 */
    private static final double BLINK_SCAN_STEP = 0.5;

    /** 延焼の数字の色。撃たれたぶんと見分けられるように、炎の色で出す。 */
    private static final TextColor BURN_COLOR = NamedTextColor.GOLD;

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

    /** 直近の瞬移で跳ぶ前に居た場所。跳んだ先だけ光らせても、どこから来たのか分からない。 */
    private Pos blinkOrigin;
    private boolean banished;

    /** 送還される前に居た場所。戻った先だけ光らせても「消えた」ようにしか見えない。 */
    private Pos banishOrigin;

    private int revivesLeft;

    /** 対戦で「誰が送った敵か」。シングルでは null。分裂した子にも引き継ぐ。 */
    private EnemySource source;

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

    /** 送り主。送られた敵でなければ null。 */
    public EnemySource source() {
        return source;
    }

    public void source(EnemySource source) {
        this.source = source;
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
     * 出発点へ送り返す（送還塔）。
     *
     * <p>倒すのではなく <b>迷路をもう一周させる</b> ための効果。
     * すでに出発点にいる敵には効かない——60 秒に 1 度の一撃を、
     * 湧いたばかりの敵に空撃ちさせないため。</p>
     */
    public boolean sendToSpawn() {
        if (progress <= 0.0) {
            return false;
        }
        banishOrigin = position();
        progress = 0.0;
        banished = true;
        return true;
    }

    /** 直近の送還で送り返される前に居た場所。 */
    public Pos banishOrigin() {
        return banishOrigin;
    }

    /** 直近の瞬移の出発地点。まだ一度も跳んでいなければ null。 */
    public Pos blinkOrigin() {
        return blinkOrigin;
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

    /**
     * コアに触れても消えず、出発点へ戻る（災厄）。
     *
     * <p>HP も状態異常もそのまま残す。全快させると、削った時間がまるごと無駄になり
     * 「倒す」という選択肢が消えてしまう。<b>削り切るまで何周でも来る</b> だけの意味づけ。</p>
     */
    public void returnToStart() {
        progress = 0.0;
        leaked = false;
    }

    public boolean slowed() {
        return slowTicks > 0;
    }

    public boolean burning() {
        return burnTicks > 0;
    }

    /**
     * 何かしらの効果が乗っているか。氷塔が「まだ掛かっていない敵」を選ぶのに使う。
     *
     * <p>減速の重ねがけは上書きにしかならないので、
     * 掛かっている敵を撃ち続けるのは手数をそのまま捨てているのと同じ。</p>
     */
    public boolean affected() {
        return slowTicks > 0 || burnTicks > 0 || vulnerableTicks > 0;
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

    /**
     * 被弾したら壁を跨いで先の通路へ跳ぶ。生きているあいだだけ。
     *
     * <p>経路上を決まった距離だけ進むのではなく、<b>半径の中に入っている経路のうち
     * いちばんコアに近い点</b> を選ぶ。曲がりくねらせた迷路ほど 1 回の瞬移で
     * 稼がれるので、「壁で距離を伸ばす」一本槍への答えになる。</p>
     */
    private void tryBlink() {
        if (!kind.trait().blinks() || blinkCooldown > 0 || hp <= 0.0) {
            return;
        }
        double target = blinkTarget(kind.trait().blinkRadius());
        if (target <= progress) {
            // 跳べる先が無いなら間隔も消費しない。次に撃たれたときに跳べる
            return;
        }
        blinkCooldown = kind.trait().blinkCooldown();
        blinkOrigin = positionAt(progress);
        progress = target;
        blinked = true;
    }

    /**
     * 半径 {@code radius} の球に入っている経路のうち、いちばんコアに近い地点の進行度。
     *
     * <p>コアの直前までしか候補にしない。跳んだだけで漏れるのは理不尽なので。
     * 後ろから見ていって最初に届いた点がそのまま答えになる。</p>
     */
    private double blinkTarget(double radius) {
        double limit = Math.max(0.0, totalLength - 1.0);
        if (limit <= progress) {
            return progress;
        }
        Pos from = positionAt(progress);
        for (double distance = limit; distance > progress; distance -= BLINK_SCAN_STEP) {
            if (from.distance(positionAt(distance)) <= radius) {
                return distance;
            }
        }
        return progress;
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
            // 1 tick 分は 0.5 に満たないので、そのままでは数字が出ない。
            // 表示待ちに足しておけば、たまった分がまとめて 1 つの数字になる
            double tickBurn = burnDps / 20.0;
            damageDirect(tickBurn);
            addPendingDamage(tickBurn, BURN_COLOR);
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
        // 耐力と速度は常に頭上へ出す。「あと何発で落ちるか」「先回りが間に合うか」は
        // バーの長さだけでは読めず、置き直しの判断がそのぶん勘になる
        Component name = Component.text(kind.displayName() + " ", kind.color())
                .append(Component.text(String.format("%.0f/%.0f ", Math.max(0.0, hp), maxHp),
                        NamedTextColor.WHITE))
                .append(Component.text(String.format("速%.1f ", kind.speedPerSecond()),
                        NamedTextColor.GRAY))
                .append(Component.text(bar.toString(),
                        filled > 5 ? NamedTextColor.GREEN : filled > 2 ? NamedTextColor.YELLOW : NamedTextColor.RED));

        String tag = kind.abilityTag();
        if (!tag.isEmpty()) {
            name = name.append(Component.text(" " + tag, NamedTextColor.DARK_AQUA));
        }
        // 瞬移だけは「いま跳べるのか」で寄せ方が変わるので、残り時間まで出す
        if (kind.trait().blinks()) {
            name = name.append(blinkCooldown > 0
                    ? Component.text(String.format(" 次の瞬移 %.1f秒", blinkCooldown / 20.0),
                            NamedTextColor.DARK_GRAY)
                    : Component.text(" 瞬移可", NamedTextColor.LIGHT_PURPLE));
        }
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
