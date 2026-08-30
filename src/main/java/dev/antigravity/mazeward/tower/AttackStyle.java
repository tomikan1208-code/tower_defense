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
    AURA("経路効果");

    private final String displayName;

    AttackStyle(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
