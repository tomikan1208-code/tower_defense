package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.enemy.EnemyKind;
import dev.antigravity.mazeward.versus.AttackerKind;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.scoreboard.Sidebar;

/** 対戦モードのホットバー・サイドバー・送りメニュー。 */
public final class VersusHud {

    /** 送りメニューを開くスロット。シングルの「ウェーブ開始」の位置を置き換える。 */
    public static final int SLOT_SEND = 8;

    private VersusHud() {
    }

    // ---------------------------------------------------------------- ホットバー

    public static void applyHotbar(PlayerSession session, VersusPlayer self, VersusMatch match) {
        Player player = session.player();

        Hud.applyPalette(session);

        player.getInventory().setItemStack(PlayerSession.SLOT_INSPECT, Hud.inspectItem());

        player.getInventory().setItemStack(SLOT_SEND, match.preparing()
                ? Hud.item(Material.CLOCK,
                        Component.text("準備中 — あと " + match.prepSecondsLeft() + " 秒",
                                NamedTextColor.YELLOW),
                        Component.text("迷路とタワーを組む時間", NamedTextColor.GRAY))
                : Hud.item(Material.NETHER_STAR,
                        Component.text("敵を送る", NamedTextColor.RED),
                        Component.text("インカムが増えるのは送ったときだけ", NamedTextColor.GRAY),
                        Component.text("右クリックで開く", NamedTextColor.DARK_GRAY)));
    }

    // ---------------------------------------------------------------- サイドバー

    /**
     * 対戦中のサイドバー。
     *
     * @param aiName 相手の AI の名前（モデル名込み）。AI が居ない試合なら null。
     *               <b>誰と戦っているのかは資源と同じくらい常に見えていてほしい</b>
     */
    public static void updateSidebar(PlayerSession session, VersusPlayer self,
                                     VersusMatch match, String aiName) {
        updateSidebar(session, self, match);
        if (session.sidebar() != null && aiName != null) {
            set(session.sidebar(), "l8", Component.text(aiName, NamedTextColor.DARK_AQUA));
        }
    }

    public static void updateSidebar(PlayerSession session, VersusPlayer self, VersusMatch match) {
        Sidebar sidebar = session.sidebar();
        if (sidebar == null) {
            return;
        }
        Island island = self.island();
        int towerCount = island == null ? 0 : island.towers().size();

        set(sidebar, "l1", Component.text("ライフ " + self.lives() + "/" + self.maxLives(),
                self.lives() > 6 ? NamedTextColor.GREEN : NamedTextColor.RED));
        set(sidebar, "l2", Component.text("コイン " + self.coins(), NamedTextColor.GOLD));
        // 収入間隔は人数で変わるので、秒数も試合から取って表示する
        set(sidebar, "l3", Component.text(
                "インカム " + self.income() + " /" + match.incomeSeconds() + "秒", NamedTextColor.YELLOW));
        set(sidebar, "l4", Component.text("ストック " + self.stock() + "/" + VersusPlayer.MAX_STOCK,
                NamedTextColor.AQUA));
        set(sidebar, "l5", Component.text("タワー " + towerCount + "/" + Island.MAX_TOWERS,
                towerCount >= Island.MAX_TOWERS ? NamedTextColor.RED : NamedTextColor.WHITE));
        set(sidebar, "l6", match.preparing()
                ? Component.text("準備 あと " + match.prepSecondsLeft() + "秒", NamedTextColor.LIGHT_PURPLE)
                : Component.text("経過 " + (match.elapsedTicks() / 1200) + "分", NamedTextColor.GRAY));

        // 他プレイヤーの残ライフ。誰が落ちそうかは送りの判断材料になる
        StringBuilder others = new StringBuilder();
        for (VersusPlayer other : match.participants()) {
            if (other == self) {
                continue;
            }
            if (!others.isEmpty()) {
                others.append(" ");
            }
            others.append(other.alive() ? other.name() + ":" + other.lives() : other.name() + ":✖");
        }
        set(sidebar, "l7", Component.text(others.toString(), NamedTextColor.GRAY));
        set(sidebar, "l8", Component.empty());
    }

    private static void set(Sidebar sidebar, String id, Component content) {
        sidebar.updateLineContent(id, content);
    }

    // ---------------------------------------------------------------- 送りメニュー

    /**
     * 送るモンスターを選ぶ画面。
     *
     * <p>解禁に必要なインカムを常に表示する。インカムがそのまま技術ツリーなので、
     * 「あといくら伸ばせば何が撃てるか」が見えていないと投資の判断ができない。</p>
     */
    public static void openSendMenu(PlayerSession session, VersusPlayer self, VersusMatch match) {
        List<AttackerKind> all = List.of(AttackerKind.values());

        Menus.open(session, new Menus.Screen() {
            @Override
            public Component title() {
                return Component.text("送る  コイン " + self.coins()
                        + " / インカム " + self.income() + " / ストック " + self.stock());
            }

            @Override
            public InventoryType type() {
                return InventoryType.CHEST_5_ROW;
            }

            @Override
            public void render(PlayerSession s, Inventory inventory) {
                for (int i = 0; i < all.size(); i++) {
                    inventory.setItemStack(menuSlot(i), sendIcon(all.get(i), self, match));
                }
                inventory.setItemStack(CLOSE_SLOT, Hud.item(Material.BARRIER,
                        Component.text("閉じる", NamedTextColor.RED)));
            }

            @Override
            public void click(PlayerSession s, Inventory inventory, int slot) {
                if (slot == CLOSE_SLOT) {
                    s.clearMenu();
                    s.player().closeInventory();
                    return;
                }
                int index = menuIndex(slot);
                if (index < 0 || index >= all.size()) {
                    return;
                }
                String message = match.send(self, all.get(index));
                s.player().sendActionBar(Component.text(message, NamedTextColor.YELLOW));
                render(s, inventory);
            }
        });
    }

    /**
     * 送りメニューの並び。1 段 7 枠 x 3 段 = 21 枠。
     *
     * <p>梯子が 18 段（インカム 16 + 削り切る用 2）あるので、
     * 5 段チェストの内側 3 段を使う。上の段ほど安く、下へ行くほど重い。</p>
     */
    private static final int ROW_WIDTH = 7;
    private static final int ROWS = 3;
    private static final int[] ROW_START = {10, 19, 28};
    private static final int CLOSE_SLOT = 40;

    private static int menuSlot(int index) {
        return ROW_START[index / ROW_WIDTH] + (index % ROW_WIDTH);
    }

    private static int menuIndex(int slot) {
        for (int row = 0; row < ROWS; row++) {
            int start = ROW_START[row];
            if (slot >= start && slot < start + ROW_WIDTH) {
                return row * ROW_WIDTH + (slot - start);
            }
        }
        return -1;
    }

    private static ItemStack sendIcon(AttackerKind kind, VersusPlayer self, VersusMatch match) {
        boolean unlocked = self.income() >= kind.unlockIncome();
        EnemyKind body = kind.body();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(kind.description(), NamedTextColor.GRAY));
        lore.add(Component.empty());

        // 耐力と速度は必ず出す。何秒で通路を抜けるのかが読めないと、
        // 「どの塔で受けるか」も「何を送るか」も勘で決めることになる
        lore.add(Component.text(String.format("耐力 %.0f  速度 %.1f ブロック/秒  装甲 %.0f",
                kind.hp(), body.speedPerSecond(), body.armor()), NamedTextColor.AQUA));
        if (body.flying()) {
            lore.add(Component.text("飛行：迷路を無視して直線で飛ぶ", NamedTextColor.AQUA));
        }
        if (body.slowResist() > 0) {
            lore.add(Component.text(String.format("減速耐性 %.0f%%", body.slowResist() * 100),
                    NamedTextColor.AQUA));
        }
        String ability = body.abilitySummary();
        if (!ability.isEmpty()) {
            lore.add(Component.text("能力：" + ability, NamedTextColor.LIGHT_PURPLE));
        }
        lore.add(Component.empty());
        lore.add(Component.text("コイン " + kind.cost() + "  ストック " + kind.stockCost(),
                self.coins() >= kind.cost() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (kind.incomeGain() > 0) {
            // 効率が見えないと「安いのを回すか、上を撃つか」を勘で決めることになる。
            // 上ほど比率が落ちる梯子なので、比率こそが一番大事な情報
            lore.add(Component.text(String.format("インカム +%d（コストの %.1f%%）",
                    kind.incomeGain(), 100.0 * kind.incomeGain() / kind.cost()),
                    NamedTextColor.YELLOW));
        } else {
            lore.add(Component.text("インカムは増えない（削り切る用）", NamedTextColor.DARK_RED));
        }
        lore.add(Component.text("相手のコアに届くと自分のライフ +1（上限まで）",
                NamedTextColor.DARK_GREEN));
        if (kind.stealsMaxLife()) {
            lore.add(Component.text("コアまで通すと相手のライフ上限 -1（倒されたら何も起きない）",
                    NamedTextColor.DARK_PURPLE));
        }
        if (!unlocked) {
            lore.add(Component.empty());
            lore.add(Component.text("インカム " + kind.unlockIncome() + " で解禁",
                    NamedTextColor.DARK_GRAY));
        }
        return Hud.item(unlocked ? kind.icon() : Material.GRAY_DYE,
                Component.text(kind.displayName(), unlocked ? NamedTextColor.RED : NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                lore);
    }
}
