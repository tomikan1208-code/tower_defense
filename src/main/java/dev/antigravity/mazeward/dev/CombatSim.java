package dev.antigravity.mazeward.dev;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.run.Roadmap;
import dev.antigravity.mazeward.run.Rune;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.stage.Phase;
import dev.antigravity.mazeward.stage.Stage;
import dev.antigravity.mazeward.stage.StageGenerator;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.TowerKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;

/**
 * 戦闘ループのヘッドレス統合検証。
 *
 * <p>Minecraft クライアントを繋がずに、実際の {@link Stage} を動かして
 * 「迷路を組む → ウェーブを回す → 敵が湧いて歩いて死ぬ / 漏れる」までを通す。
 * 疑似プレイヤーは「経路がいちばん伸びる置き方」を貪欲に選ぶので、
 * 経路プレビュー API もそのまま試されることになる。</p>
 *
 * <p>{@code gradle combatSim} で実行。</p>
 */
public final class CombatSim {

    private static final int MAX_COMBAT_TICKS = 20 * 60 * 6;

    private static int failures;
    private static int totalMidCombatCards;

    private CombatSim() {
    }

    public static void main(String[] args) {
        MinecraftServer.init();

        checkRunes();

        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        int[] reached = new int[Roadmap.LAYERS + 1];
        int cleared = 0;
        for (int i = 0; i < runs; i++) {
            int depth = simulateFullRun(2000 + i * 137L, runs <= 3);
            reached[Math.min(depth, Roadmap.LAYERS)]++;
            if (depth >= Roadmap.LAYERS) {
                cleared++;
            }
            System.out.println();
        }

        System.out.println("=== 難易度カーブ (" + runs + " ラン) ===");
        for (int layer = 1; layer <= Roadmap.LAYERS; layer++) {
            System.out.println("  第" + layer + "層で終了: " + reached[layer]);
        }
        System.out.println("  完全踏破: " + cleared + " / " + runs);
        System.out.println("  戦闘中に設置した障害物: " + totalMidCombatCards + " 枚"
                + "（すべて敵の経路を引き直して検証済み）");
        System.out.println();
        if (failures == 0) {
            System.out.println("[OK] 戦闘シミュレーション完了");
            System.exit(0);
        }
        System.out.println("[FAIL] 不整合 " + failures + " 件");
        System.exit(1);
    }

    /**
     * ルーン（特殊障害物）が実際に効いているかを確かめる。
     *
     * <p>土台系は「同じタワーでもルーン付きの壁に乗せると実効値が上がる」ことを、
     * 地形系は「そばを通る敵が実際に削れる／遅くなる」ことを、
     * それぞれ実際の {@link Stage} を動かして確認する。</p>
     */
    private static void checkRunes() {
        System.out.println("== ルーン（特殊障害物）の検証 ==");

        InstanceContainer instance =
                MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        instance.setGenerator(unit -> {
        });

        RunState run = new RunState(20260830L);
        // ステージ生成前にデッキ全体へルーンを付ける（手札に必ずルーン付きが来るように）
        for (int i = 0; i < run.deck().librarySize(); i++) {
            run.deck().applyRune(i, i % 2 == 0 ? Rune.REINFORCED : Rune.SPIKE);
        }
        StageGenerator.Result generated =
                StageGenerator.generate(1, Roadmap.NodeKind.BATTLE, 20260830L);
        Stage stage = new Stage(instance, generated, run, (st, victory) -> {
        });
        // Stage の生成でゴールドが配り直されるので、検証用の追加はそのあとで
        run.addGold(2000);

        // 手札のルーン付きカードを、経路の近くに置けるだけ置く
        Random random = new Random(7);
        int runedPlaced = 0;
        while (!run.deck().hand().isEmpty()) {
            if (buildMaze(stage, run, random, 1) == 0) {
                break;
            }
            runedPlaced++;
        }
        assertSim(runedPlaced > 0, "ルーン付きカードを 1 枚も置けなかった");

        // 補強ルーンの上に乗ったタワーは実効攻撃力が上がっているはず
        int boosted = 0;
        buyTowers(stage, run);
        for (TowerInstance tower : stage.towers()) {
            boolean reinforced = stage.runesUnder(tower).contains(Rune.REINFORCED);
            double base = tower.stats().damage();
            double resolved = stage.resolvedStats(tower).damage();
            if (reinforced) {
                assertSim(resolved > base + 1e-6,
                        "補強ルーンの上なのに攻撃力が上がっていない");
                boosted++;
            } else {
                assertSim(Math.abs(resolved - base) < 1e-6,
                        "ルーンがないのに攻撃力が変わっている");
            }
        }
        System.out.printf("  ルーン付きカード %d 枚を配置  タワー %d 基（うち補強の上 %d 基）%n",
                runedPlaced, stage.towers().size(), boosted);
        assertSim(boosted > 0, "補強ルーンの上に乗ったタワーが 1 基もない（配置の検証にならない）");

        // 棘ルーンの近くを通る敵は、タワーがなくても削れるはず
        stage.startWave();
        for (int t = 0; t < 20 * 30 && stage.phase() == Phase.COMBAT; t++) {
            stage.tick();
            assertSim(groundEnemiesOnWalkableCells(stage), "敵がルーン壁の中に入り込んでいる");
        }
        System.out.printf("  棘ルーンを含む盤面でウェーブを 30 秒進行  残敵 %d%n",
                stage.remainingEnemies());

        stage.dispose();
        MinecraftServer.getInstanceManager().unregisterInstance(instance);
        System.out.println();
    }

    private static void assertSim(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    /**
     * ラン全体を通しで回し、到達層を返す。
     * 実際のロードマップからノードを選ぶので、商店・祭壇で立て直す選択肢も含まれる。
     */
    private static int simulateFullRun(long seed, boolean verbose) {
        RunState run = new RunState(seed);
        Random random = new Random(seed);
        System.out.println("######## ラン seed=" + seed + " ########");

        for (int layer = 1; layer <= Roadmap.LAYERS; layer++) {
            // 実際のプレイヤーと同じく、直前のノードから辺が伸びている先だけを選ぶ
            List<Roadmap.Node> choices = run.currentChoices();
            if (choices.isEmpty()) {
                System.out.println("  ✖ 第" + layer + "層で進める先が無い");
                return layer;
            }
            Roadmap.Node node = choices.get(random.nextInt(choices.size()));

            if (!node.kind().combat()) {
                applyNonCombatNode(run, random, node);
                System.out.printf("  · 第%d層 %s  コア %d/%d  エンバー %d  デッキ %d枚%n",
                        layer, node.kind().displayName(), run.coreHp(), run.maxCoreHp(),
                        run.ember(), run.deck().librarySize());
                run.advanceLayer(node.index());
                continue;
            }

            StageResult result = simulate(run, random, layer, node.kind(), seed * 31 + layer, verbose);
            if (!result.victory()) {
                System.out.println("  ✖ 第" + layer + "層 " + node.kind().displayName() + " で陥落");
                return layer;
            }
            run.addEmber(run.emberRewardFor(node.kind(), layer));
            System.out.printf("  ✔ 第%d層 %s 制圧  コア %d/%d  エンバー %d  タワー %d基  移動距離 %.1f"
                            + "  デッキ %d枚(ルーン %d)%n",
                    layer, node.kind().displayName(), run.coreHp(), run.maxCoreHp(), run.ember(),
                    result.towers(), result.pathLength(),
                    run.deck().librarySize(), run.deck().runedCount());
            grantReward(run, random, layer);
            run.advanceLayer(node.index());
        }
        System.out.println("  ★ 踏破");
        return Roadmap.LAYERS;
    }

    /** 商店・祭壇ノードの効果を疑似的に適用する（弱っていれば回復、余裕があれば強化）。 */
    private static void applyNonCombatNode(RunState run, Random random, Roadmap.Node node) {
        boolean hurt = run.coreHp() <= run.maxCoreHp() / 2;
        if (node.kind() == Roadmap.NodeKind.EVENT) {
            // イベントは「賭ける」ノード。ここではルーン刻印を選んだ想定で回す。
            if (run.deck().hasPlainCard()) {
                int index = random.nextInt(run.deck().librarySize());
                for (int i = 0; i < run.deck().librarySize(); i++) {
                    int candidate = (index + i) % run.deck().librarySize();
                    if (run.deck().applyRune(candidate, Rune.random(random))) {
                        return;
                    }
                }
            }
            run.addEmber(95);
            return;
        }
        if (node.kind() == Roadmap.NodeKind.ALTAR) {
            if (hurt) {
                run.healCore(8);
            } else {
                run.deck().increaseHandSize(1);
            }
            return;
        }
        if (hurt && run.spendEmber(70)) {
            run.healCore(5);
        }
        var relic = dev.antigravity.mazeward.run.Relic.randomMissing(run.relics(), random);
        if (relic != null && run.spendEmber(130)) {
            run.grant(relic);
        }
        TowerKind locked = run.randomLockedTower();
        if (locked != null && run.spendEmber(110)) {
            run.unlock(locked);
        }
        if (run.spendEmber(45)) {
            run.deck().addShape(dev.antigravity.mazeward.core.Shapes.random(random));
        }
    }

    /** ステージ間の報酬を疑似的に選ぶ（カード → タワー解放 → レリック の順で回す）。 */
    private static void grantReward(RunState run, Random random, int layer) {
        switch (layer % 3) {
            case 1 -> {
                run.deck().addShape(dev.antigravity.mazeward.core.Shapes.random(random));
                run.deck().addShape(dev.antigravity.mazeward.core.Shapes.random(random));
            }
            case 2 -> {
                TowerKind locked = run.randomLockedTower();
                if (locked != null) {
                    run.unlock(locked);
                } else {
                    run.addEmber(70 + layer * 20);
                }
            }
            default -> {
                var relic = dev.antigravity.mazeward.run.Relic.randomMissing(run.relics(), random);
                if (relic != null) {
                    run.grant(relic);
                } else if (run.deck().hasPlainCard()) {
                    for (int i = 0; i < run.deck().librarySize(); i++) {
                        if (run.deck().applyRune(i, Rune.random(random))) {
                            break;
                        }
                    }
                } else {
                    run.addEmber(70 + layer * 20);
                }
            }
        }
    }

    private record StageResult(boolean victory, int towers, double pathLength) {
    }

    /** 地上の敵が全員、通行可能なセルの上にいるか（壁をすり抜けていないか）。 */
    private static boolean groundEnemiesOnWalkableCells(Stage stage) {
        for (var enemy : stage.enemies()) {
            if (enemy.kind().flying()) {
                continue;
            }
            var pos = enemy.position();
            Vec2i cell = stage.arena().toCell(pos.x(), pos.z());
            if (stage.grid().inBounds(cell) && !stage.grid().walkable(cell)) {
                return false;
            }
        }
        return true;
    }

    private static StageResult simulate(RunState run, Random random, int layer,
                                        Roadmap.NodeKind kind, long seed, boolean verbose) {
        InstanceContainer instance =
                MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        instance.setGenerator(unit -> {
        });

        StageGenerator.Result generated = StageGenerator.generate(layer, kind, seed);
        boolean[] ended = {false};
        boolean[] won = {false};

        Stage stage = new Stage(instance, generated, run, (s, victory) -> {
            ended[0] = true;
            won[0] = victory;
        });

        if (verbose) {
            System.out.println("=== " + generated.config().title()
                    + "  " + stage.grid().width() + "x" + stage.grid().height()
                    + "  wave数=" + stage.waveCount()
                    + String.format("  初期移動距離 %.1f", stage.totalPathLength())
                    + "  所持 " + run.gold() + "G ===");
        }

        int guard = 0;
        while (!ended[0] && guard++ < 40) {
            if (stage.phase() == Phase.BUILD) {
                double before = stage.totalPathLength();
                // 手札を 2 枚残しておき、戦闘中の増築を実際に走らせる
                int reserve = Math.max(1, run.deck().hand().size() - 2);
                int cards = buildMaze(stage, run, random, reserve);
                int towers = buyTowers(stage, run);
                if (verbose) {
                    System.out.printf("    [建築] W%d  カード %d 枚  タワー %d 基  移動距離 %.1f → %.1f  残金 %dG%n",
                            stage.waveNumber(), cards, towers, before, stage.totalPathLength(), run.gold());
                }

                Stage.Outcome start = stage.startWave();
                if (!start.success()) {
                    System.out.println("  [中断] " + start.message());
                    break;
                }
            }

            int combatTicks = 0;
            int peak = 0;
            int midCombatCards = 0;
            while (stage.phase() == Phase.COMBAT && combatTicks < MAX_COMBAT_TICKS && !ended[0]) {
                stage.tick();
                peak = Math.max(peak, stage.aliveEnemies());
                combatTicks++;

                // 戦闘中の増築。経路の引き直しが正しく効いているかをここで実際に走らせる。
                if (combatTicks % 50 == 0 && !run.deck().hand().isEmpty()) {
                    midCombatCards += buildMaze(stage, run, random, 1);
                }
                if (!groundEnemiesOnWalkableCells(stage)) {
                    System.out.println("  [FAIL] 敵が通行不可セルの上にいる（経路の引き直し漏れ）");
                    failures++;
                }
            }
            totalMidCombatCards += midCombatCards;
            if (verbose) {
                System.out.printf("    [戦闘] %d tick (%.1f 秒)  最大同時敵数 %d  戦闘中の増築 %d 枚"
                                + "  コア %d/%d  所持 %dG%n",
                        combatTicks, combatTicks / 20.0, peak, midCombatCards,
                        run.coreHp(), run.maxCoreHp(), run.gold());
            }

            if (combatTicks >= MAX_COMBAT_TICKS) {
                System.out.println("  [FAIL] ウェーブが終わらない（敵が進まない可能性）");
                break;
            }
        }

        StageResult result = new StageResult(won[0], stage.towers().size(), stage.totalPathLength());
        stage.dispose();
        MinecraftServer.getInstanceManager().unregisterInstance(instance);
        return result;
    }

    /**
     * 疑似プレイヤー: 人間と同じように「経路の近く」だけを候補にして、
     * 経路がいちばん伸びる置き方を選ぶ。伸びない場合はキルゾーンの土台として置く。
     */
    private static int buildMaze(Stage stage, RunState run, Random random) {
        return buildMaze(stage, run, random, Integer.MAX_VALUE);
    }

    private static int buildMaze(Stage stage, RunState run, Random random, int maxCards) {
        int placed = 0;
        int guard = 0;
        while (guard++ < 40 && placed < maxCards) {
            List<BlockCard> hand = run.deck().hand();
            if (hand.isEmpty()) {
                break;
            }
            Shape shape = hand.get(0).shape();
            List<Vec2i> candidates = nearPathCells(stage, 3);
            java.util.Collections.shuffle(candidates, random);

            Vec2i bestOrigin = null;
            Rot bestRot = Rot.R0;
            int bestScore = Integer.MIN_VALUE;
            int examined = 0;

            for (Vec2i cursor : candidates) {
                if (examined > 260) {
                    break;
                }
                for (Rot rot : Rot.values()) {
                    Vec2i origin = stage.originFor(shape, cursor, rot);
                    Stage.PlacementPreview preview = stage.preview(shape, origin, rot);
                    examined++;
                    if (!preview.ok()) {
                        continue;
                    }
                    int score = (int) Math.round(preview.delta() * 40)
                            + adjacentWalls(stage.grid(), shape, origin, rot);
                    if (score > bestScore) {
                        bestScore = score;
                        bestOrigin = origin;
                        bestRot = rot;
                    }
                }
            }

            if (bestOrigin == null) {
                break;
            }
            if (!stage.placeCard(0, bestOrigin, bestRot).success()) {
                break;
            }
            placed++;
        }
        return placed;
    }

    /** 現在の経路から distance 以内の空きセル。 */
    private static List<Vec2i> nearPathCells(Stage stage, int distance) {
        Grid grid = stage.grid();
        java.util.Set<Vec2i> set = new java.util.LinkedHashSet<>();
        for (var path : stage.paths()) {
            for (Vec2i cell : dev.antigravity.mazeward.core.PathFinder.traversedCells(path.waypoints())) {
                for (int dx = -distance; dx <= distance; dx++) {
                    for (int dz = -distance; dz <= distance; dz++) {
                        Vec2i candidate = cell.add(dx, dz);
                        if (grid.inBounds(candidate) && grid.buildable(candidate)) {
                            set.add(candidate);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** 既存の壁・岩にくっつけて置くほうが、まとまったタワー土台になりやすい。 */
    private static int adjacentWalls(Grid grid, Shape shape, Vec2i origin, Rot rot) {
        int count = 0;
        for (Vec2i cell : shape.cellsAt(origin, rot)) {
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (grid.get(cell.add(offset[0], offset[1])).towerBase()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 疑似プレイヤー: 経路のそばの土台に、安いタワーを数で並べる。 */
    private static int buyTowers(Stage stage, RunState run) {
        Grid grid = stage.grid();
        java.util.Set<Vec2i> pathCells = new java.util.HashSet<>();
        for (var path : stage.paths()) {
            pathCells.addAll(dev.antigravity.mazeward.core.PathFinder.traversedCells(path.waypoints()));
        }

        List<Vec2i> bases = new ArrayList<>();
        for (int x = 0; x < grid.width(); x++) {
            for (int z = 0; z < grid.height(); z++) {
                Vec2i cell = new Vec2i(x, z);
                if (!grid.get(cell).towerBase()) {
                    continue;
                }
                boolean useful = false;
                for (Vec2i pathCell : pathCells) {
                    if (cell.manhattan(pathCell) <= 3) {
                        useful = true;
                        break;
                    }
                }
                // 飛行敵は迷路を無視して直線で来るので、その線上もカバーしないと詰む。
                if (!useful && nearFlightLine(grid, cell)) {
                    useful = true;
                }
                if (useful) {
                    bases.add(cell);
                }
            }
        }

        int placed = 0;
        int guard = 0;
        while (guard++ < 60) {
            TowerKind kind = pickTower(stage, run, placed);
            if (kind == null) {
                break;
            }
            boolean any = false;
            for (Vec2i base : bases) {
                for (Rot rot : Rot.values()) {
                    if (stage.towerPlacementError(kind, base, rot) == null
                            && stage.placeTower(kind, base, rot).success()) {
                        placed++;
                        any = true;
                        break;
                    }
                }
                if (any) {
                    break;
                }
            }
            if (!any) {
                break;
            }
        }
        return placed;
    }

    /** スポーン → コアの直線から 2.5 セル以内か。 */
    private static boolean nearFlightLine(Grid grid, Vec2i cell) {
        double cx = cell.x() + 0.5;
        double cz = cell.z() + 0.5;
        for (Vec2i spawn : grid.spawns()) {
            double ax = spawn.x() + 0.5;
            double az = spawn.z() + 0.5;
            double bx = grid.coreCenterX();
            double bz = grid.coreCenterZ();
            double abx = bx - ax;
            double abz = bz - az;
            double lengthSq = abx * abx + abz * abz;
            if (lengthSq < 1e-9) {
                continue;
            }
            double t = Math.max(0, Math.min(1, ((cx - ax) * abx + (cz - az) * abz) / lengthSq));
            double dx = cx - (ax + abx * t);
            double dz = cz - (az + abz * t);
            if (Math.sqrt(dx * dx + dz * dz) <= 2.5) {
                return true;
            }
        }
        return false;
    }

    /**
     * 何を建てるか。
     *
     * <p>人間ほど上手くはないが、<b>支援・妨害の塔も必ず建つ</b> ようにしてある。
     * 建たないと、その塔の射撃処理がヘッドレス検証を一度も通らない。</p>
     */
    private static TowerKind pickTower(Stage stage, RunState run, int placedSoFar) {
        int existing = stage.towers().size();
        // 5 基に 1 基は減速、9 基に 1 基は範囲、11・13 基ごとに支援と妨害。あとは数を並べる。
        TowerKind preferred = existing % 5 == 4 ? TowerKind.FROST
                : existing % 9 == 8 ? TowerKind.CANNON
                : existing % 11 == 10 ? TowerKind.WATCHTOWER
                : existing % 13 == 12 ? TowerKind.HEXER
                : existing % 17 == 16 ? TowerKind.BANISHER
                : TowerKind.ARROW;
        if (run.isUnlocked(preferred) && run.gold() >= preferred.baseCost()) {
            return preferred;
        }
        if (run.gold() >= TowerKind.ARROW.baseCost()) {
            return TowerKind.ARROW;
        }
        return null;
    }
}
