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
        /** 送還: 出発点へ送り返す敵の数。 */
        double banishTargets,
        /** 呪詛: 被ダメージの上乗せ率。0.35 なら +35%。 */
        double vulnerability,
        /** 呪詛の持続 tick。 */
        int vulnerabilityTicks,
        /** 監視: 周囲のタワーの攻撃力の上乗せ率。 */
        double boostDamage,
        /** 監視: 周囲のタワーのクールダウン短縮率。 */
        double boostRate,
        /** 監視: 周囲のタワーが受ける妨害の軽減率。1.0 なら完全に無効。 */
        double disableResist) {

    public static final Effect NONE = new Effect(0, 0, 0, 0, 0, 0);

    /**
     * 出発点へ送り返す。
     *
     * <p>数ブロック押し戻すだけだった頃は、こまめに撃てるぶん
     * 「少しだけ足止めする塔」にしかならず、置いても盤面が変わらなかった。
     * <b>丸ごと出発点まで戻す代わりに、60 秒に 1 度しか撃てない</b> 切り札にしてある。
     * 一度撃てば迷路をもう一周させられるので、長い迷路ほど効きが跳ね上がる。</p>
     *
     * @param targets 一度に送り返す敵の数
     */
    public static Effect banish(int targets) {
        return new Effect(targets, 0, 0, 0, 0, 0);
    }

    public static Effect curse(double vulnerability, int ticks) {
        return new Effect(0, vulnerability, ticks, 0, 0, 0);
    }

    public static Effect watch(double boostDamage, double boostRate) {
        return watch(boostDamage, boostRate, 0);
    }

    /**
     * 監視の傘。数字を上げるだけでなく、<b>妨害者から守る</b>。
     *
     * <p>妨害者（クリーパー）は火力を 1 箇所に固めるほどまとめて黙らせてくる。
     * その対策が「散らして置く」しかないと、結局どの構成も同じ形に収束する。
     * 監視塔の傘の下だけは固めてよい、という逃げ道を作るための効果。</p>
     *
     * @param disableResist 妨害の軽減率。0.5 で半減、1.0 で完全無効
     */
    public static Effect watch(double boostDamage, double boostRate, double disableResist) {
        return new Effect(0, 0, 0, boostDamage, boostRate, disableResist);
    }

    public boolean empty() {
        return banishTargets <= 0 && vulnerability <= 0
                && boostDamage <= 0 && boostRate <= 0 && disableResist <= 0;
    }

    /** 特化で上乗せするときに使う。 */
    public Effect plus(Effect other) {
        return new Effect(
                banishTargets + other.banishTargets,
                vulnerability + other.vulnerability,
                Math.max(vulnerabilityTicks, other.vulnerabilityTicks),
                boostDamage + other.boostDamage,
                boostRate + other.boostRate,
                Math.min(1.0, disableResist + other.disableResist));
    }
}
