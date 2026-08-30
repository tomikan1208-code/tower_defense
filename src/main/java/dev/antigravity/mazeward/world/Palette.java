package dev.antigravity.mazeward.world;

import java.util.List;
import net.minestom.server.color.Color;
import net.minestom.server.instance.block.Block;
import net.minestom.server.particle.Particle;

/** 色・ブロック・地域テーマの一元管理。見た目を変えたいときはここだけ触る。 */
public final class Palette {

    // ---- 経路プレビューの 3 色（仕様の必須要件） -------------------------------

    /** 青: 現在の敵経路。 */
    public static final Color PATH_CURRENT = new Color(64, 150, 255);

    /** 赤: 障害物を置いた後の予測経路。 */
    public static final Color PATH_PREVIEW = new Color(255, 64, 64);

    /** 黄: 配置予定の障害物。 */
    public static final Block GHOST_VALID = Block.YELLOW_STAINED_GLASS;

    /** 濃赤: 配置できない障害物。 */
    public static final Block GHOST_INVALID = Block.RED_STAINED_GLASS;

    /** 緑: 配置可能なタワーのゴースト。 */
    public static final Block GHOST_TOWER = Block.LIME_STAINED_GLASS;

    public static final Color RANGE_RING = new Color(255, 214, 96);
    public static final Color SPAWN_MARK = new Color(255, 90, 90);
    public static final Color CORE_MARK = new Color(120, 220, 255);

    public static final Particle.Dust DUST = Particle.DUST;

    // ---- 地域テーマ ------------------------------------------------------------

    /**
     * 地域ごとの見た目。
     *
     * <p>壁と岩は <b>1 種類ではなく候補のリスト</b> を持つ。
     * 同じブロックが延々と並ぶと迷路が無機質な塊にしか見えないため。</p>
     *
     * <p>ただし混ぜ方が違う。<b>壁はカード単位</b>で 1 種類に揃える
     * （カードの素材番号で引く）。1 枚のカードが作った壁がひと塊に見えるので、
     * 迷路のどこを自分がどのカードで組んだのかが分かる。
     * <b>岩はセル座標で混ぜる</b>。こちらは自然地形なので、
     * バラけていたほうがそれらしく見える。</p>
     *
     * @param floor  空き地の床
     * @param walls  プレイヤーが置いた壁の候補
     * @param rocks  初期地形の岩の候補（＝無料の壁）
     * @param border アリーナ外周
     * @param accent 壁・岩の足元に敷く装飾
     */
    public record Theme(String name, Block floor, List<Block> walls, List<Block> rocks,
                        Block border, Block accent) {

        /**
         * カードの素材番号に対応する壁ブロック。
         * 1 枚のカードが作る壁は全部これになるので、盤面上で 1 つの塊として読める。
         */
        public Block wallForVariant(int variant) {
            return walls.get(Math.floorMod(variant, walls.size()));
        }

        public Block rockFor(int x, int z) {
            return pick(rocks, x, z);
        }

        /** 代表色（プレビューやアイコン用）。 */
        public Block wall() {
            return walls.get(0);
        }

        private static Block pick(List<Block> candidates, int x, int z) {
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            // 座標を撹拌して決める。乱数を使わないので描き直しても揺れない。
            int hash = (x * 73_856_093) ^ (z * 19_349_663);
            hash ^= hash >>> 13;
            return candidates.get(Math.floorMod(hash, candidates.size()));
        }
    }

    public static final Theme MISTY_FOREST = new Theme("霧の森",
            Block.MOSS_BLOCK,
            List.of(Block.MOSSY_STONE_BRICKS, Block.COBBLESTONE, Block.OAK_PLANKS,
                    Block.STONE_BRICKS, Block.MOSSY_COBBLESTONE, Block.OAK_LOG),
            List.of(Block.MOSSY_COBBLESTONE, Block.ANDESITE, Block.STONE, Block.COBBLESTONE),
            Block.STONE_BRICKS, Block.PODZOL);

    public static final Theme BONE_DESERT = new Theme("骨の砂漠",
            Block.SAND,
            List.of(Block.CUT_SANDSTONE, Block.SANDSTONE, Block.BRICKS,
                    Block.SMOOTH_SANDSTONE, Block.TERRACOTTA, Block.STRIPPED_JUNGLE_LOG),
            List.of(Block.SANDSTONE, Block.RED_SANDSTONE, Block.SMOOTH_SANDSTONE, Block.CALCITE),
            Block.SMOOTH_SANDSTONE, Block.RED_SAND);

    public static final Theme FROZEN_KEEP = new Theme("凍てつく城砦",
            Block.SNOW_BLOCK,
            List.of(Block.POLISHED_DIORITE, Block.STONE_BRICKS, Block.PACKED_ICE,
                    Block.DIORITE, Block.POLISHED_ANDESITE, Block.SPRUCE_PLANKS),
            List.of(Block.PACKED_ICE, Block.BLUE_ICE, Block.DIORITE, Block.CALCITE),
            Block.DEEPSLATE_BRICKS, Block.BLUE_ICE);

    public static final Theme EMBER_FORGE = new Theme("熾火の炉",
            Block.BLACKSTONE,
            List.of(Block.POLISHED_BLACKSTONE_BRICKS, Block.NETHER_BRICKS, Block.BLACKSTONE,
                    Block.POLISHED_BASALT, Block.CHISELED_POLISHED_BLACKSTONE, Block.CRIMSON_PLANKS),
            List.of(Block.BASALT, Block.BLACKSTONE, Block.MAGMA_BLOCK, Block.POLISHED_BASALT),
            Block.NETHER_BRICKS, Block.MAGMA_BLOCK);

    public static Theme themeForLayer(int layer) {
        if (layer >= 7) {
            return EMBER_FORGE;
        }
        if (layer >= 5) {
            return FROZEN_KEEP;
        }
        if (layer >= 3) {
            return BONE_DESERT;
        }
        return MISTY_FOREST;
    }

    private Palette() {
    }
}
