package dev.antigravity.mazeward.ai;

import dev.antigravity.mazeward.versus.VersusMatch;
import java.util.List;

/**
 * AI の頭。学習済みの方策（{@link BrainClient}）と、
 * その場で計算する貪欲ボット（{@link HeuristicPolicy}）が同じ形で刺さる。
 *
 * <p><b>非同期を前提にしている。</b> 方策は別プロセスにいるので、要求した tick に
 * 答えが返るとは限らない。返事が 1〜2 tick 遅れても、意思決定は 1 秒に 1 回なので
 * ゲームとしては何も変わらない。ここで待ち合わせるとサーバー全体が止まる。</p>
 */
public interface AiPolicy {

    /** 表示に出す名前（「学習済み方策 gen42」など）。 */
    String name();

    /** いま使えるか。false になったら {@code AiDirector} が貪欲ボットへ切り替える。 */
    boolean available();

    /** 答えを待っている最中か。true のあいだは重ねて要求しない。 */
    boolean pending();

    /**
     * 行動を求める。
     *
     * @param askSeats 行動を求める席
     * @param stats    席ごとの統計（無効手の割合が観測に入っている）
     */
    void request(VersusMatch match, List<Integer> askSeats, MatchSnapshot.SeatStats[] stats);

    /** 届いた行動を取り出す。まだなら空リスト。 */
    List<AiAction.Seated> poll();

    void close();
}
