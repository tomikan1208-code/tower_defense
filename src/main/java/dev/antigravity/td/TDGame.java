package dev.antigravity.td;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;

public final class TDGame {
    private final InstanceContainer instance;
    private final List<Point> path;
    private final List<EnemyUnit> enemies = new ArrayList<>();
    private final List<Tower> towers = new ArrayList<>();

    private int tickCounter = 0;
    private int wave = 0;
    private int playerGold = 50;
    private int baseHp = 20;
    private int enemiesToSpawn = 0;
    private int nextSpawnTick = 0;
    private boolean waveRewardGranted = true;
    private boolean waveClearPending = false;

    public TDGame(InstanceContainer instance, List<Point> path) {
        this.instance = instance;
        this.path = path;
    }

    public InstanceContainer instance() {
        return instance;
    }

    public void startWave() {
        if (enemiesToSpawn > 0 || !enemies.isEmpty()) {
            broadcast("すでにウェーブ進行中です");
            return;
        }
        wave++;
        enemiesToSpawn = 8 + wave * 2;
        nextSpawnTick = tickCounter;
        waveRewardGranted = false;
        waveClearPending = false;
        broadcast("Wave " + wave + " 開始");
    }

    public boolean consumeWaveCleared() {
        if (!waveClearPending) {
            return false;
        }
        waveClearPending = false;
        return true;
    }

    public void tryPlaceTower(Player player, String towerTypeToken) {
        Pos p = player.getPosition();
        int originX = (int) Math.floor(p.x());
        int originZ = (int) Math.floor(p.z());
        int baseY = Math.max(1, (int) Math.floor(p.y()) + 1);
        tryPlaceTowerAt(player, towerTypeToken, originX, originZ, baseY);
    }

    public void tryPlaceTowerAt(Player player, String towerTypeToken, int originX, int originZ, int baseY) {
        TowerType towerType = TowerType.fromToken(towerTypeToken);
        if (towerType == null) {
            player.sendMessage(Component.text("不明な塔タイプです: " + towerTypeToken));
            player.sendMessage(Component.text("使用可能: " + TowerType.usageKeys()));
            return;
        }

        if (playerGold < towerType.cost()) {
            player.sendMessage(Component.text("ゴールド不足: " + playerGold + "/" + towerType.cost()));
            return;
        }

        if (isTooCloseToPath(originX, originZ, towerType) || hasTowerOverlap(originX, originZ, towerType)) {
            player.sendMessage(Component.text("設置不可: 道沿いか、既存塔と重複しています"));
            return;
        }

        if (!hasSupportBlocks(originX, originZ, baseY, towerType)) {
            player.sendMessage(Component.text("設置不可: ブロックの1段上にのみ設置できます"));
            return;
        }

        playerGold -= towerType.cost();

        List<Entity> visuals = spawnTowerVisuals(originX, baseY, originZ, towerType);
        Pos center = footprintCenter(originX, baseY, originZ, towerType);
        towers.add(new Tower(towerType, center, originX, originZ, visuals));
        broadcast(towerType.displayName() + " を設置。残りGold: " + playerGold);
    }

    public void tick() {
        tickCounter++;

        if (enemiesToSpawn > 0 && tickCounter >= nextSpawnTick) {
            spawnEnemy();
            enemiesToSpawn--;
            nextSpawnTick = tickCounter + 20;
        }

        updateEnemies();
        updateTowers();
        updateBattleUi();

        if (baseHp <= 0) {
            broadcast("敗北: コアが破壊されました。!start で再挑戦");
            resetRun();
        }
    }

    public String debugState() {
        return "Wave=" + wave
                + " HP=" + baseHp
                + " Gold=" + playerGold
                + " Enemies=" + enemies.size()
                + " Towers=" + towers.size();
    }

    public int playerGold() {
        return playerGold;
    }

    public Tower findTowerAt(int x, int z) {
        for (Tower tower : towers) {
            int minX = tower.originX();
            int minZ = tower.originZ();
            int maxX = minX + tower.type().sizeX() - 1;
            int maxZ = minZ + tower.type().sizeZ() - 1;
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                return tower;
            }
        }
        return null;
    }

    public boolean tryUpgradeTower(Player player, Tower tower) {
        if (tower == null) {
            return false;
        }
        if (!tower.canUpgrade()) {
            player.sendMessage(Component.text("このタワーは最大レベルです", NamedTextColor.RED));
            return false;
        }
        int cost = tower.upgradeCost();
        if (playerGold < cost) {
            player.sendMessage(Component.text("ゴールド不足: " + playerGold + "/" + cost, NamedTextColor.RED));
            return false;
        }
        playerGold -= cost;
        tower.upgrade();
        player.sendMessage(Component.text(tower.type().displayName() + " を強化しました (Lv" + tower.level() + ")", NamedTextColor.GREEN));
        return true;
    }

    public String towerCatalog() {
        StringBuilder out = new StringBuilder("Tower: ");
        for (TowerType type : TowerType.values()) {
            if (!out.toString().endsWith(": ")) {
                out.append(" | ");
            }
            out.append(type.key())
               .append("(")
               .append(type.sizeX())
               .append("x")
               .append(type.sizeZ())
               .append(",")
               .append(type.cost())
               .append("G)");
        }
        return out.toString();
    }

    private void spawnEnemy() {
        Entity body = new Entity(EntityType.ZOMBIE);
        body.setNoGravity(true);
        body.setInstance(instance, path.get(0));

        EnemyUnit enemy = new EnemyUnit(body, path, 14.0 + wave * 2.0, 0.03 + wave * 0.004);
        enemies.add(enemy);
    }

    private void updateEnemies() {
        Iterator<EnemyUnit> it = enemies.iterator();
        while (it.hasNext()) {
            EnemyUnit enemy = it.next();
            if (enemy.isDead()) {
                enemy.body().remove();
                it.remove();
                continue;
            }

            enemy.advance();
            enemy.syncEntity();

            if (enemy.reachedGoal()) {
                enemy.body().remove();
                it.remove();
                baseHp--;
            }
        }

        if (!waveRewardGranted && enemiesToSpawn == 0 && enemies.isEmpty()) {
            playerGold += 10 + wave * 2;
            waveRewardGranted = true;
            waveClearPending = true;
            broadcast("Wave " + wave + " クリア! ボーナスGold獲得");
        }
    }

    private void updateTowers() {
        for (Tower tower : towers) {
            tower.cooldownTick();
            if (!tower.canFire()) {
                continue;
            }

            EnemyUnit target = null;
            double best = Double.MAX_VALUE;
            for (EnemyUnit enemy : enemies) {
                if (enemy.isDead()) {
                    continue;
                }
                double d = sqrDist(tower.position(), enemy.position());
                if (d <= tower.range() * tower.range() && d < best) {
                    best = d;
                    target = enemy;
                }
            }

            if (target != null) {
                target.damage(tower.damage());
                tower.fire();
                if (target.isDead()) {
                    playerGold += 3;
                }
            }
        }
    }

    private boolean isTooCloseToPath(int originX, int originZ, TowerType towerType) {
        for (Point p : path) {
            int px = (int) Math.floor(p.x());
            int pz = (int) Math.floor(p.z());
            for (int dx = 0; dx < towerType.sizeX(); dx++) {
                for (int dz = 0; dz < towerType.sizeZ(); dz++) {
                    int tx = originX + dx;
                    int tz = originZ + dz;
                    if (Math.abs(tx - px) <= 1 && Math.abs(tz - pz) <= 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasTowerOverlap(int originX, int originZ, TowerType towerType) {
        int minX = originX;
        int minZ = originZ;
        int maxX = originX + towerType.sizeX() - 1;
        int maxZ = originZ + towerType.sizeZ() - 1;

        for (Tower t : towers) {
            int tMinX = t.originX();
            int tMinZ = t.originZ();
            int tMaxX = t.originX() + t.type().sizeX() - 1;
            int tMaxZ = t.originZ() + t.type().sizeZ() - 1;
            boolean overlap = minX <= tMaxX && maxX >= tMinX && minZ <= tMaxZ && maxZ >= tMinZ;
            if (overlap) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSupportBlocks(int originX, int originZ, int baseY, TowerType towerType) {
        for (int dx = 0; dx < towerType.sizeX(); dx++) {
            for (int dz = 0; dz < towerType.sizeZ(); dz++) {
                int tx = originX + dx;
                int tz = originZ + dz;
                Block support = instance.getBlock(tx, baseY - 1, tz);
                Block towerSpace = instance.getBlock(tx, baseY, tz);
                if (support == Block.AIR || towerSpace != Block.AIR) {
                    return false;
                }
            }
        }
        return true;
    }

    private Pos footprintCenter(int originX, int baseY, int originZ, TowerType towerType) {
        double cx = originX + towerType.sizeX() / 2.0;
        double cz = originZ + towerType.sizeZ() / 2.0;
        return new Pos(cx, baseY, cz);
    }

    private List<Entity> spawnTowerVisuals(int originX, int baseY, int originZ, TowerType towerType) {
        List<Entity> visuals = new ArrayList<>();
        for (int dx = 0; dx < towerType.sizeX(); dx++) {
            for (int dz = 0; dz < towerType.sizeZ(); dz++) {
                Entity entity = new Entity(resolveEntityForSegment(towerType, dx, dz));
                entity.setNoGravity(true);

                double yOffset = towerType == TowerType.SNOWBALL ? 0.05 : 0.0;
                Pos mobPos = new Pos(originX + dx + 0.5, baseY + yOffset, originZ + dz + 0.5);
                entity.setInstance(instance, mobPos);
                entity.setCustomName(Component.text(towerType.displayName()));
                entity.setCustomNameVisible(false);
                tryApplyVisualScale(entity, towerType.visualScale());
                visuals.add(entity);
            }
        }
        return visuals;
    }

    private EntityType resolveEntityForSegment(TowerType towerType, int dx, int dz) {
        if (towerType == TowerType.POISON && dz == 1) {
            return EntityType.SPIDER;
        }
        if (towerType == TowerType.LIGHTNING_BALL && dz == 1) {
            return EntityType.ALLAY;
        }
        if (towerType == TowerType.SNOWBALL && dx == 1 && dz == 1) {
            return EntityType.SNOW_GOLEM;
        }
        return towerType.entityType();
    }

    private void tryApplyVisualScale(Entity entity, double scale) {
        if (Math.abs(scale - 1.0) < 0.001) {
            return;
        }

        try {
            Class<?> attributeClass = Class.forName("net.minestom.server.entity.attribute.Attribute");
            Object scaleAttribute = attributeClass.getField("SCALE").get(null);
            Object attributeInstance = entity.getClass()
                    .getMethod("getAttribute", attributeClass)
                    .invoke(entity, scaleAttribute);
            attributeInstance.getClass()
                    .getMethod("setBaseValue", double.class)
                    .invoke(attributeInstance, scale);
        } catch (ReflectiveOperationException ignored) {
            // Minestom側のAPI差異がある場合はそのまま表示する
        }
    }

    private double sqrDist(Point a, Point b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private void resetRun() {
        for (EnemyUnit enemy : enemies) {
            enemy.body().remove();
        }
        enemies.clear();

        for (Tower tower : towers) {
            for (Entity visual : tower.visuals()) {
                visual.remove();
            }
        }
        towers.clear();

        enemiesToSpawn = 0;
        baseHp = 20;
        playerGold = 50;
        wave = 0;
    }

    private void updateBattleUi() {
        if (tickCounter % 10 != 0) {
            return;
        }

        Component ui = Component.text()
                .append(Component.text("タワーHP: ", NamedTextColor.GOLD))
                .append(Component.text(baseHp, NamedTextColor.RED))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("所持金: ", NamedTextColor.GOLD))
                .append(Component.text(playerGold, NamedTextColor.YELLOW))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Wave: ", NamedTextColor.AQUA))
                .append(Component.text(wave, NamedTextColor.WHITE))
                .build();

        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (player.getInstance() == instance) {
                player.sendActionBar(ui);
            }
        }
    }

    private void broadcast(String msg) {
        Component text = Component.text("[TD] " + msg);
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            p.sendMessage(text);
        }
    }
}
