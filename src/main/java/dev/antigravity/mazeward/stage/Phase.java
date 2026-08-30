package dev.antigravity.mazeward.stage;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * ステージのフェーズ。
 *
 * <p>建築と戦闘を完全に分離しているのは意図的で、
 * 「壁を置いて壊してを繰り返して敵の経路を往復させる」退化戦術（ジャグリング）を
 * ルールごと不可能にするため。副次効果として、戦闘中は経路が固定されるので
 * プレビュー表示が常に正しいことが保証される。</p>
 */
public enum Phase {

    /** 建築フェーズ。カード配置・タワー購入・経路プレビューが可能。敵はいない。 */
    BUILD("建築", NamedTextColor.AQUA),

    /** 戦闘フェーズ。カード配置は不可。タワーの購入・強化は可能。 */
    COMBAT("戦闘", NamedTextColor.RED),

    VICTORY("制圧", NamedTextColor.GREEN),

    DEFEAT("陥落", NamedTextColor.DARK_RED);

    private final String displayName;
    private final TextColor color;

    Phase(String displayName, TextColor color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    public TextColor color() {
        return color;
    }

    public boolean active() {
        return this == BUILD || this == COMBAT;
    }
}
