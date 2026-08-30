package dev.antigravity.mazeward.tower;

/**
 * その塔が射程内の誰を狙うか。
 *
 * <p>全部の塔が「コアにいちばん近い敵」を撃つと、<b>並べる塔が違っても弾は同じ 1 体に集まる</b>。
 * 溶けかけの敵に 9 種類が overkill を重ね、後ろの列は素通りする。
 * 狙い方を塔ごとに変えると、同じ場所に並べても仕事が分かれる。</p>
 *
 * <p>狙い方は性能表に出す。見えない優先度は、配置の判断材料にならないただの乱数に見えてしまう。</p>
 */
public enum Targeting {

    /** コアにいちばん近い敵。漏らさないことを最優先する、TD の定番。 */
    FIRST("コアに近い敵", "漏れそうな先頭から削る"),

    /** 効果（減速・燃焼・呪い）がまだ乗っていない敵。かけ直しの無駄を無くす。 */
    UNAFFECTED("効果が切れている敵", "同じ敵に重ねがけせず、掛かっていない敵から凍らせる"),

    /** 残り HP がいちばん多い敵。一撃の重い塔を、軽い敵に浪費させない。 */
    TOUGHEST("いちばん硬い敵", "重い一撃を、雑魚ではなく本命に当てる"),

    /** 周りに敵がいちばん多い敵。範囲・連鎖の巻き込みを最大にする。 */
    DENSEST("敵が密集している所", "巻き込みがいちばん増える一体を狙う"),

    /** 塔からいちばん遠い敵。長射程を、近づかれる前に使い切る。 */
    FARTHEST("いちばん遠い敵", "長い射程を、届くうちに使い切る"),

    /** 狙う相手を持たない（範囲効果・支援）。 */
    NONE("狙わない", "射程内すべてに効く");

    private final String displayName;
    private final String description;

    Targeting(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
