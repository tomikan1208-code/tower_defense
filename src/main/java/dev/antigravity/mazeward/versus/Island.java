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
        rewardSender(enemy);
        if (enemy.kind().boss()) {
            // 災厄は消えずに戻る。送られた側は「倒すまで終わらない」ことを知らされる
            broadcast(Component.text("災厄は出発点へ戻った。倒し切るまで終わらない",
                    NamedTextColor.DARK_RED));
        }
        match.onLifeLost(owner);
    }

    /**
     * 終焉騎が出発点へ戻った。ライフ上限を 1 奪われる。
     *
     * <p>これだけが取り返しのつかない削り方なので、全員に見えるように告知する。
     * 「誰がもう後がないか」は送り先を決める最大の材料になる。</p>
     */
    @Override
    protected void onEnemyRevived(EnemyInstance enemy, Pos at) {
        owner.stealMaxLife(1);
        broadcast(Component.text(owner.name() + " のライフ上限 -1（残り "
                + owner.lives() + "/" + owner.maxLives() + "）", NamedTextColor.DARK_PURPLE));
        match.onLifeLost(owner);
    }

    /**
     * 通した送り主にライフを 1 返す。上限は超えない。
     *
     * <p>送りが「相手を削るだけ」だと、削られた側は守りを固める以外に戻す手が無い。
     * 通ったぶんだけ自分も戻せるようにすることで、
     * <b>攻めることが立て直しにもなる</b>。上限を超えないので、
     * 一度も漏らしていない側が送りだけで太ることはない。</p>
     */
    private void rewardSender(EnemyInstance enemy) {
        if (!(enemy.source() instanceof VersusPlayer sender) || sender == owner || !sender.alive()) {
            return;
        }
        if (!sender.gainLife(1)) {
            return;
        }
        match.announce(Component.text(sender.name() + " の送りがコアに届いた  ライフ +1（"
                + sender.lives() + "/" + sender.maxLives() + "）", NamedTextColor.GREEN));
    }

    // ================================================================ 送りの受け取り

    /**
     * 送られてきたモンスターをこの島に 1 体湧かせる。
     *
     * <p>送り主を敵に持たせておく。コアまで通ったときに
     * <b>送った側のライフが 1 戻る</b> ので、誰の送りだったかが分からないと精算できない。</p>
     */
    public void receive(AttackerKind kind, VersusPlayer sender) {
        int spawn = grid.spawns().isEmpty() ? 0 : nextSpawn++ % grid.spawns().size();
        EnemyInstance enemy = spawnEnemy(kind.body(), spawn, kind.hp(), kind.killReward());
        if (enemy != null) {
            enemy.source(sender);
        }
    }

    /** 毎 tick。ウェーブがないので、ひたすら戦闘と描画を回すだけ。 */
    public void tick() {
        tick(true);
    }

    /**
     * @param render 経路の表示を更新するか。倍速の途中経過では描かない
     */
    public void tick(boolean render) {
        tickBattle();
        if (render) {
            tickPathDisplay();
        }
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
