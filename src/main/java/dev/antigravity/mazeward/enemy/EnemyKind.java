package dev.antigravity.mazeward.enemy;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.entity.EntityType;

/**
 * 敵定義。<b>敵を増やすときはここに enum 定数を 1 つ足すだけでよい。</b>
 *
 * <p>種類の選び方には意図がある。「経路を伸ばす」一本槍を成立させないために、
 * FLYER（迷路無視）・BRUTE（固定装甲）・HEALER（範囲火力要求）の 3 種で
 * それぞれ別の対策を強制する。</p>
 *
 * <p>能力持ちの種は {@link Trait} で能力を持つ。こちらは
 * 「長い迷路 + 高 DPS の塔を並べる」という <b>防衛側の最適解そのもの</b> を
 * 崩しにいく役割で、対戦の送り合いで効いてくる。</p>
 *
 * <p>末尾の 6 種は <b>上位種</b>。能力は下位と同じで、体だけが太い。
 * 対戦の経済が指数で伸びる以上、送れるものも同じ勢いで太らないと
 * 「もう送るものがない」で成長が止まる。詳しくは
 * {@code docs/VERSUS_ECONOMY_ja.md} を参照。</p>
 */
public enum EnemyKind {

    GRUNT("徘徊者", EntityType.ZOMBIE, MoveMode.GROUND,
            34, 0.055, 0, 6, 1, 0.0, 0.0, NamedTextColor.GRAY),

    RUNNER("疾走者", EntityType.SPIDER, MoveMode.GROUND,
            20, 0.115, 0, 5, 1, 0.15, 0.0, NamedTextColor.GREEN),

    BRUTE("重装兵", EntityType.IRON_GOLEM, MoveMode.GROUND,
            130, 0.035, 5, 16, 3, 0.35, 0.0, NamedTextColor.DARK_AQUA),

    FLYER("浮遊体", EntityType.PHANTOM, MoveMode.FLYING,
            62, 0.075, 0, 12, 2, 0.0, 0.0, NamedTextColor.LIGHT_PURPLE),

    HEALER("祈祷師", EntityType.EVOKER, MoveMode.GROUND,
            70, 0.045, 2, 18, 2, 0.0, 6.0, NamedTextColor.GOLD),

    /**
     * コアに触れても消えず、出発点へ戻って何周でも来る。
     *
     * <p>「漏らしてでもやり過ごす」が通らない唯一の敵。逃げ切れない代わりに、
     * 一撃で負けはしない厚さにしてある。周回のたびにコアが削れるので、
     * <b>削り切れる火力があるかどうか</b> だけを問う。</p>
     */
    BOSS("災厄", EntityType.RAVAGER, MoveMode.GROUND,
            4200, 0.032, 8, 200, 10, 0.85, 0.0, NamedTextColor.DARK_RED),

    // ---------------------------------------------------------------- 能力持ち

    /** 通り道のタワーを黙らせる。火力を 1 箇所に固めるほど、まとめて止まる。 */
    SAPPER("妨害者", EntityType.CREEPER, MoveMode.GROUND,
            56, 0.062, 1, 11, 1, 0.0, 0.0, NamedTextColor.DARK_GREEN,
            Trait.sapper(3.5, 40)),

    /**
     * 被弾すると半径 5.0 の中で最もコア寄りの経路へ跳ぶ。壁は無視して跨ぐ。
     * 1 点集中のキルゾーンも、折り返しを詰め込んだ迷路も、まとめて飛び越える。
     */
    BLINKER("瞬移体", EntityType.ENDERMAN, MoveMode.GROUND,
            74, 0.058, 0, 13, 2, 0.4, 0.0, NamedTextColor.LIGHT_PURPLE,
            Trait.blink(5.0, 45)),

    /** 周囲の味方の被ダメージを 35% 減らす。数字を並べるだけでは溶けなくなる。 */
    AEGIS("庇護者", EntityType.PIGLIN_BRUTE, MoveMode.GROUND,
            150, 0.040, 4, 22, 2, 0.5, 0.0, NamedTextColor.BLUE,
            Trait.ward(5.5, 0.35)),

    /** 倒すと 2 体に分かれる。単体火力だけの構成を咎める。 */
    SPLITTER("分裂体", EntityType.SLIME, MoveMode.GROUND,
            96, 0.050, 2, 10, 2, 0.0, 0.0, NamedTextColor.GREEN,
            Trait.split(2)),

    /**
     * 分裂体が割れて出てくる小さいスライム。
     *
     * <p>親と同じスライムのまま一回り小さくすることで、
     * 「割れた」ことが見た目だけで伝わる。HP と報酬は親から渡されるので、
     * ここの素の値は単体で出したときの目安でしかない。</p>
     */
    SPLITLING("分裂片", EntityType.SLIME, MoveMode.GROUND,
            28, 0.068, 0, 4, 1, 0.0, 0.0, NamedTextColor.GREEN),

    /** 燃えず、減速も効かない。炎と氷に寄せた構成を咎める。 */
    EMBERLING("熱塊", EntityType.BLAZE, MoveMode.GROUND,
            110, 0.070, 3, 18, 2, 1.0, 0.0, NamedTextColor.GOLD,
            Trait.fireproof()),

    /**
     * 倒れても一度だけ出発点へ戻り、そのとき守り手のライフ上限を 1 奪う。
     * 「倒しさえすれば損はない」という前提を崩すための存在。
     */
    REAPER("終焉騎", EntityType.WITHER_SKELETON, MoveMode.GROUND,
            260, 0.048, 6, 46, 3, 0.6, 0.0, NamedTextColor.DARK_PURPLE,
            Trait.reaper(1)),

    // ---------------------------------------------------------------- 上位種
    //
    // 対戦の送りの梯子が 16 段に伸びたので、後半 6 段ぶんの体をここに足す。
    // 能力（Trait）は下位と同じものを使い回している。**新しい咎め方を増やすのではなく、
    // 同じ咎め方を、育った防衛にも通る太さで撃ち直せるようにする**のが上位種の役目。
    // 新しい能力を段ごとに足すと、覚えることだけが増えて選択が濁る。
    //
    // 見た目だけは必ず別の EntityType にする。同じモデルで数字だけ違うと、
    // 「いま来ているのがどっちか」が盤面から読めず、受け手が判断できない。

    /** 疾走者の上位。速さはそのままに、減速をほとんど受け付けない。 */
    SWIFTBEAST("疾風獣", EntityType.HOGLIN, MoveMode.GROUND,
            900, 0.125, 3, 60, 2, 0.55, 0.0, NamedTextColor.DARK_GREEN),

    /** 妨害者の上位。黙らせる半径も時間も伸びる。 */
    BREAKER("破城者", EntityType.VINDICATOR, MoveMode.GROUND,
            1400, 0.062, 4, 80, 2, 0.20, 0.0, NamedTextColor.DARK_GREEN,
            Trait.sapper(5.0, 70)),

    /** 石背の上位。装甲が厚く、減速もほとんど効かない。 */
    IRONWALL("鉄壁", EntityType.WARDEN, MoveMode.GROUND,
            4000, 0.030, 14, 120, 3, 0.65, 0.0, NamedTextColor.DARK_AQUA),

    /** 浮遊蟲の上位。大きく、速く、迷路を無視して飛ぶ。 */
    CANOPY("天蓋", EntityType.GHAST, MoveMode.FLYING,
            5000, 0.062, 6, 150, 3, 0.0, 0.0, NamedTextColor.LIGHT_PURPLE),

    /** 祈祷師の上位。回復量が桁で違う。 */
    HIGHPRIEST("大祭司", EntityType.ILLUSIONER, MoveMode.GROUND,
            6000, 0.042, 6, 180, 3, 0.10, 700.0, NamedTextColor.GOLD),

    /** 分裂体の上位。倒すと 3 体に割れる。 */
    GREATSPLITTER("大分裂体", EntityType.MAGMA_CUBE, MoveMode.GROUND,
            8000, 0.048, 5, 220, 3, 0.0, 0.0, NamedTextColor.GREEN,
            Trait.split(3));

    private final String displayName;
    private final EntityType entityType;
    private final MoveMode moveMode;
    private final double baseHp;
    private final double baseSpeed;
    private final double armor;
    private final int goldReward;
    private final int leakDamage;
    private final double slowResist;
    private final double healPerSecond;
    private final TextColor color;
    private final Trait trait;

    EnemyKind(String displayName, EntityType entityType, MoveMode moveMode,
              double baseHp, double baseSpeed, double armor, int goldReward, int leakDamage,
              double slowResist, double healPerSecond, TextColor color) {
        this(displayName, entityType, moveMode, baseHp, baseSpeed, armor, goldReward,
                leakDamage, slowResist, healPerSecond, color, Trait.NONE);
    }

    EnemyKind(String displayName, EntityType entityType, MoveMode moveMode,
              double baseHp, double baseSpeed, double armor, int goldReward, int leakDamage,
              double slowResist, double healPerSecond, TextColor color, Trait trait) {
        this.displayName = displayName;
        this.entityType = entityType;
        this.moveMode = moveMode;
        this.baseHp = baseHp;
        this.baseSpeed = baseSpeed;
        this.armor = armor;
        this.goldReward = goldReward;
        this.leakDamage = leakDamage;
        this.slowResist = slowResist;
        this.healPerSecond = healPerSecond;
        this.color = color;
        this.trait = trait;
    }

    /** 特殊能力。持たない敵は {@link Trait#NONE}。 */
    public Trait trait() {
        return trait;
    }

    public String displayName() {
        return displayName;
    }

    public EntityType entityType() {
        return entityType;
    }

    public MoveMode moveMode() {
        return moveMode;
    }

    public boolean flying() {
        return moveMode == MoveMode.FLYING;
    }

    /** ブロック / tick。 */
    public double baseSpeed() {
        return baseSpeed;
    }

    /** 固定軽減。低ダメージ高速タワーだけの構成を通さないためのパラメータ。 */
    public double armor() {
        return armor;
    }

    public int leakDamage() {
        return leakDamage;
    }

    /** 減速耐性 0.0〜1.0。1.0 で完全耐性。 */
    public double slowResist() {
        return slowResist;
    }

    public double healPerSecond() {
        return healPerSecond;
    }

    public boolean healer() {
        return healPerSecond > 0.0;
    }

    public boolean boss() {
        return this == BOSS;
    }

    /**
     * スライムの見た目の大きさ。0 ならスライムではない。
     *
     * <p>分裂体は中くらい、分裂片は小さいスライムにして、
     * 割れた前後が同じ生き物だと一目で分かるようにしている。</p>
     */
    public int slimeSize() {
        return switch (this) {
            case SPLITTER -> 2;
            case SPLITLING -> 1;
            default -> 0;
        };
    }

    public TextColor color() {
        return color;
    }

    /** 表示用の速度（ブロック / 秒）。内部は tick 単位なので、そのままでは読めない。 */
    public double speedPerSecond() {
        return baseSpeed * 20.0;
    }

    /** 層 1・ウェーブ 1 での耐力。表示用の基準値。 */
    public double baseHp() {
        return baseHp;
    }

    /** 名札に出す短い能力表示。持たない敵は空文字。 */
    public String abilityTag() {
        if (healer()) {
            return String.format("回復%.0f/秒", healPerSecond);
        }
        if (boss()) {
            return "周回";
        }
        return trait.tag();
    }

    /**
     * 能力の説明文。持たない敵は空文字。
     *
     * <p>{@link Trait} に無い「回復」「災厄の周回」もここでまとめて言葉にする。
     * プレイヤーから見れば、どれも同じ「この敵は何をするのか」でしかない。</p>
     */
    public String abilitySummary() {
        if (healer()) {
            return String.format("周囲の味方を毎秒 %.0f 回復する", healPerSecond);
        }
        if (boss()) {
            return "コアに触れても消えず、出発点へ戻って何周でもやり直す";
        }
        return trait.summary();
    }

    /** 層とウェーブに応じた最大 HP。 */
    public double hpAt(int layer, int wave, double difficulty) {
        double layerScale = 1.0 + 0.32 * (layer - 1);
        double waveScale = 1.0 + 0.16 * (wave - 1);
        return baseHp * layerScale * waveScale * difficulty;
    }

    public int goldAt(int layer) {
        return (int) Math.round(goldReward * (1.0 + 0.07 * (layer - 1)));
    }
}
