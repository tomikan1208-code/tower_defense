package dev.antigravity.mazeward.ai;

import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.tower.TowerKind;
import dev.antigravity.mazeward.versus.AttackerKind;

/**
 * AI が選んだ 1 手。
 *
 * <p>方策の行動空間（{@code type} / {@code card} / {@code tower} / {@code cell} /
 * {@code unit} / {@code spec} / {@code send}）を、そのまま Java 側の操作へ
 * 落としたもの。<b>合法性は判定していない</b>。判定は島（{@code Island}）の
 * 責任で、ここは「何をしようとしたか」だけを運ぶ。</p>
 *
 * <p>受け取る形式は 1 手 1 行のテキスト。JSON にしていないのは、
 * 返る内容がこの 6 種類しかなく、パーサを持つ理由がないため。</p>
 */
public sealed interface AiAction {

    /** 何もしない。方策が持つ正式な選択肢のひとつ（貯める判断）。 */
    record Skip() implements AiAction {
    }

    /** 障害物カードを置く。{@code slot} は手札の位置。 */
    record Card(int slot, Rot rot, Vec2i origin) implements AiAction {
    }

    record Tower(TowerKind kind, Rot rot, Vec2i origin) implements AiAction {
    }

    /**
     * タワーを 1 段階強化する。
     *
     * @param towerIndex スナップショットに並べた順番。セルではなく番号で指すのは
     *                   方策の {@code unit} ヘッドがそういう形をしているため
     * @param spec       最終段階でのみ使う特化（0 / 1）。それ以外では -1
     */
    record Upgrade(int towerIndex, int spec) implements AiAction {
    }

    record Sell(int towerIndex) implements AiAction {
    }

    record Send(AttackerKind kind) implements AiAction {
    }

    /** 1 手ぶんの応答（席番号 + 行動）。 */
    record Seated(int seat, AiAction action) {
    }

    /**
     * {@code "<席> card <スロット> <回転> <x> <z>"} 形式を読む。
     *
     * @return 読めなければ null（壊れた行で試合を止めない）
     */
    static Seated parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            int seat = Integer.parseInt(parts[0]);
            AiAction action = switch (parts[1]) {
                case "skip" -> new Skip();
                case "card" -> new Card(Integer.parseInt(parts[2]), rot(parts[3]),
                        new Vec2i(Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
                case "tower" -> new Tower(TowerKind.valueOf(parts[2]), rot(parts[3]),
                        new Vec2i(Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
                case "upgrade" -> new Upgrade(Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]));
                case "sell" -> new Sell(Integer.parseInt(parts[2]));
                case "send" -> new Send(AttackerKind.valueOf(parts[2]));
                default -> null;
            };
            return action == null ? null : new Seated(seat, action);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Rot rot(String text) {
        return Rot.values()[Math.floorMod(Integer.parseInt(text), 4)];
    }

    /** ログとアクションバーに出す短い説明。 */
    static String describe(AiAction action) {
        return switch (action) {
            case Skip ignored -> "待機";
            case Card card -> "壁を置く (" + card.origin().x() + "," + card.origin().z() + ")";
            case Tower tower -> tower.kind().displayName() + " を設置";
            case Upgrade upgrade -> "強化 #" + upgrade.towerIndex();
            case Sell sell -> "売却 #" + sell.towerIndex();
            case Send send -> send.kind().displayName() + " を送る";
        };
    }
}
