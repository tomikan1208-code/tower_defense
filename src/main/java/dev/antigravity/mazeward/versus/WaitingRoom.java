package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.world.Overlay;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * 対戦に参加する人が集まる待機部屋。
 *
 * <p>対戦は「何人で始めるか」を先に決めないと島を並べられない。
 * メニューで人数を選ぶ形だと、実際に来ている人数と食い違って
 * 空き島や置いてけぼりが出る。<b>部屋に入っている人がそのまま参加者</b>
 * にしてしまえば、人数は数える必要すらなくなり、
 * 「入る／出る」という物理的な動作がそのまま参加表明になる。</p>
 *
 * <p>ミラーボットは本来デバッグ用なので、この部屋の中から
 * 別枠（デバッグ用アイテム）で呼び出す。</p>
 */
public final class WaitingRoom {

    public static final int FLOOR_Y = 64;

    /** 部屋の内側。ここに立っている人が参加者。 */
    public static final int MIN_X = 10;
    public static final int MAX_X = 20;
    public static final int MIN_Z = -5;
    public static final int MAX_Z = 5;

    /** 入口の手前。ロビーから飛ばすときの着地点。 */
    public static final Pos ENTRY = new Pos(11.5, FLOOR_Y + 1, 0.5);

    /** 島の配置が正方形に収まる範囲での上限。 */
    public static final int CAPACITY = 8;

    private final Instance lobby;
    private Entity board;

    public WaitingRoom(Instance lobby) {
        this.lobby = lobby;
        build();
        updateBoard(List.of());
    }

    /** その人が部屋の中にいるか。 */
    public boolean contains(net.minestom.server.entity.Player player) {
        if (player.getInstance() != lobby) {
            return false;
        }
        Pos pos = player.getPosition();
        return pos.x() >= MIN_X && pos.x() < MAX_X + 1
                && pos.z() >= MIN_Z && pos.z() < MAX_Z + 1
                && pos.y() >= FLOOR_Y && pos.y() <= FLOOR_Y + 6;
    }

    // ---------------------------------------------------------------- 見た目

    private void build() {
        // ロビー（-6..6）から部屋までの通路
        for (int x = 6; x <= MIN_X - 1; x++) {
            for (int z = -1; z <= 1; z++) {
                lobby.setBlock(x, FLOOR_Y, z, Block.POLISHED_DEEPSLATE);
            }
            for (int y = 1; y <= 3; y++) {
                lobby.setBlock(x, FLOOR_Y + y, -2, Block.POLISHED_BLACKSTONE_BRICKS);
                lobby.setBlock(x, FLOOR_Y + y, 2, Block.POLISHED_BLACKSTONE_BRICKS);
            }
        }

        // 床と天井
        for (int x = MIN_X - 1; x <= MAX_X + 1; x++) {
            for (int z = MIN_Z - 1; z <= MAX_Z + 1; z++) {
                lobby.setBlock(x, FLOOR_Y, z, (x + z) % 2 == 0
                        ? Block.POLISHED_DEEPSLATE : Block.DEEPSLATE_TILES);
                lobby.setBlock(x, FLOOR_Y + 6, z, Block.POLISHED_BLACKSTONE);
            }
        }
        // 天井が真っ暗だと誰がいるのか見えない
        for (int x = MIN_X + 1; x <= MAX_X - 1; x += 4) {
            for (int z = MIN_Z + 1; z <= MAX_Z - 1; z += 4) {
                lobby.setBlock(x, FLOOR_Y + 6, z, Block.SEA_LANTERN);
            }
        }

        // 壁。入口だけ開けておく
        for (int x = MIN_X - 1; x <= MAX_X + 1; x++) {
            for (int z = MIN_Z - 1; z <= MAX_Z + 1; z++) {
                boolean edge = x == MIN_X - 1 || x == MAX_X + 1
                        || z == MIN_Z - 1 || z == MAX_Z + 1;
                if (!edge) {
                    continue;
                }
                boolean doorway = x == MIN_X - 1 && z >= -1 && z <= 1;
                for (int y = 1; y <= 5; y++) {
                    if (doorway && y <= 3) {
                        continue;
                    }
                    lobby.setBlock(x, FLOOR_Y + y, z, Block.POLISHED_BLACKSTONE_BRICKS);
                }
            }
        }

        // 「ここがスタート地点」だと分かる台。押すのはホットバーのアイテムだが、
        // 部屋の中に目印がないと、どこを見て待てばいいのか分からない
        for (int x = MAX_X - 2; x <= MAX_X; x++) {
            for (int z = -1; z <= 1; z++) {
                lobby.setBlock(x, FLOOR_Y, z, Block.EMERALD_BLOCK);
            }
        }
    }

    /** 参加者の名簿を貼り替える。出入りしたときだけ呼ぶ。 */
    public void updateBoard(List<String> names) {
        if (board != null) {
            board.remove();
        }
        Component text = Component.text("対戦 待機部屋", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(names.isEmpty()
                        ? Component.text("この部屋に入った人が参加します", NamedTextColor.GRAY)
                        : Component.text("参加 " + names.size() + " / " + CAPACITY + " 人",
                                NamedTextColor.AQUA))
                .append(Component.newline());
        for (String name : names) {
            text = text.append(Component.newline())
                    .append(Component.text("・" + name, NamedTextColor.WHITE));
        }
        text = text.append(Component.newline())
                .append(Component.text(names.size() >= 2
                                ? "緑のアイテムを右クリックで開始"
                                : "2 人以上そろうと開始できます",
                        names.size() >= 2 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));

        board = Overlay.createLabel(lobby, new Pos(MAX_X - 0.5, FLOOR_Y + 3.4, 0.5), text, 4.0f);
        board.setNoGravity(true);
    }
}
