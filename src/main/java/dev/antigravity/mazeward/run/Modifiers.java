package dev.antigravity.mazeward.run;

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
}
