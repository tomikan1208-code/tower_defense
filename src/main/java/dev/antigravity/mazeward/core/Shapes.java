package dev.antigravity.mazeward.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 障害物カードの形状カタログ。
 * <b>形状を増やしたいときはここに 1 行足すだけでゲーム全体に反映される。</b>
 */
public final class Shapes {

    public static final Shape DOT = Shape.of("DOT", "点", 0, 0);
    public static final Shape I2 = Shape.of("I2", "1x2", 0, 0, 1, 0);
    public static final Shape I3 = Shape.of("I3", "1x3", 0, 0, 1, 0, 2, 0);
    public static final Shape I4 = Shape.of("I4", "1x4", 0, 0, 1, 0, 2, 0, 3, 0);
    public static final Shape O = Shape.of("O", "2x2", 0, 0, 1, 0, 0, 1, 1, 1);
    public static final Shape L = Shape.of("L", "L字", 0, 0, 0, 1, 0, 2, 1, 2);
    public static final Shape J = Shape.of("J", "J字", 1, 0, 1, 1, 1, 2, 0, 2);
    public static final Shape T = Shape.of("T", "T字", 0, 0, 1, 0, 2, 0, 1, 1);
    public static final Shape S = Shape.of("S", "S字", 1, 0, 2, 0, 0, 1, 1, 1);
    public static final Shape Z = Shape.of("Z", "Z字", 0, 0, 1, 0, 1, 1, 2, 1);
    public static final Shape P = Shape.of("P", "2x3", 0, 0, 1, 0, 0, 1, 1, 1, 0, 2, 1, 2);
    public static final Shape U = Shape.of("U", "U字", 0, 0, 0, 1, 1, 1, 2, 1, 2, 0);
    public static final Shape CORNER = Shape.of("CORNER", "かぎ形", 0, 0, 0, 1, 1, 1);

    private static final Map<String, Shape> BY_ID = new LinkedHashMap<>();
    private static final List<Weighted> POOL = new ArrayList<>();

    static {
        register(DOT, 4);
        register(I2, 10);
        register(I3, 12);
        register(I4, 7);
        register(O, 8);
        register(L, 9);
        register(J, 9);
        register(T, 8);
        register(S, 6);
        register(Z, 6);
        register(P, 4);
        register(U, 4);
        register(CORNER, 9);
    }

    private Shapes() {
    }

    private static void register(Shape shape, int weight) {
        BY_ID.put(shape.id(), shape);
        POOL.add(new Weighted(shape, weight));
    }

    public static List<Shape> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Shape byId(String id) {
        return BY_ID.get(id);
    }

    /**
     * ランの開始デッキ。素直な形を多めにして最初の一手を分かりやすくする。
     *
     * <p>枚数はゲーム性に直結する。少なすぎると迷路が組めないまま手札が尽き、
     * 多すぎると「置き場所を選ぶ」緊張が消える。ヘッドレスの通しシミュレーションで
     * 「第1層の最終ウェーブ前に使い切らない」ことを確認して 16 枚にしてある。</p>
     */
    public static List<Shape> starterDeck() {
        return List.of(
                I3, I3, I3, I3,
                I2, I2, I2,
                L, L, J, J,
                CORNER, CORNER, CORNER,
                O, T);
    }

    /** 報酬・ショップで提示する形状を重み付きで抽選する。 */
    public static Shape random(Random random) {
        int total = 0;
        for (Weighted weighted : POOL) {
            total += weighted.weight;
        }
        int roll = random.nextInt(total);
        for (Weighted weighted : POOL) {
            roll -= weighted.weight;
            if (roll < 0) {
                return weighted.shape;
            }
        }
        return I3;
    }

    private record Weighted(Shape shape, int weight) {
    }
}
