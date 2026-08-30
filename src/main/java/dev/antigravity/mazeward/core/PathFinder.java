package dev.antigravity.mazeward.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 任意角度経路探索（Theta*）。
 *
 * <p>グリッド A* だと敵が「マス目に沿ってカクカク」動いてしまう。
 * このゲームは敵の経路そのものを見せるのが売りなので、
 * <b>角から角へ直線で、45°に限らない任意の角度で</b> 移動させたい。
 * そのために A* ではなく Theta* を使っている。</p>
 *
 * <p>Theta* は展開のたびに「親から直接見通せるか（line of sight）」を判定し、
 * 見通せるなら中間ノードを飛ばして親を引き継ぐ。結果として
 * <b>曲がり角は必ず「障害物に接している通行可能セル」の中心になる</b>。
 * 障害物の角そのものではなく、その隣の空きセルの中心が折れ点になる。</p>
 *
 * <pre>
 *   A* (4方向)              Theta* (任意角度)
 *   S─┐                     S
 *     │                      ＼
 *     └─┐                      ＼
 *       │                        ＼
 *       └─ G                       G
 * </pre>
 *
 * <h2>決定性</h2>
 * 配置プレビューの信頼性のため、同じ盤面からは必ず同じ経路が返る必要がある。
 * そのために近傍の展開順を固定し、優先度が同値のときは h → 挿入順で決着させている。
 *
 * <h2>角抜けの禁止</h2>
 * 斜め移動と見通し判定はどちらも「障害物の角を斜めにすり抜けない」ルールで統一している。
 * 敵の当たり判定には幅があるので、角をかすめる経路は壁にめり込んで見えてしまう。
 * このため、斜めに進むには両隣のセルが空いている必要がある。
 * 結果として、壁の角を回るときは必ずその隣の空きセル（＝曲がり角）を経由する。
 */
public final class PathFinder {

    /** 北 → 北東 → 東 → 南東 → 南 → 南西 → 西 → 北西。展開順を変えると経路の見た目が変わるので固定。 */
    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

    private static final double EPSILON = 1e-9;

    private PathFinder() {
    }

    public static PathResult find(Grid grid, Vec2i start, Vec2i goal) {
        return find(grid, start, List.of(goal));
    }

    public static PathResult find(Grid grid, Vec2i start, Collection<Vec2i> goals) {
        if (!grid.inBounds(start) || !grid.walkable(start) || goals.isEmpty()) {
            return PathResult.unreachable();
        }

        int width = grid.width();
        int height = grid.height();
        int cellCount = width * height;

        boolean[] isGoal = new boolean[cellCount];
        List<Vec2i> goalList = new ArrayList<>(goals.size());
        for (Vec2i goal : goals) {
            if (grid.inBounds(goal)) {
                isGoal[goal.z() * width + goal.x()] = true;
                goalList.add(goal);
            }
        }
        if (goalList.isEmpty()) {
            return PathResult.unreachable();
        }

        double[] gScore = new double[cellCount];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        int[] parent = new int[cellCount];
        Arrays.fill(parent, -1);
        boolean[] closed = new boolean[cellCount];

        int startIndex = start.z() * width + start.x();
        gScore[startIndex] = 0.0;
        parent[startIndex] = startIndex;

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble(Node::f)
                        .thenComparingDouble(Node::h)
                        .thenComparingLong(Node::seq));
        long sequence = 0;
        double startH = heuristic(start.x(), start.z(), goalList);
        open.add(new Node(startIndex, startH, startH, sequence++));

        while (!open.isEmpty()) {
            Node current = open.poll();
            int index = current.index();
            if (closed[index]) {
                continue;
            }
            closed[index] = true;

            if (isGoal[index]) {
                return rebuild(parent, index, width, gScore[index]);
            }

            int cx = index % width;
            int cz = index / width;
            int parentIndex = parent[index];
            int px = parentIndex % width;
            int pz = parentIndex / width;

            for (int direction = 0; direction < 8; direction++) {
                int dx = DX[direction];
                int dz = DZ[direction];
                if (!grid.canStep(cx, cz, dx, dz)) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                int neighbour = nz * width + nx;
                if (closed[neighbour]) {
                    continue;
                }

                // Theta* の肝: 親から直接見通せるなら、いま展開しているノードを飛ばして
                // 親から直線でつなぐ。これで経路が任意角度の直線になる。
                double tentative;
                int newParent;
                if (lineOfSight(grid, px, pz, nx, nz)) {
                    tentative = gScore[parentIndex] + distance(px, pz, nx, nz);
                    newParent = parentIndex;
                } else {
                    tentative = gScore[index] + distance(cx, cz, nx, nz);
                    newParent = index;
                }

                if (tentative < gScore[neighbour] - EPSILON) {
                    gScore[neighbour] = tentative;
                    parent[neighbour] = newParent;
                    double h = heuristic(nx, nz, goalList);
                    open.add(new Node(neighbour, tentative + h, h, sequence++));
                }
            }
        }

        return PathResult.unreachable();
    }

    /**
     * 2 つのセルの中心を結ぶ直線が、通行可能セルだけを通るか。
     *
     * <p>線分が触れるセルをすべて拾う「スーパーカバー」で走査する。
     * 線分がちょうど格子点（4 セルの角）を通るときは、
     * 斜めの隙間をすり抜けたことになるので、両隣が空いている場合だけ許可する。
     * これがないと、敵が壁の角にめり込んで見える。</p>
     */
    public static boolean lineOfSight(Grid grid, int x0, int z0, int x1, int z1) {
        if (!grid.walkable(x0, z0) || !grid.walkable(x1, z1)) {
            return false;
        }
        if (x0 == x1 && z0 == z1) {
            return true;
        }

        int dx = x1 - x0;
        int dz = z1 - z0;
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        int absX = Math.abs(dx);
        int absZ = Math.abs(dz);

        int x = x0;
        int z = z0;
        int error = absX - absZ;
        int doubleX = absX * 2;
        int doubleZ = absZ * 2;

        for (int remaining = 1 + absX + absZ; remaining > 0; remaining--) {
            if (!grid.walkable(x, z)) {
                return false;
            }
            if (error > 0) {
                x += stepX;
                error -= doubleZ;
            } else if (error < 0) {
                z += stepZ;
                error += doubleX;
            } else {
                // 格子点をちょうど通過する = 斜めに角を抜ける。両隣が空いていなければ不可。
                if (!grid.walkable(x + stepX, z) || !grid.walkable(x, z + stepZ)) {
                    return false;
                }
                x += stepX;
                z += stepZ;
                error -= doubleZ;
                error += doubleX;
                remaining--;
            }
        }
        return true;
    }

    public static boolean lineOfSight(Grid grid, Vec2i from, Vec2i to) {
        return lineOfSight(grid, from.x(), from.z(), to.x(), to.z());
    }

    /**
     * 折れ線が実際に通過するセルの一覧。
     * 描画やタワー配置の補助（経路の近くか判定する）で使う。
     */
    public static List<Vec2i> traversedCells(List<Vec2i> waypoints) {
        List<Vec2i> cells = new ArrayList<>();
        if (waypoints.isEmpty()) {
            return cells;
        }
        cells.add(waypoints.get(0));
        for (int i = 1; i < waypoints.size(); i++) {
            appendSegmentCells(cells, waypoints.get(i - 1), waypoints.get(i));
        }
        return cells;
    }

    private static void appendSegmentCells(List<Vec2i> out, Vec2i from, Vec2i to) {
        int stepX = Integer.signum(to.x() - from.x());
        int stepZ = Integer.signum(to.z() - from.z());
        int absX = Math.abs(to.x() - from.x());
        int absZ = Math.abs(to.z() - from.z());

        int x = from.x();
        int z = from.z();
        int error = absX - absZ;
        int doubleX = absX * 2;
        int doubleZ = absZ * 2;

        for (int remaining = 1 + absX + absZ; remaining > 0; remaining--) {
            Vec2i cell = new Vec2i(x, z);
            if (out.isEmpty() || !out.get(out.size() - 1).equals(cell)) {
                out.add(cell);
            }
            if (absX == 0 && absZ == 0) {
                return;
            }
            if (error > 0) {
                x += stepX;
                error -= doubleZ;
            } else if (error < 0) {
                z += stepZ;
                error += doubleX;
            } else {
                x += stepX;
                z += stepZ;
                error -= doubleZ;
                error += doubleX;
                remaining--;
            }
        }
    }

    /**
     * 2 つの折れ線を比べて <b>変化した区間だけ</b> を返す。
     *
     * <p>障害物を置いたときの予測経路は、実際には経路の一部しか変わらないことが多い。
     * 全体を赤で描くと「どこが変わったのか」がかえって読めなくなるので、
     * 前後の共通部分を取り除いて、変化した区間だけを描けるようにする。</p>
     *
     * <p>つなぎ目が浮かないよう、変化部分の前後 1 点ずつは残す。
     * 完全に同じなら空リスト（＝赤を一切描かない）。</p>
     */
    public static List<Vec2i> divergentSection(List<Vec2i> before, List<Vec2i> after) {
        if (after.isEmpty() || before.equals(after)) {
            return List.of();
        }
        int beforeSize = before.size();
        int afterSize = after.size();

        int prefix = 0;
        while (prefix < beforeSize && prefix < afterSize
                && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < beforeSize - prefix && suffix < afterSize - prefix
                && before.get(beforeSize - 1 - suffix).equals(after.get(afterSize - 1 - suffix))) {
            suffix++;
        }

        int from = Math.max(0, prefix - 1);
        int to = Math.min(afterSize, afterSize - suffix + 1);
        if (to <= from) {
            return List.of();
        }
        return List.copyOf(after.subList(from, to));
    }

    /** 折れ線の全長（セル中心を結んだユークリッド距離）。 */
    public static double polylineLength(List<Vec2i> waypoints) {
        double total = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            Vec2i a = waypoints.get(i - 1);
            Vec2i b = waypoints.get(i);
            total += distance(a.x(), a.z(), b.x(), b.z());
        }
        return total;
    }

    private static PathResult rebuild(int[] parent, int goalIndex, int width, double length) {
        List<Vec2i> reversed = new ArrayList<>();
        int index = goalIndex;
        while (true) {
            reversed.add(new Vec2i(index % width, index / width));
            int next = parent[index];
            if (next == index || next < 0) {
                break;
            }
            index = next;
        }
        List<Vec2i> waypoints = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            waypoints.add(reversed.get(i));
        }
        return new PathResult(List.copyOf(waypoints), length, true);
    }

    private static double distance(int x0, int z0, int x1, int z1) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double heuristic(int x, int z, List<Vec2i> goals) {
        double best = Double.POSITIVE_INFINITY;
        for (Vec2i goal : goals) {
            double dx = x - goal.x();
            double dz = z - goal.z();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private record Node(int index, double f, double h, long seq) {
    }
}
