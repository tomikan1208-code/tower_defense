package dev.antigravity.mazeward.run;

/**
 * 資金の出し入れ。
 *
 * <p>戦場側は「いくら持っていて、払えるか」だけを知っていればよく、
 * それがシングルのゴールドなのか対戦のコインなのかを知る必要がない。</p>
 */
public interface Wallet {

    int balance();

    /** 足りなければ何もせず false。 */
    boolean spend(int amount);

    void gain(int amount);
}
