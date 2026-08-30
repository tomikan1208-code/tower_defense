package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.run.Roadmap;
import dev.antigravity.mazeward.stage.StageGenerator;
import dev.antigravity.mazeward.world.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.sound.SoundEvent;

/**
 * 対戦 1 試合。全員の島と経済を回し、送り合いと勝敗を裁く。
 *
 * <p>ルールは Hypixel の TowerWars を下敷きにしている。</p>
 * <ul>
 *   <li>ライフ 20。送られたモンスターが自陣に到達するたびに減る</li>
 *   <li>コインは 10 秒ごとに「インカム」ぶん入る</li>
 *   <li><b>インカムが増えるのは送ったときだけ</b>。守りに使うか収入に回すかが最大の判断</li>
 *   <li>撃破するとコインが入り、そのぶんインカムも少し伸びる</li>
 *   <li>送りは相手を選べない。<b>生き残っている全員に同時に飛ぶ</b></li>
 *   <li>ストックは 30、毎秒 1 回復。撃ちっぱなしを防ぐ</li>
 * </ul>
 *
 * <p>地形は全員まったく同じ（同一シードで生成）。開幕は完全に同条件で、
 * 差がつくのは迷路の組み方とタワーの置き方だけになる。</p>
 */
public final class VersusMatch {

    /** 島と島の間隔。飛んで見に行ける距離感に収める。 */
    public static final int ISLAND_SPACING = 34;

    /** 準備時間。迷路は落ち着いて組みたいので、開幕だけは送りを止める。 */
    public static final int PREP_TICKS = 20 * 60;

    /** 収入が入る間隔。 */
    public static final int INCOME_INTERVAL = 20 * 10;

    /** ストックの回復間隔。 */
    public static final int STOCK_INTERVAL = 20;

    /** ここを過ぎると漏らしたときのライフ減少が倍になる（長期戦を畳む）。 */
    public static final int SUDDEN_DEATH_TICKS = 20 * 60 * 15;

    /** 手札の上限。ここを超えて配らないので、配布が止まる＝使わないと増えない。 */
    private static final int HAND_LIMIT = 6;

    /** カードが 1 枚配られる間隔。 */
    public static final int CARD_INTERVAL = 20 * 30;

    /** 開始時に配る枚数。 */
    private static final int START_HAND = 5;

    public interface Listener {
        void onMatchEnded(VersusMatch match, VersusPlayer winner);
    }

    private final Instance instance;
    private final int expectedPlayers;
    private final List<VersusPlayer> participants = new ArrayList<>();
    private final Random random;
    private final Listener listener;
    private final long seed;

    private int tick;
    private boolean finished;
    private VersusPlayer winner;

    public VersusMatch(Instance instance, long seed, int expectedPlayers, Listener listener) {
        this.instance = instance;
        this.seed = seed;
        this.expectedPlayers = Math.max(2, expectedPlayers);
        this.random = new Random(seed);
        this.listener = listener;
    }

    /**
     * 島を並べる列数。
     *
     * <p>横一列にすると端と端が遠くなりすぎて、相手の島を見に行くのが面倒になる。
     * なるべく正方形に近い格子へ並べると、どの島へも同じくらいの距離で行ける。
     * 8 人なら 3x3 の角が 1 つ欠けた形になる。</p>
     */
    public int columns() {
        return (int) Math.ceil(Math.sqrt(expectedPlayers));
    }

    public Instance instance() {
        return instance;
    }

    public Random random() {
        return random;
    }

    public int handLimit() {
        return HAND_LIMIT;
    }

    public int startHand() {
        return START_HAND;
    }

    /** 次にカードが配られるまでの秒数。 */
    public int nextCardSeconds() {
        return (CARD_INTERVAL - (tick % CARD_INTERVAL)) / 20;
    }

    public boolean finished() {
        return finished;
    }

    public VersusPlayer winner() {
        return winner;
    }

    public List<VersusPlayer> participants() {
        return List.copyOf(participants);
    }

    public int playerCount() {
        return participants.size();
    }

    public int elapsedTicks() {
        return tick;
    }

    /** 準備時間中は送りを出せない。 */
    public boolean preparing() {
        return tick < PREP_TICKS;
    }

    public int prepSecondsLeft() {
        return Math.max(0, (PREP_TICKS - tick) / 20);
    }

    /** 漏らしたときに減るライフ。長引くと倍になる。 */
    public int leakDamage() {
        return tick >= SUDDEN_DEATH_TICKS ? 2 : 1;
    }

    // ================================================================ 準備

    /**
     * 参加者を追加して島を作る。
     *
     * <p>全員の地形を同じにするため、盤面は 1 度だけ生成して各島へ複製する。</p>
     */
    public void addParticipant(VersusPlayer participant) {
        int index = participants.size();
        participants.add(participant);

        int columns = columns();
        int column = index % columns;
        int row = index / columns;

        Grid grid = generateSharedGrid();
        Island island = new Island(instance, grid, theme(),
                column * ISLAND_SPACING, row * ISLAND_SPACING, this, participant);
        participant.setIsland(island);
    }

    private Grid generateSharedGrid() {
        // 同じシードで作るので、何度呼んでも全く同じ地形になる
        StageGenerator.Result result =
                StageGenerator.generate(2, Roadmap.NodeKind.BATTLE, seed);
        return result.grid();
    }

    private Palette.Theme theme() {
        return Palette.MISTY_FOREST;
    }

    /** その島を見下ろせる位置。 */
    public Pos overviewOf(VersusPlayer participant) {
        Island island = participant.island();
        return island.arena().overviewPos(island.grid());
    }

    // ================================================================ 毎 tick

    public void tick() {
        if (finished) {
            return;
        }
        tick++;

        for (VersusPlayer participant : participants) {
            if (participant.island() != null) {
                participant.island().tick();
            }
        }

        if (tick % STOCK_INTERVAL == 0) {
            for (VersusPlayer participant : participants) {
                participant.regenerateStock();
            }
        }
        if (tick % INCOME_INTERVAL == 0) {
            for (VersusPlayer participant : participants) {
                if (participant.alive()) {
                    participant.applyIncomeTick();
                }
            }
        }
        // カードは一定時間ごとに 1 枚だけ。無制限に配ると壁が資源でなくなる
        if (tick % CARD_INTERVAL == 0) {
            for (VersusPlayer participant : participants) {
                if (participant.island() != null && participant.alive()
                        && participant.island().grantCard(HAND_LIMIT, random)) {
                    Player player = participant.player();
                    if (player != null) {
                        player.sendActionBar(Component.text("障害物カードが 1 枚届いた",
                                NamedTextColor.YELLOW));
                    }
                }
            }
        }
        if (tick == PREP_TICKS) {
            announce(Component.text("準備終了。送り合い開始！",
                    NamedTextColor.RED, TextDecoration.BOLD));
            playSound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.2f);
        }
        if (tick == SUDDEN_DEATH_TICKS) {
            announce(Component.text("サドンデス — 漏らしたときのライフ減少が 2 倍になった",
                    NamedTextColor.DARK_RED, TextDecoration.BOLD));
        }
    }

    // ================================================================ 送り

    /**
     * 送りを実行する。<b>生き残っている自分以外の全員</b> に同時に湧く。
     *
     * @return UI に出すメッセージ。送れなかった理由もここに入る
     */
    public String send(VersusPlayer sender, AttackerKind kind) {
        if (finished) {
            return "試合は終わっています";
        }
        if (preparing()) {
            return "準備時間中は送れません（あと " + prepSecondsLeft() + " 秒）";
        }
        if (!sender.alive()) {
            return "脱落しています";
        }
        if (sender.income() < kind.unlockIncome()) {
            return kind.displayName() + " はインカム " + kind.unlockIncome() + " で解禁されます";
        }
        if (sender.stock() < kind.stockCost()) {
            return "ストックが足りません（" + kind.stockCost() + " 必要 / 残り " + sender.stock() + "）";
        }
        if (!sender.paySend(kind)) {
            return "コインが足りません（" + kind.cost() + " 必要）";
        }

        int targets = 0;
        for (VersusPlayer other : participants) {
            if (other == sender || !other.alive() || other.island() == null) {
                continue;
            }
            other.island().receive(kind);
            targets++;
        }

        announce(Component.text(sender.name() + " が " + kind.displayName()
                + " を送った（インカム +" + kind.incomeGain() + "）", NamedTextColor.YELLOW));
        return kind.displayName() + " を " + targets + " 人へ送信（インカム " + sender.income() + "）";
    }

    // ================================================================ 勝敗

    void onLifeLost(VersusPlayer participant) {
        if (participant.alive() || finished) {
            checkFinish();
            return;
        }
        announce(Component.text(participant.name() + " が脱落した",
                NamedTextColor.DARK_RED, TextDecoration.BOLD));
        checkFinish();
    }

    private void checkFinish() {
        if (finished) {
            return;
        }
        List<VersusPlayer> alive = new ArrayList<>();
        for (VersusPlayer participant : participants) {
            if (participant.alive()) {
                alive.add(participant);
            }
        }
        if (alive.size() > 1) {
            return;
        }
        finished = true;
        winner = alive.isEmpty() ? null : alive.get(0);
        announce(winner == null
                ? Component.text("引き分け", NamedTextColor.GRAY, TextDecoration.BOLD)
                : Component.text(winner.name() + " の勝利！", NamedTextColor.GOLD, TextDecoration.BOLD));
        listener.onMatchEnded(this, winner);
    }

    public void dispose() {
        for (VersusPlayer participant : participants) {
            if (participant.island() != null) {
                participant.island().dispose();
            }
        }
        participants.clear();
    }

    // ================================================================ 通知

    public void announce(Component message) {
        for (VersusPlayer participant : participants) {
            Player player = participant.player();
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    public void playSound(SoundEvent event, float volume, float pitch) {
        for (VersusPlayer participant : participants) {
            Player player = participant.player();
            if (player != null) {
                player.playSound(net.kyori.adventure.sound.Sound.sound(
                        event, net.kyori.adventure.sound.Sound.Source.MASTER, volume, pitch));
            }
        }
    }
}
