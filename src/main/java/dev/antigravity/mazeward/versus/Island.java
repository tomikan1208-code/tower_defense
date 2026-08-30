package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.enemy.EnemyInstance;
import dev.antigravity.mazeward.run.Deck;
import dev.antigravity.mazeward.run.Modifiers;
import dev.antigravity.mazeward.run.Wallet;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.world.Palette;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;

/**
 * 対戦での 1 人ぶんの防衛島。
 *
 * <p>盤面と戦闘は {@link Battlefield} そのまま。ここが足すのは
 * <b>「送られてきた敵を受け取る」「漏らしたらライフが減る」「タワー数に上限がある」</b>
 * の 3 つだけ。ウェーブもフェーズも持たない。</p>
 */
public final class Island extends Battlefield {

    /**
     * 置けるタワーの数。
     *
     * <p>壁は定額で買えるので、終盤には実質タダになる。それでも壁を敷き詰める意味が
     * 出ないように、<b>載せられるタワーの数で上限を作る</b>。
     * 余った壁は経路を伸ばす以外の使い道がなくなるので、
     * 「どこに置くか」の judgement が最後まで残る。</p>
     */
    public static final int MAX_TOWERS = 24;

    private final VersusMatch match;
    private final VersusPlayer owner;
    /** スポーンが複数あるとき、送られた敵を入口ごとに散らすためのカウンタ。 */
    private int nextSpawn;

    public Island(Instance instance, Grid grid, Palette.Theme theme,
                  int originX, int originZ, VersusMatch match, VersusPlayer owner) {
        super(instance, grid, theme, originX, originZ);
        this.match = match;
        this.owner = owner;

        arena.paintAll(grid);
        createMarkers("▼ 敵の出現地点", "◆ " + owner.name() + " の拠点");
        recomputePaths();
        owner.deck().resetForStage(match.random());
        owner.deck().drawToHandSize(match.startHand());
    }

    public VersusPlayer owner() {
        return owner;
    }

    // ================================================================ Battlefield のフック

    @Override
    public Wallet wallet() {
        return owner.wallet();
    }

    @Override
    public Modifiers modifiers() {
        return Modifiers.NONE;
    }

    @Override
    public Deck deck() {
        return owner.deck();
    }

    @Override
    public boolean buildingAllowed() {
        return owner.alive() && !match.finished();
    }

    @Override
    public boolean isUnlocked(TowerKind kind) {
        return owner.isUnlocked(kind);
    }

    @Override
    public String currencyName() {
        return "コイン";
    }

    @Override
    public String money(int amount) {
        return amount + "コイン";
    }

    @Override
    protected String towerLimitError() {
        return towers.size() >= MAX_TOWERS
                ? "タワーはこれ以上置けません（上限 " + MAX_TOWERS + " 基）"
                : null;
    }

    @Override
    protected void onEnemyKilled(EnemyInstance enemy, Pos at, int reward) {
        // コインは Battlefield が既に入れている。インカムの端数だけここで進める
        owner.onKillReward(reward);
    }

    @Override
    protected void onEnemyLeaked(EnemyInstance enemy, Pos at) {
        owner.loseLife(match.leakDamage());
        broadcast(Component.text(owner.name() + " のライフ -" + match.leakDamage()
                + "（残り " + owner.lives() + "）", NamedTextColor.RED));
        match.onLifeLost(owner);
    }

    // ================================================================ 送りの受け取り

    /** 送られてきたモンスターをこの島に 1 体湧かせる。 */
    public void receive(AttackerKind kind) {
        int spawn = grid.spawns().isEmpty() ? 0 : nextSpawn++ % grid.spawns().size();
        spawnEnemy(kind.body(), spawn, kind.hp(), kind.killReward());
    }

    /** 毎 tick。ウェーブがないので、ひたすら戦闘と描画を回すだけ。 */
    public void tick() {
        tickBattle();
        tickPathDisplay();
    }

    /**
     * カードを 1 枚配る。
     *
     * <p>対戦にはウェーブが無いので「ウェーブごとに引く」タイミングが存在しない。
     * かといって無制限に配ると壁が資源でなくなり、迷路を組む判断が消える。
     * <b>一定時間ごとに 1 枚だけ</b> 配り、手札にも上限を設けることで、
     * 「いま来た 1 枚をどこに使うか」という判断が常に残るようにしている。</p>
     */
    public boolean grantCard(int handLimit, java.util.Random random) {
        return owner.deck().drawOne(handLimit, random);
    }
}
