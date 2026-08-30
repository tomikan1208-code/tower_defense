package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.stage.Stage;
import dev.antigravity.mazeward.tower.TowerInstance;
import dev.antigravity.mazeward.tower.TowerKind;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/**
 * GUI 画面の共通土台。
 *
 * <p>画面の中身は {@link Screen} を実装して差し込む。
 * 報酬・ショップ・祭壇のようにゲーム進行に依存するものは Mazeward 側で組み立て、
 * ここは「開く・描く・クリックを渡す」だけに徹する。</p>
 */
public final class Menus {

    /** 1 つの GUI 画面。 */
    public interface Screen {
        Component title();

        InventoryType type();

        void render(PlayerSession session, Inventory inventory);

        void click(PlayerSession session, Inventory inventory, int slot);
    }

    private static final ItemStack FILLER = ItemStack.builder(Material.GRAY_STAINED_GLASS_PANE)
            .customName(Component.text(" "))
            .hideExtraTooltip()
            .build();

    private Menus() {
    }

    public static void open(PlayerSession session, Screen screen) {
        open(session, screen, false);
    }

    /**
     * @param mandatory true なら、閉じられても開き直す。
     *                  報酬やイベントのように「選ばないと進行が止まる」画面で使う。
     */
    public static void open(PlayerSession session, Screen screen, boolean mandatory) {
        Inventory inventory = new Inventory(screen.type(), screen.title());
        fill(inventory);
        screen.render(session, inventory);
        session.setMenu(inventory, (s, slot) -> screen.click(s, inventory, slot), mandatory);
        session.player().openInventory(inventory);
    }

    public static void fill(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItemStack(slot, FILLER);
        }
    }

    /** クリックで 1 つ選ぶだけの画面（報酬・祭壇）。 */
    public record Option(ItemStack icon, Runnable action) {
    }

    public static void openChoice(PlayerSession session, Component title, List<Option> options) {
        openChoice(session, title, options, true);
    }

    public static void openChoice(PlayerSession session, Component title,
                                  List<Option> options, boolean mandatory) {
        open(session, new Screen() {
            @Override
            public Component title() {
                return title;
            }

            @Override
            public InventoryType type() {
                return InventoryType.CHEST_3_ROW;
            }

            @Override
            public void render(PlayerSession s, Inventory inventory) {
                int[] slots = slotsFor(options.size());
                for (int i = 0; i < options.size() && i < slots.length; i++) {
                    inventory.setItemStack(slots[i], options.get(i).icon());
                }
            }

            @Override
            public void click(PlayerSession s, Inventory inventory, int slot) {
                int[] slots = slotsFor(options.size());
                for (int i = 0; i < options.size() && i < slots.length; i++) {
                    if (slots[i] == slot) {
                        s.clearMenu();
                        s.player().closeInventory();
                        options.get(i).action().run();
                        return;
                    }
                }
            }
        }, mandatory);
    }

    private static int[] slotsFor(int count) {
        return switch (count) {
            case 1 -> new int[] {13};
            case 2 -> new int[] {12, 14};
            case 3 -> new int[] {11, 13, 15};
            case 4 -> new int[] {10, 12, 14, 16};
            default -> new int[] {10, 11, 12, 13, 14, 15, 16};
        };
    }

    // ---------------------------------------------------------------- デッキから 1 枚選ぶ

    /**
     * デッキのカードを一覧して 1 枚選ばせる（ルーン付与用）。
     * すでにルーンが付いているカードは選べない。
     */
    public static void openDeckPicker(PlayerSession session, Component title,
                                      List<BlockCard> library, java.util.function.IntConsumer onPick) {
        open(session, new Screen() {
            @Override
            public Component title() {
                return title;
            }

            @Override
            public InventoryType type() {
                return InventoryType.CHEST_5_ROW;
            }

            @Override
            public void render(PlayerSession s, Inventory inventory) {
                int shown = Math.min(library.size(), inventory.getSize());
                for (int i = 0; i < shown; i++) {
                    inventory.setItemStack(i, Hud.deckCardIcon(library.get(i), i, true));
                }
            }

            @Override
            public void click(PlayerSession s, Inventory inventory, int slot) {
                if (slot < 0 || slot >= library.size()) {
                    return;
                }
                if (library.get(slot).hasRune()) {
                    s.player().sendActionBar(Component.text(
                            "そのカードにはすでにルーンが付いています", NamedTextColor.RED));
                    return;
                }
                s.clearMenu();
                s.player().closeInventory();
                onPick.accept(slot);
            }
        }, true);
    }

    // ---------------------------------------------------------------- タワー一覧

    public static void openTowerShop(PlayerSession session) {
        Stage stage = session.stage();
        if (stage == null) {
            return;
        }
        List<TowerKind> unlocked = new ArrayList<>();
        for (TowerKind kind : TowerKind.values()) {
            if (stage.run().isUnlocked(kind)) {
                unlocked.add(kind);
            }
        }

        open(session, new Screen() {
            @Override
            public Component title() {
                return Component.text("タワーを選ぶ  所持 " + stage.run().gold() + "G");
            }

            @Override
            public InventoryType type() {
                return InventoryType.CHEST_3_ROW;
            }

            @Override
            public void render(PlayerSession s, Inventory inventory) {
                for (int i = 0; i < unlocked.size(); i++) {
                    inventory.setItemStack(10 + i, towerIcon(unlocked.get(i), stage.run().gold()));
                }
                for (TowerKind kind : TowerKind.values()) {
                    if (!stage.run().isUnlocked(kind)) {
                        continue;
                    }
                }
                inventory.setItemStack(22, Hud.item(Material.BARRIER,
                        Component.text("閉じる", NamedTextColor.RED)));
            }

            @Override
            public void click(PlayerSession s, Inventory inventory, int slot) {
                if (slot == 22) {
                    s.clearMenu();
                    s.player().closeInventory();
                    return;
                }
                int index = slot - 10;
                if (index < 0 || index >= unlocked.size()) {
                    return;
                }
                TowerKind kind = unlocked.get(index);
                s.clearMenu();
                s.player().closeInventory();
                s.selectTower(kind);
                s.player().setHeldItemSlot((byte) PlayerSession.SLOT_SELECTED_TOWER);
                Hud.applyStageHotbar(s);
                s.player().sendActionBar(Component.text(
                        kind.displayName() + " を選択  壁の上を狙って右クリック", kind.element().color()));
            }
        });
    }

    private static ItemStack towerIcon(TowerKind kind, int gold) {
        TowerKind.Stats stats = kind.statsAt(0);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(kind.description(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        for (String line : kind.shape().ascii(dev.antigravity.mazeward.core.Rot.R0)) {
            lore.add(Component.text(line, NamedTextColor.DARK_GRAY));
        }
        lore.add(Component.empty());
        lore.add(Component.text("属性 " + kind.element().displayName()
                + " / " + kind.style().displayName(), kind.element().color()));
        lore.add(Component.text(String.format("攻撃力 %.0f  射程 %.1f  間隔 %dt",
                stats.damage(), stats.range(), stats.cooldown()), NamedTextColor.WHITE));
        if (stats.slowFactor() > 0) {
            lore.add(Component.text(String.format("減速 %.0f%%", stats.slowFactor() * 100), NamedTextColor.AQUA));
        }
        if (stats.burnDps() > 0) {
            lore.add(Component.text(String.format("燃焼 %.0f/秒", stats.burnDps()), NamedTextColor.GOLD));
        }
        if (kind.style() == dev.antigravity.mazeward.tower.AttackStyle.SPLASH) {
            lore.add(Component.text(String.format("範囲半径 %.1f", stats.splashRadius()), NamedTextColor.GOLD));
        }
        if (stats.chainTargets() > 0) {
            lore.add(Component.text("対象数 " + stats.chainTargets(), NamedTextColor.YELLOW));
        }
        lore.add(Component.empty());
        lore.add(Component.text(kind.baseCost() + "G",
                gold >= kind.baseCost() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return Hud.item(kind.icon(), Component.text(kind.displayName(), kind.element().color()), lore);
    }

    // ---------------------------------------------------------------- タワー詳細

    public static void openTowerDetail(PlayerSession session, Vec2i cell) {
        Stage stage = session.stage();
        if (stage == null) {
            return;
        }
        TowerInstance tower = stage.towerAt(cell);
        if (tower == null) {
            session.player().sendActionBar(Component.text("そこにタワーはありません", NamedTextColor.RED));
            return;
        }

        open(session, new Screen() {
            @Override
            public Component title() {
                return Component.text(tower.kind().displayName() + " Lv" + (tower.level() + 1));
            }

            @Override
            public InventoryType type() {
                return InventoryType.CHEST_3_ROW;
            }

            @Override
            public void render(PlayerSession s, Inventory inventory) {
                TowerKind.Stats stats = stage.resolvedStats(tower);
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(String.format("攻撃力 %.1f  射程 %.1f  間隔 %dt",
                        stats.damage(), stats.range(), stats.cooldown()), NamedTextColor.WHITE));
                lore.add(Component.text(String.format("推定 DPS %.1f", stats.dps()), NamedTextColor.GRAY));
                var runes = stage.runesUnder(tower);
                if (!runes.isEmpty()) {
                    lore.add(Component.empty());
                    for (var rune : runes) {
                        lore.add(Component.text("土台ルーン: " + rune.displayName()
                                + " — " + rune.description(), rune.color()));
                    }
                }
                inventory.setItemStack(13, Hud.item(tower.kind().icon(),
                        Component.text(tower.kind().displayName() + " Lv" + (tower.level() + 1),
                                tower.kind().element().color()),
                        lore));

                if (tower.maxed()) {
                    inventory.setItemStack(11, Hud.item(Material.BARRIER,
                            Component.text("最大レベル", NamedTextColor.GRAY)));
                } else {
                    int cost = (int) Math.round(tower.nextUpgradeCost() * stage.run().upgradeCostMultiplier());
                    TowerKind.Stats next = tower.kind().statsAt(tower.level() + 1);
                    inventory.setItemStack(11, Hud.item(Material.ANVIL,
                            Component.text("強化 → Lv" + (tower.level() + 2), NamedTextColor.GREEN),
                            Component.text(String.format("攻撃力 %.1f → %.1f",
                                    tower.stats().damage(), next.damage()), NamedTextColor.WHITE),
                            Component.text(String.format("射程 %.1f → %.1f",
                                    tower.stats().range(), next.range()), NamedTextColor.WHITE),
                            Component.text(cost + "G",
                                    stage.run().gold() >= cost ? NamedTextColor.GREEN : NamedTextColor.RED)));
                }

                inventory.setItemStack(15, Hud.item(Material.HOPPER,
                        Component.text("売却", NamedTextColor.YELLOW),
                        Component.text("+" + tower.sellValue() + "G", NamedTextColor.GOLD)));
                inventory.setItemStack(22, Hud.item(Material.BARRIER,
                        Component.text("閉じる", NamedTextColor.RED)));
            }

            @Override
            public void click(PlayerSession s, Inventory inventory, int slot) {
                Player player = s.player();
                switch (slot) {
                    case 11 -> {
                        Stage.Outcome outcome = stage.upgradeTower(cell);
                        Hud.feedback(player, outcome);
                        if (outcome.success()) {
                            render(s, inventory);
                        }
                    }
                    case 15 -> {
                        Stage.Outcome outcome = stage.sellTower(cell);
                        Hud.feedback(player, outcome);
                        s.clearMenu();
                        player.closeInventory();
                    }
                    case 22 -> {
                        s.clearMenu();
                        player.closeInventory();
                    }
                    default -> {
                    }
                }
            }
        });
    }
}
