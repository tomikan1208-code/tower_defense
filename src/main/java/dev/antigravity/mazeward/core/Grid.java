package dev.antigravity.mazeward.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 盤面。セル種別・スポーン・コアだけを持ち、Minecraft のことは何も知らない。
 *
 * <p>「置けるか」「置いたら詰むか」の判定を全部ここに閉じ込めてある。
 * 将来「完全封鎖を許可して、敵が壁を殴って壊す」ルールに差し替えたくなったら
 * {@link #checkPlacement} の {@link Placement#WOULD_BLOCK} を返している箇所だけ変えればよい。</p>
 */
public final class Grid {

    /** 障害物カードを置けるかどうかの判定結果。 */
    public enum Placement {
        OK(null),
        OUT_OF_BOUNDS("アリーナの外です"),
        OCCUPIED("すでに何かが置かれています"),
        WOULD_BLOCK("敵の経路が完全に塞がれます");

        private final String reason;

        Placement(String reason) {
            this.reason = reason;
        }

        public boolean ok() {
            return this == OK;
        }

        public String reason() {
            return reason;
        }
    }

    private static final int[] STEP_DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] STEP_DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

    private final int width;
    private final int height;
    private final CellType[] cells;
    private final List<Vec2i> spawns = new ArrayList<>();
    private final List<Vec2i> coreCells = new ArrayList<>();

    public Grid(int width, int height) {
        this.width = width;
        this.height = height;
        this.cells = new CellType[width * height];
        Arrays.fill(this.cells, CellType.OPEN);
    }

    private Grid(Grid source) {
        this.width = source.width;
        this.height = source.height;
        this.cells = source.cells.clone();
        this.spawns.addAll(source.spawns);
        this.coreCells.addAll(source.coreCells);
    }

    public Grid copy() {
        return new Grid(this);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean inBounds(Vec2i pos) {
        return inBounds(pos.x(), pos.z());
    }

    public boolean inBounds(int x, int z) {
        return x >= 0 && z >= 0 && x < width && z < height;
    }

    public CellType get(Vec2i pos) {
        return get(pos.x(), pos.z());
    }

    public CellType get(int x, int z) {
        if (!inBounds(x, z)) {
            return CellType.BORDER;
        }
        return cells[z * width + x];
    }

    public void set(Vec2i pos, CellType type) {
        set(pos.x(), pos.z(), type);
    }

    public void set(int x, int z, CellType type) {
        if (!inBounds(x, z)) {
            return;
        }
        cells[z * width + x] = type;
    }

    public boolean walkable(Vec2i pos) {
        return get(pos).walkable();
    }

    public boolean walkable(int x, int z) {
        return get(x, z).walkable();
    }

    public boolean towerBase(Vec2i pos) {
        return get(pos).towerBase();
    }

    public boolean buildable(Vec2i pos) {
        return get(pos).buildable();
    }

    // ---------------------------------------------------------------- spawn / core

    public void addSpawn(Vec2i pos) {
        set(pos, CellType.SPAWN);
        spawns.add(pos);
    }

    public List<Vec2i> spawns() {
        return Collections.unmodifiableList(spawns);
    }

    /** コアは 2x2。指定セルを左上として 4 セルを CORE にする。 */
    public void setCore(Vec2i topLeft) {
        coreCells.clear();
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                Vec2i cell = topLeft.add(dx, dz);
                set(cell, CellType.CORE);
                coreCells.add(cell);
            }
        }
    }

    public List<Vec2i> coreCells() {
        return Collections.unmodifiableList(coreCells);
    }

    public Vec2i coreCenter() {
        if (coreCells.isEmpty()) {
            return new Vec2i(width / 2, height / 2);
        }
        return coreCells.get(0);
    }

    /** コアの中心座標（2x2 の真ん中）。描画・飛行敵の目標に使う。 */
    public double coreCenterX() {
        return coreCells.isEmpty() ? width / 2.0 : coreCells.get(0).x() + 1.0;
    }

    public double coreCenterZ() {
        return coreCells.isEmpty() ? height / 2.0 : coreCells.get(0).z() + 1.0;
    }

    // ---------------------------------------------------------------- 経路

    /**
     * 斜め移動を含む 1 歩が可能か。
     *
     * <p>斜めに進むには <b>両隣のセルが空いている</b> 必要がある。
     * 敵の当たり判定には幅があるので、障害物の角を斜めにかすめる移動を許すと
     * 壁にめり込んで見えてしまう。この制約があるおかげで、
     * 壁の角を回るときは必ずその隣の空きセル（＝曲がり角）を経由することになる。</p>
     *
     * <p>副次的に「斜めに並べた壁は隙間なく塞がる」ことも保証されるので、
     * 迷路として成立する。</p>
     */
    public boolean canStep(int x, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (!inBounds(nx, nz) || !walkable(nx, nz)) {
            return false;
        }
        if (dx != 0 && dz != 0) {
            return walkable(x + dx, z) && walkable(x, z + dz);
        }
        return true;
    }

    public PathResult pathFrom(Vec2i spawn) {
        return PathFinder.find(this, spawn, coreCells);
    }

    /**
     * 到達可能かどうかだけを幅優先で調べる軽量版。
     *
     * <p>障害物カードの配置判定は「置いたら詰むか」を毎回確認するので非常に高頻度で呼ばれる。
     * 経路の形は要らないので、ここでは Theta* ではなく単純な塗りつぶしを使う。</p>
     */
    public boolean reachable(Vec2i from, Collection<Vec2i> goals) {
        if (!inBounds(from) || !walkable(from) || goals.isEmpty()) {
            return false;
        }
        boolean[] visited = new boolean[width * height];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int startIndex = from.z() * width + from.x();
        visited[startIndex] = true;
        queue.add(startIndex);

        boolean[] isGoal = new boolean[width * height];
        for (Vec2i goal : goals) {
            if (inBounds(goal)) {
                isGoal[goal.z() * width + goal.x()] = true;
            }
        }

        while (!queue.isEmpty()) {
            int index = queue.poll();
            if (isGoal[index]) {
                return true;
            }
            int x = index % width;
            int z = index / width;
            for (int i = 0; i < STEP_DX.length; i++) {
                int dx = STEP_DX[i];
                int dz = STEP_DZ[i];
                if (!canStep(x, z, dx, dz)) {
                    continue;
                }
                int neighbour = (z + dz) * width + (x + dx);
                if (!visited[neighbour]) {
                    visited[neighbour] = true;
                    queue.add(neighbour);
                }
            }
        }
        return false;
    }

    /** 全スポーンからコアへ到達できるか。 */
    public boolean allSpawnsConnected() {
        for (Vec2i spawn : spawns) {
            if (!reachable(spawn, coreCells)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 設置

    /**
     * 障害物カードを置けるか判定する。
     * 「置いた結果、どこかのスポーンからコアへ行けなくなる」場合は {@link Placement#WOULD_BLOCK}。
     */
    public Placement checkPlacement(Shape shape, Vec2i origin, Rot rot) {
        List<Vec2i> target = shape.cellsAt(origin, rot);
        for (Vec2i cell : target) {
            if (!inBounds(cell)) {
                return Placement.OUT_OF_BOUNDS;
            }
            if (!get(cell).buildable()) {
                return Placement.OCCUPIED;
            }
        }

        // 仮置きして経路を検証し、必ず元に戻す。
        for (Vec2i cell : target) {
            set(cell, CellType.WALL);
        }
        boolean connected = allSpawnsConnected();
        for (Vec2i cell : target) {
            set(cell, CellType.OPEN);
        }

        return connected ? Placement.OK : Placement.WOULD_BLOCK;
    }

    /** 実際に壁として確定させる。呼ぶ前に {@link #checkPlacement} を通すこと。 */
    public void place(Shape shape, Vec2i origin, Rot rot) {
        for (Vec2i cell : shape.cellsAt(origin, rot)) {
            set(cell, CellType.WALL);
        }
    }

    /**
     * 仮に置いた状態の経路を返す（プレビュー用）。盤面は変更しない。
     * 置けない配置なら null。
     */
    public List<PathResult> previewPaths(Shape shape, Vec2i origin, Rot rot) {
        List<Vec2i> target = shape.cellsAt(origin, rot);
        for (Vec2i cell : target) {
            if (!inBounds(cell) || !get(cell).buildable()) {
                return null;
            }
        }
        for (Vec2i cell : target) {
            set(cell, CellType.WALL);
        }
        List<PathResult> results = new ArrayList<>(spawns.size());
        boolean allReachable = true;
        for (Vec2i spawn : spawns) {
            PathResult result = PathFinder.find(this, spawn, coreCells);
            results.add(result);
            allReachable &= result.reachable();
        }
        for (Vec2i cell : target) {
            set(cell, CellType.OPEN);
        }
        return allReachable ? results : null;
    }

    /** タワーの形状がその場所に置けるか（全セルが壁か岩で、かつ空いているか）。 */
    public boolean isTowerBaseFor(Shape shape, Vec2i origin, Rot rot) {
        for (Vec2i cell : shape.cellsAt(origin, rot)) {
            if (!inBounds(cell) || !get(cell).towerBase()) {
                return false;
            }
        }
        return true;
    }

    public int countOpenCells() {
        int count = 0;
        for (CellType cell : cells) {
            if (cell == CellType.OPEN) {
                count++;
            }
        }
        return count;
    }
}
