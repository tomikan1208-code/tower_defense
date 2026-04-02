package dev.antigravity.td;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;

public final class TDGame {
    private final InstanceContainer instance;
    private final List<Point> path;
    private final List<EnemyUnit> enemies = new ArrayList<>();
    private final List<Tower> towers = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final int layerNumber;

    private int tickCounter = 0;
    private int wave = 0;
    private int playerGold = 50;
    private int baseHp = 20;
    private int enemiesToSpawn = 0;
    private int nextSpawnTick = 0;
    private boolean waveRewardGranted = true;
    private boolean waveClearPending = false;

    public TDGame(InstanceContainer instance, List<Point> path, int layerNumber) {
        this.instance = instance;
        this.path = path;
        this.layerNumber = layerNumber;
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
        int baseY = Math.max(1, (int) Math.floor(p.y()));
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
        updateProjectiles();
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

    public List<Projectile> projectiles() {
        return projectiles;
    }

    public List<Tower> towers() {
        return towers;
    }

    public List<EnemyUnit> enemies() {
        return enemies;
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
        // 敵タイプをランダムに選択（重みに基づいて）
        EnemyType selectedType = selectEnemyTypeByWeight();
        
        // EntityTypeの作成
        Entity body = new Entity(selectedType.entityType());
        body.setNoGravity(true);
        body.setInstance(instance, path.get(0));

        // 敵パラメータ計算
        double maxHp = selectedType.calculateMaxHp(wave, layerNumber);
        double speed = selectedType.calculateSpeed(wave, layerNumber);
        int goldReward = selectedType.calculateGoldReward(wave, layerNumber);

        EnemyUnit enemy = new EnemyUnit(body, path, selectedType, maxHp, speed, goldReward);
        enemies.add(enemy);
    }

    /**
     * 敵タイプごとの重みに基づいてランダムに敵タイプを選択
     * Wave進行に応じて敵構成が段階的に変わる
     * 
     * Wave 1-2: 通常敵主体 (NORMAL 85%, FAST 15%)
     * Wave 3-4: 多様化開始 (NORMAL 70%, FAST 20%, ARMORED 10%)
     * Wave 5-6: 装甲敵増加 (NORMAL 55%, FAST 25%, ARMORED 20%)
     * Wave 7-8: 高難度 (NORMAL 40%, FAST 25%, ARMORED 30%, BOSS 5%)
     * Wave 9+: 最高難度 (NORMAL 30%, FAST 25%, ARMORED 35%, BOSS 10%)
     */
    private EnemyType selectEnemyTypeByWeight() {
        int rand = ThreadLocalRandom.current().nextInt(100);

        if (wave <= 2) {
            // Wave 1-2: 通常敵がメイン
            if (rand < 85) {
                return EnemyType.NORMAL;
            } else {
                return EnemyType.FAST;
            }
        } else if (wave <= 4) {
            // Wave 3-4: 装甲敵登場開始
            if (rand < 70) {
                return EnemyType.NORMAL;
            } else if (rand < 90) {
                return EnemyType.FAST;
            } else {
                return EnemyType.ARMORED;
            }
        } else if (wave <= 6) {
            // Wave 5-6: 装甲敵が増加
            if (rand < 55) {
                return EnemyType.NORMAL;
            } else if (rand < 80) {
                return EnemyType.FAST;
            } else {
                return EnemyType.ARMORED;
            }
        } else if (wave <= 8) {
            // Wave 7-8: 装甲敵がさらに増加、ボス敵登場
            if (rand < 40) {
                return EnemyType.NORMAL;
            } else if (rand < 65) {
                return EnemyType.FAST;
            } else if (rand < 95) {
                return EnemyType.ARMORED;
            } else {
                return EnemyType.BOSS;
            }
        } else {
            // Wave 9+: 最高難度
            if (rand < 30) {
                return EnemyType.NORMAL;
            } else if (rand < 55) {
                return EnemyType.FAST;
            } else if (rand < 90) {
                return EnemyType.ARMORED;
            } else {
                return EnemyType.BOSS;
            }
        }
    }

    private void updateEnemies() {
        Iterator<EnemyUnit> it = enemies.iterator();
        while (it.hasNext()) {
            EnemyUnit enemy = it.next();
            if (enemy.isDead()) {
                enemy.body().remove();
                it.remove();
                playerGold += enemy.goldReward();
                
                // 敵タイプごとのメッセージカラーを変える
                NamedTextColor color = switch(enemy.type()) {
                    case NORMAL -> NamedTextColor.WHITE;
                    case FAST -> NamedTextColor.YELLOW;
                    case ARMORED -> NamedTextColor.GOLD;
                    case BOSS -> NamedTextColor.RED;
                };
                Component msg = Component.text()
                    .append(Component.text("敵撃破! "))
                    .append(Component.text(enemy.type().displayName(), color))
                    .append(Component.text(" +" + enemy.goldReward() + "G", NamedTextColor.YELLOW))
                    .build();
                broadcastComponent(msg);
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
                // 発射体を生成してターゲット
                projectiles.add(new Projectile(tower, target, tower.type()));
                tower.fire();
            }
        }
    }

    private void updateProjectiles() {
        Iterator<Projectile> iter = projectiles.iterator();
        while (iter.hasNext()) {
            Projectile proj = iter.next();
            proj.tick();
            if (!proj.isAlive()) {
                iter.remove();
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
        broadcastComponent(text);
    }

    private void broadcastComponent(Component component) {
        Component text = Component.text()
                .append(Component.text("[TD] "))
                .append(component)
                .build();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            p.sendMessage(text);
        }
    }
}
