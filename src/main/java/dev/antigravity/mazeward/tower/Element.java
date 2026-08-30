package dev.antigravity.mazeward.tower;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** タワーの属性。将来のシナジー（レリック連動）用に分離してある。 */
public enum Element {
    NONE("無", NamedTextColor.WHITE),
    FIRE("炎", NamedTextColor.RED),
    ICE("氷", NamedTextColor.AQUA),
    ARC("電", NamedTextColor.YELLOW);

    private final String displayName;
    private final TextColor color;

    Element(String displayName, TextColor color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    public TextColor color() {
        return color;
    }
}
