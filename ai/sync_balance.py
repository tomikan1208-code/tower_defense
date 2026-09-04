# -*- coding: utf-8 -*-
"""``balance.py`` と Java 側 enum の数値を突き合わせる。

**コンパイラは使わない。** AI 環境は Minestom をビルドしないので、
Java ソースを**テキストとして解析**して数値を抜き出す。
そのぶん壊れやすいので、抜き出しに失敗した項目は「不一致」ではなく
「読めなかった」として別に報告する（黙って一致扱いにしない）。

使い方::

    python sync_balance.py            # 突き合わせて差分を表示
    python sync_balance.py --json     # 機械可読（ダッシュボードが読む）

一致していれば終了コード 0、不一致があれば 1。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import balance as B  # noqa: E402

JAVA_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "..", "src", "main", "java", "dev", "antigravity", "mazeward")

NUM = r"-?\d+(?:\.\d+)?"
TOL = 1e-6


def _read(*parts: str) -> str:
    path = os.path.join(JAVA_ROOT, *parts)
    with open(path, encoding="utf-8") as f:
        return f.read()


def _strip_comments(text: str) -> str:
    """コメント内の数字を拾ってしまわないよう先に落とす。"""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def _eval_int(expr: str) -> Optional[int]:
    """``20 * 60 * 15`` のような定数式だけを安全に評価する。"""
    expr = expr.strip()
    if not re.fullmatch(r"[\d\s*+\-]+", expr):
        return None
    try:
        return int(eval(expr, {"__builtins__": {}}, {}))  # noqa: S307
    except (SyntaxError, ValueError, TypeError):
        return None


def _constant(text: str, name: str) -> Optional[float]:
    m = re.search(rf"\b{name}\s*=\s*([^;]+);", text)
    if not m:
        return None
    raw = m.group(1).strip()
    value = _eval_int(raw)
    if value is not None:
        return float(value)
    m2 = re.fullmatch(rf"({NUM})[fFdD]?", raw)
    return float(m2.group(1)) if m2 else None


# ════════════════════════════════════════════════════════════════════
# 抽出
# ════════════════════════════════════════════════════════════════════
def _enum_chunks(text: str, valid: Dict) -> Dict[str, str]:
    """enum 定数ごとに、**次の定数の手前まで**を切り出す。

    固定幅の窓（「次の 700 文字」など）で切ると、能力を持たない敵が
    次の敵の ``Trait.sapper(...)`` を拾ってしまう。実際にこれを踏んで
    BRUTE / FLYER / HEALER / BOSS に妨害能力が付いていると誤報した。
    """
    starts: List[Tuple[int, str]] = []
    for m in re.finditer(r"^[ \t]{4}([A-Z][A-Z_0-9]*)\s*\(", text, re.M):
        starts.append((m.start(), m.group(1)))
    out: Dict[str, str] = {}
    for i, (pos, name) in enumerate(starts):
        if name not in valid:
            continue
        end = starts[i + 1][0] if i + 1 < len(starts) else len(text)
        out[name] = text[pos:end]
    return out


def _numbers_after(chunk: str, anchor: str, count: int) -> Optional[List[float]]:
    t = re.search(anchor, chunk)
    if not t:
        return None
    nums = re.findall(rf"(?<![\w.]){NUM}(?=[fFdD]?\s*,)", chunk[t.end():])
    return [float(x) for x in nums[:count]] if len(nums) >= count else None


def parse_towers(text: str) -> Dict[str, List[float]]:
    """``Targeting.X,`` の直後に並ぶ 10 個の数値を拾う。

    順番は TowerKind のコンストラクタどおり:
    baseCost, baseRange, baseCooldown, baseDamage, splashRadius,
    chainTargets, slowFactor, slowTicks, burnDps, burnTicks
    """
    out: Dict[str, List[float]] = {}
    for name, chunk in _enum_chunks(text, B.TOWERS).items():
        nums = _numbers_after(chunk, r"Targeting\.\w+\s*,", 10)
        if nums:
            out[name] = nums
    return out


def parse_enemies(text: str) -> Dict[str, List[float]]:
    """``MoveMode.X,`` の直後の 7 個。"""
    out: Dict[str, List[float]] = {}
    for name, chunk in _enum_chunks(text, B.ENEMIES).items():
        nums = _numbers_after(chunk, r"MoveMode\.\w+\s*,", 7)
        if nums:
            out[name] = nums
    return out


def parse_attackers(text: str) -> Dict[str, list]:
    """``EnemyKind.X,`` の直後の 5 個 ＋ 体の名前。"""
    out: Dict[str, list] = {}
    for name, chunk in _enum_chunks(text, B.ATTACKERS).items():
        body = re.search(r"EnemyKind\.(\w+)\s*,", chunk)
        nums = _numbers_after(chunk, r"EnemyKind\.\w+\s*,", 5)
        if nums and body:
            out[name] = nums + [body.group(1)]
    return out


def parse_traits(text: str) -> Dict[str, Tuple[str, List[float]]]:
    """``Trait.sapper(3.5, 40)`` のようなファクトリ呼び出し。"""
    out: Dict[str, Tuple[str, List[float]]] = {}
    for name, chunk in _enum_chunks(text, B.ENEMIES).items():
        t = re.search(r"Trait\.(\w+)\(([^)]*)\)", chunk)
        if t:
            out[name] = (t.group(1), [float(x) for x in re.findall(NUM, t.group(2))])
    return out


# ════════════════════════════════════════════════════════════════════
# 比較
# ════════════════════════════════════════════════════════════════════
class Report:
    def __init__(self) -> None:
        self.matched = 0
        self.mismatch: List[dict] = []
        self.unreadable: List[str] = []

    def check(self, key: str, java, python) -> None:
        if java is None:
            self.unreadable.append(key)
            return
        if isinstance(java, str) or isinstance(python, str):
            ok = str(java) == str(python)
        else:
            ok = abs(float(java) - float(python)) <= TOL
        if ok:
            self.matched += 1
        else:
            self.mismatch.append({"key": key, "java": java, "python": python})

    def as_dict(self) -> dict:
        return {"matched": self.matched, "mismatch": self.mismatch,
                "unreadable": self.unreadable,
                "ok": not self.mismatch and not self.unreadable}


def run() -> Report:
    rep = Report()

    # --- タワー ---
    tower_text = _strip_comments(_read("tower", "TowerKind.java"))
    towers = parse_towers(tower_text)
    fields = ("base_cost", "base_range", "base_cooldown", "base_damage",
              "splash_radius", "chain_targets", "slow_factor", "slow_ticks",
              "burn_dps", "burn_ticks")
    for name, py in B.TOWERS.items():
        java = towers.get(name)
        if java is None:
            rep.unreadable.append(f"tower.{name}")
            continue
        for i, field in enumerate(fields):
            rep.check(f"tower.{name}.{field}", java[i], getattr(py, field))

    # --- 敵 ---
    enemy_text = _strip_comments(_read("enemy", "EnemyKind.java"))
    enemies = parse_enemies(enemy_text)
    e_fields = ("base_hp", "base_speed", "armor", "gold_reward", "leak_damage",
                "slow_resist", "heal_per_second")
    for name, py in B.ENEMIES.items():
        java = enemies.get(name)
        if java is None:
            rep.unreadable.append(f"enemy.{name}")
            continue
        for i, field in enumerate(e_fields):
            rep.check(f"enemy.{name}.{field}", java[i], getattr(py, field))

    traits = parse_traits(enemy_text)
    trait_args = {
        "sapper": ("disable_radius", "disable_ticks"),
        "blink": ("blink_radius", "blink_cooldown"),
        "ward": ("ward_radius", "ward_reduction"),
        "split": ("split_count",),
        "reaper": ("revives",),
        "fireproof": (),
    }
    for name, py in B.ENEMIES.items():
        found = traits.get(name)
        if found is None:
            if py.trait != B.NO_TRAIT:
                rep.mismatch.append({"key": f"enemy.{name}.trait",
                                     "java": "なし", "python": "あり"})
            continue
        kind, args = found
        for i, field in enumerate(trait_args.get(kind, ())):
            if i < len(args):
                rep.check(f"enemy.{name}.trait.{field}", args[i],
                          getattr(py.trait, field))

    # --- 送りモンスター ---
    atk_text = _strip_comments(_read("versus", "AttackerKind.java"))
    attackers = parse_attackers(atk_text)
    a_fields = ("cost", "income_gain", "stock_cost", "unlock_income", "hp")
    for name, py in B.ATTACKERS.items():
        java = attackers.get(name)
        if java is None:
            rep.unreadable.append(f"attacker.{name}")
            continue
        for i, field in enumerate(a_fields):
            rep.check(f"attacker.{name}.{field}", java[i], getattr(py, field))
        rep.check(f"attacker.{name}.body", java[5], py.body)

    # --- 経済 ---
    player_text = _strip_comments(_read("versus", "VersusPlayer.java"))
    match_text = _strip_comments(_read("versus", "VersusMatch.java"))
    island_text = _strip_comments(_read("versus", "Island.java"))
    eco = B.ECONOMY
    for name, value in (("START_COINS", eco.start_coins),
                        ("START_INCOME", eco.start_income),
                        ("START_LIVES", eco.start_lives),
                        ("MAX_STOCK", eco.max_stock)):
        rep.check(f"economy.{name}", _constant(player_text, name), value)
    for name, value in (("PREP_TICKS", eco.prep_ticks),
                        ("INCOME_INTERVAL", eco.income_interval),
                        ("STOCK_INTERVAL", eco.stock_interval),
                        ("SUDDEN_DEATH_TICKS", eco.sudden_death_ticks),
                        ("CARD_INTERVAL", eco.card_interval),
                        ("HAND_LIMIT", eco.hand_limit),
                        ("START_HAND", eco.start_hand)):
        rep.check(f"economy.{name}", _constant(match_text, name), value)
    rep.check("economy.MAX_TOWERS", _constant(island_text, "MAX_TOWERS"),
              eco.max_towers)

    # --- 戦闘定数 ---
    field_text = _strip_comments(_read("stage", "Battlefield.java"))
    for name, value in (("CHAIN_RADIUS", B.CHAIN_RADIUS),
                        ("PIERCE_WIDTH", B.PIERCE_WIDTH),
                        ("CROWD_RADIUS", B.CROWD_RADIUS),
                        ("HEAL_INTERVAL", B.HEAL_INTERVAL)):
        rep.check(f"combat.{name}", _constant(field_text, name), value)
    trait_text = _strip_comments(_read("enemy", "Trait.java"))
    rep.check("combat.DISABLE_REFRESH_TICKS",
              _constant(trait_text, "DISABLE_REFRESH_TICKS"),
              B.DISABLE_REFRESH_TICKS)

    # --- 式の係数（数値ではなく計算式そのもの） ---
    checks = [
        # シングルは等差のまま、対戦だけ等比 (TowerKind#upgradeCost)
        ("formula.upgrade_cost_single",
         r"baseCost\s*\*\s*\(0\.7\s*\+\s*0\.55", tower_text),
        ("formula.upgrade_cost_versus",
         rf"baseCost\s*\*\s*{B.UPGRADE_COST_SCALE}\s*\*\s*Math\.pow\("
         rf"{B.UPGRADE_COST_GROWTH},\s*level\)", tower_text),
        # 送りの厚みは人数で正規化する
        ("formula.send_power_scale",
         rf"REFERENCE_OPPONENTS\s*=\s*{B.REFERENCE_OPPONENTS}", match_text),
        ("formula.send_power_applied",
         r"kind\.hp\(\)\s*\*\s*match\.sendPowerScale\(\)", island_text),
        # まとめ送り（1 手で複数体）とずらし湧き
        ("formula.max_send_batch",
         rf"MAX_SEND_BATCH\s*=\s*VersusPlayer\.MAX_STOCK", match_text),
        ("formula.send_stagger",
         rf"SEND_STAGGER_TICKS\s*=\s*{B.SEND_STAGGER_TICKS}", match_text),
        ("formula.send_power_exponent",
         rf"SEND_POWER_EXPONENT\s*=\s*{B.SEND_POWER_EXPONENT}", match_text),
        ("formula.versus_max_level",
         rf"VERSUS_MAX_LEVEL\s*=\s*{B.MAX_TOWER_LEVEL}", tower_text),
        ("formula.level_damage",
         rf"Math\.pow\({B.LEVEL_DAMAGE_GROWTH},\s*level\)", tower_text),
        ("formula.level_range", r"baseRange\s*\+\s*0\.6\s*\*\s*level", tower_text),
        ("formula.level_cooldown", r"Math\.pow\(0\.88,\s*level\)", tower_text),
        ("formula.sell_refund", r"investedGold\s*\*\s*0\.6",
         _strip_comments(_read("tower", "TowerInstance.java"))),
        # 撃破報酬は総量を人数で割る（人数によらず一定）
        ("formula.kill_reward",
         rf"KILL_REWARD_TOTAL\s*=\s*{B.KILL_REWARD_TOTAL}", atk_text),
        ("formula.kill_reward_split",
         r"cost\s*\*\s*KILL_REWARD_TOTAL\s*/\s*targets", atk_text),
        ("formula.kill_income",
         r"coinReward\s*\*\s*AttackerKind\.KILL_INCOME_RATIO", player_text),
        # ライフ上限を奪うのは「コアまで通されたとき」だけ
        ("formula.max_life_steal_on_leak",
         r"onEnemyLeaked[\s\S]{0,900}?EnemyKind\.REAPER[\s\S]{0,200}?"
         r"owner\.stealMaxLife\(1\)", island_text),
        # 送りがコアに届いたとき、送り主のライフが戻る（上限まで）
        ("formula.leak_life_reward",
         rf"sender\.gainLife\({B.LEAK_LIFE_REWARD}\)", island_text),
        ("formula.gain_life_cap",
         r"lives\s*=\s*Math\.min\(maxLives,\s*lives\s*\+\s*amount\)", player_text),
        # 送り主本人には戻らない / 脱落者には戻らない
        ("formula.reward_sender_guard",
         r"sender\s*==\s*owner\s*\|\|\s*!sender\.alive\(\)", island_text),
        # 分裂の子も送り主を引き継ぐ
        ("formula.split_inherits_source",
         r"child\.source\(parent\.source\(\)\)", field_text),
    ]
    for key, pattern, text in checks:
        rep.check(key, "一致" if re.search(pattern, text) else None, "一致")
    return rep


def main() -> int:
    ap = argparse.ArgumentParser(description="balance.py と Java enum の突き合わせ")
    ap.add_argument("--json", action="store_true", help="機械可読で出力")
    args = ap.parse_args()

    rep = run()
    if args.json:
        print(json.dumps(rep.as_dict(), ensure_ascii=False, indent=2))
        return 0 if rep.as_dict()["ok"] else 1

    print(f"一致: {rep.matched} 項目")
    if rep.unreadable:
        print(f"\n読み取れなかった項目 ({len(rep.unreadable)}):")
        for key in rep.unreadable:
            print(f"  ? {key}")
        print("  → Java 側の書き方が変わった可能性があります。"
              "sync_balance.py の正規表現を直してください")
    if rep.mismatch:
        print(f"\n不一致 ({len(rep.mismatch)}):")
        for item in rep.mismatch:
            print(f"  x {item['key']}: Java={item['java']}  Python={item['python']}")
        print("\nbalance.py が正でない場合は Java 側に合わせてください。")
        return 1
    if not rep.unreadable:
        print("balance.py は Java の定義と完全に一致しています。")
    return 0 if not rep.mismatch and not rep.unreadable else 1


if __name__ == "__main__":
    sys.exit(main())
