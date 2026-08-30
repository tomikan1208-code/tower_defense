package dev.antigravity.mazeward.enemy;

/** 敵の移動様式。 */
public enum MoveMode {

    /** 迷路に従って歩く。A* の経路をたどる。 */
    GROUND,

    /**
     * 迷路を完全に無視してスポーンからコアへ直線移動する。
     * 「経路を伸ばすだけ」の構成を成立させないための、設計上いちばん重要な敵。
     */
    FLYING
}
