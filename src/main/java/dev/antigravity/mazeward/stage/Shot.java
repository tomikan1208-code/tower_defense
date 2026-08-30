package dev.antigravity.mazeward.stage;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/**
 * タワーが撃ち出す弾。<b>実体のあるアイテムが飛ぶ</b>。
 *
 * <p>パーティクルの線だけだと「撃った」感触が薄いので、
 * 塔ごとに違うアイテム（矢・雪玉・ファイヤーチャージ…）を実際に飛ばす。
 * アイテム表示エンティティなので物理も当たり判定も持たず、
 * こちらで座標を補間して動かすだけ。</p>
 *
 * <p>ダメージは発射した瞬間に確定させているので、この弾は完全に見た目だけの存在。
 * 当たる前に敵が死んでも弾は最後まで飛ぶが、そのほうが手数の多さが目に見えて気持ちいい。</p>
 */
public final class Shot {

    /** 弾の速さ（ブロック / tick）。速すぎると見えず、遅すぎると発射と着弾がずれて見える。 */
    private static final double SPEED = 2.2;

    private static final int MIN_TICKS = 2;
    private static final int MAX_TICKS = 14;

    private final Entity entity;
    private final Pos from;
    private final Pos to;
    private final int totalTicks;
    private int age;

    private Shot(Entity entity, Pos from, Pos to, int totalTicks) {
        this.entity = entity;
        this.from = from;
        this.to = to;
        this.totalTicks = totalTicks;
    }

    /**
     * 弾を出す。
     *
     * @param item 飛ばすアイテム。null なら弾を出さない（範囲効果の塔など）
     */
    public static Shot spawn(Instance instance, Pos from, Pos to, Material item, float scale) {
        if (item == null) {
            return null;
        }
        double distance = from.distance(to);
        int ticks = (int) Math.round(distance / SPEED);
        ticks = Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));

        Entity entity = new Entity(EntityType.ITEM_DISPLAY);
        entity.setNoGravity(true);
        entity.editEntityMeta(ItemDisplayMeta.class, meta -> {
            meta.setItemStack(ItemStack.of(item));
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
            meta.setScale(new Vec(scale, scale, scale));
            meta.setViewRange(1.2f);
            meta.setBrightness(15, 15);
        });
        entity.setInstance(instance, from);
        return new Shot(entity, from, to, ticks);
    }

    /** 1 tick 進める。着弾したら false を返す。 */
    public boolean tick() {
        age++;
        if (age >= totalTicks) {
            remove();
            return false;
        }
        double t = age / (double) totalTicks;
        // 少しだけ山なりにすると、直線で飛ぶより弾道が読みやすい
        double lift = Math.sin(Math.PI * t) * 0.45;
        entity.teleport(new Pos(
                from.x() + (to.x() - from.x()) * t,
                from.y() + (to.y() - from.y()) * t + lift,
                from.z() + (to.z() - from.z()) * t));
        return true;
    }

    public void remove() {
        entity.remove();
    }
}
