package dev.antigravity.mazeward.run;

import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Shapes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 障害物カードのデッキ。
 *
 * <p>ルール:</p>
 * <ul>
 *   <li>ライブラリはラン全体で保持され、報酬やショップで増える</li>
 *   <li>ステージ開始時にライブラリをシャッフルして山札にする</li>
 *   <li>建築フェーズの頭に手札上限まで引く</li>
 *   <li>置いたカードはそのステージでは戻ってこない（＝有限資源）</li>
 * </ul>
 *
 * <p>この「有限」がないと、壁を置くほど経路も土台も両方伸びてしまい、
 * 意思決定がなくなる。</p>
 */
public final class Deck {

    /**
     * 手札の上限。
     *
     * <p>ホットバーの切り替えパレットが 6 枠なので、それ以上は持てても表示できない。
     * 「増えたはずのカードが見えない」ほうが、上限で止まるより分かりにくい。</p>
     */
    public static final int MAX_HAND_SIZE = 6;

    private final List<BlockCard> library = new ArrayList<>();
    private final List<BlockCard> drawPile = new ArrayList<>();
    private final List<BlockCard> hand = new ArrayList<>();
    private int baseHandSize = 4;
    private int nextVariant;

    public static Deck starter() {
        Deck deck = new Deck();
        for (Shape shape : Shapes.starterDeck()) {
            deck.addShape(shape);
        }
        return deck;
    }

    public void add(BlockCard card) {
        library.add(card);
    }

    /**
     * カードを 1 枚足す。素材番号を連番で振るので、
     * 続けて手に入れたカードは必ず違うブロックになる。
     */
    public void addShape(Shape shape) {
        library.add(new BlockCard(shape, null, nextVariant++));
    }

    public List<BlockCard> library() {
        return Collections.unmodifiableList(library);
    }

    /**
     * ライブラリの 1 枚にルーンを付ける。
     * すでにルーンが付いているカードには付け直せない（付け替えを許すと選択が薄まるため）。
     */
    public boolean applyRune(int libraryIndex, Rune rune) {
        if (rune == null || libraryIndex < 0 || libraryIndex >= library.size()) {
            return false;
        }
        BlockCard card = library.get(libraryIndex);
        if (card.hasRune()) {
            return false;
        }
        library.set(libraryIndex, card.withRune(rune));
        return true;
    }

    /** ルーンを付けられるカードが 1 枚でもあるか。 */
    public boolean hasPlainCard() {
        for (BlockCard card : library) {
            if (!card.hasRune()) {
                return true;
            }
        }
        return false;
    }

    public int runedCount() {
        int count = 0;
        for (BlockCard card : library) {
            if (card.hasRune()) {
                count++;
            }
        }
        return count;
    }

    public int librarySize() {
        return library.size();
    }

    public int baseHandSize() {
        return baseHandSize;
    }

    public boolean canIncreaseHandSize() {
        return baseHandSize < MAX_HAND_SIZE;
    }

    public void increaseHandSize(int amount) {
        baseHandSize = Math.min(MAX_HAND_SIZE, baseHandSize + amount);
    }

    /** ステージ開始時。山札を作り直して手札を空にする。 */
    public void resetForStage(Random random) {
        drawPile.clear();
        drawPile.addAll(library);
        Collections.shuffle(drawPile, random);
        hand.clear();
    }

    /** 建築フェーズの頭に呼ぶ。引いた枚数を返す。 */
    public int drawToHandSize(int handSize) {
        int drawn = 0;
        while (hand.size() < handSize && !drawPile.isEmpty()) {
            hand.add(drawPile.remove(drawPile.size() - 1));
            drawn++;
        }
        return drawn;
    }

    /**
     * 1 枚だけ引く。山札が尽きていたらライブラリを切り直して引き直す。
     * 対戦のように「一定時間ごとに配る」用途で使う。
     */
    public boolean drawOne(int handLimit, Random random) {
        if (hand.size() >= handLimit) {
            return false;
        }
        if (drawPile.isEmpty()) {
            drawPile.addAll(library);
            Collections.shuffle(drawPile, random);
        }
        if (drawPile.isEmpty()) {
            return false;
        }
        hand.add(drawPile.remove(drawPile.size() - 1));
        return true;
    }

    public List<BlockCard> hand() {
        return Collections.unmodifiableList(hand);
    }

    public BlockCard peek(int index) {
        if (index < 0 || index >= hand.size()) {
            return null;
        }
        return hand.get(index);
    }

    /** 手札からカードを消費する。 */
    public BlockCard play(int index) {
        if (index < 0 || index >= hand.size()) {
            return null;
        }
        return hand.remove(index);
    }

    public int drawPileSize() {
        return drawPile.size();
    }

    public boolean handEmpty() {
        return hand.isEmpty();
    }

    public boolean exhausted() {
        return hand.isEmpty() && drawPile.isEmpty();
    }
}
