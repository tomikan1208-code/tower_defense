package dev.antigravity.mazeward.core;

/** グリッド 1 セルの種別。 */
public enum CellType {

    /** 空き地。敵が歩ける。カードを置ける。 */
    OPEN(true, false, true),

    /** プレイヤーが置いた壁。敵は通れず、タワーの土台になる。 */
    WALL(false, true, false),

    /** 初期地形の岩。通れないがタワーの土台になる = 無料の壁。 */
    ROCK(false, true, false),

    /** 敵のスポーン地点。歩けるがカードは置けない。 */
    SPAWN(true, false, false),

    /** 守るべきコア。敵が到達すると HP が減る。 */
    CORE(true, false, false),

    /** アリーナ外周。通れず、タワーも置けない。 */
    BORDER(false, false, false);

    private final boolean walkable;
    private final boolean towerBase;
    private final boolean buildable;

    CellType(boolean walkable, boolean towerBase, boolean buildable) {
        this.walkable = walkable;
        this.towerBase = towerBase;
        this.buildable = buildable;
    }

    /** 地上の敵が通過できるか。 */
    public boolean walkable() {
        return walkable;
    }

    /** タワーの土台になれるか。 */
    public boolean towerBase() {
        return towerBase;
    }

    /** 障害物カードを置けるか。 */
    public boolean buildable() {
        return buildable;
    }
}
