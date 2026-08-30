package dev.antigravity.mazeward.run;

import dev.antigravity.mazeward.tower.TowerKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 1 ラン分の状態。<b>Minestom に依存しないので、そのまま JSON にすれば永続化できる。</b>
 *
 * <p>レリックの効果はすべてここの modifier 系メソッドに集約してある。
 * 戦闘側は「素の値 + RunState の補正」だけを見ればよい。</p>
 */
public final class RunState {

    /** ステージ開始時に配られるゴールドの基準値。 */
    private static final int STAGE_BASE_GOLD = 130;

    /** 層が上がるごとに増えるゴールド。 */
    private static final int STAGE_GOLD_PER_LAYER = 25;

    private static final int START_EMBER = 0;
    private static final int START_CORE_HP = 20;

    private final long seed;
    private final Random random;
    private final Roadmap roadmap;
    private final Deck deck;
    private final Set<TowerKind> unlocked = EnumSet.noneOf(TowerKind.class);
    private final Set<Relic> relics = new LinkedHashSet<>();

    /** ステージ内だけで使う通貨。タワーの設置と強化に使い、ステージが変わると持ち越さない。 */
    private int gold;

    /** ステージ間で使う通貨。商店・祭壇・イベントでの買い物に使う。 */
    private int ember = START_EMBER;

    private int coreHp = START_CORE_HP;
    private int maxCoreHp = START_CORE_HP;
    private int layer = 1;
    /** 直前に踏んだノードの index。-1 は「まだどのノードも踏んでいない」。 */
    private int lastNodeIndex = -1;
    /** 各層で実際に踏んだノードの index。通ってきた道を描くのに使う。 */
    private final List<Integer> takenPath = new ArrayList<>();
    private int clearedStages;
    private boolean finished;
    private boolean victorious;

    public RunState(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
        this.roadmap = Roadmap.generate(random);
        this.deck = Deck.starter();
        this.unlocked.addAll(TowerKind.starterUnlocks());
    }

    public long seed() {
        return seed;
    }

    public Random random() {
        return random;
    }

    public Roadmap roadmap() {
        return roadmap;
    }

    public Deck deck() {
        return deck;
    }

    // ---------------------------------------------------------------- 資源

    public int gold() {
        return gold;
    }

    public void addGold(int amount) {
        gold = Math.max(0, gold + amount);
    }

    public boolean spendGold(int amount) {
        if (gold < amount) {
            return false;
        }
        gold -= amount;
        return true;
    }

    /**
     * ステージ開始時のゴールドを配り直す。
     *
     * <p><b>ゴールドは持ち越さない。</b> 持ち越すと、序盤に節約して溜め込むのが常に最適になり、
     * 「いま出せる火力でこのウェーブを凌げるか」という 1 ステージ内の判断が消えてしまう。
     * 毎ステージ同じ条件から始めることで、盤面ごとに使い切る配分の勝負になる。</p>
     */
    public void beginStage(int layer) {
        gold = STAGE_BASE_GOLD + STAGE_GOLD_PER_LAYER * (layer - 1) + stageStartBonusGold();
    }

    // ---------------------------------------------------------------- エンバー

    public int ember() {
        return ember;
    }

    public void addEmber(int amount) {
        ember = Math.max(0, ember + amount);
    }

    public boolean spendEmber(int amount) {
        if (ember < amount) {
            return false;
        }
        ember -= amount;
        return true;
    }

    /** ステージを制圧したときに得るエンバー。 */
    public int emberRewardFor(Roadmap.NodeKind kind, int layer) {
        int base = switch (kind) {
            case ELITE -> 110 + 15 * layer;
            case BOSS -> 260;
            default -> 60 + 10 * layer;
        };
        return (int) Math.round(base * goldMultiplier());
    }

    public int coreHp() {
        return coreHp;
    }

    public int maxCoreHp() {
        return maxCoreHp;
    }

    public void damageCore(int amount) {
        coreHp = Math.max(0, coreHp - amount);
    }

    public void healCore(int amount) {
        coreHp = Math.min(maxCoreHp, coreHp + amount);
    }

    public void raiseMaxCoreHp(int amount) {
        maxCoreHp += amount;
        coreHp += amount;
    }

    public boolean coreDestroyed() {
        return coreHp <= 0;
    }

    // ---------------------------------------------------------------- 進行

    public int layer() {
        return layer;
    }

    public int lastNodeIndex() {
        return lastNodeIndex;
    }

    /**
     * 1 つノードを踏み終えた。次に選べるのは、そのノードから辺が伸びている先だけになる。
     *
     * @param nodeIndex いま踏み終えたノードの index
     */
    public void advanceLayer(int nodeIndex) {
        takenPath.add(nodeIndex);
        lastNodeIndex = nodeIndex;
        layer++;
        clearedStages++;
    }

    /** 第 layer 層で踏んだノードの index。まだ踏んでいなければ -1。 */
    public int takenAt(int layer) {
        return layer >= 1 && layer <= takenPath.size() ? takenPath.get(layer - 1) : -1;
    }

    public int clearedStages() {
        return clearedStages;
    }

    public boolean finished() {
        return finished;
    }

    public boolean victorious() {
        return victorious;
    }

    public void finish(boolean victory) {
        finished = true;
        victorious = victory;
    }

    /**
     * いま進める先のノード。
     *
     * <p>第 1 層はどれでも選べる。以降は「直前に踏んだノードから辺が伸びている先」だけ。
     * これがあるから、ロードマップ全体を見て数手先まで経路を組み立てる意味が生まれる。</p>
     */
    public List<Roadmap.Node> currentChoices() {
        List<Roadmap.Node> row = roadmap.layer(layer);
        if (lastNodeIndex < 0 || layer <= 1) {
            return row;
        }
        Roadmap.Node from = roadmap.node(layer - 1, lastNodeIndex);
        if (from == null || from.next().isEmpty()) {
            return row;
        }
        List<Roadmap.Node> reachable = new ArrayList<>();
        for (int index : from.next()) {
            Roadmap.Node node = roadmap.node(layer, index);
            if (node != null) {
                reachable.add(node);
            }
        }
        return reachable.isEmpty() ? row : reachable;
    }

    /** そのノードへいま進めるか。 */
    public boolean canEnter(Roadmap.Node node) {
        return node != null && node.layer() == layer && currentChoices().contains(node);
    }

    // ---------------------------------------------------------------- 解放

    public Set<TowerKind> unlockedTowers() {
        return Collections.unmodifiableSet(unlocked);
    }

    public boolean isUnlocked(TowerKind kind) {
        return unlocked.contains(kind);
    }

    public void unlock(TowerKind kind) {
        unlocked.add(kind);
    }

    public TowerKind randomLockedTower() {
        List<TowerKind> locked = java.util.Arrays.stream(TowerKind.values())
                .filter(kind -> !unlocked.contains(kind))
                .toList();
        if (locked.isEmpty()) {
            return null;
        }
        return locked.get(random.nextInt(locked.size()));
    }

    // ---------------------------------------------------------------- レリック

    public Set<Relic> relics() {
        return Collections.unmodifiableSet(relics);
    }

    public void grant(Relic relic) {
        if (relic == null || !relics.add(relic)) {
            return;
        }
        if (relic == Relic.BULWARK) {
            raiseMaxCoreHp(5);
        }
    }

    // ---------------------------------------------------------------- 補正値

    public double rangeBonus() {
        return relics.contains(Relic.LONG_LENS) ? 0.8 : 0.0;
    }

    public double goldMultiplier() {
        return relics.contains(Relic.TAX_COLLECTOR) ? 1.25 : 1.0;
    }

    public int handSize() {
        return deck.baseHandSize() + (relics.contains(Relic.MASON_HAND) ? 1 : 0);
    }

    public int stageStartBonusGold() {
        return relics.contains(Relic.WAR_CHEST) ? 60 : 0;
    }

    public double slowBonus() {
        return relics.contains(Relic.FROST_SIGIL) ? 0.15 : 0.0;
    }

    public int chainBonus() {
        return relics.contains(Relic.ARC_COIL) ? 1 : 0;
    }

    public double splashBonus() {
        return relics.contains(Relic.POWDER_HEART) ? 0.8 : 0.0;
    }

    public double upgradeCostMultiplier() {
        return relics.contains(Relic.SWIFT_FORGE) ? 0.8 : 1.0;
    }

    public double burnMultiplier() {
        return relics.contains(Relic.KINDLING) ? 1.4 : 1.0;
    }
}
