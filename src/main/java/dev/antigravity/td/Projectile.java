package dev.antigravity.td;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.display.BlockDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.InstanceContainer;

public final class Projectile {
    private final EnemyUnit targetEnemy;
    private final double damage;
    private final TowerType.ProjectileColor color;
    private final Entity orbDisplay;
    private Pos position;
    private final double velocityX;
    private final double velocityY;
    private final double velocityZ;
    private final double speed = 0.36;
    private final double hitRadius = 0.65;
    private HitEvent pendingHitEvent;
    private int aliveTicksRemaining = 300; // 最大15秒生存

    public Projectile(InstanceContainer instance, Tower tower, EnemyUnit targetEnemy, TowerType towerType) {
        this.targetEnemy = targetEnemy;
        this.damage = tower.damage();
        this.color = towerType.projectileColor();
        this.position = tower.position().withY(tower.position().y() + 1);

        Pos targetPos = targetEnemy != null ? targetEnemy.position() : tower.position();
        double dx = targetPos.x() - position.x();
        double dy = targetPos.y() - position.y();
        double dz = targetPos.z() - position.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= 0.0001) {
            this.velocityX = 0.0;
            this.velocityY = 0.0;
            this.velocityZ = 0.0;
        } else {
            this.velocityX = (dx / dist) * speed;
            this.velocityY = (dy / dist) * speed;
            this.velocityZ = (dz / dist) * speed;
        }

        this.orbDisplay = new Entity(EntityType.BLOCK_DISPLAY);
        orbDisplay.setNoGravity(true);
        orbDisplay.setInstance(instance, position);
        orbDisplay.editEntityMeta(BlockDisplayMeta.class, meta -> {
            meta.setBlockState(projectileBlock());
        });
    }

    public void tick() {
        if (targetEnemy == null || !targetEnemy.isAlive()) {
            dispose();
            aliveTicksRemaining = 0;
            return;
        }

        Pos targetPos = targetEnemy.body().getPosition();
        double dx = targetPos.x() - position.x();
        double dy = targetPos.y() - position.y();
        double dz = targetPos.z() - position.z();

        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // ターゲットに到達（衝突判定）
        if (dist < hitRadius) {
            targetEnemy.takeDamage(damage);
            pendingHitEvent = new HitEvent(targetPos, damage, color);
            dispose();
            aliveTicksRemaining = 0;
            return;
        }

        // 固定弾速で前進
        position = new Pos(
                position.x() + velocityX,
                position.y() + velocityY,
                position.z() + velocityZ);
        orbDisplay.teleport(position);

        aliveTicksRemaining--;
        if (aliveTicksRemaining <= 0) {
            dispose();
        }
    }

    public boolean isAlive() {
        return aliveTicksRemaining > 0;
    }

    public Pos position() {
        return position;
    }

    public TowerType.ProjectileColor color() {
        return color;
    }

    public HitEvent consumeHitEvent() {
        HitEvent event = pendingHitEvent;
        pendingHitEvent = null;
        return event;
    }

    public void dispose() {
        if (!orbDisplay.isRemoved()) {
            orbDisplay.remove();
        }
    }

    private Block projectileBlock() {
        return switch (color) {
            case BASIC -> Block.WHITE_CONCRETE;
            case FLAME -> Block.RED_CONCRETE;
            case FROST -> Block.LIGHT_BLUE_CONCRETE;
            case LIGHTNING -> Block.YELLOW_CONCRETE;
            case POISON -> Block.LIME_CONCRETE;
            case SNOWBALL -> Block.CYAN_CONCRETE;
        };
    }

    public record HitEvent(Pos position, double damage, TowerType.ProjectileColor color) {}
}
