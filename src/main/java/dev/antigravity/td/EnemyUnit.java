package dev.antigravity.td;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;

public final class EnemyUnit {
    private final Entity body;
    private final List<Point> path;
    private final EnemyType type;
    private final double maxHp;
    private final double speed;
    private final int goldReward;

    private double hp;
    private double pathProgress = 0.0;

    public EnemyUnit(Entity body, List<Point> path, EnemyType type, double maxHp, double speed, int goldReward) {
        this.body = body;
        this.path = path;
        this.type = type;
        this.maxHp = maxHp;
        this.speed = speed;
        this.goldReward = goldReward;
        this.hp = maxHp;
    }

    public Entity body() {
        return body;
    }

    public EnemyType type() {
        return type;
    }

    public int goldReward() {
        return goldReward;
    }

    public Pos position() {
        return lerpAlongPath(pathProgress);
    }

    public void damage(double amount) {
        hp -= amount;
    }

    public void takeDamage(double amount) {
        damage(amount);
    }

    public boolean isDead() {
        return hp <= 0.0;
    }

    public boolean isAlive() {
        return hp > 0.0;
    }

    public void advance() {
        pathProgress += speed;
    }

    public boolean reachedGoal() {
        return pathProgress >= path.size() - 1;
    }

    public void syncEntity() {
        Pos pos = lerpAlongPath(pathProgress);
        body.teleport(pos);
        // 敵タイプとHPを表示
        String hpText = String.format("%s HP %d/%d", 
            type.displayName(), 
            (int) Math.ceil(hp), 
            (int) maxHp);
        body.setCustomName(Component.text(hpText));
        body.setCustomNameVisible(true);
    }

    private Pos lerpAlongPath(double progress) {
        int i = (int) Math.floor(progress);
        double t = progress - i;

        if (i <= 0) {
            i = 0;
            t = Math.max(0.0, t);
        }
        if (i >= path.size() - 1) {
            Point end = path.get(path.size() - 1);
            return new Pos(end.x(), end.y(), end.z());
        }

        Point a = path.get(i);
        Point b = path.get(i + 1);
        return new Pos(
                a.x() + (b.x() - a.x()) * t,
                a.y() + (b.y() - a.y()) * t,
                a.z() + (b.z() - a.z()) * t
        );
    }
}
