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
import dev.antigravity.mazeward.run.Rune;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.tower.AttackStyle;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.world.ArenaRenderer;
import dev.antigravity.mazeward.world.Overlay;
import dev.antigravity.mazeward.world.Palette;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;

/**
 * 1 ステージの状態機械。ゲームの心臓部。
 *
 * <p>建築フェーズと戦闘フェーズを厳密に分け、経路の再計算は
 * <b>盤面が変わった瞬間だけ</b> 行う。戦闘中は盤面が変わらないので、
 * 敵が湧いた時点の経路が最後まで正しいことが保証される。</p>
 */
public final class Stage {

    /** ステージ終了をゲーム全体へ知らせる。 */
    public interface Listener {
        void onStageEnded(Stage stage, boolean victory);
    }

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

    private record PendingSpawn(int atTick, EnemyKind kind, int spawnIndex) {
    }

    private static final int PATH_DRAW_INTERVAL = 3;

    /** ダメージ数字をまとめて出す間隔。1 発ごとに出すとエンティティが溢れる。 */
    private static final int DAMAGE_FLUSH_INTERVAL = 8;


    private static final int HEAL_INTERVAL = 20;
    private static final int BOSS_SUMMON_INTERVAL = 120;
    private static final double CHAIN_RADIUS = 3.6;
    private static final double PIERCE_WIDTH = 1.3;

    private final Instance instance;
    private final ArenaRenderer arena;
    private final Grid grid;
    private final StageConfig config;
    private final List<Waves.WaveSpec> waves;
    private final RunState run;
    private final Listener listener;

    private final List<Player> players = new ArrayList<>();
    private final List<TowerInstance> towers = new ArrayList<>();
    private final Map<Vec2i, TowerInstance> towerByCell = new HashMap<>();
    private final Map<Vec2i, Rune> runeCells = new HashMap<>();
    /** どのセルがどのカードの素材で置かれたか。 */
    private final Map<Vec2i, Integer> wallVariants = new HashMap<>();
    private final List<Shot> shots = new ArrayList<>();
    private final List<EnemyInstance> enemies = new ArrayList<>();
    private final Deque<PendingSpawn> spawnSchedule = new ArrayDeque<>();
    private final List<Entity> ownedEntities = new ArrayList<>();
    private final List<PathResult> paths = new ArrayList<>();

    private final BossBar waveBar = BossBar.bossBar(Component.empty(), 1.0f,
            BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
    private final java.util.Random random = new java.util.Random();

    private Phase phase = Phase.BUILD;
    private int waveIndex;
    private int tick;
    private int spawnedThisWave;
    private int gridVersion;
    private int totalThisWave;
    private boolean ended;

    public Stage(Instance instance, StageGenerator.Result generated, RunState run, Listener listener) {
        this.instance = instance;
        this.grid = generated.grid();
        this.config = generated.config();
        this.waves = generated.waves();
        this.run = run;
        this.listener = listener;
        this.arena = new ArenaRenderer(instance, config.theme());

        arena.paintAll(grid);
        createMarkers();
        recomputePaths();

        run.deck().resetForStage(run.random());
        run.beginStage(config.layer());
        beginBuildPhase();
    }

    // ---------------------------------------------------------------- 参照

    public Instance instance() {
        return instance;
    }

    public ArenaRenderer arena() {
        return arena;
    }

    public Grid grid() {
        return grid;
    }

    public StageConfig config() {
        return config;
    }

    public RunState run() {
        return run;
    }

    public Phase phase() {
        return phase;
    }

    /** 盤面が変わるたびに増える。プレビューのキャッシュ判定に使う。 */
    public int gridVersion() {
        return gridVersion;
    }

    public int waveNumber() {
        return Math.min(waveIndex + 1, waves.size());
    }

    public int waveCount() {
        return waves.size();
    }

    public Waves.WaveSpec nextWave() {
        return waveIndex < waves.size() ? waves.get(waveIndex) : null;
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

    public int remainingEnemies() {
        return enemies.size() + spawnSchedule.size();
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
        player.showBossBar(waveBar);
        updateBossBar();
    }

    public void removePlayer(Player player) {
        players.remove(player);
        player.hideBossBar(waveBar);
    }

    // ---------------------------------------------------------------- フェーズ

    private void beginBuildPhase() {
        phase = Phase.BUILD;
        int drawn = run.deck().drawToHandSize(run.handSize());
        updateBossBar();
        if (drawn > 0) {
            broadcast(Component.text("建築フェーズ: カードを " + drawn + " 枚引きました", NamedTextColor.AQUA));
        }
    }

    /** プレイヤーが「開始」を押したときに呼ぶ。 */
    public Outcome startWave() {
        if (phase != Phase.BUILD) {
            return Outcome.fail("いまは建築フェーズではありません");
        }
        if (waveIndex >= waves.size()) {
            return Outcome.fail("このステージのウェーブは終わっています");
        }

        recomputePaths();
        for (PathResult path : paths) {
            if (!path.reachable()) {
                return Outcome.fail("経路が塞がっています（バグ回避のため開始できません）");
            }
        }

        Waves.WaveSpec wave = waves.get(waveIndex);
        spawnSchedule.clear();
        List<PendingSpawn> pending = new ArrayList<>();
        for (Waves.Entry entry : wave.entries()) {
            int spawnIndex = Math.min(entry.spawnIndex(), grid.spawns().size() - 1);
            for (int i = 0; i < entry.count(); i++) {
                pending.add(new PendingSpawn(entry.delayTicks() + i * entry.intervalTicks(),
                        entry.kind(), spawnIndex));
            }
        }
        pending.sort((a, b) -> Integer.compare(a.atTick(), b.atTick()));
        spawnSchedule.addAll(pending);

        totalThisWave = wave.totalEnemies();
        spawnedThisWave = 0;
        tick = 0;
        phase = Phase.COMBAT;

        broadcast(Component.text("ウェーブ " + wave.number() + " / " + waves.size() + " 開始 — "
                + wave.summary(), NamedTextColor.RED));
        playSound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
        updateBossBar();
        return Outcome.ok("ウェーブ開始");
    }

    // ---------------------------------------------------------------- 毎 tick

    public void tick() {
        if (ended) {
            return;
        }
        tick++;

        if (phase == Phase.COMBAT) {
            tickSpawns();
            tickEnemies();
            tickTowers();
            tickShots();
            checkWaveEnd();
        }

        if (tick % PATH_DRAW_INTERVAL == 0 && phase.active()) {
            drawCurrentPaths();
        }
        if (tick % 10 == 0) {
            updateBossBar();
        }
    }

    private void tickSpawns() {
        while (!spawnSchedule.isEmpty() && spawnSchedule.peek().atTick() <= tick) {
            PendingSpawn pending = spawnSchedule.poll();
            spawnEnemy(pending.kind(), pending.spawnIndex());
            spawnedThisWave++;
        }
    }

    private void tickEnemies() {
        List<EnemyInstance> dead = null;
        List<EnemyInstance> leaked = null;

        for (EnemyInstance enemy : enemies) {
            enemy.tick();
            enemy.syncBody();

            if (!enemy.alive()) {
                (dead == null ? dead = new ArrayList<>() : dead).add(enemy);
            } else if (enemy.leaked()) {
                (leaked == null ? leaked = new ArrayList<>() : leaked).add(enemy);
            }
        }

        applyFieldRunes();
        if (tick % DAMAGE_FLUSH_INTERVAL == 0) {
            flushDamageNumbers();
        }
        if (tick % HEAL_INTERVAL == 0) {
            applyHealerAuras();
        }
        if (tick % BOSS_SUMMON_INTERVAL == 0) {
            applyBossSummons();
        }

        if (dead != null) {
            for (EnemyInstance enemy : dead) {
                onEnemyKilled(enemy);
            }
        }
        if (leaked != null) {
            for (EnemyInstance enemy : leaked) {
                onEnemyLeaked(enemy);
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
                    double distX = pos.x() - (cell.x() + 0.5);
                    double distZ = pos.z() - (cell.z() + 0.5);
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
     * どの塔が実際に効いているのかが、盤面を見るだけで分かるようにするのが目的。
     * 背景の灰色板は消して数字だけを出す。</p>
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

    private void applyHealerAuras() {
        for (EnemyInstance healer : enemies) {
            if (!healer.kind().healer() || !healer.alive()) {
                continue;
            }
            Pos center = healer.position();
            for (EnemyInstance target : enemies) {
                if (target == healer || !target.alive()) {
                    continue;
                }
                if (target.position().distance(center) <= 5.0) {
                    target.heal(healer.kind().healPerSecond());
                }
            }
            Overlay.drawBurst(players, center.withY(center.y() + 1.2), Particle.HAPPY_VILLAGER, 4, 1.2f);
        }
    }

    private void applyBossSummons() {
        boolean hasBoss = false;
        for (EnemyInstance enemy : enemies) {
            if (enemy.kind().boss() && enemy.alive()) {
                hasBoss = true;
                break;
            }
        }
        if (!hasBoss) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            spawnEnemy(EnemyKind.GRUNT, 0);
        }
        broadcast(Component.text("災厄が随伴を呼んだ！", NamedTextColor.DARK_RED));
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

    private void checkWaveEnd() {
        if (run.coreDestroyed()) {
            finish(false);
            return;
        }
        if (!spawnSchedule.isEmpty() || !enemies.isEmpty()) {
            return;
        }

        Waves.WaveSpec wave = waves.get(waveIndex);
        int bonus = (int) Math.round(wave.clearBonus() * run.goldMultiplier());
        run.addGold(bonus);
        broadcast(Component.text("ウェーブ " + wave.number() + " 制圧  +" + bonus + "G", NamedTextColor.GOLD));
        playSound(SoundEvent.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);

        waveIndex++;
        if (waveIndex >= waves.size()) {
            finish(true);
        } else {
            beginBuildPhase();
        }
    }

    private void finish(boolean victory) {
        if (ended) {
            return;
        }
        ended = true;
        phase = victory ? Phase.VICTORY : Phase.DEFEAT;
        for (EnemyInstance enemy : enemies) {
            enemy.body().remove();
        }
        enemies.clear();
        spawnSchedule.clear();
        clearShots();
        listener.onStageEnded(this, victory);
    }

    // ---------------------------------------------------------------- 敵

    private void spawnEnemy(EnemyKind kind, int spawnIndex) {
        if (grid.spawns().isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(spawnIndex, grid.spawns().size() - 1));
        List<Pos> waypoints = waypointsFor(kind, index);
        if (waypoints.size() < 2) {
            return;
        }

        Entity body = new Entity(kind.entityType());
        body.setNoGravity(true);
        body.setInstance(instance, waypoints.get(0));

        double hp = kind.hpAt(config.layer(), waveNumber(), config.difficulty());
        int gold = (int) Math.round(kind.goldAt(config.layer()) * run.goldMultiplier());
        enemies.add(new EnemyInstance(kind, body, waypoints, hp, gold));
    }

    private List<Pos> waypointsFor(EnemyKind kind, int spawnIndex) {
        Vec2i spawn = grid.spawns().get(spawnIndex);
        if (kind.flying()) {
            // 迷路を完全に無視する直線ルート
            Pos start = new Pos(spawn.x() + 0.5, ArenaRenderer.FLYING_Y, spawn.z() + 0.5);
            Pos end = new Pos(grid.coreCenterX(), ArenaRenderer.SURFACE_Y + 0.8, grid.coreCenterZ());
            return List.of(start, end);
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

    private void onEnemyKilled(EnemyInstance enemy) {
        enemies.remove(enemy);
        Pos at = enemy.position();
        enemy.body().remove();
        run.addGold(enemy.goldReward() + goldVeinBonus(at));

        Overlay.drawBurst(players, at.withY(at.y() + 0.8), Particle.CRIT, 8, 0.35f);
        for (Player player : players) {
            Overlay.popupText(instance, player, at.withY(at.y() + 1.4),
                    Component.text("+" + enemy.goldReward() + "G", NamedTextColor.GOLD), 14);
        }
        if (enemy.kind().boss()) {
            broadcast(Component.text("災厄を討伐した！", NamedTextColor.GOLD));
            playSound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);
        }
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
                double distX = at.x() - (cell.x() + 0.5);
                double distZ = at.z() - (cell.z() + 0.5);
                if (Math.sqrt(distX * distX + distZ * distZ) <= Rune.GOLD_VEIN_RADIUS) {
                    return Rune.GOLD_VEIN_BONUS;
                }
            }
        }
        return 0;
    }

    private void onEnemyLeaked(EnemyInstance enemy) {
        enemies.remove(enemy);
        Pos at = enemy.position();
        enemy.body().remove();
        run.damageCore(enemy.kind().leakDamage());

        Overlay.drawBurst(players, at.withY(at.y() + 1.0), Particle.LARGE_SMOKE, 12, 0.5f);
        broadcast(Component.text(enemy.kind().displayName() + " がコアに到達  コアHP -"
                + enemy.kind().leakDamage() + " (残り " + run.coreHp() + ")", NamedTextColor.RED));
        playSound(SoundEvent.BLOCK_ANVIL_LAND, 0.6f, 0.7f);

        if (run.coreDestroyed()) {
            finish(false);
        }
    }

    // ---------------------------------------------------------------- タワーの射撃

    /**
     * レリックと土台のルーンをすべて適用したあとの実効性能。
     *
     * <p>戦闘の計算は必ずここを通す。素の値に補正を足す場所が散らばると、
     * 「どのタワーが本当はどれだけ強いのか」が誰にも分からなくなるため。</p>
     */
    public TowerKind.Stats resolvedStats(TowerInstance tower) {
        TowerKind.Stats base = tower.stats();
        java.util.EnumSet<Rune> runes = runesUnder(tower);

        double damage = base.damage() * (runes.contains(Rune.REINFORCED) ? Rune.REINFORCED_DAMAGE : 1.0);
        double range = base.range() + run.rangeBonus()
                + (runes.contains(Rune.LENS) ? Rune.LENS_RANGE : 0.0);
        int cooldown = runes.contains(Rune.BEACON)
                ? Math.max(2, (int) Math.round(base.cooldown() * Rune.BEACON_COOLDOWN))
                : base.cooldown();
        double slow = base.slowFactor() > 0 ? Math.min(0.85, base.slowFactor() + run.slowBonus()) : 0.0;

        return new TowerKind.Stats(
                damage,
                range,
                cooldown,
                base.splashRadius() + run.splashBonus(),
                base.chainTargets() + run.chainBonus(),
                slow,
                base.slowTicks(),
                base.burnDps() * run.burnMultiplier(),
                base.burnTicks());
    }

    /**
     * その壁セルに置くブロック。
     * ルーンが付いていればルーンの色、なければ <b>そのセルを置いたカードの素材</b>。
     * 1 枚のカードが作った壁は全部同じブロックになる。
     */
    private net.minestom.server.instance.block.Block wallBlockFor(Vec2i cell) {
        Rune rune = runeCells.get(cell);
        if (rune != null) {
            return rune.wallBlock();
        }
        return arena.theme().wallForVariant(wallVariants.getOrDefault(cell, 0));
    }

    /** そのタワーが乗っているセルに付いているルーン（同じ種類は 1 回だけ数える）。 */
    public java.util.EnumSet<Rune> runesUnder(TowerInstance tower) {
        java.util.EnumSet<Rune> runes = java.util.EnumSet.noneOf(Rune.class);
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

        if (tower.kind().style() == AttackStyle.AURA) {
            fireAura(tower, stats, range);
            return;
        }

        EnemyInstance target = findTarget(tower, range);
        if (target == null) {
            return;
        }
        tower.resetCooldown(stats.cooldown());
        playFireSound(tower);

        Pos muzzle = new Pos(tower.centerX(), ArenaRenderer.WALL_TOP_Y + 0.6, tower.centerZ());
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
     *
     * <p>間引きはしない。以前は同じ種類の塔をまとめて間引いていたが、
     * ウェーブ開始で tick が 0 に戻るのに前ウェーブの記録が残るせいで、
     * ウェーブの頭で音がまるごと消える不具合になっていた。</p>
     */
    private void playFireSound(TowerInstance tower) {
        Sound sound = Sound.sound(tower.kind().fireSound(), Sound.Source.BLOCK,
                0.5f, tower.kind().firePitch());
        for (Player player : players) {
            player.playSound(sound, tower.centerX(), ArenaRenderer.WALL_TOP_Y, tower.centerZ());
        }
    }

    /** 塔から狙った先へアイテムを飛ばす。当たり判定は持たない見た目だけの弾。 */
    private void spawnShot(TowerInstance tower, Pos from, Pos to) {
        Shot shot = Shot.spawn(instance, from, to,
                tower.kind().projectile(), tower.kind().projectileScale());
        if (shot != null) {
            shots.add(shot);
        }
    }

    private void tickShots() {
        shots.removeIf(shot -> !shot.tick());
    }

    private void fireAura(TowerInstance tower, TowerKind.Stats stats, double range) {
        tower.resetCooldown(stats.cooldown());
        playFireSound(tower);
        double burn = stats.burnDps();
        boolean any = false;
        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive()) {
                continue;
            }
            Pos pos = enemy.position();
            if (tower.distanceTo(pos.x(), pos.z()) <= range) {
                enemy.applyBurn(burn, stats.burnTicks());
                any = true;
            }
        }
        if (any) {
            Overlay.drawRangeRing(players, tower.centerX(), tower.centerZ(), range);
        }
    }

    private EnemyInstance findTarget(TowerInstance tower, double range) {
        EnemyInstance best = null;
        double bestRemaining = Double.MAX_VALUE;
        for (EnemyInstance enemy : enemies) {
            if (!enemy.alive()) {
                continue;
            }
            Pos pos = enemy.position();
            if (tower.distanceTo(pos.x(), pos.z()) > range) {
                continue;
            }
            // コアにいちばん近い敵を狙う（TD の定番かつ漏れに強い）。
            // 進行距離ではなく残距離で見るので、戦闘中に経路を引き直しても優先順位が壊れない。
            if (enemy.remaining() < bestRemaining) {
                bestRemaining = enemy.remaining();
                best = enemy;
            }
        }
        return best;
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

    // ---------------------------------------------------------------- 配置

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
     * <p>建築フェーズでも戦闘フェーズでも置ける。壁は一度置いたら撤去できない仕様なので、
     * 「置いては壊して敵を往復させる」タイプの退化戦術は成立しない。
     * ただし戦闘中は生きた敵が盤面にいるので、追加で 2 つの条件を課す。</p>
     */
    public String cardPlacementError(Shape shape, Vec2i origin, Rot rot) {
        if (!phase.active()) {
            return "いまは配置できません";
        }
        Grid.Placement placement = grid.checkPlacement(shape, origin, rot);
        if (!placement.ok()) {
            return placement.reason();
        }
        if (phase == Phase.COMBAT) {
            return combatPlacementError(shape.cellsAt(origin, rot));
        }
        return null;
    }

    /** 戦闘中だけの追加条件: 敵の上に置かない・敵を閉じ込めない。 */
    private String combatPlacementError(List<Vec2i> target) {
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
        BlockCard card = run.deck().peek(handIndex);
        if (card == null) {
            return Outcome.fail("そのカードは手札にありません");
        }
        String error = cardPlacementError(card.shape(), origin, rot);
        if (error != null) {
            return Outcome.fail(error);
        }

        grid.place(card.shape(), origin, rot);
        run.deck().play(handIndex);
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
        if (phase == Phase.COMBAT) {
            repathEnemies();
        }
        playSound(SoundEvent.BLOCK_STONE_PLACE, 0.9f, 1.0f);
        return Outcome.ok(card.displayName() + " を配置");
    }

    /**
     * 生きている地上の敵の経路を、いまの立ち位置から引き直す。
     * 戦闘中に壁が増えたときに呼ぶ。これをやらないと敵が新しい壁をすり抜けて見える。
     */
    private void repathEnemies() {
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
        if (!phase.active()) {
            return "いまはタワーを置けません";
        }
        if (!run.isUnlocked(kind)) {
            return kind.displayName() + " はまだ解放されていません";
        }
        if (!grid.isTowerBaseFor(kind.shape(), origin, rot)) {
            return "壁または岩の上にしか置けません";
        }
        for (Vec2i cell : kind.shape().cellsAt(origin, rot)) {
            if (towerByCell.containsKey(cell)) {
                return "すでにタワーがあります";
            }
        }
        if (run.gold() < kind.baseCost()) {
            return "ゴールドが足りません（" + kind.baseCost() + "G 必要）";
        }
        return null;
    }

    public Outcome placeTower(TowerKind kind, Vec2i origin, Rot rot) {
        String error = towerPlacementError(kind, origin, rot);
        if (error != null) {
            return Outcome.fail(error);
        }
        run.spendGold(kind.baseCost());

        TowerInstance tower = new TowerInstance(kind, origin, rot, kind.baseCost());
        towers.add(tower);
        for (Vec2i cell : tower.footprint()) {
            towerByCell.put(cell, tower);
        }
        arena.paintTower(tower.footprint(), kind.model());
        attachTowerLabel(tower);
        playSound(SoundEvent.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        return Outcome.ok(kind.displayName() + " を設置 (-" + kind.baseCost() + "G)");
    }

    public Outcome upgradeTower(Vec2i cell) {
        TowerInstance tower = towerByCell.get(cell);
        if (tower == null) {
            return Outcome.fail("タワーがありません");
        }
        if (tower.maxed()) {
            return Outcome.fail("すでに最大レベルです");
        }
        int cost = (int) Math.round(tower.nextUpgradeCost() * run.upgradeCostMultiplier());
        if (!run.spendGold(cost)) {
            return Outcome.fail("ゴールドが足りません（" + cost + "G 必要）");
        }
        tower.upgrade(cost);
        updateTowerLabel(tower);
        playSound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.8f);
        return Outcome.ok(tower.kind().displayName() + " を Lv" + (tower.level() + 1) + " に強化 (-" + cost + "G)");
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
        arena.clearTower(tower.footprint());
        if (tower.label() != null) {
            tower.label().remove();
            ownedEntities.remove(tower.label());
        }
        run.addGold(refund);
        playSound(SoundEvent.BLOCK_GLASS_BREAK, 0.7f, 1.0f);
        return Outcome.ok(tower.kind().displayName() + " を売却 (+" + refund + "G)");
    }

    // ---------------------------------------------------------------- 経路描画

    private void recomputePaths() {
        paths.clear();
        for (Vec2i spawn : grid.spawns()) {
            paths.add(grid.pathFrom(spawn));
        }
    }

    private void drawCurrentPaths() {
        if (players.isEmpty()) {
            return;
        }
        for (PathResult path : paths) {
            if (path.reachable()) {
                Overlay.drawPath(players, path.waypoints(), Palette.PATH_CURRENT, Overlay.Y_CURRENT, 0.9f);
            }
        }
        // 飛行敵の直線ルートも常時見せる（対策を立てられるように）
        if (tick % (PATH_DRAW_INTERVAL * 4) == 0) {
            for (Vec2i spawn : grid.spawns()) {
                Overlay.drawStraightLine(players,
                        new Pos(spawn.x() + 0.5, ArenaRenderer.FLYING_Y, spawn.z() + 0.5),
                        new Pos(grid.coreCenterX(), ArenaRenderer.SURFACE_Y + 0.8, grid.coreCenterZ()),
                        new Color(200, 120, 255), 0.7f);
            }
        }
    }

    // ---------------------------------------------------------------- 装飾・後始末

    private void createMarkers() {
        for (Vec2i spawn : grid.spawns()) {
            Entity label = Overlay.createLabel(instance,
                    new Pos(spawn.x() + 0.5, ArenaRenderer.SURFACE_Y + 2.6, spawn.z() + 0.5),
                    Component.text("▼ 敵の出現地点", NamedTextColor.RED), 3.0f);
            ownedEntities.add(label);
        }
        Entity coreLabel = Overlay.createLabel(instance,
                new Pos(grid.coreCenterX(), ArenaRenderer.SURFACE_Y + 3.0, grid.coreCenterZ()),
                Component.text("◆ コア", NamedTextColor.AQUA), 4.0f);
        ownedEntities.add(coreLabel);
    }

    private void attachTowerLabel(TowerInstance tower) {
        Entity label = Overlay.createLabel(instance,
                new Pos(tower.centerX(), ArenaRenderer.WALL_TOP_Y + 1.4, tower.centerZ()),
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
        return Component.text(tower.kind().displayName() + " Lv" + (tower.level() + 1),
                tower.kind().element().color());
    }

    private void clearShots() {
        for (Shot shot : shots) {
            shot.remove();
        }
        shots.clear();
    }

    public void dispose() {
        for (EnemyInstance enemy : enemies) {
            enemy.body().remove();
        }
        enemies.clear();
        clearShots();
        for (Entity entity : ownedEntities) {
            entity.remove();
        }
        ownedEntities.clear();
        for (Player player : players) {
            player.hideBossBar(waveBar);
        }
        players.clear();
    }

    // ---------------------------------------------------------------- 通知

    private void updateBossBar() {
        if (phase == Phase.BUILD) {
            Waves.WaveSpec next = nextWave();
            waveBar.name(Component.text("建築フェーズ — 次: ウェーブ " + waveNumber() + "/" + waves.size()
                    + (next == null ? "" : "  [" + next.summary() + "]"), NamedTextColor.AQUA));
            waveBar.color(BossBar.Color.BLUE);
            waveBar.progress(1.0f);
            return;
        }
        if (phase == Phase.COMBAT) {
            int remaining = remainingEnemies();
            float progress = totalThisWave <= 0 ? 0f
                    : Math.max(0f, Math.min(1f, remaining / (float) totalThisWave));
            waveBar.name(Component.text("ウェーブ " + waveNumber() + "/" + waves.size()
                    + "  残敵 " + remaining + "/" + totalThisWave, NamedTextColor.RED));
            waveBar.color(BossBar.Color.RED);
            waveBar.progress(progress);
        }
    }

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
