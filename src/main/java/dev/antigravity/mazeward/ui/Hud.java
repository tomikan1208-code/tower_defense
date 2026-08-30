package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.run.Relic;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.stage.Phase;
import dev.antigravity.mazeward.stage.Stage;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.scoreboard.Sidebar;

/** ホットバー・サイドバー・アクションバーの表示。 */
public final class Hud {

    private Hud() {
    }

    // ---------------------------------------------------------------- アイテム生成

    public static ItemStack item(Material material, Component name, List<Component> lore) {
        return ItemStack.builder(material)
                .customName(name.decoration(TextDecoration.ITALIC, false))
                .lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList())
                .hideExtraTooltip()
                .build();
    }

    public static ItemStack item(Material material, Component name, Component... lore) {
        return item(material, name, List.of(lore));
    }

    public static ItemStack cardItem(BlockCard card, dev.antigravity.mazeward.core.Rot rot,
                                     boolean selected, Material material) {
        List<Component> lore = new ArrayList<>();
        for (String line : card.shape().ascii(rot)) {
            lore.add(Component.text(line, NamedTextColor.YELLOW));
        }
        lore.add(Component.empty());
        if (card.hasRune()) {
            lore.add(Component.text("ルーン: " + card.rune().displayName(), card.rune().color()));
            lore.add(Component.text(card.rune().description(), NamedTextColor.GRAY));
            lore.add(Component.empty());
        }
        lore.add(Component.text("カード 1 枚 / " + card.cellCount() + " マス", NamedTextColor.GRAY));
        lore.add(Component.text("左クリック: 回転  /  右クリック: 配置", NamedTextColor.DARK_GRAY));
        if (selected) {
            lore.add(Component.text("▶ 選択中 (" + rot.label() + ")", NamedTextColor.GREEN));
        }
        return ItemStack.builder(material)
                .customName(Component.text("障害物カード: " + card.displayName(),
                                selected ? NamedTextColor.GREEN
                                        : card.hasRune() ? card.rune().color() : NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false))
                .lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList())
                .amount(1)
                .glowing(selected || card.hasRune())
                .hideExtraTooltip()
                .build();
    }

    // ---------------------------------------------------------------- ホットバー

    public static void applyStageHotbar(PlayerSession session) {
        Player player = session.player();
        Stage stage = session.stage();
        if (stage == null) {
            return;
        }
        RunState run = stage.run();
        List<BlockCard> hand = run.deck().hand();

        for (int slot = 0; slot < PlayerSession.MAX_HAND_SLOTS; slot++) {
            if (slot < hand.size()) {
                boolean selected = session.mode() == PlayerSession.Mode.CARD && session.cardIndex() == slot;
                player.getInventory().setItemStack(slot,
                        cardItem(hand.get(slot), selected ? session.rot()
                                        : dev.antigravity.mazeward.core.Rot.R0, selected,
                                cardMaterial(stage, hand.get(slot))));
            } else {
                player.getInventory().setItemStack(slot, ItemStack.AIR);
            }
        }

        if (session.mode() == PlayerSession.Mode.TOWER && session.selectedTower() != null) {
            var kind = session.selectedTower();
            player.getInventory().setItemStack(PlayerSession.SLOT_SELECTED_TOWER,
                    item(kind.icon(),
                            Component.text("選択中: " + kind.displayName(), kind.element().color()),
                            Component.text(kind.baseCost() + "G", NamedTextColor.GOLD),
                            Component.text("左クリック: 回転  右クリック: 設置", NamedTextColor.DARK_GRAY)));
        } else {
            player.getInventory().setItemStack(PlayerSession.SLOT_SELECTED_TOWER, ItemStack.AIR);
        }

        player.getInventory().setItemStack(PlayerSession.SLOT_TOWER_SHOP,
                item(Material.CHEST, Component.text("タワー一覧", NamedTextColor.AQUA),
                        Component.text("右クリックで開く", NamedTextColor.DARK_GRAY)));

        // 望遠鏡は右クリックで画面がズームしてしまうため、使用アクションのないアイテムを使う
        player.getInventory().setItemStack(PlayerSession.SLOT_INSPECT,
                item(Material.BLAZE_ROD, Component.text("強化 / 売却", NamedTextColor.WHITE),
                        Component.text("タワーを狙って右クリック", NamedTextColor.DARK_GRAY)));

        boolean build = stage.phase() == Phase.BUILD;
        player.getInventory().setItemStack(PlayerSession.SLOT_START, build
                ? item(Material.LIME_DYE,
                        Component.text("▶ ウェーブ開始", NamedTextColor.GREEN),
                        Component.text(stage.nextWave() == null ? "" : stage.nextWave().summary(),
                                NamedTextColor.GRAY))
                : item(Material.GRAY_DYE, Component.text("戦闘中", NamedTextColor.RED),
                        Component.text("戦闘中もカードとタワーは設置できます", NamedTextColor.GRAY),
                        Component.text("ウェーブが終わるまで待機", NamedTextColor.DARK_GRAY)));
    }

    /** そのカードが実際に置くブロックに対応するアイテム。手札を見れば素材が分かる。 */
    private static Material cardMaterial(Stage stage, BlockCard card) {
        var block = card.hasRune()
                ? card.rune().wallBlock()
                : stage.arena().theme().wallForVariant(card.variant());
        Material material = block.registry().material();
        return material == null ? Material.STONE_BRICKS : material;
    }

    public static void applyLobbyHotbar(Player player) {
        player.getInventory().clear();
        player.getInventory().setItemStack(0,
                item(Material.NETHER_STAR, Component.text("新しいランを開始", NamedTextColor.GOLD),
                        Component.text("右クリックでロードマップへ", NamedTextColor.GRAY)));
    }

    public static void applyRoadmapHotbar(Player player) {
        player.getInventory().clear();
        player.getInventory().setItemStack(0,
                item(Material.FILLED_MAP, Component.text("ロードマップ", NamedTextColor.AQUA),
                        Component.text("行きたいノードの足場に乗る", NamedTextColor.GRAY)));
        player.getInventory().setItemStack(8,
                item(Material.BOOK, Component.text("所持レリック", NamedTextColor.LIGHT_PURPLE),
                        Component.text("右クリックで確認", NamedTextColor.DARK_GRAY)));
    }

    // ---------------------------------------------------------------- サイドバー

    public static Sidebar createSidebar() {
        Sidebar sidebar = new Sidebar(Component.text("MAZEWARD", NamedTextColor.GOLD, TextDecoration.BOLD));
        String[] ids = {"l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8"};
        for (int i = 0; i < ids.length; i++) {
            sidebar.createLine(new Sidebar.ScoreboardLine(ids[i], Component.empty(), ids.length - i));
        }
        return sidebar;
    }

    public static void updateSidebar(PlayerSession session, RunState run) {
        Sidebar sidebar = session.sidebar();
        if (sidebar == null || run == null) {
            return;
        }
        Stage stage = session.stage();

        set(sidebar, "l1", Component.text("第 " + run.layer() + " 層", NamedTextColor.WHITE));
        set(sidebar, "l2", stage == null
                ? Component.text("ロードマップ", NamedTextColor.GRAY)
                : Component.text(stage.phase().displayName() + "フェーズ", stage.phase().color()));
        set(sidebar, "l3", stage == null
                ? Component.empty()
                : Component.text("ウェーブ " + stage.waveNumber() + "/" + stage.waveCount(), NamedTextColor.YELLOW));
        set(sidebar, "l4", stage == null
                ? Component.text("エンバー " + run.ember(), NamedTextColor.GOLD)
                : Component.text("ゴールド " + run.gold() + "G  ｜ エンバー " + run.ember(),
                        NamedTextColor.GOLD));
        set(sidebar, "l5", Component.text("コア " + run.coreHp() + "/" + run.maxCoreHp(),
                run.coreHp() > run.maxCoreHp() / 2 ? NamedTextColor.GREEN : NamedTextColor.RED));
        set(sidebar, "l6", Component.text("手札 " + run.deck().hand().size()
                + "  山札 " + run.deck().drawPileSize(), NamedTextColor.AQUA));
        set(sidebar, "l7", stage == null
                ? Component.text("デッキ " + run.deck().librarySize() + "枚", NamedTextColor.GRAY)
                : Component.text(String.format("移動距離 %.1f", stage.totalPathLength()),
                        NamedTextColor.LIGHT_PURPLE));
        set(sidebar, "l8", Component.text("レリック " + run.relics().size(), NamedTextColor.LIGHT_PURPLE));
    }

    private static void set(Sidebar sidebar, String id, Component content) {
        sidebar.updateLineContent(id, content);
    }

    // ---------------------------------------------------------------- テキスト

    public static void feedback(Player player, Stage.Outcome outcome) {
        player.sendActionBar(Component.text(outcome.message(),
                outcome.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    /** デッキ一覧・ルーン付与画面で使うカードのアイコン。 */
    public static ItemStack deckCardIcon(dev.antigravity.mazeward.run.BlockCard card,
                                         int index, boolean selectable) {
        List<Component> lore = new ArrayList<>();
        for (String line : card.shape().ascii(dev.antigravity.mazeward.core.Rot.R0)) {
            lore.add(Component.text(line, NamedTextColor.YELLOW));
        }
        lore.add(Component.empty());
        if (card.hasRune()) {
            lore.add(Component.text("ルーン: " + card.rune().displayName(), card.rune().color()));
            lore.add(Component.text(card.rune().description(), NamedTextColor.GRAY));
            lore.add(Component.text("これ以上ルーンは付けられません", NamedTextColor.DARK_GRAY));
        } else if (selectable) {
            lore.add(Component.text("クリックでルーンを付与", NamedTextColor.GREEN));
        }
        return ItemStack.builder(card.hasRune() ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS)
                .customName(Component.text((index + 1) + ". " + card.displayName(),
                                card.hasRune() ? card.rune().color() : NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false))
                .lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList())
                .glowing(card.hasRune())
                .hideExtraTooltip()
                .build();
    }

    public static Component relicLine(Relic relic) {
        return Component.text("・" + relic.displayName() + " — " + relic.description(),
                NamedTextColor.LIGHT_PURPLE);
    }
}
