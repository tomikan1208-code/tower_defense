package dev.antigravity.mazeward.ui;

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

        player.getInventory().setItemStack(PlayerSession.SLOT_INSPECT,
                Hud.item(Material.BLAZE_ROD, Component.text("強化 / 売却", NamedTextColor.WHITE),
                        Component.text("タワーを狙って右クリック", NamedTextColor.DARK_GRAY)));

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
        set(sidebar, "l3", Component.text("インカム " + self.income() + " /10秒", NamedTextColor.YELLOW));
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
                return InventoryType.CHEST_4_ROW;
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

    /** 送りメニューの並び。1 段 7 枠、2 段で 14 枠まで。 */
    private static final int ROW_WIDTH = 7;
    private static final int CLOSE_SLOT = 31;

    private static int menuSlot(int index) {
        return index < ROW_WIDTH ? 10 + index : 19 + (index - ROW_WIDTH);
    }

    private static int menuIndex(int slot) {
        if (slot >= 10 && slot <= 16) {
            return slot - 10;
        }
        if (slot >= 19 && slot <= 25) {
            return ROW_WIDTH + (slot - 19);
        }
        return -1;
    }

    private static ItemStack sendIcon(AttackerKind kind, VersusPlayer self, VersusMatch match) {
        boolean unlocked = self.income() >= kind.unlockIncome();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(kind.description(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("コイン " + kind.cost() + "  ストック " + kind.stockCost(),
                self.coins() >= kind.cost() ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(kind.incomeGain() > 0
                ? Component.text("インカム +" + kind.incomeGain(), NamedTextColor.YELLOW)
                : Component.text("インカムは増えない（削り切る用）", NamedTextColor.DARK_RED));
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
