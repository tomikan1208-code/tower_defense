package dev.antigravity.mazeward.enemy;

/**
 * その敵を送り込んだ主体。対戦の「送り主」を敵に持たせるためだけの印。
 *
 * <p>敵の側から中身を触ることは無いのでメソッドを持たない。
 * 送り主の型（{@code VersusPlayer}）を直接持たせると
 * enemy → versus の依存が生まれてしまうので、印だけをここに置いている。</p>
 */
public interface EnemySource {
}
