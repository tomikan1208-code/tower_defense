package dev.antigravity.mazeward.tower;

/**
 * 弾やオーラに乗る「ダメージ以外の効果」。
 *
 * <p>{@link TowerKind} のコンストラクタ引数をこれ以上増やさないための入れ物。
 * 効果を持たない塔がほとんどなので、既定値は {@link #NONE}。</p>
 *
 * <p>ここにある 3 つはどれも <b>自分では削らない</b>。
 * 数字を上げる塔ばかりだと「いちばん DPS の高い塔を並べる」で終わってしまうので、
 * 「並べ方そのものを変える」種類の塔を別枠として用意している。</p>
 */
public record Effect(
        /** 送還: 敵を経路上で何ブロック押し戻すか。 */
        double knockback,
        /** 呪詛: 被ダメージの上乗せ率。0.35 なら +35%。 */
        double vulnerability,
        /** 呪詛の持続 tick。 */
        int vulnerabilityTicks,
        /** 監視: 周囲のタワーの攻撃力の上乗せ率。 */
        double boostDamage,
        /** 監視: 周囲のタワーのクールダウン短縮率。 */
        double boostRate) {

    public static final Effect NONE = new Effect(0, 0, 0, 0, 0);

    public static Effect banish(double knockback) {
        return new Effect(knockback, 0, 0, 0, 0);
    }

    public static Effect curse(double vulnerability, int ticks) {
        return new Effect(0, vulnerability, ticks, 0, 0);
    }

    public static Effect watch(double boostDamage, double boostRate) {
        return new Effect(0, 0, 0, boostDamage, boostRate);
    }

    public boolean empty() {
        return knockback <= 0 && vulnerability <= 0 && boostDamage <= 0 && boostRate <= 0;
    }

    /** 特化で上乗せするときに使う。 */
    public Effect plus(Effect other) {
        return new Effect(
                knockback + other.knockback,
                vulnerability + other.vulnerability,
                Math.max(vulnerabilityTicks, other.vulnerabilityTicks),
                boostDamage + other.boostDamage,
                boostRate + other.boostRate);
    }
}
