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
 */
public record Look(VillagerProfession profession, Material helmet, Material chestplate,
                   Material hand, double scale, int tiers) {

    public static final Look PLAIN = new Look(null, null, null, null, 1.0, 1);

    public static Look of() {
        return PLAIN;
    }

    public Look job(VillagerProfession profession) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers);
    }

    public Look helmet(Material helmet) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers);
    }

    public Look chestplate(Material chestplate) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers);
    }

    public Look hand(Material hand) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers);
    }

    public Look scale(double scale) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers);
    }

    public Look tiers(int tiers) {
        return new Look(profession, helmet, chestplate, hand, scale, tiers);
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
                Math.max(tiers, other.tiers));
    }
}
