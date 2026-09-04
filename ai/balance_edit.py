# -*- coding: utf-8 -*-
"""バランス数値を GUI から編集し、``balance.py`` と Java enum の**両方**へ書き戻す。

なぜ両方書くのか
----------------
``balance.py`` は AI 学習のシミュレータ、Java enum は実際のゲーム。
片方だけ変えると **学習した方策が実ゲームに通用しなくなる** うえ、
:mod:`sync_balance` の突き合わせが不一致だらけになって、
「意図した差分」と「直し忘れ」の区別がつかなくなる。**常に両方を同時に動かす。**

どうやって場所を突き止めるか
----------------------------
:mod:`sync_balance` と**同じ当て方**をする。あちらが「読めている」場所は
こちらでも書ける、という関係にしておくと、Java 側の書き方が変わったときに
片方だけ黙って壊れることがない。

ただし読み取りと違って、書き込みは **元のファイルの正確な位置** が要る。
``sync_balance`` はコメントを削ってから解析するので位置がずれる。
ここでは :func:`_blank_comments` で **長さを保ったまま**コメントを空白に潰し、
オフセットを元ファイルと一致させたまま解析する。

安全策
------
1. 触る全ファイルの内容を先に控える
2. 数値リテラルの区間だけを、同じ種別（int/float）の数値で置き換える
3. 書いたあとに ``ast.parse`` → ``balance`` の再読み込み → ``sync_balance``
   の突き合わせまで通す。**編集前より不一致が増えていたら全部差し戻す**

対象外（意図的）
----------------
- ``Effect``（送還・呪詛・支援）の中身。Java は ``Effect.banish(2)`` の
  ファクトリ、Python は ``Effect(banish_targets=2)`` と形が違ううえ、
  ``sync_balance`` が見ていないので**書いても検算できない**
- 敵やタワーの見た目（Material / EntityType / 色 / 音）と説明文
- 形状・属性・攻撃様式・狙い方（``Shapes`` / ``Element`` / ``AttackStyle`` /
  ``Targeting`` / ``MoveMode``）。数値ではなく種別なので、変えると
  戦闘処理の分岐そのものが変わる
"""

from __future__ import annotations

import ast
import importlib
import os
import re
import sys
from typing import Dict, List, Optional, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import balance as B          # noqa: E402
import sync_balance          # noqa: E402

BALANCE_PY = os.path.join(HERE, "balance.py")
JAVA_ROOT = os.path.normpath(os.path.join(
    HERE, "..", "src", "main", "java", "dev", "antigravity", "mazeward"))

#: 数値リテラル（Java の f/d 接尾辞まで含める）
_NUM = re.compile(r"^(-?\d+(?:\.\d+)?)([fFdD]?)$")


# ════════════════════════════════════════════════════════════════════
# テキスト操作の下ごしらえ
# ════════════════════════════════════════════════════════════════════
def _blank_comments(text: str) -> str:
    """コメントと文字列の中身を**同じ長さの空白**に潰す。

    改行だけは残す。行頭アンカー（``^    NAME(``）と、元ファイルと同じ
    オフセットの両方が要るため。文字列も潰すのは、説明文に書かれた
    ``Trait.sapper(3.5, 40)`` のような**例示**を拾わないようにするため。
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif text[i] in "\"'":
            quote = text[i]
            j = i + 1
            while j < n and text[j] != quote:
                if text[j] == "\\":
                    j += 1
                j += 1
            j = min(j + 1, n)
            for k in range(i + 1, j - 1):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def _match_paren(text: str, open_idx: int) -> int:
    """``text[open_idx]`` の ``(`` に対応する ``)`` の位置を返す。"""
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("括弧が閉じていません")


def _split_args(blank: str, open_idx: int) -> List[Tuple[int, int]]:
    """引数リストを**トップレベルのカンマ**で割り、各引数の区間を返す。

    入れ子の ``Model.of(...)`` や ``new Spec(...)`` の中のカンマでは割らない。
    ``blank`` は :func:`_blank_comments` を通したテキスト（文字列の中身が
    空白なので、引数の文字列に入ったカンマも安全）。
    """
    close = _match_paren(blank, open_idx)
    spans: List[Tuple[int, int]] = []
    depth, start = 0, open_idx + 1
    for i in range(open_idx, close + 1):
        c = blank[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                spans.append((start, i))
                break
        elif c == "," and depth == 1:
            spans.append((start, i))
            start = i + 1
    return [(s, e) for s, e in spans if blank[s:e].strip()]


def _trim(text: str, s: int, e: int) -> Tuple[int, int]:
    """前後の空白を落とした区間。"""
    while s < e and text[s].isspace():
        s += 1
    while e > s and text[e - 1].isspace():
        e -= 1
    return s, e


def _num_span(text: str, s: int, e: int) -> Optional[Tuple[int, int, str]]:
    """引数がただの数値なら ``(開始, 終了, 接尾辞)``。違うなら ``None``。"""
    s, e = _trim(text, s, e)
    m = _NUM.match(text[s:e])
    return (s, e, m.group(2)) if m else None


def _fmt(value: float, kind: str, suffix: str = "") -> str:
    """種別どおりの literal にする。**int の欄に小数を書くと Java が壊れる。**"""
    if kind == "int":
        return f"{int(round(float(value)))}{suffix}"
    out = f"{round(float(value), 4):g}"
    if "." not in out and "e" not in out:
        out += ".0"
    return f"{out}{suffix}"


class Doc:
    """1 ファイルぶんの編集。区間の置き換えをためて最後にまとめて適用する。"""

    def __init__(self, path: str) -> None:
        self.path = path
        # **newline="" は必須。** 付け忘れると Python が CRLF を LF に直して
        # 読み込み、書き戻したときに**ファイル全体の改行が置き換わる**。
        # 変えたのは数値 1 個なのに、差分が数千行になる
        with open(path, encoding="utf-8", newline="") as f:
            self.orig = f.read()
        self.blank = _blank_comments(self.orig)
        self.edits: List[Tuple[int, int, str]] = []

    def put(self, span: Tuple[int, int], new: str) -> None:
        self.edits.append((span[0], span[1], new))

    def render(self) -> str:
        out = self.orig
        # 後ろから当てないと、前の置換で後ろの位置がずれる
        for s, e, new in sorted(self.edits, key=lambda x: -x[0]):
            out = out[:s] + new + out[e:]
        return out


# ════════════════════════════════════════════════════════════════════
# どの数値を編集できるか
# ════════════════════════════════════════════════════════════════════
#: タワーの基礎性能。**並びは Java の TowerKind コンストラクタと同じ順**で、
#: ``Targeting.X`` の直後に 10 個並んでいる
TOWER_FIELDS = (
    ("base_cost",     "建設コスト",       "int",   "コイン"),
    ("base_range",    "射程",             "float", "ブロック"),
    ("base_cooldown", "攻撃間隔",         "int",   "tick"),
    ("base_damage",   "攻撃力",           "float", "1 発あたり"),
    ("splash_radius", "範囲半径",         "float", "SPLASH のみ"),
    ("chain_targets", "連鎖数",           "int",   "CHAIN / PIERCE のみ"),
    ("slow_factor",   "減速率",           "float", "0..1"),
    ("slow_ticks",    "減速時間",         "int",   "tick"),
    ("burn_dps",      "燃焼ダメージ",     "float", "毎秒"),
    ("burn_ticks",    "燃焼時間",         "int",   "tick"),
)

#: Lv3 の特化。Java は ``new Spec(名前, 説明, ここから数値)``
SPEC_FIELDS = (
    ("damage_mul",   "攻撃力倍率",   "float", "1.0 で等倍"),
    ("range_add",    "射程加算",     "float", "ブロック"),
    ("cooldown_mul", "攻撃間隔倍率", "float", "小さいほど速い"),
    ("splash_add",   "範囲加算",     "float", ""),
    ("chain_add",    "連鎖加算",     "int",   ""),
    ("slow_add",     "減速加算",     "float", ""),
    ("burn_add",     "燃焼加算",     "float", "毎秒"),
    ("burn_mul",     "燃焼倍率",     "float", "1.0 で等倍"),
)

#: 敵の体。``MoveMode.X`` の直後に 7 個
ENEMY_FIELDS = (
    ("base_hp",         "HP",         "float", "対戦では送りの HP が優先"),
    ("base_speed",      "速度",       "float", "ブロック / tick"),
    ("armor",           "装甲",       "float", "固定軽減"),
    ("gold_reward",     "撃破報酬",   "int",   "シングル用"),
    ("leak_damage",     "漏れダメージ", "int", "シングル用"),
    ("slow_resist",     "減速耐性",   "float", "0..1"),
    ("heal_per_second", "毎秒回復",   "float", ""),
)

#: 能力。Java は ``Trait.sapper(3.5, 40)`` のようなファクトリで、引数の順が定義
TRAIT_ARGS: Dict[str, Tuple[Tuple[str, str, str, str], ...]] = {
    "sapper":    (("disable_radius", "妨害半径",   "float", "ブロック"),
                  ("disable_ticks",  "妨害時間",   "int",   "tick")),
    "blink":     (("blink_radius",   "瞬移半径",   "float", "ブロック"),
                  ("blink_cooldown", "瞬移間隔",   "int",   "tick")),
    "ward":      (("ward_radius",    "庇護半径",   "float", "ブロック"),
                  ("ward_reduction", "軽減率",     "float", "0..1")),
    "split":     (("split_count",    "分裂数",     "int",   "体")),
    "reaper":    (("revives",        "復活回数",   "int",   "回")),
    "fireproof": (),
}
# 1 要素のタプルは書き間違えやすいので正規化する
TRAIT_ARGS["split"] = (("split_count", "分裂数", "int", "体"),)
TRAIT_ARGS["reaper"] = (("revives", "復活回数", "int", "回"),)

#: 送りモンスター。``EnemyKind.X`` の直後に 5 個
ATTACKER_FIELDS = (
    ("cost",          "コイン",       "int",   ""),
    ("income_gain",   "インカム増加", "int",   "送るたび恒久"),
    ("stock_cost",    "ストック消費", "int",   ""),
    ("unlock_income", "解禁インカム", "int",   "これ以上で送れる"),
    ("hp",            "HP",           "float", "送られた個体"),
)

#: 経済。``(python 属性, Java 定数名, Java ファイル, 種別, 表示名)``
ECONOMY_FIELDS = (
    ("start_coins",         "START_COINS",         "versus/VersusPlayer.java", "int", "開始コイン"),
    ("start_income",        "START_INCOME",        "versus/VersusPlayer.java", "int", "開始インカム"),
    ("start_lives",         "START_LIVES",         "versus/VersusPlayer.java", "int", "開始ライフ"),
    ("max_stock",           "MAX_STOCK",           "versus/VersusPlayer.java", "int", "ストック上限"),
    ("prep_ticks",          "PREP_TICKS",          "versus/VersusMatch.java",  "int", "準備時間(tick)"),
    ("income_interval",     "INCOME_INTERVAL",     "versus/VersusMatch.java",  "int", "収入間隔(tick)"),
    ("stock_interval",      "STOCK_INTERVAL",      "versus/VersusMatch.java",  "int", "ストック回復間隔(tick)"),
    ("sudden_death_ticks",  "SUDDEN_DEATH_TICKS",  "versus/VersusMatch.java",  "int", "サドンデス開始(tick)"),
    ("card_interval",       "CARD_INTERVAL",       "versus/VersusMatch.java",  "int", "カード配布間隔(tick)"),
    ("hand_limit",          "HAND_LIMIT",          "versus/VersusMatch.java",  "int", "手札上限"),
    ("start_hand",          "START_HAND",          "versus/VersusMatch.java",  "int", "開始手札"),
    ("max_towers",          "MAX_TOWERS",          "versus/Island.java",       "int", "タワー上限"),
)

#: 戦闘定数。``(python 定数名, Java ファイル, 種別, 表示名)``
CONSTANT_FIELDS = (
    ("CHAIN_RADIUS",           "stage/Battlefield.java", "float", "連鎖の届く半径"),
    ("PIERCE_WIDTH",           "stage/Battlefield.java", "float", "貫通の判定幅"),
    ("CROWD_RADIUS",           "stage/Battlefield.java", "float", "密集の判定半径"),
    ("HEAL_INTERVAL",          "stage/Battlefield.java", "int",   "オーラ適用間隔(tick)"),
    ("DISABLE_REFRESH_TICKS",  "enemy/Trait.java",       "int",   "妨害の掛け直し間隔(tick)"),
)


# ════════════════════════════════════════════════════════════════════
# Java 側の場所を突き止める
# ════════════════════════════════════════════════════════════════════
#: 1 項目ぶんの書き込み先。``insert`` が真なら区間ではなく挿入位置
Target = Tuple[str, Tuple[int, int], str, str, bool]   # (file, span, kind, suffix, insert)


def _enum_spans(blank: str, valid) -> Dict[str, Tuple[int, int]]:
    """enum 定数ごとに **次の定数の手前まで** の区間を返す。

    固定幅の窓で切ると、能力を持たない敵が次の敵の ``Trait.sapper(...)`` を
    拾ってしまう（:mod:`sync_balance` が実際に踏んだ罠）。
    """
    starts = [(m.start(), m.group(1))
              for m in re.finditer(r"^[ \t]{4}([A-Z][A-Z_0-9]*)\s*\(", blank, re.M)]
    out: Dict[str, Tuple[int, int]] = {}
    for i, (pos, name) in enumerate(starts):
        if name in valid:
            end = starts[i + 1][0] if i + 1 < len(starts) else len(blank)
            out[name] = (pos, end)
    return out


def _after_marker(blank: str, args: List[Tuple[int, int]], prefix: str,
                  count: int) -> Optional[List[Tuple[int, int]]]:
    """``prefix`` で始まる引数の**次から** ``count`` 個ぶんの引数区間。"""
    for i, (s, e) in enumerate(args):
        if blank[s:e].strip().startswith(prefix):
            got = args[i + 1:i + 1 + count]
            return got if len(got) == count else None
    return None


def locate_java(docs: Dict[str, Doc]) -> Dict[str, Target]:
    """Java 側の書き込み先を全部集める。読めなかった項目は黙って落とす。"""
    out: Dict[str, Target] = {}

    def take(path, rel, span, kind):
        doc = docs[rel]
        got = _num_span(doc.blank, span[0], span[1])
        if got:
            out[path] = (rel, (got[0], got[1]), kind, got[2], False)

    # --- タワー ---
    rel = "tower/TowerKind.java"
    blank = docs[rel].blank
    for name, (lo, hi) in _enum_spans(blank, B.TOWERS).items():
        args = _split_args(blank, blank.index("(", lo))
        nums = _after_marker(blank, args, "Targeting.", len(TOWER_FIELDS))
        if nums:
            for (field, _l, kind, _u), span in zip(TOWER_FIELDS, nums):
                take(f"tower.{name}.{field}", rel, span, kind)
        specs = [(s, e) for s, e in args if blank[s:e].strip().startswith("new Spec(")]
        for si, (s, e) in enumerate(specs):
            sub = _split_args(blank, blank.index("(", s))
            # 名前・説明のあと、数値が続くところまでが性能。そのあとは Effect
            nums2 = []
            for a in sub[2:]:
                if _num_span(blank, a[0], a[1]) is None:
                    break
                nums2.append(a)
            for fi, (field, _l, kind, _u) in enumerate(SPEC_FIELDS):
                if fi < len(nums2):
                    take(f"tower.{name}.spec{si}.{field}", rel, nums2[fi], kind)
                elif fi == len(nums2) and field == "burn_mul":
                    # burnMul を省いた 7 引数版。**足すなら burnAdd の直後**
                    end = nums2[-1][1] if nums2 else None
                    if end is not None:
                        _s, e2 = _trim(blank, nums2[-1][0], end)
                        out[f"tower.{name}.spec{si}.{field}"] = (
                            rel, (e2, e2), kind, "", True)

    # --- 敵 ---
    rel = "enemy/EnemyKind.java"
    blank = docs[rel].blank
    for name, (lo, hi) in _enum_spans(blank, B.ENEMIES).items():
        args = _split_args(blank, blank.index("(", lo))
        nums = _after_marker(blank, args, "MoveMode.", len(ENEMY_FIELDS))
        if nums:
            for (field, _l, kind, _u), span in zip(ENEMY_FIELDS, nums):
                take(f"enemy.{name}.{field}", rel, span, kind)
        t = re.search(r"Trait\.(\w+)\(", blank[lo:hi])
        if t:
            spec = TRAIT_ARGS.get(t.group(1), ())
            sub = _split_args(blank, lo + t.end() - 1)
            for (field, _l, kind, _u), span in zip(spec, sub):
                take(f"enemy.{name}.trait.{field}", rel, span, kind)

    # --- 送りモンスター ---
    rel = "versus/AttackerKind.java"
    blank = docs[rel].blank
    for name, (lo, hi) in _enum_spans(blank, B.ATTACKERS).items():
        args = _split_args(blank, blank.index("(", lo))
        nums = _after_marker(blank, args, "EnemyKind.", len(ATTACKER_FIELDS))
        if nums:
            for (field, _l, kind, _u), span in zip(ATTACKER_FIELDS, nums):
                take(f"attacker.{name}.{field}", rel, span, kind)

    # --- 経済・戦闘定数（定数の代入式そのものを置き換える）---
    for field, java_name, rel, kind, _label in ECONOMY_FIELDS:
        _java_constant(out, docs, f"economy.{field}", rel, java_name, kind)
    for name, rel, kind, _label in CONSTANT_FIELDS:
        _java_constant(out, docs, f"const.{name}", rel, name, kind)
    return out


def _java_constant(out, docs, path, rel, java_name, kind) -> None:
    """``NAME = 20 * 60 * 15;`` の**右辺まるごと**を置き換え対象にする。

    定数式で書かれていることがあるので、数値 1 個だけを狙うと当たらない。
    """
    doc = docs.get(rel)
    if doc is None:
        return
    m = re.search(rf"\b{java_name}\s*=\s*([^;]+);", doc.blank)
    if m:
        out[path] = (rel, _trim(doc.blank, *m.span(1)), kind, "", False)


# ════════════════════════════════════════════════════════════════════
# balance.py 側の場所を突き止める
# ════════════════════════════════════════════════════════════════════
def _kwarg_span(blank: str, lo: int, hi: int, field: str
                ) -> Optional[Tuple[int, int]]:
    """``field=12.5`` の**値の側**の区間。無ければ ``None``。"""
    m = re.search(rf"\b{field}\s*=\s*(-?\d+(?:\.\d+)?)", blank[lo:hi])
    return (lo + m.start(1), lo + m.end(1)) if m else None


def _insert_before(blank: str, lo: int, hi: int, anchors: Tuple[str, ...]) -> int:
    """省略された引数を差し込む位置。``anchors`` の手前、無ければ閉じ括弧の手前。"""
    for anchor in anchors:
        m = re.search(rf"\b{anchor}\s*=", blank[lo:hi])
        if m:
            return lo + m.start()
    return hi


def locate_python(doc: Doc) -> Dict[str, Target]:
    """``balance.py`` 側の書き込み先。**省略されている引数は挿入先**を返す。"""
    out: Dict[str, Target] = {}
    blank, rel = doc.blank, "balance.py"

    def take(path, span, kind):
        got = _num_span(blank, span[0], span[1])
        if got:
            out[path] = (rel, (got[0], got[1]), kind, got[2], False)

    def entry(pattern: str) -> Optional[Tuple[int, int, List[Tuple[int, int]]]]:
        """辞書の項目を探す。

        **キーの検索だけは元テキストで行う。** :func:`_blank_comments` は
        文字列の中身も潰すので、``"ARROW":`` の ``ARROW`` が消えてしまう。
        潰したあとも引用符とオフセットは残るので、``blank`` 側が引用符で
        始まっていることを確かめれば「説明文の中の例示」を弾ける。
        """
        for m in re.finditer(pattern, doc.orig):
            if blank[m.start()] != '"':
                continue                      # docstring の中の例示
            open_idx = blank.index("(", m.end() - 1)
            return (open_idx, _match_paren(blank, open_idx),
                    _split_args(blank, open_idx))
        return None

    # --- タワー（キーワード引数。既定値のままなら省略されている）---
    for name in B.TOWERS:
        found = entry(rf'"{name}":\s*TowerDef\(')
        if not found:
            continue
        lo, hi, args = found
        specs = [(s, e) for s, e in args if "TowerSpec(" in blank[s:e]]
        head_hi = min([s for s, _e in specs] + [hi])
        for field, _l, kind, _u in TOWER_FIELDS:
            span = _kwarg_span(blank, lo, head_hi, field)
            if span:
                take(f"tower.{name}.{field}", span, kind)
            else:
                pos = _insert_before(blank, lo, hi, ("specs", "effect"))
                out[f"tower.{name}.{field}"] = (rel, (pos, pos), kind, "", True)
        # 特化は specs=(...) の中に TowerSpec(...) が並ぶ
        spec_calls = [m.start() for m in re.finditer(r"TowerSpec\(", blank[lo:hi])]
        for si, off in enumerate(spec_calls):
            s_open = blank.index("(", lo + off + len("TowerSpec") - 1)
            s_close = _match_paren(blank, s_open)
            for field, _l, kind, _u in SPEC_FIELDS:
                span = _kwarg_span(blank, s_open, s_close, field)
                path = f"tower.{name}.spec{si}.{field}"
                if span:
                    take(path, span, kind)
                else:
                    pos = _insert_before(blank, s_open, s_close, ("effect_add",))
                    out[path] = (rel, (pos, pos), kind, "", True)

    # --- 敵（位置引数なので必ず全部ある）---
    for name in B.ENEMIES:
        found = entry(rf'"{name}":\s*EnemyDef\(')
        if not found:
            continue
        lo, hi, args = found
        nums = args[2:2 + len(ENEMY_FIELDS)]     # 名前・飛行のあとに 7 個
        for (field, _l, kind, _u), span in zip(ENEMY_FIELDS, nums):
            take(f"enemy.{name}.{field}", span, kind)
        t = re.search(r"\bTrait\(", blank[lo:hi])
        if t:
            t_open = lo + t.end() - 1
            t_close = _match_paren(blank, t_open)
            for field in _trait_fields_of(name):
                span = _kwarg_span(blank, t_open, t_close, field[0])
                path = f"enemy.{name}.trait.{field[0]}"
                if span:
                    take(path, span, field[2])
                else:
                    out[path] = (rel, (t_close, t_close), field[2], "", True)

    # --- 送りモンスター（位置引数）---
    for name in B.ATTACKERS:
        found = entry(rf'"{name}":\s*AttackerDef\(')
        if not found:
            continue
        lo, hi, args = found
        nums = args[2:2 + len(ATTACKER_FIELDS)]  # 名前・体のあとに 5 個
        for (field, _l, kind, _u), span in zip(ATTACKER_FIELDS, nums):
            take(f"attacker.{name}.{field}", span, kind)

    # --- 経済（dataclass の既定値）---
    eco = re.search(r"class Economy[^\n]*\n(.*?)\n\n\n", blank, re.S)
    if eco:
        lo, hi = eco.span(1)
        for field, _j, _rel, kind, _label in ECONOMY_FIELDS:
            m = re.search(rf"^\s*{field}\s*:\s*(?:int|float)\s*=\s*([^\n#]+)",
                          blank[lo:hi], re.M)
            if m:
                out[f"economy.{field}"] = (
                    rel, _trim(blank, lo + m.start(1), lo + m.end(1)),
                    kind, "", False)

    # --- 戦闘定数（モジュール直下）---
    for name, _rel, kind, _label in CONSTANT_FIELDS:
        m = re.search(rf"^{name}\s*=\s*([^\n#]+)", blank, re.M)
        if m:
            out[f"const.{name}"] = (rel, _trim(blank, *m.span(1)), kind, "", False)
    return out


def _trait_fields_of(enemy: str) -> Tuple[Tuple[str, str, str, str], ...]:
    """その敵が実際に持っている能力の欄だけを返す。"""
    trait = B.ENEMIES[enemy].trait
    if trait == B.NO_TRAIT:
        return ()
    for spec in TRAIT_ARGS.values():
        if spec and all(getattr(trait, f[0]) for f in spec):
            return spec
    # 一部が 0 の能力（庇護の軽減率 0 など）は総当たりでは決まらないので、
    # 「0 でない欄をひとつでも持つ」ゆるい判定に落とす
    for spec in TRAIT_ARGS.values():
        if spec and any(getattr(trait, f[0]) for f in spec):
            return spec
    return ()


# ════════════════════════════════════════════════════════════════════
# 公開 API
# ════════════════════════════════════════════════════════════════════
JAVA_FILES = ("tower/TowerKind.java", "enemy/EnemyKind.java",
              "versus/AttackerKind.java", "versus/VersusPlayer.java",
              "versus/VersusMatch.java", "versus/Island.java",
              "stage/Battlefield.java", "enemy/Trait.java")


def _open_docs() -> Dict[str, Doc]:
    docs = {"balance.py": Doc(BALANCE_PY)}
    for rel in JAVA_FILES:
        path = os.path.join(JAVA_ROOT, *rel.split("/"))
        if os.path.exists(path):
            docs[rel] = Doc(path)
    return docs


#: Java 側で**引数ごと省略されている**ときの既定値。
#: ``new Spec(...)`` は burnMul を省いた 7 引数版があり、そのとき Java は 1.0。
#: ここを ``None`` のままにすると「Java と値がずれている」と誤報する
JAVA_OMITTED_DEFAULT = {"burn_mul": 1.0}


def _java_value(docs, target, path: str = "") -> Optional[float]:
    rel, (s, e), kind, _suffix, insert = target
    if insert:
        return JAVA_OMITTED_DEFAULT.get(path.rsplit(".", 1)[-1])
    raw = docs[rel].blank[s:e].strip()
    m = _NUM.match(raw)
    if m:
        return float(m.group(1))
    return sync_balance._eval_int(raw)          # 20 * 60 * 15 のような定数式


def _py_value(path: str):
    """``balance.py`` の現在値。読めない項目は ``None``。"""
    part = path.split(".")
    try:
        if part[0] == "tower":
            tower = B.TOWERS[part[1]]
            if part[2].startswith("spec"):
                idx = int(part[2][4:])
                if idx >= len(tower.specs):
                    return None
                return getattr(tower.specs[idx], part[3])
            return getattr(tower, part[2])
        if part[0] == "enemy":
            enemy = B.ENEMIES[part[1]]
            if part[2] == "trait":
                return getattr(enemy.trait, part[3])
            return getattr(enemy, part[2])
        if part[0] == "attacker":
            return getattr(B.ATTACKERS[part[1]], part[2])
        if part[0] == "economy":
            return getattr(B.ECONOMY, part[1])
        if part[0] == "const":
            return getattr(B, part[1])
    except (KeyError, AttributeError, IndexError, ValueError):
        return None
    return None


def snapshot() -> dict:
    """編集画面が必要とするものを一式返す。

    **Java の現在値も一緒に返す。** 片方だけ見て編集すると、ずれていることに
    気づかないまま上書きしてしまう。
    """
    importlib.reload(B)
    docs = _open_docs()
    py_loc = locate_python(docs["balance.py"])
    java_loc = locate_java(docs)

    def field(path, label, kind, unit):
        value = _py_value(path)
        java = _java_value(docs, java_loc[path], path) if path in java_loc else None
        same = (value is not None and java is not None
                and abs(float(value) - float(java)) <= 1e-6)
        return {"path": path, "label": label, "kind": kind, "unit": unit,
                "value": value, "java": java, "same": same,
                "editable": path in py_loc and path in java_loc}

    groups = []

    rows = []
    for name, tower in B.TOWERS.items():
        fields = [field(f"tower.{name}.{f}", l, k, u)
                  for f, l, k, u in TOWER_FIELDS]
        subs = []
        for si, spec in enumerate(tower.specs):
            subs.append({"id": f"spec{si}", "label": f"特化: {spec.name_jp}",
                         "fields": [field(f"tower.{name}.spec{si}.{f}", l, k, u)
                                    for f, l, k, u in SPEC_FIELDS]})
        rows.append({"id": name, "label": tower.name_jp,
                     "note": f"{tower.shape} / {tower.element} / {tower.style}"
                             f" / {tower.targeting}",
                     "fields": fields, "subs": subs})
    groups.append({"key": "towers", "label": "タワー", "rows": rows})

    rows = []
    for name, enemy in B.ENEMIES.items():
        fields = [field(f"enemy.{name}.{f}", l, k, u) for f, l, k, u in ENEMY_FIELDS]
        fields += [field(f"enemy.{name}.trait.{f}", l, k, u)
                   for f, l, k, u in _trait_fields_of(name)]
        rows.append({"id": name, "label": enemy.name_jp,
                     "note": ("飛行" if enemy.flying else "地上")
                             + ("・ボス" if enemy.boss else ""),
                     "fields": fields, "subs": []})
    groups.append({"key": "enemies", "label": "敵（体）", "rows": rows})

    rows = []
    for name, atk in B.ATTACKERS.items():
        rows.append({"id": name, "label": atk.name_jp,
                     "note": f"体 = {atk.body}",
                     "fields": [field(f"attacker.{name}.{f}", l, k, u)
                                for f, l, k, u in ATTACKER_FIELDS],
                     "subs": []})
    groups.append({"key": "attackers", "label": "送りモンスター", "rows": rows})

    groups.append({"key": "economy", "label": "経済", "rows": [{
        "id": "ECONOMY", "label": "試合の経済", "note": "", "subs": [],
        "fields": [field(f"economy.{f}", label, kind, "")
                   for f, _j, _rel, kind, label in ECONOMY_FIELDS]}]})

    groups.append({"key": "constants", "label": "戦闘定数", "rows": [{
        "id": "CONST", "label": "戦闘の共通定数", "note": "", "subs": [],
        "fields": [field(f"const.{n}", label, kind, "")
                   for n, _rel, kind, label in CONSTANT_FIELDS]}]})

    missing = sorted(p for p in py_loc if p not in java_loc)
    return {"groups": groups, "java_root": JAVA_ROOT,
            "unwritable": missing[:40], "unwritable_total": len(missing)}


def _diverged() -> set:
    """balance.py と Java で値が食い違っている項目のパス。**全 412 項目を見る。**"""
    snap = snapshot()
    out = set()
    for group in snap["groups"]:
        for row in group["rows"]:
            for block in [row] + row["subs"]:
                for f in block["fields"]:
                    if not f["same"]:
                        out.add(f["path"])
    return out


def _insert_text(blank: str, pos: int, field: str, literal: str,
                 is_python: bool) -> str:
    if not is_python:
        return f", {literal}"
    if blank[pos:pos + 1] == ")":
        return f", {field}={literal}"
    return f"{field}={literal}, "


def apply(changes: List[dict]) -> Tuple[bool, str, dict]:
    """まとめて書き戻す。**1 つでも駄目なら 1 文字も書かない。**

    ``changes`` は ``[{"path": "tower.ARROW.base_cost", "value": 35}, ...]``。
    """
    if not changes:
        return False, "変更がありません", {}

    importlib.reload(B)
    before = sync_balance.run().as_dict()
    before_diff = _diverged()
    docs = _open_docs()
    py_loc = locate_python(docs["balance.py"])
    java_loc = locate_java(docs)

    unknown, applied = [], []
    for item in changes:
        path = str(item.get("path", ""))
        if path not in py_loc or path not in java_loc:
            unknown.append(path)
            continue
        try:
            value = float(item["value"])
        except (KeyError, TypeError, ValueError):
            unknown.append(path)
            continue
        old = _py_value(path)
        for target in (py_loc[path], java_loc[path]):
            rel, span, kind, suffix, insert = target
            doc = docs[rel]
            literal = _fmt(value, kind, suffix)
            if insert:
                field = path.rsplit(".", 1)[1]
                doc.put(span, _insert_text(doc.blank, span[0], field, literal,
                                           rel == "balance.py"))
            else:
                doc.put(span, literal)
        applied.append({"path": path, "old": old, "new": value})

    if unknown:
        return False, ("書き込み先が見つからない項目があるので中止しました: "
                       + ", ".join(unknown[:5])), {"unknown": unknown}

    originals = {rel: doc.orig for rel, doc in docs.items() if doc.edits}
    try:
        for rel, doc in docs.items():
            if doc.edits:
                with open(doc.path, "w", encoding="utf-8", newline="") as f:
                    f.write(doc.render())

        with open(BALANCE_PY, encoding="utf-8") as f:
            ast.parse(f.read())
        importlib.reload(B)
        after = sync_balance.run().as_dict()
        known_bad = {m["key"] for m in before["mismatch"]}
        # sync_balance は特化（Spec）を見ていないので、**自前の全項目比較も通す**。
        # 書いたのは両方だから普通はずれないが、位置の取り違えはこれで出る
        new_bad = ([m["key"] for m in after["mismatch"] if m["key"] not in known_bad]
                   + [k for k in after["unreadable"]
                      if k not in before["unreadable"]]
                   + sorted(_diverged() - before_diff))
        if new_bad:
            raise ValueError("Java と balance.py がずれました: "
                             + ", ".join(new_bad[:5]))
    except Exception as e:      # noqa: BLE001
        for rel, text in originals.items():
            with open(docs[rel].path, "w", encoding="utf-8", newline="") as f:
                f.write(text)
        importlib.reload(B)
        return False, f"書き換えに失敗したので全部元に戻しました: {e}", {}

    files = sorted(originals)
    return True, (f"{len(applied)} 項目を書き換えました（{len(files)} ファイル）。"
                  "実ゲームに反映するにはビルドとサーバー再起動が必要です"), \
        {"applied": applied, "files": files}
