package dev.antigravity.mazeward.stage;

import dev.antigravity.mazeward.run.Roadmap;
import dev.antigravity.mazeward.world.Palette;

/**
 * 生成済みステージのパラメータ。
 *
 * @param layer      ロードマップの層（1 始まり）
 * @param nodeKind   ノード種別
 * @param title      表示名
 * @param theme      地域テーマ
 * @param waveCount  ウェーブ数
 * @param difficulty 敵 HP の倍率
 * @param seed       生成に使ったシード（再現用）
 */
public record StageConfig(
        int layer,
        Roadmap.NodeKind nodeKind,
        String title,
        Palette.Theme theme,
        int waveCount,
        double difficulty,
        long seed) {
}
