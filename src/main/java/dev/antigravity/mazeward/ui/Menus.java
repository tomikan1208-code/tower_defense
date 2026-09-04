package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.stage.Stage;
import dev.antigravity.mazeward.tower.Effect;
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

    /** 最終段階の特化 1 つぶんのアイコン。 */
    private static ItemStack specIcon(Battlefield field, TowerKind.Spec spec, int cost) {
        return Hud.item(Material.NETHER_STAR,
                Component.text("特化: " + spec.displayName(), NamedTextColor.LIGHT_PURPLE),
                Component.text(spec.description(), NamedTextColor.GRAY),
                Component.empty(),
                Component.text("一度選ぶと変更できません", NamedTextColor.DARK_GRAY),
                Component.text(field.money(cost),
                        field.wallet().balance() >= cost ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    /**
     * 送還・呪詛・支援の効果を行にする。
     *
     * <p>これらの塔は攻撃力が 0 なので、効果を出さないと
     * 「何もしない塔」に見えてしまう。</p>
     */
    static List<Component> effectLore(TowerKind.Stats stats) {
        List<Component> lore = new ArrayList<>();
        Effect effect = stats.effect();
        if (effect.banishTargets() > 0) {
            // 「何秒に 1 度か」まで出さないと、この塔の強さも弱さも伝わらない
            lore.add(Component.text(String.format("送還 %.0f 体を出発点へ（%.0f 秒に 1 度）",
                    effect.banishTargets(), stats.cooldown() / 20.0),
                    NamedTextColor.DARK_PURPLE));
        }
        if (effect.vulnerability() > 0) {
            lore.add(Component.text(String.format("呪詛 被ダメージ +%.0f%% (%.1f秒)",
                    effect.vulnerability() * 100, effect.vulnerabilityTicks() / 20.0),
                    NamedTextColor.LIGHT_PURPLE));
        }
        if (effect.boostDamage() > 0 || effect.boostRate() > 0) {
            lore.add(Component.text(String.format("支援 周囲の塔の威力 +%.0f%%  手数 +%.0f%%",
                    effect.boostDamage() * 100, effect.boostRate() * 100), NamedTextColor.AQUA));
        }
        if (effect.disableResist() > 0) {
            lore.add(Component.text(effect.disableResist() >= 1.0
                    ? "傘 周囲の塔は妨害者に黙らされない"
                    : String.format("傘 周囲の塔が受ける妨害 -%.0f%%",
                            effect.disableResist() * 100),
                    NamedTextColor.AQUA));
        }
        return lore;
    }

    // ---------------------------------------------------------------- タワー詳細

    /**
     * タワーの強化・売却。
     *
     * <p>{@link Stage} ではなく {@link Battlefield} 越しに触る。
     * シングル専用にしていると、同じ盤面を使っている対戦で強化も売却もできない。
     * 通貨の呼び名と残高は戦場が答えるので、ここは両方を区別しなくてよい。</p>
     */
    public static void openTowerDetail(PlayerSession session, Vec2i cell) {
        Battlefield field = session.field();
        if (field == null) {
            return;
        }
        TowerInstance tower = field.towerAt(cell);
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
                TowerKind.Stats stats = field.resolvedStats(tower);
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text(String.format("射程 %.1f  間隔 %.1f秒",
                        stats.range(), stats.cooldown() / 20.0), NamedTextColor.WHITE));
                if (tower.kind().targeting() != dev.antigravity.mazeward.tower.Targeting.NONE) {
                    lore.add(Component.text("狙い: " + tower.kind().targeting().displayName()
                            + " — " + tower.kind().targeting().description(), NamedTextColor.AQUA));
                }
                if (stats.damage() > 0) {
                    lore.add(Component.text(String.format("攻撃力 %.1f  推定 DPS %.1f",
                            stats.damage(), stats.dps()), NamedTextColor.WHITE));
                }
                lore.addAll(effectLore(stats));
                if (tower.boosted()) {
                    lore.add(Component.text(String.format("監視塔の支援: 威力 +%.0f%%  手数 +%.0f%%",
                            tower.boostDamage() * 100, tower.boostRate() * 100),
                            NamedTextColor.AQUA));
                }
                if (tower.disableImmune()) {
                    lore.add(Component.text("監視塔の傘: 妨害を完全に無効化",
                            NamedTextColor.AQUA));
                } else if (tower.disableResist() > 0) {
                    lore.add(Component.text(String.format("監視塔の傘: 妨害 -%.0f%%",
                            tower.disableResist() * 100), NamedTextColor.AQUA));
                }
                if (tower.disabled()) {
                    lore.add(Component.text("妨害されて停止中", NamedTextColor.RED));
                }
                var runes = field.runesUnder(tower);
                if (!runes.isEmpty()) {
                    lore.add(Component.empty());
                    for (var rune : runes) {
                        lore.add(Component.text("土台ルーン: " + rune.displayName()
                                + " — " + rune.description(), rune.color()));
                    }
                }
                String title = tower.spec() == null ? tower.kind().displayName()
                        : tower.kind().displayName() + "・" + tower.spec().displayName();
                inventory.setItemStack(13, Hud.item(tower.kind().icon(),
                        Component.text(title + " Lv" + (tower.level() + 1),
                                tower.kind().element().color()),
                        lore));

                if (tower.maxed()) {
                    inventory.setItemStack(11, Hud.item(Material.BARRIER,
                            Component.text("最大レベル", NamedTextColor.GRAY)));
                } else if (tower.nextIsSpecialization()) {
                    // 最終段階。同じタワーが 2 つの別物に分かれるので、両方を並べて選ばせる
                    int cost = (int) Math.round(tower.nextUpgradeCost()
                            * field.modifiers().upgradeCostMultiplier());
                    List<TowerKind.Spec> specs = tower.kind().specs();
                    inventory.setItemStack(10, specIcon(field, specs.get(0), cost));
                    inventory.setItemStack(12, specIcon(field, specs.get(1), cost));
                } else {
                    int cost = (int) Math.round(tower.nextUpgradeCost()
                            * field.modifiers().upgradeCostMultiplier());
                    TowerKind.Stats next = tower.kind().statsAt(tower.level() + 1);
                    List<Component> upgradeLore = new ArrayList<>();
                    if (next.damage() > 0) {
                        upgradeLore.add(Component.text(String.format("攻撃力 %.1f → %.1f",
                                tower.stats().damage(), next.damage()), NamedTextColor.WHITE));
                    }
                    upgradeLore.add(Component.text(String.format("射程 %.1f → %.1f",
                            tower.stats().range(), next.range()), NamedTextColor.WHITE));
                    upgradeLore.add(Component.text(field.money(cost),
                            field.wallet().balance() >= cost ? NamedTextColor.GREEN : NamedTextColor.RED));
                    inventory.setItemStack(11, Hud.item(Material.ANVIL,
                            Component.text("強化 → Lv" + (tower.level() + 2), NamedTextColor.GREEN),
                            upgradeLore));
                }

                inventory.setItemStack(15, Hud.item(Material.HOPPER,
                        Component.text("売却", NamedTextColor.YELLOW),
                        Component.text("+" + field.money(tower.sellValue()), NamedTextColor.GOLD)));
                inventory.setItemStack(22, Hud.item(Material.BARRIER,
                        Component.text("閉じる", NamedTextColor.RED)));
            }

            @Override
            public void click(PlayerSession s, Inventory inventory, int slot) {
                Player player = s.player();
                switch (slot) {
                    case 10, 12 -> {
                        if (!tower.nextIsSpecialization()) {
                            return;
                        }
                        TowerKind.Spec chosen = tower.kind().specs().get(slot == 10 ? 0 : 1);
                        Battlefield.Outcome outcome = field.upgradeTower(cell, chosen);
                        Hud.feedback(player, outcome);
                        if (outcome.success()) {
                            render(s, inventory);
                        }
                    }
                    case 11 -> {
                        if (tower.nextIsSpecialization()) {
                            return;
                        }
                        Battlefield.Outcome outcome = field.upgradeTower(cell);
                        Hud.feedback(player, outcome);
                        if (outcome.success()) {
                            render(s, inventory);
                        }
                    }
                    case 15 -> {
                        Battlefield.Outcome outcome = field.sellTower(cell);
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
