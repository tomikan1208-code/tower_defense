package dev.antigravity.mazeward.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 「形状」そのもの。障害物カードとタワーの両方がこれを持つ。
 *
 * <p>セル集合は必ず正規化されている（最小 x = 0, 最小 z = 0、辞書順ソート）。
 * 4 方向の回転は生成時に計算してキャッシュするので、プレビュー中に毎 tick 回しても軽い。</p>
 */
public final class Shape {

    private final String id;
    private final String displayName;
    private final Map<Rot, List<Vec2i>> rotations = new EnumMap<>(Rot.class);
    private final Map<Rot, int[]> bounds = new EnumMap<>(Rot.class);

    private Shape(String id, String displayName, List<Vec2i> rawCells) {
        this.id = id;
        this.displayName = displayName;
        for (Rot rot : Rot.values()) {
            List<Vec2i> rotated = new ArrayList<>(rawCells.size());
            for (Vec2i cell : rawCells) {
                rotated.add(rot.apply(cell));
            }
            List<Vec2i> normalized = normalize(rotated);
            rotations.put(rot, List.copyOf(normalized));
            bounds.put(rot, extent(normalized));
        }
    }

    /** {@code of("I3", "1x3", 0,0, 1,0, 2,0)} のように x,z ペアで書く。 */
    public static Shape of(String id, String displayName, int... xzPairs) {
        if (xzPairs.length % 2 != 0) {
            throw new IllegalArgumentException("x,z のペアで指定してください: " + id);
        }
        List<Vec2i> cells = new ArrayList<>(xzPairs.length / 2);
        for (int i = 0; i < xzPairs.length; i += 2) {
            cells.add(new Vec2i(xzPairs[i], xzPairs[i + 1]));
        }
        return new Shape(id, displayName, cells);
    }

    private static List<Vec2i> normalize(List<Vec2i> cells) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (Vec2i cell : cells) {
            minX = Math.min(minX, cell.x());
            minZ = Math.min(minZ, cell.z());
        }
        List<Vec2i> out = new ArrayList<>(cells.size());
        for (Vec2i cell : cells) {
            out.add(new Vec2i(cell.x() - minX, cell.z() - minZ));
        }
        out.sort(Comparator.comparingInt(Vec2i::z).thenComparingInt(Vec2i::x));
        return out;
    }

    private static int[] extent(List<Vec2i> cells) {
        int w = 0;
        int h = 0;
        for (Vec2i cell : cells) {
            w = Math.max(w, cell.x() + 1);
            h = Math.max(h, cell.z() + 1);
        }
        return new int[] {w, h};
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int size() {
        return rotations.get(Rot.R0).size();
    }

    public List<Vec2i> cells(Rot rot) {
        return rotations.get(rot);
    }

    public int width(Rot rot) {
        return bounds.get(rot)[0];
    }

    public int height(Rot rot) {
        return bounds.get(rot)[1];
    }

    /** 原点セルを基準にワールド上のセル座標へ展開する。 */
    public List<Vec2i> cellsAt(Vec2i origin, Rot rot) {
        List<Vec2i> local = rotations.get(rot);
        List<Vec2i> out = new ArrayList<>(local.size());
        for (Vec2i cell : local) {
            out.add(origin.add(cell));
        }
        return out;
    }

    /**
     * カーソルセルを形状の中心に合わせるためのオフセット。
     * これがないと L 字などが「右下にずれて」置かれて非常に扱いにくい。
     */
    public Vec2i centerOffset(Rot rot) {
        return new Vec2i(-(width(rot) - 1) / 2, -(height(rot) - 1) / 2);
    }

    /** ホットバーの lore に出す簡易プレビュー。 */
    public List<String> ascii(Rot rot) {
        List<Vec2i> cells = rotations.get(rot);
        int w = width(rot);
        int h = height(rot);
        boolean[][] filled = new boolean[h][w];
        for (Vec2i cell : cells) {
            filled[cell.z()][cell.x()] = true;
        }
        List<String> lines = new ArrayList<>(h);
        for (int z = 0; z < h; z++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < w; x++) {
                sb.append(filled[z][x] ? "■" : "　");
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    @Override
    public String toString() {
        return id;
    }
}
