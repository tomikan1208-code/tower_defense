package dev.antigravity.mazeward.dev;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.PathFinder;
import dev.antigravity.mazeward.core.PathResult;
import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Shapes;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.run.Deck;
import dev.antigravity.mazeward.run.Roadmap;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.stage.StageGenerator;
import dev.antigravity.mazeward.stage.Waves;
import dev.antigravity.mazeward.world.Palette;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Minecraft クライアントなしで純粋ロジックを検証するヘッドレスチェック。
 *
 * <p>{@code gradle selfCheck} で実行する。ゲームの土台（盤面・任意角度の経路探索・
 * 配置判定・生成）が壊れていないことを、サーバを立てずに確認できるようにしてある。</p>
 */
public final class SelfCheck {

    private static int failures;

    private SelfCheck() {
    }

    public static void main(String[] args) {
        checkShapes();
        checkAnyAngle();
        checkPathDiff();
        checkPathDeterminism();
        checkBlockingRule();
        checkCardMaterials();
        checkRoadmapGraph();
        checkGeneration();
        checkWaves();
        printSampleStage();

        System.out.println();
        if (failures == 0) {
            System.out.println("[OK] すべてのチェックを通過しました");
        } else {
            System.out.println("[FAIL] 失敗 " + failures + " 件");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 形状

    private static void checkShapes() {
        section("形状カタログ");
        for (Shape shape : Shapes.all()) {
            int size = shape.size();
            for (Rot rot : Rot.values()) {
                assertTrue(shape.cells(rot).size() == size,
                        shape.id() + " の " + rot + " でセル数が変わった");
                for (Vec2i cell : shape.cells(rot)) {
                    assertTrue(cell.x() >= 0 && cell.z() >= 0,
                            shape.id() + " の " + rot + " が正規化されていない");
                }
            }
            System.out.printf("  %-7s %-5s %d マス  %dx%d%n",
                    shape.id(), shape.displayName(), size,
                    shape.width(Rot.R0), shape.height(Rot.R0));
        }
    }

    // ---------------------------------------------------------------- 任意角度

    /**
     * 「敵は角から角へ直線で動く。角度は 45° に限らない。
     * 曲がり角は障害物の角ではなく、障害物に接している通行可能セルの中心」
     * という要件をそのまま検証する。
     */
    private static void checkAnyAngle() {
        section("任意角度の経路 (Theta*)");

        // (1) 障害物が無ければ、スポーンからコアまで完全な一直線になる
        Grid open = new Grid(21, 21);
        open.setCore(new Vec2i(17, 9));
        Vec2i spawn = new Vec2i(0, 3);
        open.addSpawn(spawn);

        PathResult straight = open.pathFrom(spawn);
        assertTrue(straight.reachable(), "開けた盤面で到達できない");
        assertTrue(straight.turns() == 0,
                "障害物がないのに曲がっている: 折れ点 " + straight.turns() + " 個");
        int manhattan = spawn.manhattan(straight.end());
        System.out.printf("  障害物なし  折れ点 %d 個 / 実距離 %.2f （4方向移動なら %d）%n",
                straight.turns(), straight.length(), manhattan);
        assertTrue(straight.length() < manhattan - 0.5,
                "実距離が 4 方向移動と変わっていない = 斜めに動けていない");

        // (2) 45° 単位ではない角度が実際に出ているか
        assertTrue(hasNonOctileSegment(straight.waypoints()),
                "45° の倍数の区間しかない = 任意角度になっていない");
        System.out.println("  45°単位でない区間を確認（真の任意角度）");

        // (3) 障害物を置くと、その「隣の空きセル」で曲がる
        Grid blocked = open.copy();
        for (int x = 8; x <= 10; x++) {
            for (int z = 5; z <= 7; z++) {
                blocked.set(new Vec2i(x, z), CellType.ROCK);
            }
        }
        PathResult detour = blocked.pathFrom(spawn);
        assertTrue(detour.reachable(), "迂回できない");
        assertTrue(detour.turns() >= 1, "障害物があるのに曲がっていない");

        List<Vec2i> waypoints = detour.waypoints();
        for (int i = 1; i < waypoints.size() - 1; i++) {
            Vec2i turn = waypoints.get(i);
            assertTrue(blocked.get(turn).walkable(),
                    "曲がり角が通行不可セルにある: " + turn);
            assertTrue(touchesObstacle(blocked, turn),
                    "曲がり角が障害物に接していない: " + turn);
        }
        for (int i = 1; i < waypoints.size(); i++) {
            assertTrue(PathFinder.lineOfSight(blocked, waypoints.get(i - 1), waypoints.get(i)),
                    "区間が障害物を突き抜けている: " + waypoints.get(i - 1) + " → " + waypoints.get(i));
        }
        System.out.printf("  3x3 の岩を経路上に配置 → 折れ点 %d 個、すべて障害物に接した空きセル%n",
                detour.turns());
        System.out.println("  折れ線: " + waypoints);

        // (4) 斜めの壁は隙間なく塞がる（角抜けの禁止）
        Grid diagonal = new Grid(11, 11);
        diagonal.setCore(new Vec2i(8, 8));
        diagonal.addSpawn(new Vec2i(0, 0));
        for (int i = 0; i < 11; i++) {
            diagonal.set(new Vec2i(i, 10 - i), CellType.ROCK);
        }
        assertTrue(!diagonal.allSpawnsConnected(),
                "斜めに並べた壁を角抜けですり抜けられてしまう");
        System.out.println("  斜めに並べた壁は角抜けできず、きちんと塞がる");
    }

    /** 45° の倍数でない向きの区間が 1 つでもあるか。 */
    private static boolean hasNonOctileSegment(List<Vec2i> waypoints) {
        for (int i = 1; i < waypoints.size(); i++) {
            int dx = Math.abs(waypoints.get(i).x() - waypoints.get(i - 1).x());
            int dz = Math.abs(waypoints.get(i).z() - waypoints.get(i - 1).z());
            if (dx != 0 && dz != 0 && dx != dz) {
                return true;
            }
        }
        return false;
    }

    /** そのセルが障害物（通行不可セル）に 8 近傍で接しているか。 */
    private static boolean touchesObstacle(Grid grid, Vec2i cell) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (!grid.get(cell.x() + dx, cell.z() + dz).walkable()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 差分表示

    /**
     * 赤い予測経路は「変わる部分だけ」を描く。その差分計算を検証する。
     * 全体を赤で塗ると青い現在経路と重なって、どこが変わったのか読めなくなるため。
     */
    private static void checkPathDiff() {
        section("経路の差分（変更箇所だけを赤で出す）");

        Grid grid = new Grid(21, 21);
        grid.setCore(new Vec2i(17, 9));
        Vec2i spawn = new Vec2i(0, 3);
        grid.addSpawn(spawn);
        PathResult before = grid.pathFrom(spawn);

        // 同じ経路どうしなら差分なし = 赤は 1 本も出ない
        assertTrue(PathFinder.divergentSection(before.waypoints(), before.waypoints()).isEmpty(),
                "同じ経路なのに差分が出ている");

        // 経路から離れた場所に置いても経路は変わらない = 赤は出ない
        Grid untouched = grid.copy();
        untouched.set(new Vec2i(2, 19), CellType.WALL);
        untouched.set(new Vec2i(3, 19), CellType.WALL);
        PathResult unchanged = untouched.pathFrom(spawn);
        assertTrue(PathFinder.divergentSection(before.waypoints(), unchanged.waypoints()).isEmpty(),
                "経路が変わっていないのに差分が出ている");
        System.out.println("  経路から離れた配置 → 赤の描画なし");

        // 経路上に置くと、その周辺だけが差分になる
        Grid detoured = grid.copy();
        for (int x = 8; x <= 10; x++) {
            for (int z = 5; z <= 7; z++) {
                detoured.set(new Vec2i(x, z), CellType.ROCK);
            }
        }
        PathResult after = detoured.pathFrom(spawn);
        List<Vec2i> changed = PathFinder.divergentSection(before.waypoints(), after.waypoints());

        assertTrue(!changed.isEmpty(), "経路が変わったのに差分が空");
        assertTrue(changed.size() <= after.waypoints().size(), "差分が変更後の経路より長い");
        assertTrue(isContiguousSublist(after.waypoints(), changed),
                "差分が変更後の経路の連続した一部になっていない");
        System.out.printf("  経路上に配置 → 変更後 %d 点のうち %d 点だけを赤で描画%n",
                after.waypoints().size(), changed.size());

        measureDiffOnRealMaze();
    }

    /**
     * 実際に迷路を組んだ盤面で、1 枚置いたときに赤くなる割合を測る。
     * 経路全体が赤くなるのではなく、本当に変わった区間だけで済んでいるかの実測。
     */
    private static void measureDiffOnRealMaze() {
        Random random = new Random(4242);
        Grid maze = randomGrid(random, 23, 23);
        for (int i = 0; i < 3000; i++) {
            Shape shape = Shapes.random(random);
            Rot rot = Rot.values()[random.nextInt(4)];
            Vec2i origin = new Vec2i(random.nextInt(maze.width()), random.nextInt(maze.height()));
            if (maze.checkPlacement(shape, origin, rot).ok()) {
                maze.place(shape, origin, rot);
            }
        }

        Vec2i spawn = maze.spawns().get(0);
        PathResult full = maze.pathFrom(spawn);
        if (!full.reachable() || full.waypoints().size() < 4) {
            return;
        }

        int changedSamples = 0;
        int unchangedSamples = 0;
        int partial = 0;
        double ratioSum = 0;
        for (int x = 0; x < maze.width(); x++) {
            for (int z = 0; z < maze.height(); z++) {
                Vec2i cell = new Vec2i(x, z);
                if (!maze.checkPlacement(Shapes.DOT, cell, Rot.R0).ok()) {
                    continue;
                }
                maze.set(cell, CellType.WALL);
                PathResult after = maze.pathFrom(spawn);
                maze.set(cell, CellType.OPEN);
                if (!after.reachable()) {
                    continue;
                }
                if (after.waypoints().equals(full.waypoints())) {
                    unchangedSamples++;
                    continue;
                }
                List<Vec2i> diff = PathFinder.divergentSection(full.waypoints(), after.waypoints());
                assertTrue(!diff.isEmpty(), "経路が変わったのに差分が空");
                assertTrue(isContiguousSublist(after.waypoints(), diff), "差分が連続していない");
                changedSamples++;
                ratioSum += diff.size() / (double) after.waypoints().size();
                if (diff.size() < after.waypoints().size()) {
                    partial++;
                }
            }
        }

        System.out.printf("  実際の迷路（折れ点 %d）で 1 マス置いた場合:%n", full.turns());
        System.out.printf("    経路が変わらない置き方 %d 通り → 赤の描画なし%n", unchangedSamples);
        System.out.printf("    経路が変わる置き方 %d 通り → 平均で経路の %.0f%% だけが赤"
                        + "（うち %d 通りは一部だけ）%n",
                changedSamples, changedSamples == 0 ? 0 : 100.0 * ratioSum / changedSamples, partial);
    }

    /** whole の中に part が連続した並びとして含まれているか。 */
    private static boolean isContiguousSublist(List<Vec2i> whole, List<Vec2i> part) {
        for (int start = 0; start + part.size() <= whole.size(); start++) {
            if (whole.subList(start, start + part.size()).equals(part)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 決定性

    private static void checkPathDeterminism() {
        section("経路探索の決定性");
        Random random = new Random(12345);
        for (int trial = 0; trial < 200; trial++) {
            Grid grid = randomGrid(random, 21, 21);
            PathResult first = grid.pathFrom(grid.spawns().get(0));
            PathResult second = grid.pathFrom(grid.spawns().get(0));
            assertTrue(first.waypoints().equals(second.waypoints()),
                    "同じ盤面で経路が揺れた (trial " + trial + ")");
            if (first.reachable()) {
                assertTrue(grid.get(first.end()) == CellType.CORE, "経路の終点がコアでない");
                assertTrue(grid.get(first.start()) == CellType.SPAWN, "経路の始点がスポーンでない");
                for (int i = 1; i < first.waypoints().size(); i++) {
                    assertTrue(PathFinder.lineOfSight(grid,
                                    first.waypoints().get(i - 1), first.waypoints().get(i)),
                            "区間が障害物を突き抜けている");
                }
            }
        }
        System.out.println("  200 回の探索で経路が一意、かつ全区間が障害物を突き抜けないことを確認");
    }

    // ---------------------------------------------------------------- 封鎖禁止

    private static void checkBlockingRule() {
        section("完全封鎖の禁止");

        Grid grid = new Grid(15, 15);
        grid.setCore(new Vec2i(7, 7));
        grid.addSpawn(new Vec2i(7, 0));

        Shape dot = Shapes.DOT;
        Vec2i[] ring = {
                new Vec2i(6, 0), new Vec2i(8, 0), new Vec2i(6, 1),
                new Vec2i(7, 1), new Vec2i(8, 1)
        };
        int accepted = 0;
        int rejected = 0;
        for (Vec2i cell : ring) {
            Grid.Placement placement = grid.checkPlacement(dot, cell, Rot.R0);
            if (placement.ok()) {
                grid.place(dot, cell, Rot.R0);
                accepted++;
            } else {
                rejected++;
                assertTrue(placement == Grid.Placement.WOULD_BLOCK,
                        "封鎖以外の理由で弾かれた: " + placement);
            }
        }
        assertTrue(rejected >= 1, "スポーンを完全に囲めてしまった");
        assertTrue(grid.allSpawnsConnected(), "封鎖禁止を通ったのに経路が失われている");
        System.out.println("  スポーン包囲の試行: 受理 " + accepted + " / 拒否 " + rejected);

        Random random = new Random(777);
        Grid stress = randomGrid(random, 23, 23);
        int placed = 0;
        for (int i = 0; i < 4000; i++) {
            Shape shape = Shapes.random(random);
            Rot rot = Rot.values()[random.nextInt(4)];
            Vec2i origin = new Vec2i(random.nextInt(stress.width()), random.nextInt(stress.height()));
            if (stress.checkPlacement(shape, origin, rot).ok()) {
                stress.place(shape, origin, rot);
                placed++;
                assertTrue(stress.allSpawnsConnected(), "配置後に経路が失われた");
            }
        }
        PathResult finalPath = stress.pathFrom(stress.spawns().get(0));
        assertTrue(finalPath.reachable(), "詰め込んだ後に経路が消えた");
        System.out.printf("  ランダム配置 %d 枚後も経路は健在（実距離 %.1f / 折れ点 %d）%n",
                placed, finalPath.length(), finalPath.turns());
    }

    // ---------------------------------------------------------------- カードの素材

    /**
     * 「1 枚のカードは 1 種類のブロックでできている」ことを確かめる。
     * カードごとに素材が違えば、盤面のどこを自分がどのカードで組んだのかが読める。
     */
    private static void checkCardMaterials() {
        section("カードごとの素材");

        Deck deck = Deck.starter();
        java.util.Set<Integer> variants = new java.util.HashSet<>();
        for (BlockCard card : deck.library()) {
            assertTrue(variants.add(card.variant()),
                    "素材番号が重複している: " + card.variant());
        }
        System.out.println("  開始デッキ " + deck.librarySize() + " 枚に別々の素材番号が振られている");

        for (int layer = 1; layer <= 7; layer += 2) {
            Palette.Theme theme = Palette.themeForLayer(layer);
            java.util.Set<String> blocks = new java.util.LinkedHashSet<>();
            for (BlockCard card : deck.library()) {
                var first = theme.wallForVariant(card.variant());
                // 同じカードは何度引いても同じブロック
                assertTrue(first.equals(theme.wallForVariant(card.variant())),
                        "同じカードなのにブロックが変わる");
                blocks.add(first.name());
            }
            System.out.println("  " + theme.name() + ": " + blocks.size() + " 種類の壁が使われる "
                    + blocks);
        }
    }

    // ---------------------------------------------------------------- ロードマップ

    /**
     * ロードマップのノードグラフを検証する。
     *
     * <p>全体を最初から見せて経路を選ばせる以上、グラフが壊れていると
     * 「進めない行き止まり」「絶対に踏めないノード」がそのままプレイヤーに見えてしまう。
     * 生成の性質をここで固めておく。</p>
     *
     * <p>道の交差は <b>許す</b>。パーティクルの直線で描くので交差していても目で追えるし、
     * 交差があるほうがルートが絡み合って経路選択が面白くなる。
     * ここでは交差数を「出ているか」の確認として数えるだけにする。</p>
     */
    private static void checkRoadmapGraph() {
        section("ロードマップのノードグラフ");

        Random random = new Random(31337);
        int deadEnds = 0;
        int unreachable = 0;
        int crossings = 0;
        int noCombat = 0;
        int maxOutDegree = 0;
        int maxWidth = 0;
        int totalNodes = 0;
        int totalEdges = 0;

        for (int trial = 0; trial < 300; trial++) {
            Roadmap map = Roadmap.generate(random);

            assertTrue(map.layer(1).size() >= 2, "第1層に選択肢が無い");
            assertTrue(map.layer(Roadmap.LAYERS).get(0).kind() == Roadmap.NodeKind.BOSS,
                    "最終層がボスでない");

            for (int layer = 1; layer <= Roadmap.LAYERS; layer++) {
                List<Roadmap.Node> row = map.layer(layer);
                totalNodes += row.size();
                assertTrue(!row.isEmpty(), "空の層がある");

                boolean combat = false;
                for (Roadmap.Node node : row) {
                    combat |= node.kind().combat();
                    totalEdges += node.next().size();
                    if (layer < Roadmap.LAYERS) {
                        maxOutDegree = Math.max(maxOutDegree, node.next().size());
                    }
                    maxWidth = Math.max(maxWidth, row.size());
                    // 行き止まりがないこと
                    if (layer < Roadmap.LAYERS && node.next().isEmpty()) {
                        deadEnds++;
                    }
                    // 辺の指す先が実在すること
                    for (int index : node.next()) {
                        assertTrue(map.node(layer + 1, index) != null,
                                "存在しないノードへの辺: 第" + layer + "層 " + index);
                    }
                }
                if (!combat) {
                    noCombat++;
                }

                // 到達不能なノードがないこと
                if (layer > 1) {
                    List<Roadmap.Node> previous = map.layer(layer - 1);
                    for (int j = 0; j < row.size(); j++) {
                        boolean incoming = false;
                        for (Roadmap.Node from : previous) {
                            if (from.next().contains(j)) {
                                incoming = true;
                                break;
                            }
                        }
                        if (!incoming) {
                            unreachable++;
                        }
                    }
                }

                // 交差の数を数える（禁止はしない。絡み合ってよい）
                if (layer < Roadmap.LAYERS) {
                    for (int i = 0; i + 1 < row.size(); i++) {
                        List<Integer> a = row.get(i).next();
                        List<Integer> b = row.get(i + 1).next();
                        if (a.isEmpty() || b.isEmpty()) {
                            continue;
                        }
                        if (Collections.min(b) < Collections.min(a)
                                || Collections.max(b) < Collections.max(a)) {
                            crossings++;
                        }
                    }
                }
            }

            // 第1層からボスまで実際に辿り着けること
            assertTrue(reachesBoss(map), "第1層からボスへ到達できないロードマップ");
        }

        assertTrue(deadEnds == 0, "行き止まりのノードが " + deadEnds + " 個");
        assertTrue(unreachable == 0, "到達不能なノードが " + unreachable + " 個");
        assertTrue(noCombat == 0, "戦闘ノードが 1 つも無い層が " + noCombat + " 個");
        assertTrue(crossings > 0, "道がまったく交差していない（絡み合いが生まれていない）");
        assertTrue(maxOutDegree <= 3, "1 ノードから伸びる道が多すぎる: " + maxOutDegree);
        System.out.printf("  300 マップ / 平均 %.1f ノード・%.1f 辺  最大幅 %d・最大分岐 %d%n",
                totalNodes / 300.0, totalEdges / 300.0, maxWidth, maxOutDegree);
        System.out.printf("  行き止まり 0・到達不能 0／交差 %d 箇所（意図的に許容）%n", crossings);

        // 選択肢が「直前に踏んだノードの辺」に従うこと
        RunState run = new RunState(4242);
        for (int layer = 1; layer < Roadmap.LAYERS; layer++) {
            List<Roadmap.Node> choices = run.currentChoices();
            assertTrue(!choices.isEmpty(), "第" + layer + "層で進める先が無い");
            for (Roadmap.Node node : choices) {
                assertTrue(node.layer() == run.layer(), "別の層のノードが選択肢に出ている");
                assertTrue(run.canEnter(node), "選択肢なのに入場できない判定");
            }
            Roadmap.Node picked = choices.get(0);
            run.advanceLayer(picked.index());
            for (Roadmap.Node next : run.currentChoices()) {
                assertTrue(picked.next().contains(next.index()),
                        "辺でつながっていないノードが選択肢に出ている");
            }
        }
        System.out.println("  進行に応じた選択肢が、必ず直前のノードの辺に一致することを確認");
    }

    /** 第1層からボス層まで辺をたどって到達できるか。 */
    private static boolean reachesBoss(Roadmap map) {
        java.util.Set<Integer> frontier = new java.util.HashSet<>();
        for (Roadmap.Node node : map.layer(1)) {
            frontier.add(node.index());
        }
        for (int layer = 1; layer < Roadmap.LAYERS; layer++) {
            java.util.Set<Integer> next = new java.util.HashSet<>();
            for (int index : frontier) {
                Roadmap.Node node = map.node(layer, index);
                if (node != null) {
                    next.addAll(node.next());
                }
            }
            if (next.isEmpty()) {
                return false;
            }
            frontier = next;
        }
        return !frontier.isEmpty();
    }

    // ---------------------------------------------------------------- ステージ生成

    private static void checkGeneration() {
        section("ステージ生成と検証");
        Roadmap.NodeKind[] kinds = {
                Roadmap.NodeKind.BATTLE, Roadmap.NodeKind.ELITE, Roadmap.NodeKind.BOSS
        };
        int count = 0;
        int fallbacks = 0;
        double minLength = Double.MAX_VALUE;
        double maxLength = 0;
        for (int layer = 1; layer <= 7; layer++) {
            for (Roadmap.NodeKind kind : kinds) {
                for (int seed = 0; seed < 12; seed++) {
                    StageGenerator.Result result =
                            StageGenerator.generate(layer, kind, layer * 1000L + seed);
                    Grid grid = result.grid();
                    assertTrue(!grid.spawns().isEmpty(), "スポーンがない");
                    assertTrue(grid.coreCells().size() == 4, "コアが 2x2 でない");
                    if (result.fallback()) {
                        fallbacks++;
                    }
                    for (Vec2i spawn : grid.spawns()) {
                        PathResult path = grid.pathFrom(spawn);
                        assertTrue(path.reachable(),
                                "生成された盤面でコアへ到達できない (layer " + layer + " seed " + seed + ")");
                        minLength = Math.min(minLength, path.length());
                        maxLength = Math.max(maxLength, path.length());
                    }
                    assertTrue(!result.waves().isEmpty(), "ウェーブが空");
                    count++;
                }
            }
        }
        assertTrue(fallbacks == 0, "既定盤面へのフォールバックが " + fallbacks + " 回発生した");
        System.out.printf("  %d 盤面すべてが検証を通過（初期移動距離 %.1f〜%.1f / フォールバック %d 回）%n",
                count, minLength, maxLength, fallbacks);
    }

    private static void checkWaves() {
        section("ウェーブ構成");
        StageGenerator.Result battle = StageGenerator.generate(3, Roadmap.NodeKind.BATTLE, 42);
        for (Waves.WaveSpec wave : battle.waves()) {
            assertTrue(wave.totalEnemies() > 0, "空のウェーブ");
            System.out.println("  W" + wave.number() + ": " + wave.summary()
                    + "  (計 " + wave.totalEnemies() + " 体, 報酬 " + wave.clearBonus() + "G)");
        }
        StageGenerator.Result boss = StageGenerator.generate(7, Roadmap.NodeKind.BOSS, 99);
        Waves.WaveSpec last = boss.waves().get(boss.waves().size() - 1);
        assertTrue(last.summary().contains("災厄"), "ボスウェーブにボスがいない");
        System.out.println("  ボス最終ウェーブ: " + last.summary());
    }

    // ---------------------------------------------------------------- 可視化

    private static void printSampleStage() {
        section("サンプル盤面（#=岩 .=空き S=スポーン C=コア +=経路 O=曲がり角）");
        StageGenerator.Result result = StageGenerator.generate(2, Roadmap.NodeKind.BATTLE, 20260830L);
        Grid grid = result.grid();
        PathResult path = grid.pathFrom(grid.spawns().get(0));
        List<Vec2i> traversed = PathFinder.traversedCells(path.waypoints());
        List<Vec2i> turns = path.waypoints();

        System.out.printf("  %s  %dx%d  実距離 %.2f  折れ点 %d 個%n",
                result.config().title(), grid.width(), grid.height(), path.length(), path.turns());
        for (int z = 0; z < grid.height(); z++) {
            StringBuilder line = new StringBuilder("  ");
            for (int x = 0; x < grid.width(); x++) {
                Vec2i cell = new Vec2i(x, z);
                CellType type = grid.get(cell);
                char c = switch (type) {
                    case ROCK -> '#';
                    case WALL -> 'W';
                    case SPAWN -> 'S';
                    case CORE -> 'C';
                    default -> turns.contains(cell) ? 'O' : traversed.contains(cell) ? '+' : '.';
                };
                line.append(c).append(' ');
            }
            System.out.println(line);
        }
    }

    // ---------------------------------------------------------------- 補助

    private static Grid randomGrid(Random random, int width, int height) {
        Grid grid = new Grid(width, height);
        grid.setCore(new Vec2i(width / 2 - 1, height / 2 - 1));
        grid.addSpawn(new Vec2i(random.nextInt(width - 4) + 2, 0));

        List<Vec2i> candidates = new ArrayList<>();
        for (int x = 1; x < width - 1; x++) {
            for (int z = 1; z < height - 1; z++) {
                candidates.add(new Vec2i(x, z));
            }
        }
        java.util.Collections.shuffle(candidates, random);
        int rocks = (int) (width * height * 0.06);
        int placed = 0;
        for (Vec2i cell : candidates) {
            if (placed >= rocks) {
                break;
            }
            if (grid.get(cell) != CellType.OPEN) {
                continue;
            }
            grid.set(cell, CellType.ROCK);
            if (!grid.allSpawnsConnected()) {
                grid.set(cell, CellType.OPEN);
                continue;
            }
            placed++;
        }
        return grid;
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
