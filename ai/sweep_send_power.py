# -*- coding: utf-8 -*-
"""``SEND_POWER_EXPONENT`` を振って、人数ごとの守り／送りの釣り合いを測る。

なぜ専用スクリプトなのか
------------------------
``balance_analyzer`` の対称性チェックは既定 8 試合・12 分で、
**サンプルが少なすぎて 100% と 43% が振れる**。指数を決めるには足りないので、
試合数と長さを増やして振り直すためだけのもの。

進捗が見えること
----------------
1 回の測定に数分かかるので、``ai/_sweep_progress.md`` へ
**測定が 1 つ終わるたびに追記**する。走らせたまま次のコマンドで眺められる::

    tail -f ai/_sweep_progress.md
"""

from __future__ import annotations

import importlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import balance as B  # noqa: E402

BASE = os.path.dirname(os.path.abspath(__file__))
PROGRESS = os.path.join(BASE, "_sweep_progress.md")
RESULT = os.path.join(BASE, "_sweep_result.json")

#: 振る指数。0 = 補正なし、1 = 浴びる耐力の総量をぴったり揃える
EXPONENTS = (0.3, 0.5, 0.7)
#: 人数
PLAYERS = (2, 4, 8)
#: 1 条件あたりの試合数と 1 試合の上限（分）
GAMES = 24
CAP_MINUTES = 20


def _fmt(seconds: float) -> str:
    m, s = divmod(int(max(0, seconds)), 60)
    return f"{m}分{s:02d}秒"


def main() -> int:
    total = len(EXPONENTS) * len(PLAYERS)
    done = 0
    started = time.time()
    rows = []

    with open(PROGRESS, "w", encoding="utf-8") as f:
        f.write("# SEND_POWER_EXPONENT の掃引\n\n")
        f.write(f"1 条件 = {GAMES} 試合 x 最大 {CAP_MINUTES} 分。"
                f"全 {total} 条件。\n\n")
        f.write("守り勝率が 40〜60% なら釣り合っている。\n\n")
        f.write("| 指数 | 人数 | 守り勝率 | 送り勝率 | 引分 | 決着率 | 平均分 | 経過 |\n")
        f.write("|---|---|---|---|---|---|---|---|\n")
        f.flush()

    for p in EXPONENTS:
        B.SEND_POWER_EXPONENT = p
        # 環境は import 時に balance を読むので、指数を変えたら読み直す
        for name in [m for m in sys.modules if m.startswith("mazeward_env")]:
            del sys.modules[name]
        analyzer = importlib.import_module("balance_analyzer")
        importlib.reload(analyzer)
        analyzer.B.SEND_POWER_EXPONENT = p

        for players in PLAYERS:
            t0 = time.time()
            sym = analyzer.simulate_symmetry_one(players, games=GAMES,
                                                 cap_minutes=CAP_MINUTES)
            done += 1
            elapsed = time.time() - started
            eta = elapsed / done * (total - done)
            row = {"p": p, "players": players, **sym}
            rows.append(row)
            line = (f"| {p} | {players} | {sym['defense_win']:.0%} | "
                    f"{sym['offense_win']:.0%} | {sym['tie']:.0%} | "
                    f"{sym['decided']:.0%} | {sym['minutes']:.1f} | "
                    f"{_fmt(time.time() - t0)} |\n")
            with open(PROGRESS, "a", encoding="utf-8") as f:
                f.write(line)
                f.write(f"<!-- {done}/{total} 完了 / 残り約 {_fmt(eta)} -->\n")
            with open(RESULT, "w", encoding="utf-8") as f:
                json.dump(rows, f, ensure_ascii=False, indent=1)
            print(f"[{done}/{total}] p={p} {players}人 "
                  f"守り {sym['defense_win']:.0%} / 送り {sym['offense_win']:.0%} "
                  f"（残り約 {_fmt(eta)}）", flush=True)

    # まとめ: 人数をまたいで「守り勝率が 50% からいちばん離れていない」指数
    best, best_score = None, 1e9
    for p in EXPONENTS:
        picks = [r for r in rows if r["p"] == p]
        played = [r["defense_win"] + r["offense_win"] for r in picks]
        share = [(r["defense_win"] / q if q > 0 else 0.5)
                 for r, q in zip(picks, played)]
        score = max(abs(x - 0.5) for x in share)
        if score < best_score:
            best, best_score = p, score
    with open(PROGRESS, "a", encoding="utf-8") as f:
        f.write(f"\n**結論: SEND_POWER_EXPONENT = {best}**"
                f"（守り勝率の 50% からのずれが最大 {best_score:.0%}）\n")
    print(f"ALLDONE best={best} (ずれ最大 {best_score:.0%})", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
