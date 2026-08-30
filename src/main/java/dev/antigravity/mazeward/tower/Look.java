package dev.antigravity.mazeward.tower;

import net.minestom.server.entity.VillagerProfession;
import net.minestom.server.item.Material;

/**
 * タワー本体の見た目。
 *
 * <p>最終段階の 2 択は性能だけでなく <b>見た目も分かれる</b>。
 * 特化を選んだあとの盤面は「同じ塔が並んでいる」ように見えてしまい、
 * どちらに振ったのかをいちいち開いて確かめることになるため。
 * 装備・職業・大きさ・段数と、塔ごとに効く軸が違うので、
 * 「何を変えるか」を 1 つの値にまとめて持たせている。</p>
 *
 * <p>指定のない項目は {@code null}（＝素のまま）。
 * {@link #with(Look)} で基本の見た目に特化の見た目を重ねる。</p>
 *
 * @param profession 村人の職業
 * @param helmet     頭に載せるもの。ブロックを指定すればそれが頭に乗る
 * @param chestplate 胴の装備
 * @param hand       手に持たせるもの
 * @param scale      大きさの倍率（重ねるときは掛け合わせる）
 * @param tiers      段数。2 なら上にもう 1 体、少し小さく積む
 * @param spread     占有マス 1 つにつき 1 体ずつ並べるか。1x2 なら 2 体が横に並ぶ
 */
public record Look(VillagerProfession profession, Material helmet, Material chestplate,
                   Material hand, double scale, int tiers, boolean spread) {

    public static final Look PLAIN = new Look(null, null, null, null, 1.0, 1, false);

    public static Look of() {
        return PLAIN;
    }

    public Look job(VillagerProfession profession) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, spread);
    }

    public Look helmet(Material helmet) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, spread);
    }

    public Look chestplate(Material chestplate) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, spread);
    }

    public Look hand(Material hand) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, spread);
    }

    public Look scale(double scale) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, spread);
    }

    public Look tiers(int tiers) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, spread);
    }

    /**
     * 占有マスごとに 1 体ずつ並べる。
     *
     * <p>1x2 や 2x2 の塔を <b>大きくして</b> 存在感を出すと、名前と性能の表示に被って読めなくなる。
     * 占有マスに 1 体ずつ並べれば、大きさを変えずに「広い塔だ」と伝わる。</p>
     */
    public Look spreading() {
        return new Look(profession, helmet, chestplate, hand, scale, tiers, true);
    }

    /** 基本の見た目に別の見た目を重ねる。指定のあるものだけ上書きし、倍率は掛け合わせる。 */
    public Look with(Look other) {
        if (other == null) {
            return this;
        }
        return new Look(
                other.profession != null ? other.profession : profession,
                other.helmet != null ? other.helmet : helmet,
                other.chestplate != null ? other.chestplate : chestplate,
                other.hand != null ? other.hand : hand,
                scale * other.scale,
                Math.max(tiers, other.tiers),
                spread || other.spread);
    }
}
