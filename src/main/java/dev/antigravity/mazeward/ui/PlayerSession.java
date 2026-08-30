package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.PathFinder;
import dev.antigravity.mazeward.core.PathResult;
import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.stage.Stage;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.world.ArenaRenderer;
import dev.antigravity.mazeward.world.Overlay;
import dev.antigravity.mazeward.world.Palette;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.scoreboard.Sidebar;

/**
 * プレイヤー 1 人分の操作状態と、その人にだけ見えるプレビュー描画。
 *
 * <p>このクラスが「今どのカードを持ち、どの向きで、どのセルを狙っているか」を保持し、
 * それに応じて黄色いゴーストと赤い予測経路を毎 tick 更新する。</p>
 */
public final class PlayerSession {

    /**
     * ホットバー 0〜5 は <b>切り替え式のパレット</b>。
     *
     * <p>障害物モードなら手札のカードが、タワーモードならタワーが並ぶ。
     * 以前はチェストを開いてタワーを選ぶ形だったが、
     * 置くたびに画面を開き直すことになり、盤面から目を離す時間が長すぎた。
     * 持ち替えるだけで選べるほうが、迷路を見ながら判断できる。</p>
     */
    public static final int PALETTE_SLOTS = 6;

    /** 障害物 ⇄ タワーの切り替え。 */
    public static final int SLOT_TOGGLE = 6;

    public static final int SLOT_INSPECT = 7;
    public static final int SLOT_START = 8;

    /** ホットバー 0〜5 に何を並べるか。 */
    public enum HandMode {
        OBSTACLE,
        TOWER
    }

    /** プレビューを描き直す間隔（tick）。パーティクル量を抑えるため毎 tick にはしない。 */
    private static final int DRAW_INTERVAL = 3;

    public enum Mode {
        NONE,
        CARD,
        TOWER
    }

    private final Player player;

    /**
     * いま操作している戦場。シングルのステージでも対戦の島でも同じ扱いになる。
     * ゴーストと経路プレビューはどちらでも同じものを使いたいので、基底型で持つ。
     */
    private Battlefield field;
    private Overlay.GhostView ghost;
    private Sidebar sidebar;

    private HandMode handMode = HandMode.OBSTACLE;
    private Mode mode = Mode.NONE;
    private int cardIndex = -1;
    private TowerKind towerKind;
    private Rot rot = Rot.R0;
    private Vec2i cursor;

    private Inventory openMenu;
    private MenuHandler menuHandler;
    private boolean menuMandatory;

    /**
     * 直前に成立した設置。対戦のミラーボットが「人間が何をしたか」を読むために使う。
     * confirm() の中で原点を計算しているので、外から再計算せずに済むようここに残す。
     */
    public record Placement(Mode mode, dev.antigravity.mazeward.core.Shape shape,
                            TowerKind tower, Vec2i origin, Rot rot) {
    }

    private Placement lastPlacement;
    private long previewKey = Long.MIN_VALUE;
    private Battlefield.PlacementPreview cachedPreview;

    /** GUI クリックの受け口。 */
    public interface MenuHandler {
        void onClick(PlayerSession session, int slot);
    }

    public PlayerSession(Player player) {
        this.player = player;
    }

    public Player player() {
        return player;
    }

    public Battlefield field() {
        return field;
    }

    /** キャンペーンのステージ。対戦中なら null。 */
    public Stage stage() {
        return field instanceof Stage campaign ? campaign : null;
    }

    public Mode mode() {
        return mode;
    }

    public HandMode handMode() {
        return handMode;
    }

    /** 障害物とタワーを切り替える。選択は解除して、持ち替え直してもらう。 */
    public void toggleHandMode() {
        handMode = handMode == HandMode.OBSTACLE ? HandMode.TOWER : HandMode.OBSTACLE;
        clearSelection();
    }

    public Rot rot() {
        return rot;
    }

    public Vec2i cursor() {
        return cursor;
    }

    public TowerKind selectedTower() {
        return towerKind;
    }

    public int cardIndex() {
        return cardIndex;
    }

    public Sidebar sidebar() {
        return sidebar;
    }

    public void setSidebar(Sidebar sidebar) {
        this.sidebar = sidebar;
    }

    // ---------------------------------------------------------------- ステージ出入り

    public void enterStage(Battlefield field) {
        leaveStage();
        this.field = field;
        this.ghost = new Overlay.GhostView(player, field.instance());
        clearSelection();
    }

    public void leaveStage() {
        if (ghost != null) {
            ghost.dispose();
            ghost = null;
        }
        field = null;
        clearSelection();
    }

    public void clearSelection() {
        mode = Mode.NONE;
        cardIndex = -1;
        towerKind = null;
        rot = Rot.R0;
        previewKey = Long.MIN_VALUE;
        cachedPreview = null;
        if (ghost != null) {
            ghost.hide();
        }
    }

    public void selectCard(int index) {
        mode = Mode.CARD;
        cardIndex = index;
        towerKind = null;
        rot = Rot.R0;
        invalidatePreview();
    }

    public void selectTower(TowerKind kind) {
        mode = Mode.TOWER;
        towerKind = kind;
        cardIndex = -1;
        rot = Rot.R0;
        invalidatePreview();
    }

    public void rotate() {
        rot = rot.next();
        invalidatePreview();
    }

    private void invalidatePreview() {
        previewKey = Long.MIN_VALUE;
        cachedPreview = null;
    }

    // ---------------------------------------------------------------- メニュー

    public void setMenu(Inventory inventory, MenuHandler handler, boolean mandatory) {
        this.openMenu = inventory;
        this.menuHandler = handler;
        this.menuMandatory = mandatory;
    }

    public void clearMenu() {
        this.openMenu = null;
        this.menuHandler = null;
        this.menuMandatory = false;
    }

    /**
     * 閉じてしまうと進行が止まるメニュー（報酬・イベント・商店など）か。
     *
     * <p>これらを Esc で閉じられると、選択待ちのままランが二度と進まなくなる。
     * 閉じられたら開き直すために区別している。</p>
     */
    public boolean isMenuMandatory() {
        return menuMandatory && openMenu != null;
    }

    public Inventory openMenuInventory() {
        return openMenu;
    }

    public boolean handleMenuClick(Inventory inventory, int slot) {
        if (openMenu == null || openMenu != inventory || menuHandler == null) {
            return false;
        }
        menuHandler.onClick(this, slot);
        return true;
    }

    // ---------------------------------------------------------------- カーソル

    /**
     * 視線と水平面の交点からセルを求める。
     * ブロックへのレイキャストではなく平面交点にしているので、
     * 上空から遠く離れたセルも正確に狙える（Minecraft の到達距離に縛られない）。
     */
    public Vec2i raycastCell(double planeY) {
        if (field == null) {
            return null;
        }
        Pos eye = player.getPosition().add(0, player.getEyeHeight(), 0);
        Vec direction = player.getPosition().direction();
        if (direction.y() > -0.02) {
            return null;
        }
        double t = (planeY - eye.y()) / direction.y();
        if (t < 0 || t > 256) {
            return null;
        }
        double x = eye.x() + direction.x() * t;
        double z = eye.z() + direction.z() * t;
        // ワールド座標 → セル座標の変換は戦場側が持つ（島ごとに原点が違うため）
        return field.arena().toCell(x, z);
    }

    /** いま狙っているセル（モードに応じて床 or 壁の上を見る）。 */
    public Vec2i aimCell() {
        if (mode == Mode.TOWER) {
            Vec2i onWall = raycastCell(ArenaRenderer.WALL_TOP_Y);
            if (onWall != null && field != null && field.grid().inBounds(onWall)
                    && field.grid().towerBase(onWall)) {
                return onWall;
            }
        }
        return raycastCell(ArenaRenderer.SURFACE_Y);
    }

    // ---------------------------------------------------------------- 毎 tick 描画

    public void tick(int globalTick) {
        if (field == null || ghost == null) {
            return;
        }
        if (!field.buildingAllowed() || mode == Mode.NONE) {
            ghost.hide();
            cursor = null;
            return;
        }

        cursor = aimCell();
        if (cursor == null || !field.grid().inBounds(cursor)) {
            ghost.hide();
            return;
        }

        boolean draw = globalTick % DRAW_INTERVAL == 0;
        if (mode == Mode.CARD) {
            tickCardPreview(draw);
        } else {
            tickTowerPreview(draw);
        }
    }

    private void tickCardPreview(boolean draw) {
        BlockCard card = field.deck().peek(cardIndex);
        if (card == null) {
            clearSelection();
            return;
        }
        Shape shape = card.shape();
        Vec2i origin = field.originFor(shape, cursor, rot);

        long key = key(origin, rot.ordinal(), cardIndex, field.gridVersion());
        if (key != previewKey) {
            previewKey = key;
            cachedPreview = field.preview(shape, origin, rot);
        }
        Battlefield.PlacementPreview preview = cachedPreview;
        boolean ok = preview.ok();

        List<Vec2i> cells = shape.cellsAt(origin, rot);
        Block block = ok ? Palette.GHOST_VALID : Palette.GHOST_INVALID;

        // 置ける間は何も出さない。形も角度も距離もゴーストを見れば分かるので、
        // 文字は「置けない理由」だけに絞ったほうが読む量が減って速い。
        Component label = ok ? null
                : Component.text("✖ " + preview.error(), NamedTextColor.RED);

        ghost.show(field.toWorldPath(cells), block, ArenaRenderer.SURFACE_Y, 1.0,
                label, field.arena().centerX(cursor), field.arena().centerZ(cursor),
                ArenaRenderer.SURFACE_Y + 2.6);

        if (draw && ok) {
            drawChangedPathSections(preview);
        }
    }

    /**
     * 赤い予測経路。<b>変わる部分だけ</b> を描く。
     *
     * <p>経路全体を赤で塗ると、青い現在経路と重なって「どこが変わったのか」が
     * かえって読めなくなる。前後の共通部分を落として差分の区間だけを出す。
     * 経路がまったく変わらない置き方なら赤は 1 本も出ない。</p>
     */
    private void drawChangedPathSections(Battlefield.PlacementPreview preview) {
        List<PathResult> current = field.paths();
        List<List<Vec2i>> predicted = preview.previewPaths();
        for (int i = 0; i < predicted.size(); i++) {
            List<Vec2i> before = i < current.size() ? current.get(i).waypoints() : List.of();
            List<Vec2i> changed = PathFinder.divergentSection(before, predicted.get(i));
            if (changed.isEmpty()) {
                continue;
            }
            Overlay.drawPath(List.of(player), field.toWorldPath(changed),
                    Palette.PATH_PREVIEW, Overlay.Y_PREVIEW, 0.85f);
        }
    }

    private void tickTowerPreview(boolean draw) {
        if (towerKind == null) {
            clearSelection();
            return;
        }
        Shape shape = towerKind.shape();
        Vec2i origin = field.originFor(shape, cursor, rot);
        String error = field.towerPlacementError(towerKind, origin, rot);
        boolean ok = error == null;

        List<Vec2i> cells = shape.cellsAt(origin, rot);
        Block block = ok ? Palette.GHOST_TOWER : Palette.GHOST_INVALID;

        Component label = ok
                ? Component.text(towerKind.displayName() + "  " + towerKind.baseCost() + "G",
                        towerKind.element().color())
                : Component.text("✖ " + error, NamedTextColor.RED);

        ghost.show(field.toWorldPath(cells), block, ArenaRenderer.WALL_TOP_Y, 1.0,
                label, field.arena().centerX(cursor), field.arena().centerZ(cursor),
                ArenaRenderer.WALL_TOP_Y + 2.0);

        if (draw) {
            double range = towerKind.statsAt(0).range() + field.modifiers().rangeBonus();
            double cx = 0;
            double cz = 0;
            for (Vec2i cell : cells) {
                cx += field.arena().centerX(cell);
                cz += field.arena().centerZ(cell);
            }
            Overlay.drawRangeRing(List.of(player), cx / cells.size(), cz / cells.size(), range);
        }
    }

    // ---------------------------------------------------------------- 確定操作

    /**
     * 右クリックで確定。結果メッセージを返す。
     *
     * <p>狙っているセルはここで <b>その場で計算し直す</b>。
     * tick でキャッシュした値に頼ると、まだ 1 度も tick を通っていない状況
     * （ステージに入った直後など）で「狙っている場所がありません」になってしまう。</p>
     */
    public Battlefield.Outcome confirm() {
        if (field == null) {
            return Battlefield.Outcome.fail("ステージにいません");
        }
        if (mode == Mode.NONE) {
            return Battlefield.Outcome.fail("ホットバー 1〜5 でカードを選ぶか、7 番のタワー一覧から選んでください");
        }

        Vec2i target = aimCell();
        if (target == null) {
            return Battlefield.Outcome.fail("床が見えていません。もう少し下を向いてから右クリックしてください");
        }
        if (!field.grid().inBounds(target)) {
            return Battlefield.Outcome.fail("アリーナの外を狙っています");
        }
        cursor = target;

        if (mode == Mode.CARD) {
            BlockCard card = field.deck().peek(cardIndex);
            if (card == null) {
                return Battlefield.Outcome.fail("手札が空です");
            }
            Vec2i origin = field.originFor(card.shape(), target, rot);
            Battlefield.Outcome outcome = field.placeCard(cardIndex, origin, rot);
            if (outcome.success()) {
                lastPlacement = new Placement(Mode.CARD, card.shape(), null, origin, rot);
                invalidatePreview();
            }
            return outcome;
        }

        Vec2i origin = field.originFor(towerKind.shape(), target, rot);
        Battlefield.Outcome outcome = field.placeTower(towerKind, origin, rot);
        if (outcome.success()) {
            lastPlacement = new Placement(Mode.TOWER, towerKind.shape(), towerKind, origin, rot);
            invalidatePreview();
        }
        return outcome;
    }

    /**
     * 持っているホットバースロットと手札を突き合わせて選択を直す。
     *
     * <p>ステージに入った直後やウェーブが切り替わって手札を引き直したときに、
     * 「何も選ばれていないので右クリックが効かない」状態にならないようにする。
     * ホットバーのスロットを正とするので、スクロールするだけでカードを選べる。</p>
     */
    /**
     * 持っているスロットに合わせて選択を直す。
     * パレットのスロットなら、いまのモードに応じてカードかタワーを選ぶ。
     */
    public void syncSelectionWithHotbar() {
        if (field == null || !field.buildingAllowed()) {
            return;
        }
        int slot = player.getHeldSlot();
        if (slot >= PALETTE_SLOTS) {
            clearSelection();
            return;
        }

        if (handMode == HandMode.TOWER) {
            var towers = field.availableTowers();
            if (slot < towers.size()) {
                if (mode != Mode.TOWER || towerKind != towers.get(slot)) {
                    selectTower(towers.get(slot));
                }
            } else {
                clearSelection();
            }
            return;
        }

        int handSize = field.deck().hand().size();
        if (slot < handSize) {
            if (mode != Mode.CARD || cardIndex != slot) {
                selectCard(slot);
            }
        } else {
            clearSelection();
        }
    }

    /** カードを 1 枚使ったあと、手札の詰め直しに合わせて選択を維持する。 */
    public void reselectAfterPlay() {
        if (field == null || handMode != HandMode.OBSTACLE) {
            return;
        }
        int handSize = field.deck().hand().size();
        if (handSize == 0) {
            clearSelection();
            return;
        }
        int next = Math.min(cardIndex < 0 ? 0 : cardIndex, handSize - 1);
        selectCard(next);
        player.setHeldItemSlot((byte) next);
    }

    /** 直前に成立した設置を取り出す（1 度読むと消える）。 */
    public Placement takeLastPlacement() {
        Placement placement = lastPlacement;
        lastPlacement = null;
        return placement;
    }

    /** 検査モードで狙ったセルのタワー。 */
    public Vec2i inspectCell() {
        Vec2i onWall = raycastCell(ArenaRenderer.WALL_TOP_Y);
        if (onWall != null && field != null && field.grid().inBounds(onWall)
                && field.towerAt(onWall) != null) {
            return onWall;
        }
        return raycastCell(ArenaRenderer.SURFACE_Y);
    }

    public Grid grid() {
        return field == null ? null : field.grid();
    }

    private static long key(Vec2i cell, int rotation, int index, int version) {
        long value = cell.x() * 1_000L + cell.z();
        value = value * 8 + rotation;
        value = value * 64 + (index + 1);
        value = value * 100_003L + version;
        return value;
    }
}
