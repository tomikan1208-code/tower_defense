package dev.antigravity.mazeward.ai;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.PathResult;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.enemy.EnemyInstance;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.List;

/**
 * 試合の状態を Python の方策へ渡す JSON にする。
 *
 * <p><b>渡すのは生の状態だけで、観測は作らない。</b> 観測（14 チャンネルの盤面・
 * 126 個のスカラー・相手の要約）は学習に使った {@code ai/mazeward_env/observation.py}
 * が唯一の定義なので、Java 側で組み立て直すと <b>学習時と推論時で意味の違う
 * ベクトルを食わせる</b> 事故が静かに起きる。並びを手で写した 1000 行が
 * どこか 1 箇所ずれても、AI が「なんとなく弱い」だけで誰も気付けない。
 * だからこちらは事実（盤面・塔・敵・財布）を送り、
 * <b>観測の組み立ては学習と同じコードに任せる</b>。</p>
 *
 * <p>1 回の要求に <b>全員ぶんの島</b> を入れる。相手の特徴（経路長・カバレッジ・
 * 送りの履歴）が観測に入っているので、AI が 1 人でも全員の盤面が要る。
 * 席ごとに要求を分けると同じ情報を人数ぶん送ることになる。</p>
 *
 * <p>座標はすべて <b>セル座標</b>（ワールド座標ではない）。島はワールド上で
 * 離れた場所に並んでいるが、盤面としては全員 (0,0) 始まりで同じ形をしている。</p>
 */
public final class MatchSnapshot {

    private MatchSnapshot() {
    }

    /** 席ごとの補助統計。方策の観測に「自分がどれくらい無駄手を打っているか」が入っている。 */
    public record SeatStats(int steps, int invalid) {
        public static final SeatStats EMPTY = new SeatStats(0, 0);
    }

    /**
     * @param askSeats 行動を求める席（AI が操作している席）
     * @param stats    席ごとの統計。null なら 0 として送る
     */
    public static String build(VersusMatch match, List<Integer> askSeats, SeatStats[] stats) {
        Json json = new Json();
        json.beginObject();
        json.field("v", 1);

        json.key("match").beginObject()
                .field("tick", match.elapsedTicks())
                .field("prepTicks", VersusMatch.PREP_TICKS)
                .field("suddenDeath", VersusMatch.SUDDEN_DEATH_TICKS)
                .field("cardInterval", VersusMatch.CARD_INTERVAL)
                .field("handLimit", match.handLimit())
                .field("maxTowers", Island.MAX_TOWERS)
                .field("players", match.playerCount())
                .endObject();

        json.key("boards").beginArray();
        List<VersusPlayer> participants = match.participants();
        for (int seat = 0; seat < participants.size(); seat++) {
            board(json, seat, participants.get(seat),
                    stats == null || seat >= stats.length ? SeatStats.EMPTY : stats[seat]);
        }
        json.endArray();

        json.key("ask").beginArray();
        for (int seat : askSeats) {
            json.value(seat);
        }
        json.endArray();

        json.endObject();
        return json.toString();
    }

    private static void board(Json json, int seat, VersusPlayer player, SeatStats stats) {
        Island island = player.island();
        json.beginObject();
        json.field("seat", seat);
        json.field("name", player.name());
        json.field("alive", player.alive());
        json.field("coins", player.coins());
        json.field("income", player.income());
        json.field("stock", player.stock());
        json.field("lives", player.lives());
        json.field("maxLives", player.maxLives());
        json.field("steps", stats.steps());
        json.field("invalid", stats.invalid());

        json.key("sends").beginObject()
                .field("d10", player.sendDecay10())
                .field("d30", player.sendDecay30())
                .field("total", player.sendsTotal())
                .field("lastCost", player.lastSendCost())
                .field("income", player.sentIncome())
                .endObject();

        json.key("hand").beginArray();
        for (BlockCard card : player.deck().hand()) {
            json.value(card.shape().id());
        }
        json.endArray();
        json.field("pile", player.deck().drawPileSize());

        if (island == null) {
            json.field("size", 0);
            json.endObject();
            return;
        }

        Grid grid = island.grid();
        json.field("size", grid.width());
        json.field("cells", cells(grid));

        json.key("spawns").beginArray();
        for (Vec2i spawn : grid.spawns()) {
            cell(json, spawn);
        }
        json.endArray();
        json.key("core").beginArray().value(grid.coreCenter().x())
                .value(grid.coreCenter().z()).endArray();

        // 経路は「曲がり角の折れ線」。セルの塗り分けは Python 側で同じ
        // ラスタライズ（pathfinder.traversed_cells）に通す
        json.key("paths").beginArray();
        for (PathResult path : island.paths()) {
            if (!path.reachable()) {
                continue;
            }
            json.beginArray();
            for (Vec2i waypoint : path.waypoints()) {
                cell(json, waypoint);
            }
            json.endArray();
        }
        json.endArray();

        json.key("towers").beginArray();
        for (TowerInstance tower : island.towers()) {
            TowerKind.Stats resolved = island.resolvedStats(tower);
            json.beginObject()
                    .field("kind", tower.kind().name())
                    .field("level", tower.level())
                    .field("spec", specIndex(tower))
                    .field("cx", tower.centerX())
                    .field("cz", tower.centerZ())
                    .field("range", resolved.range())
                    .field("damage", resolved.damage())
                    .field("cooldown", resolved.cooldown())
                    .field("upCost", tower.maxed() ? -1 : tower.nextUpgradeCost());
            json.key("cells").beginArray();
            for (Vec2i footprint : tower.footprint()) {
                cell(json, footprint);
            }
            json.endArray();
            json.endObject();
        }
        json.endArray();

        json.key("enemies").beginArray();
        double originX = island.arena().originX();
        double originZ = island.arena().originZ();
        for (EnemyInstance enemy : island.enemies()) {
            if (!enemy.alive()) {
                continue;
            }
            json.beginObject()
                    .field("x", enemy.position().x() - originX)
                    .field("z", enemy.position().z() - originZ)
                    .field("hp", enemy.hp())
                    .field("maxHp", enemy.maxHp())
                    .field("progress", enemy.progressRatio())
                    .endObject();
        }
        json.endArray();

        json.endObject();
    }

    private static void cell(Json json, Vec2i pos) {
        json.beginArray().value(pos.x()).value(pos.z()).endArray();
    }

    /** 最終段階で選んだ特化。未選択は -1。 */
    private static int specIndex(TowerInstance tower) {
        if (tower.spec() == null) {
            return -1;
        }
        List<TowerKind.Spec> specs = tower.kind().specs();
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i) == tower.spec()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 盤面を 1 文字ずつの文字列にする。並びは z * width + x（Python の
     * {@code cells[z, x]} と同じ）。
     */
    private static String cells(Grid grid) {
        StringBuilder out = new StringBuilder(grid.width() * grid.height());
        for (int z = 0; z < grid.height(); z++) {
            for (int x = 0; x < grid.width(); x++) {
                out.append(symbol(grid.get(x, z)));
            }
        }
        return out.toString();
    }

    private static char symbol(CellType type) {
        return switch (type) {
            case OPEN -> '.';
            case WALL -> '#';
            case ROCK -> 'R';
            case SPAWN -> 'S';
            case CORE -> 'C';
            case BORDER -> 'B';
        };
    }
}
