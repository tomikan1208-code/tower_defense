package dev.antigravity.mazeward.stage;

import dev.antigravity.mazeward.enemy.EnemyKind;
import dev.antigravity.mazeward.run.Roadmap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ウェーブ構成の生成。
 *
 * <p>敵構成には「プレイヤーに何を強制するか」という意図を持たせている。</p>
 * <ul>
 *   <li>FLYER は 3 ウェーブごとに来る → 経路を伸ばすだけの構成を潰し、直線上への配置を強制する</li>
 *   <li>BRUTE は中盤から → 低ダメージ高速タワーだけの構成を潰す</li>
 *   <li>HEALER は終盤から → 単体火力だけの構成を潰す</li>
 *   <li>SAPPER / BLINKER は第 4 層から → 火力を 1 箇所に固める構成を潰す</li>
 *   <li>SPLITTER / EMBERLING は第 5 層から → 単体火力・炎氷偏重を潰す</li>
 *   <li>AEGIS は第 6 層から → 「数字を並べれば溶ける」を潰す</li>
 *   <li>REAPER は第 6 層以降の精鋭とボスだけ → 倒す速さそのものを問う</li>
 * </ul>
 *
 * <p>能力持ちは <b>層で解禁し、数は絞る</b>。同時に大量に出すと
 * 何に対処しているのか分からなくなり、対策を立てる余地が消える。</p>
 */
public final class Waves {

    /**
     * 1 種類の敵の湧き指示。
     *
     * @param delayTicks    ウェーブ開始からの遅延
     * @param intervalTicks 1 体ごとの間隔
     * @param spawnIndex    どのスポーン地点から出るか
     */
    public record Entry(EnemyKind kind, int count, int intervalTicks, int delayTicks, int spawnIndex) {
    }

    /** 1 ウェーブ分。 */
    public record WaveSpec(int number, List<Entry> entries, int clearBonus) {

        public int totalEnemies() {
            int total = 0;
            for (Entry entry : entries) {
                total += entry.count();
            }
            return total;
        }

        /** ウェーブの構成を 1 行で説明する（HUD 用）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Entry entry : entries) {
                if (!sb.isEmpty()) {
                    sb.append(" / ");
                }
                sb.append(entry.kind().displayName()).append("x").append(entry.count());
            }
            return sb.toString();
        }
    }

    private Waves() {
    }

    public static List<WaveSpec> generate(int layer, Roadmap.NodeKind nodeKind,
                                          int waveCount, int spawnCount, Random random) {
        List<WaveSpec> waves = new ArrayList<>(waveCount);
        boolean elite = nodeKind == Roadmap.NodeKind.ELITE;
        boolean boss = nodeKind == Roadmap.NodeKind.BOSS;

        for (int wave = 1; wave <= waveCount; wave++) {
            List<Entry> entries = new ArrayList<>();
            boolean finalWave = wave == waveCount;

            if (boss && finalWave) {
                entries.add(new Entry(EnemyKind.BOSS, 1, 0, 0, 0));
                entries.add(new Entry(EnemyKind.BRUTE, 3, 40, 60, spawnCount > 1 ? 1 : 0));
                entries.add(new Entry(EnemyKind.GRUNT, 12, 18, 100, 0));
                entries.add(new Entry(EnemyKind.FLYER, 4, 30, 200, spawnCount > 1 ? 1 : 0));
                entries.add(new Entry(EnemyKind.AEGIS, 1, 90, 140, 0));
                entries.add(new Entry(EnemyKind.REAPER, 1, 0, 300, spawnCount > 1 ? 1 : 0));
                waves.add(new WaveSpec(wave, List.copyOf(entries), 200));
                continue;
            }

            double sizeScale = (elite ? 1.25 : 1.0) + (boss ? 0.2 : 0.0);
            int budget = (int) Math.round((5 + wave * 2 + layer) * sizeScale);
            int spawn = spawnCount > 1 ? random.nextInt(spawnCount) : 0;
            int otherSpawn = spawnCount > 1 ? (spawn + 1) % spawnCount : 0;

            int gruntCount = Math.max(3, budget - wave);
            entries.add(new Entry(EnemyKind.GRUNT, gruntCount, Math.max(8, 22 - wave), 0, spawn));

            if (wave >= 2) {
                int runners = 2 + wave / 2 + (elite ? 2 : 0);
                entries.add(new Entry(EnemyKind.RUNNER, runners, 10, 50, otherSpawn));
            }

            // 3 ウェーブごとに飛行。迷路を無視するので、直線上の火力が必ず要る。
            int flyerFrom = elite ? 2 : 3;
            if (wave >= flyerFrom && wave % 3 == 0) {
                int flyers = 2 + wave / 2 + (elite ? 1 : 0);
                entries.add(new Entry(EnemyKind.FLYER, flyers, 22, 90, spawn));
            }

            if (wave >= (elite ? 3 : 4)) {
                int brutes = 1 + (wave - 3) / 2 + (elite ? 1 : 0);
                entries.add(new Entry(EnemyKind.BRUTE, brutes, 45, 120, spawn));
            }

            if (wave >= (elite ? 4 : 5)) {
                int healers = 1 + (wave >= 7 ? 1 : 0);
                entries.add(new Entry(EnemyKind.HEALER, healers, 60, 150, otherSpawn));
            }

            // ---------------- 能力持ち。層で解禁し、1〜2 体だけ混ぜる

            if (layer >= 4 && wave >= 4) {
                // 妨害者と瞬移体は交互に。両方来ると対策が同時に 2 つ要って重すぎる
                boolean sapper = (layer + wave) % 2 == 0;
                entries.add(new Entry(sapper ? EnemyKind.SAPPER : EnemyKind.BLINKER,
                        1 + (elite ? 1 : 0), 50, 110, otherSpawn));
            }

            if (layer >= 5 && wave >= 4) {
                entries.add(new Entry(EnemyKind.SPLITTER, 1 + wave / 5, 55, 170, spawn));
            }

            if (layer >= 5 && wave % 3 == 1 && wave >= 4) {
                entries.add(new Entry(EnemyKind.EMBERLING, 1, 50, 200, spawn));
            }

            if (layer >= 6 && wave >= (elite ? 4 : 5)) {
                entries.add(new Entry(EnemyKind.AEGIS, 1, 0, 130, otherSpawn));
            }

            if ((elite || boss) && layer >= 6 && finalWave) {
                entries.add(new Entry(EnemyKind.REAPER, 1, 0, 220, spawn));
            }

            int bonus = 20 + wave * 5 + layer * 3 + (elite ? 25 : 0);
            waves.add(new WaveSpec(wave, List.copyOf(entries), bonus));
        }

        return List.copyOf(waves);
    }
}
