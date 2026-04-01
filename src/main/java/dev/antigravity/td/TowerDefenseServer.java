package dev.antigravity.td;

import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.TaskSchedule;

public final class TowerDefenseServer {
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 25565;

    private final GameFlowController flow;
    private final MinecraftServer minecraftServer;

    public TowerDefenseServer() {
        this.minecraftServer = MinecraftServer.init();
        this.flow = new GameFlowController();
    }

    public void start() {
        registerEvents();
        MinecraftServer.getSchedulerManager()
                .buildTask(flow::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule();

        minecraftServer.start(HOST, PORT);
    }

    private void registerEvents() {
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();

        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            flow.configurePlayer(event.getPlayer(), event);
        });

        events.addListener(PlayerSpawnEvent.class, event -> {
            flow.onPlayerSpawn(event.getPlayer());
            if (!event.isFirstSpawn()) {
                return;
            }
            event.getPlayer().sendMessage(Component.text("RogueLike TDロビーへようこそ"));
            event.getPlayer().sendMessage(Component.text("門の起動ブロックを右クリックしてロードマップへ進んでください"));
        });

        events.addListener(PlayerBlockInteractEvent.class, flow::onBlockInteract);
        events.addListener(PlayerBlockBreakEvent.class, flow::onBlockBreak);
        events.addListener(PlayerBlockPlaceEvent.class, flow::onBlockPlace);
        events.addListener(PlayerHandAnimationEvent.class, flow::onHandAnimation);
        events.addListener(InventoryPreClickEvent.class, flow::handleInventoryPreClick);

        events.addListener(PlayerChatEvent.class, event -> {
            String message = event.getRawMessage().trim();
            if (!message.startsWith("!")) {
                return;
            }

            event.setCancelled(true);
            String[] split = message.split("\\s+");
            String command = split[0].toLowerCase();
            switch (command) {
                case "!lobby" -> flow.moveToLobby(event.getPlayer());
                case "!roadmap" -> flow.moveToRoadmap(event.getPlayer());
                case "!start" -> flow.startWave(event.getPlayer());
                case "!state" -> flow.showState(event.getPlayer());
                case "!deck" -> {
                    int index = 1;
                    if (split.length >= 2) {
                        try {
                            index = Integer.parseInt(split[1]);
                        } catch (NumberFormatException ignored) {
                            index = -1;
                        }
                    }
                    flow.selectDeck(event.getPlayer(), index);
                }
                case "!tower" -> {
                    String towerType = split.length >= 2 ? split[1] : "basic";
                    flow.placeTower(event.getPlayer(), towerType);
                }
                default -> event.getPlayer().sendMessage(Component.text("不明コマンド: !lobby !roadmap !start !state !deck <1-3> !tower <type>"));
            }
        });
    }
}
