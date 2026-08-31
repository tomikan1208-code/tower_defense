package dev.antigravity.mazeward.ai;

/**
 * 最小の JSON 書き出し。
 *
 * <p>ライブラリを足さないのは、この用途で必要なのが <b>書き出しだけ</b> だから。
 * 返ってくる行動は 1 行のテキスト（{@link AiAction#parse}）にしてあるので、
 * 読み取り側の JSON は要らない。依存を 1 つ増やすほどの仕事がない。</p>
 *
 * <p>カンマは呼び出し側で数えない。直前に書いた文字が {@code { [ :} のどれかで
 * なければ区切りが要る、という判定だけで足りる。付け忘れも付けすぎも起きない。</p>
 */
public final class Json {

    private final StringBuilder out = new StringBuilder(8192);

    private void separate() {
        if (out.isEmpty()) {
            return;
        }
        char last = out.charAt(out.length() - 1);
        if (last != '{' && last != '[' && last != ':' && last != ',') {
            out.append(',');
        }
    }

    public Json beginObject() {
        separate();
        out.append('{');
        return this;
    }

    public Json endObject() {
        out.append('}');
        return this;
    }

    public Json beginArray() {
        separate();
        out.append('[');
        return this;
    }

    public Json endArray() {
        out.append(']');
        return this;
    }

    /** キーを書く。次に来る値は区切り無しで続く。 */
    public Json key(String name) {
        separate();
        string(name);
        out.append(':');
        return this;
    }

    public Json value(String text) {
        separate();
        string(text);
        return this;
    }

    public Json value(int number) {
        separate();
        out.append(number);
        return this;
    }

    public Json value(boolean flag) {
        separate();
        out.append(flag);
        return this;
    }

    /**
     * 小数は 3 桁で切る。
     *
     * <p>座標も HP も 1/1000 まで一致していれば方策の入力としては同じ。
     * 既定の 17 桁で出すとスナップショットが 3 倍近く膨らむ。</p>
     */
    public Json value(double number) {
        separate();
        if (!Double.isFinite(number)) {
            out.append('0');
            return this;
        }
        out.append(Math.round(number * 1000.0) / 1000.0);
        return this;
    }

    public Json field(String name, String text) {
        return key(name).value(text);
    }

    public Json field(String name, int number) {
        return key(name).value(number);
    }

    public Json field(String name, double number) {
        return key(name).value(number);
    }

    public Json field(String name, boolean flag) {
        return key(name).value(flag);
    }

    private void string(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
