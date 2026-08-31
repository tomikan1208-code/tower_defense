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

    /** 送るときに手元へ残すコイン。使い切ると塔が建たなくなる。 */
    private static final int SEND_RESERVE = 40;

    /** これだけストックが貯まるまで送らない。重い送りを撃てる体にしておくため。 */
    private static final int STOCK_FLOOR = 20;

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
     */
    private AiAction decide(VersusMatch match, VersusPlayer player) {
        Island island = player.island();

        AiAction card = bestCard(player, island);
        if (card != null) {
            return card;
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
            return new AiAction.Send(send);
        }
        return new AiAction.Skip();
    }

    /**
     * 送れるもののうち、いちばん高いもの。インカムがいちばん伸びる。
     *
     * <p>守りが薄いうちは多めに手元へ残す。序盤に送り切ると、
     * 返ってきた敵を受けきれずに自分が先に溶ける。</p>
     */
    private AttackerKind bestSend(VersusPlayer player, VersusMatch match) {
        if (match.preparing()) {
            return null;
        }
        // ストックが貯まるまで待つ。毎秒 1 回復しかしないので、
        // 買えるたびに送ると **ストック 1 の雑魚しか送れない体** になり、
        // 相手のタワーに溶けるだけの送りを延々と続けることになる
        if (player.stock() < STOCK_FLOOR) {
            return null;
        }
        int reserve = player.island().towers().size() < 12
                ? SEND_RESERVE * 3 : SEND_RESERVE;
        List<AttackerKind> options = AttackerKind.unlockedAt(player.income());
        for (int i = options.size() - 1; i >= 0; i--) {
            AttackerKind kind = options.get(i);
            if (player.canSend(kind) && player.coins() - kind.cost() >= reserve) {
                return kind;
            }
        }
        return null;
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
