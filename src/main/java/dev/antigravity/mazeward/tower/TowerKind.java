package dev.antigravity.mazeward.tower;

import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Shapes;
import java.util.List;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;

/**
 * タワー定義。<b>タワーを増やすときはここに enum 定数を 1 つ足すだけでよい。</b>
 *
 * <p>形状を持つのが肝で、2x2 のタワーを置きたければ 2x2 の壁面を「わざわざ作る」必要がある。
 * これが「経路を伸ばすために壁を置く」のか「タワー土台を作るために壁を置く」のかという
 * カード 1 枚の奪い合いを生む。</p>
 */
public enum TowerKind {

    ARROW(
            "弓塔", "安価で手数が多い基礎火力。まずこれで骨組みを作る。",
            Shapes.DOT, Material.BOW, Block.COBBLESTONE_WALL,
            Element.NONE, AttackStyle.SINGLE,
            30, 5.5, 12, 7.0,
            0.0, 0, 0.0, 0, 0.0, 0,
            SoundEvent.ENTITY_ARROW_SHOOT, 1.5f, Material.ARROW, 0.7f),

    FROST(
            "氷塔", "ダメージは低いが 40% 減速。キルゾーンの滞在時間を伸ばす。",
            Shapes.DOT, Material.PACKED_ICE, Block.BLUE_ICE,
            Element.ICE, AttackStyle.SINGLE,
            45, 5.0, 18, 3.0,
            0.0, 0, 0.40, 45, 0.0, 0,
            SoundEvent.BLOCK_GLASS_BREAK, 1.6f, Material.SNOWBALL, 0.7f),

    BRAZIER(
            "火炉", "射程内の経路を燃焼帯に変える。弾を撃たず、通過する敵を焼き続ける。",
            Shapes.I2, Material.CAMPFIRE, Block.MAGMA_BLOCK,
            Element.FIRE, AttackStyle.AURA,
            60, 3.6, 20, 0.0,
            0.0, 0, 0.0, 0, 5.0, 60,
            SoundEvent.ITEM_FIRECHARGE_USE, 0.8f, null, 0f),

    CANNON(
            "砲塔", "低速・高ダメージの範囲攻撃。敵が密集する折り返しに置く。",
            Shapes.O, Material.TNT, Block.BLAST_FURNACE,
            Element.FIRE, AttackStyle.SPLASH,
            110, 6.5, 40, 26.0,
            2.2, 0, 0.0, 0, 0.0, 0,
            SoundEvent.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, Material.FIRE_CHARGE, 0.9f),

    TESLA(
            "雷塔", "3 体に連鎖する。蛇行迷路で経路が平行に並ぶほど強い。",
            Shapes.O, Material.LIGHTNING_ROD, Block.COPPER_BLOCK,
            Element.ARC, AttackStyle.CHAIN,
            130, 5.0, 22, 11.0,
            0.0, 3, 0.0, 0, 0.0, 0,
            SoundEvent.BLOCK_CONDUIT_ATTACK_TARGET, 1.7f, Material.AMETHYST_SHARD, 0.7f),

    BALLISTA(
            "弩塔", "射程が極端に長く、直線上の敵を貫く。飛行の直線ルートの見張りに。",
            Shapes.I3, Material.CROSSBOW, Block.DARK_OAK_FENCE,
            Element.NONE, AttackStyle.PIERCE,
            100, 12.0, 50, 30.0,
            0.0, 3, 0.0, 0, 0.0, 0,
            SoundEvent.ITEM_CROSSBOW_SHOOT, 0.9f, Material.SPECTRAL_ARROW, 1.0f);

    public static final int MAX_LEVEL = 3;

    private final String displayName;
    private final String description;
    private final Shape shape;
    private final Material icon;
    private final Block model;
    private final Element element;
    private final AttackStyle style;
    private final int baseCost;
    private final double baseRange;
    private final int baseCooldown;
    private final double baseDamage;
    private final double splashRadius;
    private final int chainTargets;
    private final double slowFactor;
    private final int slowTicks;
    private final double burnDps;
    private final int burnTicks;
    private final SoundEvent fireSound;
    private final float firePitch;
    private final Material projectile;
    private final float projectileScale;

    TowerKind(String displayName, String description, Shape shape, Material icon, Block model,
              Element element, AttackStyle style,
              int baseCost, double baseRange, int baseCooldown, double baseDamage,
              double splashRadius, int chainTargets,
              double slowFactor, int slowTicks, double burnDps, int burnTicks,
              SoundEvent fireSound, float firePitch,
              Material projectile, float projectileScale) {
        this.displayName = displayName;
        this.description = description;
        this.shape = shape;
        this.icon = icon;
        this.model = model;
        this.element = element;
        this.style = style;
        this.baseCost = baseCost;
        this.baseRange = baseRange;
        this.baseCooldown = baseCooldown;
        this.baseDamage = baseDamage;
        this.splashRadius = splashRadius;
        this.chainTargets = chainTargets;
        this.slowFactor = slowFactor;
        this.slowTicks = slowTicks;
        this.burnDps = burnDps;
        this.burnTicks = burnTicks;
        this.fireSound = fireSound;
        this.firePitch = firePitch;
        this.projectile = projectile;
        this.projectileScale = projectileScale;
    }

    /** 実際に飛ばすアイテム。範囲効果の塔は弾を撃たないので null。 */
    public Material projectile() {
        return projectile;
    }

    public float projectileScale() {
        return projectileScale;
    }

    /** 発射音。塔ごとに違う音にすると、目を離していても何が撃っているか分かる。 */
    public SoundEvent fireSound() {
        return fireSound;
    }

    public float firePitch() {
        return firePitch;
    }

    /** ラン開始時から使えるタワー。残りは報酬・ショップで解放する。 */
    public static List<TowerKind> starterUnlocks() {
        return List.of(ARROW, FROST, CANNON);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Shape shape() {
        return shape;
    }

    public Material icon() {
        return icon;
    }

    public Block model() {
        return model;
    }

    public Element element() {
        return element;
    }

    public AttackStyle style() {
        return style;
    }

    public int baseCost() {
        return baseCost;
    }

    /** レベル 0 から level へ上げるのに必要なゴールド。 */
    public int upgradeCost(int level) {
        return (int) Math.round(baseCost * (0.7 + 0.55 * (level + 1)));
    }

    public Stats statsAt(int level) {
        double levelMul = 1.0 + 0.55 * level;
        return new Stats(
                baseDamage * levelMul,
                baseRange + 0.6 * level,
                Math.max(2, (int) Math.round(baseCooldown * Math.pow(0.88, level))),
                splashRadius + 0.25 * level,
                chainTargets + (level >= 2 ? 1 : 0),
                slowFactor > 0 ? Math.min(0.75, slowFactor + 0.05 * level) : 0.0,
                slowTicks,
                burnDps * levelMul,
                burnTicks);
    }

    /**
     * レベルごとの実効性能。タワーの計算は必ずここを通す。
     */
    public record Stats(
            double damage,
            double range,
            int cooldown,
            double splashRadius,
            int chainTargets,
            double slowFactor,
            int slowTicks,
            double burnDps,
            int burnTicks) {

        public double dps() {
            return damage * 20.0 / Math.max(1, cooldown);
        }
    }
}
