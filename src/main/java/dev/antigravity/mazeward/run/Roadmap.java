package dev.antigravity.mazeward.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;

/**
 * ラン全体の進行マップ。Slay the Spire 型のノードグラフを 7 層で構成する。
 *
 * <p>単なる「毎回 2〜3 択」ではなく <b>ノード同士が辺でつながったグラフ</b> にしてある。
 * こうすると「この道を辿ればあの精鋭を踏まずにボスへ行ける」「ここで右に逸れると
 * 商店を 2 つ拾えるが精鋭を 1 回踏む」といった、数手先を見た経路選択が生まれる。
 * 目の前の 2 択だけを見せる形だと、この読み合いが丸ごと消えてしまう。</p>
 *
 * <p>Minecraft では GUI のノードマップより <b>物理的な浮島</b> のほうが強いので、
 * この構造をそのまま足場と道として生成し、プレイヤーは歩いて選ぶ。</p>
 */
public final class Roadmap {

    public static final int LAYERS = 7;

    /** ノード種別。増やすときはここに 1 つ足して、遷移側に分岐を 1 つ足す。 */
    public enum NodeKind {
        BATTLE("戦闘", "通常の防衛戦。クリアで報酬 3 択。",
                Material.IRON_SWORD, Block.STONE_BRICKS, NamedTextColor.WHITE),
        ELITE("精鋭", "敵が強い代わりに報酬が良い。",
                Material.NETHERITE_SWORD, Block.CRIMSON_PLANKS, NamedTextColor.RED),
        SHOP("商店", "ゴールドでカード・レリック・タワーを買う。",
                Material.EMERALD, Block.GOLD_BLOCK, NamedTextColor.GREEN),
        ALTAR("祭壇", "3 つの祝福から 1 つ選ぶ。",
                Material.AMETHYST_SHARD, Block.AMETHYST_BLOCK, NamedTextColor.LIGHT_PURPLE),
        EVENT("事件", "予期せぬ出来事。得も損もある選択を迫られる。",
                Material.BOOK, Block.BOOKSHELF, NamedTextColor.YELLOW),
        BOSS("ボス", "地域の主。特殊な挙動を持つ。",
                Material.DRAGON_HEAD, Block.OBSIDIAN, NamedTextColor.DARK_RED);

        private final String displayName;
        private final String description;
        private final Material icon;
        private final Block platform;
        private final TextColor color;

        NodeKind(String displayName, String description, Material icon, Block platform, TextColor color) {
            this.displayName = displayName;
            this.description = description;
            this.icon = icon;
            this.platform = platform;
            this.color = color;
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

        public Block platform() {
            return platform;
        }

        public TextColor color() {
            return color;
        }

        public boolean combat() {
            return this == BATTLE || this == ELITE || this == BOSS;
        }
    }

    /**
     * @param layer 1 始まり
     * @param index 同じ層の中での並び順
     * @param next  次の層のうち、このノードから進めるノードの index
     */
    public record Node(int layer, int index, NodeKind kind, List<Integer> next) {
    }

    private final List<List<Node>> layers = new ArrayList<>();

    private Roadmap() {
    }

    public static Roadmap generate(Random random) {
        Roadmap map = new Roadmap();

        // まず各層の幅と種別だけ決める
        List<List<NodeKind>> kindRows = new ArrayList<>();
        // 第1層も複数ノード。最初からレーンを選ばせる（種別は戦闘で固定して安定させる）
        int firstWidth = random.nextInt(3) + 2;
        List<NodeKind> firstRow = new ArrayList<>();
        for (int i = 0; i < firstWidth; i++) {
            firstRow.add(NodeKind.BATTLE);
        }
        kindRows.add(List.copyOf(firstRow));

        for (int layer = 2; layer < LAYERS; layer++) {
            int width = random.nextInt(3) + 2; // 2〜4 分岐
            kindRows.add(pickKinds(layer, width, random));
        }
        kindRows.add(List.of(NodeKind.BOSS));

        // 次に層と層をつなぐ
        for (int layer = 1; layer <= LAYERS; layer++) {
            List<NodeKind> kinds = kindRows.get(layer - 1);
            List<List<Integer>> edges = layer == LAYERS
                    ? null
                    : connect(kinds.size(), kindRows.get(layer).size(), random);

            List<Node> row = new ArrayList<>(kinds.size());
            for (int i = 0; i < kinds.size(); i++) {
                List<Integer> next = edges == null ? List.of() : List.copyOf(edges.get(i));
                row.add(new Node(layer, i, kinds.get(i), next));
            }
            map.layers.add(List.copyOf(row));
        }
        return map;
    }

    /**
     * 層と層のつなぎ方を決める。よくあるローグライクのマップと同じ形にする。
     *
     * <p>1 つのノードから伸びる道は 1 本か 2 本。行き先は「位置が近い相手」を軸に
     * 少しだけ横へぶれさせるので、道は自然に交差する。<b>交差は許す</b>
     * ——道をパーティクルの直線で描くので、交差していても目で追えるし、
     * 交差があるほうがルートの絡み合いが生まれて経路選択が面白くなる。</p>
     *
     * <p>守るのは 2 つだけ。</p>
     * <ul>
     *   <li>どのノードからも必ず先へ進める（行き止まりを作らない）</li>
     *   <li>どのノードにも必ず入って来られる（絶対に踏めないノードを作らない）</li>
     * </ul>
     */
    private static List<List<Integer>> connect(int fromCount, int toCount, Random random) {
        List<List<Integer>> edges = new ArrayList<>(fromCount);
        for (int i = 0; i < fromCount; i++) {
            edges.add(new ArrayList<>());
        }

        for (int i = 0; i < fromCount; i++) {
            int base = proportional(i, fromCount, toCount);
            // 真正面ではなく ±1 ぶらす。ここで道が斜めに走り、隣の道と交差する
            int first = clamp(base + random.nextInt(3) - 1, toCount);
            edges.get(i).add(first);

            // 半分強のノードは 2 本に分岐する
            if (toCount > 1 && random.nextInt(100) < 55) {
                int second = clamp(first + (random.nextBoolean() ? 1 : -1), toCount);
                if (second != first) {
                    edges.get(i).add(second);
                }
            }
        }

        // 入口のないノードを潰す。分岐の少ないノードに優先して割り当てる
        for (int j = 0; j < toCount; j++) {
            boolean covered = false;
            for (List<Integer> targets : edges) {
                if (targets.contains(j)) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }
            int best = proportional(j, toCount, fromCount);
            for (int offset = 0; offset < fromCount; offset++) {
                int candidate = clamp(best + (offset % 2 == 0 ? offset / 2 : -(offset / 2 + 1)), fromCount);
                if (edges.get(candidate).size() < 2) {
                    best = candidate;
                    break;
                }
            }
            edges.get(best).add(j);
        }

        for (List<Integer> targets : edges) {
            Collections.sort(targets);
        }
        return edges;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    /** 位置の比率で対応する相手の index。道の「正面」を決める基準になる。 */
    private static int proportional(int index, int fromCount, int toCount) {
        if (fromCount <= 1 || toCount <= 1) {
            return 0;
        }
        return (int) Math.round(index * (toCount - 1) / (double) (fromCount - 1));
    }

    private static List<NodeKind> pickKinds(int layer, int width, Random random) {
        List<NodeKind> pool = new ArrayList<>();
        pool.add(NodeKind.BATTLE);
        pool.add(NodeKind.BATTLE);
        // 精鋭は第4層から。第3層に置くと、通常戦闘 2 回ぶんの成長しかない状態で
        // 難易度が跳ね上がり、そこだけが壁になってしまう。
        if (layer >= 4) {
            pool.add(NodeKind.ELITE);
        }
        pool.add(NodeKind.SHOP);
        pool.add(NodeKind.ALTAR);
        pool.add(NodeKind.EVENT);
        Collections.shuffle(pool, random);

        List<NodeKind> chosen = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            chosen.add(pool.get(i % pool.size()));
        }
        // 全部が非戦闘にならないよう保証（経済だけの層を作らない）
        boolean anyCombat = chosen.stream().anyMatch(NodeKind::combat);
        if (!anyCombat) {
            chosen.set(0, NodeKind.BATTLE);
        }
        return chosen;
    }

    public List<Node> layer(int layer) {
        if (layer < 1 || layer > layers.size()) {
            return List.of();
        }
        return layers.get(layer - 1);
    }

    public Node node(int layer, int index) {
        List<Node> row = layer(layer);
        if (index < 0 || index >= row.size()) {
            return null;
        }
        return row.get(index);
    }

    public int layerCount() {
        return layers.size();
    }

    /** 一番幅の広い層の幅。描画の横幅を決めるのに使う。 */
    public int maxWidth() {
        int max = 1;
        for (List<Node> row : layers) {
            max = Math.max(max, row.size());
        }
        return max;
    }
}
