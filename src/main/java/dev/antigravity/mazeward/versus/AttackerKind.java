package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.enemy.EnemyKind;
import java.util.ArrayList;
import java.util.List;
import net.minestom.server.item.Material;

/**
 * 相手へ送りつけるモンスター。
 *
 * <p>体そのもの（見た目・速度・装甲・飛行・回復）は既存の {@link EnemyKind} を流用し、
 * ここでは <b>「いくらで、どれだけインカムが増えるか」</b> だけを定義する。
 * 防衛側から見れば送られてきた敵はシングルの敵と何も変わらないので、
 * 戦闘処理を一切足さずに済む。</p>
 *
 * <h2>1 回の送りにつき、相手 1 人あたり 1 体</h2>
 * 体数という軸を持たせない。押し引きの強さは
 * <b>コスト・ストック・モンスターの質</b> だけで決まるので、
 * 「何を送るか」の判断が体数に埋もれない。人数が増えれば受ける数もそのまま増える。
 *
 * <h2>値段は等比、インカム比率は逓減</h2>
 * <p>コストは 1 段ごとにおよそ 1.45 倍（Hypixel と同じ刻み）、
 * インカム比率は 6.7% から 2.2% へ落ちていく。
 * <b>安いモンスターほど投資効率がよく、高いモンスターほど「圧をかけるための道具」になる</b>。
 * Hypixel TowerWars と同じ形で、これが経済の暴走を自然に減速させる唯一の仕組みになっている
 * （比率が一定だと、金が増えるほどインカムの伸びも比例して増え続けてしまう）。</p>
 *
 * <h2>ストック消費はどれも 1</h2>
 * <p>ストックは <b>「1 秒に 1 回しか送れない」という回数制限</b> であって、
 * 強さの値付けではない。上位ほど重くすると
 * 「1 秒あたりに増やせるインカム」に固定の天井ができ、
 * 経済が途中から指数をやめて直線になってしまう。
 * 金を力に変える量は <b>値段の梯子だけ</b> で表す。</p>
 *
 * <h2>ボスと終焉騎はインカムを生まない</h2>
 * 終盤に「もう伸ばさずに削り切る」へ切り替える判断を作るため。
 * 値段は最上位のインカムモブに対する <b>倍率</b> で置いてある（災厄 2 倍 / 終焉騎 4 倍）。
 * 定額で置くと、指数で伸びる経済の中では必ず「タダ同然」になる。
 *
 * <h2>解禁順に意味がある</h2>
 * 安い順に並べているのではなく、<b>相手の防衛が固まっていく順に、
 * その固め方を咎めるものが出てくる</b>ように並べてある。
 * 妨害者はキルゾーンの集中を、瞬移体は 1 点集中を、分裂体は単体火力を、
 * 熱塊は炎氷偏重を、庇護者は火力の総量を、それぞれ咎める。
 * 後半 6 段は同じ咎め方の上位種で、育った防衛にも通る太さで撃ち直せるようにしてある。
 *
 * <p>この表をこの形に組み直した経緯と、組み直す前に何が壊れていたかは
 * {@code docs/VERSUS_ECONOMY_ja.md} にまとめてある。</p>
 */
public enum AttackerKind {

    WHELP("走狗", EnemyKind.GRUNT, 15, 1, 1, 0, 50,
            Material.ROTTEN_FLESH, "基本。安く速く送れる"),

    DASHER("疾走者", EnemyKind.RUNNER, 22, 1, 1, 10, 80,
            Material.FEATHER, "速い。キルゾーンを走り抜ける"),

    SAPPER("妨害者", EnemyKind.SAPPER, 32, 2, 1, 20, 120,
            Material.GUNPOWDER, "通り道のタワーを 2 秒黙らせる。火力を固めた相手ほど痛い"),

    STONEBACK("石背", EnemyKind.BRUTE, 46, 2, 1, 20, 170,
            Material.IRON_INGOT, "固定装甲。手数だけの防衛を咎める"),

    SKIMMER("浮遊蟲", EnemyKind.FLYER, 66, 3, 1, 30, 240,
            Material.PHANTOM_MEMBRANE, "迷路を無視して直線で飛ぶ。対空のない相手に刺さる"),

    PHASER("瞬移体", EnemyKind.BLINKER, 96, 4, 1, 50, 350,
            Material.ENDER_EYE, "撃たれるたび前へ飛ぶ。1 箇所に固めたキルゾーンを跳び越える"),

    CHANTER("祈祷師", EnemyKind.HEALER, 140, 6, 1, 70, 500,
            Material.GOLDEN_APPLE, "周囲を回復し続ける。単体火力だけでは倒しきれない"),

    CLEAVER("分裂体", EnemyKind.SPLITTER, 200, 8, 1, 100, 720,
            Material.SLIME_BALL, "倒すとその場で 2 体に分かれる。単体火力だけの防衛を咎める"),

    CINDER("熱塊", EnemyKind.EMBERLING, 290, 11, 1, 140, 1040,
            Material.BLAZE_POWDER, "燃えず、減速も効かない。炎と氷に寄せた防衛を咎める"),

    BULWARK("庇護者", EnemyKind.AEGIS, 430, 15, 1, 220, 1550,
            Material.SHIELD, "周りの味方の被ダメージを 35% 減らす。数を並べただけでは溶けない"),

    // ---------------------------------------------------------------- 上位種

    SWIFTBEAST("疾風獣", EnemyKind.SWIFTBEAST, 620, 20, 1, 310, 2230,
            Material.SUGAR, "疾走者の上位。減速がほとんど乗らないまま走り抜ける"),

    BREAKER("破城者", EnemyKind.BREAKER, 890, 26, 1, 440, 3200,
            Material.TNT_MINECART, "妨害者の上位。黙らせる範囲も時間も倍近い"),

    IRONWALL("鉄壁", EnemyKind.IRONWALL, 1300, 36, 1, 650, 4680,
            Material.NETHERITE_INGOT, "石背の上位。装甲が厚く、減速も効かない"),

    CANOPY("天蓋", EnemyKind.CANOPY, 1900, 48, 1, 950, 6840,
            Material.ELYTRA, "浮遊蟲の上位。対空が薄いままなら防ぎようがない"),

    HIERARCH("大祭司", EnemyKind.HIGHPRIEST, 2700, 64, 1, 1350, 9720,
            Material.ENCHANTED_GOLDEN_APPLE, "祈祷師の上位。回復量が桁で違う"),

    RENDER("大分裂体", EnemyKind.GREATSPLITTER, 4000, 88, 1, 2000, 14400,
            Material.MAGMA_CREAM, "分裂体の上位。倒すと 3 体に割れる"),

    // ---------------------------------------------------------------- 削り切る用

    CALAMITY("災厄", EnemyKind.BOSS, 8000, 0, 1, 800, 64000,
            Material.WITHER_SKELETON_SKULL,
            "コアに触れても消えず、出発点へ戻って何周でも来る。インカムは増えない"),

    REAPER("終焉騎", EnemyKind.REAPER, 16000, 0, 1, 1600, 48000,
            Material.NETHERITE_SCRAP,
            "一度倒しても出発点へ戻る。コアまで通すと相手のライフ上限を 1 奪う。インカムは増えない");

    /**
     * 1 回の送りが盤面に返すコインの総量（送りコストに対する割合）。
     *
     * <p><b>ここが人数に依らず一定であることが、マルチプレイ最大の生命線。</b>
     * 送りは生存者全員に湧くので、素朴に「1 体につき 20%」にすると
     * 返ってくる総量が {@code 20% x (人数-1)} になり、
     * 6 人以上で <b>払った額より返ってくる額のほうが多くなる</b>。
     * 送りが黒字の行為になり、コインが雪だるま式に膨らむ。
     * 撃破報酬は人数で割り、総量をここに固定する。</p>
     */
    public static final double KILL_REWARD_TOTAL = 0.40;

    /** 撃破報酬コインの何割がインカムになるか。 */
    public static final double KILL_INCOME_RATIO = 0.10;

    private final String displayName;
    private final EnemyKind body;
    private final int cost;
    private final int incomeGain;
    private final int stockCost;
    private final int unlockIncome;
    private final double hp;
    private final Material icon;
    private final String description;

    AttackerKind(String displayName, EnemyKind body, int cost, int incomeGain,
                 int stockCost, int unlockIncome, double hp, Material icon, String description) {
        this.displayName = displayName;
        this.body = body;
        this.cost = cost;
        this.incomeGain = incomeGain;
        this.stockCost = stockCost;
        this.unlockIncome = unlockIncome;
        this.hp = hp;
        this.icon = icon;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public EnemyKind body() {
        return body;
    }

    public int cost() {
        return cost;
    }

    /** 送ると恒久的に増えるインカム。ボスと終焉騎は 0。 */
    public int incomeGain() {
        return incomeGain;
    }

    public int stockCost() {
        return stockCost;
    }

    /** これを送れるようになるのに必要なインカム。 */
    public int unlockIncome() {
        return unlockIncome;
    }

    public double hp() {
        return hp;
    }

    public Material icon() {
        return icon;
    }

    public String description() {
        return description;
    }

    /** ライフ上限を奪うのはこれだけ。<b>コアまで通されたときにだけ</b> 効く。 */
    public boolean stealsMaxLife() {
        return this == REAPER;
    }

    /**
     * 撃破報酬（コイン）。
     *
     * <p>送りは生存者全員に 1 体ずつ湧くので、1 体あたりの報酬を人数で割って
     * <b>盤面に返る総量を {@link #KILL_REWARD_TOTAL} に固定する</b>。
     * 2 人なら 1 人が 40% を、8 人なら 7 人が 5.7% ずつ受け取る。</p>
     *
     * @param opponents 送り主以外の生存者数（＝この送りが湧く島の数）
     */
    public int killReward(int opponents) {
        int targets = Math.max(1, opponents);
        return Math.max(1, (int) Math.round(cost * KILL_REWARD_TOTAL / targets));
    }

    /** そのインカムで送れるものを、安い順に返す。 */
    public static List<AttackerKind> unlockedAt(int income) {
        List<AttackerKind> out = new ArrayList<>();
        for (AttackerKind kind : values()) {
            if (income >= kind.unlockIncome) {
                out.add(kind);
            }
        }
        return out;
    }
}
