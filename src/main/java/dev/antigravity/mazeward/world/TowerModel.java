package dev.antigravity.mazeward.world;

import dev.antigravity.mazeward.tower.Look;
import dev.antigravity.mazeward.tower.TowerKind;
import java.util.ArrayList;
import java.util.List;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.metadata.other.SlimeMeta;
import net.minestom.server.entity.metadata.villager.VillagerMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

/**
 * 台座の上に立つタワー本体（エンティティ）の生成と向き。
 *
 * <p>タワーはもともとブロック 1 個だった。並べてみると 9 種類が
 * 「色違いの立方体」にしかならず、上空から俯瞰したときに何を置いたか分からない。
 * エンティティにすると輪郭で見分けられるので、盤面を覚え直さずに済む。</p>
 *
 * <p>Minestom の {@link Entity} は AI を持たないので、置いただけでは動かない。
 * 意図的に動かすのは {@link #aimAt} の向きだけで、それ以外は台座の上に静止する。</p>
 */
public final class TowerModel {

    /**
     * 2 段目以降の縮み方。同じ大きさで積むと 2 体が重なっただけに見える。
     *
     * <p>大きさを変えるのは基本これと、砲塔の「大口径」だけ。
     * マス数や強化段階で大きくしていた頃は、育った塔ほど頭上の名前と性能表示に被って
     * かえって読めなくなっていた。<b>広さは台座と体の数で見せる</b>。</p>
     */
    private static final double TIER_SHRINK = 0.72;

    private TowerModel() {
    }

    /**
     * タワー本体を台座の上に立たせる。
     *
     * <p>「二段」なら上に積み、「並べる」なら占有マス 1 つにつき 1 体置く。
     * どちらでもない塔は footprint の中心に 1 体だけ。先頭が本体（狙いを向けるのはこれ）。</p>
     *
     * @param look        基本の見た目に特化を重ねたもの
     * @param cellCenters 占有マスそれぞれの天面中心
     * @param center      footprint の中心（足元）
     */
    public static List<Entity> spawn(Instance instance, TowerKind kind, Look look,
                                     List<Pos> cellCenters, Pos center) {
        List<Pos> bases = look.spread() ? cellCenters : List.of(center);
        // 並べるときは 1 体が 1 マスぶんなので、大きさの基準も 1 マスで見る
        int cellsPerBody = look.spread() ? 1 : cellCenters.size();

        List<Entity> bodies = new ArrayList<>(bases.size() * Math.max(1, look.tiers()));
        for (Pos base : bases) {
            double y = base.y();
            for (int tier = 0; tier < Math.max(1, look.tiers()); tier++) {
                double scale = look.scale() * Math.pow(TIER_SHRINK, tier);
                LivingEntity body = create(instance, kind, look, base.withY(y), scale, cellsPerBody);
                bodies.add(body);
                y += body.getBoundingBox().height() * scale;
            }
        }
        return bodies;
    }

    private static LivingEntity create(Instance instance, TowerKind kind, Look look,
                                       Pos at, double scale, int cells) {
        LivingEntity body = new LivingEntity(kind.model().type());
        body.setNoGravity(true);
        body.setInvulnerable(true);
        body.setSilent(true);

        if (look.profession() != null && body.getEntityMeta() instanceof VillagerMeta villager) {
            villager.setVillagerData(villager.getVillagerData().withProfession(look.profession()));
        }
        // スライム系は「大きさ」がメタデータ側にあり、拡大率とは別枠で効く
        if (body.getEntityMeta() instanceof SlimeMeta slime) {
            slime.setSize(cells >= 4 ? 2 : 1);
        }
        equip(body, EquipmentSlot.HELMET, look.helmet());
        equip(body, EquipmentSlot.CHESTPLATE, look.chestplate());
        equip(body, EquipmentSlot.MAIN_HAND, look.hand());

        body.getAttribute(Attribute.SCALE).setBaseValue(scale);
        body.setInstance(instance, at);
        return body;
    }

    private static void equip(LivingEntity body, EquipmentSlot slot, Material material) {
        if (material != null) {
            body.setEquipment(slot, ItemStack.of(material));
        }
    }

    /**
     * 狙っている相手のほうを向かせる。
     *
     * <p>どの塔がどの敵を撃っているのかは、弾道より先に <b>向き</b> で伝わる。
     * 射線が通っていないのに撃ち続けているように見える事故も、これで気付ける。</p>
     */
    public static void aimAt(List<Entity> bodies, double targetX, double targetZ) {
        for (Entity body : bodies) {
            double dx = targetX - body.getPosition().x();
            double dz = targetZ - body.getPosition().z();
            if (dx == 0 && dz == 0) {
                continue;
            }
            body.setView((float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
        }
    }

    /** 検査中の塔を光らせる。狙っているのがどれかを、いちばん強く伝えられる。 */
    public static void setGlowing(List<Entity> bodies, boolean glowing) {
        for (Entity body : bodies) {
            // 売却や強化で作り直された直後は、消す相手がもう居ないことがある
            if (!body.isRemoved()) {
                body.getEntityMeta().setHasGlowingEffect(glowing);
            }
        }
    }
}
