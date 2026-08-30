package dev.antigravity.mazeward.world;

import dev.antigravity.mazeward.core.CellType;
import dev.antigravity.mazeward.core.Grid;
import dev.antigravity.mazeward.core.Vec2i;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * グリッド ⇄ Minecraft ワールドの写像を一手に引き受ける層。
 *
 * <p>ここより上（core / stage / tower / enemy）は「セル座標」しか知らず、
 * ここより下（Minestom）は「ブロック座標」しか知らない。</p>
 *
 * <pre>
 *   y = 67.5  飛行敵
 *   y = 66    タワーの模型が乗る面（WALL_TOP_Y）
 *   y = 65    壁ブロック / 地上敵の足元（SURFACE_Y）
 *   y = 64    床ブロック（FLOOR_Y）
 * </pre>
 *
 * <p>壁は 1 ブロック高。上空から俯瞰したときに迷路の形がいちばん読みやすいのがこの高さで、
 * 「本来なら 1 ブロックは乗り越えられる」というバニラの挙動はゲームルールとして無視する。</p>
 */
public final class ArenaRenderer {

    public static final int FLOOR_Y = 64;
    public static final int SURFACE_Y = FLOOR_Y + 1;
    public static final int WALL_HEIGHT = 1;
    public static final int WALL_TOP_Y = SURFACE_Y + WALL_HEIGHT;
    public static final int BORDER_HEIGHT = 2;
    public static final double FLYING_Y = SURFACE_Y + 2.5;

    private final Instance instance;
    private final Palette.Theme theme;

    public ArenaRenderer(Instance instance, Palette.Theme theme) {
        this.instance = instance;
        this.theme = theme;
    }

    public Instance instance() {
        return instance;
    }

    public Palette.Theme theme() {
        return theme;
    }

    // ---------------------------------------------------------------- 座標変換

    /** セルの中心のワールド座標。 */
    public Pos center(Vec2i cell, double y) {
        return new Pos(cell.x() + 0.5, y, cell.z() + 0.5);
    }

    public Pos surfaceCenter(Vec2i cell) {
        return center(cell, SURFACE_Y);
    }

    /** ワールド座標 → セル座標。 */
    public Vec2i toCell(double worldX, double worldZ) {
        return new Vec2i((int) Math.floor(worldX), (int) Math.floor(worldZ));
    }

    public double cellCenterX(Vec2i cell) {
        return cell.x() + 0.5;
    }

    public double cellCenterZ(Vec2i cell) {
        return cell.z() + 0.5;
    }

    // ---------------------------------------------------------------- 描画

    /** 盤面全体を描き直す。ステージ開始時に一度だけ呼ぶ。 */
    public void paintAll(Grid grid) {
        int w = grid.width();
        int h = grid.height();

        // 外周の土台（アリーナが浮島として成立するように 1 マス広く敷く）
        for (int x = -2; x < w + 2; x++) {
            for (int z = -2; z < h + 2; z++) {
                boolean inside = x >= 0 && z >= 0 && x < w && z < h;
                if (inside) {
                    continue;
                }
                instance.setBlock(x, FLOOR_Y, z, theme.border());
                instance.setBlock(x, FLOOR_Y - 1, z, Block.DEEPSLATE);
            }
        }

        // 外周の壁（1 マス外側に立てる）
        for (int x = -1; x < w + 1; x++) {
            for (int z = -1; z < h + 1; z++) {
                boolean ring = x == -1 || z == -1 || x == w || z == h;
                if (!ring) {
                    continue;
                }
                for (int y = 0; y < BORDER_HEIGHT; y++) {
                    instance.setBlock(x, SURFACE_Y + y, z, theme.border());
                }
            }
        }

        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                paintCell(grid, new Vec2i(x, z));
            }
        }
    }

    /** 1 セルだけ描き直す。カード確定・タワー撤去のときに使う。 */
    public void paintCell(Grid grid, Vec2i cell) {
        int x = cell.x();
        int z = cell.z();
        CellType type = grid.get(cell);

        instance.setBlock(x, FLOOR_Y - 1, z, Block.DEEPSLATE);
        instance.setBlock(x, FLOOR_Y, z, floorBlockFor(type));

        // 岩は自然地形なのでセル座標でばらけさせる。
        // 壁のほうは「置いたカードの素材」で塗り直されるので、ここでは既定値だけ置く。
        Block column = switch (type) {
            case WALL -> theme.wallForVariant(0);
            case ROCK -> theme.rockFor(x, z);
            default -> Block.AIR;
        };
        for (int y = 0; y < WALL_HEIGHT; y++) {
            instance.setBlock(x, SURFACE_Y + y, z, column);
        }
        // 壁の上（タワーが乗る面）は既定では空にする
        instance.setBlock(x, WALL_TOP_Y, z, Block.AIR);
    }

    private Block floorBlockFor(CellType type) {
        return switch (type) {
            case SPAWN -> Block.NETHER_WART_BLOCK;
            case CORE -> Block.SEA_LANTERN;
            case WALL, ROCK -> theme.accent();
            default -> theme.floor();
        };
    }

    /**
     * 壁 1 セルのブロックを差し替える。ルーン付きの壁を色分けするために使う。
     * ルーンの存在は run パッケージの都合なので、world 側は「何を置くか」だけを受け取る。
     */
    public void paintWall(Vec2i cell, Block block) {
        for (int y = 0; y < WALL_HEIGHT; y++) {
            instance.setBlock(cell.x(), SURFACE_Y + y, cell.z(), block);
        }
    }

    /** タワーの模型ブロックを壁の上に置く。 */
    public void paintTower(Iterable<Vec2i> footprint, Block model) {
        for (Vec2i cell : footprint) {
            instance.setBlock(cell.x(), WALL_TOP_Y, cell.z(), model);
        }
    }

    public void clearTower(Iterable<Vec2i> footprint) {
        for (Vec2i cell : footprint) {
            instance.setBlock(cell.x(), WALL_TOP_Y, cell.z(), Block.AIR);
        }
    }

    /** アリーナ全体を俯瞰できる観戦位置。 */
    public Pos overviewPos(Grid grid) {
        double cx = grid.width() / 2.0;
        double cz = grid.height() / 2.0;
        double height = SURFACE_Y + Math.max(18, Math.max(grid.width(), grid.height()) * 0.95);
        return new Pos(cx, height, cz + grid.height() * 0.42, 0f, 62f);
    }
}
