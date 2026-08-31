package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.enemy.EnemySource;
import dev.antigravity.mazeward.run.Deck;
import dev.antigravity.mazeward.run.Wallet;
import dev.antigravity.mazeward.tower.TowerKind;
import java.util.EnumSet;
import java.util.Set;
import net.minestom.server.entity.Player;

/**
 * 対戦の参加者 1 人ぶんの状態。
 *
 * <p>通貨は <b>コイン 1 本</b>。シングルのようにステージ内とステージ間で分けない。
 * 対戦は最初から最後まで 1 つの局面なので、財布を分ける理由がないうえに、
 * 「守りに使うか、送って収入を伸ばすか」という一番大事な judgement を
 * 2 つの財布に割ると薄まってしまう。</p>
 */
public final class VersusPlayer implements EnemySource {

    /** 開始時のコイン。 */
    public static final int START_COINS = 100;

    /** 開始時のインカム。収入間隔ごとにこの数だけコインが入る（間隔は人数で変わる）。 */
    public static final int START_INCOME = 5;

    public static final int START_LIVES = 20;

    /** 送りモンスターのストック上限。毎秒 1 回復する。 */
    public static final int MAX_STOCK = 30;

    /** 送り履歴の減衰係数（10 秒 / 30 秒の時定数を 1 tick ぶんにしたもの）。 */
    private static final double DECAY_10 = Math.exp(-1.0 / 200.0);
    private static final double DECAY_30 = Math.exp(-1.0 / 600.0);

    private final String name;
    private final Player player;
    private final boolean bot;
    private final Deck deck = Deck.starter();
    /** 対戦では全タワーを最初から使える。解放要素はインカムによる送りの解禁だけに絞る。 */
    private final Set<TowerKind> unlocked = EnumSet.allOf(TowerKind.class);

    private final Wallet wallet = new Wallet() {
        @Override
        public int balance() {
            return coins;
        }

        @Override
        public boolean spend(int amount) {
            if (coins < amount) {
                return false;
            }
            coins -= amount;
            return true;
        }

        @Override
        public void gain(int amount) {
            coins = Math.max(0, coins + amount);
        }
    };

    private int coins = START_COINS;
    private int income = START_INCOME;
    private int lives = START_LIVES;
    /** 終焉騎に奪われると減る。回復手段はない。 */
    private int maxLives = START_LIVES;
    private int stock = MAX_STOCK;
    private Island island;

    /**
     * 撃破報酬のインカム端数。
     * 「コイン報酬の 10%」がインカムなので、1 に届くまで貯めてから加算する。
     */
    private double incomeProgress;

    /**
     * 送りの履歴。<b>AI の観測に渡す唯一の「相手の懐事情」</b>。
     *
     * <p>コインとインカムは本来見えない情報だが、送りはチャットに流れるので
     * 全員が見ている。学習環境（{@code ai/mazeward_env}）も同じ 5 つを持ち、
     * ここから相手の経済を推定させている。<b>直近ほど重い</b> ように
     * 10 秒 / 30 秒の時定数で減衰させる。</p>
     */
    private double sendDecay10;
    private double sendDecay30;
    private int sendsTotal;
    private int lastSendCost;
    private int sentIncome;

    public VersusPlayer(String name, Player player, boolean bot) {
        this.name = name;
        this.player = player;
        this.bot = bot;
    }

    public String name() {
        return name;
    }

    /** 人間なら Player、ボットなら null。 */
    public Player player() {
        return player;
    }

    public boolean bot() {
        return bot;
    }

    public Deck deck() {
        return deck;
    }

    public Wallet wallet() {
        return wallet;
    }

    public Set<TowerKind> unlocked() {
        return unlocked;
    }

    public boolean isUnlocked(TowerKind kind) {
        return unlocked.contains(kind);
    }

    public Island island() {
        return island;
    }

    void setIsland(Island island) {
        this.island = island;
    }

    // ---------------------------------------------------------------- 資源

    public int coins() {
        return coins;
    }

    public int income() {
        return income;
    }

    public int lives() {
        return lives;
    }

    public int maxLives() {
        return maxLives;
    }

    public int stock() {
        return stock;
    }

    public boolean alive() {
        return lives > 0;
    }

    /** 10 秒ごとの収入。 */
    public void applyIncomeTick() {
        wallet.gain(income);
    }

    /** ストックは毎秒 1 回復する。 */
    public void regenerateStock() {
        stock = Math.min(MAX_STOCK, stock + 1);
    }

    public boolean canSend(AttackerKind kind) {
        return coins >= kind.cost() && stock >= kind.stockCost() && income >= kind.unlockIncome();
    }

    /** 送りを確定させる。コインとストックを払い、インカムを増やす。 */
    public boolean paySend(AttackerKind kind) {
        if (!canSend(kind)) {
            return false;
        }
        coins -= kind.cost();
        stock -= kind.stockCost();
        income += kind.incomeGain();
        sendDecay10 += 1.0;
        sendDecay30 += 1.0;
        sendsTotal++;
        lastSendCost = kind.cost();
        sentIncome += kind.incomeGain();
        return true;
    }

    /**
     * 送り履歴の減衰。ゲーム 1 tick ごとに呼ぶ。
     *
     * <p>時定数は学習環境と同じ（10 秒 = 200 tick / 30 秒 = 600 tick）。
     * ここがずれると、AI から見た「相手がいま攻めているか」の尺度だけが
     * 実ゲームと学習でずれる。</p>
     */
    public void decaySendHistory() {
        sendDecay10 *= DECAY_10;
        sendDecay30 *= DECAY_30;
    }

    public double sendDecay10() {
        return sendDecay10;
    }

    public double sendDecay30() {
        return sendDecay30;
    }

    public int sendsTotal() {
        return sendsTotal;
    }

    public int lastSendCost() {
        return lastSendCost;
    }

    /** 送りで積み上げたインカム。開始インカムを足すと現在値の推定になる。 */
    public int sentIncome() {
        return sentIncome;
    }

    /**
     * 敵を 1 体倒した。コインは既に入っているので、ここではインカムの端数だけ進める。
     * インカムはコイン報酬の 10%（＝送りコストの 2%）。
     */
    public void onKillReward(int coinReward) {
        incomeProgress += coinReward * 0.10;
        while (incomeProgress >= 1.0) {
            incomeProgress -= 1.0;
            income++;
        }
    }

    /** ライフを削られた。0 になったら脱落。 */
    public void loseLife(int amount) {
        lives = Math.max(0, lives - amount);
    }

    /**
     * 送ったモンスターが相手のコアに届いたときの見返り。上限は超えない。
     *
     * <p>送りは「相手を削る」だけの行為だった。通れば自分も 1 戻るようにすると、
     * <b>削られたぶんを攻めで取り返す</b> 道ができる。上限を超えないので、
     * 守りが崩れていない側がひたすら太ることにはならない。</p>
     *
     * @return 実際に増えたなら true。上限に張り付いていれば false
     */
    public boolean gainLife(int amount) {
        if (amount <= 0 || lives >= maxLives) {
            return false;
        }
        lives = Math.min(maxLives, lives + amount);
        return true;
    }

    /**
     * ライフの上限を恒久的に奪われる（終焉騎）。
     *
     * <p>普通の漏れと違って取り返しがつかない。
     * 「倒しさえすれば損はない」を崩すための唯一の手段なので、
     * 現在ライフも一緒に減らす。</p>
     */
    public void stealMaxLife(int amount) {
        maxLives = Math.max(0, maxLives - amount);
        lives = Math.min(lives, maxLives);
    }
}
