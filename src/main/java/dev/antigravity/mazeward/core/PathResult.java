package dev.antigravity.mazeward.core;

import java.util.List;

/**
 * 経路探索の結果。
 *
 * <p>グリッドのマス目を 1 つずつ並べたものではなく、
 * <b>「曲がり角」だけを並べた折れ線</b> を返す。区間と区間の間は直線移動になり、
 * 角度は 45° 単位に縛られない。</p>
 *
 * <p>各ウェイポイントは「障害物に接している通行可能セル」の座標で、
 * 実際の通過点はそのセルの中心 (x + 0.5, z + 0.5)。</p>
 *
 * @param waypoints スポーンからゴールまでの曲がり角（セル座標。中心を通る）
 * @param length    実際の移動距離（ユークリッド長。マス数ではない）
 * @param reachable ゴールに到達できるか
 */
public record PathResult(List<Vec2i> waypoints, double length, boolean reachable) {

    private static final PathResult UNREACHABLE = new PathResult(List.of(), 0.0, false);

    public static PathResult unreachable() {
        return UNREACHABLE;
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    /** 曲がり角の数（始点と終点を除く）。 */
    public int turns() {
        return Math.max(0, waypoints.size() - 2);
    }

    public Vec2i start() {
        return waypoints.isEmpty() ? null : waypoints.get(0);
    }

    public Vec2i end() {
        return waypoints.isEmpty() ? null : waypoints.get(waypoints.size() - 1);
    }
}
