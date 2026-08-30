package dev.antigravity.mazeward.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.BlockDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;

/**
 * 経路・ゴースト・射程の視覚化。<b>このゲームの生命線</b>。
 *
 * <p>仕様どおり「現在の経路（青）」「配置予定の障害物（黄）」「配置後の予測経路（赤）」を
 * 同時に、別の高さに描いて見分けられるようにしている。</p>
 */
public final class Overlay {

    /** 青い現在経路を描く高さ（床からのオフセット）。 */
    public static final double Y_CURRENT = 0.12;

    /** 赤い予測経路を描く高さ。青と重ならないように少し上げる。 */
    public static final double Y_PREVIEW = 0.42;

    private Overlay() {
    }

    // ---------------------------------------------------------------- 経路

    /** 経路サンプリングの間隔（ブロック）。 */
    private static final double PATH_SPACING = 0.55;

    /**
     * 経路をダストパーティクルで描く。
     *
     * <p>経路はマス目の列ではなく <b>曲がり角を結んだ折れ線</b> なので、
     * セルごとに点を打つのではなく線分に沿って一定間隔でサンプリングする。
     * これで 45° に限らない任意角度の直線がそのまま見える。
     * 曲がり角は大きい粒で強調して、どこで曲がるのかを読めるようにしている。</p>
     *
     * <p>色は 1 経路につき 1 色だけ使う。進行方向マーカーのような別色を重ねると、
     * 「現在の経路（青）」と「変更後の経路（赤）」の区別がつきにくくなるため。</p>
     *
     * @param waypoints 曲がり角の <b>ワールド座標</b>。
     *                  グリッド座標との変換は {@code ArenaRenderer} が持つので、
     *                  ここは island の位置を一切知らない
     */
    public static void drawPath(Collection<Player> viewers, List<Pos> waypoints,
                                Color color, double yOffset, float scale) {
        if (viewers.isEmpty() || waypoints.isEmpty()) {
            return;
        }
        Particle dust = Palette.DUST.withColor(color).withScale(scale);
        Particle cornerDust = Palette.DUST.withColor(color).withScale(scale + 0.9f);
        double y = ArenaRenderer.SURFACE_Y + yOffset;

        // 曲がり角を強調する（「ここで曲がる」を読ませるのが目的）
        for (Pos waypoint : waypoints) {
            emit(viewers, cornerDust, waypoint.x(), y + 0.06, waypoint.z());
        }
        if (waypoints.size() == 1) {
            return;
        }

        int count = waypoints.size();
        double[] cumulative = new double[count];
        for (int i = 1; i < count; i++) {
            cumulative[i] = cumulative[i - 1] + flatDistance(waypoints.get(i - 1), waypoints.get(i));
        }
        double total = cumulative[count - 1];
        if (total <= 1e-6) {
            return;
        }

        int segment = 0;
        for (double travelled = 0; travelled <= total; travelled += PATH_SPACING) {
            while (segment < count - 2 && cumulative[segment + 1] < travelled) {
                segment++;
            }
            Pos from = waypoints.get(segment);
            Pos to = waypoints.get(segment + 1);
            double segmentLength = cumulative[segment + 1] - cumulative[segment];
            double t = segmentLength <= 1e-9 ? 0.0 : (travelled - cumulative[segment]) / segmentLength;
            double x = from.x() + (to.x() - from.x()) * t;
            double z = from.z() + (to.z() - from.z()) * t;

            emit(viewers, dust, x, y, z);
        }
    }

    private static double flatDistance(Pos a, Pos b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void emit(Collection<Player> viewers, Particle particle, double x, double y, double z) {
        for (Player viewer : viewers) {
            viewer.sendPacket(new ParticlePacket(particle, true, false, x, y, z, 0f, 0f, 0f, 0f, 0));
        }
    }

    /** 飛行敵の直線ルート（迷路を無視するので別途示す必要がある）。 */
    public static void drawStraightLine(Collection<Player> viewers, Pos from, Pos to,
                                        Color color, float scale) {
        drawStraightLine(viewers, from, to, color, scale, 1.2);
    }

    /**
     * 直線をダストで描く。
     *
     * @param spacing 粒の間隔（ブロック）。小さいほど濃い線になる。
     *                ロードマップの道のように「線として追う」ものは詰めて描く
     */
    public static void drawStraightLine(Collection<Player> viewers, Pos from, Pos to,
                                        Color color, float scale, double spacing) {
        if (viewers.isEmpty()) {
            return;
        }
        Particle dust = Palette.DUST.withColor(color).withScale(scale);
        double distance = from.distance(to);
        int steps = Math.max(1, (int) (distance / Math.max(0.05, spacing)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x() + (to.x() - from.x()) * t;
            double y = from.y() + (to.y() - from.y()) * t;
            double z = from.z() + (to.z() - from.z()) * t;
            for (Player viewer : viewers) {
                viewer.sendPacket(new ParticlePacket(dust, true, false, x, y, z, 0f, 0f, 0f, 0f, 0));
            }
        }
    }

    // ---------------------------------------------------------------- 射程・弾道

    /** タワー → 敵の弾道。エンティティを作らずパーティクルの線で表す（軽い）。 */
    public static void drawTracer(Collection<Player> viewers, Pos from, Pos to, Color color, float scale) {
        if (viewers.isEmpty()) {
            return;
        }
        Particle dust = Palette.DUST.withColor(color).withScale(scale);
        double distance = from.distance(to);
        int steps = Math.max(2, (int) (distance * 1.8));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x() + (to.x() - from.x()) * t;
            double y = from.y() + (to.y() - from.y()) * t + Math.sin(Math.PI * t) * 0.35;
            double z = from.z() + (to.z() - from.z()) * t;
            for (Player viewer : viewers) {
                viewer.sendPacket(new ParticlePacket(dust, true, false, x, y, z, 0f, 0f, 0f, 0f, 0));
            }
        }
    }

    public static void drawBurst(Collection<Player> viewers, Pos at, Particle particle, int count, float spread) {
        for (Player viewer : viewers) {
            viewer.sendPacket(new ParticlePacket(particle, true, false,
                    at.x(), at.y(), at.z(), spread, spread, spread, 0.05f, count));
        }
    }

    /** ダメージ表示などに使う、短時間だけ浮かぶテキスト。背景は透明。 */
    public static void popupText(Instance instance, Player viewer, Pos at, Component text, int ticks) {
        Entity display = new Entity(EntityType.TEXT_DISPLAY);
        display.setNoGravity(true);
        display.setAutoViewable(false);
        display.setInstance(instance, at);
        display.addViewer(viewer);
        display.editEntityMeta(TextDisplayMeta.class, meta -> {
            meta.setText(text);
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
            meta.setUseDefaultBackground(false);
            meta.setBackgroundColor(0x00000000);
            meta.setShadow(true);
            meta.setSeeThrough(true);
            meta.setViewRange(0.6f);
        });
        display.scheduleRemove(Duration.ofMillis(ticks * 50L));
    }

    /**
     * 浮かぶ文字。<b>背景の灰色は消して文字だけにする。</b>
     *
     * <p>既定の背景は半透明の灰色板で、盤面や経路のパーティクルを覆い隠してしまう。
     * 背景をアルファ 0 にして影を付けると、下地を隠さずに読める文字になる。</p>
     */
    public static Entity createLabel(Instance instance, Pos at, Component text, float viewRange) {
        Entity display = new Entity(EntityType.TEXT_DISPLAY);
        display.setNoGravity(true);
        display.setInstance(instance, at);
        display.editEntityMeta(TextDisplayMeta.class, meta -> {
            meta.setText(text);
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
            // 背景板を完全に透明にする。影を付けないと暗い床の上で読めなくなる
            meta.setUseDefaultBackground(false);
            meta.setBackgroundColor(0x00000000);
            meta.setShadow(true);
            meta.setSeeThrough(false);
            meta.setViewRange(viewRange);
            meta.setAlignment(TextDisplayMeta.Alignment.CENTER);
        });
        return display;
    }

    public static void updateLabel(Entity label, Component text) {
        label.editEntityMeta(TextDisplayMeta.class, meta -> meta.setText(text));
    }

    // ---------------------------------------------------------------- ゴースト

    /**
     * 「配置予定の障害物」をブロック表示エンティティで描くビュー。
     * プレイヤー 1 人にだけ見え、カーソルが動いた tick だけ更新される。
     */
    public static final class GhostView {

        private final Player owner;
        private final Instance instance;
        private final List<Entity> blocks = new ArrayList<>();
        private Entity label;
        private boolean visible;

        // 同じ内容を毎 tick 送り直すと、文字がわずかに揺れて「ちかちか」して見える
        private Component lastLabelText;
        private Pos lastLabelPos;

        public GhostView(Player owner, Instance instance) {
            this.owner = owner;
            this.instance = instance;
        }

        /**
         * ゴーストを表示する。
         *
         * @param cells  各セルの中心の <b>ワールド座標</b>
         * @param baseY  ブロック表示の底面の高さ（障害物は床、タワーは壁の上）
         * @param height ブロック表示の高さ
         */
        public void show(List<Pos> cells, Block block, double baseY, double height,
                         Component labelText, double labelX, double labelZ, double labelY) {
            visible = true;
            ensureCapacity(cells.size(), block);

            for (int i = 0; i < cells.size(); i++) {
                Pos cell = cells.get(i);
                Entity entity = blocks.get(i);
                entity.teleport(new Pos(cell.x(), baseY, cell.z()));
                entity.editEntityMeta(BlockDisplayMeta.class, meta -> {
                    meta.setBlockState(block);
                    meta.setScale(new Vec(0.9, height, 0.9));
                });
            }
            for (int i = cells.size(); i < blocks.size(); i++) {
                blocks.get(i).removeViewer(owner);
            }
            for (int i = 0; i < cells.size(); i++) {
                blocks.get(i).addViewer(owner);
            }

            if (labelText == null) {
                if (label != null) {
                    label.removeViewer(owner);
                }
                return;
            }
            Pos labelPos = new Pos(labelX, labelY, labelZ);
            if (label == null) {
                label = createLabel(instance, labelPos, labelText, 1.2f);
                label.setAutoViewable(false);
            } else {
                if (!samePoint(labelPos, lastLabelPos)) {
                    label.teleport(labelPos);
                }
                if (!labelText.equals(lastLabelText)) {
                    updateLabel(label, labelText);
                }
            }
            lastLabelText = labelText;
            lastLabelPos = labelPos;
            label.addViewer(owner);
        }

        /**
         * ブロックを出さず、文字だけを浮かべる。
         *
         * <p>内容も位置も変わっていないときは <b>何も送らない</b>。
         * 同じテキストを毎 tick 送り直すと、文字がわずかに揺れてちかちかする。</p>
         */
        public void showLabel(Component text, double x, double y, double z) {
            visible = true;
            for (Entity entity : blocks) {
                entity.removeViewer(owner);
            }
            if (text == null) {
                if (label != null) {
                    label.removeViewer(owner);
                }
                return;
            }
            Pos labelPos = new Pos(x, y, z);
            if (label == null) {
                label = createLabel(instance, labelPos, text, 1.2f);
                label.setAutoViewable(false);
            } else {
                if (!samePoint(labelPos, lastLabelPos)) {
                    label.teleport(labelPos);
                }
                if (!text.equals(lastLabelText)) {
                    updateLabel(label, text);
                }
            }
            lastLabelText = text;
            lastLabelPos = labelPos;
            label.addViewer(owner);
        }

        public void hide() {
            if (!visible) {
                return;
            }
            visible = false;
            for (Entity entity : blocks) {
                entity.removeViewer(owner);
            }
            if (label != null) {
                label.removeViewer(owner);
            }
        }

        public void dispose() {
            for (Entity entity : blocks) {
                entity.remove();
            }
            blocks.clear();
            if (label != null) {
                label.remove();
                label = null;
            }
            visible = false;
        }

        private static boolean samePoint(Pos a, Pos b) {
            return b != null && a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
        }

        private void ensureCapacity(int size, Block block) {
            while (blocks.size() < size) {
                Entity entity = new Entity(EntityType.BLOCK_DISPLAY);
                entity.setNoGravity(true);
                entity.setAutoViewable(false);
                entity.setInstance(instance, new Pos(0, ArenaRenderer.SURFACE_Y, 0));
                entity.editEntityMeta(BlockDisplayMeta.class, meta -> {
                    meta.setBlockState(block);
                    meta.setTranslation(new Vec(-0.45, 0.0, -0.45));
                    meta.setScale(new Vec(0.9, 1.9, 0.9));
                    meta.setViewRange(1.5f);
                    meta.setBrightness(15, 15);
                });
                blocks.add(entity);
            }
        }
    }

    /**
     * 射程の輪。<b>パーティクルではなくブロック表示で描く。</b>
     *
     * <p>ダスト粒は 1 粒ずつ寿命がばらつくので、同じ輪を数 tick おきに描き直すと
     * 消えるタイミングがずれてちかちかする。狙いを定めているあいだ
     * ずっと見ている輪でそれが起きると、ただの雑音になる。</p>
     *
     * <p>中心と半径が変わらないかぎり何も送らないので、置いたら置きっぱなしで静止する。</p>
     */
    public static final class RingView {

        /** 半径 1 ブロックあたりの印の数。少ないと多角形に見え、多いと重い。 */
        private static final double MARKS_PER_BLOCK = 3.2;
        private static final int MIN_MARKS = 16;
        private static final int MAX_MARKS = 72;

        private final Player owner;
        private final Instance instance;
        private final List<Entity> marks = new ArrayList<>();

        private boolean visible;
        private double centerX = Double.NaN;
        private double centerZ = Double.NaN;
        private double radius = -1;
        private double y = Double.NaN;

        public RingView(Player owner, Instance instance) {
            this.owner = owner;
            this.instance = instance;
        }

        public void show(double centerX, double centerZ, double radius, double y) {
            if (visible && same(centerX, this.centerX) && same(centerZ, this.centerZ)
                    && same(radius, this.radius) && same(y, this.y)) {
                return;
            }
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.y = y;
            visible = true;

            int count = (int) Math.round(radius * MARKS_PER_BLOCK);
            count = Math.max(MIN_MARKS, Math.min(MAX_MARKS, count));
            ensureCapacity(count);

            for (int i = 0; i < count; i++) {
                double angle = 2 * Math.PI * i / count;
                marks.get(i).teleport(new Pos(
                        centerX + Math.cos(angle) * radius, y,
                        centerZ + Math.sin(angle) * radius));
            }
            for (int i = count; i < marks.size(); i++) {
                marks.get(i).removeViewer(owner);
            }
            for (int i = 0; i < count; i++) {
                marks.get(i).addViewer(owner);
            }
        }

        public void hide() {
            if (!visible) {
                return;
            }
            visible = false;
            radius = -1;
            for (Entity mark : marks) {
                mark.removeViewer(owner);
            }
        }

        public void dispose() {
            for (Entity mark : marks) {
                mark.remove();
            }
            marks.clear();
            visible = false;
        }

        private static boolean same(double a, double b) {
            return Math.abs(a - b) < 1e-6;
        }

        private void ensureCapacity(int size) {
            while (marks.size() < size) {
                Entity mark = new Entity(EntityType.BLOCK_DISPLAY);
                mark.setNoGravity(true);
                mark.setAutoViewable(false);
                mark.setInstance(instance, new Pos(0, ArenaRenderer.SURFACE_Y, 0));
                mark.editEntityMeta(BlockDisplayMeta.class, meta -> {
                    meta.setBlockState(Palette.RANGE_MARK);
                    meta.setTranslation(new Vec(-0.09, 0.0, -0.09));
                    meta.setScale(new Vec(0.18, 0.18, 0.18));
                    meta.setViewRange(1.5f);
                    meta.setBrightness(15, 15);
                });
                marks.add(mark);
            }
        }
    }

    public static TextColor toTextColor(Color color) {
        return TextColor.color(color.red(), color.green(), color.blue());
    }
}
