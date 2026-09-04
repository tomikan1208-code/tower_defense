package dev.antigravity.mazeward.run;

import dev.antigravity.mazeward.tower.TowerKind;

/**
 * タワー性能への恒久補正。
 *
 * <p>シングルではレリックが値を持ち（{@link RunState} がそのまま実装している）、
 * 対戦では補正なし（{@link #NONE}）。戦場側はどちらか意識せずに済む。</p>
 */
public interface Modifiers {

    /** 補正なし。 */
    Modifiers NONE = new Modifiers() {
    };

    default double rangeBonus() {
        return 0.0;
    }

    default double slowBonus() {
        return 0.0;
    }

    default int chainBonus() {
        return 0;
    }

    default double splashBonus() {
        return 0.0;
    }

    default double burnMultiplier() {
        return 1.0;
    }

    default double upgradeCostMultiplier() {
        return 1.0;
    }

    /**
     * 塔を何段まで上げられるか。
     *
     * <p>シングルは 3 段。対戦だけ 5 段まで伸ばす（{@link TowerKind#VERSUS_MAX_LEVEL}）。
     * 対戦の収入は指数で伸びるので、受け皿が有限だと余ったコインが
     * 「相手を削る札」にしか流れず、経済ゲームが成立しなくなる。</p>
     */
    default int maxTowerLevel() {
        return TowerKind.MAX_LEVEL;
    }
}
