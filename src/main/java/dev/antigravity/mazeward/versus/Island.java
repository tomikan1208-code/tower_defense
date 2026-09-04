package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.enemy.EnemyInstance;
import dev.antigravity.mazeward.enemy.EnemyKind;
import dev.antigravity.mazeward.run.Deck;
import dev.antigravity.mazeward.run.Modifiers;
import dev.antigravity.mazeward.run.Wallet;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.world.ArenaRenderer;
import dev.antigravity.mazeward.world.Palette;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

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
    /** まとめ送りの湧かせ待ち行列。1 送りにつき 1 要素。 */
    private final java.util.List<Pending> pending = new java.util.ArrayList<>();
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
        
        // マルチプレイ時にチーム色で島の周辺を装飾
        paintTeamColorFrame(grid, owner);
    }

    public VersusPlayer owner() {
        return owner;
    }

    // ================================================================ Battlefield のフック

    @Override
    public Wallet wallet() {
        return owner.wallet();
    }

    /**
     * 対戦の補正。レリックは無いが、<b>塔を 5 段まで上げられる</b> のがシングルとの違い。
     *
     * <p>置ける数が {@link #MAX_TOWERS} で頭打ちなので、指数で伸びるコインの
     * 行き先は「上へ伸ばす」しかない。ここを 3 段のままにすると盤面がすぐ飽和し、
     * 余ったコインが相手を削る札だけに流れる。</p>
     */
    private static final Modifiers VERSUS = new Modifiers() {
        @Override
        public int maxTowerLevel() {
            return TowerKind.VERSUS_MAX_LEVEL;
        }
    };

    @Override
    public Modifiers modifiers() {
        return VERSUS;
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
        // 終焉騎だけは、通されるとライフ上限そのものを持っていく
        if (enemy.kind() == EnemyKind.REAPER) {
            owner.stealMaxLife(1);
            broadcast(Component.text(owner.name() + " のライフ上限 -1（残り "
                    + owner.lives() + "/" + owner.maxLives() + "）", NamedTextColor.DARK_PURPLE));
        }
        match.onLifeLost(owner);
    }

    /**
     * 終焉騎を倒した。出発点へ戻すだけで、罰は与えない。
     *
     * <p><b>ここで上限を奪っていた頃は、防衛に正解が存在しなかった。</b>
     * 倒しても上限が減るなら、守りを固める意味そのものが消える。
     * 上限を奪うのは「コアまで通されたとき」だけにして、
     * <b>倒し切れば無傷</b>／<b>通せば取り返しがつかない</b> という
     * タワーディフェンスとして成立する形に戻してある。
     * 経緯は {@code docs/VERSUS_ECONOMY_ja.md} を参照。</p>
     */
    @Override
    protected void onEnemyRevived(EnemyInstance enemy, Pos at) {
        broadcast(Component.text(owner.name() + " の島で終焉騎が倒れた — 出発点からもう一周してくる",
                NamedTextColor.DARK_PURPLE));
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
        receive(kind, sender, 1);
    }

    /**
     * まとめて送られてきたぶんを受け取る。
     *
     * <p><b>一度に湧かせず、{@link VersusMatch#SEND_STAGGER_TICKS} ごとに 1 体ずつ出す。</b>
     * 同座標に固めると範囲攻撃と連鎖が 1 塊に当たり、実際より柔らかく
     * （単体火力には硬く）なる。人間が送りメニューを連打しても 1 体ずつ間が空くので、
     * そちらに合わせている。</p>
     */
    public void receive(AttackerKind kind, VersusPlayer sender, int count) {
        if (count <= 0) {
            return;
        }
        spawnOne(kind, sender);
        if (count > 1) {
            pending.add(new Pending(kind, sender, count - 1,
                    VersusMatch.SEND_STAGGER_TICKS));
        }
    }

    /** 待ち行列の 1 体。 */
    private static final class Pending {
        private final AttackerKind kind;
        private final VersusPlayer sender;
        private int left;
        private int wait;

        private Pending(AttackerKind kind, VersusPlayer sender, int left, int wait) {
            this.kind = kind;
            this.sender = sender;
            this.left = left;
            this.wait = wait;
        }
    }

    private void spawnOne(AttackerKind kind, VersusPlayer sender) {
        int spawn = grid.spawns().isEmpty() ? 0 : nextSpawn++ % grid.spawns().size();
        // 耐力も撃破報酬も「何人に湧いたか」で正規化する。
        // 詳しくは VersusMatch#REFERENCE_OPPONENTS / AttackerKind#KILL_REWARD_TOTAL
        EnemyInstance enemy = spawnEnemy(kind.body(), spawn,
                kind.hp() * match.sendPowerScale(),
                kind.killReward(match.aliveCount() - 1));
        if (enemy != null) {
            enemy.source(sender);
        }
    }

    /** 待ち行列を 1 tick 進め、間隔に達したものを 1 体ずつ湧かせる。 */
    private void tickPending() {
        if (pending.isEmpty()) {
            return;
        }
        java.util.Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (--p.wait > 0) {
                continue;
            }
            spawnOne(p.kind, p.sender);
            if (--p.left <= 0) {
                it.remove();
            } else {
                p.wait = VersusMatch.SEND_STAGGER_TICKS;
            }
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
        tickPending();
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

    /**
     * 島の周辺をプレイヤーのチーム色で装飾する。
     * マルチプレイで各プレイヤーの島を視覚的に区別できるようにする。
     */
    private void paintTeamColorFrame(Grid grid, VersusPlayer owner) {
        if (owner.teamColor() == null) {
            return; // チーム色が未設定の場合はスキップ
        }

        // チーム色に対応したウール（羊毛）ブロックを取得
        Block teamBlock = getWoolBlockForColor(owner.teamColor());
        if (teamBlock == null) {
            return;
        }

        // 島の周辺（枠）に色付きブロックを配置
        int margin = 1; // 島の外枠1マスに色付きブロックを配置
        int width = grid.width();
        int height = grid.height();

        // 座標は ArenaRenderer が持つ。原点はアクセサ越しに読む（フィールドは private）
        Instance world = arena.instance();
        int originX = arena.originX();
        int originZ = arena.originZ();
        int y = ArenaRenderer.FLOOR_Y - 1;

        // 上下の辺
        for (int x = -margin; x < width + margin; x++) {
            world.setBlock(originX + x, y, originZ - margin, teamBlock);
            world.setBlock(originX + x, y, originZ + height + margin - 1, teamBlock);
        }

        // 左右の辺
        for (int z = 0; z < height; z++) {
            world.setBlock(originX - margin, y, originZ + z, teamBlock);
            world.setBlock(originX + width + margin - 1, y, originZ + z, teamBlock);
        }
    }

    /**
     * NamedTextColor に対応したウール（羊毛）ブロックを返す。
     *
     * <p>{@code NamedTextColor} は enum ではなく定数オブジェクトなので
     * {@code switch} のラベルには使えない。等値比較で引く。</p>
     */
    private Block getWoolBlockForColor(NamedTextColor color) {
        if (NamedTextColor.RED.equals(color)) {
            return Block.RED_WOOL;
        }
        if (NamedTextColor.BLUE.equals(color)) {
            return Block.BLUE_WOOL;
        }
        if (NamedTextColor.GREEN.equals(color)) {
            return Block.GREEN_WOOL;
        }
        if (NamedTextColor.YELLOW.equals(color)) {
            return Block.YELLOW_WOOL;
        }
        if (NamedTextColor.LIGHT_PURPLE.equals(color)) {
            return Block.MAGENTA_WOOL;
        }
        if (NamedTextColor.AQUA.equals(color)) {
            return Block.CYAN_WOOL;
        }
        if (NamedTextColor.GOLD.equals(color)) {
            return Block.ORANGE_WOOL;
        }
        if (NamedTextColor.DARK_AQUA.equals(color)) {
            return Block.LIGHT_BLUE_WOOL;
        }
        return Block.WHITE_WOOL;
    }
}
