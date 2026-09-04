package dev.antigravity.mazeward.stage;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.PathFinder;
import dev.antigravity.mazeward.core.PathResult;
import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.enemy.EnemyInstance;
import dev.antigravity.mazeward.enemy.EnemyKind;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.run.Deck;
import dev.antigravity.mazeward.run.Modifiers;
import dev.antigravity.mazeward.run.Rune;
import dev.antigravity.mazeward.run.Wallet;
import dev.antigravity.mazeward.enemy.Trait;
import dev.antigravity.mazeward.tower.AttackStyle;
import dev.antigravity.mazeward.tower.Effect;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.Targeting;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.world.ArenaRenderer;
import dev.antigravity.mazeward.world.Overlay;
import dev.antigravity.mazeward.world.Palette;
import dev.antigravity.mazeward.world.TowerModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.cube.SlimeMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;

/**
 * 1 つの戦場。<b>盤面・タワー・敵・弾・ルーン</b> と、その戦闘処理を持つ。
 *
 * <p>「どういう条件で敵が湧き、何が起きたら終わるのか」は持たない。
 * それはシングルの {@link Stage}（ウェーブ制）と対戦の島（送り合い）で全く違うので、
 * 継承した側が決める。</p>
 *
 * <p>継承を使っているのは、共有したいのが <b>状態と振る舞いの塊そのもの</b> だから。
 * 委譲にすると 30 近いメソッドを丸ごと横流しするだけの層ができて、
 * かえって読みにくくなる。派生が必要とするのは下の 7 つのフックだけ。</p>
 */
public abstract class Battlefield {

    /** 操作の結果。UI にそのまま出せるメッセージを持つ。 */
    public record Outcome(boolean success, String message) {
        public static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        public static Outcome fail(String message) {
            return new Outcome(false, message);
        }
    }

    /**
     * 配置プレビューの結果。
     *
     * @param error         置けない理由。置けるなら null
     * @param previewPaths  置いた後の経路（置けない場合は空）
     * @param currentLength 現在の移動距離（全スポーンの合計。マス数ではなく実距離）
     * @param previewLength 置いた後の移動距離
     */
    public record PlacementPreview(
            String error,
            List<List<Vec2i>> previewPaths,
            double currentLength,
            double previewLength) {

        public boolean ok() {
            return error == null;
        }

        public double delta() {
            return previewLength - currentLength;
        }
    }

    protected static final int PATH_DRAW_INTERVAL = 3;

    /** 「密集している」と見なす半径。範囲攻撃の巻き込みが期待できる距離。 */
    private static final double CROWD_RADIUS = 3.0;

    /** 燃えている敵に炎を出す間隔。毎 tick だと粒が多すぎて盤面が見えなくなる。 */
    private static final int BURN_DRAW_INTERVAL = 4;

    /** ダメージ数字をまとめて出す間隔。1 発ごとに出すとエンティティが溢れる。 */
    protected static final int DAMAGE_FLUSH_INTERVAL = 8;

    protected static final int HEAL_INTERVAL = 20;

    /** 妨害者がタワーを黙らせにいく間隔。毎 tick 見る必要はない。 */
    protected static final int DISABLE_INTERVAL = Trait.DISABLE_REFRESH_TICKS;
    protected static final double CHAIN_RADIUS = 3.6;
    protected static final double PIERCE_WIDTH = 1.3;

    protected final Instance instance;
    protected final ArenaRenderer arena;
    protected final Grid grid;

    protected final List<Player> players = new ArrayList<>();
    protected final List<TowerInstance> towers = new ArrayList<>();
    protected final Map<Vec2i, TowerInstance> towerByCell = new HashMap<>();
    protected final Map<Vec2i, Rune> runeCells = new HashMap<>();
    /** どのセルがどのカードの素材で置かれたか。 */
    protected final Map<Vec2i, Integer> wallVariants = new HashMap<>();
    protected final List<Shot> shots = new ArrayList<>();
    protected final List<EnemyInstance> enemies = new ArrayList<>();
    protected final List<Entity> ownedEntities = new ArrayList<>();
    protected final List<PathResult> paths = new ArrayList<>();
    protected final Random random = new Random();

    protected int tick;
    protected int gridVersion;

    protected Battlefield(Instance instance, Grid grid, Palette.Theme theme) {
        this(instance, grid, theme, 0, 0);
    }

    /**
     * @param originX 盤面をワールドのどこに置くか。対戦では島ごとにずらす
     * @param originZ 同上
     */
    protected Battlefield(Instance instance, Grid grid, Palette.Theme theme,
                          int originX, int originZ) {
        this.instance = instance;
        this.grid = grid;
        this.arena = new ArenaRenderer(instance, theme, originX, originZ);
    }

    // ================================================================ 派生が決めること

    public abstract Wallet wallet();

    public abstract Modifiers modifiers();

    public abstract Deck deck();

    /** いま建築（カード・タワーの設置）を受け付けるか。 */
    public abstract boolean buildingAllowed();

    public abstract boolean isUnlocked(TowerKind kind);

    /** 敵を倒した。報酬は既に {@link #wallet()} へ入っている。 */
    protected abstract void onEnemyKilled(EnemyInstance enemy, Pos at, int reward);

    /** 敵がゴールへ到達した。ライフを減らすなどはここで。 */
    protected abstract void onEnemyLeaked(EnemyInstance enemy, Pos at);

    /**
     * 終焉騎が倒れずに出発点へ戻ったとき。
     *
     * <p>「倒しさえすれば損はない」という前提を崩すための唯一の仕掛けなので、
     * ここでは必ず恒久的な代償（ライフ上限を奪う）を払わせる。</p>
     */
    protected abstract void onEnemyRevived(EnemyInstance enemy, Pos at);

    /** 通貨の呼び名。メッセージに使う。 */
    public String currencyName() {
        return "ゴールド";
    }

    /** 金額の表記。 */
    public String money(int amount) {
        return amount + "G";
    }

    /** 後始末のフック。プレイヤー一覧が消される前に呼ばれる。 */
    protected void onDispose() {
    }

    // ================================================================ 座標

    /**
     * 塔の中心のワールド座標。
     *
     * <p>{@code TowerInstance} は自分が乗っているセルしか知らないのでグリッド座標で持つ。
     * 敵の座標はワールド座標なので、距離を比べる前にここで必ず揃える。</p>
     */
    protected double towerWorldX(TowerInstance tower) {
        return arena.worldX(tower.centerX());
    }

    protected double towerWorldZ(TowerInstance tower) {
        return arena.worldZ(tower.centerZ());
    }

    private double distanceFromTower(TowerInstance tower, Pos pos) {
        double dx = towerWorldX(tower) - pos.x();
        double dz = towerWorldZ(tower) - pos.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 折れ線のセル列を、描画用のワールド座標へ変換する。 */
    public List<Pos> toWorldPath(List<Vec2i> cells) {
        List<Pos> out = new ArrayList<>(cells.size());
        for (Vec2i cell : cells) {
            out.add(arena.surfaceCenter(cell));
        }
        return out;
    }

    // ================================================================ 参照

    public Instance instance() {
        return instance;
    }

    public ArenaRenderer arena() {
        return arena;
    }

    public Grid grid() {
        return grid;
    }

    /** 盤面が変わるたびに増える。プレビューのキャッシュ判定に使う。 */
    public int gridVersion() {
        return gridVersion;
    }

    public List<PathResult> paths() {
        return Collections.unmodifiableList(paths);
    }

    /** 全スポーンの移動距離の合計（実距離）。 */
    public double totalPathLength() {
        double total = 0;
        for (PathResult path : paths) {
            total += path.length();
        }
        return total;
    }

    public int aliveEnemies() {
        return enemies.size();
    }

    /** 出現中の敵。ヘッドレス検証と将来の拡張のために公開している。 */
    public List<EnemyInstance> enemies() {
        return Collections.unmodifiableList(enemies);
    }

    /** いま置けるタワー。ホットバーにそのまま並べる。 */
    public List<TowerKind> availableTowers() {
        List<TowerKind> out = new ArrayList<>();
        for (TowerKind kind : TowerKind.values()) {
            if (isUnlocked(kind)) {
                out.add(kind);
            }
        }
        return out;
    }

    public List<TowerInstance> towers() {
        return Collections.unmodifiableList(towers);
    }

    public TowerInstance towerAt(Vec2i cell) {
        return towerByCell.get(cell);
    }

    public List<Player> players() {
        return Collections.unmodifiableList(players);
    }

    public void addPlayer(Player player) {
        if (!players.contains(player)) {
            players.add(player);
        }
    }

    public void removePlayer(Player player) {
        players.remove(player);
    }

    // ================================================================ 毎 tick

    /** 敵・タワー・弾を 1 tick 進める。 */
    protected void tickBattle() {
        tickBattle(true);
    }

    /**
     * @param render 見せ物（ダメージ数字・燃焼の粒・能力の演出）を出すか。
     *               倍速で 1 サーバー tick に何度も進めるとき、
     *               <b>途中の回では出さない</b>。16 倍速で 16 回ぶんの数字を
     *               同時に浮かべると、entity が数百単位で溜まって
     *               <b>速くしたのにクライアントが重くなる</b>。
     *               溜まったダメージは消えず、次に出すときに合算されて出る
     */
    protected void tickBattle(boolean render) {
        tickEnemies(render);
        tickTowers();
        tickShots();
    }

    protected void tickPathDisplay() {
        if (tick % PATH_DRAW_INTERVAL == 0) {
            drawCurrentPaths();
        }
    }

    private void tickEnemies(boolean render) {
        List<EnemyInstance> dead = null;
        List<EnemyInstance> leaked = null;

        for (EnemyInstance enemy : enemies) {
            enemy.tick();
            enemy.syncBody();
            if (render) {
                showAbilityEffects(enemy);
            }

            if (!enemy.alive()) {
                (dead == null ? dead = new ArrayList<>() : dead).add(enemy);
            } else if (enemy.leaked()) {
                (leaked == null ? leaked = new ArrayList<>() : leaked).add(enemy);
            }
        }

        applyFieldRunes();
        if (render && tick % BURN_DRAW_INTERVAL == 0) {
            drawBurning();
        }
        if (render && tick % DAMAGE_FLUSH_INTERVAL == 0) {
            flushDamageNumbers();
        }
        if (tick % HEAL_INTERVAL == 0) {
            applyEnemyAuras();
        }
        if (tick % DISABLE_INTERVAL == 0) {
            applyDisablers();
        }

        if (dead != null) {
            for (EnemyInstance enemy : dead) {
                handleKill(enemy);
            }
        }
        if (leaked != null) {
            for (EnemyInstance enemy : leaked) {
                handleLeak(enemy);
            }
        }
    }

    /**
     * 棘・茨のルーンを、そのセルの近くを通っている敵に適用する。
     *
     * <p>敵の側から近傍セルを引く形にしている。ルーンの数が増えても
     * 参照回数が敵の数 x 9 で頭打ちになるため。</p>
     */
    private void applyFieldRunes() {
        if (runeCells.isEmpty() || enemies.isEmpty()) {
            return;
        }
        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive() || enemy.kind().flying()) {
                continue;
            }
            Pos pos = enemy.position();
            Vec2i center = arena.toCell(pos.x(), pos.z());
            boolean spiked = false;
            boolean snared = false;

            for (int dx = -1; dx <= 1 && !(spiked && snared); dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Rune rune = runeCells.get(center.add(dx, dz));
                    if (rune == null || !rune.fieldRune()) {
                        continue;
                    }
                    Vec2i cell = center.add(dx, dz);
                    double distX = pos.x() - arena.centerX(cell);
                    double distZ = pos.z() - arena.centerZ(cell);
                    if (Math.sqrt(distX * distX + distZ * distZ) > Rune.FIELD_RADIUS) {
                        continue;
                    }
                    if (rune == Rune.SPIKE) {
                        spiked = true;
                    } else {
                        snared = true;
                    }
                }
            }

            if (spiked) {
                enemy.damageDirect(Rune.SPIKE_DPS / 20.0);
                enemy.addPendingDamage(Rune.SPIKE_DPS / 20.0, Rune.SPIKE.color());
            }
            if (snared) {
                enemy.applySlow(Rune.BRAMBLE_SLOW, 10);
            }
        }
    }

    /**
     * 溜まったダメージを数字として浮かべる。
     *
     * <p>色は与えた属性のもの（炎＝赤 / 氷＝水色 / 電＝黄 / 無＝白）。
     * どの塔が実際に効いているのかが、盤面を見るだけで分かるようにするのが目的。</p>
     */
    private void flushDamageNumbers() {
        if (players.isEmpty()) {
            return;
        }
        for (EnemyInstance enemy : enemies) {
            if (!enemy.hasPendingDamage()) {
                continue;
            }
            double amount = enemy.takePendingDamage();
            Component text = Component.text(String.format("%.0f", amount), enemy.pendingColor());
            Pos at = enemy.position();
            for (Player player : players) {
                Overlay.popupText(instance, player,
                        at.withY(at.y() + 1.6 + random.nextDouble() * 0.4), text, 16);
            }
        }
    }

    /**
     * 敵側のオーラ（回復・庇護）をまとめて適用する。
     *
     * <p>どちらも「オーラを出している個体を先に潰す」という判断を作るためのもの。
     * 庇護は期限つきで貼るので、庇護者が落ちればすぐに軽減が切れる。</p>
     */
    private void applyEnemyAuras() {
        for (EnemyInstance source : enemies) {
            if (!source.alive()) {
                continue;
            }
            Trait trait = source.kind().trait();
            boolean heals = source.kind().healer();
            if (!heals && !trait.wards()) {
                continue;
            }
            Pos center = source.position();
            double radius = heals ? 5.0 : trait.wardRadius();
            for (EnemyInstance target : enemies) {
                if (target == source || !target.alive()) {
                    continue;
                }
                if (target.position().distance(center) > radius) {
                    continue;
                }
                if (heals) {
                    // 回復量は定額だが、対戦の送りは HP が桁で違う。
                    // 最大 HP の 1.5%/秒 を下限にしておかないと、
                    // 上の段ではただの「柔らかい的」になってしまう
                    target.heal(Math.max(source.kind().healPerSecond(),
                            target.maxHp() * 0.015));
                } else {
                    // 次のオーラ tick まで少しだけ余裕を持たせる
                    target.applyWard(trait.wardReduction(), HEAL_INTERVAL + 5);
                }
            }
            Overlay.drawBurst(players, center.withY(center.y() + 1.2),
                    heals ? Particle.HAPPY_VILLAGER : Particle.ENCHANT, 4, 1.2f);
        }
    }

    /**
     * 妨害者が近くのタワーを黙らせる。
     *
     * <p>火力を 1 箇所に固めるほど、1 体でまとめて止められる。
     * 「キルゾーンを 1 つ作れば勝ち」を崩すのが狙い。</p>
     */
    private void applyDisablers() {
        for (EnemyInstance enemy : enemies) {
            Trait trait = enemy.kind().trait();
            if (!trait.disables() || !enemy.alive()) {
                continue;
            }
            Pos pos = enemy.position();
            boolean any = false;
            for (TowerInstance tower : towers) {
                if (distanceFromTower(tower, pos) > trait.disableRadius()) {
                    continue;
                }
                Pos over = new Pos(towerWorldX(tower),
                        ArenaRenderer.WALL_TOP_Y + 0.9, towerWorldZ(tower));

                // 監視塔の傘の下は弾く。守られたことが見えないと、
                // 「効いていないのか、そもそも狙われていないのか」が分からない
                if (tower.disableImmune()) {
                    Overlay.drawBurst(players, over, Particle.END_ROD, 4, 0.25f);
                    continue;
                }
                int ticks = (int) Math.round(trait.disableTicks() * (1.0 - tower.disableResist()));
                if (ticks <= 0) {
                    continue;
                }
                tower.disable(ticks);
                any = true;
                Overlay.drawBurst(players, over, Particle.LARGE_SMOKE, 3, 0.25f);
            }
            if (any) {
                playSound(SoundEvent.BLOCK_FIRE_EXTINGUISH, 0.35f, 0.6f);
            }
        }
    }

    /** 瞬移・送還が起きた瞬間だけ演出を出す。何が起きたのか分からないと理不尽に見える。 */
    private void showAbilityEffects(EnemyInstance enemy) {
        Pos at = enemy.position();
        if (enemy.consumeBlinked()) {
            // 壁を跨いで飛ぶので、出発地点も光らせないと「消えて湧いた」ようにしか見えない
            Pos from = enemy.blinkOrigin();
            if (from != null) {
                Overlay.drawBurst(players, from.withY(from.y() + 1.0), Particle.PORTAL, 10, 0.6f);
            }
            Overlay.drawBurst(players, at.withY(at.y() + 1.0), Particle.PORTAL, 14, 0.6f);
            playSound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.4f);
        }
        if (enemy.consumeBanished()) {
            Pos from = enemy.banishOrigin();
            if (from != null) {
                Overlay.drawBurst(players, from.withY(from.y() + 1.0), Particle.PORTAL, 24, 0.8f);
            }
            Overlay.drawBurst(players, at.withY(at.y() + 1.0), Particle.REVERSE_PORTAL, 24, 0.8f);
            playSound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
            for (Player player : players) {
                Overlay.popupText(instance, player, at.withY(at.y() + 2.0),
                        Component.text("送還", NamedTextColor.DARK_PURPLE), 30);
            }
        }
    }

    private void tickTowers() {
        for (TowerInstance tower : towers) {
            tower.tickCooldown();
            if (!tower.ready()) {
                continue;
            }
            fireTower(tower);
        }
    }

    private void tickShots() {
        shots.removeIf(shot -> !shot.tick());
    }

    // ================================================================ 敵

    /**
     * 敵を 1 体出す。HP と報酬は呼び出し側が決める
     * （シングルは層とウェーブから、対戦は送り主の編成から算出する）。
     */
    protected EnemyInstance spawnEnemy(EnemyKind kind, int spawnIndex, double hp, int reward) {
        if (grid.spawns().isEmpty()) {
            return null;
        }
        int index = Math.max(0, Math.min(spawnIndex, grid.spawns().size() - 1));
        List<Pos> waypoints = waypointsFor(kind, index);
        if (waypoints.size() < 2) {
            return null;
        }

        Entity body = createBody(kind);
        body.setInstance(instance, waypoints.get(0));

        EnemyInstance enemy = new EnemyInstance(kind, body, waypoints, hp, reward);
        enemies.add(enemy);
        return enemy;
    }

    /** 敵の見た目を持つエンティティを作る。スライム系はここで大きさを決める。 */
    private static Entity createBody(EnemyKind kind) {
        Entity body = new Entity(kind.entityType());
        int slimeSize = kind.slimeSize();
        if (slimeSize > 0) {
            body.editEntityMeta(SlimeMeta.class, meta -> meta.setSize(slimeSize));
        }
        body.setNoGravity(true);
        return body;
    }

    private List<Pos> waypointsFor(EnemyKind kind, int spawnIndex) {
        Vec2i spawn = grid.spawns().get(spawnIndex);
        if (kind.flying()) {
            // 迷路を完全に無視する直線ルート
            return List.of(arena.center(spawn, ArenaRenderer.FLYING_Y),
                    arena.coreCenter(grid, ArenaRenderer.SURFACE_Y + 0.8));
        }

        PathResult path = spawnIndex < paths.size() ? paths.get(spawnIndex) : grid.pathFrom(spawn);
        if (!path.reachable()) {
            return List.of();
        }
        List<Pos> waypoints = new ArrayList<>(path.waypoints().size());
        for (Vec2i cell : path.waypoints()) {
            waypoints.add(arena.surfaceCenter(cell));
        }
        return waypoints;
    }

    private void handleKill(EnemyInstance enemy) {
        Pos at = enemy.position();

        // 終焉騎は倒れる代わりに出発点へ戻る。報酬も出ない
        if (enemy.tryRevive()) {
            Overlay.drawBurst(players, at.withY(at.y() + 1.0), Particle.SOUL, 20, 0.8f);
            playSound(SoundEvent.ENTITY_WITHER_SPAWN, 0.5f, 1.6f);
            onEnemyRevived(enemy, at);
            return;
        }

        enemies.remove(enemy);
        enemy.body().remove();

        if (enemy.kind().trait().splits()) {
            spawnSplits(enemy, at);
        }

        int reward = enemy.goldReward() + goldVeinBonus(at);
        wallet().gain(reward);

        Overlay.drawBurst(players, at.withY(at.y() + 0.8), Particle.CRIT, 8, 0.35f);
        for (Player player : players) {
            Overlay.popupText(instance, player, at.withY(at.y() + 1.4),
                    Component.text("+" + money(reward), NamedTextColor.GOLD), 14);
        }
        onEnemyKilled(enemy, at, reward);
    }

    /**
     * 分裂体が倒れたときに子を湧かせる。
     *
     * <p>親の折れ線をそのまま使い、親が居た地点から歩かせる。
     * 出発点に戻すと「倒したのに一番遠くから来直す」ことになり、
     * 分裂が単なる時間稼ぎになってしまう。</p>
     */
    private void spawnSplits(EnemyInstance parent, Pos at) {
        Trait trait = parent.kind().trait();
        double hp = parent.maxHp() * 0.28;
        int reward = Math.max(1, parent.goldReward() / 3);
        for (int i = 0; i < trait.splitCount(); i++) {
            Entity body = createBody(EnemyKind.SPLITLING);
            body.setInstance(instance, at);

            EnemyInstance child = new EnemyInstance(
                    EnemyKind.SPLITLING, body, parent.waypoints(), hp, reward);
            // 割れた子も「送られてきた敵」のまま。親だけが送り主を持つと、
            // 分裂体を送ったときだけコア到達の見返りが消えてしまう
            child.source(parent.source());
            // 少しずらして出すと重なって 1 体に見えるのを防げる
            child.advanceTo(Math.max(0.0, parent.travelled() - 0.9 * i));
            enemies.add(child);
        }
        Overlay.drawBurst(players, at.withY(at.y() + 0.8), Particle.ITEM_SLIME, 12, 0.5f);
    }

    /** 金脈ルーンの近くで倒したときの追加報酬。 */
    private int goldVeinBonus(Pos at) {
        if (runeCells.isEmpty()) {
            return 0;
        }
        Vec2i center = arena.toCell(at.x(), at.z());
        int radius = (int) Math.ceil(Rune.GOLD_VEIN_RADIUS);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Vec2i cell = center.add(dx, dz);
                if (runeCells.get(cell) != Rune.GOLD_VEIN) {
                    continue;
                }
                double distX = at.x() - arena.centerX(cell);
                double distZ = at.z() - arena.centerZ(cell);
                if (Math.sqrt(distX * distX + distZ * distZ) <= Rune.GOLD_VEIN_RADIUS) {
                    return Rune.GOLD_VEIN_BONUS;
                }
            }
        }
        return 0;
    }

    private void handleLeak(EnemyInstance enemy) {
        Pos at = enemy.position();

        // 災厄はコアに触れても消えない。出発点へ戻り、倒し切るまで何周でも来る
        if (enemy.kind().boss()) {
            enemy.returnToStart();
            enemy.syncBody();
            Overlay.drawBurst(players, at.withY(at.y() + 1.2), Particle.LARGE_SMOKE, 24, 0.9f);
            Overlay.drawBurst(players, enemy.position().withY(at.y() + 1.2),
                    Particle.SOUL_FIRE_FLAME, 24, 0.9f);
            playSound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.7f);
            onEnemyLeaked(enemy, at);
            return;
        }

        enemies.remove(enemy);
        enemy.body().remove();

        Overlay.drawBurst(players, at.withY(at.y() + 1.0), Particle.LARGE_SMOKE, 12, 0.5f);
        playSound(SoundEvent.BLOCK_ANVIL_LAND, 0.6f, 0.7f);
        onEnemyLeaked(enemy, at);
    }

    // ================================================================ タワーの射撃

    /**
     * 恒久補正と土台のルーンをすべて適用したあとの実効性能。
     *
     * <p>戦闘の計算は必ずここを通す。素の値に補正を足す場所が散らばると、
     * 「どのタワーが本当はどれだけ強いのか」が誰にも分からなくなるため。</p>
     */
    public TowerKind.Stats resolvedStats(TowerInstance tower) {
        TowerKind.Stats base = tower.stats();
        EnumSet<Rune> runes = runesUnder(tower);
        Modifiers mods = modifiers();

        double damage = base.damage() * (runes.contains(Rune.REINFORCED) ? Rune.REINFORCED_DAMAGE : 1.0);
        double range = base.range() + mods.rangeBonus()
                + (runes.contains(Rune.LENS) ? Rune.LENS_RANGE : 0.0);
        int cooldown = runes.contains(Rune.BEACON)
                ? Math.max(2, (int) Math.round(base.cooldown() * Rune.BEACON_COOLDOWN))
                : base.cooldown();
        double slow = base.slowFactor() > 0 ? Math.min(0.85, base.slowFactor() + mods.slowBonus()) : 0.0;

        // 監視塔からの上乗せ
        damage *= 1.0 + tower.boostDamage();
        if (tower.boostRate() > 0) {
            cooldown = Math.max(2, (int) Math.round(cooldown * (1.0 - tower.boostRate())));
        }

        return new TowerKind.Stats(
                damage,
                range,
                cooldown,
                base.splashRadius() + mods.splashBonus(),
                base.chainTargets() + mods.chainBonus(),
                slow,
                base.slowTicks(),
                base.burnDps() * mods.burnMultiplier(),
                base.burnTicks(),
                base.effect());
    }

    /**
     * 監視塔の効果を各タワーに焼き込む。
     *
     * <p>塔が増減・強化されたときだけ呼ぶ。毎 tick 周りを数え直すと
     * 塔の数の 2 乗になるうえ、結果は塔が動かないかぎり変わらない。</p>
     *
     * <p>監視塔どうしは強化し合わない。並べるほど雪だるま式に伸びると、
     * 「撃つ塔をどれだけ置くか」という肝心の配分が消えてしまう。</p>
     */
    protected void recomputeSupport() {
        double rangeBonus = modifiers().rangeBonus();
        for (TowerInstance tower : towers) {
            double damage = 0;
            double rate = 0;
            double resist = 0;
            for (TowerInstance source : towers) {
                if (source == tower || !source.kind().passive()) {
                    continue;
                }
                TowerKind.Stats stats = source.stats();
                if (tower.distanceTo(source.centerX(), source.centerZ()) > stats.range() + rangeBonus) {
                    continue;
                }
                // 火力の上乗せは足し合わせる。監視塔を増やした甲斐が要るので
                damage += stats.effect().boostDamage();
                rate += stats.effect().boostRate();
                // 妨害の傘だけは <b>いちばん厚い 1 枚</b> を採る。
                // 足し合わせると監視塔を 2 つ並べるだけで無効化に届いてしまい、
                // 「無効化は特化でしか手に入らない」という段取りが消える
                resist = Math.max(resist, stats.effect().disableResist());
            }
            // 監視塔どうしは強化し合わないが、傘には入れる。
            // 監視塔だけ狙って黙らせれば傘が剥がれる、という抜け道を作らないため。
            // （自分の傘には入らないが、黙らされても支援そのものは止まらないので実害はない）
            tower.setBoost(tower.kind().passive() ? 0 : damage,
                    tower.kind().passive() ? 0 : Math.min(0.6, rate));
            tower.setDisableResist(resist);
        }
    }

    /**
     * その壁セルに置くブロック。
     * ルーンが付いていればルーンの色、なければ <b>そのセルを置いたカードの素材</b>。
     * 1 枚のカードが作った壁は全部同じブロックになる。
     */
    private Block wallBlockFor(Vec2i cell) {
        Rune rune = runeCells.get(cell);
        if (rune != null) {
            return rune.wallBlock();
        }
        return arena.theme().wallForVariant(wallVariants.getOrDefault(cell, 0));
    }

    /** そのタワーが乗っているセルに付いているルーン（同じ種類は 1 回だけ数える）。 */
    public EnumSet<Rune> runesUnder(TowerInstance tower) {
        EnumSet<Rune> runes = EnumSet.noneOf(Rune.class);
        if (runeCells.isEmpty()) {
            return runes;
        }
        for (Vec2i cell : tower.footprint()) {
            Rune rune = runeCells.get(cell);
            if (rune != null && rune.towerRune()) {
                runes.add(rune);
            }
        }
        return runes;
    }

    private void fireTower(TowerInstance tower) {
        TowerKind.Stats stats = resolvedStats(tower);
        double range = stats.range();

        switch (tower.kind().style()) {
            case AURA -> {
                fireAura(tower, stats, range);
                return;
            }
            case SUPPORT -> {
                fireSupport(tower, stats, range);
                return;
            }
            case CURSE -> {
                fireCurse(tower, stats, range);
                return;
            }
            default -> {
            }
        }

        EnemyInstance target = findTarget(tower, range);
        if (target == null) {
            return;
        }
        tower.resetCooldown(stats.cooldown());
        playFireSound(tower);

        TowerModel.aimAt(tower.bodies(), target.position().x(), target.position().z());
        Pos muzzle = new Pos(towerWorldX(tower),
                ArenaRenderer.TOWER_STAND_Y + 1.1, towerWorldZ(tower));
        Color tracerColor = new Color(
                tower.kind().element().color().red(),
                tower.kind().element().color().green(),
                tower.kind().element().color().blue());

        switch (tower.kind().style()) {
            case SINGLE -> {
                spawnShot(tower, muzzle, target.position().withY(target.position().y() + 0.8));
                hit(tower, target, stats, stats.damage());
            }
            case SPLASH -> {
                Pos impact = target.position();
                spawnShot(tower, muzzle, impact.withY(impact.y() + 0.8));
                Overlay.drawBurst(players, impact.withY(impact.y() + 0.6), Particle.EXPLOSION, 1, 0.1f);
                double radius = stats.splashRadius();
                hit(tower, target, stats, stats.damage());
                for (EnemyInstance other : List.copyOf(enemies)) {
                    if (other == target || !other.alive()) {
                        continue;
                    }
                    if (other.position().distance(impact) <= radius) {
                        hit(tower, other, stats, stats.damage() * 0.6);
                    }
                }
                playSound(SoundEvent.ENTITY_GENERIC_EXPLODE, 0.25f, 1.5f);
            }
            case CHAIN -> {
                int remaining = stats.chainTargets();
                List<EnemyInstance> chainHit = new ArrayList<>();
                EnemyInstance current = target;
                Pos from = muzzle;
                double damage = stats.damage();
                boolean first = true;
                while (current != null && remaining-- > 0) {
                    Pos to = current.position().withY(current.position().y() + 0.8);
                    if (first) {
                        // 最初の 1 体へは弾を飛ばし、そこから先の連鎖は線で見せる
                        spawnShot(tower, from, to);
                        first = false;
                    } else {
                        Overlay.drawTracer(players, from, to, tracerColor, 1.0f);
                    }
                    hit(tower, current, stats, damage);
                    chainHit.add(current);
                    damage *= 0.78;
                    from = to;
                    current = nearestUnhit(current, chainHit);
                }
            }
            case BANISH -> {
                Pos targetPos = target.position();
                spawnShot(tower, muzzle, targetPos.withY(targetPos.y() + 0.8));
                hit(tower, target, stats, stats.damage());
                if (target.alive()) {
                    target.sendToSpawn();
                }
                // 「一斉送還」はコアに近いほうから順に、あと何体か道連れにする
                int extra = (int) Math.round(stats.effect().banishTargets()) - 1;
                for (EnemyInstance other : banishOrder(tower, range)) {
                    if (extra <= 0) {
                        break;
                    }
                    if (other == target || !other.alive()) {
                        continue;
                    }
                    hit(tower, other, stats, stats.damage());
                    if (other.alive() && other.sendToSpawn()) {
                        extra--;
                    }
                }
            }
            case PIERCE -> {
                Pos targetPos = target.position();
                spawnShot(tower, muzzle, targetPos.withY(targetPos.y() + 0.8));
                int pierced = stats.chainTargets();
                hit(tower, target, stats, stats.damage());
                pierced--;
                for (EnemyInstance other : List.copyOf(enemies)) {
                    if (pierced <= 0) {
                        break;
                    }
                    if (other == target || !other.alive()) {
                        continue;
                    }
                    if (distanceToSegment(other.position(), muzzle, targetPos) <= PIERCE_WIDTH) {
                        hit(tower, other, stats, stats.damage() * 0.75);
                        pierced--;
                    }
                }
                playSound(SoundEvent.ENTITY_ARROW_SHOOT, 0.3f, 0.8f);
            }
            default -> {
            }
        }
    }

    /**
     * 塔の発射音を鳴らす。塔の位置から鳴らすので、どこで撃っているかが耳で分かる。
     * 間引きはしない——重なって潰れるより、撃っている手数がそのまま聞こえるほうがよい。
     */
    private void playFireSound(TowerInstance tower) {
        Sound sound = Sound.sound(tower.kind().fireSound(), Sound.Source.BLOCK,
                0.5f, tower.kind().firePitch());
        for (Player player : players) {
            player.playSound(sound, towerWorldX(tower), ArenaRenderer.WALL_TOP_Y, towerWorldZ(tower));
        }
    }

    /** 塔から狙った先へアイテムを飛ばす。当たり判定は持たない見た目だけの弾。 */
    private void spawnShot(TowerInstance tower, Pos from, Pos to) {
        Shot shot = Shot.spawn(instance, from, to, tower.kind().projectile());
        if (shot != null) {
            shots.add(shot);
        }
    }

    /**
     * 監視塔。敵を狙わないので、射程の輪を見せるだけ。
     *
     * <p>音は鳴らさない。撃っていないのに 2 秒ごとに鐘が鳴ると、
     * どの塔が働いているのかを耳で追えなくなる。</p>
     */
    private void fireSupport(TowerInstance tower, TowerKind.Stats stats, double range) {
        tower.resetCooldown(stats.cooldown());
    }

    /** 呪詛塔。削らずに、射程内の敵の被ダメージを増やす。 */
    private void fireCurse(TowerInstance tower, TowerKind.Stats stats, double range) {
        Effect effect = stats.effect();
        boolean any = false;
        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive() || distanceFromTower(tower, enemy.position()) > range) {
                continue;
            }
            enemy.applyVulnerability(effect.vulnerability(), effect.vulnerabilityTicks());
            any = true;
        }
        if (!any) {
            return;
        }
        tower.resetCooldown(stats.cooldown());
        playFireSound(tower);
    }

    /**
     * 火炉。弾を撃たず、射程内を燃焼帯に変える。
     *
     * <p>以前は射程の輪を出していたが、数秒おきに輪が現れては消えるので
     * 盤面がちかちかしていた。輪は配置と検査のときだけにして、
     * ここでは <b>燃えている敵そのもの</b> に炎を出す。
     * どこまで焼けているかは、輪より敵を見たほうが早い。</p>
     */
    private void fireAura(TowerInstance tower, TowerKind.Stats stats, double range) {
        tower.resetCooldown(stats.cooldown());
        playFireSound(tower);
        double burn = stats.burnDps();
        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive()) {
                continue;
            }
            if (distanceFromTower(tower, enemy.position()) <= range) {
                enemy.applyBurn(burn, stats.burnTicks());
            }
        }
        Overlay.drawBurst(players,
                new Pos(towerWorldX(tower), ArenaRenderer.TOWER_STAND_Y + 0.9, towerWorldZ(tower)),
                Particle.FLAME, 8, 0.35f);
    }

    /** 燃えている敵から炎を上げる。延焼が効いていることを数字より先に伝える。 */
    private void drawBurning() {
        if (players.isEmpty()) {
            return;
        }
        for (EnemyInstance enemy : enemies) {
            if (enemy.alive() && enemy.burning()) {
                Pos at = enemy.position();
                Overlay.drawBurst(players, at.withY(at.y() + 0.6), Particle.FLAME, 3, 0.25f);
            }
        }
    }

    /**
     * 射程内から 1 体選ぶ。<b>選び方は塔ごとに違う</b>（{@link Targeting}）。
     *
     * <p>全部の塔が「コアにいちばん近い敵」を撃つと、種類を変えて並べても
     * 弾が同じ 1 体に集まり、溶けかけの敵に overkill を重ねて後ろは素通りになる。</p>
     *
     * <p>同点はコアに近いほうを採る。どの狙い方でも最後の拠り所は「漏らさない」で、
     * ここを進行距離ではなく残距離で見ているので、
     * 戦闘中に経路を引き直しても優先順位が壊れない。</p>
     */
    private EnemyInstance findTarget(TowerInstance tower, double range) {
        Targeting mode = tower.kind().targeting();
        EnemyInstance best = null;
        double bestScore = 0;
        double bestRemaining = Double.MAX_VALUE;

        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive()) {
                continue;
            }
            double distance = distanceFromTower(tower, enemy.position());
            if (distance > range) {
                continue;
            }
            // 送還は 60 秒に 1 度きり。すでに出発点にいる敵を撃つと空撃ちになる
            if (tower.kind().style() == AttackStyle.BANISH && enemy.travelled() <= 0.0) {
                continue;
            }
            double score = switch (mode) {
                case UNAFFECTED -> enemy.affected() ? 0.0 : 1.0;
                case TOUGHEST -> enemy.hp();
                case DENSEST -> crowdAround(enemy);
                case FARTHEST -> distance;
                default -> 0.0;
            };
            boolean better = best == null
                    || score > bestScore + 1e-9
                    || (score > bestScore - 1e-9 && enemy.remaining() < bestRemaining);
            if (better) {
                best = enemy;
                bestScore = score;
                bestRemaining = enemy.remaining();
            }
        }
        return best;
    }

    /**
     * 送還の巻き込み順。コアに近い敵から並べる。
     *
     * <p>60 秒に 1 度しか撃てないので、<b>いちばん漏れそうな敵から</b>戻すのが常に正しい。</p>
     */
    private List<EnemyInstance> banishOrder(TowerInstance tower, double range) {
        List<EnemyInstance> inRange = new ArrayList<>();
        for (EnemyInstance enemy : enemies) {
            if (enemy.alive() && distanceFromTower(tower, enemy.position()) <= range) {
                inRange.add(enemy);
            }
        }
        inRange.sort(Comparator.comparingDouble(EnemyInstance::remaining));
        return inRange;
    }

    /** その敵の周りにいる敵の数。範囲・連鎖の巻き込みがいちばん増える一体を探すのに使う。 */
    private int crowdAround(EnemyInstance enemy) {
        int count = 0;
        Pos origin = enemy.position();
        for (EnemyInstance other : enemies) {
            if (other.alive() && other.position().distance(origin) <= CROWD_RADIUS) {
                count++;
            }
        }
        return count;
    }

    private EnemyInstance nearestUnhit(EnemyInstance from, List<EnemyInstance> alreadyHit) {
        EnemyInstance best = null;
        double bestDistance = CHAIN_RADIUS;
        Pos origin = from.position();
        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive() || alreadyHit.contains(enemy)) {
                continue;
            }
            double distance = enemy.position().distance(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = enemy;
            }
        }
        return best;
    }

    private void hit(TowerInstance tower, EnemyInstance enemy, TowerKind.Stats stats, double damage) {
        double applied = enemy.damage(damage);
        enemy.addPendingDamage(applied, tower.kind().element().color());
        if (stats.slowFactor() > 0) {
            enemy.applySlow(stats.slowFactor(), stats.slowTicks());
        }
        if (stats.burnDps() > 0 && tower.kind().style() != AttackStyle.AURA) {
            enemy.applyBurn(stats.burnDps(), stats.burnTicks());
        }
    }

    private static double distanceToSegment(Pos point, Pos a, Pos b) {
        double abx = b.x() - a.x();
        double abz = b.z() - a.z();
        double lengthSq = abx * abx + abz * abz;
        if (lengthSq < 1e-9) {
            return point.distance(a);
        }
        double t = ((point.x() - a.x()) * abx + (point.z() - a.z()) * abz) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        double px = a.x() + abx * t;
        double pz = a.z() + abz * t;
        double dx = point.x() - px;
        double dz = point.z() - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ================================================================ 配置

    /** カーソルセルを形状の中心に合わせた原点を返す。 */
    public Vec2i originFor(Shape shape, Vec2i cursor, Rot rot) {
        return cursor.add(shape.centerOffset(rot));
    }

    /**
     * 障害物を仮に置いたときの経路を計算する。ここが本作の核となる UI。
     * 盤面には一切触れない。
     */
    public PlacementPreview preview(Shape shape, Vec2i origin, Rot rot) {
        double currentLength = totalPathLength();
        String error = cardPlacementError(shape, origin, rot);
        if (error != null) {
            return new PlacementPreview(error, List.of(), currentLength, currentLength);
        }
        List<PathResult> previewPaths = grid.previewPaths(shape, origin, rot);
        if (previewPaths == null) {
            return new PlacementPreview(Grid.Placement.WOULD_BLOCK.reason(),
                    List.of(), currentLength, currentLength);
        }
        List<List<Vec2i>> cells = new ArrayList<>(previewPaths.size());
        double length = 0;
        for (PathResult result : previewPaths) {
            cells.add(result.waypoints());
            length += result.length();
        }
        return new PlacementPreview(null, cells, currentLength, length);
    }

    /**
     * 障害物カードを置けない理由。置けるなら null。
     *
     * <p>壁は一度置いたら撤去できない仕様なので、
     * 「置いては壊して敵を往復させる」タイプの退化戦術は成立しない。
     * ただし敵が盤面にいるときは、追加で 2 つの条件を課す。</p>
     */
    public String cardPlacementError(Shape shape, Vec2i origin, Rot rot) {
        if (!buildingAllowed()) {
            return "いまは配置できません";
        }
        Grid.Placement placement = grid.checkPlacement(shape, origin, rot);
        if (!placement.ok()) {
            return placement.reason();
        }
        return liveEnemyPlacementError(shape.cellsAt(origin, rot));
    }

    /** 敵が出ているときの追加条件: 敵の上に置かない・敵を閉じ込めない。 */
    private String liveEnemyPlacementError(List<Vec2i> target) {
        if (enemies.isEmpty()) {
            return null;
        }
        for (EnemyInstance enemy : enemies) {
            if (enemy.kind().flying()) {
                continue;
            }
            if (target.contains(enemyCell(enemy))) {
                return "敵がいる場所には置けません";
            }
        }

        // 仮置きして「生きている敵がコアへ行けるか」を確かめ、必ず元に戻す
        for (Vec2i cell : target) {
            grid.set(cell, CellType.WALL);
        }
        boolean trapped = false;
        for (EnemyInstance enemy : enemies) {
            if (enemy.kind().flying()) {
                continue;
            }
            if (!grid.reachable(enemyCell(enemy), grid.coreCells())) {
                trapped = true;
                break;
            }
        }
        for (Vec2i cell : target) {
            grid.set(cell, CellType.OPEN);
        }
        return trapped ? "その配置だと敵が閉じ込められます" : null;
    }

    public Outcome placeCard(int handIndex, Vec2i origin, Rot rot) {
        BlockCard card = deck().peek(handIndex);
        if (card == null) {
            return Outcome.fail("そのカードは手札にありません");
        }
        String error = cardPlacementError(card.shape(), origin, rot);
        if (error != null) {
            return Outcome.fail(error);
        }

        grid.place(card.shape(), origin, rot);
        deck().play(handIndex);
        for (Vec2i cell : card.shape().cellsAt(origin, rot)) {
            arena.paintCell(grid, cell);
            wallVariants.put(cell, card.variant());
            if (card.hasRune()) {
                runeCells.put(cell, card.rune());
            }
            arena.paintWall(cell, wallBlockFor(cell));
        }
        gridVersion++;
        recomputePaths();
        if (!enemies.isEmpty()) {
            repathEnemies();
        }
        playSound(SoundEvent.BLOCK_STONE_PLACE, 0.9f, 1.0f);
        return Outcome.ok(card.displayName() + " を配置");
    }

    /**
     * 生きている地上の敵の経路を、いまの立ち位置から引き直す。
     * 壁が増えたときに呼ぶ。これをやらないと敵が新しい壁をすり抜けて見える。
     */
    protected void repathEnemies() {
        for (EnemyInstance enemy : enemies) {
            if (enemy.kind().flying() || !enemy.alive()) {
                continue;
            }
            Pos position = enemy.position();
            PathResult path = PathFinder.find(grid, enemyCell(enemy), grid.coreCells());
            if (!path.reachable() || path.waypoints().isEmpty()) {
                continue;
            }
            List<Pos> waypoints = new ArrayList<>(path.waypoints().size() + 1);
            waypoints.add(position);
            for (Vec2i cell : path.waypoints()) {
                waypoints.add(arena.surfaceCenter(cell));
            }
            enemy.repath(waypoints);
        }
    }

    /** 敵がいまいるセル。壁際で境界にまたがっている場合は近くの通行可能セルへ寄せる。 */
    private Vec2i enemyCell(EnemyInstance enemy) {
        Pos position = enemy.position();
        Vec2i cell = arena.toCell(position.x(), position.z());
        if (grid.inBounds(cell) && grid.walkable(cell)) {
            return cell;
        }
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Vec2i candidate = cell.add(dx, dz);
                    if (grid.inBounds(candidate) && grid.walkable(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return cell;
    }

    /** タワーを置けない理由を返す。置けるなら null。 */
    public String towerPlacementError(TowerKind kind, Vec2i origin, Rot rot) {
        if (!buildingAllowed()) {
            return "いまはタワーを置けません";
        }
        if (!isUnlocked(kind)) {
            return kind.displayName() + " はまだ解放されていません";
        }
        String limit = towerLimitError();
        if (limit != null) {
            return limit;
        }
        if (!grid.isTowerBaseFor(kind.shape(), origin, rot)) {
            return "壁または岩の上にしか置けません";
        }
        for (Vec2i cell : kind.shape().cellsAt(origin, rot)) {
            if (towerByCell.containsKey(cell)) {
                return "すでにタワーがあります";
            }
        }
        if (wallet().balance() < kind.baseCost()) {
            return currencyName() + "が足りません（" + money(kind.baseCost()) + " 必要）";
        }
        return null;
    }

    /** タワーの設置数に上限がある場合の理由。上限なしなら null。 */
    protected String towerLimitError() {
        return null;
    }

    public Outcome placeTower(TowerKind kind, Vec2i origin, Rot rot) {
        String error = towerPlacementError(kind, origin, rot);
        if (error != null) {
            return Outcome.fail(error);
        }
        wallet().spend(kind.baseCost());

        TowerInstance tower = new TowerInstance(kind, origin, rot, kind.baseCost(),
                modifiers().maxTowerLevel());
        towers.add(tower);
        for (Vec2i cell : tower.footprint()) {
            towerByCell.put(cell, tower);
        }
        arena.paintPedestal(tower.footprint(), kind.pedestal());
        attachTowerBody(tower);
        attachTowerLabel(tower);
        recomputeSupport();
        playSound(SoundEvent.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        return Outcome.ok(kind.displayName() + " を設置 (-" + money(kind.baseCost()) + ")");
    }

    public Outcome upgradeTower(Vec2i cell) {
        return upgradeTower(cell, null);
    }

    /**
     * タワーを 1 段階だけ強化する。
     *
     * @param spec 最終段階でのみ使う特化の選択。それ以外の段階では null
     */
    public Outcome upgradeTower(Vec2i cell, TowerKind.Spec spec) {
        TowerInstance tower = towerByCell.get(cell);
        if (tower == null) {
            return Outcome.fail("タワーがありません");
        }
        if (tower.maxed()) {
            return Outcome.fail("すでに最大レベルです");
        }
        if (tower.nextIsSpecialization() && spec == null) {
            return Outcome.fail("特化を選んでください");
        }
        int cost = (int) Math.round(tower.nextUpgradeCost() * modifiers().upgradeCostMultiplier());
        if (!wallet().spend(cost)) {
            return Outcome.fail(currencyName() + "が足りません（" + money(cost) + " 必要）");
        }
        tower.upgrade(cost, spec);
        attachTowerBody(tower);
        if (spec != null) {
            Overlay.drawBurst(players,
                    new Pos(towerWorldX(tower), ArenaRenderer.TOWER_STAND_Y + 1.0, towerWorldZ(tower)),
                    Particle.TOTEM_OF_UNDYING, 30, 0.5f);
        }
        updateTowerLabel(tower);
        recomputeSupport();
        playSound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);
        String name = spec == null ? tower.kind().displayName()
                : tower.kind().displayName() + "・" + spec.displayName();
        return Outcome.ok(name + " Lv" + (tower.level() + 1) + " に強化 (-" + money(cost) + ")");
    }

    public Outcome sellTower(Vec2i cell) {
        TowerInstance tower = towerByCell.get(cell);
        if (tower == null) {
            return Outcome.fail("タワーがありません");
        }
        int refund = tower.sellValue();
        towers.remove(tower);
        for (Vec2i footprintCell : tower.footprint()) {
            towerByCell.remove(footprintCell);
        }
        arena.clearPedestal(tower.footprint());
        removeTowerBodies(tower);
        if (tower.label() != null) {
            tower.label().remove();
            ownedEntities.remove(tower.label());
        }
        wallet().gain(refund);
        recomputeSupport();
        playSound(SoundEvent.BLOCK_GLASS_BREAK, 0.7f, 1.0f);
        return Outcome.ok(tower.kind().displayName() + " を売却 (+" + money(refund) + ")");
    }

    // ================================================================ 経路描画

    protected void recomputePaths() {
        paths.clear();
        for (Vec2i spawn : grid.spawns()) {
            paths.add(grid.pathFrom(spawn));
        }
    }

    protected void drawCurrentPaths() {
        if (players.isEmpty()) {
            return;
        }
        for (PathResult path : paths) {
            if (path.reachable()) {
                Overlay.drawPath(players, toWorldPath(path.waypoints()),
                        Palette.PATH_CURRENT, Overlay.Y_CURRENT, 0.9f);
            }
        }
        // 飛行敵の直線ルートも常時見せる（対策を立てられるように）
        if (tick % (PATH_DRAW_INTERVAL * 4) == 0) {
            for (Vec2i spawn : grid.spawns()) {
                Overlay.drawStraightLine(players,
                        arena.center(spawn, ArenaRenderer.FLYING_Y),
                        arena.coreCenter(grid, ArenaRenderer.SURFACE_Y + 0.8),
                        new Color(200, 120, 255), 0.7f);
            }
        }
    }

    // ================================================================ 装飾・後始末

    protected void createMarkers(String spawnLabel, String coreLabel) {
        for (Vec2i spawn : grid.spawns()) {
            Entity label = Overlay.createLabel(instance,
                    arena.center(spawn, ArenaRenderer.SURFACE_Y + 2.6),
                    Component.text(spawnLabel, NamedTextColor.RED), 3.0f);
            ownedEntities.add(label);
        }
        Entity label = Overlay.createLabel(instance,
                arena.coreCenter(grid, ArenaRenderer.SURFACE_Y + 3.0),
                Component.text(coreLabel, NamedTextColor.AQUA), 4.0f);
        ownedEntities.add(label);
    }

    /**
     * 台座の上に本体を立たせる。強化のたびに立て直す。
     *
     * <p>大きさも段数も装備も変わりうるので、差分を当てるより <b>作り直すほうが確実</b>。
     * 1 基あたり数回しか通らないので、作り直しの重さは問題にならない。</p>
     */
    private void attachTowerBody(TowerInstance tower) {
        removeTowerBodies(tower);
        List<Pos> cellCenters = new ArrayList<>(tower.footprint().size());
        for (Vec2i cell : tower.footprint()) {
            cellCenters.add(arena.center(cell, ArenaRenderer.TOWER_STAND_Y));
        }
        List<Entity> bodies = TowerModel.spawn(instance, tower.kind(), tower.look(), cellCenters,
                new Pos(towerWorldX(tower), ArenaRenderer.TOWER_STAND_Y, towerWorldZ(tower)));
        tower.setBodies(bodies);
        ownedEntities.addAll(bodies);
    }

    private void removeTowerBodies(TowerInstance tower) {
        for (Entity body : tower.bodies()) {
            body.remove();
            ownedEntities.remove(body);
        }
        tower.setBodies(List.of());
    }

    private void attachTowerLabel(TowerInstance tower) {
        Entity label = Overlay.createLabel(instance,
                new Pos(towerWorldX(tower), ArenaRenderer.TOWER_STAND_Y + 2.2, towerWorldZ(tower)),
                towerLabelText(tower), 0.45f);
        tower.setLabel(label);
        ownedEntities.add(label);
    }

    private void updateTowerLabel(TowerInstance tower) {
        if (tower.label() != null) {
            Overlay.updateLabel(tower.label(), towerLabelText(tower));
        }
    }

    private Component towerLabelText(TowerInstance tower) {
        String name = tower.spec() == null ? tower.kind().displayName()
                : tower.kind().displayName() + "・" + tower.spec().displayName();
        return Component.text(name + " Lv" + (tower.level() + 1), tower.kind().element().color());
    }

    protected void clearEnemies() {
        for (EnemyInstance enemy : enemies) {
            enemy.body().remove();
        }
        enemies.clear();
    }

    protected void clearShots() {
        for (Shot shot : shots) {
            shot.remove();
        }
        shots.clear();
    }

    public void dispose() {
        clearEnemies();
        clearShots();
        onDispose();
        for (Entity entity : ownedEntities) {
            entity.remove();
        }
        ownedEntities.clear();
        players.clear();
    }

    // ================================================================ 通知

    public void broadcast(Component message) {
        for (Player player : players) {
            player.sendMessage(message);
        }
    }

    public void playSound(SoundEvent event, float volume, float pitch) {
        Sound sound = Sound.sound(event, Sound.Source.MASTER, volume, pitch);
        for (Player player : players) {
            player.playSound(sound);
        }
    }
}
