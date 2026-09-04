# -*- coding: utf-8 -*-
"""MAZEWARD VERSUS の全数値。**ここがバランスの単一の真実（single source of truth）。**

バランス調整者が触るのはこのファイルだけ。学習環境・診断ツール・ボットは
すべてここを読む。Java 側の enum と数値が一致しているかは
``sync_balance.py`` がソースのテキスト解析で突き合わせる
（AI 環境は Minestom をコンパイルしないので、コンパイラは使えない）。

なぜ「1 ファイルに全部」なのか
------------------------------
塔・敵・経済の数値が 3 箇所に散っていると、「強化コストがインカムに対して
適切か」という診断が**そもそも書けない**。3 つは同じ財布を奪い合っていて、
片方だけ見ても意味がないため、意図的に 1 つの名前空間へ集めている。

数値の出どころ（Java 側）
------------------------
- ``tower/TowerKind.java``      … TOWERS / TowerSpec / upgrade_cost / stats_at
- ``enemy/EnemyKind.java``      … ENEMIES
- ``enemy/Trait.java``          … Trait（敵の能力）
- ``versus/AttackerKind.java``  … ATTACKERS（送りモンスター）
- ``versus/VersusPlayer.java``  … ECONOMY（コイン・インカム・ライフ・ストック）
- ``versus/VersusMatch.java``   … ECONOMY / TIMING（収入間隔・カード間隔・サドンデス）
- ``versus/Island.java``        … MAX_TOWERS
- ``stage/Battlefield.java``    … COMBAT（連鎖半径・範囲減衰などの戦闘定数）
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Dict, List, Optional, Tuple

# ════════════════════════════════════════════════════════════════════
# 形状カタログ  (core/Shapes.java)
# ════════════════════════════════════════════════════════════════════
# セルは (x, z) のペア。Java 側と同じ定義をそのまま持つ。
SHAPE_CELLS: Dict[str, Tuple[Tuple[int, int], ...]] = {
    "DOT":    ((0, 0),),
    "I2":     ((0, 0), (1, 0)),
    "I3":     ((0, 0), (1, 0), (2, 0)),
    "I4":     ((0, 0), (1, 0), (2, 0), (3, 0)),
    "O":      ((0, 0), (1, 0), (0, 1), (1, 1)),
    "L":      ((0, 0), (0, 1), (0, 2), (1, 2)),
    "J":      ((1, 0), (1, 1), (1, 2), (0, 2)),
    "T":      ((0, 0), (1, 0), (2, 0), (1, 1)),
    "S":      ((1, 0), (2, 0), (0, 1), (1, 1)),
    "Z":      ((0, 0), (1, 0), (1, 1), (2, 1)),
    "P":      ((0, 0), (1, 0), (0, 1), (1, 1), (0, 2), (1, 2)),
    "U":      ((0, 0), (0, 1), (1, 1), (2, 1), (2, 0)),
    "CORNER": ((0, 0), (0, 1), (1, 1)),
}

#: ラン開始デッキ 16 枚 (Shapes#starterDeck)。対戦では山札が尽きると
#: ライブラリを切り直して引き直すので、**カードは有限ではなく「30 秒に 1 枚」の
#: 流量制限**である（Deck#drawOne）。手札上限 6 と合わせて希少性を作っている。
STARTER_DECK: Tuple[str, ...] = (
    "I3", "I3", "I3", "I3",
    "I2", "I2", "I2",
    "L", "L", "J", "J",
    "CORNER", "CORNER", "CORNER",
    "O", "T",
)


# ════════════════════════════════════════════════════════════════════
# 敵の能力  (enemy/Trait.java)
# ════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class Trait:
    """敵の特殊能力。持たない敵は :data:`NO_TRAIT`。"""

    disable_radius: float = 0.0     # 妨害: タワーを黙らせる半径
    disable_ticks: int = 0          # 妨害: 黙る tick 数
    blink_radius: float = 0.0       # 瞬移: 被弾時に跳べる空間半径
    blink_cooldown: int = 0         # 瞬移: 再瞬移までの tick
    ward_radius: float = 0.0        # 庇護: 被ダメ軽減オーラの半径
    ward_reduction: float = 0.0     # 庇護: 軽減率
    split_count: int = 0            # 分裂: 倒れたとき湧く子の数
    burn_resist: float = 0.0        # 不燃: 燃焼耐性 0..1
    revives: int = 0                # 復活: 出発点へ戻れる回数（もう一周させるだけ）


NO_TRAIT = Trait()

#: 妨害の掛け直し間隔 (Trait.DISABLE_REFRESH_TICKS)
DISABLE_REFRESH_TICKS = 10


# ════════════════════════════════════════════════════════════════════
# 敵  (enemy/EnemyKind.java)
# ════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class EnemyDef:
    """敵の体。

    ``base_hp`` / ``gold_reward`` / ``leak_damage`` は**シングル用**で、
    対戦では使わない（HP は :class:`AttackerDef` が、報酬は送りコストの 20%、
    リークダメージは試合のサドンデス状態が決める）。診断のために保持している。
    """

    name_jp: str
    flying: bool
    base_hp: float
    base_speed: float           # ブロック / tick
    armor: float                # 固定軽減（先に引く）
    gold_reward: int
    leak_damage: int
    slow_resist: float          # 0..1（1.0 で完全耐性）
    heal_per_second: float
    trait: Trait = NO_TRAIT
    boss: bool = False          # コアに触れても消えず出発点へ戻る


ENEMIES: Dict[str, EnemyDef] = {
    "GRUNT":     EnemyDef("徘徊者", False, 34,   0.055, 0, 6,   1, 0.00, 0.0),
    "RUNNER":    EnemyDef("疾走者", False, 20,   0.115, 0, 5,   1, 0.15, 0.0),
    "BRUTE":     EnemyDef("重装兵", False, 130,  0.035, 5, 16,  3, 0.35, 0.0),
    "FLYER":     EnemyDef("浮遊体", True,  62,   0.075, 0, 12,  2, 0.00, 0.0),
    "HEALER":    EnemyDef("祈祷師", False, 70,   0.045, 2, 18,  2, 0.00, 6.0),
    "BOSS":      EnemyDef("災厄",   False, 4200, 0.032, 8, 200, 10, 0.85, 0.0,
                          boss=True),
    "SAPPER":    EnemyDef("妨害者", False, 56,  0.062, 1, 11, 1, 0.00, 0.0,
                          Trait(disable_radius=3.5, disable_ticks=40)),
    "BLINKER":   EnemyDef("瞬移体", False, 74,  0.058, 0, 13, 2, 0.40, 0.0,
                          Trait(blink_radius=5.0, blink_cooldown=45)),
    "AEGIS":     EnemyDef("庇護者", False, 150, 0.040, 4, 22, 2, 0.50, 0.0,
                          Trait(ward_radius=5.5, ward_reduction=0.35)),
    "SPLITTER":  EnemyDef("分裂体", False, 96,  0.050, 2, 10, 2, 0.00, 0.0,
                          Trait(split_count=2)),
    "SPLITLING": EnemyDef("分裂片", False, 28,  0.068, 0, 4,  1, 0.00, 0.0),
    "EMBERLING": EnemyDef("熱塊",   False, 110, 0.070, 3, 18, 2, 1.00, 0.0,
                          Trait(burn_resist=1.0)),
    "REAPER":    EnemyDef("終焉騎", False, 260, 0.048, 6, 46, 3, 0.60, 0.0,
                          Trait(revives=1)),
    # --- 上位種 ---
    # 送りの梯子が 16 段に伸びたぶんの体。**能力は下位と同じものを使い回す。**
    # 新しい咎め方を段ごとに増やすと覚えることだけが増えるので、
    # 「同じ咎め方を、育った防衛にも通る太さで撃ち直せる」だけにしてある。
    "SWIFTBEAST":    EnemyDef("疾風獣",   False, 900,  0.125, 3,  60, 2, 0.55, 0.0),
    "BREAKER":       EnemyDef("破城者",   False, 1400, 0.062, 4,  80, 2, 0.20, 0.0,
                              Trait(disable_radius=5.0, disable_ticks=70)),
    "IRONWALL":      EnemyDef("鉄壁",     False, 4000, 0.030, 14, 120, 3, 0.65, 0.0),
    "CANOPY":        EnemyDef("天蓋",     True,  5000, 0.062, 6, 150, 3, 0.00, 0.0),
    "HIGHPRIEST":    EnemyDef("大祭司",   False, 6000, 0.042, 6, 180, 3, 0.10, 700.0),
    "GREATSPLITTER": EnemyDef("大分裂体", False, 8000, 0.048, 5, 220, 3, 0.00, 0.0,
                              Trait(split_count=3)),
}

#: 回復オーラの半径 (Battlefield#applyEnemyAuras)
HEAL_AURA_RADIUS = 5.0
#: 敵オーラ（回復・庇護）の適用間隔 tick
HEAL_INTERVAL = 20
#: 庇護を貼り直す持続 tick。オーラ間隔より少し長い＝庇護者が落ちれば即切れる
WARD_TICKS = HEAL_INTERVAL + 5
#: 分裂の子の HP 倍率と報酬分割 (Battlefield#spawnSplits)
SPLIT_HP_RATIO = 0.28
SPLIT_REWARD_DIVISOR = 3


# ════════════════════════════════════════════════════════════════════
# 送りモンスター  (versus/AttackerKind.java)
# ════════════════════════════════════════════════════════════════════
#: **1 回の送りが盤面に返すコインの総量**（送りコストに対する割合）。
#: (AttackerKind#KILL_REWARD_TOTAL)
#:
#: 送りは生存者全員に 1 体ずつ湧くので、「1 体につき 20%」と素朴に置くと
#: 返る総量が ``20% x (人数-1)`` になり、**6 人以上で払った額より返る額が多くなる**。
#: 送りが黒字の行為になり、コインが雪だるま式に膨らむ（実際にそうなっていた）。
#: 1 体あたりの報酬を人数で割り、総量をここに固定する。
KILL_REWARD_TOTAL = 0.40
#: 撃破報酬コインの何割がインカムになるか (VersusPlayer#onKillReward)
KILL_INCOME_RATIO = 0.10
#: 送ったモンスターが相手のコアに届いたとき、**送り主**が取り戻すライフ。
#: (Island#rewardSender / VersusPlayer#gainLife)
#:
#: 送りは「相手を削る」だけの行為だった。通れば自分も戻るようにすると
#: **攻めることが立て直しにもなる**。自分の上限は超えないので、
#: 一度も漏らしていない側が送りだけで太ることはない。
#: 災厄は周回するので、コアに触れるたびに戻る。
LEAK_LIFE_REWARD = 1


@dataclass(frozen=True)
class AttackerDef:
    """相手へ送るモンスター。体は :data:`ENEMIES` を流用する。"""

    name_jp: str
    body: str           # ENEMIES のキー
    cost: int           # コイン
    income_gain: int    # 送ると恒久的に増えるインカム
    stock_cost: int     # 消費ストック
    unlock_income: int  # これを送るのに必要なインカム
    hp: float           # 送られた個体の HP（EnemyDef.base_hp は使わない）

    def kill_reward(self, opponents: int) -> int:
        """撃破報酬（コイン）。

        :param opponents: 送り主以外の生存者数（＝この送りが湧いた島の数）

        総量 :data:`KILL_REWARD_TOTAL` を人数で割る。2 人なら 1 人が 40% を、
        8 人なら 7 人が 5.7% ずつ受け取る。**人数によらず総量が一定**なのが肝。
        """
        return max(1, round(self.cost * KILL_REWARD_TOTAL / max(1, opponents)))

    @property
    def income_ratio(self) -> float:
        """コスト 1 コインあたりのインカム増。上の段ほど下がる。"""
        return self.income_gain / self.cost if self.cost else 0.0


ATTACKERS: Dict[str, AttackerDef] = {
    # 値段は 1 段ごとにおよそ 1.45 倍（Hypixel と同じ刻み）、
    # インカム比率は 6.7% -> 2.2% へ逓減。
    # ストック消費は**どれも 1**（回数制限であって強さの値付けではない）。
    # 経緯は docs/VERSUS_ECONOMY_ja.md
    #                             body            cost  inc  stk  unlock      hp
    "WHELP":      AttackerDef("走狗",     "GRUNT", 15, 1, 1, 0, 50),
    "DASHER":     AttackerDef("疾走者",   "RUNNER", 22, 1, 1, 10, 80),
    "SAPPER":     AttackerDef("妨害者",   "SAPPER", 32, 2, 1, 20, 120),
    "STONEBACK":  AttackerDef("石背",     "BRUTE", 46, 2, 1, 20, 170),
    "SKIMMER":    AttackerDef("浮遊蟲",   "FLYER", 66, 3, 1, 30, 240),
    "PHASER":     AttackerDef("瞬移体",   "BLINKER", 96, 4, 1, 50, 350),
    "CHANTER":    AttackerDef("祈祷師",   "HEALER", 140, 6, 1, 70, 500),
    "CLEAVER":    AttackerDef("分裂体",   "SPLITTER", 200, 8, 1, 100, 720),
    "CINDER":     AttackerDef("熱塊",     "EMBERLING", 290, 11, 1, 140, 1040),
    "BULWARK":    AttackerDef("庇護者",   "AEGIS", 430, 15, 1, 220, 1550),
    # --- 上位種（能力は下位と同じ、体だけ太い）---
    "SWIFTBEAST": AttackerDef("疾風獣",   "SWIFTBEAST", 620, 20, 1, 310, 2230),
    "BREAKER":    AttackerDef("破城者",   "BREAKER", 890, 26, 1, 440, 3200),
    "IRONWALL":   AttackerDef("鉄壁",     "IRONWALL", 1300, 36, 1, 650, 4680),
    "CANOPY":     AttackerDef("天蓋",     "CANOPY", 1900, 48, 1, 950, 6840),
    "HIERARCH":   AttackerDef("大祭司",   "HIGHPRIEST", 2700, 64, 1, 1350, 9720),
    "RENDER":     AttackerDef("大分裂体", "GREATSPLITTER", 4000, 88, 1, 2000, 14400),
    # --- 削り切る用（インカムは増えない）---
    # 値段は最上位のインカムモブに対する倍率で置く。定額だと、指数で伸びる
    # 経済の中では必ず「タダ同然」になる。
    "CALAMITY":   AttackerDef("災厄",     "BOSS", 8000, 0, 1, 800, 64000),
    "REAPER":     AttackerDef("終焉騎",   "REAPER", 16000, 0, 1, 1600, 48000),
}

#: **コアまで通されたときにだけ**ライフ上限を奪う送り (Island#onEnemyLeaked)。
#:
#: かつては「倒したときに奪う」だった。倒しても上限が減るなら防衛に正解が存在せず、
#: タワーディフェンスとして成立しない。倒し切れば無傷／通せば取り返しがつかない、
#: という形に戻してある。
MAX_LIFE_STEALERS: Tuple[str, ...] = ("REAPER",)

#: 送りの厚みを揃える基準の相手人数（＝ 6 人ロビー）。(VersusMatch.REFERENCE_OPPONENTS)
#:
#: 送りは生存者全員に同時に飛ぶので、守る側が浴びる量は相手の人数に比例するのに、
#: 建てられる塔は 24 基で変わらない。放っておくと 2 人戦は守りが絶対に崩れず
#: （決着率 0%）、8 人戦は守りようがない、という形になる。
#: 撃破報酬と同じ原理で **1 回の送りが盤面に生む耐力の総量を人数によらず一定**にする。
#: 体数ではなく耐力で揃えるのは、体数で揃えると 2 人戦で毎秒 5 体湧くため。
REFERENCE_OPPONENTS = 5

#: 人数正規化の効かせ具合。1.0 で「浴びる耐力の総量」がぴったり揃う。
#: (VersusMatch.SEND_POWER_EXPONENT)
SEND_POWER_EXPONENT = 0.7


def send_power_scale(opponents: int) -> float:
    """送られたモンスターの耐力に掛かる倍率。 (VersusMatch#sendPowerScale)

    **平方根なのは実測でそうしないと合わなかったから。** 人数ぶんをそのまま
    掛けると効きすぎて、2 人戦が今度は送り側の勝率 100% になる。
    1 体を 5 倍太らせるのと 5 体送るのは等価ではなく（範囲攻撃・連鎖・燃焼は
    どれも体数に効く）、太った 1 体は同じ総耐力の 5 体よりずっと硬い。
    """
    return (REFERENCE_OPPONENTS / max(1, opponents)) ** SEND_POWER_EXPONENT

ATTACKER_ORDER: Tuple[str, ...] = tuple(ATTACKERS.keys())


# ════════════════════════════════════════════════════════════════════
# タワー  (tower/TowerKind.java)
# ════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class Effect:
    """ダメージ以外の効果（送還・呪詛・支援）。 (tower/Effect.java)"""

    banish_targets: float = 0.0
    vulnerability: float = 0.0
    vulnerability_ticks: int = 0
    boost_damage: float = 0.0
    boost_rate: float = 0.0
    # 監視塔の傘。射程内の塔が受ける妨害をこの割合だけ削る（1.0 で完全無効）
    disable_resist: float = 0.0

    def empty(self) -> bool:
        return (self.banish_targets <= 0 and self.vulnerability <= 0
                and self.boost_damage <= 0 and self.boost_rate <= 0
                and self.disable_resist <= 0)

    def plus(self, other: "Effect") -> "Effect":
        return Effect(
            self.banish_targets + other.banish_targets,
            self.vulnerability + other.vulnerability,
            max(self.vulnerability_ticks, other.vulnerability_ticks),
            self.boost_damage + other.boost_damage,
            self.boost_rate + other.boost_rate,
            min(1.0, self.disable_resist + other.disable_resist),
        )


NO_EFFECT = Effect()


@dataclass(frozen=True)
class TowerSpec:
    """最終段階（Lv3）で選ぶ 2 択の特化。"""

    name_jp: str
    damage_mul: float = 1.0
    range_add: float = 0.0
    cooldown_mul: float = 1.0
    splash_add: float = 0.0
    chain_add: int = 0
    slow_add: float = 0.0
    burn_add: float = 0.0
    burn_mul: float = 1.0
    effect_add: Effect = NO_EFFECT


@dataclass(frozen=True)
class TowerDef:
    name_jp: str
    shape: str
    element: str        # NONE / FIRE / ICE / ARC / VOID / HEX
    style: str          # SINGLE/SPLASH/CHAIN/PIERCE/AURA/BANISH/CURSE/SUPPORT
    targeting: str      # FIRST/UNAFFECTED/TOUGHEST/DENSEST/FARTHEST/NONE
    base_cost: int
    base_range: float
    base_cooldown: int  # tick
    base_damage: float
    splash_radius: float = 0.0
    chain_targets: int = 0
    slow_factor: float = 0.0
    slow_ticks: int = 0
    burn_dps: float = 0.0
    burn_ticks: int = 0
    effect: Effect = NO_EFFECT
    specs: Tuple[TowerSpec, ...] = ()


TOWERS: Dict[str, TowerDef] = {
    "ARROW": TowerDef(
        "弓塔", "DOT", "NONE", "SINGLE", "FIRST",
        base_cost=30, base_range=5.5, base_cooldown=12, base_damage=7.0,
        specs=(TowerSpec("狙撃", damage_mul=1.5, range_add=6.0, cooldown_mul=1.7),
               TowerSpec("連射", damage_mul=0.85, cooldown_mul=0.45))),
    "FROST": TowerDef(
        "氷塔", "DOT", "ICE", "SINGLE", "UNAFFECTED",
        base_cost=45, base_range=5.0, base_cooldown=18, base_damage=3.0,
        slow_factor=0.40, slow_ticks=45,
        specs=(TowerSpec("極低温", slow_add=0.25),
               TowerSpec("霜害", damage_mul=3.0))),
    "BRAZIER": TowerDef(
        "火炉", "I2", "FIRE", "AURA", "NONE",
        base_cost=60, base_range=3.6, base_cooldown=20, base_damage=0.0,
        burn_dps=5.0, burn_ticks=60,
        specs=(TowerSpec("業火", burn_mul=2.2),
               TowerSpec("灼熱地帯", range_add=3.0))),
    "CANNON": TowerDef(
        "砲塔", "O", "FIRE", "SPLASH", "TOUGHEST",
        base_cost=110, base_range=6.5, base_cooldown=40, base_damage=26.0,
        splash_radius=2.2,
        specs=(TowerSpec("大口径", damage_mul=1.3, splash_add=2.0),
               TowerSpec("焼夷", burn_add=14.0))),
    "TESLA": TowerDef(
        "雷塔", "O", "ARC", "CHAIN", "DENSEST",
        base_cost=130, base_range=5.0, base_cooldown=22, base_damage=11.0,
        chain_targets=3,
        specs=(TowerSpec("拡散", damage_mul=0.8, chain_add=3),
               TowerSpec("過負荷", damage_mul=2.0, chain_add=-2))),
    "BALLISTA": TowerDef(
        "弩塔", "I3", "NONE", "PIERCE", "FARTHEST",
        base_cost=100, base_range=12.0, base_cooldown=50, base_damage=30.0,
        chain_targets=3,
        specs=(TowerSpec("長距離", range_add=8.0),
               TowerSpec("貫通強化", damage_mul=1.2, chain_add=3))),
    "BANISHER": TowerDef(
        "送還塔", "DOT", "VOID", "BANISH", "FIRST",
        base_cost=75, base_range=5.5, base_cooldown=1200, base_damage=5.0,
        effect=Effect(banish_targets=1),
        specs=(TowerSpec("一斉送還", effect_add=Effect(banish_targets=2)),
               TowerSpec("連続送還", cooldown_mul=0.5))),
    "HEXER": TowerDef(
        "呪詛塔", "I2", "HEX", "CURSE", "NONE",
        base_cost=95, base_range=6.0, base_cooldown=30, base_damage=0.0,
        effect=Effect(vulnerability=0.35, vulnerability_ticks=60),
        specs=(TowerSpec("深き呪い", effect_add=Effect(vulnerability=0.30)),
               TowerSpec("広域呪詛", range_add=5.0))),
    "WATCHTOWER": TowerDef(
        "監視塔", "O", "NONE", "SUPPORT", "NONE",
        base_cost=120, base_range=5.0, base_cooldown=40, base_damage=0.0,
        effect=Effect(boost_damage=0.30, boost_rate=0.15, disable_resist=0.5),
        specs=(TowerSpec("号令", effect_add=Effect(boost_damage=0.30,
                                                 disable_resist=0.5)),
               TowerSpec("展望", range_add=4.0,
                         effect_add=Effect(boost_rate=0.15,
                                           disable_resist=0.5)))),
}

TOWER_ORDER: Tuple[str, ...] = tuple(TOWERS.keys())

#: 強化の最大段階。**対戦は 5 段** (TowerKind.VERSUS_MAX_LEVEL)。
#:
#: 置ける塔は 24 基で頭打ちなので、指数で伸びるコインの受け皿は
#: 「上へ伸ばす」しかない。3 段・等差のままだと盤面を完全に埋めても
#: 総額 2 万コインで終わり、それ以降のコインが行き場を失う
#: （実際に 8 人戦では 18 分で 10 万コインが死蔵されていた）。
#: シングルは TowerKind.MAX_LEVEL = 3 のままで、この環境は使わない。
MAX_TOWER_LEVEL = 5
#: 売却時の返金率 (TowerInstance#sellValue)
SELL_REFUND = 0.6

# --- 強化の伸び方 (TowerKind#statsAt / #upgradeCost) -----------------
#
# **費用は 2.6 倍ずつ、火力は 1.384 倍ずつ。**
# 強化するほどコインあたりの火力は割高になる（2.6 払って 1.384 しか伸びない）。
# それでも上げるのは置ける数に上限があるからで、
# 「金を火力に変える交換レートが、豊かになるほど悪くなる」ことが
# そのまま雪だるまの抑制になっている。Hypixel TowerWars と同じ構造。
#
# 火力の指数 1.384 は、旧式（1 + 0.55 * level）とレベル 3 で一致する値
# （1.384^3 = 2.65）。シングルの体感を変えずに 4・5 段の伸びしろだけを足すため。
UPGRADE_COST_SCALE = 0.9       # cost = base_cost * 0.9 * 2.6 ** level
UPGRADE_COST_GROWTH = 2.6
LEVEL_DAMAGE_GROWTH = 1.384    # levelMul = 1.384 ** level
LEVEL_RANGE_STEP = 0.6
LEVEL_COOLDOWN_MUL = 0.88      # cooldown *= 0.88 ** level
LEVEL_SPLASH_STEP = 0.25
LEVEL_SLOW_STEP = 0.05
SLOW_CAP = 0.75                # 特化前の上限
SLOW_CAP_SPEC = 0.85           # 特化後の上限
MIN_COOLDOWN = 2

TICKS_PER_SECOND = 20


@dataclass(frozen=True)
class TowerStats:
    """レベルと特化を解決した実効性能。戦闘計算は必ずここを通る。"""

    damage: float
    range: float
    cooldown: int
    splash_radius: float
    chain_targets: int
    slow_factor: float
    slow_ticks: int
    burn_dps: float
    burn_ticks: int
    effect: Effect

    @property
    def dps(self) -> float:
        return self.damage * TICKS_PER_SECOND / max(1, self.cooldown)


def _stats_with(d: TowerDef, level: int, spec: Optional[int]) -> TowerStats:
    """TowerKind#statsAt の移植。任意の :class:`TowerDef` に対して働く
    （ドメインランダム化で差し替えた定義にも使えるようにするため）。"""
    level_mul = LEVEL_DAMAGE_GROWTH ** level
    damage = d.base_damage * level_mul
    rng = d.base_range + LEVEL_RANGE_STEP * level
    cooldown = max(MIN_COOLDOWN, round(d.base_cooldown * (LEVEL_COOLDOWN_MUL ** level)))
    splash = d.splash_radius + LEVEL_SPLASH_STEP * level
    chain = d.chain_targets + (1 if level >= 2 else 0) + (1 if level >= 4 else 0)
    slow = min(SLOW_CAP, d.slow_factor + LEVEL_SLOW_STEP * level) if d.slow_factor > 0 else 0.0
    burn = d.burn_dps * level_mul
    burn_for = d.burn_ticks

    # 支援・妨害の効果もレベルで伸びる。ただし送り返す「数」は伸ばさない
    # （1 回の重さが変わらないほうが、いつ撃たせるかの読み合いが素直に効く）
    if d.effect.empty():
        resolved = d.effect
    else:
        # 妨害の軽減率もレベルでは伸ばさない
        # （伸ばすと特化の前に 100% へ届き、「半減 → 特化で無効」の段取りが消える）
        resolved = Effect(d.effect.banish_targets,
                          d.effect.vulnerability * level_mul,
                          d.effect.vulnerability_ticks,
                          d.effect.boost_damage * level_mul,
                          d.effect.boost_rate * level_mul,
                          d.effect.disable_resist)

    if spec is not None and d.specs:
        s = d.specs[spec]
        damage *= s.damage_mul
        rng += s.range_add
        cooldown = max(MIN_COOLDOWN, round(cooldown * s.cooldown_mul))
        splash += s.splash_add
        chain = max(1, chain + s.chain_add)
        slow = min(SLOW_CAP_SPEC, slow + s.slow_add) if slow > 0 else slow
        burn = burn * s.burn_mul + s.burn_add
        if burn > 0 and burn_for <= 0:
            burn_for = 60
        resolved = resolved.plus(s.effect_add)

    return TowerStats(damage, rng, cooldown, splash, chain, slow,
                      d.slow_ticks, burn, burn_for, resolved)


def stats_at(tower: str, level: int, spec: Optional[int] = None) -> TowerStats:
    """既定バランスでの実効性能。"""
    return _stats_with(TOWERS[tower], level, spec)


def upgrade_cost(tower: str, level: int) -> int:
    """レベル ``level`` から ``level+1`` へ上げる費用。 (TowerKind#upgradeCost)"""
    base = TOWERS[tower].base_cost
    return round(base * UPGRADE_COST_SCALE * UPGRADE_COST_GROWTH ** level)


def total_invested(tower: str, level: int) -> int:
    """Lv0 から ``level`` まで上げたときの累計投資（売却額の元）。"""
    total = TOWERS[tower].base_cost
    for lv in range(level):
        total += upgrade_cost(tower, lv)
    return total


# ════════════════════════════════════════════════════════════════════
# 戦闘の定数  (stage/Battlefield.java)
# ════════════════════════════════════════════════════════════════════
CHAIN_RADIUS = 3.6          # 連鎖が次の敵へ飛べる距離
CHAIN_FALLOFF = 0.78        # 連鎖するたびダメージに掛かる係数
SPLASH_FALLOFF = 0.6        # 巻き込まれた敵へのダメージ倍率
PIERCE_WIDTH = 1.3          # 貫通線の太さ
PIERCE_FALLOFF = 0.75       # 貫通の 2 体目以降のダメージ倍率
CROWD_RADIUS = 3.0          # 「密集」と見なす半径（DENSEST 狙いに使う）
SUPPORT_RATE_CAP = 0.6      # 監視塔によるクールダウン短縮の上限

MIN_DAMAGE_AFTER_ARMOR = 1.0   # armor を引いた後の下限
MIN_DAMAGE_APPLIED = 0.5       # 呪詛・庇護まで通した後の下限
BLINK_SCAN_STEP = 0.5          # 瞬移先を探すときの経路の刻み


# ════════════════════════════════════════════════════════════════════
# 経済とルール  (versus/VersusPlayer.java, VersusMatch.java, Island.java)
# ════════════════════════════════════════════════════════════════════
@dataclass
class Economy:
    """対戦の経済パラメータ。**ドメインランダム化はこれを複製して揺らす。**"""

    start_coins: int = 100
    start_income: int = 5
    start_lives: int = 20
    max_stock: int = 30
    max_towers: int = 24                 # Island.MAX_TOWERS

    prep_ticks: int = 20 * 60            # 準備時間（この間は送れない）
    income_interval: int = 20 * 10       # 収入間隔。**人数によらず一定**
    stock_interval: int = 20             # ストックが 1 回復する間隔
    sudden_death_ticks: int = 20 * 60 * 15
    sudden_death_leak: int = 2           # サドンデス後のリークダメージ
    normal_leak: int = 1
    reaper_max_life_steal: int = 1       # 終焉騎を**通した**ときに奪うライフ上限

    hand_limit: int = 6                  # VersusMatch.HAND_LIMIT
    card_interval: int = 20 * 30         # カードが 1 枚届く間隔
    start_hand: int = 5                  # VersusMatch.START_HAND

    def income_interval_for(self, alive: int) -> int:
        """収入間隔。 (VersusMatch#incomeInterval)

        **人数によらず一定**。かつては少人数ほど速くしていた
        （届く敵が少なく撃破報酬が細るぶんの補填）が、
        **この補正は指数の肩に乗る**。インカムは「収入間隔ぶんの一」の速さで
        自己増殖するので、間隔を半分にすると成長率がそのまま倍になり、
        20 分後には桁違いの差になる。少人数の不利は
        :data:`KILL_REWARD_TOTAL`（撃破報酬の総量を人数で割って一定にする）
        側で埋めてある。
        """
        return self.income_interval


ECONOMY = Economy()


# ════════════════════════════════════════════════════════════════════
# 盤面生成  (stage/StageGenerator.java)
# ════════════════════════════════════════════════════════════════════
@dataclass
class BoardConfig:
    """島の盤面。対戦は全員 **同一シードの同じ地形** で始まる。

    既定値は Java の ``StageGenerator.generate(2, BATTLE, seed)`` と同じ
    （層 2 → 21x21・スポーン 1・岩の予算 5.3%）。カリキュラム学習では
    ``size`` を小さくして使う。
    """

    size: int = 21
    spawns: int = 1
    rock_ratio: float = 0.053           # 0.045 + 0.008 * min(4, layer-1), layer=2
    min_separation_ratio: float = 0.48  # スポーンとコアの最低距離 / 盤面サイズ
    min_open_ratio: float = 0.78        # 迷路を組む余地


BOARD = BoardConfig()

#: 学習で扱う盤面の最大サイズ。観測テンソルの一辺はこれで固定する
MAX_BOARD = 27
#: 1 つの島に同時に存在できる敵の上限。**実ゲームには無い制限**で、
#: 配列を固定長で確保するためだけのもの（観測は敵ごとのスロットではなく
#: ヒートマップなので、ここを増やしても観測の形は変わらない）。
#:
#: 上限に当たると湧きが**黙って捨てられる**。送り手はコインとストックを
#: 払った損だけが残り、捨てられた敵は漏れないので**守りが不当に強く見える**。
#: 実測（ボット対戦・8 島）::
#:
#:     2 人戦   同時最大 28 体   上限に触れない   速度 3700 board-steps/s
#:     8 人戦   上限 48 で頭打ち                  速度 1415
#:              上限 96 で最大 96 まで伸びる      速度 1162（-18%）
#:              上限 160 で最大 129               速度 1049（-26%）
#:
#: 2 人戦は 48 でも触れないが、8 人戦は明らかに頭打ちになっていたので 96 にした。
#: 遅くなるのは多人数のときだけで、2 人戦の速度は変わらない。
#: **まとめ送りを入れたので 96 → 256 に上げた。** 1 回の行動で
#: ストックぶん（最大 30 体）まとめて送れるようになったので、
#: 4 人戦で相手 3 人が同時に撃つと 90 体が一度に届く。96 のままだと
#: 上限に当たったぶんが黙って捨てられ、守りが不当に強く見える。
#: 戦闘は ``en_count.max()`` の幅でしか回さないので、
#: **枠を広げても実際に敵がいないときのコストは増えない**（確保するメモリだけ）。
MAX_ENEMIES = 256
#: 対戦人数の上限（観測の相手スロット数）
MAX_PLAYERS = 8

#: 1 回の行動でまとめて送れる上限 (VersusMatch.MAX_SEND_BATCH)。
#: ストック上限と同じにしてある（ストックが実際の上限なので、
#: これ以上を選べるようにしても意味がない）。
MAX_SEND_BATCH = ECONOMY.max_stock

#: まとめ送りしたときに、1 体ずつずらして湧かせる間隔（tick）。
#: (VersusMatch.SEND_STAGGER_TICKS)
#:
#: **同座標に一度に湧かせてはいけない。** 範囲攻撃と連鎖が 1 塊に当たるので、
#: 実際より柔らかく（単体火力には硬く）なる。人間が送りメニューを連打しても
#: 1 体ずつ間が空くので、そちらに合わせる。
#: 環境の戦闘刻み ``COMBAT_DT`` = 4 tick と同じにしてあるので、
#: 戦闘サブステップ 1 回につき 1 体ずつ出る。
SEND_STAGGER_TICKS = 4
MIN_PLAYERS = 2


# ════════════════════════════════════════════════════════════════════
# ドメインランダム化のホワイトリスト
# ════════════════════════════════════════════════════════════════════
# 「どの数値を揺らしてよいか」を明示的に列挙する。ここに無いものは絶対に
# 揺らさない。行動空間の形（塔の種類数・送りの種類数・盤面の最大サイズ）が
# 変わるとネットワークの入出力が変わり、**同じ方策で扱えなくなる**ため。
#
# 揺らす目的は「特定の数値を暗記した方策」を作らないこと。観測を比率で
# 与えていること（observation.py）と対になっていて、両方そろって初めて
# 「バランスを変えても再学習なしである程度動く」が成り立つ。
RANDOMIZABLE = (
    "tower.base_cost", "tower.base_range", "tower.base_cooldown",
    "tower.base_damage", "tower.splash_radius", "tower.slow_factor",
    "tower.burn_dps",
    "attacker.cost", "attacker.income_gain", "attacker.hp",
    "attacker.unlock_income",
    "enemy.base_speed", "enemy.armor",
    "economy.start_coins", "economy.start_income",
    "economy.income_interval", "economy.card_interval",
)


@dataclass
class Balance:
    """1 エピソードぶんの数値一式。ドメインランダム化はこれを複製して揺らす。

    環境はこのオブジェクト経由でしか数値を読まない。**モジュール変数を直接
    参照しない**ので、並列環境ごとに違うバランスで回せる。
    """

    towers: Dict[str, TowerDef] = field(default_factory=lambda: dict(TOWERS))
    attackers: Dict[str, AttackerDef] = field(default_factory=lambda: dict(ATTACKERS))
    enemies: Dict[str, EnemyDef] = field(default_factory=lambda: dict(ENEMIES))
    economy: Economy = field(default_factory=lambda: replace(ECONOMY))
    board: BoardConfig = field(default_factory=lambda: replace(BOARD))

    def tower_stats(self, tower: str, level: int,
                    spec: Optional[int] = None) -> TowerStats:
        return _stats_with(self.towers[tower], level, spec)

    def upgrade_cost(self, tower: str, level: int) -> int:
        base = self.towers[tower].base_cost
        return round(base * UPGRADE_COST_SCALE * UPGRADE_COST_GROWTH ** level)

    def kill_reward(self, attacker: str, opponents: int) -> int:
        return self.attackers[attacker].kill_reward(opponents)

    def fingerprint(self) -> str:
        """数値が変わったことを検知するための短い指紋。学習ログに残す。"""
        import hashlib
        parts: List[str] = []
        for k in TOWER_ORDER:
            t = self.towers[k]
            parts.append(f"{k}:{t.base_cost}:{t.base_damage:.4f}:{t.base_range:.4f}:"
                         f"{t.base_cooldown}:{t.splash_radius:.3f}:"
                         f"{t.slow_factor:.3f}:{t.burn_dps:.3f}")
        for k in ATTACKER_ORDER:
            a = self.attackers[k]
            parts.append(f"{k}:{a.cost}:{a.income_gain}:{a.stock_cost}:"
                         f"{a.unlock_income}:{a.hp:.2f}")
        for k in sorted(self.enemies):
            e = self.enemies[k]
            parts.append(f"{k}:{e.base_speed:.5f}:{e.armor:.2f}:{e.slow_resist:.2f}")
        ec = self.economy
        parts.append(f"eco:{ec.start_coins}:{ec.start_income}:{ec.start_lives}:"
                     f"{ec.max_stock}:{ec.max_towers}:{ec.income_interval}:"
                     f"{ec.card_interval}:{ec.prep_ticks}:{ec.sudden_death_ticks}")
        parts.append(f"board:{self.board.size}:{self.board.spawns}:"
                     f"{self.board.rock_ratio:.4f}")
        return hashlib.sha1("|".join(parts).encode()).hexdigest()[:12]


def default_balance() -> Balance:
    """このファイルの値そのままのバランス。"""
    return Balance()


def randomized_balance(rng, strength: float = 0.20) -> Balance:
    """±``strength`` の範囲でスケールを掛けたバランスを作る。

    :data:`RANDOMIZABLE` に載っている項目だけを揺らす。整数の項目は丸めて
    最低値でクランプする（コスト 0 の塔などを作らないため）。
    """
    b = Balance()
    if strength <= 0:
        return b

    def jitter() -> float:
        return 1.0 + rng.uniform(-strength, strength)

    b.towers = {
        key: replace(
            t,
            base_cost=max(5, round(t.base_cost * jitter())),
            base_range=max(1.5, t.base_range * jitter()),
            base_cooldown=max(MIN_COOLDOWN, round(t.base_cooldown * jitter())),
            base_damage=t.base_damage * jitter(),
            splash_radius=t.splash_radius * jitter(),
            slow_factor=min(0.9, t.slow_factor * jitter()),
            burn_dps=t.burn_dps * jitter(),
        )
        for key, t in b.towers.items()
    }
    b.attackers = {
        key: replace(
            a,
            cost=max(5, round(a.cost * jitter())),
            income_gain=(0 if a.income_gain == 0
                         else max(1, round(a.income_gain * jitter()))),
            hp=max(5.0, a.hp * jitter()),
            unlock_income=(0 if a.unlock_income == 0
                           else max(1, round(a.unlock_income * jitter()))),
        )
        for key, a in b.attackers.items()
    }
    b.enemies = {
        key: replace(e,
                     base_speed=max(0.01, e.base_speed * jitter()),
                     armor=e.armor * jitter())
        for key, e in b.enemies.items()
    }

    ec = b.economy
    ec.start_income = max(1, round(ec.start_income * jitter()))
    ec.start_coins = max(20, round(ec.start_coins * jitter()))
    ec.income_interval = max(40, round(ec.income_interval * jitter()))
    ec.card_interval = max(60, round(ec.card_interval * jitter()))
    return b


__all__ = [
    "SHAPE_CELLS", "STARTER_DECK", "Trait", "NO_TRAIT", "EnemyDef", "ENEMIES",
    "AttackerDef", "ATTACKERS", "ATTACKER_ORDER", "Effect", "NO_EFFECT",
    "TowerSpec", "TowerDef", "TOWERS", "TOWER_ORDER", "TowerStats",
    "stats_at", "upgrade_cost", "total_invested", "Economy", "ECONOMY",
    "BoardConfig", "BOARD", "Balance", "default_balance", "randomized_balance",
    "RANDOMIZABLE", "MAX_BOARD", "MAX_ENEMIES", "MAX_PLAYERS", "MIN_PLAYERS",
    "MAX_TOWER_LEVEL", "SELL_REFUND", "TICKS_PER_SECOND", "KILL_REWARD_TOTAL",
    "KILL_INCOME_RATIO", "DISABLE_REFRESH_TICKS", "HEAL_AURA_RADIUS",
    "HEAL_INTERVAL", "WARD_TICKS", "SPLIT_HP_RATIO", "SPLIT_REWARD_DIVISOR", "MAX_LIFE_STEALERS",
    "REFERENCE_OPPONENTS", "SEND_POWER_EXPONENT", "send_power_scale",
    "MAX_SEND_BATCH", "SEND_STAGGER_TICKS",
    "CHAIN_RADIUS", "CHAIN_FALLOFF", "SPLASH_FALLOFF", "PIERCE_WIDTH",
    "PIERCE_FALLOFF", "CROWD_RADIUS", "SUPPORT_RATE_CAP",
    "MIN_DAMAGE_AFTER_ARMOR", "MIN_DAMAGE_APPLIED", "BLINK_SCAN_STEP",
    "MIN_COOLDOWN", "UPGRADE_COST_SCALE", "UPGRADE_COST_GROWTH",
    "LEVEL_DAMAGE_GROWTH", "LEVEL_RANGE_STEP", "LEVEL_COOLDOWN_MUL",
    "LEVEL_SPLASH_STEP", "LEVEL_SLOW_STEP", "SLOW_CAP", "SLOW_CAP_SPEC",
]
