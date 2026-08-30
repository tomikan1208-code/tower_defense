package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.run.Relic;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.stage.Phase;
import dev.antigravity.mazeward.stage.Battlefield;
import dev.antigravity.mazeward.stage.Stage;
import dev.antigravity.mazeward.tower.TowerKind;
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

    /**
     * タワーをホットバーに置くときのアイコン。
     *
     * <p>チェストを開かずに選ぶので、性能はすべてここに載せる。
     * 形も出す。タワーは複数マスを占めるので、
     * 「その土台に載るか」が分からないと持ち替える意味がない。</p>
     */
    public static ItemStack towerPaletteItem(TowerKind kind, boolean selected,
                                             String price, boolean affordable) {
        TowerKind.Stats stats = kind.statsAt(0);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(kind.description(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        for (String line : kind.shape().ascii(Rot.R0)) {
            lore.add(Component.text(line, NamedTextColor.DARK_AQUA));
        }
        lore.add(Component.empty());
        lore.add(Component.text(String.format("射程 %.1f  間隔 %dt", stats.range(), stats.cooldown()),
                NamedTextColor.WHITE));
        if (stats.dps() > 0) {
            lore.add(Component.text(String.format("DPS %.0f", stats.dps()), NamedTextColor.WHITE));
        }
        // 送還・呪詛・支援の塔は攻撃力が 0 なので、効果を出さないと空欄に見える
        lore.addAll(Menus.effectLore(stats));
        lore.add(Component.text(price, affordable ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(Component.text("右クリック: 壁の上に設置", NamedTextColor.DARK_GRAY));
        if (selected) {
            lore.add(Component.text("▶ 選択中", NamedTextColor.GREEN));
        }
        return ItemStack.builder(kind.icon())
                .customName(Component.text(kind.displayName(),
                                selected ? NamedTextColor.GREEN : kind.element().color())
                        .decoration(TextDecoration.ITALIC, false))
                .lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList())
                .glowing(selected)
                .hideExtraTooltip()
                .build();
    }

    // ---------------------------------------------------------------- ホットバー

    /**
     * ホットバー 0〜5 と切り替えスロットを描く。
     *
     * <p>シングルも対戦もここを共有する。
     * 「障害物を持つか、タワーを持つか」は盤面の見え方に関わらず同じ操作であってほしい。</p>
     */
    public static void applyPalette(PlayerSession session) {
        Battlefield field = session.field();
        if (field == null) {
            return;
        }
        var inventory = session.player().getInventory();

        if (session.handMode() == PlayerSession.HandMode.TOWER) {
            List<TowerKind> towers = session.palette();
            for (int slot = 0; slot < PlayerSession.PALETTE_SLOTS; slot++) {
                if (slot >= towers.size()) {
                    inventory.setItemStack(slot, ItemStack.AIR);
                    continue;
                }
                TowerKind kind = towers.get(slot);
                boolean selected = session.mode() == PlayerSession.Mode.TOWER
                        && session.selectedTower() == kind;
                inventory.setItemStack(slot, towerPaletteItem(kind, selected,
                        field.money(kind.baseCost()),
                        field.wallet().balance() >= kind.baseCost()));
            }
        } else {
            List<BlockCard> hand = field.deck().hand();
            for (int slot = 0; slot < PlayerSession.PALETTE_SLOTS; slot++) {
                if (slot >= hand.size()) {
                    inventory.setItemStack(slot, ItemStack.AIR);
                    continue;
                }
                boolean selected = session.mode() == PlayerSession.Mode.CARD
                        && session.cardIndex() == slot;
                inventory.setItemStack(slot, cardItem(hand.get(slot),
                        selected ? session.rot() : Rot.R0, selected,
                        cardMaterial(field, hand.get(slot))));
            }
        }

        inventory.setItemStack(PlayerSession.SLOT_TOGGLE, toggleItem(session));
    }

    private static ItemStack toggleItem(PlayerSession session) {
        boolean tower = session.handMode() == PlayerSession.HandMode.TOWER;
        boolean more = session.hasNextTowerPage();

        String next = !tower ? "▶ タワーに持ち替える"
                : more ? "▶ タワー 続き" : "▶ 障害物に持ち替える";
        String now = tower
                ? "いま持っているのはタワー（" + (session.towerPage() + 1) + "ページ目）"
                : "いま持っているのは障害物カード";

        // 望遠鏡は右クリックでズームしてしまうので使わない
        return item(!tower ? Material.BOW : more ? Material.PAPER : Material.BRICKS,
                Component.text(next, tower && !more ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
                Component.text(now, NamedTextColor.GRAY),
                Component.text("右クリックで切り替え  1〜6 で選択", NamedTextColor.DARK_GRAY));
    }

    public static void applyStageHotbar(PlayerSession session) {
        Player player = session.player();
        Stage stage = session.stage();
        if (stage == null) {
            return;
        }
        applyPalette(session);

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
    private static Material cardMaterial(Battlefield field, BlockCard card) {
        var block = card.hasRune()
                ? card.rune().wallBlock()
                : field.arena().theme().wallForVariant(card.variant());
        Material material = block.registry().material();
        return material == null ? Material.STONE_BRICKS : material;
    }

    public static void applyLobbyHotbar(Player player) {
        player.getInventory().clear();
        player.getInventory().setItemStack(0,
                item(Material.NETHER_STAR, Component.text("ソロ — ローグライト", NamedTextColor.GOLD),
                        Component.text("7 層のノードマップを踏破する", NamedTextColor.GRAY),
                        Component.text("右クリックで開始", NamedTextColor.DARK_GRAY)));
        player.getInventory().setItemStack(1,
                item(Material.IRON_SWORD, Component.text("対戦 — 送り合い", NamedTextColor.RED),
                        Component.text("島を守りながら相手にモンスターを送る", NamedTextColor.GRAY),
                        Component.text("右クリックで待機部屋へ移動", NamedTextColor.DARK_GRAY)));
    }

    /**
     * 待機部屋のホットバー。
     *
     * <p>部屋に入っている人がそのまま参加者になるので、人数を選ぶ操作はない。
     * 押せるかどうかを色で出しておかないと、
     * 「押したのに始まらない」のか「まだ人が足りない」のかが分からない。</p>
     */
    public static void applyWaitingHotbar(Player player, int party) {
        boolean ready = party >= 2;
        player.getInventory().clear();
        player.getInventory().setItemStack(0, ready
                ? item(Material.LIME_DYE,
                        Component.text("▶ 対戦を開始", NamedTextColor.GREEN),
                        Component.text("いま部屋にいる " + party + " 人で始めます", NamedTextColor.GRAY),
                        Component.text("右クリック", NamedTextColor.DARK_GRAY))
                : item(Material.GRAY_DYE,
                        Component.text("開始できません", NamedTextColor.RED),
                        Component.text("参加 " + party + " 人 — あと "
                                + (2 - party) + " 人必要", NamedTextColor.GRAY)));
        player.getInventory().setItemStack(1,
                item(Material.PLAYER_HEAD,
                        Component.text("デバッグ: ボットで埋める", NamedTextColor.LIGHT_PURPLE),
                        Component.text("あなたの操作を真似するボットと対戦します", NamedTextColor.GRAY),
                        Component.text("動作確認用", NamedTextColor.DARK_GRAY)));
        player.getInventory().setItemStack(8,
                item(Material.BARRIER, Component.text("ロビーに戻る", NamedTextColor.RED),
                        Component.text("部屋を出ると参加は取り消されます", NamedTextColor.DARK_GRAY)));
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
