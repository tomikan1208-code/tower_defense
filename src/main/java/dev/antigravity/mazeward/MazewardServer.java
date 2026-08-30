package dev.antigravity.mazeward;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.timer.TaskSchedule;

/** Minestom の初期化とイベント配線だけを持つ薄い層。 */
public final class MazewardServer {

    private static final String HOST = "0.0.0.0";
    private static final int PORT = 25565;

    private final MinecraftServer server;
    private final Mazeward game;

    public MazewardServer() {
        this.server = MinecraftServer.init();
        this.game = new Mazeward();
    }

    public void start() {
        registerEvents();

        MinecraftServer.getSchedulerManager()
                .buildTask(game::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule();

        server.start(HOST, PORT);
        System.out.println("MAZEWARD server listening on " + HOST + ":" + PORT);
    }

    private void registerEvents() {
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();

        events.addListener(AsyncPlayerConfigurationEvent.class, game::onConfigure);
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                game.onSpawn(event.getPlayer());
            }
        });
        events.addListener(PlayerDisconnectEvent.class, event -> game.onDisconnect(event.getPlayer()));

        events.addListener(PlayerHandAnimationEvent.class, game::onHandAnimation);
        events.addListener(PlayerUseItemEvent.class, game::onUseItem);
        events.addListener(PlayerBlockInteractEvent.class, game::onBlockInteract);
        events.addListener(PlayerBlockPlaceEvent.class, game::onBlockPlace);
        events.addListener(PlayerBlockBreakEvent.class, game::onBlockBreak);
        events.addListener(PlayerChangeHeldSlotEvent.class, game::onChangeHeldSlot);
        events.addListener(InventoryPreClickEvent.class, game::onInventoryPreClick);
        events.addListener(InventoryCloseEvent.class, game::onInventoryClose);

        events.addListener(PlayerChatEvent.class, event -> {
            String message = event.getRawMessage().trim();
            if (!message.startsWith("!")) {
                return;
            }
            event.setCancelled(true);
            switch (message.toLowerCase()) {
                case "!state" -> event.getPlayer().sendMessage(
                        Component.text(game.debugState(), NamedTextColor.AQUA));
                case "!next" -> game.forceAdvance(event.getPlayer());
                case "!help" -> {
                    event.getPlayer().sendMessage(Component.text("── 操作 ──", NamedTextColor.GOLD));
                    event.getPlayer().sendMessage(Component.text(
                            "ホットバー 1-5: 障害物カード / 6: 選択中のタワー / 7: タワー一覧 / 8: 検査 / 9: ウェーブ開始",
                            NamedTextColor.GRAY));
                    event.getPlayer().sendMessage(Component.text(
                            "左クリック: 回転   右クリック: 確定", NamedTextColor.GRAY));
                    event.getPlayer().sendMessage(Component.text(
                            "青=現在の経路  黄=配置予定  赤=変わる区間", NamedTextColor.AQUA));
                    event.getPlayer().sendMessage(Component.text(
                            "!next : 進行が止まったときの復帰", NamedTextColor.GRAY));
                }
                default -> event.getPlayer().sendMessage(
                        Component.text("!help で操作一覧", NamedTextColor.GRAY));
            }
        });
    }
}
