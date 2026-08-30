package dev.antigravity.mazeward.stage;

import net.minestom.server.ServerFlag;
import net.minestom.server.collision.Aerodynamics;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.Instance;

/**
 * タワーが撃ち出す弾。<b>本物の矢や雪玉が飛ぶ</b>。
 *
 * <p>以前はアイテム表示エンティティを毎 tick テレポートさせていた。
 * テレポートは絶対座標の packet なので、クライアントは補間せずその場に飛ばす。
 * 結果、弾が 1 tick ごとにワープしてカクついて見えていた。</p>
 *
 * <p>いまは <b>速度を与えて飛ばすだけ</b>。あとは Minestom が毎 tick 動かし、
 * クライアントは相対移動の packet を受けてフレーム間を補間するので、
 * 20 tick/秒でも滑らかに流れて見える。</p>
 *
 * <p>アイテムではなく矢そのものを飛ばすのは、矢が進行方向を向いてくれるから。
 * アイテムの板が飛ぶより、何が飛んでいるのかが一目で分かる。</p>
 *
 * <p>ダメージは発射した瞬間に確定させているので、この弾は完全に見た目だけの存在。
 * 当たる前に敵が死んでも弾は最後まで飛ぶが、そのほうが手数の多さが目に見えて気持ちいい。</p>
 */
public final class Shot {

    /** 弾の速さ（ブロック / tick）。速すぎると見えず、遅すぎると発射と着弾がずれて見える。 */
    private static final double SPEED = 1.6;

    private static final int MIN_TICKS = 2;
    private static final int MAX_TICKS = 20;

    private final Entity entity;
    private final int totalTicks;
    private int age;

    private Shot(Entity entity, int totalTicks) {
        this.entity = entity;
        this.totalTicks = totalTicks;
    }

    /**
     * 弾を出す。
     *
     * @param type 飛ばすエンティティ。null なら弾を出さない（範囲効果の塔など）
     */
    public static Shot spawn(Instance instance, Pos from, Pos to, EntityType type) {
        if (type == null) {
            return null;
        }
        double distance = from.distance(to);
        if (distance < 1e-6) {
            return null;
        }
        int ticks = (int) Math.round(distance / SPEED);
        ticks = Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));

        Vec direction = to.sub(from).asVec().normalize();

        Entity entity = new Entity(type);
        entity.setNoGravity(true);
        // 見た目だけの弾なので、壁にも敵にも当たらずに飛び切ってほしい
        entity.setHasPhysics(false);
        // 空気抵抗が掛かると手前で失速する。等速で飛ばすために抵抗も重力も抜く
        entity.setAerodynamics(new Aerodynamics(0.0, 1.0, 1.0));

        // 矢は自分の向きで描画されるので、飛ぶ方向を向かせておく
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
        float pitch = (float) Math.toDegrees(-Math.asin(direction.y()));
        entity.setInstance(instance, from.withView(yaw, pitch));

        // Minestom の速度は「ブロック / 秒」
        entity.setVelocity(direction.mul(SPEED * ServerFlag.SERVER_TICKS_PER_SECOND));
        return new Shot(entity, ticks);
    }

    /** 1 tick 進める。着弾したら false を返す。動かすのは Minestom 側なので、ここは寿命だけ。 */
    public boolean tick() {
        if (++age >= totalTicks) {
            remove();
            return false;
        }
        return true;
    }

    public void remove() {
        entity.remove();
    }
}
