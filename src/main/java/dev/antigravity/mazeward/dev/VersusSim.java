package dev.antigravity.mazeward.dev;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Shapes;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.enemy.EnemyInstance;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.versus.AttackerKind;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.util.Random;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;

/**
 * 対戦モードのヘッドレス検証。
 *
 * <p>いちばん壊れやすいのは <b>1 つのワールドに複数の島を並べたときの座標</b>。
 * 原点の足し忘れが 1 箇所でもあると、敵が隣の島へ歩いて行ったり、
 * 別の島のタワーに撃たれたりする。目で見ないと気付きにくいので、
 * 毎 tick「全部の敵が自分の島の中にいるか」を機械的に確かめる。</p>
 *
 * <p>{@code gradle versusSim} で実行。</p>
 */
public final class VersusSim {

    private static int failures;

    private VersusSim() {
    }

    public static void main(String[] args) {
        MinecraftServer.init();
        int playerCount = args.length > 0 ? Integer.parseInt(args[0]) : 4;

        checkSenderReward();

        for (int trial = 0; trial < 2; trial++) {
            simulate(playerCount, 900 + trial * 77L);
            System.out.println();
        }

        if (failures == 0) {
            System.out.println("[OK] 対戦シミュレーション完了");
            System.exit(0);
        }
        System.out.println("[FAIL] 不整合 " + failures + " 件");
        System.exit(1);
    }

    private static void simulate(int playerCount, long seed) {
        Random random = new Random(seed);
        InstanceContainer instance =
                MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        instance.setGenerator(unit -> {
        });

        boolean[] ended = {false};
        VersusPlayer[] winner = {null};
        VersusMatch match = new VersusMatch(instance, seed, playerCount, (m, w) -> {
            ended[0] = true;
            winner[0] = w;
        });

        List<VersusPlayer> everyone = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            VersusPlayer participant = new VersusPlayer("P" + (i + 1), null, i > 0);
            match.addParticipant(participant);
            everyone.add(participant);
        }
        System.out.println("=== " + playerCount + " 人対戦  seed=" + seed
                + "  島間隔 " + VersusMatch.ISLAND_SPACING + " ===");

        checkIslandsDoNotOverlap(everyone);

        int builds = 0;
        int sends = 0;
        int guard = 0;
        Map<AttackerKind, Integer> sentByKind = new EnumMap<>(AttackerKind.class);
        while (!ended[0] && guard++ < 20 * 60 * 25) {
            match.tick();

            // 全員が同じように迷路を組み、タワーを並べ、送る
            if (guard % 40 == 0) {
                for (VersusPlayer participant : everyone) {
                    if (participant.alive()) {
                        builds += build(participant, random);
                    }
                }
            }
            if (guard % 100 == 0 && !match.preparing()) {
                for (VersusPlayer participant : everyone) {
                    if (!participant.alive()) {
                        continue;
                    }
                    AttackerKind sent = sendSomething(match, participant);
                    if (sent != null) {
                        sends++;
                        sentByKind.merge(sent, 1, Integer::sum);
                    }
                }
            }
            if (guard % 5 == 0) {
                checkEnemiesStayHome(everyone);
            }
        }

        System.out.printf("  %d tick (%.1f 分)  建築 %d 手  送り %d 回%n",
                guard, guard / 1200.0, builds, sends);
        for (VersusPlayer participant : everyone) {
            Island island = participant.island();
            System.out.printf("    %-4s ライフ %2d  コイン %4d  インカム %3d  タワー %2d  移動距離 %.1f%n",
                    participant.name(), participant.lives(), participant.coins(),
                    participant.income(), island.towers().size(), island.totalPathLength());
        }
        reportSends(sentByKind);
        reportSteals(everyone);
        check(ended[0], "試合が終わらなかった（決着がつかない）");
        System.out.println("  勝者: " + (winner[0] == null ? "引き分け" : winner[0].name()));

        match.dispose();
        MinecraftServer.getInstanceManager().unregisterInstance(instance);
    }

    /**
     * 島同士がワールド上で重なっていないか。
     *
     * <p>島は正方形に近い格子で並ぶので、X だけを見ても足りない。
     * 縦横どちらかで十分に離れていれば重ならない。</p>
     */
    private static void checkIslandsDoNotOverlap(List<VersusPlayer> everyone) {
        int worst = Integer.MAX_VALUE;
        for (int i = 0; i < everyone.size(); i++) {
            for (int j = i + 1; j < everyone.size(); j++) {
                Island a = everyone.get(i).island();
                Island b = everyone.get(j).island();
                int gapX = Math.abs(a.arena().originX() - b.arena().originX()) - a.grid().width();
                int gapZ = Math.abs(a.arena().originZ() - b.arena().originZ()) - a.grid().height();
                int gap = Math.max(gapX, gapZ);
                worst = Math.min(worst, gap);
                check(gap >= 4, "島 " + i + " と " + j + " が近すぎる（隙間 X " + gapX
                        + " / Z " + gapZ + "）");
            }
        }
        System.out.println("  島の重なりなし（最小の隙間 " + worst + " ブロック）");
    }

    /**
     * すべての敵が自分の島の盤面内にいるか。
     * 原点の足し忘れがあると、ここで即座に落ちる。
     */
    private static void checkEnemiesStayHome(List<VersusPlayer> everyone) {
        for (VersusPlayer participant : everyone) {
            Island island = participant.island();
            for (EnemyInstance enemy : island.enemies()) {
                Pos pos = enemy.position();
                Vec2i cell = island.arena().toCell(pos.x(), pos.z());
                if (!island.grid().inBounds(cell)) {
                    check(false, participant.name() + " の敵が島の外にいる: " + cell);
                    return;
                }
                if (!enemy.kind().flying() && !island.grid().get(cell).walkable()) {
                    check(false, participant.name() + " の敵が壁の中にいる: " + cell);
                    return;
                }
            }
        }
    }

    /** 経路の近くに壁を置き、その上にタワーを並べる。 */
    private static int build(VersusPlayer participant, Random random) {
        Island island = participant.island();
        int placed = 0;

        for (int attempt = 0; attempt < 2; attempt++) {
            if (participant.deck().hand().isEmpty()) {
                break;
            }
            Shape shape = participant.deck().hand().get(0).shape();
            Vec2i best = null;
            Rot bestRot = Rot.R0;
            double bestDelta = -1;
            for (int i = 0; i < 60; i++) {
                Rot rot = Rot.values()[random.nextInt(4)];
                Vec2i cursor = new Vec2i(random.nextInt(island.grid().width()),
                        random.nextInt(island.grid().height()));
                Vec2i origin = island.originFor(shape, cursor, rot);
                Battlefield.PlacementPreview preview = island.preview(shape, origin, rot);
                if (preview.ok() && preview.delta() > bestDelta) {
                    bestDelta = preview.delta();
                    best = origin;
                    bestRot = rot;
                }
            }
            if (best == null || !island.placeCard(0, best, bestRot).success()) {
                break;
            }
            placed++;
        }

        // 経路の近くの土台に弓塔を並べる
        for (int x = 0; x < island.grid().width(); x++) {
            for (int z = 0; z < island.grid().height(); z++) {
                if (participant.coins() < TowerKind.ARROW.baseCost()) {
                    return placed;
                }
                Vec2i cell = new Vec2i(x, z);
                CellType type = island.grid().get(cell);
                if (type != CellType.WALL && type != CellType.ROCK) {
                    continue;
                }
                if (island.towerPlacementError(TowerKind.ARROW, cell, Rot.R0) == null) {
                    island.placeTower(TowerKind.ARROW, cell, Rot.R0);
                }
            }
        }
        return placed;
    }

    /**
     * 送られた内訳。
     *
     * <p>能力持ちが一度も送られていないと、その戦闘処理（無力化・瞬移・分裂・庇護・復活）が
     * 検証を素通りしてしまう。決着したかどうかだけでなく、
     * <b>何が実際に走ったか</b> を出しておく。</p>
     */
    private static void reportSends(Map<AttackerKind, Integer> sentByKind) {
        StringBuilder line = new StringBuilder("  送りの内訳: ");
        for (AttackerKind kind : AttackerKind.values()) {
            int count = sentByKind.getOrDefault(kind, 0);
            if (count > 0) {
                line.append(kind.displayName()).append(' ').append(count).append("  ");
            }
        }
        System.out.println(line.toString().stripTrailing());
    }

    /** 終焉騎に上限を奪われた人がいるか。復活処理が実際に走ったかの確認になる。 */
    private static void reportSteals(List<VersusPlayer> everyone) {
        int stolen = 0;
        for (VersusPlayer participant : everyone) {
            stolen += VersusPlayer.START_LIVES - participant.maxLives();
        }
        if (stolen > 0) {
            System.out.println("  終焉騎に奪われたライフ上限: 合計 " + stolen);
        }
    }

    /**
     * 送りが通ったときのライフ +1 は、上限を超えない。
     *
     * <p>上限を超えられると、守りを固めたまま送り続けるだけで無敵になる。
     * 「削られたぶんを攻めで取り返す」ところまでが狙いなので、ここは必ず頭打ちにする。</p>
     */
    private static void checkSenderReward() {
        VersusPlayer sender = new VersusPlayer("送り主", null, true);
        int max = sender.maxLives();

        check(!sender.gainLife(1), "満タンなのにライフが増えた");
        check(sender.lives() == max, "満タンからライフが動いた: " + sender.lives());

        sender.loseLife(2);
        check(sender.gainLife(1), "削られているのにライフが戻らない");
        check(sender.lives() == max - 1, "戻り幅が 1 ではない: " + sender.lives());

        sender.gainLife(1);
        check(sender.lives() == max, "上限まで戻らない: " + sender.lives());
        check(!sender.gainLife(1), "上限を超えて戻った: " + sender.lives());

        // 上限を奪われたあとは、その低い上限が頭打ちになる
        sender.stealMaxLife(1);
        check(sender.lives() == max - 1, "上限を奪われたのに現在ライフが下がっていない");
        check(!sender.gainLife(1), "奪われた上限を超えて戻った: " + sender.lives());

        System.out.println("  送りがコアに届いたときのライフ +1: 上限 " + sender.maxLives()
                + " を超えない");
        System.out.println();
    }

    /** 送れるもののうち、いちばん高いものを送る（インカムを伸ばす動き）。 */
    private static AttackerKind sendSomething(VersusMatch match, VersusPlayer participant) {
        List<AttackerKind> options = AttackerKind.unlockedAt(participant.income());
        for (int i = options.size() - 1; i >= 0; i--) {
            AttackerKind kind = options.get(i);
            if (participant.canSend(kind)) {
                match.send(participant, kind);
                return kind;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
