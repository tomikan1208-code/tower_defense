package dev.antigravity.mazeward.versus;

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
public final class VersusPlayer {

    /** 開始時のコイン。 */
    public static final int START_COINS = 100;

    /** 開始時のインカム。10 秒ごとにこの数だけコインが入る。 */
    public static final int START_INCOME = 5;

    public static final int START_LIVES = 20;

    /** 送りモンスターのストック上限。毎秒 1 回復する。 */
    public static final int MAX_STOCK = 30;

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
    private int stock = MAX_STOCK;
    private Island island;

    /**
     * 撃破報酬のインカム端数。
     * 「コイン報酬の 10%」がインカムなので、1 に届くまで貯めてから加算する。
     */
    private double incomeProgress;

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
        return true;
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
}
