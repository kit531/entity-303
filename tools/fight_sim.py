"""Monte-Carlo model of Entity 303 against N fully geared players:  python tools/fight_sim.py [players ...]

The aim (asked for by the user): 4 fully enchanted players in diamond armor with Protection III who eat golden apples
should only just win or lose, 5 should win for sure. The model is a simplification of the real fight, but it uses the
numbers of the mod: the attack table and phase multipliers of Entity303AttackGoal, the per-player burst budget
(Entity303.BURST_CAP / BURST_WINDOW_TICKS), his health, armor, crowd toughness and life steal.

Change the constants of the BOSS section to the ones in the Java code and re-run to see what a change does.
"""
import random
import sys

# ----------------------------------------------------------------------- BOSS ---
BOSS_HP = 1500.0
BOSS_ARMOR = 16.0                     # Entity303.ARMOR
BOSS_TOUGHNESS = 0.0                  # armor toughness attribute (0 in the mod so far)
BOSS_TAKES = 0.22                     # Entity303.DAMAGE_TAKEN_SMALL_GROUP: share of the damage that reaches him, up to 4 players
BOSS_TAKES_5 = 0.40                   # Entity303.DAMAGE_TAKEN_BIG_GROUP: with 5 or more players
TIME_LIMIT = 3600.0                   # seconds: a fight that lasts longer counts as the boss holding out
PAUSE_SCALE = 0.5                     # Entity303.PACE: < 1 = shorter pauses between his attacks
PHASE_AT = (0.66, 0.33)               # health ratios of phase 2 and 3
DAMAGE_MULT = {1: 2.0, 2: 2.5, 3: 4.5}   # DAMAGE_SCALE x (1 / 1.25 / 1.5 x FINAL_DAMAGE_BONUS 1.5)
CROWD_DAMAGE = 0.05                   # CROWD_DAMAGE_PER_PLAYER
CROWD_TOUGHNESS, CROWD_TOUGHNESS_MAX = 0.0, 0.0   # replaced by BOSS_TAKES / BOSS_TAKES_5
CROWD_CAP = 8
BURST_CAP = {1: 8.0, 2: 12.0, 3: 16.0}   # HP per player per window, after mitigation
WINDOW_S = 60 / 20.0                  # Entity303.BURST_WINDOW_TICKS
LIFESTEAL = 0.5                       # phase 3 only
HEAL_PER_S, HEAL_BURST = 0.4 * 20, 30.0
COOLDOWN_SCALE = {1: 1.0, 2: 0.85, 3: 0.7}   # Entity303#cooldownScale (approximation)
# (name, duration ticks, base pause ticks, raw hits (damage each), share of the players hit, min phase)
ATTACKS = [
    ("sweep", 32, 14, [14.0], 0.45, 1), ("slash", 44, 26, [11.0], 0.80, 1), ("combo", 40, 16, [10.0, 13.0], 0.45, 1),
    ("lunge", 34, 18, [19.0], 0.35, 1), ("leap", 52, 36, [17.0], 0.60, 1), ("hook", 24, 6, [6.0], 0.45, 1),
    ("guard", 56, 30, [9.0], 0.60, 1), ("drain", 60, 50, [3.0, 3.0, 3.0, 3.0], 0.50, 1),
    ("slam", 66, 44, [17.0], 0.70, 2), ("rings", 70, 40, [6.0, 6.0, 6.0], 0.60, 2), ("dance", 64, 36, [13.0, 13.0, 13.0], 0.50, 2),
    ("rain", 70, 44, [12.0], 0.80, 2), ("summon", 60, 70, [], 0.0, 1), ("step", 20, 2, [], 0.0, 1),
]
DESCENT = (104, 80, 0.5, 0.65)        # duration, pause, share of max health in phase 2 / 3, (phase 3 value)

# --------------------------------------------------------------------- PLAYER ---
ARMOR, TOUGHNESS, EPF = 20.0, 8.0, 12.0
PLAYER_HP = 20.0
SWORD_HIT = 11.0                      # netherite sword + Sharpness V
HITS_PER_S = 1.6
CRIT = 1.3                            # average crit multiplier
UPTIME = 0.60                         # share of the time a player actually connects (boss moves, teleports, knockback)
FIRE_DPS = 1.0                        # Fire Aspect, per player
EAT_BELOW = 17.0                      # effective HP below which a player starts eating (stays ahead of the next burst)
REACTION_S = 0.6
EAT_S = 1.6
APPLE_ABSORB = 4.0
REGEN_II_HPS = 0.8
NATURAL_HPS = 1.6                     # natural regeneration while the hunger bar is full (80 % of 2 HP/s)
DT = 0.1


def armor_reduction(damage, armor, toughness):
    defense = min(20.0, max(armor / 5.0, armor - damage / (2.0 + toughness / 4.0)))
    return damage * (1.0 - defense / 25.0)


def player_effective(raw):
    return armor_reduction(raw, ARMOR, TOUGHNESS) * (1.0 - min(20.0, EPF) * 0.04)


APPLES = 64                           # golden apples per player: one stack
class Player:
    def __init__(self, rng=None):
        r = rng or random
        self.apples = APPLES
        self.eat_below = EAT_BELOW + r.uniform(-4.0, 1.0)          # skill: some wait too long
        self.reaction = REACTION_S * r.uniform(0.6, 1.6)
        self.uptime = UPTIME * r.uniform(0.8, 1.15)
        self.hp = PLAYER_HP
        self.abs = 0.0
        self.eating = 0.0          # seconds of eating left
        self.react = 0.0
        self.regen_left = 0.0
        self.dead = False
        self.window_start = -1e9
        self.spent = 0.0

    @property
    def effective(self):
        return self.hp + self.abs


def simulate(n_players, rng):
    boss = BOSS_HP
    players = [Player(rng) for _ in range(n_players)]
    t = 0.0
    next_attack = 2.0
    heal_budget = HEAL_BURST
    pending_descent = rng.uniform(15.0, 30.0)
    while t < TIME_LIMIT:
        alive = [p for p in players if not p.dead]
        if not alive:
            return False, t
        ratio = boss / BOSS_HP
        phase = 3 if ratio <= PHASE_AT[1] else 2 if ratio <= PHASE_AT[0] else 1
        extra = min(len(alive), CROWD_CAP) - 1

        # ------------------------------------------------------------ players
        toughness = 1.0 / (1.0 + min(CROWD_TOUGHNESS_MAX, CROWD_TOUGHNESS * extra))
        for p in alive:
            if p.regen_left > 0:
                p.hp = min(PLAYER_HP, p.hp + REGEN_II_HPS * DT)
                p.regen_left -= DT
            p.hp = min(PLAYER_HP, p.hp + NATURAL_HPS * DT)
            if p.eating > 0:
                p.eating -= DT
                if p.eating <= 0:
                    p.abs = max(p.abs, APPLE_ABSORB)
                    p.regen_left = 5.0
                continue
            if p.effective < p.eat_below and p.apples > 0:
                p.react += DT
                if p.react >= p.reaction:
                    p.eating, p.react = EAT_S, 0.0
                    p.apples -= 1
                    continue
            else:
                p.react = 0.0
            hit = armor_reduction(SWORD_HIT * CRIT, BOSS_ARMOR, BOSS_TOUGHNESS)
            boss -= (hit * HITS_PER_S * p.uptime + FIRE_DPS) * toughness * (BOSS_TAKES_5 if (BOSS_TAKES_5 is not None and len(alive) >= 5) else BOSS_TAKES) * DT
        if boss <= 0:
            return True, t

        # --------------------------------------------------------------- boss
        if t >= next_attack:
            pressure = 1.0 / (1.0 + 0.12 * min(len(alive) - 1, 6))
            if phase >= 2 and t >= pending_descent and rng.random() < 0.6:
                dur, pause, f2, f3 = DESCENT
                share = f3 if phase == 3 else f2
                targets = [p for p in alive if rng.random() < 0.75]
                for p in targets:
                    dmg = limit(p, share * PLAYER_HP, True, phase, t)
                    take(p, dmg)
                    if phase == 3:
                        heal_budget, boss = lifesteal(dmg, heal_budget, boss)
                pending_descent = t + rng.uniform(18.0, 32.0)
                next_attack = t + (dur + pause * PAUSE_SCALE * COOLDOWN_SCALE[phase] * pressure) / 20.0
            else:
                pool = [a for a in ATTACKS if a[5] <= phase]
                name, dur, pause, hits, share, _ = rng.choice(pool)
                mult = DAMAGE_MULT[phase] * (1.0 + CROWD_DAMAGE * extra)
                victims = [p for p in alive if rng.random() < max(share, 1.0 / len(alive))] if hits else []
                for p in victims:
                    for raw in hits:
                        dmg = limit(p, raw * mult, False, phase, t)
                        take(p, dmg)
                        if phase == 3:
                            heal_budget, boss = lifesteal(dmg, heal_budget, boss)
                next_attack = t + (dur + pause * PAUSE_SCALE * COOLDOWN_SCALE[phase] * pressure) / 20.0
        heal_budget = min(HEAL_BURST, heal_budget + HEAL_PER_S * DT)
        t += DT
    return False, t     # nobody won in ten minutes: counts as the boss holding out


def limit(p, raw, true_damage, phase, t):
    """The damage budget of Entity303#limitBurst: returns the damage that actually reaches the player (effective HP)."""
    eff = raw if true_damage else player_effective(raw)
    if t - p.window_start >= WINDOW_S:
        p.window_start, p.spent = t, 0.0
    room = BURST_CAP[phase] - p.spent
    if room < 0.5:
        return 0.0
    dmg = min(eff, room)
    p.spent += dmg
    return dmg


def take(p, dmg):
    if dmg <= 0:
        return
    soak = min(p.abs, dmg)
    p.abs -= soak
    p.hp -= dmg - soak
    if p.hp <= 0:
        p.dead = True


def lifesteal(dmg, budget, boss):
    give = min(dmg * LIFESTEAL, budget)
    return budget - give, boss + give


def apply_overrides(args):
    """caps=8,12,16  window=5  hp=1500  apples=64  crowd=0.1  steal=0.5  -> module constants; returns the plain args."""
    global BURST_CAP, WINDOW_S, BOSS_HP, APPLES, CROWD_DAMAGE, LIFESTEAL, BOSS_ARMOR, BOSS_TOUGHNESS, CROWD_TOUGHNESS, CROWD_TOUGHNESS_MAX, BOSS_TAKES, BOSS_TAKES_5, TIME_LIMIT, PAUSE_SCALE
    rest = []
    for a in args:
        if "=" not in a:
            rest.append(a)
            continue
        k, v = a.split("=", 1)
        if k == "caps":
            c = [float(x) for x in v.split(",")]
            BURST_CAP = {1: c[0], 2: c[1], 3: c[2]}
        elif k == "window":
            WINDOW_S = float(v)
        elif k == "hp":
            BOSS_HP = float(v)
        elif k == "apples":
            APPLES = int(v)
        elif k == "crowd":
            CROWD_DAMAGE = float(v)
        elif k == "steal":
            LIFESTEAL = float(v)
        elif k == "takes5":
            BOSS_TAKES_5 = float(v)
        elif k == "pause":
            PAUSE_SCALE = float(v)
        elif k == "takes":
            BOSS_TAKES = float(v)
        elif k == "limit":
            TIME_LIMIT = float(v)
        elif k == "armor":
            BOSS_ARMOR = float(v)
        elif k == "tough":
            BOSS_TOUGHNESS = float(v)
        elif k == "ctough":
            CROWD_TOUGHNESS, CROWD_TOUGHNESS_MAX = float(v.split(",")[0]), float(v.split(",")[1])
    return rest


def main():
    sizes = [int(a) for a in apply_overrides(sys.argv[1:])] or [2, 3, 4, 5, 6, 8]
    runs = 400
    rng = random.Random(303)
    print(f"boss {BOSS_HP:.0f} HP, caps {BURST_CAP}, window {WINDOW_S:.0f} s; players eat below {EAT_BELOW:.0f} HP")
    print(f"{'players':>7} {'players win':>12} {'median fight s':>15}")
    for n in sizes:
        wins, times = 0, []
        for _ in range(runs):
            won, t = simulate(n, rng)
            wins += won
            times.append(t)
        times.sort()
        print(f"{n:7d} {wins / runs:11.0%} {times[len(times) // 2]:15.0f}")


if __name__ == "__main__":
    main()
