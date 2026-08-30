package dev.antigravity.mazeward.core;

/** 90度単位の回転。形状カード・タワーの両方で使う。 */
public enum Rot {
    R0("0°"),
    R90("90°"),
    R180("180°"),
    R270("270°");

    private final String label;

    Rot(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Rot next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Rot prev() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }

    /** 原点まわりに回転させる。y 軸まわりの右回り。 */
    public Vec2i apply(Vec2i v) {
        return switch (this) {
            case R0 -> v;
            case R90 -> new Vec2i(-v.z(), v.x());
            case R180 -> new Vec2i(-v.x(), -v.z());
            case R270 -> new Vec2i(v.z(), -v.x());
        };
    }

    /** Minecraft の yaw に対応する角度。 */
    public float yaw() {
        return switch (this) {
            case R0 -> 0f;
            case R90 -> 90f;
            case R180 -> 180f;
            case R270 -> 270f;
        };
    }
}
