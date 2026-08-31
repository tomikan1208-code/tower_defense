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
 * <h2>インカムが技術ツリーになっている</h2>
 * 強いモンスターほど {@link #unlockIncome()} が高い。インカムを伸ばすこと自体が
 * 「新しい脅威を解禁する」ことになるので、
 * 「守りを固めるか、収入を伸ばすか」の選択がそのまま戦略の分岐になる。
 *
 * <h2>ボスと終焉騎はインカムを生まない</h2>
 * 終盤に「もう伸ばさずに削り切る」へ切り替える判断を作るため。
 *
 * <h2>解禁順に意味がある</h2>
 * 安い順に並べているのではなく、<b>相手の防衛が固まっていく順に、
 * その固め方を咎めるものが出てくる</b>ように並べてある。
 * 妨害者はキルゾーンの集中を、瞬移体は 1 点集中を、分裂体は単体火力を、
 * 熱塊は炎氷偏重を、庇護者は火力の総量を、それぞれ咎める。
 */
public enum AttackerKind {

    WHELP("走狗", EnemyKind.GRUNT, 15, 1, 1, 0, 40,
            Material.ROTTEN_FLESH, "基本。安く速く送れる"),

    DASHER("疾走者", EnemyKind.RUNNER, 25, 1, 1, 15, 30,
            Material.FEATHER, "速い。キルゾーンを走り抜ける"),

    SAPPER("妨害者", EnemyKind.SAPPER, 40, 2, 2, 30, 70,
            Material.GUNPOWDER, "通り道のタワーを 3 秒黙らせる。火力を固めた相手ほど痛い"),

    STONEBACK("石背", EnemyKind.BRUTE, 45, 2, 2, 40, 170,
            Material.IRON_INGOT, "固定装甲。手数だけの防衛を咎める"),

    SKIMMER("浮遊蟲", EnemyKind.FLYER, 60, 2, 2, 70, 95,
            Material.PHANTOM_MEMBRANE, "迷路を無視して直線で飛ぶ。対空のない相手に刺さる"),

    PHASER("瞬移体", EnemyKind.BLINKER, 70, 2, 2, 90, 110,
            Material.ENDER_EYE, "撃たれるたび前へ飛ぶ。1 箇所に固めたキルゾーンを跳び越える"),

    CHANTER("祈祷師", EnemyKind.HEALER, 80, 3, 2, 110, 130,
            Material.GOLDEN_APPLE, "周囲を回復し続ける。単体火力だけでは倒しきれない"),

    CLEAVER("分裂体", EnemyKind.SPLITTER, 95, 3, 3, 140, 150,
            Material.SLIME_BALL, "倒すとその場で 2 体に分かれる。単体火力だけの防衛を咎める"),

    CINDER("熱塊", EnemyKind.EMBERLING, 120, 3, 3, 180, 175,
            Material.BLAZE_POWDER, "燃えず、減速も効かない。炎と氷に寄せた防衛を咎める"),

    BULWARK("庇護者", EnemyKind.AEGIS, 150, 4, 3, 230, 260,
            Material.SHIELD, "周りの味方の被ダメージを 35% 減らす。数を並べただけでは溶けない"),

    REAPER("終焉騎", EnemyKind.REAPER, 1000, 0, 4, 300, 420,
            Material.NETHERITE_SCRAP,
            "一度目に倒れても出発点へ戻り、相手のライフ上限を 1 奪う。インカムは増えない"),

    CALAMITY("災厄", EnemyKind.BOSS, 400, 0, 5, 350, 6600,
            Material.WITHER_SKELETON_SKULL,
            "コアに触れても消えず、出発点へ戻って何周でも来る。インカムは増えない最終手段");

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

    /** 送ると恒久的に増えるインカム。ボスは 0。 */
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

    /** 撃破報酬（コイン）。送りコストの 20%。 */
    public int killReward() {
        return Math.max(1, (int) Math.round(cost * 0.20));
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
