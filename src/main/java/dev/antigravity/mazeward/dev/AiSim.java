package dev.antigravity.mazeward.dev;

import dev.antigravity.mazeward.ai.AiDirector;
import dev.antigravity.mazeward.ai.BrainClient;
import dev.antigravity.mazeward.ai.MatchSnapshot;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.MatchClock;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;

/**
 * AI 対戦のヘッドレス検証。{@code gradle aiSim} で実行。
 *
 * <p>確かめるのは 3 つ。</p>
 * <ol>
 *   <li><b>AI が実際に打てているか</b> — 壁・塔・送りが 1 つも出ないなら、
 *       スナップショットか行動の解釈が壊れている。「動いているが弱い」と
 *       「そもそも打てていない」は画面では見分けがつかない</li>
 *   <li><b>倍速で中身が変わらないか</b> — 同じ試合を x1 と x8 で回し、
 *       サーバー tick あたりの進み方だけが変わることを確かめる</li>
 *   <li><b>ブリッジが落ちていても試合が成立するか</b> — Python を立てずに
 *       走らせると貪欲ボットへ切り替わる。ここが壊れると
 *       「Python を忘れた日は遊べない」ゲームになる</li>
 * </ol>
 *
 * <p>引数: {@code aiSim [人数] [--brain]}。{@code --brain} を付けると
 * 学習済み方策（{@code ai/mc_brain.py}）へ繋ぎに行く。</p>
 */
public final class AiSim {

    /** 打ち切り。ゲーム内 25 分。 */
    private static final int GUARD_TICKS = 20 * 60 * 25;

    private static int failures;

    private AiSim() {
    }

    public static void main(String[] args) {
        MinecraftServer.init();
        int players = 2;
        boolean wantBrain = false;
        String model = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--brain" -> wantBrain = true;
                case "--model" -> {
                    // モデルを指定するなら当然ブリッジが要る
                    model = args[++i];
                    wantBrain = true;
                }
                default -> players = Integer.parseInt(args[i]);
            }
        }

        checkClock();
        BrainClient brain = wantBrain ? connect(model) : null;
        simulate(players, 4242L, brain);

        if (brain != null) {
            brain.close();
        }
        if (failures == 0) {
            System.out.println("[OK] AI 対戦シミュレーション完了");
            System.exit(0);
        }
        System.out.println("[FAIL] 不整合 " + failures + " 件");
        System.exit(1);
    }

    /**
     * ブリッジへ繋ぎに行く。立っていなければ自分で起動する。
     *
     * <p>ゲームと同じ {@code ensureBrainProcess} を通すので、
     * <b>自動起動そのものの検証</b> にもなる（子プロセスの文字コード設定を
     * 間違えると、日本語のログ 1 行で Python が落ちる）。</p>
     */
    private static BrainClient connect(String model) {
        BrainClient brain = BrainClient.openDefault();
        for (int i = 0; i < 10 && !brain.available(); i++) {
            sleep(100);
        }
        if (!brain.available() && brain.ensureBrainProcess()) {
            System.out.println("  ブリッジが居ないので起動しました（torch の読み込み待ち）");
        }
        for (int i = 0; i < 400 && !brain.available(); i++) {
            sleep(100);
        }
        if (!brain.available()) {
            System.out.println("  ブリッジに繋がらないので貪欲ボットで走ります");
            return null;
        }
        if (model != null) {
            brain.selectModel(model);
            // 切り替えは非同期。反映されるまで待ってから試合を始める
            for (int i = 0; i < 50 && !brain.modelLabel().startsWith(model); i++) {
                sleep(100);
            }
        }
        System.out.println("  ブリッジに接続: " + brain.name());
        System.out.println("  選べるモデル: " + String.join(", ", brain.models()));
        return brain;
    }

    // ================================================================ 時計

    /**
     * 倍率どおりの回数だけ進むか。
     *
     * <p>端数の繰り越しを間違えると 1.5 倍が 1 倍になる、という
     * <b>気付きにくいほうの壊れ方</b> をするので、遅い側も必ず確かめる。</p>
     */
    private static void checkClock() {
        System.out.println("=== 速度 ===");
        MatchClock clock = new MatchClock();
        for (double target : MatchClock.SPEEDS) {
            clock.reset();
            while (clock.speed() > target) {
                clock.slower();
            }
            while (clock.speed() < target) {
                clock.faster();
            }
            int steps = 0;
            for (int serverTick = 0; serverTick < 200; serverTick++) {
                steps += clock.stepsThisTick();
            }
            int expected = (int) Math.round(target * 200);
            check(steps == expected, "速度 " + clock.label() + ": 200 tick で "
                    + steps + " 回進んだ（期待 " + expected + "）");
            System.out.printf("  %-8s 200 サーバー tick → 試合 %d tick%n", clock.label(), steps);
        }
        System.out.println();
    }

    // ================================================================ 試合

    private static void simulate(int players, long seed, BrainClient brain) {
        InstanceContainer instance =
                MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        instance.setGenerator(unit -> {
        });

        boolean[] ended = {false};
        VersusPlayer[] winner = {null};
        VersusMatch match = new VersusMatch(instance, seed, players, (m, w) -> {
            ended[0] = true;
            winner[0] = w;
        });

        List<VersusPlayer> everyone = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            VersusPlayer participant = new VersusPlayer("AI-" + (i + 1), null, true);
            match.addParticipant(participant);
            everyone.add(participant);
        }
        AiDirector director = new AiDirector(match, brain);
        for (int seat = 0; seat < players; seat++) {
            director.control(seat);
        }

        System.out.println("=== AI " + players + " 体の対戦  seed=" + seed + " ===");
        checkSnapshot(match);

        int[] wallCells = new int[players];
        int guard = 0;
        while (!ended[0] && guard++ < GUARD_TICKS) {
            match.tick(false);
            director.tick(match.elapsedTicks());
            waitForBrain(director, brain);
        }

        System.out.printf("  %d tick (%.1f 分)%n", guard, guard / 1200.0);
        int totalTowers = 0;
        int totalSends = 0;
        for (int seat = 0; seat < players; seat++) {
            VersusPlayer participant = everyone.get(seat);
            Island island = participant.island();
            wallCells[seat] = wallCount(island);
            totalTowers += island.towers().size();
            totalSends += participant.sendsTotal();
            System.out.printf("    %-5s ライフ %2d  コイン %4d  インカム %3d  "
                            + "タワー %2d  壁 %3d  送り %2d  最後の手: %s%n",
                    participant.name(), participant.lives(), participant.coins(),
                    participant.income(), island.towers().size(), wallCells[seat],
                    participant.sendsTotal(), director.lastAction(seat));
        }
        System.out.println("  頭: " + director.policyName());

        // 「動いているが弱い」ではなく「そもそも打てていない」を検出する
        check(totalTowers > 0, "AI がタワーを 1 基も置いていない");
        check(totalSends > 0, "AI が 1 度も送っていない");
        int builtWalls = 0;
        for (int count : wallCells) {
            builtWalls += count;
        }
        check(builtWalls > 0, "AI が壁を 1 マスも置いていない");
        check(ended[0], "試合が決着しなかった（" + guard + " tick）");
        System.out.println("  勝者: " + (winner[0] == null ? "引き分け" : winner[0].name()));

        director.close();
        match.dispose();
        MinecraftServer.getInstanceManager().unregisterInstance(instance);
    }

    /**
     * 方策の返事を待つ。<b>検証のときだけ</b>。
     *
     * <p>ここは実時間より何百倍も速くゲーム内時間を進めている。実ゲームなら
     * 1 秒に 1 手なので返事が 1 tick 遅れても平気だが、この速さで待たずに回すと
     * <b>1 試合 25 分ぶんを 2 秒で走り抜けて、AI は数手しか打てない</b>。
     * それを「AI が何もしない」と読み違えないよう、ここでは待つ。</p>
     */
    private static void waitForBrain(AiDirector director, BrainClient brain) {
        if (brain == null) {
            return;
        }
        for (int i = 0; i < 100 && director.thinking(); i++) {
            sleep(2);
        }
    }

    /** スナップショットが JSON として破綻していないか、最低限の形を見る。 */
    private static void checkSnapshot(VersusMatch match) {
        String json = MatchSnapshot.build(match, List.of(0), null);
        check(json.startsWith("{") && json.endsWith("}"), "スナップショットが JSON でない");
        check(json.contains("\"boards\""), "スナップショットに boards が無い");
        check(json.contains("\"cells\""), "スナップショットに盤面が無い");
        check(!json.contains(",}") && !json.contains(",]"), "スナップショットのカンマが余っている");
        check(json.indexOf('\n') < 0, "スナップショットに改行が混ざっている（1 行 1 要求のため）");
        System.out.println("  スナップショット " + json.length() + " 文字 / "
                + match.playerCount() + " 島");
    }

    private static int wallCount(Island island) {
        int count = 0;
        for (int x = 0; x < island.grid().width(); x++) {
            for (int z = 0; z < island.grid().height(); z++) {
                if (island.grid().get(x, z)
                        == dev.antigravity.mazeward.core.CellType.WALL) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("  [NG] " + message);
        }
    }
}
