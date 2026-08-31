package dev.antigravity.mazeward.ui;

import dev.antigravity.mazeward.ai.AiDirector;
import dev.antigravity.mazeward.versus.Island;
import dev.antigravity.mazeward.versus.MatchClock;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.scoreboard.Sidebar;

/**
 * 観戦者のホットバーとサイドバー。
 *
 * <p>観戦者は盤面を触らない。持ち物は <b>時間の操作</b> と <b>視点の移動</b> だけ。
 * 建築のパレットをそのまま出すと、押しても何も起きないアイテムが 6 個並ぶことになり、
 * 「自分は何ができるのか」が分からなくなる。</p>
 */
public final class SpectatorHud {

    public static final int SLOT_SLOWER = 0;
    public static final int SLOT_FASTER = 1;
    public static final int SLOT_PAUSE = 2;
    public static final int SLOT_NORMAL = 3;
    public static final int SLOT_NEXT_ISLAND = 5;
    public static final int SLOT_LEAVE = 8;

    private SpectatorHud() {
    }

    // ---------------------------------------------------------------- ホットバー

    public static void applyHotbar(Player player, MatchClock clock, String watching) {
        var inventory = player.getInventory();
        inventory.clear();

        inventory.setItemStack(SLOT_SLOWER, Hud.item(Material.RED_DYE,
                Component.text("◀ 遅くする", NamedTextColor.AQUA),
                Component.text("いまの速さ: " + clock.label(), NamedTextColor.GRAY),
                Component.text("0.25 倍まで落とせます", NamedTextColor.DARK_GRAY)));

        inventory.setItemStack(SLOT_FASTER, Hud.item(Material.LIME_DYE,
                Component.text("▶ 速くする", NamedTextColor.GREEN),
                Component.text("いまの速さ: " + clock.label(), NamedTextColor.GRAY),
                Component.text("16 倍まで上げられます", NamedTextColor.DARK_GRAY)));

        inventory.setItemStack(SLOT_PAUSE, clock.paused()
                ? Hud.item(Material.LIME_CONCRETE,
                        Component.text("▶ 再開", NamedTextColor.GREEN),
                        Component.text("止める前の速さに戻します", NamedTextColor.GRAY))
                : Hud.item(Material.BARRIER,
                        Component.text("■ 一時停止", NamedTextColor.YELLOW),
                        Component.text("敵もタワーも収入も止まります", NamedTextColor.GRAY)));

        inventory.setItemStack(SLOT_NORMAL, Hud.item(Material.CLOCK,
                Component.text("等速に戻す", NamedTextColor.WHITE),
                Component.text("x1 に戻します", NamedTextColor.GRAY)));

        inventory.setItemStack(SLOT_NEXT_ISLAND, Hud.item(Material.ENDER_EYE,
                Component.text("次の島へ", NamedTextColor.LIGHT_PURPLE),
                Component.text("見ている島: " + watching, NamedTextColor.GRAY),
                Component.text("飛んで自分で見に行くこともできます", NamedTextColor.DARK_GRAY)));

        inventory.setItemStack(SLOT_LEAVE, Hud.item(Material.OAK_DOOR,
                Component.text("観戦をやめる", NamedTextColor.RED),
                Component.text("ロビーへ戻ります", NamedTextColor.GRAY)));
    }

    // ---------------------------------------------------------------- サイドバー

    /**
     * 全員ぶんを 1 画面に出す。
     *
     * <p>観戦で見たいのは自分の資源ではなく <b>誰が優勢か</b> なので、
     * ライフ・コイン・インカム・タワー数を人ごとに 1 行ずつ並べる。
     * AI の直前の手も出す。盤面だけ見ていても
     * 「いま何を考えて置いたのか」が読み取れないため。</p>
     */
    public static void updateSidebar(PlayerSession session, VersusMatch match,
                                     AiDirector director, MatchClock clock) {
        Sidebar sidebar = session.sidebar();
        if (sidebar == null) {
            return;
        }
        set(sidebar, "l1", Component.text("速度 " + clock.label(),
                clock.paused() ? NamedTextColor.YELLOW : NamedTextColor.AQUA));
        set(sidebar, "l2", match.preparing()
                ? Component.text("準備 あと " + match.prepSecondsLeft() + "秒",
                        NamedTextColor.LIGHT_PURPLE)
                : Component.text("経過 " + (match.elapsedTicks() / 1200) + "分 "
                        + (match.elapsedTicks() / 20 % 60) + "秒", NamedTextColor.GRAY));
        set(sidebar, "l3", Component.text(
                director == null ? "AI なし" : director.policyName(), NamedTextColor.DARK_AQUA));

        List<VersusPlayer> participants = match.participants();
        for (int i = 0; i < 5; i++) {
            String id = "l" + (i + 4);
            if (i >= participants.size()) {
                set(sidebar, id, Component.empty());
                continue;
            }
            VersusPlayer participant = participants.get(i);
            Island island = participant.island();
            int towers = island == null ? 0 : island.towers().size();
            String line = participant.name() + " ♥" + participant.lives()
                    + " ¤" + participant.coins() + " ↑" + participant.income()
                    + " 塔" + towers;
            set(sidebar, id, Component.text(line, participant.alive()
                    ? (participant.bot() ? NamedTextColor.AQUA : NamedTextColor.WHITE)
                    : NamedTextColor.DARK_GRAY));
        }
    }

    /**
     * AI がいま打った手をアクションバーに出す。
     *
     * <p>サイドバーは 8 行しかないので、流れていく情報はこちらへ。
     * 「誰が」「何をしたか」が見えないと、盤面が勝手に変わるだけの画面になる。</p>
     */
    public static void showAction(Player player, VersusPlayer actor, String action) {
        if (action == null || action.isEmpty()) {
            return;
        }
        player.sendActionBar(Component.text(actor.name() + ": " + action,
                NamedTextColor.AQUA));
    }

    public static ItemStack spectateItem() {
        return Hud.item(Material.SPYGLASS,
                Component.text("AI 観戦 — AI 同士の対戦を見る", NamedTextColor.LIGHT_PURPLE),
                Component.text("速度を変えられます（一時停止〜16 倍）", NamedTextColor.GRAY),
                Component.text("右クリックで人数を選ぶ", NamedTextColor.DARK_GRAY));
    }

    public static ItemStack versusAiItem() {
        return Hud.item(Material.DIAMOND_SWORD,
                Component.text("AI と対戦", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("学習済みの方策と 1 対 1 以上で戦う", NamedTextColor.GRAY),
                Component.text("右クリックで人数を選ぶ", NamedTextColor.DARK_GRAY));
    }

    private static void set(Sidebar sidebar, String id, Component content) {
        sidebar.updateLineContent(id, content);
    }
}
