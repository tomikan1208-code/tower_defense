package dev.antigravity.mazeward.versus;

/**
 * 試合の進む速さ。観戦のときだけ動かせる。
 *
 * <p><b>1 サーバー tick に試合を何 tick 進めるか</b> だけを決める。
 * 敵の移動もタワーのクールダウンも収入もカード配布も、すべて
 * {@code VersusMatch#tick()} の中で tick を数えて動いているので、
 * ここで回数を変えるだけで <b>盤上のすべてが同じ倍率で速くなる</b>。
 * 個々の速度に係数を掛けて回ると、掛け忘れたものだけが取り残される。</p>
 *
 * <p>0.5 倍のような遅い側は、進めない tick を作って実現する。
 * 端数は溜めておくので、0.25 倍なら 4 サーバー tick に 1 回だけ進む。</p>
 *
 * <p>速い側に上限があるのは、1 サーバー tick に 16 回以上進めると
 * <b>1 tick の処理が 50ms を超えてサーバー全体が遅れ始める</b>ため。
 * そうなると「速くしたのに遅くなる」という一番分かりにくい壊れ方をする。</p>
 */
public final class MatchClock {

    /** 選べる倍率。0 は一時停止。 */
    public static final double[] SPEEDS = {0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0};

    /** 等速の位置。 */
    public static final int NORMAL = 3;

    private int index = NORMAL;
    private double carry;
    private int lastIndex = NORMAL;

    public double speed() {
        return SPEEDS[index];
    }

    public int index() {
        return index;
    }

    public boolean paused() {
        return index == 0;
    }

    /** 「x2.0」「一時停止」など、画面に出す文字。 */
    public String label() {
        return paused() ? "一時停止" : "x" + trim(speed());
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    public void faster() {
        index = Math.min(SPEEDS.length - 1, index + 1);
        if (index > 0) {
            lastIndex = index;
        }
    }

    public void slower() {
        index = Math.max(0, index - 1);
        if (index > 0) {
            lastIndex = index;
        }
    }

    /** 一時停止と、止める前の速度の行き来。 */
    public void togglePause() {
        if (paused()) {
            index = lastIndex == 0 ? NORMAL : lastIndex;
        } else {
            lastIndex = index;
            index = 0;
        }
    }

    public void reset() {
        index = NORMAL;
        lastIndex = NORMAL;
        carry = 0.0;
    }

    /**
     * このサーバー tick で試合を何回進めるか。
     *
     * <p>端数は次の tick へ繰り越す。切り捨てたままにすると
     * 1.5 倍が実質 1 倍になってしまう。</p>
     */
    public int stepsThisTick() {
        carry += speed();
        int steps = (int) carry;
        carry -= steps;
        return steps;
    }
}
