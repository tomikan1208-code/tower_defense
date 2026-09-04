# -*- coding: utf-8 -*-
"""塔の構成だけを変えたボットを総当たりで戦わせる。

    python tower_bench.py [--games 16] [--minutes 15] [--seed 0]

**何のためか。** 学習済み方策が弓塔しか建てないとき、原因は 2 つありうる。

1. **ゲームバランスの問題** — 24 基上限では弓を並べるのが実際に最適
2. **学習・観測・報酬の問題** — 他の塔のほうが強いのに見つけられていない

報酬や観測をいじる前に、**方策を介さずに**これを分ける。
:mod:`mazeward_env.bots_heuristic` の 3 種は迷路の作り方・強化・送りが
まったく同じで、**塔の選び方だけ**が違う。だから勝率の差はそのまま
「その塔構成が強いかどうか」になる。

- ``arrow_spam``   弓塔だけ 24 基（いまの方策がやっていること）
- ``big_tower``    2x2 の土台を意図して作り、砲塔・雷塔・監視塔を建てる
- ``support_mix``  弓を主軸に氷塔と呪詛塔を混ぜる

読み方::

    big_tower が arrow_spam に勝てない   → バランスの問題。方策は正しかった
    big_tower が明確に勝つのに方策は弓   → 学習・観測・報酬の問題
    ほぼ五分                             → どちらでもよく、方策を責められない
"""

from __future__ import annotations

import argparse
import itertools
import os
import sys
from typing import Dict, List

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import balance as B                                        # noqa: E402
from mazeward_env.bots_heuristic import empty_action, make_bot  # noqa: E402
from mazeward_env.env import VersusEnv                      # noqa: E402
from mazeward_env.rules import EnvConfig                    # noqa: E402

MATCHUPS = ("arrow_spam", "arrow_pads", "big_tower", "support_mix",
            "pierce_mix", "splash_mix")

#: 防衛試験で全員に配る固定インカムの既定値。**同じ金で同じ敵を受ける**ための装置
FIXED_INCOME = 80.0
#: 波の間隔（秒）と 1 波の体数の既定値。
#:
#: **1 島の同時敵数は 48 で頭打ち** (:data:`balance.MAX_ENEMIES`)。ここを
#: 超える流量にすると湧きが捨てられ、捨てられたぶんは漏れないので
#: **守りが強く見える**。実測では 5 秒 4 体で 4 割が捨てられ、
#: 漏れ率が半分近くに過小評価された。上限に触れない流量にしておくこと。
#: 弱すぎると全員が 0.6% しか漏らさず差も出ないので、その間を取る
WAVE_PERIOD = 5
WAVE_SIZE = 2


def play(name_a: str, name_b: str, games: int, minutes: float,
         seed: int) -> List[dict]:
    """A を席 0、B を席 1 に据えて ``games`` 試合。1 試合 = 1 環境。"""
    cfg = EnvConfig(num_envs=games, num_players=2, players_choices=(2,),
                    board_size=21, attacker_limit=len(B.ATTACKER_ORDER),
                    max_ticks=int(minutes * 60 * 20), seed=seed)
    env = VersusEnv(cfg)
    bot_a = make_bot(name_a, np.random.default_rng(seed))
    bot_b = make_bot(name_b, np.random.default_rng(seed + 1))
    seats_a = np.array([e * env.seats for e in range(env.n_envs)])
    seats_b = seats_a + 1

    obs = env.reset()
    done_envs: Dict[int, dict] = {}
    for _ in range(int(cfg.max_ticks // cfg.decision_ticks) + 2):
        action = empty_action(env.n)
        bot_a.act(env, obs, seats_a, action)
        bot_b.act(env, obs, seats_b, action)
        obs, _, _, infos = env.step(action)
        for info in infos:
            # 環境は勝手に次の試合を始めるので、最初の 1 試合だけ数える
            done_envs.setdefault(int(info["env"]), info)
        if len(done_envs) >= env.n_envs:
            break
    return list(done_envs.values())


def summarize(name_a: str, name_b: str, infos: List[dict]) -> dict:
    """A から見た成績。引き分け（誰も落ちずに時間切れ）は 0.5 勝で数える。"""
    wins = draws = 0
    towers = {name_a: np.zeros(len(B.TOWER_ORDER)), name_b: np.zeros(len(B.TOWER_ORDER))}
    counts = {name_a: [], name_b: []}
    for info in infos:
        rank = info["ranks"]
        if not info["decided"]:
            draws += 1
        elif rank[0] < rank[1]:
            wins += 1
        rates = info["tower_type_rates"]
        # tower_type_rates は 2 席ぶんの合算なので、内訳は別に数える
        for i, key in enumerate((name_a, name_b)):
            counts[key].append(info["towers"])
        for k, kind in enumerate(B.TOWER_ORDER):
            towers[name_a][k] += rates.get(kind, 0.0)
    n = max(len(infos), 1)
    return {
        "games": len(infos),
        "win_rate": (wins + 0.5 * draws) / n,
        "wins": wins, "draws": draws, "losses": n - wins - draws,
        "towers": float(np.mean(counts[name_a])) if counts[name_a] else 0.0,
        "path": float(np.mean([i["path_length"] for i in infos])),
        "idle": float(np.mean([i.get("idle_rate", 0.0) for i in infos])),
    }


def defense_run(name: str, games: int, minutes: float, seed: int,
                income: float = FIXED_INCOME, wave_size: int = WAVE_SIZE,
                wave_period: int = WAVE_PERIOD) -> dict:
    """**同じ金・同じ敵**で守らせて、どれだけ漏らすかだけを見る。

    対戦形式だと勝敗が序盤の建築速度と経済の雪だるまで決まってしまい、
    塔の構成の良し悪しが埋もれる（実測: 決着は最初の 4 分でつき、
    そのあと 16 分は双方 1 体も漏らさない膠着になった）。
    ここでは経済を固定してその交絡を消す。

    - 送りは封じる（インカムを自分で伸ばせないようにする）
    - インカムは全員 :data:`FIXED_INCOME` に固定する
    - 一定間隔で **同じ構成の波**を全員の島に湧かせる
    - ライフを 10000 にして**死なせない**。ライフ 0 になると環境が次の試合を
      始めてしまい、塔も漏れ数も丸ごとリセットされる（最初これで測り損ねた）
    """
    cfg = EnvConfig(num_envs=games, num_players=2, players_choices=(2,),
                    board_size=21, attacker_limit=len(B.ATTACKER_ORDER),
                    max_ticks=int(minutes * 60 * 20) + 20 * 60, seed=seed)
    env = VersusEnv(cfg)
    bot = make_bot(name, np.random.default_rng(seed))
    # 送りを封じる。インカムを自分で伸ばされると「同じ金」でなくなる
    bot.no_send = True

    seats = np.array([e * env.seats for e in range(env.n_envs)])
    foes = seats + 1
    bd = env.boards
    obs = env.reset()
    bd.max_lives[:] = 10000
    bd.lives[:] = 10000
    steps = int(minutes * 60 * 20 // cfg.decision_ticks)
    spawned = 0
    for t in range(steps):
        action = empty_action(env.n)
        bot.act(env, obs, seats, action)
        obs, _, _, _ = env.step(action)
        bd.income[seats] = income
        bd.lives[:] = 10000                 # 死なせない。漏れた回数だけを数える
        if t % wave_period == 0:
            spawned += wave_size
            # 時間とともに重い敵へ。全ボットに同じ順で同じ数を送る
            # 前半で全種類を出し切り、後半はいちばん重いものを撃ち続ける
            kind = min(len(B.ATTACKER_ORDER) - 1,
                       t // max(steps // (2 * len(B.ATTACKER_ORDER)), 1))
            targets = np.repeat(seats, wave_size)
            bd.spawn(targets, np.full(len(targets), kind, dtype=np.int64),
                     sender=np.repeat(foes, wave_size))

    kinds = {}
    for s in seats:
        c = int(bd.tw_count[s])
        for k in bd.tw_kind[s, :c]:
            if k >= 0:
                kinds[B.TOWER_ORDER[int(k)]] = kinds.get(B.TOWER_ORDER[int(k)], 0) + 1
    total = max(sum(kinds.values()), 1)
    return {
        "leaks": float(bd.stat_leaks[seats].mean()),
        "leak_rate": float(bd.stat_leaks[seats].mean()) / max(spawned, 1),
        "towers": float(bd.tw_count[seats].mean()),
        "level": float(np.mean([bd.tw_level[s, :int(bd.tw_count[s])].mean()
                                for s in seats if bd.tw_count[s] > 0] or [0.0])),
        "threat": float(env.path_threat[seats].mean()),
        "path": float(env.ground_len[seats].mean()),
        "mix": {k: v / total for k, v in sorted(kinds.items(), key=lambda kv: -kv[1])},
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="塔構成の総当たり")
    ap.add_argument("--games", type=int, default=16, help="1 組・1 席順あたりの試合数")
    ap.add_argument("--minutes", type=float, default=15.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--mode", choices=("versus", "defense", "both"),
                    default="both")
    ap.add_argument("--income", type=float, nargs="*", default=[30.0, 80.0],
                    help="防衛試験で配る固定インカム。複数指定で金欠と潤沢を比べる")
    ap.add_argument("--wave-size", type=int, default=WAVE_SIZE)
    args = ap.parse_args()

    if args.mode in ("defense", "both"):
        for income in args.income:
            print("=" * 74)
            print(f"防衛試験  インカム {income:.0f} 固定 / {args.wave_size} 体を "
                  f"{WAVE_PERIOD} 秒ごと / {args.minutes:.0f} 分 / {args.games} 島平均")
            print("経済の差を消して「その塔構成でどれだけ漏らすか」だけを見る")
            print("=" * 74)
            print(f"{'':13s}{'漏れ':>7s}{'漏れ率':>8s}{'塔':>6s}{'平均Lv':>7s}"
                  f"{'累積被ダメ':>11s}{'経路':>7s}  構成")
            for name in MATCHUPS:
                r = defense_run(name, args.games, args.minutes, args.seed,
                                income=income, wave_size=args.wave_size)
                mix = " ".join(f"{k}{v:.0%}" for k, v in list(r["mix"].items())[:4])
                print(f"{name:13s}{r['leaks']:7.1f}{r['leak_rate']:8.1%}{r['towers']:6.1f}"
                      f"{r['level']:7.2f}{r['threat']:11.0f}{r['path']:7.1f}  {mix}")
            print()
        if args.mode == "defense":
            return 0

    print("=" * 68)
    print(f"塔構成ベンチ  {args.games} 試合 x 2 席順 / 1 組  ({args.minutes:.0f} 分マッチ)")
    print("迷路・強化・送りは全ボット共通。**塔の選び方だけ**が違う")
    print("=" * 68)

    table: Dict[tuple, dict] = {}
    for a, b in itertools.combinations(MATCHUPS, 2):
        rows = []
        for i, (x, y) in enumerate(((a, b), (b, a))):
            infos = play(x, y, args.games, args.minutes, args.seed + 100 * i)
            rows.append((x, y, summarize(x, y, infos)))
            s = rows[-1][2]
            print(f"  {x:12s} (席0) vs {y:12s} (席1)  "
                  f"{s['wins']}勝 {s['losses']}敗 {s['draws']}分  "
                  f"勝率 {s['win_rate']:.0%}  塔 {s['towers']:.1f} 基  経路 {s['path']:.1f}")
        # 席順を平均して、先手後手の差を消す
        rate = (rows[0][2]["win_rate"] + (1.0 - rows[1][2]["win_rate"])) / 2.0
        table[(a, b)] = rate
        print(f"  → {a} の {b} に対する勝率（席順を平均）: {rate:.0%}\n")

    print("=" * 68)
    print("まとめ（行の勝率）")
    print(f"{'':14s}" + "".join(f"{m:>14s}" for m in MATCHUPS))
    for a in MATCHUPS:
        cells = []
        for b in MATCHUPS:
            if a == b:
                cells.append("-")
            elif (a, b) in table:
                cells.append(f"{table[(a, b)]:.0%}")
            else:
                cells.append(f"{1.0 - table[(b, a)]:.0%}")
        print(f"{a:14s}" + "".join(f"{c:>14s}" for c in cells))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
