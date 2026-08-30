package dev.antigravity.mazeward.stage;

import dev.antigravity.mazeward.enemy.EnemyInstance;
import dev.antigravity.mazeward.enemy.EnemyKind;
import dev.antigravity.mazeward.run.Deck;
import dev.antigravity.mazeward.run.Modifiers;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.run.Wallet;
import dev.antigravity.mazeward.tower.TowerKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.sound.SoundEvent;

/**
 * シングルプレイの 1 ステージ。
 *
 * <p>盤面と戦闘そのものは {@link Battlefield} が持つ。ここが受け持つのは
 * <b>ウェーブとフェーズ、そして勝敗</b> だけ。</p>
 *
 * <p>建築フェーズと戦闘フェーズを分けてはいるが、障害物はどちらでも置ける。
 * 壁は一度置くと撤去できないので、置いては壊す退化戦術は成立しない。</p>
 */
public final class Stage extends Battlefield {

    /** ステージ終了をゲーム全体へ知らせる。 */
    public interface Listener {
        void onStageEnded(Stage stage, boolean victory);
    }

    private record PendingSpawn(int atTick, EnemyKind kind, int spawnIndex) {
    }

    private static final int BOSS_SUMMON_INTERVAL = 120;

    private final StageConfig config;
    private final List<Waves.WaveSpec> waves;
    private final RunState run;
    private final Listener listener;
    private final Deque<PendingSpawn> spawnSchedule = new ArrayDeque<>();

    private final BossBar waveBar = BossBar.bossBar(Component.empty(), 1.0f,
            BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);

    /** ゴールドの出し入れ。{@link RunState} の名前を Battlefield の形に合わせる。 */
    private final Wallet goldWallet = new Wallet() {
        @Override
        public int balance() {
            return run.gold();
        }

        @Override
        public boolean spend(int amount) {
            return run.spendGold(amount);
        }

        @Override
        public void gain(int amount) {
            run.addGold(amount);
        }
    };

    private Phase phase = Phase.BUILD;
    private int waveIndex;
    private int totalThisWave;
    private boolean ended;

    public Stage(Instance instance, StageGenerator.Result generated, RunState run, Listener listener) {
        super(instance, generated.grid(), generated.config().theme());
        this.config = generated.config();
        this.waves = generated.waves();
        this.run = run;
        this.listener = listener;

        arena.paintAll(grid);
        createMarkers("▼ 敵の出現地点", "◆ コア");
        recomputePaths();

        run.deck().resetForStage(run.random());
        run.beginStage(config.layer());
        beginBuildPhase();
    }

    // ================================================================ Battlefield のフック

    @Override
    public Wallet wallet() {
        return goldWallet;
    }

    @Override
    public Modifiers modifiers() {
        return run;
    }

    @Override
    public Deck deck() {
        return run.deck();
    }

    @Override
    public boolean buildingAllowed() {
        return phase.active();
    }

    @Override
    public boolean isUnlocked(TowerKind kind) {
        return run.isUnlocked(kind);
    }

    @Override
    protected void onEnemyKilled(EnemyInstance enemy, Pos at, int reward) {
        if (enemy.kind().boss()) {
            broadcast(Component.text("災厄を討伐した！", NamedTextColor.GOLD));
            playSound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);
        }
    }

    @Override
    protected void onEnemyLeaked(EnemyInstance enemy, Pos at) {
        run.damageCore(enemy.kind().leakDamage());
        broadcast(Component.text(enemy.kind().displayName() + " がコアに到達  コアHP -"
                + enemy.kind().leakDamage() + " (残り " + run.coreHp() + ")", NamedTextColor.RED));
        if (run.coreDestroyed()) {
            finish(false);
        }
    }

    @Override
    protected void onDispose() {
        spawnSchedule.clear();
        for (Player player : players) {
            player.hideBossBar(waveBar);
        }
    }

    // ================================================================ 参照

    public StageConfig config() {
        return config;
    }

    public RunState run() {
        return run;
    }

    public Phase phase() {
        return phase;
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

    public int remainingEnemies() {
        return enemies.size() + spawnSchedule.size();
    }

    @Override
    public void addPlayer(Player player) {
        super.addPlayer(player);
        player.showBossBar(waveBar);
        updateBossBar();
    }

    @Override
    public void removePlayer(Player player) {
        super.removePlayer(player);
        player.hideBossBar(waveBar);
    }

    // ================================================================ フェーズ

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
        for (var path : paths) {
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
        tick = 0;
        phase = Phase.COMBAT;

        broadcast(Component.text("ウェーブ " + wave.number() + " / " + waves.size() + " 開始 — "
                + wave.summary(), NamedTextColor.RED));
        playSound(SoundEvent.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.4f);
        updateBossBar();
        return Outcome.ok("ウェーブ開始");
    }

    // ================================================================ 毎 tick

    public void tick() {
        if (ended) {
            return;
        }
        tick++;

        if (phase == Phase.COMBAT) {
            tickSpawns();
            tickBattle();
            if (tick % BOSS_SUMMON_INTERVAL == 0) {
                applyBossSummons();
            }
            checkWaveEnd();
        }

        if (phase.active()) {
            tickPathDisplay();
        }
        if (tick % 10 == 0) {
            updateBossBar();
        }
    }

    private void tickSpawns() {
        while (!spawnSchedule.isEmpty() && spawnSchedule.peek().atTick() <= tick) {
            PendingSpawn pending = spawnSchedule.poll();
            spawnWaveEnemy(pending.kind(), pending.spawnIndex());
        }
    }

    /** 層とウェーブから HP と報酬を決めて 1 体出す。 */
    private void spawnWaveEnemy(EnemyKind kind, int spawnIndex) {
        double hp = kind.hpAt(config.layer(), waveNumber(), config.difficulty());
        int gold = (int) Math.round(kind.goldAt(config.layer()) * run.goldMultiplier());
        spawnEnemy(kind, spawnIndex, hp, gold);
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
            spawnWaveEnemy(EnemyKind.GRUNT, 0);
        }
        broadcast(Component.text("災厄が随伴を呼んだ！", NamedTextColor.DARK_RED));
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
        clearEnemies();
        spawnSchedule.clear();
        clearShots();
        listener.onStageEnded(this, victory);
    }

    // ================================================================ 通知

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
}
