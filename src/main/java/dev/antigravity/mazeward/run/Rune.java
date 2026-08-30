package dev.antigravity.mazeward.run;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

/**
 * 障害物カードに付与する「ルーン」＝特殊障害物。
 *
 * <p>Emberward のルーンに相当する要素。カード 1 枚にルーンを付けると、
 * そのカードが作る壁セルすべてに効果が乗る。</p>
 *
 * <p>これがゲームに効いてくるのは、<b>迷路の形とタワーの置き場所という 2 つの決定に
 * 3 つ目の軸を足す</b> から。「補強ルーンを付けた 2x2 のカードは、
 * どこに置けば砲塔の土台としていちばん効くか」という考え方が生まれる。
 * 経路を伸ばすのに使うか、強化された土台にするかで、同じ 1 枚がさらに奪い合う。</p>
 *
 * <p>効果は 2 種類に分かれる。</p>
 * <ul>
 *   <li><b>土台系</b>（補強・集光・標）— その壁に乗るタワーを強化する</li>
 *   <li><b>地形系</b>（棘・茨・金脈）— そばを通る敵に直接作用する</li>
 * </ul>
 *
 * <p>増やすときはここに enum 定数を 1 つ足し、{@code Stage} の効果適用に分岐を足すだけ。</p>
 */
public enum Rune {

    REINFORCED("補強", "この壁に乗るタワーの攻撃力 +20%",
            Material.NETHERITE_SCRAP, Block.IRON_BLOCK, NamedTextColor.WHITE),

    LENS("集光", "この壁に乗るタワーの射程 +1.5",
            Material.AMETHYST_SHARD, Block.AMETHYST_BLOCK, NamedTextColor.LIGHT_PURPLE),

    BEACON("標", "この壁に乗るタワーの攻撃間隔 -15%",
            Material.REDSTONE_TORCH, Block.REDSTONE_BLOCK, NamedTextColor.RED),

    SPIKE("棘", "隣を通る敵に毎秒 8 ダメージ",
            Material.IRON_SWORD, Block.CRYING_OBSIDIAN, NamedTextColor.DARK_AQUA),

    BRAMBLE("茨", "隣を通る敵の移動速度 -30%",
            Material.VINE, Block.GREEN_TERRACOTTA, NamedTextColor.GREEN),

    GOLD_VEIN("金脈", "近くで敵を倒すと +3G",
            Material.RAW_GOLD, Block.GOLD_BLOCK, NamedTextColor.GOLD);

    /** 土台系ルーンの補正値。 */
    public static final double REINFORCED_DAMAGE = 1.20;
    public static final double LENS_RANGE = 1.5;
    public static final double BEACON_COOLDOWN = 0.85;

    /** 地形系ルーンの効果範囲と強さ。 */
    public static final double FIELD_RADIUS = 1.7;
    public static final double SPIKE_DPS = 8.0;
    public static final double BRAMBLE_SLOW = 0.30;
    public static final double GOLD_VEIN_RADIUS = 4.0;
    public static final int GOLD_VEIN_BONUS = 3;

    private final String displayName;
    private final String description;
    private final Material icon;
    private final Block wallBlock;
    private final TextColor color;

    Rune(String displayName, String description, Material icon, Block wallBlock, TextColor color) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.wallBlock = wallBlock;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Material icon() {
        return icon;
    }

    /** ルーン付きの壁は、地形テーマの壁ではなくこのブロックで描く（上空から一目で分かるように）。 */
    public Block wallBlock() {
        return wallBlock;
    }

    public TextColor color() {
        return color;
    }

    /** タワーの性能を変えるタイプか。 */
    public boolean towerRune() {
        return this == REINFORCED || this == LENS || this == BEACON;
    }

    /** そばを通る敵に作用するタイプか。 */
    public boolean fieldRune() {
        return this == SPIKE || this == BRAMBLE;
    }

    public static Rune random(java.util.Random random) {
        return values()[random.nextInt(values().length)];
    }
}
