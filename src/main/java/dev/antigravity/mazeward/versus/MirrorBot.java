package dev.antigravity.mazeward.versus;

import dev.antigravity.mazeward.core.Rot;
import dev.antigravity.mazeward.core.Shape;
import dev.antigravity.mazeward.core.Vec2i;
import dev.antigravity.mazeward.run.BlockCard;
import dev.antigravity.mazeward.tower.TowerKind;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * プレイヤーの操作をそのまま真似する対戦相手。
 *
 * <p>AI を書く代わりに <b>人間の行動を録って、少し遅らせて自分の島で再生する</b>。
 * 地形は全員同じなので、同じ座標に同じものを置けばそのまま成立する。</p>
 *
 * <p>この方式には実利がある。相手が自分と同じ強さになるので、
 * <b>「自分の組み方がどれくらい強いのか」がそのまま勝敗に出る</b>。
 * 適当な AI と戦うより、バランスの歪みがはっきり見える。
 * 1 人で対戦モードを回せるようにもなる。</p>
 *
 * <p>遅延はボットごとにずらしてある。全員が完全に同時に同じ手を打つと、
 * 送りが一斉に飛んできて事故になるため。</p>
 */
public final class MirrorBot {

    /** 記録された 1 手。 */
    public sealed interface Action {
        record PlaceCard(Shape shape, Vec2i origin, Rot rot) implements Action {
        }

        record PlaceTower(TowerKind kind, Vec2i origin, Rot rot) implements Action {
        }

        record UpgradeTower(Vec2i cell, TowerKind.Spec spec) implements Action {
        }

        record SellTower(Vec2i cell) implements Action {
        }

        record Send(AttackerKind kind) implements Action {
        }
    }

    private record Pending(int atTick, Action action) {
    }

    private final VersusPlayer owner;
    private final int delayTicks;
    private final Deque<Pending> queue = new ArrayDeque<>();

    public MirrorBot(VersusPlayer owner, int delayTicks) {
        this.owner = owner;
        this.delayTicks = delayTicks;
    }

    public VersusPlayer owner() {
        return owner;
    }

    /** 人間が 1 手打った。遅延させて自分のキューに積む。 */
    public void record(Action action, int currentTick) {
        queue.add(new Pending(currentTick + delayTicks, action));
    }

    /** 時間が来た手を実行する。資源が足りないなど失敗したものは黙って捨てる。 */
    public void tick(int currentTick, VersusMatch match) {
        if (!owner.alive() || owner.island() == null) {
            queue.clear();
            return;
        }
        while (!queue.isEmpty() && queue.peek().atTick() <= currentTick) {
            apply(queue.poll().action(), match);
        }
    }

    private void apply(Action action, VersusMatch match) {
        Island island = owner.island();
        switch (action) {
            case Action.PlaceCard place -> {
                int index = findInHand(place.shape());
                if (index >= 0) {
                    island.placeCard(index, place.origin(), place.rot());
                }
            }
            case Action.PlaceTower place ->
                    island.placeTower(place.kind(), place.origin(), place.rot());
            case Action.UpgradeTower upgrade ->
                    island.upgradeTower(upgrade.cell(), upgrade.spec());
            case Action.SellTower sell -> island.sellTower(sell.cell());
            case Action.Send send -> match.send(owner, send.kind());
        }
    }

    /**
     * 手札から同じ形のカードを探す。
     * 山札の引き順は人間と違うので、同じ形が無いこともある。その手は諦める。
     */
    private int findInHand(Shape shape) {
        List<BlockCard> hand = owner.deck().hand();
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).shape() == shape) {
                return i;
            }
        }
        return -1;
    }
}
