package dev.antigravity.mazeward.core;

/**
 * グリッド上の整数座標。Minecraft の (x, z) 平面に対応する。
 * core パッケージは Minestom に一切依存しない純粋ロジック。
 */
public record Vec2i(int x, int z) {

    public static final Vec2i ZERO = new Vec2i(0, 0);

    public Vec2i add(int dx, int dz) {
        return new Vec2i(x + dx, z + dz);
    }

    public Vec2i add(Vec2i other) {
        return new Vec2i(x + other.x, z + other.z);
    }

    public Vec2i sub(Vec2i other) {
        return new Vec2i(x - other.x, z - other.z);
    }

    public int manhattan(Vec2i other) {
        return Math.abs(x - other.x) + Math.abs(z - other.z);
    }

    public double distance(Vec2i other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double distance(double px, double pz) {
        double dx = x + 0.5 - px;
        double dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return "(" + x + "," + z + ")";
    }
}
