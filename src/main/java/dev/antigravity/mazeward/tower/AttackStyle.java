package dev.antigravity.mazeward.tower;

/** 攻撃の当たり方。タワーを増やすときは、まずこの中から選ぶ。 */
public enum AttackStyle {

    /** 単体。弾が飛んで 1 体に当たる。 */
    SINGLE("単体"),

    /** 着弾点の周囲にダメージ。 */
    SPLASH("範囲"),

    /** 最初の対象から近くの敵へ連鎖する。蛇行迷路で経路が並ぶほど強い。 */
    CHAIN("連鎖"),

    /** 直線上の敵を貫く。射程が長く、飛行の直線ルートを撃ち抜くのに向く。 */
    PIERCE("貫通"),

    /** 射程内の経路セルを燃焼帯にする。弾を撃たず、範囲内の敵に継続ダメージ。 */
    AURA("経路効果"),

    /**
     * 敵を来た道へ押し戻す。
     *
     * <p>倒すのではなく <b>同じキルゾーンを何度も通させる</b> ための当て方。
     * 迷路の長さを実質的に伸ばすので、盤面が狭くなった終盤に効く。</p>
     */
    BANISH("送還"),

    /** 射程内の敵の被ダメージを増やす。自分では削らない。 */
    CURSE("呪詛"),

    /** 周囲のタワーを強化する。敵を狙わない。 */
    SUPPORT("支援");

    private final String displayName;

    AttackStyle(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
