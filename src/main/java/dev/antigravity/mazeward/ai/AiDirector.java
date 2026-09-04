package dev.antigravity.mazeward.ai;

import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI が操作している席をまとめて動かす。
 *
 * <p><b>1 秒に 1 手。</b> 学習環境の 1 ステップ（{@code rules.DECISION_TICKS} = 20 tick）
 * と同じ刻みにしてある。方策は「1 秒に 1 手」という前提で価値を学んでいるので、
 * ここを速くすると学習時より豊かな手数を持つことになり、
 * <b>学んだ配分（守り / 送りの比率）がそのまま崩れる</b>。人間も 1 秒に
 * 何手も打たないので、見た目としても速すぎない。</p>
 *
 * <p>要求と適用は別の tick でよい。方策は別プロセスにいるので、
 * 返事を待って止まるより 1 tick 遅れて打つほうがいい。</p>
 */
public final class AiDirector {

    /** 意思決定の間隔（ゲーム tick）。学習環境の 1 ステップと同じ。 */
    public static final int DECISION_TICKS = 20;

    private final VersusMatch match;
    private final List<Integer> seats = new ArrayList<>();
    private final MatchSnapshot.SeatStats[] stats;
    private final String[] lastAction;
    private final int[] steps;
    private final int[] invalid;

    private AiPolicy policy;
    private final HeuristicPolicy fallback = new HeuristicPolicy();
    private final BrainClient brain;
    private int lastDecisionTick = -DECISION_TICKS;
    private Consumer<String> policyListener;

    /**
     * @param brain 学習済み方策への接続。null なら最初から貪欲ボットで回す
     */
    public AiDirector(VersusMatch match, BrainClient brain) {
        this.match = match;
        this.brain = brain;
        this.policy = brain == null ? fallback : brain;
        int count = match.participants().size();
        this.stats = new MatchSnapshot.SeatStats[count];
        this.lastAction = new String[count];
        this.steps = new int[count];
        this.invalid = new int[count];
        for (int i = 0; i < count; i++) {
            stats[i] = MatchSnapshot.SeatStats.EMPTY;
            lastAction[i] = "";
        }
    }

    /** この席を AI に任せる。 */
    public void control(int seat) {
        if (!seats.contains(seat)) {
            seats.add(seat);
        }
    }

    public boolean controls(int seat) {
        return seats.contains(seat);
    }

    /** いま実際に使っている頭の名前。観戦のサイドバーに出す。 */
    public String policyName() {
        return policy.name();
    }

    /**
     * 方策の返事を待っている最中か。
     *
     * <p>実ゲームでは待たない（1 秒に 1 手なので遅れても平気）。
     * これを見るのは、ゲーム内時間を実時間より速く進める
     * ヘッドレス検証（{@code AiSim}）だけ。そこで待たないと、
     * <b>AI が考え終わる前に試合が終わってしまう</b>。</p>
     */
    public boolean thinking() {
        return policy.pending();
    }

    /** 席が最後に打った手の説明。観戦の表示と、将来の「操作する姿」の材料。 */
    public String lastAction(int seat) {
        return seat >= 0 && seat < lastAction.length ? lastAction[seat] : "";
    }

    /**
     * 1 ゲーム tick 進める。倍速では 1 サーバー tick に何度も呼ばれる。
     *
     * @param matchTick 試合の経過 tick
     */
    public void tick(int matchTick) {
        apply(policy.poll());

        if (matchTick - lastDecisionTick < DECISION_TICKS) {
            return;
        }
        lastDecisionTick = matchTick;
        switchPolicyIfNeeded();
        if (policy.pending()) {
            return;
        }
        List<Integer> asking = new ArrayList<>();
        for (int seat : seats) {
            VersusPlayer player = participant(seat);
            if (player != null && player.alive() && player.island() != null) {
                asking.add(seat);
                steps[seat]++;
                stats[seat] = new MatchSnapshot.SeatStats(steps[seat], invalid[seat]);
            }
        }
        if (!asking.isEmpty()) {
            policy.request(match, asking, stats);
        }
    }

    /**
     * ブリッジが落ちたら貪欲ボットへ、戻ってきたらブリッジへ。
     *
     * <p>試合の途中で頭がすり替わるのは乱暴に見えるが、
     * <b>途中で AI が止まって案山子になるほうが観戦としては壊れている</b>。</p>
     *
     * <p>切り替わったことは必ず外へ知らせる。黙って入れ替わると、
     * プレイヤーには「AI が急に弱く（強く）なった」としか見えない。</p>
     */
    private void switchPolicyIfNeeded() {
        if (brain == null) {
            return;
        }
        if (brain.available() && policy != brain) {
            policy = brain;
            announce("AI の頭を " + policy.name() + " に切り替えました");
        } else if (!brain.available() && policy != fallback) {
            policy = fallback;
            announce("学習済み方策に繋がらないので " + fallback.name() + " に切り替えました");
        }
    }

    /** 頭が切り替わったときの通知先。ゲーム側がチャットへ流す。 */
    public void onPolicyChanged(Consumer<String> listener) {
        this.policyListener = listener;
        // 登録した時点の状態も 1 度伝える（試合開始直後の表示に使う）
        listener.accept("AI: " + policy.name());
    }

    private void announce(String message) {
        System.out.println("[MAZEWARD] " + message);
        if (policyListener != null) {
            policyListener.accept(message);
        }
    }

    private VersusPlayer participant(int seat) {
        List<VersusPlayer> participants = match.participants();
        return seat >= 0 && seat < participants.size() ? participants.get(seat) : null;
    }

    // ================================================================ 適用

    private void apply(List<AiAction.Seated> batch) {
        for (AiAction.Seated seated : batch) {
            VersusPlayer player = participant(seated.seat());
            if (player == null || !player.alive() || player.island() == null) {
                continue;
            }
            boolean ok = apply(player, seated.action());
            if (!ok) {
                invalid[seated.seat()]++;
            }
            if (!(seated.action() instanceof AiAction.Skip)) {
                lastAction[seated.seat()] = (ok ? "" : "× ") + AiAction.describe(seated.action());
            }
        }
    }

    /**
     * 1 手を島へ適用する。
     *
     * <p><b>合法性の判定はここではなく島が持つ。</b> 方策側にも同じ判定は入っているが、
     * 別プロセスで 1 tick 前の状態を見て決めている以上、必ずずれる瞬間がある。
     * 失敗は黙って捨てて「無効手」として数え、次の観測に混ぜる
     * （方策は自分の無駄打ち率を観測として見ている）。</p>
     */
    private boolean apply(VersusPlayer player, AiAction action) {
        Island island = player.island();
        return switch (action) {
            case AiAction.Skip ignored -> true;
            case AiAction.Card card -> {
                if (card.slot() < 0 || card.slot() >= player.deck().hand().size()) {
                    yield false;
                }
                Battlefield.Outcome outcome =
                        island.placeCard(card.slot(), card.origin(), card.rot());
                yield outcome.success();
            }
            case AiAction.Tower tower ->
                    island.placeTower(tower.kind(), tower.origin(), tower.rot()).success();
            case AiAction.Upgrade upgrade -> {
                TowerInstance target = towerAt(island, upgrade.towerIndex());
                if (target == null || target.maxed()) {
                    yield false;
                }
                TowerKind.Spec spec = null;
                if (target.nextIsSpecialization()) {
                    List<TowerKind.Spec> specs = target.kind().specs();
                    int index = Math.floorMod(upgrade.spec(), Math.max(1, specs.size()));
                    spec = specs.isEmpty() ? null : specs.get(index);
                }
                yield island.upgradeTower(target.origin(), spec).success();
            }
            case AiAction.Sell sell -> {
                TowerInstance target = towerAt(island, sell.towerIndex());
                yield target != null && island.sellTower(target.origin()).success();
            }
            case AiAction.Send send -> {
                if (match.preparing() || !player.canSend(send.kind())) {
                    yield false;
                }
                match.send(player, send.kind(), send.count());
                yield true;
            }
        };
    }

    /**
     * 番号で塔を引く。並びはスナップショットを作ったときのまま
     * （{@code Island#towers()} の順）。
     */
    private static TowerInstance towerAt(Island island, int index) {
        List<TowerInstance> towers = island.towers();
        return index >= 0 && index < towers.size() ? towers.get(index) : null;
    }

    /** 塔の位置。将来「AI の姿がそこへ歩いていく」ときの目的地になる。 */
    public Vec2i focusOf(int seat) {
        VersusPlayer player = participant(seat);
        if (player == null || player.island() == null) {
            return null;
        }
        List<TowerInstance> towers = player.island().towers();
        return towers.isEmpty() ? null : towers.get(towers.size() - 1).origin();
    }

    /**
     * 試合の終わり。
     *
     * <p><b>ブリッジは閉じない。</b> Python 側は torch の読み込みだけで数秒かかるので、
     * 試合ごとに立て直すと「次の試合の開始が毎回もたつく」。接続はサーバーが
     * 抱えたままにして、試合はそれを借りるだけにしてある。</p>
     */
    public void close() {
        fallback.close();
    }
}
