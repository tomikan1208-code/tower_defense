package dev.antigravity.mazeward.enemy;

/**
 * 敵の特殊能力。
 *
 * <p>{@link EnemyKind} のコンストラクタ引数をこれ以上増やさないための入れ物。
 * 能力を持たない敵がほとんどなので、既定値は {@link #NONE}。</p>
 *
 * <h2>なぜ能力を足すのか</h2>
 * 基本 6 種は「経路の長さ」と「単純な火力」でだいたい解ける。
 * それだと防衛側の答えが <b>「長い迷路 + 高 DPS の塔を並べる」</b> 一本に収束してしまう。
 * ここにある能力はどれも <b>その一本を直接咎める</b> ように選んである。
 *
 * <ul>
 *   <li>{@code disable} — キルゾーンに火力を集中させるほど、一度に黙らされる</li>
 *   <li>{@code blink} — 一点集中のキルゾーンを飛び越える</li>
 *   <li>{@code ward} — 「数字を並べれば溶ける」を止める</li>
 *   <li>{@code split} — 単体火力だけの構成を咎める</li>
 *   <li>{@code burnResist} — 炎・氷に寄せた構成を咎める</li>
 *   <li>{@code revives} — 一度倒したくらいでは終わらせない</li>
 * </ul>
 */
public record Trait(
        /** タワー無力化の半径（ブロック）。0 なら無し。 */
        double disableRadius,
        /** 無力化が続く tick 数。 */
        int disableTicks,
        /**
         * 被弾したとき飛べる直線距離（ブロック）。0 なら無し。
         *
         * <p>経路上を進む距離ではなく <b>空間上の半径</b>。この球の中に入っている
         * 経路のうち、いちばんコアに近い点へ跳ぶので、壁を跨いで先の通路へ抜ける。</p>
         */
        double blinkRadius,
        /** 連続で飛べないようにする間隔（tick）。 */
        int blinkCooldown,
        /** 味方の被ダメージを減らすオーラの半径。0 なら無し。 */
        double wardRadius,
        /** 軽減率 0.0〜1.0。 */
        double wardReduction,
        /** 倒れたときに湧く子の数。0 なら分裂しない。 */
        int splitCount,
        /** 燃焼耐性 0.0〜1.0。1.0 で完全耐性。 */
        double burnResist,
        /** 倒れたときに出発点へ戻れる回数。もう一周させるだけで、それ自体の罰はない。 */
        int revives) {

    public static final Trait NONE = new Trait(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * 無力化を掛け直す間隔（tick）。戦場はこの値で {@code applyDisablers} を回す。
     * 説明文に「何秒ごとに効くのか」を書くために、能力側の定数として持たせている。
     */
    public static final int DISABLE_REFRESH_TICKS = 10;

    /** タワーを黙らせる。 */
    public static Trait sapper(double radius, int ticks) {
        return new Trait(radius, ticks, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 被弾すると半径 {@code radius} の中で最もコア寄りの経路へ跳ぶ。 */
    public static Trait blink(double radius, int cooldown) {
        return new Trait(0, 0, radius, cooldown, 0, 0, 0, 0, 0);
    }

    /** 周囲の味方の被ダメージを減らす。 */
    public static Trait ward(double radius, double reduction) {
        return new Trait(0, 0, 0, 0, radius, reduction, 0, 0, 0);
    }

    /** 倒れると分裂する。 */
    public static Trait split(int count) {
        return new Trait(0, 0, 0, 0, 0, 0, count, 0, 0);
    }

    /** 燃えない。 */
    public static Trait fireproof() {
        return new Trait(0, 0, 0, 0, 0, 0, 0, 1.0, 0);
    }

    /** 倒れても出発点へ戻る。 */
    public static Trait reaper(int revives) {
        return new Trait(0, 0, 0, 0, 0, 0, 0, 0, revives);
    }

    public boolean disables() {
        return disableRadius > 0 && disableTicks > 0;
    }

    public boolean blinks() {
        return blinkRadius > 0;
    }

    public boolean wards() {
        return wardRadius > 0 && wardReduction > 0;
    }

    public boolean splits() {
        return splitCount > 0;
    }

    public boolean hasRevive() {
        return revives > 0;
    }

    /**
     * 名札に出す短い能力表示。説明文と違い、頭上に常時出るので数語に収める。
     *
     * @return 能力を持たないなら空文字
     */
    public String tag() {
        if (disables()) {
            return String.format("妨害R%.1f/%.1f秒", disableRadius, disableTicks / 20.0);
        }
        if (blinks()) {
            return String.format("瞬移R%.1f", blinkRadius);
        }
        if (wards()) {
            return String.format("庇護R%.1f", wardRadius);
        }
        if (splits()) {
            return "分裂x" + splitCount;
        }
        if (burnResist >= 1.0) {
            return "不燃";
        }
        if (hasRevive()) {
            return "復活x" + revives;
        }
        return "";
    }

    /**
     * 能力の説明文。半径・持続・間隔まで数字で書く。
     *
     * <p>「撃たれるたび跳ぶ」「塔を黙らせる」だけでは、対策を組めるだけの情報にならない。
     * どれだけの範囲に、何秒、どの間隔で効くのかが分かって初めて
     * 「あと 1 基ずらす」「あと 1 段上げる」の判断ができる。</p>
     *
     * @return 能力を持たないなら空文字
     */
    public String summary() {
        if (disables()) {
            return String.format("半径 %.1f のタワーを %.1f 秒黙らせる（範囲内なら %.1f 秒ごとに掛け直す）",
                    disableRadius, disableTicks / 20.0, DISABLE_REFRESH_TICKS / 20.0);
        }
        if (blinks()) {
            return String.format("被弾すると半径 %.1f の中で最もコア寄りの経路へ跳ぶ／壁を跨ぐ（間隔 %.1f 秒）",
                    blinkRadius, blinkCooldown / 20.0);
        }
        if (wards()) {
            return String.format("半径 %.1f の味方の被ダメージ -%.0f%%", wardRadius, wardReduction * 100);
        }
        if (splits()) {
            return "倒れるとその場で小さいスライム " + splitCount + " 体に分かれる";
        }
        if (burnResist >= 1.0) {
            return "燃えない（炎の継続ダメージが通らない）";
        }
        if (hasRevive()) {
            return "倒れても " + revives + " 回だけ出発点へ戻る（もう一周させられる）";
        }
        return "";
    }
}
