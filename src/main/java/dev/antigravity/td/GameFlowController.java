package dev.antigravity.td;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.world.DimensionType;

public final class GameFlowController {
    private static final int LOBBY_Y = 50;
    private static final int ROADMAP_Y = 50;
    private static final int STAGE_Y = 50;
    private static final int OBSTACLE_Y = STAGE_Y + 1;
    private static final int LOBBY_GATE_Z = 8;
    private static final int NIGHT_VISION_DURATION_TICKS = 20 * 60 * 60;

    private final InstanceContainer lobby;
    private final InstanceContainer roadmap;
    private final Map<Integer, InstanceContainer> stageInstancesByLayer = new HashMap<>();
    private final Map<InstanceContainer, TDGame> stageGames = new HashMap<>();
    private final Map<InstanceContainer, Integer> stageLayerByInstance = new HashMap<>();
    private final Map<BlockVec, RoadNode> roadmapNodeByCenter = new HashMap<>();
    private final List<PathSegment> roadmapPathPeaks = new ArrayList<>();
    private final Map<UUID, Integer> prepTicksRemaining = new HashMap<>();
    private final Map<UUID, StageType> prepStageByPlayer = new HashMap<>();
    private final Map<UUID, Integer> roadmapClearedLayerByPlayer = new HashMap<>();
    private final Map<UUID, BuildSelection> selectedBuildByPlayer = new HashMap<>();
    private final Map<UUID, List<DeckOffer>> deckOffersByPlayer = new HashMap<>();
    private final Map<UUID, DeckOffer> selectedDeckByPlayer = new HashMap<>();
    private final Map<UUID, Inventory> deckSelectionMenuByPlayer = new HashMap<>();
    private final Map<UUID, Inventory> deckDetailMenuByPlayer = new HashMap<>();
    private final Map<UUID, Integer> deckMenuIndexByPlayer = new HashMap<>();
    private int particleTickCounter = 0;

    private final BlockVec lobbyEmeraldButton = new BlockVec(0, LOBBY_Y + 1, 0);

    public GameFlowController() {
        this.lobby = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        this.roadmap = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);

        lobby.setGenerator(flatGenerator(Block.WHITE_CONCRETE));
        roadmap.setGenerator(flatGenerator(Block.DARK_OAK_PLANKS));

        decorateLobby();
        buildRoadmap();
        createStageInstances();
    }

    public void configurePlayer(Player player, AsyncPlayerConfigurationEvent event) {
        event.setSpawningInstance(lobby);
        player.setRespawnPoint(new Pos(0.5, LOBBY_Y + 1, 0.5));
    }

    public void onPlayerSpawn(Player player) {
        applyNightVision(player);
    }

    public void onHandAnimation(net.minestom.server.event.player.PlayerHandAnimationEvent event) {
        // 現状は手振り入力を追加操作に使わない
    }

    public void moveToLobby(Player player) {
        player.setInstance(lobby, new Pos(0.5, LOBBY_Y + 1, 0.5));
        applyNightVision(player);
        player.sendMessage(Component.text("ロビーに戻りました", NamedTextColor.AQUA));
    }

    public void moveToRoadmap(Player player) {
        player.setInstance(roadmap, new Pos(0.5, ROADMAP_Y + 1, 0.5));
        applyNightVision(player);
        roadmapClearedLayerByPlayer.putIfAbsent(player.getUuid(), 0);
        player.sendMessage(Component.text("ロードマップを表示します。3×3のピラミッド中央をクリックしてステージを選択", NamedTextColor.GOLD));
        player.sendMessage(Component.text("現在の解放層: " + getRoadmapClearedLayer(player) + " / 8", NamedTextColor.YELLOW));
        prepareDeckChoices(player);
        showDeckChoice(player);
    }

    public void startWave(Player player) {
        TDGame game = gameOf(player);
        if (game == null) {
            player.sendMessage(Component.text("ステージ内でのみ開始できます", NamedTextColor.RED));
            return;
        }
        game.startWave();
    }

    public void placeTower(Player player, String towerTypeToken) {
        TDGame game = gameOf(player);
        if (game == null) {
            player.sendMessage(Component.text("ステージ内でのみ塔を設置できます", NamedTextColor.RED));
            return;
        }
        game.tryPlaceTower(player, towerTypeToken);
    }

    public void showState(Player player) {
        TDGame game = gameOf(player);
        if (game == null) {
            player.sendMessage(Component.text("ステージ内でのみ状態表示できます", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text(game.debugState(), NamedTextColor.AQUA));
        player.sendMessage(Component.text(game.towerCatalog(), NamedTextColor.GRAY));
    }

    public void selectDeck(Player player, int index) {
        UUID uuid = player.getUuid();
        List<DeckOffer> offers = deckOffersByPlayer.get(uuid);
        if (offers == null || offers.size() != 3) {
            player.sendMessage(Component.text("先にロードマップへ入ってデッキを生成してください", NamedTextColor.RED));
            return;
        }
        if (index < 1 || index > 3) {
            player.sendMessage(Component.text("デッキ番号は1〜3です", NamedTextColor.RED));
            showDeckChoice(player);
            return;
        }

        DeckOffer chosen = offers.get(index - 1);
        selectedDeckByPlayer.put(uuid, chosen);
        deckSelectionMenuByPlayer.remove(uuid);
        deckDetailMenuByPlayer.remove(uuid);
        deckMenuIndexByPlayer.remove(uuid);
        player.sendMessage(Component.text("選択したデッキ: " + chosen.name(), NamedTextColor.GREEN));
        player.sendMessage(Component.text(chosen.summary(), NamedTextColor.GRAY));
        player.closeInventory();
    }

    private void prepareDeckChoices(Player player) {
        UUID uuid = player.getUuid();
        List<DeckOffer> offers = new ArrayList<>();
        offers.add(generateDeck("第1デッキ"));
        offers.add(generateDeck("第2デッキ"));
        offers.add(generateDeck("第3デッキ"));
        deckOffersByPlayer.put(uuid, offers);
        selectedDeckByPlayer.remove(uuid);
        deckSelectionMenuByPlayer.remove(uuid);
        deckDetailMenuByPlayer.remove(uuid);
        deckMenuIndexByPlayer.remove(uuid);
    }

    private void showDeckChoice(Player player) {
        UUID uuid = player.getUuid();
        List<DeckOffer> offers = deckOffersByPlayer.get(uuid);
        if (offers == null || offers.size() != 3) {
            player.sendMessage(Component.text("デッキ候補がありません。もう一度ロードマップへ入ってください", NamedTextColor.RED));
            return;
        }

        deckDetailMenuByPlayer.remove(uuid);
        deckMenuIndexByPlayer.remove(uuid);

        Inventory menu = new Inventory(InventoryType.CHEST_3_ROW, Component.text("デッキを選択"));
        fillMenuBackground(menu, Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));

        int[] chestSlots = new int[] {11, 13, 15};
        int[] confirmSlots = new int[] {20, 22, 24};
        for (int i = 0; i < offers.size(); i++) {
            DeckOffer offer = offers.get(i);
            menu.setItemStack(chestSlots[i], createDeckChestItem(offer, i + 1));
            menu.setItemStack(confirmSlots[i], createGreenConfirmItem(offer, i + 1));
        }

        deckSelectionMenuByPlayer.put(uuid, menu);
        player.openInventory(menu);
        player.sendMessage(Component.text("デッキを1つ選んでください。チェストで詳細、緑のガラスで確定です", NamedTextColor.GOLD));
    }

    private void handleSelectionClick(Player player, int slot) {
        UUID uuid = player.getUuid();
        int deckIndex = selectionSlotToIndex(slot);
        if (deckIndex >= 0) {
            openDeckDetail(player, deckIndex);
            return;
        }

        int confirmIndex = confirmSlotToIndex(slot);
        if (confirmIndex >= 0) {
            selectDeck(player, confirmIndex + 1);
        }
    }

    private void handleDetailClick(Player player, int slot) {
        UUID uuid = player.getUuid();
        int deckIndex = deckMenuIndexByPlayer.getOrDefault(uuid, -1);
        if (slot == 18) {
            showDeckChoice(player);
            return;
        }
        if (slot == 26 && deckIndex >= 0) {
            selectDeck(player, deckIndex + 1);
        }
    }

    private void openDeckDetail(Player player, int deckIndex) {
        UUID uuid = player.getUuid();
        List<DeckOffer> offers = deckOffersByPlayer.get(uuid);
        if (offers == null || deckIndex < 0 || deckIndex >= offers.size()) {
            return;
        }

        DeckOffer offer = offers.get(deckIndex);
        Inventory detail = new Inventory(InventoryType.CHEST_3_ROW, Component.text(offer.name() + " の詳細"));
        fillMenuBackground(detail, Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));

        int[] towerSlots = new int[] {11, 13, 15};
        for (int i = 0; i < offer.towers().size() && i < towerSlots.length; i++) {
            BuildSelection tower = offer.towers().get(i);
            detail.setItemStack(towerSlots[i], createTowerItem(tower));
        }

        int blockSlot = 19;
        for (BuildSelection block : offer.blocks()) {
            detail.setItemStack(blockSlot++, createBlockItem(block));
        }

        detail.setItemStack(18, ItemStack.builder(Material.ARROW)
                .customName(Component.text("戻る"))
            .lore(Component.text("デッキ一覧に戻ります"))
                .hideExtraTooltip()
                .build());
        detail.setItemStack(26, ItemStack.builder(Material.GREEN_STAINED_GLASS_PANE)
                .customName(Component.text("確定"))
            .lore(Component.text(offer.summary()), Component.text("このデッキを選択します"))
                .hideExtraTooltip()
                .build());

        deckDetailMenuByPlayer.put(uuid, detail);
        deckMenuIndexByPlayer.put(uuid, deckIndex);
        player.openInventory(detail);
    }

    private int selectionSlotToIndex(int slot) {
        return switch (slot) {
            case 11 -> 0;
            case 13 -> 1;
            case 15 -> 2;
            default -> -1;
        };
    }

    private int confirmSlotToIndex(int slot) {
        return switch (slot) {
            case 20 -> 0;
            case 22 -> 1;
            case 24 -> 2;
            default -> -1;
        };
    }

    private ItemStack createDeckChestItem(DeckOffer offer, int number) {
        return ItemStack.builder(Material.CHEST)
            .customName(Component.text(offer.name(), NamedTextColor.AQUA))
                .lore(
                        Component.text("タワー3種: " + offer.towersSummary()),
                        Component.text("ブロック7個: " + offer.blocksSummary()),
                Component.text("チェストをクリックすると詳細を表示します"))
                .hideExtraTooltip()
                .build();
    }

    private ItemStack createGreenConfirmItem(DeckOffer offer, int number) {
        return ItemStack.builder(Material.GREEN_STAINED_GLASS_PANE)
            .customName(Component.text(offer.name() + " を確定", NamedTextColor.GREEN))
            .lore(Component.text(offer.summary()), Component.text("このデッキを使います"))
                .hideExtraTooltip()
                .build();
    }

    private ItemStack createTowerItem(BuildSelection tower) {
        return ItemStack.builder(tower.material())
                .customName(Component.text(tower.displayName(), NamedTextColor.AQUA))
                .lore(
                Component.text("種類: タワー"),
                Component.text("操作: 右クリックで選択し、もう一度右クリックで設置"))
                .hideExtraTooltip()
                .build();
    }

    private ItemStack createBlockItem(BuildSelection block) {
        return ItemStack.builder(block.material())
                .customName(Component.text(block.displayName(), NamedTextColor.YELLOW))
                .lore(
                Component.text("種類: ブロック"),
                        Component.text("形状配置用アイテム"))
                .hideExtraTooltip()
                .build();
    }

    private void fillMenuBackground(Inventory inventory, Material material, Component blankName) {
        ItemStack filler = ItemStack.builder(material)
                .customName(blankName)
                .hideExtraTooltip()
                .build();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItemStack(slot, filler);
        }
    }

    public void handleInventoryPreClick(InventoryPreClickEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUuid();
        var open = player.getOpenInventory();
        if (open == null) {
            return;
        }

        Inventory selection = deckSelectionMenuByPlayer.get(uuid);
        Inventory detail = deckDetailMenuByPlayer.get(uuid);
        if (open != selection && open != detail) {
            return;
        }

        event.setCancelled(true);
        int slot = event.getSlot();
        if (open == selection) {
            handleSelectionClick(player, slot);
            return;
        }

        handleDetailClick(player, slot);
    }

    public void onBlockInteract(PlayerBlockInteractEvent event) {
        Player player = event.getPlayer();
        Instance instance = player.getInstance();
        if (instance == null) return;

        BlockVec pos = event.getBlockPosition();

        // ロビーのビーコン右クリック → ロードマップテレポート
        if (instance == lobby && pos.equals(lobbyEmeraldButton)) {
            event.setCancelled(true);
            moveToRoadmap(player);
            return;
        }

        // ロードマップのステージ選択
        if (instance == roadmap) {
            RoadNode node = roadmapNodeByCenter.get(pos);
            if (node != null) {
                event.setCancelled(true);
                int nextLayer = getRoadmapClearedLayer(player) + 1;
                if (node.layer() != nextLayer) {
                    player.sendMessage(Component.text("次に進めるのは層 " + nextLayer + " です", NamedTextColor.RED));
                    return;
                }
                enterStage(player, node);
            }
            return;
        }

        if (instance instanceof InstanceContainer container && stageGames.containsKey(container)) {
            handleStageBuildInteract(event, player, container);
        }
    }

    public void onBlockBreak(PlayerBlockBreakEvent event) {
        event.setCancelled(true);
    }

    public void onBlockPlace(PlayerBlockPlaceEvent event) {
        event.setCancelled(true);
    }

    public void tick() {
        particleTickCounter++;

        // ロビーのエメラルド門をくぐったらロードマップへ遷移
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (player.getInstance() != lobby) {
                continue;
            }
            Pos pos = player.getPosition();
            if (Math.abs(pos.x()) <= 1.5 && pos.z() >= LOBBY_GATE_Z - 0.2 && pos.y() >= LOBBY_Y + 1) {
                moveToRoadmap(player);
            }
        }

        // ロードマップ経路は頂点-頂点をパーティクルで表示
        if (particleTickCounter % 8 == 0) {
            showRoadmapPathParticles();
            showRoadmapProgressParticles();
            showStageEnemyPathParticles();
        }

        if (particleTickCounter % 2 == 0) {
            showBuildPreviewParticles();
        }

        for (TDGame game : stageGames.values()) {
            game.tick();
        }

        for (Map.Entry<InstanceContainer, TDGame> entry : stageGames.entrySet()) {
            if (!entry.getValue().consumeWaveCleared()) {
                continue;
            }
            Integer clearedLayer = stageLayerByInstance.get(entry.getKey());
            if (clearedLayer == null) {
                continue;
            }
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (player.getInstance() == entry.getKey()) {
                    roadmapClearedLayerByPlayer.put(player.getUuid(), Math.max(getRoadmapClearedLayer(player), clearedLayer));
                    player.sendMessage(Component.text("層 " + clearedLayer + " クリア！ 次の層が解放されました", NamedTextColor.GOLD));
                }
            }
        }

        // 準備時間カウントダウン
        if (prepTicksRemaining.isEmpty()) {
            return;
        }

        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            UUID uuid = player.getUuid();
            Integer left = prepTicksRemaining.get(uuid);
            if (left == null) continue;

            int next = left - 1;
            if (next <= 0) {
                prepTicksRemaining.remove(uuid);
                StageType stage = prepStageByPlayer.remove(uuid);
                if (stage != null) {
                    player.sendMessage(Component.text("準備時間終了: Wave開始", NamedTextColor.RED));
                }
                continue;
            }

            prepTicksRemaining.put(uuid, next);
            if (next % 20 == 0) {
                int seconds = next / 20;
                player.sendActionBar(Component.text("準備時間: " + seconds + "秒", NamedTextColor.YELLOW));
            }
        }
    }

    private void enterStage(Player player, RoadNode node) {
        InstanceContainer instance = stageInstancesByLayer.get(node.layer());
        if (instance == null) {
            player.sendMessage(Component.text("ステージ生成に失敗しました", NamedTextColor.RED));
            return;
        }

        DeckOffer deck = selectedDeckByPlayer.remove(player.getUuid());
        if (deck == null) {
            player.sendMessage(Component.text("先にロードマップでデッキを選んでください", NamedTextColor.RED));
            showDeckChoice(player);
            return;
        }

        player.setInstance(instance, node.stageType().spawn());
        applyNightVision(player);
        prepTicksRemaining.put(player.getUuid(), 20 * 30);
        prepStageByPlayer.put(player.getUuid(), node.stageType());
        selectedBuildByPlayer.remove(player.getUuid());
        giveDeckItems(player, deck);
        if (node.stageType() == StageType.EVENT) {
            player.sendMessage(Component.text(node.stageType().displayName() + " 層 " + node.layer() + " に到着しました。アイテムを獲得します", NamedTextColor.GOLD));
        } else {
            player.sendMessage(Component.text(node.stageType().displayName() + " 層 " + node.layer() + " に進みました。準備時間30秒", NamedTextColor.GREEN));
        }
        player.sendMessage(Component.text("コマンド: !start / !state / !tower <type>", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("アイテム右クリック: 1回目で選択、同じアイテムをもう一度右クリックでプレビュー位置に設置", NamedTextColor.GRAY));
    }

    private int getRoadmapClearedLayer(Player player) {
        return roadmapClearedLayerByPlayer.getOrDefault(player.getUuid(), 0);
    }

    private void handleStageBuildInteract(PlayerBlockInteractEvent event, Player player, InstanceContainer stageInstance) {
        ItemStack hand = player.getItemInMainHand();
        BuildSelection picked = BuildSelection.fromMaterial(hand.material());
        if (picked == null) {
            return;
        }

        event.setCancelled(true);
        UUID uuid = player.getUuid();
        BuildSelection selected = selectedBuildByPlayer.get(uuid);
        if (selected != picked) {
            selectedBuildByPlayer.put(uuid, picked);
            player.sendMessage(Component.text("選択: " + picked.displayName(), NamedTextColor.AQUA));
            return;
        }

        BlockVec origin = previewOrigin(player);
        if (picked.kind() == BuildKind.BLOCK) {
            boolean ok = placeBlockShape(stageInstance, picked, origin);
            if (ok) {
                consumeOneMainHand(player);
                player.sendMessage(Component.text("配置: " + picked.displayName(), NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("配置失敗: 重複または保護エリアです", NamedTextColor.RED));
            }
            return;
        }

        TDGame game = stageGames.get(stageInstance);
        if (game == null) {
            return;
        }

        game.tryPlaceTowerAt(player, picked.towerType().key(), origin.blockX(), origin.blockZ(), OBSTACLE_Y);
        // タワーは在庫無限（非消費）。ゴールド不足は tryPlaceTowerAt 側で拒否する。
    }

    private BlockVec previewOrigin(Player player) {
        Pos pos = player.getPosition();
        Point dir = pos.direction();
        int x = (int) Math.floor(pos.x() + dir.x() * 4.0);
        int z = (int) Math.floor(pos.z() + dir.z() * 4.0);
        x = Math.max(-27, Math.min(27, x));
        z = Math.max(-27, Math.min(27, z));
        return new BlockVec(x, OBSTACLE_Y, z);
    }

    private boolean placeBlockShape(InstanceContainer instance, BuildSelection shape, BlockVec origin) {
        List<BlockVec> targets = new ArrayList<>();
        for (int[] offset : shape.previewOffsets()) {
            int x = origin.blockX() + offset[0];
            int z = origin.blockZ() + offset[1];
            if (isProtectedStageCell(x, z)) {
                return false;
            }
            if (Math.abs(x) > 27 || Math.abs(z) > 27) {
                return false;
            }
            if (instance.getBlock(x, OBSTACLE_Y, z) != Block.AIR) {
                return false;
            }
            targets.add(new BlockVec(x, OBSTACLE_Y, z));
        }

        for (BlockVec target : targets) {
            instance.setBlock(target, shape.blockMaterial());
        }
        return true;
    }

    private void consumeOneMainHand(Player player) {
        ItemStack hand = player.getItemInMainHand();
        if (hand.isAir()) {
            return;
        }
        int amount = hand.amount();
        if (amount <= 1) {
            player.setItemInMainHand(ItemStack.AIR);
            return;
        }
        player.setItemInMainHand(hand.withAmount(amount - 1));
    }

    private void giveDeckItems(Player player, DeckOffer deck) {
        clearBuildSlots(player);

        int slot = 0;
        for (BuildSelection towerSelection : deck.towers()) {
            player.getInventory().setItemStack(slot++, ItemStack.of(towerSelection.material(), 1));
        }
        for (BuildSelection blockSelection : deck.blocks()) {
            player.getInventory().setItemStack(slot++, ItemStack.of(blockSelection.material(), 1));
        }

        player.getInventory().setItemStack(17, ItemStack.builder(Material.PAPER)
                .customName(Component.text("使い方"))
                .lore(
                        Component.text("- アイテムを持って右クリックで選択"),
                        Component.text("- もう一度右クリックで配置"),
                        Component.text("- タワーは卵、ブロックは形状アイテム"))
                .hideExtraTooltip()
                .build());
    }

    private void clearBuildSlots(Player player) {
        player.getInventory().clear();
    }

    private DeckOffer generateDeck(String name) {
        List<BuildSelection> towerPool = new ArrayList<>(List.of(
                BuildSelection.TOWER_BASIC,
                BuildSelection.TOWER_FLAME,
                BuildSelection.TOWER_FROST,
                BuildSelection.TOWER_BALL,
                BuildSelection.TOWER_POISON,
                BuildSelection.TOWER_SNOWBALL));
        List<BuildSelection> towers = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (towers.size() < 3 && !towerPool.isEmpty()) {
            int index = random.nextInt(towerPool.size());
            towers.add(towerPool.remove(index));
        }

        List<BuildSelection> blockPool = List.of(
                BuildSelection.BLOCK_L,
                BuildSelection.BLOCK_L_REV,
                BuildSelection.BLOCK_T,
                BuildSelection.BLOCK_SQUARE,
                BuildSelection.BLOCK_CROSS,
                BuildSelection.BLOCK_LINE);
        List<BuildSelection> blocks = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            blocks.add(blockPool.get(random.nextInt(blockPool.size())));
        }

        return new DeckOffer(name, towers, blocks);
    }

    private void decorateLobby() {
        // 床: チェス柄
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                Block block = ((x + z) % 2 == 0) ? Block.WHITE_CONCRETE : Block.BLACK_CONCRETE;
                lobby.setBlock(x, LOBBY_Y, z, block);
                if (x % 6 == 0 && z % 6 == 0) {
                    lobby.setBlock(x, LOBBY_Y + 1, z, Block.SEA_LANTERN);
                }
            }
        }

        // 周囲ライン（装飾）
        for (int x = -32; x <= 32; x++) {
            lobby.setBlock(x, LOBBY_Y, -32, Block.SMOOTH_QUARTZ);
            lobby.setBlock(x, LOBBY_Y, 32, Block.SMOOTH_QUARTZ);
        }
        for (int z = -30; z <= 30; z++) {
            lobby.setBlock(-32, LOBBY_Y, z, Block.SMOOTH_QUARTZ);
            lobby.setBlock(32, LOBBY_Y, z, Block.SMOOTH_QUARTZ);
        }

        // 柱と照明
        for (int x = -24; x <= 24; x += 12) {
            for (int z = -24; z <= 24; z += 12) {
                placePillar(lobby, x, LOBBY_Y + 1, z);
            }
        }

        // 中央エメラルドボタン
        lobby.setBlock(0, LOBBY_Y, 0, Block.EMERALD_BLOCK);
        lobby.setBlock(0, LOBBY_Y + 1, 0, Block.EMERALD_BLOCK);
        lobby.setBlock(-1, LOBBY_Y, -1, Block.IRON_BLOCK);
        lobby.setBlock(1, LOBBY_Y, -1, Block.IRON_BLOCK);
        lobby.setBlock(-1, LOBBY_Y, 1, Block.IRON_BLOCK);
        lobby.setBlock(1, LOBBY_Y, 1, Block.IRON_BLOCK);

        // エメラルド門（ロビー側のみ残す）
        for (int y = LOBBY_Y + 1; y <= LOBBY_Y + 4; y++) {
            lobby.setBlock(-2, y, LOBBY_GATE_Z, Block.EMERALD_BLOCK);
            lobby.setBlock(2, y, LOBBY_GATE_Z, Block.EMERALD_BLOCK);
        }
        for (int x = -2; x <= 2; x++) {
            lobby.setBlock(x, LOBBY_Y + 4, LOBBY_GATE_Z, Block.EMERALD_BLOCK);
        }
        lobby.setBlock(-1, LOBBY_Y + 1, LOBBY_GATE_Z, Block.LANTERN);
        lobby.setBlock(1, LOBBY_Y + 1, LOBBY_GATE_Z, Block.LANTERN);

        // 装飾: ランタン、カーペット、看板など
        for (int i = 0; i < 8; i++) {
            int angle = i * 45;
            double rad = Math.toRadians(angle);
            int x = (int) (Math.cos(rad) * 18);
            int z = (int) (Math.sin(rad) * 18);
            lobby.setBlock(x, LOBBY_Y + 2, z, Block.LANTERN);
        }

        // ランダムに装飾ブロック
        lobby.setBlock(-20, LOBBY_Y + 1, -20, Block.SHROOMLIGHT);
        lobby.setBlock(20, LOBBY_Y + 1, -20, Block.SHROOMLIGHT);
        lobby.setBlock(-20, LOBBY_Y + 1, 20, Block.SHROOMLIGHT);
        lobby.setBlock(20, LOBBY_Y + 1, 20, Block.SHROOMLIGHT);
    }

    private void buildRoadmap() {
        roadmapNodeByCenter.clear();
        roadmapPathPeaks.clear();

        // 既存のロードマップ面を消してから再構築する
        fillRect(roadmap, -10, ROADMAP_Y, -18, 50, ROADMAP_Y + 6, 18, Block.AIR);

        // スタート地点
        fillRect(roadmap, -2, ROADMAP_Y, -2, 2, ROADMAP_Y, 2, Block.DARK_OAK_PLANKS);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockVec previousPeak = new BlockVec(0, ROADMAP_Y + 1, 0);

        for (int layer = 1; layer <= 8; layer++) {
            int x = 8 + (layer - 1) * 6 + random.nextInt(0, 3);
            int z = switch (layer % 3) {
                case 1 -> -8 + random.nextInt(-2, 3);
                case 2 -> random.nextInt(-3, 4);
                default -> 8 + random.nextInt(-2, 3);
            };
            StageType stageType = themeForLayer(layer);
            RoadNode node = new RoadNode(new BlockVec(x, ROADMAP_Y, z), layer, stageType);
            placeRoadmapNode(node);
            registerParticlePath(previousPeak, peakOf(node.center()));
            previousPeak = peakOf(node.center());
        }
        
        // ロードマップ周辺の装飾
        decorateRoadmapPerimeter();
    }

    private void registerParticlePath(BlockVec fromPeak, BlockVec toPeak) {
        roadmapPathPeaks.add(new PathSegment(fromPeak, toPeak));
    }

    private StageType themeForLayer(int layer) {
        return switch (layer) {
            case 8 -> StageType.BOSS;
            case 2, 5 -> StageType.ELITE;
            case 3, 6 -> StageType.EVENT;
            default -> StageType.NORMAL;
        };
    }

    private void decorateRoadmapPerimeter() {
        // 周辺フレーム強調
        for (int x = -10; x <= 50; x++) {
            roadmap.setBlock(x, ROADMAP_Y, -18, Block.BLACKSTONE);
            roadmap.setBlock(x, ROADMAP_Y + 1, -18, Block.LANTERN);
            roadmap.setBlock(x, ROADMAP_Y, 18, Block.BLACKSTONE);
            roadmap.setBlock(x, ROADMAP_Y + 1, 18, Block.LANTERN);
        }
        for (int z = -16; z <= 16; z++) {
            roadmap.setBlock(-10, ROADMAP_Y, z, Block.BLACKSTONE);
            roadmap.setBlock(-10, ROADMAP_Y + 1, z, Block.LANTERN);
            roadmap.setBlock(50, ROADMAP_Y, z, Block.BLACKSTONE);
            roadmap.setBlock(50, ROADMAP_Y + 1, z, Block.LANTERN);
        }
    }

    private void connectLayers(List<RoadNode> fromNodes, List<RoadNode> toNodes, int minChoices, int maxChoices) {
        if (fromNodes.isEmpty() || toNodes.isEmpty()) {
            return;
        }

        List<Set<Integer>> chosenBySource = new ArrayList<>();
        for (int i = 0; i < fromNodes.size(); i++) {
            chosenBySource.add(new HashSet<>());
        }

        // まず各ターゲットが最低1本は到達可能になるよう接続
        for (int target = 0; target < toNodes.size(); target++) {
            int src = ThreadLocalRandom.current().nextInt(fromNodes.size());
            chosenBySource.get(src).add(target);
        }

        int maxAllowed = Math.min(maxChoices, toNodes.size());
        for (int src = 0; src < fromNodes.size(); src++) {
            Set<Integer> chosen = chosenBySource.get(src);
            int desired = ThreadLocalRandom.current().nextInt(minChoices, maxAllowed + 1);
            while (chosen.size() < desired) {
                chosen.add(ThreadLocalRandom.current().nextInt(toNodes.size()));
            }
        }

        for (int src = 0; src < fromNodes.size(); src++) {
            for (int target : chosenBySource.get(src)) {
                registerParticlePath(peakOf(fromNodes.get(src).center()), peakOf(toNodes.get(target).center()));
            }
        }
    }

    private BlockVec peakOf(BlockVec center) {
        return new BlockVec(center.blockX(), center.blockY() + 1, center.blockZ());
    }

    private void showRoadmapPathParticles() {
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (player.getInstance() != roadmap) {
                continue;
            }
            for (PathSegment segment : roadmapPathPeaks) {
                int sx = segment.from().blockX();
                int sy = segment.from().blockY();
                int sz = segment.from().blockZ();
                int ex = segment.to().blockX();
                int ey = segment.to().blockY();
                int ez = segment.to().blockZ();
                int steps = Math.max(Math.abs(ex - sx), Math.abs(ez - sz)) * 2;
                if (steps <= 0) {
                    continue;
                }
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / (double) steps;
                    double x = sx + (ex - sx) * t + 0.5;
                    double y = sy + (ey - sy) * t + 0.25;
                    double z = sz + (ez - sz) * t + 0.5;
                    player.sendPacket(new ParticlePacket(Particle.DUST, true, false, x, y, z, 1f, 0.08f, 0.08f, 1.8f, 0));
                }
            }
        }
    }

    private void showRoadmapProgressParticles() {
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (player.getInstance() != roadmap) {
                continue;
            }

            int clearedLayer = getRoadmapClearedLayer(player);
            if (clearedLayer >= roadmapPathPeaks.size()) {
                continue;
            }

            PathSegment segment = roadmapPathPeaks.get(clearedLayer);
            int sx = segment.from().blockX();
            int sy = segment.from().blockY();
            int sz = segment.from().blockZ();
            int ex = segment.to().blockX();
            int ey = segment.to().blockY();
            int ez = segment.to().blockZ();
            int steps = Math.max(Math.abs(ex - sx), Math.abs(ez - sz)) * 2;
            if (steps <= 0) {
                continue;
            }
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / (double) steps;
                double x = sx + (ex - sx) * t + 0.5;
                double y = sy + (ey - sy) * t + 0.25;
                double z = sz + (ez - sz) * t + 0.5;
                player.sendPacket(new ParticlePacket(Particle.DUST, true, false, x, y, z, 1f, 1f, 0.1f, 1.8f, 0));
            }
        }
    }

    private void showStageEnemyPathParticles() {
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance instance = player.getInstance();
            if (!(instance instanceof InstanceContainer stageInstance)) {
                continue;
            }
            if (!stageGames.containsKey(stageInstance)) {
                continue;
            }

            BlockVec start = new BlockVec(-20, OBSTACLE_Y, -20);
            BlockVec goal = new BlockVec(0, OBSTACLE_Y, 0);
            List<BlockVec> path = findPathAStar(stageInstance, start, goal);
            for (BlockVec node : path) {
                player.sendPacket(new ParticlePacket(
                    Particle.DUST,
                    true,
                    false,
                    node.blockX() + 0.5,
                    node.blockY() + 0.15,
                    node.blockZ() + 0.5,
                    1f,
                    0f,
                    0f,
                    1f,
                    0
                ));
            }
        }
    }

    private void showBuildPreviewParticles() {
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance instance = player.getInstance();
            if (!(instance instanceof InstanceContainer stageInstance) || !stageGames.containsKey(stageInstance)) {
                continue;
            }

            BuildSelection selected = selectedBuildByPlayer.get(player.getUuid());
            if (selected == null) {
                continue;
            }

            BlockVec origin = previewOrigin(player);
            for (int[] offset : selected.previewOffsets()) {
                int x = origin.blockX() + offset[0];
                int z = origin.blockZ() + offset[1];
                player.sendPacket(new ParticlePacket(
                        Particle.END_ROD,
                        true,
                        false,
                        x + 0.5,
                        OBSTACLE_Y + 0.2,
                        z + 0.5,
                        0f,
                        0f,
                        0f,
                        0f,
                        1));
            }
        }
    }

    private List<BlockVec> findPathAStar(InstanceContainer instance, BlockVec start, BlockVec goal) {
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::f));
        Map<XZ, Double> bestScore = new HashMap<>();
        Set<XZ> closed = new HashSet<>();

        XZ startKey = new XZ(start.blockX(), start.blockZ());
        XZ goalKey = new XZ(goal.blockX(), goal.blockZ());
        open.add(new PathNode(startKey, 0.0, heuristic(startKey, goalKey), null));
        bestScore.put(startKey, 0.0);

        while (!open.isEmpty()) {
            PathNode current = open.poll();
            if (current.pos().equals(goalKey)) {
                return reconstructPath(current, start.blockY());
            }
            if (!closed.add(current.pos())) {
                continue;
            }

            for (int[] dir : DIRECTIONS_8) {
                XZ next = new XZ(current.pos().x() + dir[0], current.pos().z() + dir[1]);
                if (closed.contains(next)) {
                    continue;
                }
                if (!isTraversable(instance, next, startKey, goalKey)) {
                    continue;
                }

                boolean diagonal = dir[0] != 0 && dir[1] != 0;
                double nextG = current.g() + (diagonal ? DIAGONAL_COST : 1.0);
                double known = bestScore.getOrDefault(next, Double.POSITIVE_INFINITY);
                if (nextG >= known) {
                    continue;
                }

                bestScore.put(next, nextG);
                double f = nextG + heuristic(next, goalKey);
                open.add(new PathNode(next, nextG, f, current));
            }
        }

        // 到達不能時は直線でフォールバック
        return straightLinePath(start, goal);
    }

    private boolean isTraversable(InstanceContainer instance, XZ pos, XZ start, XZ goal) {
        if (Math.abs(pos.x()) > 27 || Math.abs(pos.z()) > 27) {
            return false;
        }
        if (pos.equals(start) || pos.equals(goal)) {
            return true;
        }

        Block headBlock = instance.getBlock(pos.x(), OBSTACLE_Y, pos.z());
        return headBlock == Block.AIR;
    }

    private double heuristic(XZ from, XZ to) {
        int dx = Math.abs(from.x() - to.x());
        int dz = Math.abs(from.z() - to.z());
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return (max - min) + (min * DIAGONAL_COST);
    }

    private List<BlockVec> reconstructPath(PathNode end, int y) {
        List<BlockVec> out = new ArrayList<>();
        PathNode node = end;
        while (node != null) {
            out.add(0, new BlockVec(node.pos().x(), y, node.pos().z()));
            node = node.parent();
        }
        return out;
    }

    private List<BlockVec> straightLinePath(BlockVec from, BlockVec to) {
        List<BlockVec> out = new ArrayList<>();
        int sx = from.blockX();
        int sz = from.blockZ();
        int ex = to.blockX();
        int ez = to.blockZ();
        int steps = Math.max(Math.abs(ex - sx), Math.abs(ez - sz));
        if (steps <= 0) {
            out.add(from);
            return out;
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            int x = (int) Math.round(sx + (ex - sx) * t);
            int z = (int) Math.round(sz + (ez - sz) * t);
            out.add(new BlockVec(x, from.blockY(), z));
        }
        return out;
    }

    private static final double DIAGONAL_COST = 1.41421356237;

    private static final int[][] DIRECTIONS_8 = new int[][] {
        new int[] {1, 0},
        new int[] {-1, 0},
        new int[] {0, 1},
        new int[] {0, -1},
        new int[] {1, 1},
        new int[] {1, -1},
        new int[] {-1, 1},
        new int[] {-1, -1}
    };

    private StageType randomStageType() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 55) {
            return StageType.NORMAL;
        }
        if (roll < 85) {
            return StageType.ELITE;
        }
        return StageType.EVENT;
    }

    private void placeRoadmapNode(RoadNode node) {
        BlockVec center = node.center();
        StageType stageType = node.stageType();
        // ノードの基盤（3x3）
        Block tileBlock = switch (stageType) {
            case NORMAL -> Block.MOSS_BLOCK;
            case ELITE -> Block.RED_TERRACOTTA;
            case EVENT -> Block.AMETHYST_BLOCK;
            case BOSS -> Block.OBSIDIAN;
        };
        
        // 3x3 の基盤を配置
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = center.blockX() + dx;
                int z = center.blockZ() + dz;
                roadmap.setBlock(x, center.blockY(), z, tileBlock);
            }
        }

        // 段差は頂点のみ
        BlockVec peak = new BlockVec(center.blockX(), center.blockY() + 1, center.blockZ());
        roadmap.setBlock(peak, Block.GOLD_BLOCK);
        roadmapNodeByCenter.put(peak, node);
    }

    private void createStageInstances() {
        stageGames.clear();
        stageInstancesByLayer.clear();
        stageLayerByInstance.clear();
        for (int layer = 1; layer <= 8; layer++) {
            InstanceContainer instance = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
            StageType stage = themeForLayer(layer);
            Block floor = switch (stage) {
                case NORMAL -> Block.GRASS_BLOCK;
                case ELITE -> Block.RED_SAND;
                case EVENT -> Block.SNOW_BLOCK;
                case BOSS -> Block.BLACKSTONE;
            };

            instance.setGenerator(flatGenerator(floor));
            decorateStage(instance, stage);
            stageInstancesByLayer.put(layer, instance);
            stageLayerByInstance.put(instance, layer);

            List<BlockVec> pathBlocks = findPathAStar(
                    instance,
                    new BlockVec(-20, OBSTACLE_Y, -20),
                    new BlockVec(0, OBSTACLE_Y, 0));
            stageGames.put(instance, new TDGame(instance, toCenterPath(pathBlocks)));
        }
    }

    private TDGame gameOf(Player player) {
        Instance instance = player.getInstance();
        if (!(instance instanceof InstanceContainer container)) {
            return null;
        }
        return stageGames.get(container);
    }

    private List<Point> toCenterPath(List<BlockVec> pathBlocks) {
        List<Point> path = new ArrayList<>(pathBlocks.size());
        for (BlockVec node : pathBlocks) {
            path.add(new Pos(node.blockX() + 0.5, node.blockY(), node.blockZ() + 0.5));
        }
        return path;
    }

    private void decorateStage(InstanceContainer instance, StageType stage) {
        // マップ背景
        fillRect(instance, -30, STAGE_Y, -30, 30, STAGE_Y, 30, stage.floorAccent());
        if (stage == StageType.NORMAL) {
            // 草原ステージは床を全面草ブロックで統一する
            fillRect(instance, -30, STAGE_Y, -30, 30, STAGE_Y, 30, Block.GRASS_BLOCK);
        }

        // マップ端を強調（暗いフレーム）
        for (int x = -30; x <= 30; x++) {
            instance.setBlock(x, STAGE_Y, -30, Block.BLACKSTONE);
            instance.setBlock(x, STAGE_Y + 1, -30, Block.LANTERN);
            instance.setBlock(x, STAGE_Y, 30, Block.BLACKSTONE);
            instance.setBlock(x, STAGE_Y + 1, 30, Block.LANTERN);
        }
        for (int z = -28; z <= 28; z++) {
            instance.setBlock(-30, STAGE_Y, z, Block.BLACKSTONE);
            instance.setBlock(-30, STAGE_Y + 1, z, Block.LANTERN);
            instance.setBlock(30, STAGE_Y, z, Block.BLACKSTONE);
            instance.setBlock(30, STAGE_Y + 1, z, Block.LANTERN);
        }

        // 敵ゴール地点（床は通常地形のまま、目印のみ配置）
        instance.setBlock(0, STAGE_Y + 1, 0, Block.BEACON);

        // RED スポーン位置（敵が出現する 3×3）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                instance.setBlock(-20 + dx, STAGE_Y, -20 + dz, Block.REDSTONE_BLOCK);
            }
        }
        instance.setBlock(-20, STAGE_Y + 1, -20, Block.REDSTONE_TORCH);

        // CAMPFIRE タワー配置位置（3×3）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                instance.setBlock(18 + dx, STAGE_Y, 18 + dz, Block.CAMPFIRE);
            }
        }

        // 障害物配置：敵が一直線で進めないように
        placeObstacles(instance, stage);

        switch (stage) {
            case NORMAL -> {
                // NORMAL ステージ専用装飾
                placeTreeLike(instance, -5, STAGE_Y + 1, 0);
                placeTreeLike(instance, 5, STAGE_Y + 1, -8);
                placeTreeLike(instance, 8, STAGE_Y + 1, 8);
            }
            case ELITE -> {
                // ELITE ステージ専用装飾
                fillRect(instance, -10, STAGE_Y + 1, -5, -5, STAGE_Y + 2, 0, Block.SMOOTH_SANDSTONE);
                fillRect(instance, 8, STAGE_Y + 1, 10, 15, STAGE_Y + 2, 15, Block.CUT_SANDSTONE);
            }
            case EVENT -> {
                // イベントマス：宝箱装飾のみ（敵なし）
                placeEventTreasure(instance, -10, STAGE_Y + 1, 0);
                placeEventTreasure(instance, 12, STAGE_Y + 1, 10);
            }
            case BOSS -> {
                // BOSS ステージの大型障害物
                fillRect(instance, -15, STAGE_Y, -15, -8, STAGE_Y + 4, -8, Block.POLISHED_BLACKSTONE_BRICKS);
                fillRect(instance, 8, STAGE_Y, 8, 15, STAGE_Y + 4, 15, Block.OBSIDIAN);
            }
        }
    }

    private void placeObstacles(InstanceContainer instance, StageType stage) {
        // 位置・連続数・折れ曲がりを毎回ランダム化し、密度は低めに維持する
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<BlockVec> used = new HashSet<>();

        // 中央付近にも必ず障害物を置く（ゴールマスは空ける）
        if (stage != StageType.EVENT) {
            placeCentralObstacles(instance, used, stage, random);
        }

        switch (stage) {
            case NORMAL -> {
                Block[] palette = new Block[]{
                    Block.OAK_LOG,
                    Block.COBBLESTONE,
                    Block.MOSSY_COBBLESTONE,
                    Block.STONE,
                    Block.STONE_BRICKS
                };
                placeRandomPolylineObstacles(instance, used, palette, 30, 3, 8, 0.38, random);
            }
            case ELITE -> {
                Block[] palette = new Block[]{
                    Block.SMOOTH_SANDSTONE,
                    Block.CUT_SANDSTONE,
                    Block.SANDSTONE,
                    Block.CHISELED_SANDSTONE
                };
                placeRandomPolylineObstacles(instance, used, palette, 24, 3, 7, 0.28, random);
            }
            case EVENT -> {
                // イベントマスは移動しやすさを優先して障害物なし
            }
            case BOSS -> {
                Block[] palette = new Block[]{
                    Block.POLISHED_BLACKSTONE_BRICKS,
                    Block.OBSIDIAN,
                    Block.BLACKSTONE,
                    Block.CRACKED_POLISHED_BLACKSTONE_BRICKS
                };
                placeRandomPolylineObstacles(instance, used, palette, 24, 4, 8, 0.45, random);
            }
        }
    }

    private void placeRandomPolylineObstacles(
        InstanceContainer instance,
        Set<BlockVec> used,
        Block[] palette,
        int obstacleCount,
        int minLength,
        int maxLength,
        double bendChance,
        ThreadLocalRandom random
    ) {
        for (int i = 0; i < obstacleCount; i++) {
            int startX = random.nextInt(-16, 17);
            int startZ = random.nextInt(-16, 17);
            if (isProtectedStageCell(startX, startZ)) {
                i--;
                continue;
            }

            int length = random.nextInt(minLength, maxLength + 1);
            int dir = random.nextInt(4);
            int x = startX;
            int z = startZ;
            Block block = palette[random.nextInt(palette.length)];

            for (int step = 0; step < length; step++) {
                if (!placeObstacleBlock(instance, used, x, z, block)) {
                    break;
                }

                if (random.nextDouble() < bendChance) {
                    int turn = random.nextBoolean() ? 1 : 3;
                    dir = (dir + turn) % 4;
                }

                switch (dir) {
                    case 0 -> x++;
                    case 1 -> z++;
                    case 2 -> x--;
                    default -> z--;
                }
            }
        }
    }

    private boolean placeObstacleBlock(InstanceContainer instance, Set<BlockVec> used, int x, int z, Block block) {
        if (isProtectedStageCell(x, z)) {
            return false;
        }
        BlockVec pos = new BlockVec(x, OBSTACLE_Y, z);
        if (!used.add(pos)) {
            return false;
        }
        instance.setBlock(pos, block);
        return true;
    }

    private boolean isProtectedStageCell(int x, int z) {
        // 主要エリア（ゴール、敵スポーン、タワー配置）には障害物を置かない
        if (Math.abs(x) <= 1 && Math.abs(z) <= 1) return true;
        if (Math.abs(x + 20) <= 3 && Math.abs(z + 20) <= 3) return true;
        if (Math.abs(x - 18) <= 3 && Math.abs(z - 18) <= 3) return true;
        // 外周ランタンと干渉しないよう内側に限定
        return Math.abs(x) >= 28 || Math.abs(z) >= 28;
    }

    private void placeCentralObstacles(InstanceContainer instance, Set<BlockVec> used, StageType stage, ThreadLocalRandom random) {
        Block[] centerPalette = switch (stage) {
            case NORMAL -> new Block[]{Block.COBBLESTONE, Block.MOSSY_COBBLESTONE, Block.OAK_LOG};
            case ELITE -> new Block[]{Block.SANDSTONE, Block.CUT_SANDSTONE, Block.SMOOTH_SANDSTONE};
            case BOSS -> new Block[]{Block.BLACKSTONE, Block.POLISHED_BLACKSTONE_BRICKS, Block.OBSIDIAN};
            case EVENT -> new Block[]{Block.AIR};
        };

        // ゴール(0,0)の周囲リングに4-6個配置して中央の変化を分かりやすくする
        int[][] ring = new int[][] {
            {-2, 0}, {2, 0}, {0, -2}, {0, 2},
            {-2, -1}, {-2, 1}, {2, -1}, {2, 1},
            {-1, -2}, {1, -2}, {-1, 2}, {1, 2}
        };

        int placements = 0;
        for (int[] p : ring) {
            if (placements >= 6) break;
            if (random.nextDouble() < 0.5) {
                Block b = centerPalette[random.nextInt(centerPalette.length)];
                if (placeObstacleBlock(instance, used, p[0], p[1], b)) {
                    placements++;
                }
            }
        }

        // 運悪く少なすぎる場合の最低保証
        for (int i = 0; i < ring.length && placements < 4; i++) {
            int[] p = ring[i];
            Block b = centerPalette[random.nextInt(centerPalette.length)];
            if (placeObstacleBlock(instance, used, p[0], p[1], b)) {
                placements++;
            }
        }
    }

    private void placeEventTreasure(InstanceContainer instance, int x, int y, int z) {
        // 宝箱のような構造（GOLD_BLOCK + LANTERN + 装飾）
        instance.setBlock(x, y, z, Block.NETHER_GOLD_ORE);
        instance.setBlock(x, y + 1, z, Block.LANTERN);
        instance.setBlock(x - 1, y, z, Block.END_ROD);
        instance.setBlock(x + 1, y, z, Block.END_ROD);
        instance.setBlock(x, y, z - 1, Block.END_ROD);
        instance.setBlock(x, y, z + 1, Block.END_ROD);
    }

    private void placeTreeLike(InstanceContainer instance, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            instance.setBlock(x, y + i, z, Block.OAK_LOG);
        }
        fillRect(instance, x - 2, y + 4, z - 2, x + 2, y + 5, z + 2, Block.OAK_LEAVES);
    }

    private Generator flatGenerator(Block floor) {
        return unit -> {
            unit.modifier().fillHeight(0, STAGE_Y - 1, Block.STONE);
            unit.modifier().fillHeight(STAGE_Y - 1, STAGE_Y, floor);
        };
    }

    private void placePillar(InstanceContainer instance, int x, int y, int z) {
        for (int i = 0; i < 5; i++) {
            instance.setBlock(x, y + i, z, Block.QUARTZ_PILLAR);
        }
        instance.setBlock(x, y + 5, z, Block.SEA_LANTERN);
    }

    private void applyNightVision(Player player) {
        player.addEffect(new Potion(PotionEffect.NIGHT_VISION, (byte) 0, NIGHT_VISION_DURATION_TICKS));
    }

    private void fillRect(InstanceContainer instance, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    instance.setBlock(x, y, z, block);
                }
            }
        }
    }

    private enum StageType {
        NORMAL("通常", new Pos(0.5, STAGE_Y + 1, 0.5), Block.GRASS_BLOCK),
        ELITE("精鋭", new Pos(0.5, STAGE_Y + 1, 0.5), Block.RED_SAND),
        EVENT("イベント", new Pos(0.5, STAGE_Y + 1, 0.5), Block.SNOW_BLOCK),
        BOSS("ボス", new Pos(0.5, STAGE_Y + 1, 0.5), Block.BLACKSTONE);

        private final String displayName;
        private final Pos spawn;
        private final Block floorAccent;

        StageType(String displayName, Pos spawn, Block floorAccent) {
            this.displayName = displayName;
            this.spawn = spawn;
            this.floorAccent = floorAccent;
        }

        public String displayName() {
            return displayName;
        }

        public Pos spawn() {
            return spawn;
        }

        public Block floorAccent() {
            return floorAccent;
        }
    }

    private record RoadNode(BlockVec center, int layer, StageType stageType) {
    }

    private record PathSegment(BlockVec from, BlockVec to) {
    }

    private record XZ(int x, int z) {
    }

    private record PathNode(XZ pos, double g, double f, PathNode parent) {
    }

    private enum BuildKind {
        BLOCK,
        TOWER
    }

    private enum BuildSelection {
        BLOCK_L("L字ブロック", BuildKind.BLOCK, Material.OAK_PLANKS, Block.OAK_PLANKS, null, new int[][] {{0, 0}, {1, 0}, {0, 1}}),
        BLOCK_L_REV("逆L字ブロック", BuildKind.BLOCK, Material.SPRUCE_PLANKS, Block.SPRUCE_PLANKS, null, new int[][] {{0, 0}, {-1, 0}, {0, 1}}),
        BLOCK_T("T字ブロック", BuildKind.BLOCK, Material.BIRCH_PLANKS, Block.BIRCH_PLANKS, null, new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, 1}}),
        BLOCK_SQUARE("正方形ブロック", BuildKind.BLOCK, Material.STONE_BRICKS, Block.STONE_BRICKS, null, new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}),
        BLOCK_CROSS("十字ブロック", BuildKind.BLOCK, Material.MOSSY_STONE_BRICKS, Block.MOSSY_STONE_BRICKS, null, new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}),
        BLOCK_LINE("直線ブロック", BuildKind.BLOCK, Material.DEEPSLATE_BRICKS, Block.DEEPSLATE_BRICKS, null, new int[][] {{0, 0}, {1, 0}, {2, 0}, {3, 0}}),

        TOWER_BASIC("基本タワー", BuildKind.TOWER, Material.SKELETON_SPAWN_EGG, null, TowerType.BASIC, rectangleOffsets(TowerType.BASIC)),
        TOWER_FLAME("火炎放射タワー", BuildKind.TOWER, Material.BLAZE_SPAWN_EGG, null, TowerType.FLAMETHROWER, rectangleOffsets(TowerType.FLAMETHROWER)),
        TOWER_FROST("フロストタワー", BuildKind.TOWER, Material.SNOW_GOLEM_SPAWN_EGG, null, TowerType.FROST, rectangleOffsets(TowerType.FROST)),
        TOWER_BALL("雷球タワー", BuildKind.TOWER, Material.VEX_SPAWN_EGG, null, TowerType.LIGHTNING_BALL, rectangleOffsets(TowerType.LIGHTNING_BALL)),
        TOWER_POISON("毒タワー", BuildKind.TOWER, Material.CAVE_SPIDER_SPAWN_EGG, null, TowerType.POISON, rectangleOffsets(TowerType.POISON)),
        TOWER_SNOWBALL("スノーボールタワー", BuildKind.TOWER, Material.IRON_GOLEM_SPAWN_EGG, null, TowerType.SNOWBALL, rectangleOffsets(TowerType.SNOWBALL));

        private final String displayName;
        private final BuildKind kind;
        private final Material material;
        private final Block blockMaterial;
        private final TowerType towerType;
        private final int[][] previewOffsets;

        BuildSelection(String displayName, BuildKind kind, Material material, Block blockMaterial, TowerType towerType, int[][] previewOffsets) {
            this.displayName = displayName;
            this.kind = kind;
            this.material = material;
            this.blockMaterial = blockMaterial;
            this.towerType = towerType;
            this.previewOffsets = previewOffsets;
        }

        public String displayName() {
            return displayName;
        }

        public BuildKind kind() {
            return kind;
        }

        public Block blockMaterial() {
            return blockMaterial;
        }

        public Material material() {
            return material;
        }

        public TowerType towerType() {
            return towerType;
        }

        public int[][] previewOffsets() {
            return previewOffsets;
        }

        public static BuildSelection fromMaterial(Material material) {
            for (BuildSelection selection : values()) {
                if (selection.material == material) {
                    return selection;
                }
            }
            return null;
        }

        private static int[][] rectangleOffsets(TowerType type) {
            int[][] out = new int[type.sizeX() * type.sizeZ()][2];
            int i = 0;
            for (int dx = 0; dx < type.sizeX(); dx++) {
                for (int dz = 0; dz < type.sizeZ(); dz++) {
                    out[i++] = new int[] {dx, dz};
                }
            }
            return out;
        }
    }

    private record DeckOffer(String name, List<BuildSelection> towers, List<BuildSelection> blocks) {
        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append("塔: ");
            for (int i = 0; i < towers.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(towers.get(i).displayName());
            }
            out.append(" / ブロック: ");
            Map<BuildSelection, Integer> counts = new java.util.LinkedHashMap<>();
            for (BuildSelection block : blocks) {
                counts.merge(block, 1, Integer::sum);
            }
            boolean first = true;
            for (Map.Entry<BuildSelection, Integer> entry : counts.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(entry.getKey().displayName()).append(" x").append(entry.getValue());
            }
            return out.toString();
        }

        public String towersSummary() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < towers.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(towers.get(i).displayName());
            }
            return out.toString();
        }

        public String blocksSummary() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(blocks.get(i).displayName());
            }
            return out.toString();
        }
    }
}
