# -*- coding: utf-8 -*-
"""バランス診断。**数字を測って、具体的な推奨値まで出す。**

「なんとなく強い / 弱い」ではなく、次の 4 つを機械的に測る。

1. **経済の健全性** … 強化コストがインカムに対して何秒待ちか、
   送りコストに対する撃破報酬の回収率
2. **ユニットの釣り合い** … 弓塔を 1.0 とした実効 DPS/コスト比、
   送りの効用/コスト比。どの解禁帯でも「常に最適な 1 種類」に
   収束していないか（多様性チェック）
3. **対称性** … 守り特化ボット vs 送り特化ボットの勝率が 40〜60% か
4. **人数別** … 送りは全員に同時に飛ぶので、**人数が増えるほど
   防御側の撃破報酬の総量が増える**。人数ごとに世界全体のリターンを出す

推奨値は「(例) 砲塔 ダメージ 26 → 30 推奨」の形で出すが、**自動では適用しない**。
確定するのは人間で、GUI のボタン操作にしてある。

使い方::

    python balance_analyzer.py                 # 人が読むレポート
    python balance_analyzer.py --json          # ダッシュボード用
    python balance_analyzer.py --no-sim        # ボット対戦を省いて高速に
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance as B  # noqa: E402

# ---- 判定のしきい値（ここも調整対象なので明示的に定数化する） -------
#: 強化 1 段に必要な「インカム待ち秒数」の上限。超えたら強化が重すぎる
UPGRADE_WAIT_LIMIT = 60.0
#: 弓塔を 1.0 としたコスト効率の許容幅
EFFICIENCY_HIGH = 1.30
EFFICIENCY_LOW = 0.60
#: 撃破報酬の回収率（送りコストに対する割合 x 人数）の上限。
#: これを超えると「送るほど相手が儲かる」
WORLD_RETURN_LIMIT = 3.0
#: 対称性チェックの許容範囲
SYMMETRY_LOW, SYMMETRY_HIGH = 0.40, 0.60
#: 代表敵（実効 DPS を測る相手）。装甲込みで測らないと手数型が過大評価される
REFERENCE_ENEMIES = ("GRUNT", "BRUTE", "AEGIS")
#: キルゾーンに同時にいる敵の想定数。範囲・連鎖・貫通の価値はここで決まる。
#: 1 体想定だと範囲攻撃が不当に低く出て、実戦と食い違う
CROWD_ASSUMPTION = 3
#: **削ることを仕事にしていない塔。** DPS/コストの土俵に乗せない。
#: これを混ぜると「送還塔の DPS が 0 だから 60 万倍にしろ」のような
#: 無意味な推奨が出る（実際に出した）
NON_DAMAGE_STYLES = ("BANISH", "CURSE", "SUPPORT")


def clamp_suggestion(current: float, suggested: float,
                     lo: float = 0.4, hi: float = 3.0,
                     integer: bool = False):
    """推奨値を現在値の ``lo``〜``hi`` 倍に収める。

    比が 0 に近いと割り算が発散して「26 → 3000000」のような値が出る。
    バランス調整は少しずつ動かすものなので、1 回の提案幅は必ず縛る。
    """
    if current <= 0:
        return None
    value = float(np.clip(suggested, current * lo, current * hi))
    if integer:
        return int(round(value))
    return round(value, 2)


@dataclass
class Finding:
    """1 件の指摘。``recommend`` があれば具体的な変更候補まで出す。"""

    level: str            # info / warn / error
    area: str
    message: str
    recommend: Optional[dict] = None   # {"path", "current", "suggested"}

    def as_dict(self) -> dict:
        return {"level": self.level, "area": self.area, "message": self.message,
                "recommend": self.recommend}


@dataclass
class Report:
    findings: List[Finding] = field(default_factory=list)
    tables: Dict[str, list] = field(default_factory=dict)
    summary: Dict[str, object] = field(default_factory=dict)

    def add(self, level: str, area: str, message: str,
            recommend: Optional[dict] = None) -> None:
        self.findings.append(Finding(level, area, message, recommend))

    def as_dict(self) -> dict:
        return {"findings": [f.as_dict() for f in self.findings],
                "tables": self.tables, "summary": self.summary}


# ════════════════════════════════════════════════════════════════════
# 経済
# ════════════════════════════════════════════════════════════════════
def income_curve(bal: B.Balance, players: int, seconds: int = 900,
                 reinvest: bool = True) -> Dict[str, list]:
    """送りへの再投資を仮定した ``インカム(t)`` と ``累計コイン(t)``。

    「守りに使うか、送って収入を伸ばすか」を数字で比べるための土台。
    再投資しない場合との差がそのまま「送りの利回り」になる。
    """
    eco = bal.economy
    interval = eco.income_interval_for(players) / B.TICKS_PER_SECOND
    coins = float(eco.start_coins)
    income = float(eco.start_income)
    stock = float(eco.max_stock)
    times, incomes, totals = [], [], []
    earned = coins

    for t in range(seconds):
        if t > 0 and t % int(round(interval)) == 0:
            coins += income
            earned += income
        stock = min(eco.max_stock, stock + 1)
        if reinvest and t >= eco.prep_ticks / B.TICKS_PER_SECOND:
            # 解禁済みのうち「インカム増 / コスト」がいちばん良いものを送る
            best, best_ratio = None, 0.0
            for key, a in bal.attackers.items():
                if a.income_gain <= 0 or income < a.unlock_income:
                    continue
                if coins < a.cost or stock < a.stock_cost:
                    continue
                ratio = a.income_gain / a.cost
                if ratio > best_ratio:
                    best, best_ratio = a, ratio
            if best is not None:
                coins -= best.cost
                stock -= best.stock_cost
                income += best.income_gain
        if t % 10 == 0:
            times.append(t)
            incomes.append(round(income, 2))
            totals.append(round(earned, 1))
    return {"t": times, "income": incomes, "cumulative": totals}


def analyse_economy(bal: B.Balance, rep: Report) -> None:
    eco = bal.economy
    rows = []
    waits = []
    for key in B.TOWER_ORDER:
        d = bal.towers[key]
        for level in range(B.MAX_TOWER_LEVEL):
            cost = bal.upgrade_cost(key, level)
            # 「そのとき現実的なインカム」で割る。開幕インカムで割ると
            # 終盤の強化がすべて「重すぎ」と誤判定される
            income = eco.start_income * (1 + level * 3)
            interval = eco.income_interval_for(4) / B.TICKS_PER_SECOND
            wait = cost / max(income, 1) * interval
            waits.append(wait)
            rows.append({"tower": d.name_jp, "level": level + 1, "cost": cost,
                         "wait_sec": round(wait, 1)})
    rep.tables["upgrade_wait"] = rows
    median = float(np.median(waits))
    rep.summary["upgrade_wait_median"] = round(median, 1)
    if median > UPGRADE_WAIT_LIMIT:
        worst = max(rows, key=lambda r: r["wait_sec"])
        target = int(round(worst["cost"] * UPGRADE_WAIT_LIMIT / max(median, 1e-6)))
        rep.add("warn", "経済",
                f"強化 1 段の待ち時間の中央値が {median:.0f} 秒（目安 {UPGRADE_WAIT_LIMIT:.0f} 秒）。"
                "強化が重すぎて、コインが送りに偏ります",
                {"path": "balance.UPGRADE_COST_GROWTH",
                 "current": B.UPGRADE_COST_GROWTH,
                 "suggested": round(B.UPGRADE_COST_GROWTH * UPGRADE_WAIT_LIMIT / median, 3)})
    else:
        rep.add("info", "経済",
                f"強化 1 段の待ち時間の中央値は {median:.0f} 秒（目安 {UPGRADE_WAIT_LIMIT:.0f} 秒以内）")

    # 撃破報酬の回収率。**総量は人数によらず一定**なので、
    # 代表として 4 人戦（相手 3 人）の 1 体あたりで並べる
    rows = []
    for key in B.ATTACKER_ORDER:
        a = bal.attackers[key]
        reward = bal.kill_reward(key, 3)
        rows.append({"attacker": a.name_jp, "cost": a.cost, "reward": reward,
                     "recover": round(reward / a.cost, 3),
                     "income_ratio": round(a.income_ratio, 4)})
    rep.tables["kill_recovery"] = rows


# ════════════════════════════════════════════════════════════════════
# ユニットの釣り合い
# ════════════════════════════════════════════════════════════════════
def effective_dps(bal: B.Balance, key: str, level: int, spec: Optional[int],
                  enemy: str) -> float:
    """代表敵に対する実効 DPS。**装甲と当たる体数を込みで測る。**

    素の ``damage * 20 / cooldown`` で比べると、手数の多い塔が装甲持ちに
    まったく通らない現実を無視して過大評価される。範囲・連鎖・貫通は
    巻き込む体数を掛ける（減衰も反映する）。
    """
    st = bal.tower_stats(key, level, spec)
    d = bal.towers[key]
    e = bal.enemies[enemy]

    if d.style == "AURA":
        # 燃焼は装甲を無視する継続ダメージ。射程内の全員に乗る
        return st.burn_dps * (1.0 - e.trait.burn_resist) * CROWD_ASSUMPTION
    if d.style in ("CURSE", "SUPPORT"):
        return 0.0

    per_hit = max(B.MIN_DAMAGE_AFTER_ARMOR, st.damage - e.armor)
    per_hit = max(B.MIN_DAMAGE_APPLIED, per_hit)
    crowd = CROWD_ASSUMPTION
    hits = 1.0
    if d.style == "SPLASH":
        hits = 1.0 + (crowd - 1) * B.SPLASH_FALLOFF
    elif d.style == "CHAIN":
        n = min(st.chain_targets, crowd)
        hits = sum(B.CHAIN_FALLOFF ** i for i in range(max(1, n)))
    elif d.style == "PIERCE":
        n = min(st.chain_targets, crowd)
        hits = 1.0 + max(0, n - 1) * B.PIERCE_FALLOFF
    dps = per_hit * hits * B.TICKS_PER_SECOND / max(st.cooldown, 1)
    if st.burn_dps > 0:
        dps += st.burn_dps * (1.0 - e.trait.burn_resist)
    return dps


def total_tower_cost(bal: B.Balance, key: str, level: int) -> float:
    """コイン ＋ **土台に使うカードの機会費用**。

    2x2 の塔は 2x2 の壁を「わざわざ作る」必要がある。カードは 30 秒に 1 枚
    しか来ないので、土台に使ったぶんは経路を伸ばすのに使えない。
    1 セルあたりの機会費用を、カード 1 枚 (平均 3.4 セル) = 30 秒ぶんの
    インカムとして金額換算する。
    """
    coins = bal.towers[key].base_cost
    for lv in range(level):
        coins += bal.upgrade_cost(key, lv)
    cells = len(B.SHAPE_CELLS[bal.towers[key].shape])
    avg_cells = float(np.mean([len(c) for c in B.SHAPE_CELLS.values()]))
    card_value = bal.economy.start_income * 6.0     # 30 秒ぶんのインカム相当
    return coins + card_value * cells / avg_cells


def analyse_towers(bal: B.Balance, rep: Report) -> None:
    rows = []
    ratios: Dict[str, float] = {}
    for key in B.TOWER_ORDER:
        d = bal.towers[key]
        dps0 = float(np.mean([effective_dps(bal, key, 0, None, e)
                              for e in REFERENCE_ENEMIES]))
        best_spec = max(range(len(d.specs)),
                        key=lambda s: np.mean([effective_dps(bal, key, 3, s, e)
                                               for e in REFERENCE_ENEMIES]))
        dps3 = float(np.mean([effective_dps(bal, key, 3, best_spec, e)
                              for e in REFERENCE_ENEMIES]))
        cost0 = total_tower_cost(bal, key, 0)
        cost3 = total_tower_cost(bal, key, 3)
        eff = dps3 / cost3 if cost3 else 0.0
        ratios[key] = eff
        rows.append({"tower": d.name_jp, "style": d.style,
                     "dps_lv0": round(dps0, 1), "dps_lv3": round(dps3, 1),
                     "cost_lv0": round(cost0, 1), "cost_lv3": round(cost3, 1),
                     "eff": round(eff, 4),
                     "spec": d.specs[best_spec].name_jp if d.specs else ""})

    base = ratios.get("ARROW", 0.0) or 1e-9
    for row, key in zip(rows, B.TOWER_ORDER):
        row["vs_arrow"] = round(ratios[key] / base, 2)
    rep.tables["tower_efficiency"] = rows

    utility = []
    for row, key in zip(rows, B.TOWER_ORDER):
        d = bal.towers[key]
        if d.style in NON_DAMAGE_STYLES:
            # 送還・呪詛・支援は削るのが仕事ではない。DPS/コストの土俵に
            # 乗せると必ず「弱すぎ」と出るが、それは診断として意味がない
            utility.append(d.name_jp)
            row["vs_arrow"] = None
            continue
        r = row["vs_arrow"]
        # 燃焼型はダメージ欄が 0 なので、いじる先を burn_dps にする
        field = "base_damage" if d.base_damage > 0 else "burn_dps"
        current = d.base_damage if d.base_damage > 0 else d.burn_dps
        if r > EFFICIENCY_HIGH:
            target = clamp_suggestion(current, current * EFFICIENCY_HIGH / r)
            rep.add("warn", "タワー",
                    f"{d.name_jp} のコスト効率が弓塔の {r:.2f} 倍"
                    f"（上限 {EFFICIENCY_HIGH}）。強すぎます",
                    {"path": f"TOWERS['{key}'].{field}",
                     "current": current, "suggested": target})
        elif r < EFFICIENCY_LOW:
            target = clamp_suggestion(current, current * EFFICIENCY_LOW / max(r, 1e-6))
            rep.add("warn", "タワー",
                    f"{d.name_jp} のコスト効率が弓塔の {r:.2f} 倍"
                    f"（下限 {EFFICIENCY_LOW}）。弱すぎます",
                    {"path": f"TOWERS['{key}'].{field}",
                     "current": current, "suggested": target})
    if utility:
        rep.add("info", "タワー",
                "削るのが仕事ではない塔（" + " / ".join(utility)
                + "）は DPS/コストの比較から外しています。"
                "価値は「送り返す」「被ダメを増やす」「周りを強化する」ことなので、"
                "対称性チェック（実戦）でしか測れません")


def analyse_attackers(bal: B.Balance, rep: Report) -> None:
    """送りの効用/コスト。**解禁帯ごとに最適が 1 つに収束していないか**を見る。"""
    rows = []
    for key in B.ATTACKER_ORDER:
        a = bal.attackers[key]
        e = bal.enemies[a.body]
        speed = e.base_speed * B.TICKS_PER_SECOND
        leak = a.hp * speed                      # 硬さ x 速さ = 抜ける見込み
        rows.append({
            "attacker": a.name_jp, "cost": a.cost, "unlock": a.unlock_income,
            "hp_per_cost": round(a.hp / a.cost, 2),
            "speed_per_cost": round(speed / a.cost, 4),
            "leak_per_cost": round(leak / a.cost, 2),
            "income_per_cost": round(a.income_gain / a.cost, 4),
            "stock": a.stock_cost,
        })
    rep.tables["attacker_value"] = rows

    # 多様性: 解禁帯ごとに「効用/コスト」が突出した 1 種だけになっていないか。
    # **インカムを生まない終焉騎・災厄は帯から外す。** あれは「もう伸ばさずに
    # 削り切る」ための最終手段で、インカム梯子の一部ではない。混ぜると
    # HP 6600 の災厄が必ず突出して、意味のない推奨が出る（実際に出した）
    ladder = [(k, r) for k, r in zip(B.ATTACKER_ORDER, rows)
              if bal.attackers[k].income_gain > 0]
    bands = [(0, 60), (60, 150), (150, 10 ** 6)]
    dominated = []
    for low, high in bands:
        band = [(k, r) for k, r in ladder
                if low <= bal.attackers[k].unlock_income < high]
        if len(band) < 2:
            continue
        band.sort(key=lambda kr: -kr[1]["leak_per_cost"])
        best, second = band[0], band[1]
        if best[1]["leak_per_cost"] > second[1]["leak_per_cost"] * 1.6:
            dominated.append((low, high, best, second))

    for low, high, best, second in dominated:
        a = bal.attackers[best[0]]
        other = bal.attackers[second[0]]
        target = clamp_suggestion(
            a.cost, a.cost * best[1]["leak_per_cost"]
            / (second[1]["leak_per_cost"] * 1.4), integer=True)
        rep.add("warn", "送り",
                f"解禁インカム {low}〜{high} の帯で {a.name_jp} だけが突出しています"
                f"（効用/コスト {best[1]['leak_per_cost']:.1f} vs "
                f"{other.name_jp} {second[1]['leak_per_cost']:.1f}）。"
                "この帯では常に同じものを送るだけになります",
                {"path": f"ATTACKERS['{best[0]}'].cost",
                 "current": a.cost, "suggested": target})
    if not dominated:
        rep.add("info", "送り",
                "どの解禁帯でも送りの選択肢が 1 つに収束していません"
                "（インカムを生まない終焉騎・災厄は最終手段なので帯から除外）")

    # 終焉騎・災厄は「インカムを捨てて削り切る」ための択なので、
    # 梯子ではなく **リークあたりのコスト** で見る
    for key in ("REAPER", "CALAMITY"):
        if key not in bal.attackers:
            continue
        a = bal.attackers[key]
        cheapest = min((x for x in bal.attackers.values() if x.income_gain > 0),
                       key=lambda x: x.cost / max(x.hp, 1))
        ratio = (a.cost / a.hp) / max(cheapest.cost / cheapest.hp, 1e-9)
        if ratio > 3.0:
            rep.add("warn", "送り",
                    f"{a.name_jp} は HP あたりのコストが通常の送りの {ratio:.1f} 倍。"
                    "最終手段としても高すぎて、実戦で選ばれません",
                    {"path": f"ATTACKERS['{key}'].cost", "current": a.cost,
                     "suggested": clamp_suggestion(a.cost, a.cost / ratio * 2.0,
                                                   integer=True)})


# ════════════════════════════════════════════════════════════════════
# 人数別
# ════════════════════════════════════════════════════════════════════
def analyse_players(bal: B.Balance, rep: Report) -> None:
    """**送りは生存者全員に同時に飛ぶ。** 人数が増えるほど、1 回の送りに対して
    世界全体で発生する撃破報酬の総量が増える。ここが効きすぎると
    「送るほど相手を太らせる」ことになり、守り一辺倒が最適になる。
    """
    rows = []
    for players in range(B.MIN_PLAYERS, B.MAX_PLAYERS + 1):
        interval = bal.economy.income_interval_for(players) / B.TICKS_PER_SECOND
        # 代表として、いちばん安い送りで測る
        cheapest = min(bal.attackers.values(), key=lambda a: a.cost)
        targets = players - 1
        cheapest_key = next(k for k, v in bal.attackers.items() if v is cheapest)
        world_reward = bal.kill_reward(cheapest_key, targets) * targets
        ratio = world_reward / cheapest.cost
        curve = income_curve(bal, players, seconds=600)
        rows.append({
            "players": players,
            "income_interval_sec": round(interval, 1),
            "targets_per_send": targets,
            "world_reward": world_reward,
            "world_return": round(ratio, 2),
            "income_at_10min": curve["income"][-1],
            "coins_at_10min": curve["cumulative"][-1],
        })
    rep.tables["by_players"] = rows

    worst = max(rows, key=lambda r: r["world_return"])
    if worst["world_return"] > WORLD_RETURN_LIMIT:
        current = B.KILL_REWARD_TOTAL
        suggested = round(current * WORLD_RETURN_LIMIT / worst["world_return"], 3)
        rep.add("warn", "人数",
                f"{worst['players']} 人戦では、送り 1 回に対して世界全体の撃破報酬が "
                f"コストの {worst['world_return']:.1f} 倍になります"
                f"（上限 {WORLD_RETURN_LIMIT}）。送るほど相手が太ります",
                {"path": "balance.KILL_REWARD_TOTAL", "current": current,
                 "suggested": suggested})
    else:
        rep.add("info", "人数",
                f"世界全体の撃破報酬は最大 {worst['world_return']:.1f} 倍"
                f"（{worst['players']} 人戦）で、上限 {WORLD_RETURN_LIMIT} 以内です")


# ════════════════════════════════════════════════════════════════════
# 対称性（実際に戦わせる）
# ════════════════════════════════════════════════════════════════════
def simulate_symmetry(games: int = 8, cap_minutes: int = 12
                      ) -> Dict[int, Dict[str, float]]:
    """守り特化 vs 送り特化を人数ごとに戦わせる。

    ここだけは机上計算ではなく**実際に回す**。経路の形・射程の重なり・
    妨害や瞬移の効き方は、式では出てこないため。
    """
    return {players: simulate_symmetry_one(players, games, cap_minutes)
            for players in (2, 4, 8)}


def simulate_symmetry_one(players: int, games: int = 8, cap_minutes: int = 12
                          ) -> Dict[str, float]:
    """人数 1 つぶんの対称性チェック。

    人数ごとに分けてあるのは、掃引（``sweep_send_power.py``）が
    **1 条件終わるたびに進捗を出せる**ようにするため。1 条件が数分かかるので、
    3 人数ぶんまとめて返す作りだと途中経過が一切見えない。
    """
    from mazeward_env.bots_heuristic import empty_action, make_bot
    from mazeward_env.env import VersusEnv
    from mazeward_env.rules import EnvConfig

    out: Dict[int, Dict[str, float]] = {}
    for _once in (0,):
        rng = np.random.default_rng(20240101 + players)
        env = VersusEnv(EnvConfig(num_envs=games, players_choices=(players,),
                                  board_size=21, max_ticks=20 * 60 * cap_minutes,
                                  randomize=0.0, seed=100 + players))
        obs = env.observe()
        seat = np.arange(env.n) % env.seats
        defs = np.flatnonzero((seat % 2 == 0) & env.active)
        offs = np.flatnonzero((seat % 2 == 1) & env.active)
        bot_d = make_bot("greedy_defense", rng)
        bot_o = make_bot("income_push", rng)

        results: List[dict] = []
        steps = 0
        limit = env.cfg.max_ticks // env.cfg.decision_ticks + 5
        while len(results) < games and steps < limit:
            action = empty_action(env.n)
            bot_d.act(env, obs, defs, action)
            bot_o.act(env, obs, offs, action)
            obs, _, _, infos = env.step(action)
            steps += 1
            results.extend(infos)
        results = results[:games]

        def_wins = off_wins = ties = 0
        for info in results:
            rank = info["ranks"]
            winners = np.flatnonzero(rank == 0)
            if len(winners) > 1:
                ties += 1
            elif winners[0] % 2 == 0:
                def_wins += 1
            else:
                off_wins += 1
        n = max(len(results), 1)
        out[players] = {
            "defense_win": def_wins / n,
            "offense_win": off_wins / n,
            "tie": ties / n,
            "decided": float(np.mean([i["decided"] for i in results])) if results else 0.0,
            "leaks": float(np.mean([i["leaks"] for i in results])) if results else 0.0,
            "breakthroughs": float(np.mean([i["breakthroughs"] for i in results])) if results else 0.0,
            "life_regained": float(np.mean([i["life_regained"] for i in results])) if results else 0.0,
            "minutes": float(np.mean([i["ticks"] for i in results])) / 1200 if results else 0.0,
        }
    return out[players]


def analyse_symmetry(sym: Dict[int, Dict[str, float]], bal: B.Balance,
                     rep: Report) -> None:
    rows = []
    for players, r in sym.items():
        rows.append({"players": players,
                     "defense_win": round(r["defense_win"], 3),
                     "offense_win": round(r["offense_win"], 3),
                     "tie": round(r["tie"], 3),
                     "decided": round(r["decided"], 3),
                     "leaks_per_player": round(r["leaks"], 1),
                     "breakthroughs": round(r["breakthroughs"], 1),
                     "life_regained": round(r["life_regained"], 1),
                     "avg_minutes": round(r["minutes"], 1)})
    rep.tables["symmetry"] = rows

    for players, r in sym.items():
        # 引き分けを除いた勝負がついた試合での守り側の勝率
        played = r["defense_win"] + r["offense_win"]
        share = r["defense_win"] / played if played > 0 else 0.5
        if played == 0:
            # **「脱落者が出たか」と「勝者が 1 人に絞れたか」は別物。**
            # FFA では脱落は起きていても、時間切れ時に複数人がライフ同数で
            # 並ぶと勝者が決まらない。この 2 つを混ぜると、
            # 「試合が終わらない」と誤診して撃破報酬を削る方向へ誘導してしまう
            if r["decided"] >= 0.9:
                rep.add("warn", "対称性",
                        f"{players} 人戦: 脱落者は出ている（決着率 {r['decided']:.0%}）が、"
                        f"時間切れの時点で複数人がライフ同数で並び、勝者が 1 人に"
                        f"絞れませんでした（平均 {r['minutes']:.1f} 分）。"
                        "サドンデスを早めるか、試合時間を延ばす必要があります",
                        {"path": "ECONOMY.sudden_death_ticks",
                         "current": B.ECONOMY.sudden_death_ticks,
                         "suggested": int(B.ECONOMY.sudden_death_ticks * 0.6)})
            else:
                rep.add("error", "対称性",
                        f"{players} 人戦: 脱落者が 1 人も出ませんでした"
                        f"（決着率 {r['decided']:.0%}）。"
                        "守りが送りを完全に上回っていて、試合が終わりません",
                        {"path": "balance.KILL_REWARD_TOTAL",
                         "current": B.KILL_REWARD_TOTAL,
                         "suggested": round(B.KILL_REWARD_TOTAL * 0.5, 3)})
        elif share > SYMMETRY_HIGH:
            rep.add("warn", "対称性",
                    f"{players} 人戦: 守り特化の勝率 {share:.0%}（目安 40〜60%）。"
                    "送りが割に合っていません",
                    {"path": "balance.KILL_REWARD_TOTAL",
                     "current": B.KILL_REWARD_TOTAL,
                     "suggested": round(B.KILL_REWARD_TOTAL * 0.6, 3)})
        elif share < SYMMETRY_LOW:
            rep.add("warn", "対称性",
                    f"{players} 人戦: 送り特化の勝率 {1 - share:.0%}（目安 40〜60%）。"
                    "送りスパムが強すぎます",
                    {"path": "ATTACKERS['WHELP'].cost",
                     "current": bal.attackers["WHELP"].cost,
                     "suggested": int(round(bal.attackers["WHELP"].cost * 1.4))})
        else:
            rep.add("info", "対称性",
                    f"{players} 人戦: 守り {share:.0%} / 送り {1 - share:.0%} で釣り合っています")

        # 送りが通ると送り主のライフが 1 戻るルールが効いているか。
        # 一度も突破できていないなら、そのルールは存在しないのと同じ
        if r["breakthroughs"] < 1.0 and players > 2:
            rep.add("warn", "見返り",
                    f"{players} 人戦: 送りが相手のコアに届いた回数が 1 人あたり "
                    f"{r['breakthroughs']:.1f} 回。「通れば自分のライフが 1 戻る」"
                    "ルールがほぼ発動していません",
                    {"path": "ATTACKERS['WHELP'].cost",
                     "current": bal.attackers["WHELP"].cost,
                     "suggested": max(5, int(round(bal.attackers["WHELP"].cost * 0.7)))})
        elif r["breakthroughs"] >= 1.0 and r["life_regained"] >= 1.0:
            rep.add("info", "見返り",
                    f"{players} 人戦: 1 人あたり {r['breakthroughs']:.1f} 回突破し、"
                    f"{r['life_regained']:.1f} ライフを取り戻しています"
                    "（攻めが立て直しになっている）")
        elif r["breakthroughs"] >= 1.0:
            # 突破はしているのに回復が 0 = 送り主が満タンのまま。
            # ルールは動いているが、この人数では **意味を持っていない**
            rep.add("info", "見返り",
                    f"{players} 人戦: 1 人あたり {r['breakthroughs']:.1f} 回突破していますが、"
                    "取り戻したライフは 0 です。送り主が削られていないので"
                    "「通れば 1 戻る」が発動していません"
                    "（この人数ではそもそも漏れが起きていない）")

        if r["decided"] < 0.9:
            rep.add("warn", "決着",
                    f"{players} 人戦の決着率が {r['decided']:.0%}（目安 90% 以上）。"
                    f"平均 {r['minutes']:.1f} 分。長期戦が畳めていません",
                    {"path": "ECONOMY.sudden_death_ticks",
                     "current": B.ECONOMY.sudden_death_ticks,
                     "suggested": int(B.ECONOMY.sudden_death_ticks * 0.6)})


# ════════════════════════════════════════════════════════════════════
# 迷路
# ════════════════════════════════════════════════════════════════════
def analyse_maze(rep: Report, samples: int = 6) -> None:
    """``平均経路長 / 盤面サイズ`` と ``同一タワー射程の平均通過回数``。"""
    from mazeward_env.bots_heuristic import empty_action, make_bot
    from mazeward_env.env import VersusEnv
    from mazeward_env.rules import EnvConfig

    rng = np.random.default_rng(4242)
    env = VersusEnv(EnvConfig(num_envs=samples, players_choices=(2,),
                              board_size=21, max_ticks=20 * 60 * 8, seed=77))
    obs = env.observe()
    bot = make_bot("greedy_defense", rng)
    boards = np.flatnonzero(env.active)
    start_len = float(env.ground_len[boards].mean())
    for _ in range(400):
        action = empty_action(env.n)
        bot.act(env, obs, boards, action)
        obs, _, _, _ = env.step(action)

    size = env.size
    length = float(env.ground_len[boards].mean())
    passes = float(env.tower_passes[boards].mean())
    towers = float(env.boards.tw_count[boards].mean())
    per_tower = passes / max(towers, 1.0)
    speed = B.ENEMIES["GRUNT"].base_speed * B.TICKS_PER_SECOND
    rep.tables["maze"] = [{
        "board_size": size,
        "initial_path": round(start_len, 1),
        "built_path": round(length, 1),
        "path_per_size": round(length / size, 2),
        "towers": round(towers, 1),
        "tower_passes": round(passes, 1),
        "passes_per_tower": round(per_tower, 2),
        "seconds_to_core": round(length / speed, 1),
    }]
    if per_tower < 1.2:
        rep.add("warn", "迷路",
                f"1 基あたりの射程通過回数が {per_tower:.2f} 回。"
                "蛇行させても同じ塔の前を何度も通らないので、"
                "「経路の形」より「長さ」だけのゲームになっています")
    else:
        rep.add("info", "迷路",
                f"1 基あたりの射程通過回数は {per_tower:.2f} 回。蛇行が効いています")


# ════════════════════════════════════════════════════════════════════
# 再学習の要否
# ════════════════════════════════════════════════════════════════════
def retrain_advice(rep: Report) -> None:
    """数値を変えたあと、学習をやり直す必要があるかの目安。

    観測は比率で渡してあり、ドメインランダム化も掛けてあるので、
    **純粋なスケール変更なら学習を継続してよい**。行動空間の形が変わる
    （塔や送りの種類が増減する・盤面の最大サイズが変わる）と、
    ネットワークの入出力そのものが変わるので再学習が必要になる。
    """
    rep.summary["retrain"] = {
        "continue": [
            "コスト・HP・ダメージ・クールダウン・射程などの数値変更",
            "インカム増加量・収入間隔・カード間隔の変更",
            "サドンデスやライフの変更",
        ],
        "retrain": [
            "塔・送りモンスターの種類を増減する（行動空間の次元が変わる）",
            "盤面の最大サイズ MAX_BOARD を変える（観測の形が変わる）",
            "手札上限・タワー上限を変える（行動空間の次元が変わる）",
            "攻撃方式や狙い方の種類を増やす",
        ],
        "note": "観測は比率で渡してあるので、数値スケールの変更は方策が"
                "そのまま読み替えられます。ドメインランダム化 ±20% の"
                "範囲内なら学習済みの方策で概ね動きます。",
    }


# ════════════════════════════════════════════════════════════════════
def analyse(simulate: bool = True) -> Report:
    bal = B.default_balance()
    rep = Report()
    rep.summary["fingerprint"] = bal.fingerprint()
    analyse_economy(bal, rep)
    analyse_towers(bal, rep)
    analyse_attackers(bal, rep)
    analyse_players(bal, rep)
    rep.tables["income_curve_4p"] = [income_curve(bal, 4)]
    if simulate:
        analyse_maze(rep)
        analyse_symmetry(simulate_symmetry(), bal, rep)
    retrain_advice(rep)
    rep.summary["counts"] = {
        "error": sum(1 for f in rep.findings if f.level == "error"),
        "warn": sum(1 for f in rep.findings if f.level == "warn"),
        "info": sum(1 for f in rep.findings if f.level == "info"),
        "recommendations": sum(1 for f in rep.findings if f.recommend),
    }
    return rep


def print_report(rep: Report) -> None:
    d = rep.as_dict()
    print("=" * 72)
    print("MAZEWARD VERSUS バランス診断")
    print(f"balance.py 指紋: {rep.summary['fingerprint']}")
    print("=" * 72)

    def table(title: str, key: str, cols: List[Tuple[str, str]]) -> None:
        rows = d["tables"].get(key)
        if not rows:
            return
        print(f"\n■ {title}")
        head = "  " + "".join(f"{label:>14}" for _, label in cols)
        print(head)
        print("  " + "-" * (len(head) - 2))
        for row in rows:
            print("  " + "".join(f"{str(row.get(k, '')):>14}" for k, _ in cols))

    table("タワーのコスト効率（弓塔 = 1.00）", "tower_efficiency",
          [("tower", "塔"), ("style", "方式"), ("dps_lv3", "実効DPS"),
           ("cost_lv3", "総コスト"), ("vs_arrow", "対弓塔比")])
    table("送りモンスターの価値", "attacker_value",
          [("attacker", "送り"), ("cost", "コスト"), ("unlock", "解禁"),
           ("hp_per_cost", "HP/コスト"), ("leak_per_cost", "効用/コスト"),
           ("income_per_cost", "インカム/コスト")])
    table("人数別のバランス", "by_players",
          [("players", "人数"), ("income_interval_sec", "収入間隔s"),
           ("targets_per_send", "送り先"), ("world_return", "世界リターン"),
           ("income_at_10min", "10分後収入")])
    table("守り vs 送り（実戦）", "symmetry",
          [("players", "人数"), ("defense_win", "守り勝率"),
           ("offense_win", "送り勝率"), ("tie", "引分"), ("decided", "決着率"),
           ("breakthroughs", "突破/人"), ("life_regained", "回復/人"),
           ("avg_minutes", "平均分")])
    table("迷路", "maze",
          [("board_size", "盤面"), ("built_path", "経路長"),
           ("path_per_size", "経路/盤面"), ("towers", "塔数"),
           ("passes_per_tower", "通過/塔")])

    print("\n" + "=" * 72)
    print("診断結果")
    print("=" * 72)
    icons = {"error": "[重大]", "warn": "[注意]", "info": "[良好]"}
    for f in d["findings"]:
        print(f"\n{icons.get(f['level'], '')} ({f['area']}) {f['message']}")
        if f["recommend"]:
            r = f["recommend"]
            print(f"    → 推奨: {r['path']}  {r['current']} → {r['suggested']}")

    c = rep.summary["counts"]
    print(f"\n重大 {c['error']} / 注意 {c['warn']} / 良好 {c['info']}"
          f"  具体的な推奨値 {c['recommendations']} 件")
    print("\n※ 推奨値は自動適用しません。GUI の「バランス」タブから確定してください。")


def main() -> int:
    ap = argparse.ArgumentParser(description="MAZEWARD VERSUS バランス診断")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--no-sim", action="store_true",
                    help="ボット対戦を省いて机上計算だけにする")
    ap.add_argument("--out", help="JSON の書き出し先")
    args = ap.parse_args()

    rep = analyse(simulate=not args.no_sim)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(rep.as_dict(), f, ensure_ascii=False, indent=2)
    if args.json:
        print(json.dumps(rep.as_dict(), ensure_ascii=False, indent=2))
    else:
        print_report(rep)
    return 0


if __name__ == "__main__":
    sys.exit(main())
