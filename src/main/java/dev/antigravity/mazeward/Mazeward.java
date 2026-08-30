package dev.antigravity.mazeward;

import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Shapes;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.Relic;
import dev.antigravity.mazeward.run.Roadmap;
import dev.antigravity.mazeward.run.Rune;
import dev.antigravity.mazeward.run.RunState;
import dev.antigravity.mazeward.stage.Stage;
import dev.antigravity.mazeward.stage.StageGenerator;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.ui.Hud;
import dev.antigravity.mazeward.ui.Menus;
import dev.antigravity.mazeward.ui.PlayerSession;
import dev.antigravity.mazeward.ui.VersusHud;
import dev.antigravity.mazeward.versus.MirrorBot;
import dev.antigravity.mazeward.versus.WaitingRoom;
import dev.antigravity.mazeward.versus.VersusMatch;
import dev.antigravity.mazeward.versus.VersusPlayer;
import dev.antigravity.mazeward.world.Overlay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.world.DimensionType;

/**
 * ゲーム全体のコントローラ。ロビー → ロードマップ → ステージ → 報酬 の遷移と、
 * プレイヤー入力の振り分けを担当する。
 */
public final class Mazeward implements Stage.Listener {

    private static final int LOBBY_Y = 64;
    private static final int ROAD_Y = 64;
    /** ノード同士の横の間隔。 */
    private static final int NODE_SPACING = 10;

    /** 層と層の奥行き。 */
    private static final int LAYER_SPACING = 14;

    /** 開始台から第 1 層までの距離。 */
    private static final int FIRST_LAYER_Z = 14;

    private static final int PAD_RADIUS = 2;

    /** 実際に通ってきた道の色。 */
    private static final Color TAKEN_COLOR = new Color(255, 205, 60);

    /** これから通れない道の色。 */
    private static final Color DIM_COLOR = new Color(150, 156, 170);

    /** 意味のある道（通ってきた道・いま進める道）の粒の間隔。詰めるほど濃く見える。 */
    private static final double ROAD_SPACING = 0.35;

    /** それ以外の道の粒の間隔。全部を濃く描くと粒が多すぎるので少し粗くする。 */
    private static final double ROAD_SPACING_DIM = 0.9;

    /** 足場の上どのくらいの高さまでを「乗った」と見なすか。 */
    private static final int PAD_ENTER_HEIGHT = 4;

    private record RoadPad(Roadmap.Node node, int centerX, int centerZ) {
        boolean contains(double x, double z) {
            return Math.abs(x - (centerX + 0.5)) <= PAD_RADIUS + 0.5
                    && Math.abs(z - (centerZ + 0.5)) <= PAD_RADIUS + 0.5;
        }

        Pos center() {
            return new Pos(centerX + 0.5, ROAD_Y + 1, centerZ + 0.5);
        }
    }

    private final InstanceContainer lobby;
    private final InstanceContainer roadmap;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    /** いま進める足場だけ。入場判定はこれを見る。 */
    private final List<RoadPad> pads = new ArrayList<>();

    /** ロードマップ上の全ノードの足場。表示は常にこの全部を出す。 */
    private final List<RoadPad> allPads = new ArrayList<>();
    private final List<Entity> roadmapEntities = new ArrayList<>();

    /** 商店の売り切れ状態。ノードごとに保持し、画面を開き直しても残る。 */
    private final Map<Roadmap.Node, boolean[]> soldOutFlags = new HashMap<>();

    /** 商店の商品スロット。 */
    private static final int[] SHOP_SLOTS = {10, 12, 14, 16, 20};

    private RunState run;

    // 対戦モード
    private VersusMatch versus;
    private InstanceContainer versusInstance;
    /**
     * 対戦中の人間プレイヤー。
     *
     * <p>1 人ぶんのフィールドで持っていると、実際に複数人が入ったときに
     * 全員が同じ島を操作してしまう。最初から人ごとに持っておく。</p>
     */
    private final Map<UUID, VersusPlayer> versusHumans = new LinkedHashMap<>();

    private WaitingRoom waitingRoom;
    private final Set<UUID> waitingParty = new LinkedHashSet<>();
    private int matchEndedTick = -1;
    private final List<MirrorBot> bots = new ArrayList<>();

    private Roadmap.Node activeNode;
    private Stage stage;
    private InstanceContainer stageInstance;
    private boolean transitioning;
    private int tick;

    public Mazeward() {
        this.lobby = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        this.roadmap = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        lobby.setGenerator(unit -> {
        });
        roadmap.setGenerator(unit -> {
        });
        lobby.setTime(6000);
        lobby.setTimeRate(0);
        roadmap.setTime(18000);
        roadmap.setTimeRate(0);
        buildLobby();
        waitingRoom = new WaitingRoom(lobby);
    }

    // ================================================================ プレイヤー

    public void onConfigure(AsyncPlayerConfigurationEvent event) {
        event.setSpawningInstance(lobby);
        event.getPlayer().setRespawnPoint(new Pos(0.5, LOBBY_Y + 1, 0.5));
    }

    public void onSpawn(Player player) {
        PlayerSession session = sessions.computeIfAbsent(player.getUuid(), id -> new PlayerSession(player));
        if (session.sidebar() == null) {
            var sidebar = Hud.createSidebar();
            sidebar.addViewer(player);
            session.setSidebar(sidebar);
        }
        preparePlayer(player);
        Hud.applyLobbyHotbar(player);
        player.sendMessage(Component.text("MAZEWARD", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" — 迷路構築型ローグライト TD", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("手に持った星を右クリックしてランを開始", NamedTextColor.YELLOW));
    }

    public void onDisconnect(Player player) {
        PlayerSession session = sessions.remove(player.getUuid());
        if (session != null) {
            session.leaveStage();
        }
        versusHumans.remove(player.getUuid());
        if (waitingParty.remove(player.getUuid())) {
            refreshWaitingRoom();
        }
        if (stage != null) {
            stage.removePlayer(player);
        }
    }

    private void preparePlayer(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlying(true);
        player.setFlying(true);
        player.setFlyingSpeed(0.075f);
        applyNightVision(player);
    }

    /**
     * アリーナは虚空の上に浮いた島なので、素のままだと空も地面も真っ暗になる。
     * 常時暗視をかけて視認性を確保する。
     *
     * <p>フラグを 0 にしてあるので、画面端の渦巻きパーティクルもステータスアイコンも出ない。
     * 経路のダストパーティクルが見づらくなるのを避けるため。</p>
     */
    private void applyNightVision(Player player) {
        player.addEffect(new Potion(PotionEffect.NIGHT_VISION, 0, Potion.INFINITE_DURATION, (byte) 0));
    }

    private PlayerSession session(Player player) {
        return sessions.computeIfAbsent(player.getUuid(), id -> new PlayerSession(player));
    }

    // ================================================================ 入力

    /** 左クリック（腕振り）＝ 回転。 */
    public void onHandAnimation(PlayerHandAnimationEvent event) {
        PlayerSession session = session(event.getPlayer());
        if (session.field() == null || session.mode() == PlayerSession.Mode.NONE) {
            return;
        }
        session.rotate();
        refreshHotbar(session);
        event.getPlayer().sendActionBar(Component.text("回転: " + session.rot().label(), NamedTextColor.YELLOW));
    }

    /** 右クリック（空中）。上空から遠いセルを狙うときはこちらが飛ぶ。 */
    public void onUseItem(PlayerUseItemEvent event) {
        event.setCancelled(true);
        primaryAction(event.getPlayer());
    }

    /** 右クリック（近くのブロック）。 */
    public void onBlockInteract(PlayerBlockInteractEvent event) {
        event.setCancelled(true);
        primaryAction(event.getPlayer());
    }

    public void onBlockPlace(PlayerBlockPlaceEvent event) {
        event.setCancelled(true);
    }

    public void onBlockBreak(PlayerBlockBreakEvent event) {
        event.setCancelled(true);
    }

    public void onChangeHeldSlot(PlayerChangeHeldSlotEvent event) {
        PlayerSession session = session(event.getPlayer());
        if (session.field() == null) {
            return;
        }
        // どちらのモードで持ち替えたかは PlayerSession が判断する
        session.syncSelectionWithHotbar();
        refreshHotbar(session);
    }

    /**
     * 障害物 ⇄ タワーを切り替え、そのまま 1 番のスロットへ戻す。
     *
     * <p>切り替えた直後に何も持っていない状態にすると、
     * 「切り替わったのか」が分からない。先頭を選んだ状態にしておくと、
     * 持ち替えの結果がゴーストとして即座に見える。</p>
     */
    private void toggleHand(PlayerSession session) {
        session.toggleHandMode();
        session.player().setHeldItemSlot((byte) 0);
        session.syncSelectionWithHotbar();
        refreshHotbar(session);
        boolean tower = session.handMode() == PlayerSession.HandMode.TOWER;
        session.player().sendActionBar(Component.text(
                tower ? "タワーに持ち替えました" : "障害物カードに持ち替えました",
                tower ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
    }

    /** いまのモードに合ったホットバーを描き直す。 */
    private void refreshHotbar(PlayerSession session) {
        VersusPlayer self = versusPlayerOf(session.player());
        if (versus != null && self != null) {
            VersusHud.applyHotbar(session, self, versus);
        } else if (session.stage() != null) {
            Hud.applyStageHotbar(session);
        }
    }

    public void onInventoryPreClick(InventoryPreClickEvent event) {
        if (!(event.getInventory() instanceof Inventory inventory)) {
            return;
        }
        event.setCancelled(true);
        session(event.getPlayer()).handleMenuClick(inventory, event.getSlot());
    }

    private void primaryAction(Player player) {
        PlayerSession session = session(player);
        Instance instance = player.getInstance();

        if (instance == lobby) {
            if (waitingRoom.contains(player)) {
                switch (player.getHeldSlot()) {
                    case 0 -> startFromWaitingRoom(player);
                    case 1 -> openPlayerCountMenu(session);
                    case 8 -> player.teleport(new Pos(0.5, LOBBY_Y + 1, 0.5));
                    default -> {
                    }
                }
                return;
            }
            if (player.getHeldSlot() == 1) {
                player.teleport(WaitingRoom.ENTRY);
                player.sendMessage(Component.text(
                        "待機部屋に入りました。人がそろったら緑のアイテムを右クリック",
                        NamedTextColor.AQUA));
            } else {
                startRun(player);
            }
            return;
        }
        if (versus != null && instance == versusInstance) {
            versusAction(session);
            return;
        }
        if (instance == roadmap) {
            showRelics(player);
            return;
        }
        if (session.stage() == null) {
            return;
        }

        int slot = player.getHeldSlot();
        switch (slot) {
            case PlayerSession.SLOT_TOGGLE -> toggleHand(session);
            case PlayerSession.SLOT_INSPECT -> {
                Vec2i cell = session.inspectCell();
                if (cell != null) {
                    Menus.openTowerDetail(session, cell);
                }
            }
            case PlayerSession.SLOT_START -> {
                Stage.Outcome outcome = session.stage().startWave();
                Hud.feedback(player, outcome);
                Hud.applyStageHotbar(session);
            }
            default -> {
                boolean wasCard = session.mode() == PlayerSession.Mode.CARD;
                Stage.Outcome outcome = session.confirm();
                Hud.feedback(player, outcome);
                if (outcome.success() && wasCard) {
                    session.reselectAfterPlay();
                }
                Hud.applyStageHotbar(session);
            }
        }
    }

    // ================================================================ 待機部屋

    /**
     * 部屋の出入りを見張る。
     *
     * <p>入退室のイベントは無いので、位置を見て変化した瞬間だけ処理する。
     * 毎 tick 名簿を貼り替えると、文字が点滅して読めない。</p>
     */
    private void tickWaitingRoom(PlayerSession session) {
        Player player = session.player();
        boolean inside = waitingRoom.contains(player);
        boolean listed = waitingParty.contains(player.getUuid());
        if (inside == listed) {
            return;
        }
        if (inside) {
            waitingParty.add(player.getUuid());
        } else {
            waitingParty.remove(player.getUuid());
            Hud.applyLobbyHotbar(player);
        }
        refreshWaitingRoom();
    }

    private void refreshWaitingRoom() {
        List<Player> party = waitingPlayers();
        waitingRoom.updateBoard(party.stream().map(Player::getUsername).toList());
        for (Player member : party) {
            Hud.applyWaitingHotbar(member, party.size());
        }
    }

    private List<Player> waitingPlayers() {
        List<Player> party = new ArrayList<>();
        for (UUID id : waitingParty) {
            PlayerSession session = sessions.get(id);
            if (session != null && session.player().getInstance() == lobby) {
                party.add(session.player());
            }
        }
        return party;
    }

    private void startFromWaitingRoom(Player presser) {
        List<Player> party = waitingPlayers();
        if (party.size() < 2) {
            presser.sendActionBar(Component.text(
                    "2 人以上必要です（ひとりで試すなら「ボットで埋める」）", NamedTextColor.RED));
            return;
        }
        if (party.size() > WaitingRoom.CAPACITY) {
            party = party.subList(0, WaitingRoom.CAPACITY);
        }
        waitingParty.clear();
        startVersus(party, 0);
    }

    // ================================================================ 対戦

    private VersusPlayer versusPlayerOf(Player player) {
        return versus == null ? null : versusHumans.get(player.getUuid());
    }

    /** 人数を選ぶ。相手は自分の操作をそのまま真似するボットが埋める。 */
    private void openPlayerCountMenu(PlayerSession session) {
        List<Menus.Option> options = new ArrayList<>();
        for (int count : new int[] {2, 3, 4, 5, 6, 8}) {
            int players = count;
            options.add(new Menus.Option(
                    Hud.item(Material.PLAYER_HEAD,
                            Component.text(players + " 人で対戦", NamedTextColor.GOLD),
                            Component.text("あなた + ボット " + (players - 1) + " 人",
                                    NamedTextColor.GRAY),
                            Component.text("ボットはあなたの操作をそのまま真似します",
                                    NamedTextColor.DARK_GRAY)),
                    () -> startVersus(session.player(), players)));
        }
        Menus.openChoice(session, Component.text("対戦の人数を選ぶ"), options, false);
    }

    /** ボットだけで人数を埋めるデバッグ用の開始。 */
    private void startVersus(Player player, int playerCount) {
        startVersus(List.of(player), playerCount - 1);
    }

    /**
     * 対戦を始める。
     *
     * @param humans  参加する人。全員がそれぞれ島を 1 つ持つ
     * @param botFill 足りないぶんを埋めるミラーボットの数（デバッグ用。通常は 0）
     */
    private void startVersus(List<Player> humans, int botFill) {
        cleanupVersus();

        versusInstance = MinecraftServer.getInstanceManager()
                .createInstanceContainer(DimensionType.OVERWORLD);
        versusInstance.setGenerator(unit -> {
        });
        versusInstance.setTime(6000);
        versusInstance.setTimeRate(0);

        int playerCount = humans.size() + botFill;
        long seed = new Random().nextLong();
        versus = new VersusMatch(versusInstance, seed, playerCount, this::onMatchEnded);
        matchEndedTick = -1;

        for (Player human : humans) {
            VersusPlayer participant = new VersusPlayer(human.getUsername(), human, false);
            versus.addParticipant(participant);
            versusHumans.put(human.getUuid(), participant);
        }
        for (int i = 1; i <= botFill; i++) {
            VersusPlayer bot = new VersusPlayer("ボット" + i, null, true);
            versus.addParticipant(bot);
            // 遅延をずらす。全員が同時に同じ手を打つと送りが一斉に来て事故になる
            bots.add(new MirrorBot(bot, 20 + i * 25));
        }

        for (Player human : humans) {
            VersusPlayer participant = versusHumans.get(human.getUuid());
            PlayerSession session = session(human);
            human.setInstance(versusInstance, versus.overviewOf(participant));
            preparePlayer(human);
            session.enterStage(participant.island());
            participant.island().addPlayer(human);
            if (!participant.deck().hand().isEmpty()) {
                human.setHeldItemSlot((byte) 0);
                session.selectCard(0);
            }
            VersusHud.applyHotbar(session, participant, versus);

            human.sendMessage(Component.text("── 対戦 " + playerCount + " 人 ──",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
            human.sendMessage(Component.text(
                    "地形は全員同じ。飛べば相手の島を見に行けます（干渉はできません）",
                    NamedTextColor.AQUA));
            human.sendMessage(Component.text(
                    "インカムが増えるのは敵を送ったときだけ。守るか伸ばすかが勝負どころ",
                    NamedTextColor.YELLOW));
            human.sendMessage(Component.text(
                    "準備 60 秒のあいだに迷路とタワーを組んでください", NamedTextColor.GRAY));
        }
    }

    /** 対戦中の右クリック。 */
    private void versusAction(PlayerSession session) {
        Player player = session.player();
        VersusPlayer self = versusPlayerOf(player);
        if (self == null || !self.alive()) {
            return;
        }
        switch (player.getHeldSlot()) {
            case PlayerSession.SLOT_TOGGLE -> toggleHand(session);
            case PlayerSession.SLOT_INSPECT -> {
                Vec2i cell = session.inspectCell();
                if (cell != null) {
                    Menus.openTowerDetail(session, cell);
                }
            }
            case VersusHud.SLOT_SEND -> VersusHud.openSendMenu(session, self, versus);
            default -> {
                var outcome = session.confirm();
                Hud.feedback(player, outcome);
                if (outcome.success()) {
                    recordForBots(session);
                    if (session.mode() == PlayerSession.Mode.CARD) {
                        session.reselectAfterPlay();
                    }
                }
                VersusHud.applyHotbar(session, self, versus);
            }
        }
    }

    /** 人間が置いたものをボットのキューへ積む。 */
    private void recordForBots(PlayerSession session) {
        PlayerSession.Placement placement = session.takeLastPlacement();
        if (placement == null || versus == null) {
            return;
        }
        MirrorBot.Action action = placement.mode() == PlayerSession.Mode.CARD
                ? new MirrorBot.Action.PlaceCard(placement.shape(), placement.origin(), placement.rot())
                : new MirrorBot.Action.PlaceTower(placement.tower(), placement.origin(), placement.rot());
        for (MirrorBot bot : bots) {
            bot.record(action, versus.elapsedTicks());
        }
    }

    private void onMatchEnded(VersusMatch match, VersusPlayer winner) {
        matchEndedTick = tick;
        for (PlayerSession session : sessions.values()) {
            Player player = session.player();
            player.sendMessage(winner == null
                    ? Component.text("引き分けで終了しました", NamedTextColor.GRAY)
                    : Component.text(winner.name() + " の勝利！", NamedTextColor.GOLD, TextDecoration.BOLD));
        }
    }

    private void cleanupVersus() {
        if (versus != null) {
            versus.dispose();
            versus = null;
        }
        bots.clear();
        versusHumans.clear();
        matchEndedTick = -1;
        for (PlayerSession session : sessions.values()) {
            session.leaveStage();
        }
        if (versusInstance != null) {
            InstanceContainer previous = versusInstance;
            versusInstance = null;
            MinecraftServer.getInstanceManager().unregisterInstance(previous);
        }
    }

    private void tickVersus() {
        versus.tick();
        for (MirrorBot bot : bots) {
            bot.tick(versus.elapsedTicks(), versus);
        }
        for (PlayerSession session : sessions.values()) {
            VersusPlayer self = versusPlayerOf(session.player());
            if (session.field() == null || self == null) {
                continue;
            }
            session.syncSelectionWithHotbar();
            session.tick(tick);
            if (tick % 10 == 0) {
                VersusHud.updateSidebar(session, self, versus);
            }
            if (tick % 40 == 0) {
                VersusHud.applyHotbar(session, self, versus);
            }
        }

        // 決着してもその場に留まると、次の試合を始められない
        if (matchEndedTick >= 0 && tick - matchEndedTick > 100) {
            returnEveryoneToLobby();
        }
    }

    private void returnEveryoneToLobby() {
        List<Player> players = new ArrayList<>();
        for (UUID id : versusHumans.keySet()) {
            PlayerSession session = sessions.get(id);
            if (session != null) {
                players.add(session.player());
            }
        }
        cleanupVersus();
        for (Player player : players) {
            player.setInstance(lobby, new Pos(0.5, LOBBY_Y + 1, 0.5));
            preparePlayer(player);
            Hud.applyLobbyHotbar(player);
        }
    }

    // ================================================================ 毎 tick

    public void tick() {
        try {
            tickInternal();
        } catch (Exception exception) {
            // スケジューラのタスクで例外が外に出ると、以後 tick が回らなくなって
            // 「画面は動いているのに何も起きない」状態になる。必ずここで受け止めて記録する。
            System.err.println("[MAZEWARD] tick で例外が発生しました");
            exception.printStackTrace();
        }
    }

    private void tickInternal() {
        tick++;

        if (versus != null) {
            tickVersus();
            return;
        }

        if (stage != null) {
            stage.tick();
        }

        for (PlayerSession session : sessions.values()) {
            Player player = session.player();
            if (player.getInstance() == null) {
                continue;
            }
            if (session.stage() != null) {
                session.syncSelectionWithHotbar();
                session.tick(tick);
            } else if (player.getInstance() == roadmap) {
                checkRoadmapEntry(session);
            } else if (player.getInstance() == lobby) {
                tickWaitingRoom(session);
            }
            if (tick % 10 == 0) {
                Hud.updateSidebar(session, run);
            }
            if (tick % 40 == 0 && session.stage() != null) {
                Hud.applyStageHotbar(session);
            }
        }

        if (tick % 4 == 0 && !pads.isEmpty()) {
            drawRoadmapLinks();
        }
    }

    // ================================================================ ラン進行

    private void startRun(Player player) {
        run = new RunState(new Random().nextLong());
        player.sendMessage(Component.text("新しいランを開始しました（シード " + run.seed() + "）",
                NamedTextColor.GREEN));
        buildRoadmap();
        moveToRoadmap(player);
    }

    private void moveToRoadmap(Player player) {
        moveToRoadmap(player, null);
    }

    /**
     * ロードマップへ戻す。
     *
     * @param from 直前に踏み終えたノード。そこへ立たせると、
     *             どこから来てどこへ行けるのかが道をたどるだけで分かる
     */
    private void moveToRoadmap(Player player, Roadmap.Node from) {
        PlayerSession session = session(player);
        session.leaveStage();

        Pos start = new Pos(0.5, ROAD_Y + 1, 0.5, 180f, 25f);
        if (from != null) {
            RoadPad pad = padOf(from);
            if (pad != null) {
                start = pad.center().withYaw(180f).withPitch(25f);
            }
        }
        player.setInstance(roadmap, start);
        preparePlayer(player);
        player.setVelocity(Vec.ZERO);
        Hud.applyRoadmapHotbar(player);
        player.sendMessage(Component.text("第 " + run.layer() + " 層 — 光っている足場のどれかに乗る",
                NamedTextColor.AQUA));
    }

    private RoadPad padOf(Roadmap.Node node) {
        for (RoadPad pad : allPads) {
            if (pad.node().layer() == node.layer() && pad.node().index() == node.index()) {
                return pad;
            }
        }
        return null;
    }

    private void moveToLobby(Player player) {
        PlayerSession session = session(player);
        session.leaveStage();
        player.setInstance(lobby, new Pos(0.5, LOBBY_Y + 1, 0.5));
        preparePlayer(player);
        Hud.applyLobbyHotbar(player);
    }

    private void checkRoadmapEntry(PlayerSession session) {
        if (transitioning || run == null || run.finished()) {
            return;
        }
        Pos pos = session.player().getPosition();
        for (RoadPad pad : pads) {
            if (!pad.contains(pos.x(), pos.z())
                    || pos.y() < ROAD_Y || pos.y() > ROAD_Y + PAD_ENTER_HEIGHT) {
                continue;
            }
            if (!run.canEnter(pad.node())) {
                // 道がつながっていないノード。通り過ぎるだけなので黙って無視する
                continue;
            }
            haltOnPad(session.player(), pad);
            enterNode(pad.node());
            return;
        }
    }

    /**
     * 足場に乗った瞬間、飛行の慣性を殺してその場に止める。
     *
     * <p>クリエイティブ飛行は滑るので、ノードに触れた勢いのまま通り過ぎてしまい、
     * 「乗ったのに反応しない」「隣のノードまで滑る」といった挙動になる。
     * 踏んだ時点で飛行を解除し、速度を 0 にして足場の中央へ寄せることで、
     * 歩いて乗ったときと同じ止まり方にする。</p>
     */
    private void haltOnPad(Player player, RoadPad pad) {
        player.setFlying(false);
        player.setVelocity(Vec.ZERO);
        player.teleport(pad.center()
                .withYaw(player.getPosition().yaw())
                .withPitch(player.getPosition().pitch()));
    }

    private void enterNode(Roadmap.Node node) {
        transitioning = true;
        activeNode = node;
        switch (node.kind()) {
            case BATTLE, ELITE, BOSS -> startStage(node);
            case SHOP -> openShop(node);
            case ALTAR -> openAltar(node);
            case EVENT -> openEvent(node);
        }
    }

    private void startStage(Roadmap.Node node) {
        long seed = run.seed() * 31L + node.layer() * 7919L + node.index();
        StageGenerator.Result generated = StageGenerator.generate(node.layer(), node.kind(), seed);

        InstanceContainer previous = stageInstance;
        stageInstance = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        stageInstance.setGenerator(unit -> {
        });
        stageInstance.setTime(6000);
        stageInstance.setTimeRate(0);

        stage = new Stage(stageInstance, generated, run, this);
        Pos overview = stage.arena().overviewPos(stage.grid());

        for (PlayerSession session : sessions.values()) {
            Player player = session.player();
            player.setInstance(stageInstance, overview);
            preparePlayer(player);
            session.enterStage(stage);
            stage.addPlayer(player);
            if (!run.deck().hand().isEmpty()) {
                player.setHeldItemSlot((byte) 0);
                session.selectCard(0);
            }
            Hud.applyStageHotbar(session);
            player.sendMessage(Component.text("── " + generated.config().title() + " ──",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text(
                    "手札のカードを選び、床を狙って右クリックで配置。左クリックで回転。", NamedTextColor.YELLOW));
            player.sendMessage(Component.text(
                    "青=現在の敵経路 / 黄=配置予定 / 赤=配置後の経路", NamedTextColor.AQUA));
            player.sendMessage(Component.text(
                    "障害物は戦闘中でも設置できます（一度置くと撤去はできません）", NamedTextColor.GRAY));
        }

        if (previous != null) {
            MinecraftServer.getInstanceManager().unregisterInstance(previous);
        }
        transitioning = false;
    }

    @Override
    public void onStageEnded(Stage endedStage, boolean victory) {
        List<Player> players = new ArrayList<>(endedStage.players());
        boolean wasBoss = endedStage.config().nodeKind() == Roadmap.NodeKind.BOSS;

        if (!victory) {
            run.finish(false);
            for (Player player : players) {
                player.sendMessage(Component.text("コアが陥落しました。ランは終了です。",
                        NamedTextColor.DARK_RED, TextDecoration.BOLD));
                player.sendMessage(Component.text("到達 第" + run.layer() + "層 / 撃破ステージ "
                        + run.clearedStages(), NamedTextColor.GRAY));
                moveToLobby(player);
            }
            cleanupStage();
            return;
        }

        int reward = run.emberRewardFor(endedStage.config().nodeKind(), endedStage.config().layer());
        run.addEmber(reward);
        for (Player player : players) {
            player.sendMessage(Component.text("ステージ制圧！  エンバー +" + reward,
                    NamedTextColor.GREEN, TextDecoration.BOLD));
        }

        if (wasBoss) {
            run.finish(true);
            for (Player player : players) {
                player.sendMessage(Component.text("★ 災厄を討伐し、ランを踏破しました ★",
                        NamedTextColor.GOLD, TextDecoration.BOLD));
                moveToLobby(player);
            }
            cleanupStage();
            return;
        }

        // 報酬 3 択 → 選ぶとロードマップへ
        for (Player player : players) {
            offerReward(session(player));
        }
    }

    private void cleanupStage() {
        if (stage != null) {
            stage.dispose();
            stage = null;
        }
        for (PlayerSession session : sessions.values()) {
            session.leaveStage();
        }
        // pads はロードマップ側の状態。ここで消すと buildRoadmapForCurrentLayer が
        // 前の層の足場を消せなくなり、押しても反応しない幽霊の足場が残ってしまう。
        transitioning = false;
    }

    private void advanceAfterReward() {
        cleanupStage();
        run.advanceLayer(activeNode == null ? 0 : activeNode.index());
        Roadmap.Node cleared = activeNode;
        activeNode = null;
        refreshRoadmap();
        for (PlayerSession session : sessions.values()) {
            moveToRoadmap(session.player(), cleared);
        }
    }

    // ================================================================ 報酬・商店・祭壇

    private void offerReward(PlayerSession session) {
        Random random = run.random();
        List<Menus.Option> options = new ArrayList<>();

        TowerKind locked = run.randomLockedTower();
        if (locked != null) {
            options.add(new Menus.Option(
                    Hud.item(locked.icon(),
                            Component.text("新しいタワー: " + locked.displayName(), locked.element().color()),
                            Component.text(locked.description(), NamedTextColor.GRAY),
                            Component.text("形状 " + locked.shape().displayName()
                                    + " / " + locked.baseCost() + "G", NamedTextColor.WHITE)),
                    () -> {
                        run.unlock(locked);
                        broadcast(Component.text(locked.displayName() + " を解放しました", NamedTextColor.GREEN));
                        advanceAfterReward();
                    }));
        }

        Shape shape1 = Shapes.random(random);
        Shape shape2 = Shapes.random(random);
        options.add(new Menus.Option(
                Hud.item(Material.STONE_BRICKS,
                        Component.text("ブロックカード x2: " + shape1.displayName()
                                + " / " + shape2.displayName(), NamedTextColor.YELLOW),
                        Component.text("デッキに 2 枚追加", NamedTextColor.GRAY),
                        Component.text("迷路を伸ばす余地が増える", NamedTextColor.DARK_GRAY)),
                () -> {
                    run.deck().addShape(shape1);
                    run.deck().addShape(shape2);
                    broadcast(Component.text("カードを 2 枚獲得しました", NamedTextColor.YELLOW));
                    advanceAfterReward();
                }));

        Relic relic = Relic.randomMissing(run.relics(), random);
        if (relic != null) {
            options.add(new Menus.Option(
                    Hud.item(relic.icon(),
                            Component.text("レリック: " + relic.displayName(), NamedTextColor.LIGHT_PURPLE),
                            Component.text(relic.description(), NamedTextColor.GRAY)),
                    () -> {
                        run.grant(relic);
                        broadcast(Component.text(relic.displayName() + " を獲得しました",
                                NamedTextColor.LIGHT_PURPLE));
                        advanceAfterReward();
                    }));
        }

        if (run.deck().hasPlainCard()) {
            Rune rune = Rune.random(random);
            options.add(new Menus.Option(
                    Hud.item(rune.icon(),
                            Component.text("ルーン: " + rune.displayName(), rune.color()),
                            Component.text(rune.description(), NamedTextColor.GRAY),
                            Component.text("デッキのカード 1 枚に付与する", NamedTextColor.DARK_GRAY)),
                    () -> forgeRune(session, rune, this::advanceAfterReward)));
        }

        int emberReward = 70 + run.layer() * 20;
        options.add(new Menus.Option(
                Hud.item(Material.BLAZE_POWDER,
                        Component.text("エンバー +" + emberReward, NamedTextColor.GOLD),
                        Component.text("商店や祭壇での買い物に使う", NamedTextColor.GRAY)),
                () -> {
                    run.addEmber(emberReward);
                    broadcast(Component.text("エンバー +" + emberReward, NamedTextColor.GOLD));
                    advanceAfterReward();
                }));

        while (options.size() > 3) {
            options.remove(random.nextInt(options.size()));
        }
        Menus.openChoice(session, Component.text("報酬を 1 つ選ぶ"), options);
    }

    /**
     * ルーンを付けるカードをデッキから選ばせる。
     * 「どのカードを特殊化するか」がこの機能のいちばん面白いところなので、
     * ランダムに付けるのではなく必ずプレイヤーに選ばせる。
     */
    private void forgeRune(PlayerSession session, Rune rune, Runnable after) {
        if (!run.deck().hasPlainCard()) {
            broadcast(Component.text("ルーンを付けられるカードがありません", NamedTextColor.GRAY));
            after.run();
            return;
        }
        Menus.openDeckPicker(session,
                Component.text("「" + rune.displayName() + "」を付けるカードを選ぶ"),
                run.deck().library(),
                index -> {
                    if (run.deck().applyRune(index, rune)) {
                        broadcast(Component.text(
                                run.deck().library().get(index).displayName() + " を作成しました",
                                rune.color()));
                    }
                    after.run();
                });
    }

    // ================================================================ イベント

    /**
     * イベントノード。得も損もある選択を 2〜3 個出す。
     *
     * <p>戦闘・商店・祭壇だけだと層の選択が「戦うか、整えるか」の 2 択に固まるので、
     * 「賭ける」という第 3 の性格を足すためのノード。</p>
     */
    private void openEvent(Roadmap.Node node) {
        Random random = run.random();
        for (PlayerSession session : sessions.values()) {
            List<Menus.Option> options = new ArrayList<>();
            String title;

            switch (random.nextInt(6)) {
                case 0 -> {
                    title = "崩れた石切場";
                    Shape a = Shapes.random(random);
                    Shape b = Shapes.random(random);
                    Shape c = Shapes.random(random);
                    options.add(new Menus.Option(
                            Hud.item(Material.STONE_BRICKS,
                                    Component.text("石材を運び出す", NamedTextColor.YELLOW),
                                    Component.text("カードを 3 枚得る（" + a.displayName() + " / "
                                            + b.displayName() + " / " + c.displayName() + "）",
                                            NamedTextColor.GRAY)),
                            () -> {
                                run.deck().addShape(a);
                                run.deck().addShape(b);
                                run.deck().addShape(c);
                                broadcast(Component.text("カードを 3 枚得た", NamedTextColor.YELLOW));
                                advanceAfterReward();
                            }));
                    options.add(goldOption(95, "石材を売り払う"));
                }
                case 1 -> {
                    title = "流浪の鍛冶師";
                    Rune rune = Rune.random(random);
                    options.add(new Menus.Option(
                            Hud.item(rune.icon(),
                                    Component.text("刻んでもらう: " + rune.displayName(), rune.color()),
                                    Component.text(rune.description(), NamedTextColor.GRAY),
                                    Component.text("デッキのカード 1 枚に付与", NamedTextColor.DARK_GRAY)),
                            () -> forgeRune(session, rune, this::advanceAfterReward)));
                    options.add(goldOption(80, "駄賃だけ受け取る"));
                }
                case 2 -> {
                    title = "古い供物台";
                    Relic relic = Relic.randomMissing(run.relics(), random);
                    if (relic != null && run.coreHp() > 4) {
                        options.add(new Menus.Option(
                                Hud.item(relic.icon(),
                                        Component.text("血を捧げる: " + relic.displayName(),
                                                NamedTextColor.LIGHT_PURPLE),
                                        Component.text(relic.description(), NamedTextColor.GRAY),
                                        Component.text("コア HP -3", NamedTextColor.RED)),
                                () -> {
                                    run.damageCore(3);
                                    run.grant(relic);
                                    broadcast(Component.text(relic.displayName() + " を得た（コア HP -3）",
                                            NamedTextColor.LIGHT_PURPLE));
                                    advanceAfterReward();
                                }));
                    }
                    options.add(new Menus.Option(
                            Hud.item(Material.BARRIER,
                                    Component.text("手を触れずに立ち去る", NamedTextColor.GRAY)),
                            this::advanceAfterReward));
                }
                case 3 -> {
                    title = "打ち捨てられた哨戒塔";
                    TowerKind locked = run.randomLockedTower();
                    if (locked != null) {
                        options.add(new Menus.Option(
                                Hud.item(locked.icon(),
                                        Component.text("設計図を回収: " + locked.displayName(),
                                                locked.element().color()),
                                        Component.text(locked.description(), NamedTextColor.GRAY)),
                                () -> {
                                    run.unlock(locked);
                                    broadcast(Component.text(locked.displayName() + " を解放した",
                                            NamedTextColor.GREEN));
                                    advanceAfterReward();
                                }));
                    }
                    options.add(new Menus.Option(
                            Hud.item(Material.GOLDEN_APPLE,
                                    Component.text("資材を修理に回す", NamedTextColor.RED),
                                    Component.text("コア HP +6", NamedTextColor.GRAY)),
                            () -> {
                                run.healCore(6);
                                broadcast(Component.text("コアを修復した (+6)", NamedTextColor.GREEN));
                                advanceAfterReward();
                            }));
                }
                case 4 -> {
                    title = "行商人の荷車";
                    Shape shape = Shapes.random(random);
                    Rune rune = Rune.random(random);
                    options.add(new Menus.Option(
                            Hud.item(Material.CHEST,
                                    Component.text("荷を丸ごと買う", NamedTextColor.GREEN),
                                    Component.text("エンバー 65 / カード " + shape.displayName()
                                            + " + ルーン " + rune.displayName(), NamedTextColor.GRAY)),
                            () -> {
                                if (run.spendEmber(65)) {
                                    run.deck().addShape(shape);
                                    forgeRune(session, rune, this::advanceAfterReward);
                                } else {
                                    broadcast(Component.text("エンバーが足りなかった", NamedTextColor.RED));
                                    advanceAfterReward();
                                }
                            }));
                    options.add(new Menus.Option(
                            Hud.item(Material.BARRIER, Component.text("見送る", NamedTextColor.GRAY)),
                            this::advanceAfterReward));
                }
                default -> {
                    title = "地の裂け目";
                    options.add(run.deck().canIncreaseHandSize()
                            ? new Menus.Option(
                                    Hud.item(Material.BRICK,
                                            Component.text("覗き込んで足場を確かめる", NamedTextColor.YELLOW),
                                            Component.text("手札の上限 +1", NamedTextColor.GRAY)),
                                    () -> {
                                        run.deck().increaseHandSize(1);
                                        broadcast(Component.text("手札の上限が増えた", NamedTextColor.YELLOW));
                                        advanceAfterReward();
                                    })
                            : goldOption(90, "覗き込んで足場を確かめる"));
                    options.add(goldOption(120, "落ちている物を拾い集める"));
                }
            }

            Menus.openChoice(session, Component.text(title + " — 1 つ選ぶ"), options);
        }
    }

    private Menus.Option goldOption(int amount, String label) {
        return new Menus.Option(
                Hud.item(Material.BLAZE_POWDER,
                        Component.text(label, NamedTextColor.GOLD),
                        Component.text("エンバー +" + amount, NamedTextColor.GRAY)),
                () -> {
                    run.addEmber(amount);
                    broadcast(Component.text("エンバー +" + amount, NamedTextColor.GOLD));
                    advanceAfterReward();
                });
    }

    /**
     * 商店。
     *
     * <p>買った品物はその場で棚から消える（売り切れ表示になる）。
     * 残ったままだと同じ物を何度でも買えてしまい、
     * 「限られたエンバーを何に使うか」という選択が成立しない。</p>
     */
    private void openShop(Roadmap.Node node) {
        Random random = run.random();
        Shape shopShape = Shapes.random(random);
        Relic shopRelic = Relic.randomMissing(run.relics(), random);
        TowerKind shopTower = run.randomLockedTower();
        Rune shopRune = Rune.random(random);
        // 売り切れ状態は画面を開き直しても保つ必要があるので、node ごとに 1 つ持つ
        boolean[] sold = soldOutFlags.computeIfAbsent(node, key -> new boolean[SHOP_SLOTS.length]);

        for (PlayerSession session : sessions.values()) {
            Menus.open(session, new Menus.Screen() {
                @Override
                public Component title() {
                    return Component.text("商店  エンバー " + run.ember());
                }

                @Override
                public InventoryType type() {
                    return InventoryType.CHEST_3_ROW;
                }

                @Override
                public void render(PlayerSession s, Inventory inventory) {
                    inventory.setItemStack(10, sold[0] ? soldOutItem() : Hud.item(Material.STONE_BRICKS,
                            Component.text("ブロックカード: " + shopShape.displayName(), NamedTextColor.YELLOW),
                            Component.text("エンバー 45", NamedTextColor.GOLD)));
                    inventory.setItemStack(12, sold[1] || shopRelic == null
                            ? soldOutItem()
                            : Hud.item(shopRelic.icon(),
                                    Component.text("レリック: " + shopRelic.displayName(),
                                            NamedTextColor.LIGHT_PURPLE),
                                    Component.text(shopRelic.description(), NamedTextColor.GRAY),
                                    Component.text("エンバー 130", NamedTextColor.GOLD)));
                    inventory.setItemStack(14, sold[2] || shopTower == null
                            ? soldOutItem()
                            : Hud.item(shopTower.icon(),
                                    Component.text("タワー解放: " + shopTower.displayName(),
                                            shopTower.element().color()),
                                    Component.text(shopTower.description(), NamedTextColor.GRAY),
                                    Component.text("エンバー 110", NamedTextColor.GOLD)));
                    inventory.setItemStack(16, sold[3] ? soldOutItem() : Hud.item(Material.GOLDEN_APPLE,
                            Component.text("コア修復 +5", NamedTextColor.RED),
                            Component.text("エンバー 70", NamedTextColor.GOLD)));
                    inventory.setItemStack(20, sold[4] || !run.deck().hasPlainCard()
                            ? soldOutItem()
                            : Hud.item(shopRune.icon(),
                                    Component.text("ルーン刻印: " + shopRune.displayName(),
                                            shopRune.color()),
                                    Component.text(shopRune.description(), NamedTextColor.GRAY),
                                    Component.text("エンバー 85", NamedTextColor.GOLD)));
                    inventory.setItemStack(22, Hud.item(Material.LIME_DYE,
                            Component.text("次の層へ進む", NamedTextColor.GREEN)));
                }

                @Override
                public void click(PlayerSession s, Inventory inventory, int slot) {
                    Player player = s.player();
                    switch (slot) {
                        case 10 -> {
                            if (sold[0]) {
                                return;
                            }
                            if (run.spendEmber(45)) {
                                run.deck().addShape(shopShape);
                                sold[0] = true;
                                onPurchased(player, "カードを購入");
                            } else {
                                denied(player, "エンバー不足");
                            }
                        }
                        case 12 -> {
                            if (sold[1] || shopRelic == null) {
                                return;
                            }
                            if (run.spendEmber(130)) {
                                run.grant(shopRelic);
                                sold[1] = true;
                                onPurchased(player, "レリックを購入");
                            } else {
                                denied(player, "エンバー不足");
                            }
                        }
                        case 14 -> {
                            if (sold[2] || shopTower == null) {
                                return;
                            }
                            if (run.spendEmber(110)) {
                                run.unlock(shopTower);
                                sold[2] = true;
                                onPurchased(player, "タワーを解放");
                            } else {
                                denied(player, "エンバー不足");
                            }
                        }
                        case 16 -> {
                            if (sold[3]) {
                                return;
                            }
                            if (run.spendEmber(70)) {
                                run.healCore(5);
                                sold[3] = true;
                                onPurchased(player, "コアを修復");
                            } else {
                                denied(player, "エンバー不足");
                            }
                        }
                        case 20 -> {
                            if (sold[4]) {
                                return;
                            }
                            if (!run.deck().hasPlainCard()) {
                                denied(player, "刻めるカードがありません");
                            } else if (run.spendEmber(85)) {
                                sold[4] = true;
                                onPurchased(player, "ルーンを刻む");
                                s.clearMenu();
                                player.closeInventory();
                                forgeRune(s, shopRune, () -> openShop(node));
                                return;
                            } else {
                                denied(player, "エンバー不足");
                            }
                        }
                        case 22 -> {
                            s.clearMenu();
                            player.closeInventory();
                            soldOutFlags.remove(node);
                            advanceAfterReward();
                            return;
                        }
                        default -> {
                            return;
                        }
                    }
                    render(s, inventory);
                }
            });
        }
    }

    private static ItemStack soldOutItem() {
        return Hud.item(Material.BARRIER, Component.text("売り切れ", NamedTextColor.DARK_GRAY));
    }

    /** 購入成功。効果音を鳴らして手応えを出す。 */
    private void onPurchased(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.GREEN));
        player.playSound(Sound.sound(SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP,
                Sound.Source.MASTER, 0.8f, 1.2f));
        player.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING,
                Sound.Source.MASTER, 0.6f, 1.6f));
    }

    private void denied(Player player, String message) {
        player.sendActionBar(Component.text(message, NamedTextColor.RED));
        player.playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BASS,
                Sound.Source.MASTER, 0.7f, 0.7f));
    }

    private void openAltar(Roadmap.Node node) {
        Random random = run.random();
        Relic blessing = Relic.randomMissing(run.relics(), random);

        for (PlayerSession session : sessions.values()) {
            List<Menus.Option> options = new ArrayList<>();
            options.add(new Menus.Option(
                    Hud.item(Material.GOLDEN_APPLE, Component.text("コアを 8 回復", NamedTextColor.RED)),
                    () -> {
                        run.healCore(8);
                        broadcast(Component.text("コアを修復しました", NamedTextColor.GREEN));
                        advanceAfterReward();
                    }));
            options.add(run.deck().canIncreaseHandSize()
                    ? new Menus.Option(
                            Hud.item(Material.BRICK, Component.text("手札の上限 +1", NamedTextColor.YELLOW),
                                    Component.text("毎ターン使えるカードが増える", NamedTextColor.GRAY)),
                            () -> {
                                run.deck().increaseHandSize(1);
                                broadcast(Component.text("手札上限が増えました", NamedTextColor.YELLOW));
                                advanceAfterReward();
                            })
                    : goldOption(90, "予備の資材をかき集める"));
            options.add(new Menus.Option(
                    blessing == null
                            ? Hud.item(Material.BLAZE_POWDER, Component.text("エンバー +110", NamedTextColor.GOLD))
                            : Hud.item(blessing.icon(),
                                    Component.text("レリック: " + blessing.displayName(),
                                            NamedTextColor.LIGHT_PURPLE),
                                    Component.text(blessing.description(), NamedTextColor.GRAY)),
                    () -> {
                        if (blessing == null) {
                            run.addEmber(110);
                        } else {
                            run.grant(blessing);
                        }
                        advanceAfterReward();
                    }));
            Menus.openChoice(session, Component.text("祭壇 — 祝福を 1 つ選ぶ"), options);
        }
    }

    private void showRelics(Player player) {
        if (run == null) {
            return;
        }
        player.sendMessage(Component.text("── 所持レリック ──", NamedTextColor.LIGHT_PURPLE));
        if (run.relics().isEmpty()) {
            player.sendMessage(Component.text("まだありません", NamedTextColor.GRAY));
        }
        for (Relic relic : run.relics()) {
            player.sendMessage(Hud.relicLine(relic));
        }
        player.sendMessage(Component.text("デッキ " + run.deck().librarySize() + "枚"
                + "（ルーン付き " + run.deck().runedCount() + "枚） / 手札上限 "
                + run.handSize(), NamedTextColor.AQUA));
    }

    private void broadcast(Component message) {
        for (PlayerSession session : sessions.values()) {
            session.player().sendMessage(message);
        }
    }

    // ================================================================ ワールド構築

    private void buildLobby() {
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                lobby.setBlock(x, LOBBY_Y, z, (x + z) % 2 == 0
                        ? Block.POLISHED_DEEPSLATE : Block.DEEPSLATE_TILES);
            }
        }
        for (int x = -6; x <= 6; x += 12) {
            for (int z = -6; z <= 6; z += 12) {
                for (int y = 1; y <= 4; y++) {
                    lobby.setBlock(x, LOBBY_Y + y, z, Block.POLISHED_BLACKSTONE_BRICKS);
                }
                lobby.setBlock(x, LOBBY_Y + 5, z, Block.SEA_LANTERN);
            }
        }
        Entity title = Overlay.createLabel(lobby, new Pos(0.5, LOBBY_Y + 3.2, -4.5),
                Component.text("MAZEWARD", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("迷路構築型ローグライト タワーディフェンス", NamedTextColor.GRAY))
                        .append(Component.newline())
                        .append(Component.text("星を右クリックで開始", NamedTextColor.YELLOW)),
                6.0f);
        title.setNoGravity(true);
    }

    /**
     * ロードマップ全体を 1 度だけ組み立てる。
     *
     * <p>第 1 層からボスまでの全ノードと、それをつなぐ道を最初から全部見せる。
     * 目の前の 2 択だけを出す形だと「この先どうなるか」が分からず、
     * ロードマップが単なるボタンになってしまう。全体が見えて初めて
     * 「ここで左に逸れると商店を 2 つ拾えるが、代わりに精鋭を踏む」という
     * 数手先の経路選択が成立する。</p>
     *
     * <p>踏み終えた層も消さない。通ってきた道が残るので、
     * 自分がどのルートを辿ってきたのかが一目で分かる。</p>
     */
    private void buildRoadmap() {
        for (Entity entity : roadmapEntities) {
            entity.remove();
        }
        roadmapEntities.clear();
        allPads.clear();
        pads.clear();

        // 開始台
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                roadmap.setBlock(x, ROAD_Y, z, Block.POLISHED_DEEPSLATE);
            }
        }

        // 全層の足場
        for (int layer = 1; layer <= Roadmap.LAYERS; layer++) {
            List<Roadmap.Node> row = run.roadmap().layer(layer);
            for (int i = 0; i < row.size(); i++) {
                allPads.add(new RoadPad(row.get(i), nodeX(i, row.size()), layerZ(layer)));
            }
        }

        for (RoadPad pad : allPads) {
            buildPad(pad);
        }

        Entity header = Overlay.createLabel(roadmap, new Pos(0.5, ROAD_Y + 3.5, -4.5),
                Component.text("ロードマップ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("道をたどって行き先を決める", NamedTextColor.GRAY)),
                12.0f);
        roadmapEntities.add(header);

        refreshRoadmap();
    }

    /** いま進める足場だけを光らせ、通過済みの足場に印を付ける。 */
    private void refreshRoadmap() {
        pads.clear();
        List<Roadmap.Node> choices = run.currentChoices();
        for (RoadPad pad : allPads) {
            boolean available = choices.contains(pad.node());
            boolean passed = pad.node().layer() < run.layer();
            if (available) {
                pads.add(pad);
            }
            // 中央のブロックで状態を示す: 金=通過済み / 光源=いま行ける / 素材のまま=未到達
            roadmap.setBlock(pad.centerX(), ROAD_Y, pad.centerZ(),
                    passed ? Block.GOLD_BLOCK
                            : available ? Block.SEA_LANTERN : pad.node().kind().platform());
            roadmap.setBlock(pad.centerX(), ROAD_Y + 1, pad.centerZ() + PAD_RADIUS,
                    available ? Block.SEA_LANTERN : Block.AIR);
        }
    }

    private int nodeX(int index, int count) {
        return (int) Math.round((index - (count - 1) / 2.0) * NODE_SPACING);
    }

    private int layerZ(int layer) {
        return FIRST_LAYER_Z + (layer - 1) * LAYER_SPACING;
    }

    private RoadPad padAt(int layer, int index) {
        for (RoadPad pad : allPads) {
            if (pad.node().layer() == layer && pad.node().index() == index) {
                return pad;
            }
        }
        return null;
    }

    private void buildPad(RoadPad pad) {
        Roadmap.Node node = pad.node();
        for (int dx = -PAD_RADIUS; dx <= PAD_RADIUS; dx++) {
            for (int dz = -PAD_RADIUS; dz <= PAD_RADIUS; dz++) {
                roadmap.setBlock(pad.centerX() + dx, ROAD_Y, pad.centerZ() + dz, node.kind().platform());
            }
        }
        Entity label = Overlay.createLabel(roadmap,
                new Pos(pad.centerX() + 0.5, ROAD_Y + 2.8, pad.centerZ() + 0.5),
                Component.text("第" + node.layer() + "層 " + node.kind().displayName(),
                                node.kind().color(), TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text(node.kind().description(), NamedTextColor.GRAY)),
                14.0f);
        roadmapEntities.add(label);
    }

    /**
     * ノード同士の道をパーティクルの直線で描く。
     *
     * <p>ブロックで敷くと直線を表現できず、道が交差したときにどれがどれか分からなくなる。
     * パーティクルなら本当の直線が引けるので、交差していても目で追える。
     * だからこそグラフ側は交差を許してよく、ルートが絡み合う面白さが出る。</p>
     *
     * <p>色で 3 つの状態を分ける。金＝実際に通ってきた道、
     * 行き先の種別色＝いまここから進める道、灰＝それ以外の道。</p>
     */
    private void drawRoadmapLinks() {
        List<Player> viewers = new ArrayList<>();
        for (PlayerSession session : sessions.values()) {
            if (session.player().getInstance() == roadmap) {
                viewers.add(session.player());
            }
        }
        if (viewers.isEmpty()) {
            return;
        }

        // 開始台から第 1 層へ
        for (RoadPad pad : allPads) {
            if (pad.node().layer() != 1) {
                continue;
            }
            boolean live = run.layer() == 1;
            drawLink(viewers, new Pos(0.5, ROAD_Y + 1.4, 3.5), pad,
                    run.takenAt(1) == pad.node().index() ? TAKEN_COLOR
                            : live ? kindColor(pad.node()) : DIM_COLOR);
        }

        for (RoadPad pad : allPads) {
            int layer = pad.node().layer();
            for (int nextIndex : pad.node().next()) {
                RoadPad target = padAt(layer + 1, nextIndex);
                if (target == null) {
                    continue;
                }
                boolean taken = run.takenAt(layer) == pad.node().index()
                        && run.takenAt(layer + 1) == nextIndex;
                boolean live = run.layer() == layer + 1
                        && run.takenAt(layer) == pad.node().index();
                drawLink(viewers,
                        new Pos(pad.centerX() + 0.5, ROAD_Y + 1.4, pad.centerZ() + 0.5),
                        target,
                        taken ? TAKEN_COLOR : live ? kindColor(target.node()) : DIM_COLOR);
            }
        }

        // いま乗れる足場は光の柱で強調する
        for (RoadPad pad : pads) {
            Overlay.drawStraightLine(viewers,
                    new Pos(pad.centerX() + 0.5, ROAD_Y + 1.2, pad.centerZ() + 0.5),
                    new Pos(pad.centerX() + 0.5, ROAD_Y + 5.0, pad.centerZ() + 0.5),
                    kindColor(pad.node()), 1.4f);
        }
    }

    private void drawLink(List<Player> viewers, Pos from, RoadPad target, Color color) {
        boolean dim = color == DIM_COLOR;
        Pos to = new Pos(target.centerX() + 0.5, ROAD_Y + 1.4, target.centerZ() + 0.5);
        Overlay.drawStraightLine(viewers, from, to, color,
                dim ? 1.1f : 1.8f, dim ? ROAD_SPACING_DIM : ROAD_SPACING);
        if (!dim) {
            // 意味のある道は 2 本目を少し上にずらして重ね、太い線に見せる
            Overlay.drawStraightLine(viewers,
                    from.withY(from.y() + 0.35), to.withY(to.y() + 0.35),
                    color, 1.5f, ROAD_SPACING);
        }
    }

    private static Color kindColor(Roadmap.Node node) {
        return new Color(node.kind().color().red(),
                node.kind().color().green(),
                node.kind().color().blue());
    }

    /** ヘッドレス検証用。いま進行中のステージ（ロードマップ上なら null）。 */
    public Stage currentStage() {
        return stage;
    }

    public Instance lobby() {
        return lobby;
    }

    /**
     * 進行が止まってしまったときの脱出口（チャット `!next`）。
     * 何かの拍子に選択画面を閉じてしまっても、ランを捨てずに済むようにしておく。
     */
    public void forceAdvance(Player player) {
        if (run == null) {
            player.sendMessage(Component.text("ランが始まっていません", NamedTextColor.GRAY));
            return;
        }
        PlayerSession session = session(player);
        if (session.isMenuMandatory()) {
            player.sendMessage(Component.text("選択画面を開き直します", NamedTextColor.YELLOW));
            player.openInventory(session.openMenuInventory());
            return;
        }
        if (session.stage() != null) {
            player.sendMessage(Component.text("ステージを放棄してロードマップへ戻ります",
                    NamedTextColor.YELLOW));
            cleanupStage();
            buildRoadmap();
            moveToRoadmap(player);
            return;
        }
        player.sendMessage(Component.text("ロードマップを作り直しました", NamedTextColor.YELLOW));
        buildRoadmap();
        moveToRoadmap(player);
    }

    /**
     * 必須の選択画面を閉じられたら開き直す。
     * これがないと、Esc で閉じた瞬間にランが「何も起きない」状態で固まる。
     */
    public void onInventoryClose(net.minestom.server.event.inventory.InventoryCloseEvent event) {
        PlayerSession session = session(event.getPlayer());
        if (!session.isMenuMandatory() || event.getInventory() != session.openMenuInventory()) {
            return;
        }
        event.setNewInventory(session.openMenuInventory());
        event.getPlayer().sendActionBar(Component.text(
                "1 つ選んでください（閉じると先へ進めません）", NamedTextColor.YELLOW));
    }

    /** チャットコマンド用（デバッグ）。 */
    public String debugState() {
        if (run == null) {
            return "ラン未開始";
        }
        return "層=" + run.layer() + " 金=" + run.gold() + " エンバー=" + run.ember()
                + " コア=" + run.coreHp()
                + " デッキ=" + run.deck().librarySize()
                + (stage == null ? " (ロードマップ)"
                        : " フェーズ=" + stage.phase() + " ウェーブ=" + stage.waveNumber()
                          + "/" + stage.waveCount()
                          + String.format(" 移動距離=%.1f", stage.totalPathLength()));
    }

    /** 未使用の警告を避けるための保持（将来の拡張で使う）。 */
    @SuppressWarnings("unused")
    private static final ItemStack UNUSED = ItemStack.AIR;
}
