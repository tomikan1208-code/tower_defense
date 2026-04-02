package dev.antigravity.td;

import net.minestom.server.coordinate.Pos;

public final class Projectile {
    private final Tower tower;
    private final EnemyUnit targetEnemy;
    private final double damage;
    private final TowerType.ProjectileColor color;
    private Pos position;
    private final double speed = 0.15;
    private int aliveTicksRemaining = 300; // 最大15秒生存

    public Projectile(Tower tower, EnemyUnit targetEnemy, TowerType towerType) {
        this.tower = tower;
        this.targetEnemy = targetEnemy;
        this.damage = tower.damage();
        this.color = towerType.projectileColor();
        this.position = tower.position().withY(tower.position().y() + 1);
    }

    public void tick() {
        if (targetEnemy == null || !targetEnemy.isAlive()) {
            aliveTicksRemaining = 0;
            return;
        }

        Pos targetPos = targetEnemy.body().getPosition();
        double dx = targetPos.x() - position.x();
        double dy = targetPos.y() - position.y();
        double dz = targetPos.z() - position.z();

        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // ターゲットに到達（衝突判定）
        if (dist < 0.3) {
            targetEnemy.takeDamage(damage);
            aliveTicksRemaining = 0;
            return;
        }

        // ターゲット方向に移動
        if (dist > 0) {
            position = new Pos(
                    position.x() + (dx / dist) * speed,
                    position.y() + (dy / dist) * speed,
                    position.z() + (dz / dist) * speed);
        }

        aliveTicksRemaining--;
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

    public double hitBoxRadius() {
        return 0.15;
    }
}
