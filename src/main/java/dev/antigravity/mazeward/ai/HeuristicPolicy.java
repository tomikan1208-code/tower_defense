package dev.antigravity.mazeward.ai;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.PathFinder;
import dev.antigravity.mazeward.core.PathResult;
import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.versus.AttackerKind;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 学習済み方策が無いときの相手。<b>その場で計算する貪欲ボット</b>。
 *
 * <p>{@code ai/mazeward_env/bots_heuristic.py} の {@code greedy_defense} と
 * 同じ考え方（経路がいちばん伸びる壁 → 経路を最も長く射程に収める塔 →
 * 余ったら送る）。学習のリーグでも「動かない基準」として同じ役をしている。</p>
 *
 * <p>これがあるおかげで、Python を立てていなくても対戦と観戦が成立する。
 * <b>AI ブリッジが落ちていることが、遊べない理由になってはいけない。</b></p>
 */
public final class HeuristicPolicy implements AiPolicy {

    /** 壁の置き場所を探す試行回数。全マス全回転を試すと 1 手に数千回の経路探索になる。 */
    private static final int CARD_TRIES = 48;

    /**
     * 守りに残す予備費を「次の 1 手ぶんのコスト」の何倍にするか。
     *
     * <p><b>定額にしない。</b> 強化費は 1 段ごとに 2.6 倍で伸びるので、
     * 定額の予備費（旧: 40 コイン）は数分で実質ゼロになり、
     * 「余ったら送る」が「常に送る」に化ける。</p>
     */
    private static final double DEFENCE_RESERVE_RATIO = 1.0;

    /** 塔がこれだけ建つまでは、守りへ多めに残す。 */
    private static final int EARLY_TOWERS = 12;


    private final Random random = new Random();
    private final List<AiAction.Seated> ready = new ArrayList<>();

    @Override
    public String name() {
        return "貪欲ボット";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean pending() {
        return false;
    }

    @Override
    public void request(VersusMatch match, List<Integer> askSeats,
                        MatchSnapshot.SeatStats[] stats) {
        List<VersusPlayer> participants = match.participants();
        for (int seat : askSeats) {
            if (seat < 0 || seat >= participants.size()) {
                continue;
            }
            VersusPlayer player = participants.get(seat);
            if (!player.alive() || player.island() == null) {
                continue;
            }
            ready.add(new AiAction.Seated(seat, decide(match, player)));
        }
    }

    @Override
    public List<AiAction.Seated> poll() {
        if (ready.isEmpty()) {
            return List.of();
        }
        List<AiAction.Seated> batch = List.copyOf(ready);
        ready.clear();
        return batch;
    }

    @Override
    public void close() {
    }

    // ================================================================ 判断

    /**
     * 1 手を選ぶ。<b>順番そのものが戦略</b>になっている。
     *
     * <p>送りを最後に置いてあるのが肝。送りは常に「買える」ので、先に見ると
     * <b>毎秒それだけを選び続けて守りが一生育たない</b>（実際に、送りを先頭に
     * 置いた版は 25 分でタワー 5 基・送り 1000 回という盤面になった）。
     * 守りに使い切れなかったぶんだけを送りに回す形にすると、
     * 「固めてから伸ばす」という人間が最初に覚える形になる。</p>
     *
     * <p><b>ただし 1 つだけ例外を置く。</b> 強化費はいちばん安い段が
     * 常に手の届く額なので、順番どおりだと「毎秒どれかを強化する」だけで
     * 送りに一度も到達しない。実際にそうなって、25 分でインカム 22・
     * 送り 11 回・全員引き分けという試合になった。</p>
     *
     * <p>そこで <b>ストックが満タンになったとき</b>（＝これ以上溜められず、
     * 回復ぶんを捨てている状態）と、<b>盤面を建て切ったとき</b>だけ、
     * 送りを先に見る。ストックは毎秒 1 しか回復せず、これが送りの持続レートそのもの。
     * 満タンになるまで 30 秒かかるので、そのあいだは建設が手を持つ。
     * 送るとストックが減り、次は自然に建設側へ戻る。
     *
     * <p>半分（15）で割り込ませた版は、少しずつしか減らないので
     * 送り分岐が毎秒立ち続け、塔が下限の 10 基で固まった。</p>
     * 絶対値の閾値（コイン 400 など）を置かないのは、経済が指数で伸びるため
     * ——固定値は数分で常に真になり、分岐が死ぬ。</p>
     */
    private AiAction decide(VersusMatch match, VersusPlayer player) {
        Island island = player.island();

        AiAction card = bestCard(player, island);
        if (card != null) {
            return card;
        }
        if (player.stock() >= VersusPlayer.MAX_STOCK
                || island.towers().size() >= Island.MAX_TOWERS) {
            // **ここでは予備費を引かない。** 引くと、いちばん安い強化
            // （弓塔 27 コイン）と予備費（30）が同じ桁なので、
            // 強化がコインを先に食い尽くして送りの条件が永久に立たない。
            // 送るとストックが減り、次は自然に建設側へ戻る。
            //
            // **体数のほうには予備費を効かせる。** 判定と体数で分けるのが肝で、
            // 両方に効かせると分岐が立たず（上記）、両方外すと今度は
            // コインを全部送りに吸われてタワーが 4 基しか建たなくなる。
            // 判定は予備費なし・**体数には予備費あり**。
            // 判定にも効かせると、いちばん安い強化（弓塔 27 コイン）と
            // 予備費が同じ桁なので分岐が永久に立たない。
            // 両方外すとコインを全部送りに吸われて塔が建たない。
            // 「1 体は必ず送る、余っていれば増やす」が釣り合う
            AttackerKind invest = bestSend(player, match, 0);
            if (invest != null) {
                return new AiAction.Send(invest, batchSize(player, match, invest));
            }
        }
        AiAction tower = bestTower(player, island);
        if (tower != null) {
            return tower;
        }
        AiAction upgrade = bestUpgrade(player, island);
        if (upgrade != null) {
            return upgrade;
        }
        AttackerKind send = bestSend(player, match);
        if (send != null) {
            return new AiAction.Send(send, batchSize(player, match, send));
        }
        return new AiAction.Skip();
    }

    /**
     * 何を送るか。<b>「毎秒 1 回の送りで使い切れる額」を基準に選ぶ。</b>
     *
     * <p>ストックは毎秒 1 しか回復せず消費はどれも 1 なので、送れる回数は
     * 1 秒 1 回で頭打ち。一方コインは毎秒 {@code インカム / 収入間隔} 増える。
     * だから <b>その額を 1 回で使い切れる、いちばん高いインカムモブ</b> が最適になる。
     * 安いと送る回数が足りずコインが余り、高いと貯める時間ぶん送る回数が減るうえ、
     * 梯子は上ほどインカム比率が悪いので二重に損。
     * 貯まったぶんは収入 1 回ぶんの時間で吐き出す項として足す
     * （梯子は飛び飛びなので、収入ぴったりで切ると死蔵される帯ができる）。</p>
     *
     * <p>実測: この式が income 73、「買える中でいちばん高いもの」が income 49。
     * 回数あたりで選ぶほうが良さそうに見えて、比率の悪化で負ける。</p>
     *
     * <p>守りが薄いうちは多めに手元へ残す。序盤に送り切ると、
     * 返ってきた敵を受けきれずに自分が先に溶ける。</p>
     */
    private AttackerKind bestSend(VersusPlayer player, VersusMatch match) {
        return bestSend(player, match, defenceReserve(player));
    }

    private AttackerKind bestSend(VersusPlayer player, VersusMatch match,
                                  int reserve) {
        if (match.preparing() || player.stock() < 1) {
            return null;
        }
        double budget = player.coins() - reserve;
        if (budget <= 0) {
            return null;
        }
        double ceiling = (player.income() + player.coins())
                / (double) Math.max(1, match.incomeSeconds());
        AttackerKind best = null;
        for (AttackerKind kind : AttackerKind.unlockedAt(player.income())) {
            if (kind.incomeGain() <= 0 || !player.canSend(kind)
                    || kind.cost() > budget) {
                continue;
            }
            // 使い切れる範囲でいちばん高いもの。届かないうちはいちばん安いもの
            if (best == null || (kind.cost() <= ceiling && kind.cost() > best.cost())) {
                best = kind;
            }
        }
        return best;
    }

    /** 守りに残す額。次に建てる／強化する 1 手ぶん。 */
    private int defenceReserve(VersusPlayer player) {
        Island island = player.island();
        int towers = island.towers().size();
        int base = Integer.MAX_VALUE;
        for (TowerKind kind : TowerKind.values()) {
            base = Math.min(base, kind.baseCost());
        }
        double reserve = base * DEFENCE_RESERVE_RATIO;
        if (towers < EARLY_TOWERS) {
            reserve *= 3;
        }
        return (int) Math.round(reserve);
    }

    /**
     * 何体まとめて送るか。買えるだけ送って、残りの手を建設へ回す。
     *
     * <p>持続レートはストック回復（毎秒 1）で決まるのでまとめても総数は増えない。
     * 増えるのは <b>空く手数</b> のほう。</p>
     */
    private int batchSize(VersusPlayer player, VersusMatch match, AttackerKind kind) {
        return batchSize(player, match, kind, defenceReserve(player));
    }

    private int batchSize(VersusPlayer player, VersusMatch match, AttackerKind kind,
                          int reserve) {
        int budget = (int) Math.max(0, player.coins() - reserve);
        int byCoins = budget / Math.max(1, kind.cost());
        int byStock = player.stock() / Math.max(1, kind.stockCost());
        return Math.max(1, Math.min(Math.min(byCoins, byStock),
                VersusMatch.MAX_SEND_BATCH));
    }

    /** 経路がいちばん伸びる置き方。伸びないなら置かない（壁は撤去できない）。 */
    private AiAction bestCard(VersusPlayer player, Island island) {
        if (player.deck().hand().isEmpty()) {
            return null;
        }
        int slot = random.nextInt(player.deck().hand().size());
        Shape shape = player.deck().hand().get(slot).shape();
        Grid grid = island.grid();

        Vec2i bestOrigin = null;
        Rot bestRot = Rot.R0;
        double bestDelta = 0.5;
        for (int i = 0; i < CARD_TRIES; i++) {
            Rot rot = Rot.values()[random.nextInt(4)];
            Vec2i cursor = new Vec2i(random.nextInt(grid.width()), random.nextInt(grid.height()));
            Vec2i origin = island.originFor(shape, cursor, rot);
            Battlefield.PlacementPreview preview = island.preview(shape, origin, rot);
            if (preview.ok() && preview.delta() > bestDelta) {
                bestDelta = preview.delta();
                bestOrigin = origin;
                bestRot = rot;
            }
        }
        return bestOrigin == null ? null : new AiAction.Card(slot, bestRot, bestOrigin);
    }

    /**
     * 経路をいちばん長く射程に収める土台へ塔を置く。
     *
     * <p>種類は「好み順」に試す。<b>1 種類だけを見て諦めると、その形が
     * 収まらない盤面で塔がそこで止まる</b>（実際、砲の番になった島だけ
     * 5 基で建築が止まった）。形が入らないなら次の候補へ移る。</p>
     */
    private AiAction bestTower(VersusPlayer player, Island island) {
        if (island.towers().size() >= Island.MAX_TOWERS) {
            return null;
        }
        List<Vec2i> path = pathCells(island);
        if (path.isEmpty()) {
            return null;
        }
        for (TowerKind kind : preferredKinds(island.towers().size())) {
            if (player.coins() < kind.baseCost()) {
                continue;
            }
            Vec2i best = bestSpot(island, kind, path);
            if (best != null) {
                return new AiAction.Tower(kind, Rot.R0, best);
            }
        }
        return null;
    }

    private Vec2i bestSpot(Island island, TowerKind kind, List<Vec2i> path) {
        double range = kind.statsAt(0).range();
        Grid grid = island.grid();
        Vec2i best = null;
        int bestScore = 0;
        for (int x = 0; x < grid.width(); x++) {
            for (int z = 0; z < grid.height(); z++) {
                CellType type = grid.get(x, z);
                if (type != CellType.WALL && type != CellType.ROCK) {
                    continue;
                }
                Vec2i origin = new Vec2i(x, z);
                if (island.towerPlacementError(kind, origin, Rot.R0) != null) {
                    continue;
                }
                int score = covered(path, x + 0.5, z + 0.5, range);
                if (score > bestScore) {
                    bestScore = score;
                    best = origin;
                }
            }
        }
        return best;
    }

    /**
     * 塔の種類。弓を主軸に、たまに氷と砲を混ぜる。
     *
     * <p>1 種類だけで並べると、観戦したときに盤面が単調になるうえ、
     * 減速と範囲が効かない状況が出ない。<b>基準として見せる以上、
     * 一通りの塔が動いているほうが判断材料になる。</b></p>
     */
    private List<TowerKind> preferredKinds(int built) {
        if (built % 5 == 3) {
            return List.of(TowerKind.FROST, TowerKind.ARROW, TowerKind.CANNON);
        }
        if (built % 7 == 5) {
            return List.of(TowerKind.CANNON, TowerKind.ARROW, TowerKind.FROST);
        }
        return List.of(TowerKind.ARROW, TowerKind.FROST, TowerKind.CANNON);
    }

    /**
     * いちばん経路を見ている塔を強化する。特化はランダムに選ぶ。
     *
     * <p>コストの 2 倍を持っているときだけ強化する。ぴったりで払うと、
     * 直後に敵が来ても何も置けない。</p>
     */
    private AiAction bestUpgrade(VersusPlayer player, Island island) {
        // **盤面を埋めるまで強化しない。** 強化はいちばん安い段が常に手の届く額なので、
        // 建設と同列に置くと毎秒そちらへ流れ、塔の数が下限で止まる
        // （実測でちょうど MIN_DEFENCE_TOWERS の 10 基で固まった）。
        // 数を並べてから伸ばす順にすると、予備費が新しい塔のぶんとして残る
        if (island.towers().size() < Island.MAX_TOWERS) {
            return null;
        }
        List<Vec2i> path = pathCells(island);
        List<TowerInstance> towers = island.towers();
        int bestIndex = -1;
        int bestScore = -1;
        for (int i = 0; i < towers.size(); i++) {
            TowerInstance tower = towers.get(i);
            if (tower.maxed() || player.coins() < tower.nextUpgradeCost() * 2) {
                continue;
            }
            int score = covered(path, tower.centerX(), tower.centerZ(),
                    island.resolvedStats(tower).range());
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? null
                : new AiAction.Upgrade(bestIndex, random.nextInt(2));
    }

    // ================================================================ 補助

    private static List<Vec2i> pathCells(Island island) {
        List<Vec2i> cells = new ArrayList<>();
        for (PathResult path : island.paths()) {
            if (path.reachable()) {
                cells.addAll(PathFinder.traversedCells(path.waypoints()));
            }
        }
        return cells;
    }

    private static int covered(List<Vec2i> path, double cx, double cz, double range) {
        double squared = range * range;
        int count = 0;
        for (Vec2i cell : path) {
            double dx = cell.x() + 0.5 - cx;
            double dz = cell.z() + 0.5 - cz;
            if (dx * dx + dz * dz <= squared) {
                count++;
            }
        }
        return count;
    }
}
