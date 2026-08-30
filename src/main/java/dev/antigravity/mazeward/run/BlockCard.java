package dev.antigravity.mazeward.run;

import dev.antigravity.mazeward.core.Shape;

/**
 * 障害物カード 1 枚。
 *
 * <p>「石ブロックを 64 個渡す」のではなく <b>形そのものが資源</b> であることが
 * このゲームのルールの中心。</p>
 *
 * @param shape   形
 * @param rune    付与された特殊効果。無印なら null
 * @param variant 素材番号。<b>1 枚のカードは 1 種類のブロックでできている</b>。
 *                カードごとに違う素材が割り当たるので、盤面を見れば
 *                「この壁はどのカードで置いたか」が分かる。
 *                実際にどのブロックになるかは地域テーマ側が決める
 */
public record BlockCard(Shape shape, Rune rune, int variant) {

    public BlockCard(Shape shape) {
        this(shape, null, 0);
    }

    public String displayName() {
        return rune == null ? shape.displayName()
                : shape.displayName() + "・" + rune.displayName();
    }

    public int cellCount() {
        return shape.size();
    }

    public boolean hasRune() {
        return rune != null;
    }

    /** ルーンを付けた新しいカードを返す（カードは不変）。 */
    public BlockCard withRune(Rune newRune) {
        return new BlockCard(shape, newRune, variant);
    }
}
