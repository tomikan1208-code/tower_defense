package dev.antigravity.mazeward.ai;

import dev.antigravity.mazeward.versus.VersusMatch;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 学習済み方策（{@code ai/mc_brain.py}）への接続。
 *
 * <p><b>なぜ別プロセスなのか。</b> 方策そのものは小さな CNN なので Java でも
 * 動かせる。問題は方策ではなく <b>観測</b> で、14 チャンネルの盤面・126 個の
 * スカラー・相手 8 人ぶんの要約は {@code observation.py} と {@code env.py} に
 * 散らばった 1 本の定義になっている。これを Java へ書き写すと、
 * 学習側の数値を 1 つ変えるたびに <b>2 箇所を直さないと静かにずれる</b>。
 * ずれても例外は出ず、AI が少し弱くなるだけなので気付けない。
 * 学習と推論で同じコードを通すほうが、実装量より事故の少なさで勝る。</p>
 *
 * <p>通信は 1 行 1 メッセージ。要求は {@code req <番号> <JSON>}、
 * 応答は {@code act <番号> <席> <行動>} を必要なだけ並べて {@code end <番号>}。
 * 番号が合わない応答は捨てる（遅れて届いた前の局面の手を打たないため）。</p>
 *
 * <p>繋がらないときは <b>黙って諦める</b>。{@link #available()} が false になり、
 * 呼び出し側は貪欲ボットへ切り替える。AI が居ないせいで試合が始まらないより、
 * 弱い相手でも試合が回るほうがいい。</p>
 */
public final class BrainClient implements AiPolicy {

    /** 既定のポート。Minecraft の 25565 の隣。 */
    public static final int DEFAULT_PORT = 25577;

    /** 返事を待つ上限（ミリ秒）。これを超えたら要求を捨てて次の判断へ進む。 */
    private static final long ANSWER_TIMEOUT_MS = 6000;

    /** 連続でこの回数だけ失敗したら接続を捨てる。 */
    private static final int MAX_FAILURES = 3;

    private final String host;
    private final int port;
    private final Thread worker;
    /**
     * 送信待ち。状態の要求だけでなく、モデルの切り替えなどの指示も同じ列に積む。
     *
     * <p>枠が 2 しか無かったときは、指示が要求に押し出されて
     * 「選んだのに切り替わらない」ことがあった。指示は小さいので枠を広げてある。</p>
     */
    private final ArrayBlockingQueue<String> outbox = new ArrayBlockingQueue<>(8);
    private final ConcurrentLinkedQueue<List<AiAction.Seated>> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger pendingId = new AtomicInteger(0);
    private final AtomicReference<String> label = new AtomicReference<>("（未接続）");
    private final AtomicReference<Socket> socket = new AtomicReference<>();

    /** 使いたいモデルのファイル名。接続し直しても選び直さなくて済むよう覚えておく。 */
    private final AtomicReference<String> desiredModel = new AtomicReference<>();

    /** 直近の失敗理由。ゲーム内の {@code !ai} で出す。 */
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    /** ブリッジが持っているモデルの一覧。接続時に取りに行く。 */
    private final List<String> models = Collections.synchronizedList(new ArrayList<>());

    private volatile long pendingSince;
    private volatile int failures;
    private volatile boolean gaveUp;
    private volatile boolean everConnected;
    private Process brainProcess;

    public BrainClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.worker = new Thread(this::ioLoop, "mazeward-brain");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 既定の接続先で開く。
     *
     * <p>ポートは環境変数 {@code MAZEWARD_BRAIN_PORT} で変えられる。
     * 学習を回しながら別の世代の方策を同時に立てたいときに使う。</p>
     */
    public static BrainClient openDefault() {
        String port = System.getenv("MAZEWARD_BRAIN_PORT");
        return new BrainClient("127.0.0.1",
                port == null ? DEFAULT_PORT : Integer.parseInt(port));
    }

    /**
     * 表示名。<b>繋がる前と後を区別する。</b>
     *
     * <p>Python 側は torch の読み込みで数秒かかる。そのあいだ「学習済み方策」と
     * 出したままだと、実際には貪欲ボットが打っているのに
     * 「学習した AI のはずなのに弱い」という誤解を生む。
     * <b>いま実際に手を打っているのが何なのか</b> を、そのまま名前にする。</p>
     */
    @Override
    public String name() {
        if (connected.get()) {
            return "学習済み方策 " + label.get();
        }
        if (gaveUp) {
            return "ブリッジ不通";
        }
        return "ブリッジ接続待ち";
    }

    /** ブリッジが読み込んでいるモデル名（{@code ppo_latest.pt gen20} など）。 */
    public String modelLabel() {
        return label.get();
    }

    /** ブリッジが持っているモデルの一覧。まだ取れていなければ空。 */
    public List<String> models() {
        synchronized (models) {
            return List.copyOf(models);
        }
    }

    /**
     * 使うモデルを選ぶ。
     *
     * <p>まだ繋がっていなければ覚えておいて、繋がった瞬間に送る。
     * <b>「起動が終わるまで選べない」より「選んでおけば反映される」ほうがいい。</b></p>
     */
    public void selectModel(String fileName) {
        desiredModel.set(fileName);
        if (connected.get()) {
            outbox.offer("use " + fileName);
        }
    }

    /** いま選ばれているモデル名（未選択なら null）。 */
    public String selectedModel() {
        return desiredModel.get();
    }

    /**
     * 状態の内訳。ゲーム内の {@code !ai} でそのまま出す。
     *
     * <p>「AI が弱い」ときに知りたいのは、<b>方策が動いていないのか、
     * 方策が弱いのか</b> の区別。それが分かる材料だけを並べる。</p>
     */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("接続先: " + host + ":" + port
                + (connected.get() ? " — 接続中" : gaveUp ? " — 諦めました" : " — 未接続"));
        lines.add("モデル: " + (connected.get() ? label.get() : "（未取得）")
                + (desiredModel.get() == null ? "" : "  / 選択: " + desiredModel.get()));
        lines.add("プロセス: " + (brainProcess == null ? "自動起動していない"
                : brainProcess.isAlive() ? "起動中 (pid " + brainProcess.pid() + ")"
                        : "終了 (exit " + brainProcess.exitValue() + ")"));
        lines.add("失敗回数: " + failures + "/" + MAX_FAILURES
                + (everConnected ? "  （一度は接続できました）" : ""));
        if (!lastError.get().isEmpty()) {
            lines.add("直近のエラー: " + lastError.get());
        }
        if (!models().isEmpty()) {
            lines.add("選べるモデル: " + String.join(", ", models()));
        }
        return lines;
    }

    @Override
    public boolean available() {
        return !gaveUp && connected.get();
    }

    /** まだ 1 度も繋がっていないだけなのか、諦めたのか。表示を分けるために持つ。 */
    public boolean givenUp() {
        return gaveUp;
    }

    @Override
    public boolean pending() {
        if (pendingId.get() == 0) {
            return false;
        }
        if (System.currentTimeMillis() - pendingSince > ANSWER_TIMEOUT_MS) {
            // 返ってこない要求を抱えたままだと、AI が一生動かない
            pendingId.set(0);
            noteFailure("返事が来ない");
            return false;
        }
        return true;
    }

    @Override
    public void request(VersusMatch match, List<Integer> askSeats,
                        MatchSnapshot.SeatStats[] stats) {
        if (!available() || askSeats.isEmpty()) {
            return;
        }
        int id = nextId.incrementAndGet();
        String json = MatchSnapshot.build(match, askSeats, stats);
        if (!outbox.offer("req " + id + " " + json)) {
            // 送信が詰まっている＝前の要求をまだ書けていない。1 回飛ばす
            return;
        }
        pendingId.set(id);
        pendingSince = System.currentTimeMillis();
    }

    @Override
    public List<AiAction.Seated> poll() {
        List<AiAction.Seated> batch = inbox.poll();
        return batch == null ? List.of() : batch;
    }

    @Override
    public void close() {
        running.set(false);
        worker.interrupt();
        closeSocket();
        if (brainProcess != null) {
            brainProcess.destroy();
            brainProcess = null;
        }
    }

    // ================================================================ 通信

    private void ioLoop() {
        long backoff = 500;
        while (running.get()) {
            if (!connected.get()) {
                if (gaveUp || !connect()) {
                    // 自動起動した Python は torch の読み込みで数秒かかる。
                    // 待ち時間を伸ばしすぎると、立ち上がっているのに
                    // 「まだ貪欲ボット」の時間が無駄に延びる
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 2000);
                    continue;
                }
                backoff = 500;
            }
            try {
                String job = outbox.poll(200, TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }
                Socket open = socket.get();
                if (open == null) {
                    continue;
                }
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(open.getOutputStream(), StandardCharsets.UTF_8));
                writer.write(job);
                writer.write('\n');
                writer.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException exception) {
                noteFailure("送信に失敗: " + exception.getMessage());
                closeSocket();
            }
        }
    }

    private boolean connect() {
        try {
            Socket open = new Socket();
            open.setTcpNoDelay(true);
            open.connect(new InetSocketAddress(host, port), 1000);
            socket.set(open);
            connected.set(true);
            everConnected = true;
            failures = 0;
            Thread reader = new Thread(() -> readLoop(open), "mazeward-brain-reader");
            reader.setDaemon(true);
            reader.start();
            // 繋ぎ直したときも、選んでおいたモデルに戻す
            models.clear();
            outbox.offer("models");
            String wanted = desiredModel.get();
            if (wanted != null) {
                outbox.offer("use " + wanted);
            }
            System.out.println("[MAZEWARD] AI ブリッジに接続しました " + host + ":" + port);
            return true;
        } catch (IOException exception) {
            lastError.set("接続できません: " + exception.getMessage());
            return false;
        }
    }

    private void readLoop(Socket open) {
        List<AiAction.Seated> batch = new ArrayList<>();
        int batchId = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(open.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("hello ")) {
                    label.set(line.substring(6).trim());
                    continue;
                }
                if (line.startsWith("model ")) {
                    String name = line.substring(6).trim();
                    synchronized (models) {
                        if (!models.contains(name)) {
                            models.add(name);
                        }
                    }
                    continue;
                }
                if (line.equals("endmodels")) {
                    continue;
                }
                if (line.startsWith("err ")) {
                    // err <要求番号> <理由>。番号は表示に要らないので落とす
                    String reason = line.substring(4).trim();
                    int space = reason.indexOf(' ');
                    if (space > 0 && reason.substring(0, space).chars()
                            .allMatch(Character::isDigit)) {
                        reason = reason.substring(space + 1);
                    }
                    lastError.set(reason);
                    System.out.println("[MAZEWARD] AI ブリッジ: " + reason);
                    pendingId.set(0);
                    continue;
                }
                if (line.startsWith("end ")) {
                    int id = parseId(line.substring(4));
                    if (id == pendingId.get()) {
                        inbox.add(List.copyOf(batch));
                        pendingId.set(0);
                        failures = 0;
                    }
                    batch.clear();
                    batchId = 0;
                    continue;
                }
                if (!line.startsWith("act ")) {
                    continue;
                }
                int space = line.indexOf(' ', 4);
                if (space < 0) {
                    continue;
                }
                int id = parseId(line.substring(4, space));
                if (batchId != 0 && id != batchId) {
                    batch.clear();
                }
                batchId = id;
                AiAction.Seated seated = AiAction.parse(line.substring(space + 1));
                if (seated != null) {
                    batch.add(seated);
                }
            }
        } catch (IOException ignored) {
            // 切断は通常運転（brain を落として繋ぎ直すことがある）
        } finally {
            if (socket.get() == open) {
                closeSocket();
            }
        }
    }

    private static int parseId(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void noteFailure(String reason) {
        failures++;
        lastError.set(reason);
        System.out.println("[MAZEWARD] AI ブリッジの不調 (" + failures + "/" + MAX_FAILURES
                + "): " + reason);
        if (failures >= MAX_FAILURES) {
            gaveUp = true;
            closeSocket();
            System.out.println("[MAZEWARD] AI ブリッジを諦めました。貪欲ボットで続行します");
        }
    }

    private void closeSocket() {
        connected.set(false);
        pendingId.set(0);
        Socket open = socket.getAndSet(null);
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // 閉じられないソケットに用はない
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================ 起動

    /**
     * ブリッジが立っていなければ {@code ai/mc_brain.py} を起動する。
     *
     * <p>手で 2 つのプロセスを立ち上げさせると、片方を忘れたときに
     * 「AI が動かない」としか見えない。<b>押したら遊べる</b> ところまでを
     * ゲーム側の責任にする。すでに立っていれば何もしない（学習中に別途
     * 立てているブリッジをそのまま使う）。</p>
     *
     * @return 起動を試みたなら true
     */
    public boolean ensureBrainProcess() {
        if (connected.get() || brainProcess != null) {
            return false;
        }
        File script = new File("ai/mc_brain.py");
        if (!script.isFile()) {
            // サーバーをプロジェクト直下以外から起動すると相対パスが外れる。
            // 「見つからない」だけだと原因に辿り着けないので、探した場所を出す
            String reason = script.getAbsolutePath() + " が見つかりません";
            lastError.set(reason);
            System.out.println("[MAZEWARD] " + reason + "。貪欲ボットで続行します");
            return false;
        }
        String python = System.getenv("MAZEWARD_PYTHON");
        if (python == null || python.isBlank()) {
            python = "python";
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(python, "mc_brain.py",
                    "--port", String.valueOf(port));
            builder.directory(new File("ai"));
            // **日本語 Windows では必須。** 何も指定しないと子プロセスの標準出力が
            // cp932 になり、Python が日本語のログを 1 行出した瞬間に
            // UnicodeEncodeError で落ちる。落ちても試合は貪欲ボットで続くので、
            // 画面には「AI が弱い」としか出ず原因に辿り着けない（実際に踏んだ）
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            builder.inheritIO();
            brainProcess = builder.start();
            gaveUp = false;
            failures = 0;
            // Windows の python は「ストアを開くだけの偽物」が PATH に居ることがある。
            // その場合すぐ終了するので、繋がらない理由として残す
            // （黙って貪欲ボットになると、python が無いことに気付けない）
            brainProcess.onExit().thenAccept(dead -> {
                String reason = "python が終了しました (exit " + dead.exitValue() + ")";
                if (!connected.get()) {
                    lastError.set(reason + " — python が PATH にあるか、"
                            + "torch が入っているか確認してください");
                }
                System.out.println("[MAZEWARD] AI ブリッジの " + reason);
            });
            System.out.println("[MAZEWARD] AI ブリッジを起動しました（" + python
                    + " ai/mc_brain.py --port " + port + "）");
            return true;
        } catch (IOException exception) {
            String reason = python + " を起動できません: " + exception.getMessage()
                    + "（MAZEWARD_PYTHON で実行ファイルを指定できます）";
            lastError.set(reason);
            System.out.println("[MAZEWARD] AI ブリッジを起動できません: " + reason);
            return false;
        }
    }
}
