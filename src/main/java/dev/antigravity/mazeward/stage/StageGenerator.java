package dev.antigravity.mazeward.stage;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.Roadmap;
import dev.antigravity.mazeward.world.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ルールベースのステージ生成。完全ランダムではなく、
 * <b>生成 → 経路検証 → 不正なら再生成</b> を必ず通す。
 *
 * <p>検証項目:</p>
 * <ol>
 *   <li>全スポーンからコアへ到達できる</li>
 *   <li>初期経路が短すぎない（迷路を組む前から一直線だと面白くない）</li>
 *   <li>空きセルの比率が十分（迷路を組む余地がある）</li>
 *   <li>スポーンとコアが近すぎない</li>
 * </ol>
 */
public final class StageGenerator {

    private static final int MAX_ATTEMPTS = 64;

    /**
     * 生成結果。
     *
     * @param fallback 検証を通る盤面を作れず、安全な既定盤面に落ちた場合 true
     */
    public record Result(Grid grid, StageConfig config, List<Waves.WaveSpec> waves, boolean fallback) {
    }

    private StageGenerator() {
    }

    public static Result generate(int layer, Roadmap.NodeKind nodeKind, long seed) {
        Random random = new Random(seed);
        Palette.Theme theme = Palette.themeForLayer(layer);

        boolean elite = nodeKind == Roadmap.NodeKind.ELITE;
        boolean boss = nodeKind == Roadmap.NodeKind.BOSS;

        int size = 21 + ((layer - 1) / 2) * 2;
        if (elite) {
            size += 2;
        }
        if (boss) {
            size = 27;
        }
        size = Math.min(29, size);

        int spawnCount = boss ? 2 : (layer >= 3 ? (random.nextBoolean() ? 2 : 1) : 1);
        int waveCount = boss ? 8 : elite ? 6 : Math.min(7, 4 + layer / 2);
        double difficulty = (elite ? 1.22 : 1.0) * (boss ? 1.15 : 1.0);

        Grid grid = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Grid candidate = attemptLayout(size, size, spawnCount, layer, random);
            if (candidate != null) {
                grid = candidate;
                break;
            }
        }
        boolean fallback = grid == null;
        if (fallback) {
            grid = fallbackLayout(size, size, spawnCount);
        }

        String title = theme.name() + " ・ 第" + layer + "層 " + nodeKind.displayName();
        StageConfig config = new StageConfig(layer, nodeKind, title, theme, waveCount, difficulty, seed);
        List<Waves.WaveSpec> waves = Waves.generate(layer, nodeKind, waveCount, grid.spawns().size(), random);
        return new Result(grid, config, waves, fallback);
    }

    private static Grid attemptLayout(int width, int height, int spawnCount, int layer, Random random) {
        Grid grid = new Grid(width, height);

        // --- コア: 中央付近の 2x2 -------------------------------------------------
        int coreX = width / 2 - 1 + random.nextInt(3) - 1;
        int coreZ = height / 2 - 1 + random.nextInt(3) - 1;
        coreX = clamp(coreX, 3, width - 5);
        coreZ = clamp(coreZ, 3, height - 5);
        grid.setCore(new Vec2i(coreX, coreZ));

        // --- スポーン: 外周の別々の辺 --------------------------------------------
        List<Integer> edges = new ArrayList<>(List.of(0, 1, 2, 3));
        java.util.Collections.shuffle(edges, random);
        for (int i = 0; i < spawnCount; i++) {
            Vec2i spawn = edgeCell(edges.get(i), width, height, random);
            if (grid.get(spawn) != CellType.OPEN) {
                return null;
            }
            grid.addSpawn(spawn);
        }

        // --- 岩: 小さな塊を撒く（＝無料の壁であり、迷路の起点） -------------------
        int area = width * height;
        int rockBudget = (int) (area * (0.045 + 0.008 * Math.min(4, layer - 1)));
        int placed = 0;
        int guard = 0;
        while (placed < rockBudget && guard++ < rockBudget * 12) {
            int bx = 2 + random.nextInt(width - 4);
            int bz = 2 + random.nextInt(height - 4);
            int blobSize = 1 + random.nextInt(4);
            for (int i = 0; i < blobSize && placed < rockBudget; i++) {
                int cx = bx + random.nextInt(3) - 1;
                int cz = bz + random.nextInt(3) - 1;
                Vec2i cell = new Vec2i(cx, cz);
                if (!grid.inBounds(cell) || grid.get(cell) != CellType.OPEN) {
                    continue;
                }
                if (tooCloseToKeyCell(grid, cell, 2)) {
                    continue;
                }
                grid.set(cell, CellType.ROCK);
                placed++;
            }
        }

        return validate(grid, width, height) ? grid : null;
    }

    private static boolean validate(Grid grid, int width, int height) {
        if (grid.spawns().isEmpty()) {
            return false;
        }
        if (!grid.allSpawnsConnected()) {
            return false;
        }
        // 経路は任意角度の直線なので、長さの下限をマス数で語っても意味がない。
        // 「スポーンとコアが直線距離で十分離れているか」で迷路を組む余地を保証する。
        double minSeparation = Math.max(width, height) * 0.48;
        for (Vec2i spawn : grid.spawns()) {
            double dx = spawn.x() + 0.5 - grid.coreCenterX();
            double dz = spawn.z() + 0.5 - grid.coreCenterZ();
            if (Math.sqrt(dx * dx + dz * dz) < minSeparation) {
                return false;
            }
        }
        double openRatio = grid.countOpenCells() / (double) (width * height);
        return openRatio >= 0.78;
    }

    /**
     * どうしても生成に失敗したときの安全な盤面（岩なし）。
     * スポーンは隅寄りに置いて、フォールバックでも経路が極端に短くならないようにする。
     */
    private static Grid fallbackLayout(int width, int height, int spawnCount) {
        Grid grid = new Grid(width, height);
        grid.setCore(new Vec2i(width / 2 - 1, height / 2 - 1));
        grid.addSpawn(new Vec2i(2, 0));
        if (spawnCount > 1) {
            grid.addSpawn(new Vec2i(width - 3, height - 1));
        }
        return grid;
    }

    private static boolean tooCloseToKeyCell(Grid grid, Vec2i cell, int radius) {
        for (Vec2i spawn : grid.spawns()) {
            if (cell.manhattan(spawn) <= radius) {
                return true;
            }
        }
        for (Vec2i core : grid.coreCells()) {
            if (cell.manhattan(core) <= radius) {
                return true;
            }
        }
        return false;
    }

    private static Vec2i edgeCell(int edge, int width, int height, Random random) {
        return switch (edge) {
            case 0 -> new Vec2i(2 + random.nextInt(width - 4), 0);
            case 1 -> new Vec2i(width - 1, 2 + random.nextInt(height - 4));
            case 2 -> new Vec2i(2 + random.nextInt(width - 4), height - 1);
            default -> new Vec2i(0, 2 + random.nextInt(height - 4));
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
