package dev.antigravity.mazeward.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import net.minestom.server.item.Material;

/**
 * 恒久パッシブ。<b>増やすときはここに enum 定数を 1 つ足すだけ。</b>
 * 効果の適用は {@link RunState} の modifier 系メソッドに集約してある。
 */
public enum Relic {

    LONG_LENS("長射程レンズ", "全タワーの射程 +0.8", Material.SPYGLASS),
    TAX_COLLECTOR("収税官", "ゴールドとエンバーの獲得 +25%", Material.GOLD_INGOT),
    MASON_HAND("石工の手", "手札の上限 +1", Material.BRICK),
    WAR_CHEST("戦費箱", "ステージ開始時に +60G", Material.CHEST),
    FROST_SIGIL("氷結の刻印", "減速効果 +15%", Material.BLUE_ICE),
    ARC_COIL("導線コイル", "連鎖・貫通の対象 +1", Material.COPPER_INGOT),
    POWDER_HEART("火薬の心臓", "範囲攻撃の半径 +0.8", Material.GUNPOWDER),
    BULWARK("城塞の礎", "取得時にコア最大HP +5（同時に回復）", Material.SHIELD),
    SWIFT_FORGE("速成炉", "タワー強化コスト -20%", Material.ANVIL),
    KINDLING("焚きつけ", "燃焼ダメージ +40%", Material.BLAZE_POWDER);

    private final String displayName;
    private final String description;
    private final Material icon;

    Relic(String displayName, String description, Material icon) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
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

    /** まだ持っていないレリックから 1 つ抽選する。すべて持っていたら null。 */
    public static Relic randomMissing(Collection<Relic> owned, Random random) {
        List<Relic> pool = new ArrayList<>();
        for (Relic relic : values()) {
            if (!owned.contains(relic)) {
                pool.add(relic);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
