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
 *   <li>コインは 10 秒ごとに「インカム」ぶん入る（人数によらず一定）</li>
 *   <li><b>インカムが増えるのは送ったときだけ</b>。守りに使うか収入に回すかが最大の判断</li>
 *   <li>撃破するとコインが入り、そのぶんインカムも少し伸びる</li>
 *   <li>送りは相手を選べない。<b>生き残っている全員に同時に飛ぶ</b></li>
 *   <li>ストックは 30、毎秒 1 回復。<b>どのモンスターも消費は 1</b>。
 *       これは「1 秒に 1 回しか送れない」という回数制限であって、強さの値付けではない</li>
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

    /**
     * 収入が入る間隔。<b>人数によらず常に 10 秒</b>（Hypixel TowerWars と同じ）。
     *
     * <p>かつては「少人数ほど速く」していた。人数が少ないと自分に届く敵も少なく、
     * 撃破報酬が細ってコインが貯まらないので、そのぶんを定期収入の速さで補う狙いだった。
     * <b>だがこの補正は指数の肩に乗る。</b> インカムは
     * {@code 収入間隔ぶんの一} の速さで自己増殖するので、間隔を半分にすると
     * 成長率がそのまま倍になり、20 分後には桁違いの差になる。
     * 少人数の不利は {@link AttackerKind#KILL_REWARD_TOTAL}
     * （撃破報酬の総量を人数で割って一定にする）側で埋めてある。</p>
     */
    public static final int INCOME_INTERVAL = 20 * 10;

    /** ストックの回復間隔。 */
    public static final int STOCK_INTERVAL = 20;

    /**
     * 1 回の操作でまとめて送れる上限。ストック上限と同じ。
     *
     * <p>人間はもともと送りメニューを連打すればストックぶん撃てる。
     * これは <b>AI が 1 手でそれを表せるようにする</b> ためのもので、
     * 新しいルールではない。持続レートはストック回復（毎秒 1）で決まるので
     * まとめても総数は増えず、増えるのは「空く手数」のほう。</p>
     */
    public static final int MAX_SEND_BATCH = VersusPlayer.MAX_STOCK;

    /**
     * まとめ送りしたときに、1 体ずつずらして湧かせる間隔（tick）。
     *
     * <p><b>同座標に一度に湧かせてはいけない。</b> 範囲攻撃と連鎖が 1 塊に当たるので、
     * 実際より柔らかく（単体火力には硬く）なる。人間が連打しても 1 体ずつ間が空くので、
     * そちらに合わせる。学習環境の戦闘刻み（{@code COMBAT_DT} = 4 tick）と同じ値。</p>
     */
    public static final int SEND_STAGGER_TICKS = 4;

    /**
     * 送りの厚みを揃える基準の相手人数（＝ 6 人ロビー）。
     *
     * <p><b>送りは生き残っている全員に同時に飛ぶ。</b> つまり守る側から見た
     * 「浴びる量」は相手の人数にそのまま比例するのに、自分が建てられる塔は
     * {@code Island.MAX_TOWERS} 基で変わらない。2 人戦では 1 体しか来ないので
     * 何を送っても抜けず（決着率 0%）、8 人戦では 7 体同時に来るので
     * 守りようがない、という形になっていた。</p>
     *
     * <p>撃破報酬と同じ原理で、<b>1 回の送りが盤面に生む総量を人数によらず一定</b>に
     * する。総量を揃える先がコインなら {@code KILL_REWARD_TOTAL}、
     * 耐力ならここ。人数が少ないほど 1 体が分厚くなる。</p>
     */
    public static final int REFERENCE_OPPONENTS = 5;

    /**
     * 人数正規化の効かせ具合。1.0 で「浴びる耐力の総量」がぴったり揃う。
     *
     * <p><b>1.0 では効きすぎる。</b> 実測すると 2 人戦が今度は送り側の勝率 100% になった。
     * <b>1 体を 5 倍太らせるのと 5 体送るのは等価ではない</b>ためで、
     * 範囲攻撃・連鎖・燃焼はどれも「体数」に効くので、
     * 太った 1 体は同じ総耐力の 5 体よりずっと硬い。</p>
     *
     * <p>2 人 / 4 人 / 8 人 x 24 試合 x 20 分の対称性チェックを
     * 0.3 / 0.5 / 0.7 で回して決めた（{@code ai/sweep_send_power.py}）。
     * 引き分けを除いた守り側の勝率:</p>
     *
     * <pre>
     *          2 人    4 人    8 人
     *   0.3    100%    86%   100%
     *   0.5     91%    66%    50%
     *   0.7     48%    46%    25%   ← 採用
     * </pre>
     */
    public static final double SEND_POWER_EXPONENT = 0.7;

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
    private int incomeTimer;
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

    /**
     * 送られたモンスターの耐力に掛かる倍率。
     *
     * <p>相手が少ないほど 1 体が分厚くなる
     * （2 人戦で 2.24 倍、6 人戦で等倍、8 人戦で 0.85 倍）。</p>
     *
     * <p><b>平方根なのは、実測でそうしないと合わなかったから。</b>
     * 人数ぶんをそのまま（{@code 5 / 相手人数}）掛けると効きすぎて、
     * 2 人戦が今度は送り側の勝率 100% になった。
     * <b>1 体を 5 倍太らせるのと、5 体送るのは等価ではない</b>ためで、
     * 範囲攻撃・連鎖・燃焼はどれも「体数」に効くので、
     * 太った 1 体は同じ総耐力の 5 体よりずっと硬い。
     * 半分だけ埋めるくらいがちょうど釣り合う
     * （2 人 / 4 人 / 8 人での対称性チェックで測った）。</p>
     *
     * <p>体数ではなく耐力で揃えるのは、体数で揃えると 2 人戦で毎秒 5 体湧いて
     * エンティティが破綻するため。</p>
     */
    public double sendPowerScale() {
        return Math.pow((double) REFERENCE_OPPONENTS / Math.max(1, aliveCount() - 1),
                SEND_POWER_EXPONENT);
    }

    /** 生き残っている参加者の数。 */
    public int aliveCount() {
        int count = 0;
        for (VersusPlayer participant : participants) {
            if (participant.alive()) {
                count++;
            }
        }
        return count;
    }

    /** 現在の収入間隔。人数によらず一定（{@link #INCOME_INTERVAL} の説明を参照）。 */
    public int incomeInterval() {
        return INCOME_INTERVAL;
    }

    /** 収入間隔の秒数。表示用。 */
    public int incomeSeconds() {
        return incomeInterval() / 20;
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
        tick(true);
    }

    /**
     * 1 tick 進める。
     *
     * @param render 見た目（経路のパーティクル）を描くか。
     *               観戦の倍速で 1 サーバー tick に何度も進めるとき、
     *               <b>最後の 1 回以外は描かない</b>。同じ線を 8 回描いても
     *               見た目は変わらないのに、パケットだけ 8 倍になる
     */
    public void tick(boolean render) {
        if (finished) {
            return;
        }
        tick++;

        for (VersusPlayer participant : participants) {
            participant.decaySendHistory();
            if (participant.island() != null) {
                participant.island().tick(render);
            }
        }

        if (tick % STOCK_INTERVAL == 0) {
            for (VersusPlayer participant : participants) {
                participant.regenerateStock();
            }
        }
        // 間隔は人数で変わるので、剰余ではなく専用のタイマーで数える
        incomeTimer++;
        if (incomeTimer >= incomeInterval()) {
            incomeTimer = 0;
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
        return send(sender, kind, 1);
    }

    /**
     * まとめて送る。
     *
     * <p><b>払える数まで黙って切り詰める。</b>「30 送ろうとして 7 しか送れなかった」は
     * 失敗ではなく普通の判断なので、ここを弾くと大きい数を選ぶこと自体が怖くなる
     * （AI にとってはそのまま学習の障害になる）。</p>
     *
     * @param count 送りたい体数。1 未満は 1 に、{@link #MAX_SEND_BATCH} 超は上限に丸める
     */
    public String send(VersusPlayer sender, AttackerKind kind, int count) {
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
        int want = Math.max(1, Math.min(count, MAX_SEND_BATCH));
        int affordable = Math.min(sender.coins() / Math.max(1, kind.cost()),
                sender.stock() / Math.max(1, kind.stockCost()));
        int batch = Math.min(want, affordable);
        if (batch <= 0) {
            return sender.stock() < kind.stockCost()
                    ? "ストックが足りません（" + kind.stockCost() + " 必要 / 残り "
                            + sender.stock() + "）"
                    : "コインが足りません（" + kind.cost() + " 必要）";
        }
        for (int i = 0; i < batch; i++) {
            if (!sender.paySend(kind)) {
                batch = i;
                break;
            }
        }
        if (batch <= 0) {
            return "コインが足りません（" + kind.cost() + " 必要）";
        }

        int targets = 0;
        for (VersusPlayer other : participants) {
            if (other == sender || !other.alive() || other.island() == null) {
                continue;
            }
            other.island().receive(kind, sender, batch);
            targets++;
        }

        String many = batch > 1 ? " x" + batch : "";
        announce(Component.text(sender.name() + " が " + kind.displayName() + many
                + " を送った（インカム +" + kind.incomeGain() * batch + "）", NamedTextColor.YELLOW));
        return kind.displayName() + many + " を " + targets + " 人へ送信（インカム "
                + sender.income() + "）";
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
