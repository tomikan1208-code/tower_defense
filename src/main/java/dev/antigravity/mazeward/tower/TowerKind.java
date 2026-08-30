package dev.antigravity.mazeward.tower;

import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Shapes;
import java.util.List;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.VillagerProfession;
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
            Shapes.DOT, Material.BOW, Block.CHISELED_STONE_BRICKS, Model.of(EntityType.SKELETON, Look.of().hand(Material.BOW)),
            Element.NONE, AttackStyle.SINGLE,
            30, 5.5, 12, 7.0,
            0.0, 0, 0.0, 0, 0.0, 0,
            SoundEvent.ENTITY_ARROW_SHOOT, 1.5f, Material.ARROW, 0.7f,
            new Spec("狙撃", "射程が大きく伸び、一撃が重くなる。代わりに遅い",
                    1.5, 6.0, 1.7, 0, 0, 0, 0)
                    .looking(Look.of().helmet(Material.CHAINMAIL_HELMET)
                            .chestplate(Material.CHAINMAIL_CHESTPLATE).scale(1.15)),
            new Spec("連射", "攻撃間隔が半分以下になる。代わりに一撃は軽い",
                    0.85, 0, 0.45, 0, 0, 0, 0)
                    .looking(Look.of().helmet(Material.GOLDEN_HELMET)
                            .chestplate(Material.GOLDEN_CHESTPLATE).scale(0.95))),

    FROST(
            "氷塔", "ダメージは低いが 40% 減速。キルゾーンの滞在時間を伸ばす。",
            Shapes.DOT, Material.PACKED_ICE, Block.BLUE_ICE, Model.of(EntityType.SNOW_GOLEM),
            Element.ICE, AttackStyle.SINGLE,
            45, 5.0, 18, 3.0,
            0.0, 0, 0.40, 45, 0.0, 0,
            SoundEvent.BLOCK_GLASS_BREAK, 1.6f, Material.SNOWBALL, 0.7f,
            new Spec("極低温", "減速がさらに 25% 深くなる",
                    1.0, 0, 1.0, 0, 0, 0.25, 0)
                    .looking(Look.of().helmet(Material.PACKED_ICE).scale(1.15)),
            new Spec("霜害", "減速はそのままに、攻撃力が 3 倍になる",
                    3.0, 0, 1.0, 0, 0, 0, 0)
                    .looking(Look.of().helmet(Material.JACK_O_LANTERN).scale(1.3))),

    BRAZIER(
            "火炉", "射程内の経路を燃焼帯に変える。弾を撃たず、通過する敵を焼き続ける。",
            Shapes.I2, Material.CAMPFIRE, Block.MAGMA_BLOCK, Model.of(EntityType.BLAZE),
            Element.FIRE, AttackStyle.AURA,
            60, 3.6, 20, 0.0,
            0.0, 0, 0.0, 0, 5.0, 60,
            SoundEvent.ITEM_FIRECHARGE_USE, 0.8f, null, 0f,
            new Spec("業火", "燃焼ダメージが 2.2 倍になる",
                    1.0, 0, 1.0, 0, 0, 0, 0, 2.2)
                    .looking(Look.of().scale(1.4)),
            new Spec("灼熱地帯", "燃焼帯の範囲が大きく広がる",
                    1.0, 3.0, 1.0, 0, 0, 0, 0)
                    .looking(Look.of().tiers(2))),

    CANNON(
            "砲塔", "低速・高ダメージの範囲攻撃。敵が密集する折り返しに置く。",
            Shapes.O, Material.TNT, Block.BLAST_FURNACE, Model.of(EntityType.MAGMA_CUBE),
            Element.FIRE, AttackStyle.SPLASH,
            110, 6.5, 40, 26.0,
            2.2, 0, 0.0, 0, 0.0, 0,
            SoundEvent.ENTITY_FIREWORK_ROCKET_BLAST, 0.7f, Material.FIRE_CHARGE, 0.9f,
            new Spec("大口径", "爆発の範囲が大きく広がり、威力も上がる",
                    1.3, 0, 1.0, 2.0, 0, 0, 0)
                    .looking(Look.of().scale(1.45)),
            new Spec("焼夷", "着弾した敵を燃やし続ける",
                    1.0, 0, 1.0, 0, 0, 0, 14.0)
                    .looking(Look.of().tiers(2))),

    TESLA(
            "雷塔", "3 体に連鎖する。蛇行迷路で経路が平行に並ぶほど強い。",
            Shapes.O, Material.LIGHTNING_ROD, Block.COPPER_BLOCK, Model.of(EntityType.BREEZE),
            Element.ARC, AttackStyle.CHAIN,
            130, 5.0, 22, 11.0,
            0.0, 3, 0.0, 0, 0.0, 0,
            SoundEvent.BLOCK_CONDUIT_ATTACK_TARGET, 1.7f, Material.AMETHYST_SHARD, 0.7f,
            new Spec("拡散", "連鎖する数が 3 体増える。蛇行迷路で真価を発揮する",
                    0.8, 0, 1.0, 0, 3, 0, 0)
                    .looking(Look.of().tiers(2)),
            new Spec("過負荷", "連鎖を捨てて、1 体への威力を 2 倍にする",
                    2.0, 0, 1.0, 0, -2, 0, 0)
                    .looking(Look.of().scale(1.45))),

    BALLISTA(
            "弩塔", "射程が極端に長く、直線上の敵を貫く。飛行の直線ルートの見張りに。",
            Shapes.I3, Material.CROSSBOW, Block.DARK_OAK_WOOD, Model.villager(VillagerProfession.FLETCHER),
            Element.NONE, AttackStyle.PIERCE,
            100, 12.0, 50, 30.0,
            0.0, 3, 0.0, 0, 0.0, 0,
            SoundEvent.ITEM_CROSSBOW_SHOOT, 0.9f, Material.SPECTRAL_ARROW, 1.0f,
            new Spec("長距離", "射程がさらに 8 伸びる。盤面のどこへでも届く",
                    1.0, 8.0, 1.0, 0, 0, 0, 0)
                    .looking(Look.of().job(VillagerProfession.TOOLSMITH).scale(1.2)),
            new Spec("貫通強化", "貫く数が 3 体増え、威力も上がる",
                    1.2, 0, 1.0, 0, 3, 0, 0)
                    .looking(Look.of().job(VillagerProfession.WEAPONSMITH).scale(1.05))),

    // ---------------------------------------------------------------- 支援・妨害

    BANISHER(
            "送還塔", "敵を来た道へ押し戻す。削る力は無いが、キルゾーンを何度も通させる。",
            Shapes.DOT, Material.ENDER_PEARL, Block.PURPUR_PILLAR, Model.of(EntityType.ENDERMAN),
            Element.VOID, AttackStyle.BANISH,
            75, 5.5, 55, 5.0,
            0.0, 0, 0.0, 0, 0.0, 0,
            SoundEvent.ENTITY_ENDERMAN_TELEPORT, 1.2f, Material.ENDER_PEARL, 0.6f,
            Effect.banish(4.0),
            new Spec("深淵送り", "押し戻す距離が 2 倍以上になる",
                    1.0, 0, 1.0, 0, 0, 0, 0, 1.0, Effect.banish(5.0))
                    .looking(Look.of().helmet(Material.OBSIDIAN).scale(1.2)),
            new Spec("連続送還", "攻撃間隔が半分になる。押し戻す距離は変わらない",
                    1.0, 0, 0.5, 0, 0, 0, 0)
                    .looking(Look.of().helmet(Material.CHORUS_FLOWER).scale(0.95))),

    HEXER(
            "呪詛塔", "射程内の敵の守りを剥ぐ。自分は削らないが、周りの塔の火力が伸びる。",
            Shapes.I2, Material.DRAGON_BREATH, Block.CRYING_OBSIDIAN, Model.of(EntityType.WITCH),
            Element.HEX, AttackStyle.CURSE,
            95, 6.0, 30, 0.0,
            0.0, 0, 0.0, 0, 0.0, 0,
            SoundEvent.ENTITY_WITCH_AMBIENT, 1.1f, null, 0f,
            Effect.curse(0.35, 60),
            new Spec("深き呪い", "被ダメージ増加が 2 倍近くになる",
                    1.0, 0, 1.0, 0, 0, 0, 0, 1.0, Effect.curse(0.30, 0))
                    .looking(Look.of().helmet(Material.WITHER_SKELETON_SKULL).scale(1.1)),
            new Spec("広域呪詛", "射程が大きく広がる。盤面の大半を呪える",
                    1.0, 5.0, 1.0, 0, 0, 0, 0)
                    .looking(Look.of().helmet(Material.BEACON).scale(1.25))),

    WATCHTOWER(
            "監視塔", "周りのタワーの威力と手数を上げる。自分は撃たない。",
            Shapes.O, Material.BELL, Block.LODESTONE, Model.villager(VillagerProfession.NONE),
            Element.NONE, AttackStyle.SUPPORT,
            120, 5.0, 40, 0.0,
            0.0, 0, 0.0, 0, 0.0, 0,
            SoundEvent.BLOCK_BELL_USE, 1.4f, null, 0f,
            Effect.watch(0.30, 0.15),
            new Spec("号令", "威力の上乗せが 2 倍になる",
                    1.0, 0, 1.0, 0, 0, 0, 0, 1.0, Effect.watch(0.30, 0))
                    .looking(Look.of().job(VillagerProfession.LIBRARIAN).scale(1.1)),
            new Spec("展望", "射程が伸び、手数の上乗せが厚くなる",
                    1.0, 4.0, 1.0, 0, 0, 0, 0, 1.0, Effect.watch(0, 0.15))
                    .looking(Look.of().job(VillagerProfession.CARTOGRAPHER).scale(1.25)));

    /**
     * 台座の上に立たせるエンティティ。
     *
     * <p>ブロックの塊では 9 種類の塔を遠目に見分けられなかった。
     * 人型・スライム・炎など <b>輪郭が違う</b> エンティティにすると、
     * 上空から俯瞰したままでも「どこに何を置いたか」が読める。</p>
     *
     * <p>村人だけは職業で見た目が変わるので、そこまで指定できるようにしている
     * （弩塔＝素の村人／監視塔＝司書）。</p>
     */
    public record Model(EntityType type, Look look) {

        public static Model of(EntityType type) {
            return new Model(type, Look.PLAIN);
        }

        public static Model of(EntityType type, Look look) {
            return new Model(type, look);
        }

        public static Model villager(VillagerProfession profession) {
            return new Model(EntityType.VILLAGER, Look.of().job(profession));
        }
    }

    /** 特化まで含めた実際の見た目。未選択なら基本の見た目そのまま。 */
    public Look lookFor(Spec spec) {
        return spec == null ? model.look() : model.look().with(spec.look());
    }

    public static final int MAX_LEVEL = 3;

    private final String displayName;
    private final String description;
    private final Shape shape;
    private final Material icon;
    private final Block pedestal;
    private final Model model;
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
    private final Effect effect;
    private final Spec specA;
    private final Spec specB;

    TowerKind(String displayName, String description, Shape shape, Material icon,
              Block pedestal, Model model,
              Element element, AttackStyle style,
              int baseCost, double baseRange, int baseCooldown, double baseDamage,
              double splashRadius, int chainTargets,
              double slowFactor, int slowTicks, double burnDps, int burnTicks,
              SoundEvent fireSound, float firePitch,
              Material projectile, float projectileScale,
              Spec specA, Spec specB) {
        this(displayName, description, shape, icon, pedestal, model, element, style,
                baseCost, baseRange, baseCooldown, baseDamage, splashRadius, chainTargets,
                slowFactor, slowTicks, burnDps, burnTicks, fireSound, firePitch,
                projectile, projectileScale, Effect.NONE, specA, specB);
    }

    TowerKind(String displayName, String description, Shape shape, Material icon,
              Block pedestal, Model model,
              Element element, AttackStyle style,
              int baseCost, double baseRange, int baseCooldown, double baseDamage,
              double splashRadius, int chainTargets,
              double slowFactor, int slowTicks, double burnDps, int burnTicks,
              SoundEvent fireSound, float firePitch,
              Material projectile, float projectileScale,
              Effect effect, Spec specA, Spec specB) {
        this.displayName = displayName;
        this.description = description;
        this.shape = shape;
        this.icon = icon;
        this.pedestal = pedestal;
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
        this.effect = effect;
        this.specA = specA;
        this.specB = specB;
    }

    /** ダメージ以外の効果（送還・呪詛・支援）。持たない塔は {@link Effect#NONE}。 */
    public Effect effect() {
        return effect;
    }

    /** 敵を狙わない塔か。狙う相手がいなくても働くので、射撃処理を通さない。 */
    public boolean passive() {
        return style == AttackStyle.SUPPORT;
    }

    /** 最終段階で選べる 2 つの特化。 */
    public List<Spec> specs() {
        return List.of(specA, specB);
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

    /** 台座に敷くブロック。タワーの占有マス（n×m）はこれで表現する。 */
    public Block pedestal() {
        return pedestal;
    }

    /** 台座の上に立つエンティティ。 */
    public Model model() {
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

    /**
     * 最終段階で選ぶ特化。同じタワーが 2 つの別物に分かれる。
     *
     * <p>「伸ばすか、尖らせるか」を最後に一度だけ選ばせることで、
     * 同じ構成でも盤面ごとに違う答えが出るようにするための仕組み。</p>
     */
    public record Spec(String displayName, String description,
                       double damageMul, double rangeAdd, double cooldownMul,
                       double splashAdd, int chainAdd, double slowAdd,
                       double burnAdd, double burnMul, Effect effectAdd, Look look) {

        public Spec(String displayName, String description,
                    double damageMul, double rangeAdd, double cooldownMul,
                    double splashAdd, int chainAdd, double slowAdd, double burnAdd) {
            this(displayName, description, damageMul, rangeAdd, cooldownMul,
                    splashAdd, chainAdd, slowAdd, burnAdd, 1.0, Effect.NONE, Look.PLAIN);
        }

        public Spec(String displayName, String description,
                    double damageMul, double rangeAdd, double cooldownMul,
                    double splashAdd, int chainAdd, double slowAdd,
                    double burnAdd, double burnMul) {
            this(displayName, description, damageMul, rangeAdd, cooldownMul,
                    splashAdd, chainAdd, slowAdd, burnAdd, burnMul, Effect.NONE, Look.PLAIN);
        }

        public Spec(String displayName, String description,
                    double damageMul, double rangeAdd, double cooldownMul,
                    double splashAdd, int chainAdd, double slowAdd,
                    double burnAdd, double burnMul, Effect effectAdd) {
            this(displayName, description, damageMul, rangeAdd, cooldownMul,
                    splashAdd, chainAdd, slowAdd, burnAdd, burnMul, effectAdd, Look.PLAIN);
        }

        /**
         * この特化を選んだときの見た目。
         *
         * <p>性能の引数の並びに紛れ込ませると、どれが見た目なのか読めなくなるので、
         * 定義側では性能を書き切ったあとに繋げる形にしている。</p>
         */
        public Spec looking(Look look) {
            return new Spec(displayName, description, damageMul, rangeAdd, cooldownMul,
                    splashAdd, chainAdd, slowAdd, burnAdd, burnMul, effectAdd, look);
        }
    }

    public Stats statsAt(int level) {
        return statsAt(level, null);
    }

    /**
     * レベルと特化から実効性能を出す。
     *
     * @param spec 最終段階で選んだ特化。未選択なら null
     */
    public Stats statsAt(int level, Spec spec) {
        double levelMul = 1.0 + 0.55 * level;
        double damage = baseDamage * levelMul;
        double range = baseRange + 0.6 * level;
        int cooldown = Math.max(2, (int) Math.round(baseCooldown * Math.pow(0.88, level)));
        double splash = splashRadius + 0.25 * level;
        int chain = chainTargets + (level >= 2 ? 1 : 0);
        double slow = slowFactor > 0 ? Math.min(0.75, slowFactor + 0.05 * level) : 0.0;
        double burn = burnDps * levelMul;
        int burnFor = burnTicks;
        // 支援・妨害の効果もレベルで伸びる。伸びなければ強化する意味がない
        Effect resolved = effect.empty() ? effect : new Effect(
                effect.knockback() * levelMul,
                effect.vulnerability() * levelMul,
                effect.vulnerabilityTicks(),
                effect.boostDamage() * levelMul,
                effect.boostRate() * levelMul);

        if (spec != null) {
            damage *= spec.damageMul();
            range += spec.rangeAdd();
            cooldown = Math.max(2, (int) Math.round(cooldown * spec.cooldownMul()));
            splash += spec.splashAdd();
            chain = Math.max(1, chain + spec.chainAdd());
            slow = slow > 0 ? Math.min(0.85, slow + spec.slowAdd()) : slow;
            burn = burn * spec.burnMul() + spec.burnAdd();
            if (burn > 0 && burnFor <= 0) {
                burnFor = 60;
            }
            resolved = resolved.plus(spec.effectAdd());
        }

        return new Stats(damage, range, cooldown, splash, chain, slow, slowTicks,
                burn, burnFor, resolved);
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
            int burnTicks,
            Effect effect) {

        public Stats(double damage, double range, int cooldown, double splashRadius,
                     int chainTargets, double slowFactor, int slowTicks,
                     double burnDps, int burnTicks) {
            this(damage, range, cooldown, splashRadius, chainTargets, slowFactor,
                    slowTicks, burnDps, burnTicks, Effect.NONE);
        }

        public double dps() {
            return damage * 20.0 / Math.max(1, cooldown);
        }
    }
}
