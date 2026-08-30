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
 *   <li>{@code revives} — 「倒しさえすれば損はない」を崩す</li>
 * </ul>
 */
public record Trait(
        /** タワー無力化の半径（ブロック）。0 なら無し。 */
        double disableRadius,
        /** 無力化が続く tick 数。 */
        int disableTicks,
        /** 被弾したとき経路上を前へ飛ぶ距離（ブロック）。0 なら無し。 */
        double blinkDistance,
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
        /** 倒れたときに出発点へ戻れる回数。戻るたびに守り手のライフ上限が 1 減る。 */
        int revives) {

    public static final Trait NONE = new Trait(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** タワーを黙らせる。 */
    public static Trait sapper(double radius, int ticks) {
        return new Trait(radius, ticks, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 被弾すると前へ飛ぶ。 */
    public static Trait blink(double distance, int cooldown) {
        return new Trait(0, 0, distance, cooldown, 0, 0, 0, 0, 0);
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
        return blinkDistance > 0;
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
}
