"""The numbers behind Entity303.BURST_CAP / BURST_WINDOW_TICKS:  python tools/balance_check.py

Goal: a player in full diamond armor with Protection III on every piece who eats golden apples without a pause must not
be killable by Entity 303 in any phase. A single hit may take up to 8 hearts in the final form, after which that player
is left alone for the rest of the window (3 seconds). The sustained rate then equals what a non-stop eater heals
(golden apple + natural regeneration), so such a player is safe; tools/fight_sim.py fits the rest of the fight.

Vanilla formulas (Minecraft 1.21):
  armor:      defense = clamp(armor - damage / (2 + toughness / 4), armor / 5, 20);   damage *= 1 - defense / 25
  protection: EPF = 3 per Protection III piece (4 pieces = 12, capped at 20);         damage *= 1 - EPF * 0.04
Healing of somebody who eats golden apples back to back (an apple takes 32 ticks = 1.6 s to eat):
  absorption: 4 HP per apple (2 hearts) / 16 HP per enchanted apple (8 hearts)
  Regeneration II: 1 HP every 25 ticks = 0.8 HP/s (re-eating restarts it, it does not stack)
  natural regeneration while the hunger bar is full: 1 HP every 10 ticks = 2 HP/s (counted below)
"""
ARMOR, TOUGHNESS, EPF = 20.0, 8.0, 12.0     # full diamond, Protection III x 4
CAPS = {1: 8.0, 2: 12.0, 3: 16.0}           # Entity303.BURST_CAP: HP a player can lose per window, after mitigation
WINDOW_TICKS = 60                           # Entity303.BURST_WINDOW_TICKS
WINDOW_S = WINDOW_TICKS / 20.0

# damage multiplier of the attacks: DAMAGE_SCALE 2.0 x phase bonus (1.0 / 1.25 / 1.5 x FINAL_DAMAGE_BONUS 1.5)
MULT = {1: 2.0, 2: 1.25 * 2.0, 3: 1.5 * 1.5 * 2.0}
EAT_S = 1.6
REGEN_II_HPS = 20.0 / 25.0


def after_armor(d):
    defense = min(20.0, max(ARMOR / 5.0, ARMOR - d / (2.0 + TOUGHNESS / 4.0)))
    return d * (1.0 - defense / 25.0)


def effective(d):
    return after_armor(d) * (1.0 - min(20.0, EPF) * 0.04)


def heal_rate(absorption_hp):
    return absorption_hp / EAT_S + REGEN_II_HPS


if __name__ == "__main__":
    print(f"defender: armor {ARMOR:.0f}, toughness {TOUGHNESS:.0f}, EPF {EPF:.0f}; window {WINDOW_S:.1f} s\n")
    print("What one hit costs this defender (unmitigated -> HP taken):")
    for d in (10, 20, 30, 49.5, 60, 100):
        e = effective(d)
        print(f"  {d:6.1f} -> {e:5.1f} HP ({e / 2:4.1f} hearts)")

    print("\nBudget per phase and the damage rate it allows:")
    for phase, cap in CAPS.items():
        print(f"  phase {phase}: {cap:4.1f} HP ({cap / 2:.0f} hearts) per {WINDOW_S:.0f} s window = {cap / WINDOW_S:4.2f} HP/s")

    natural = 2.0
    plain, enchanted = heal_rate(4.0) + natural, heal_rate(16.0) + natural
    print(f"\nHealing without pause (incl. {natural:.1f} HP/s natural regeneration): golden apple {plain:.2f} HP/s, "
          f"enchanted golden apple {enchanted:.2f} HP/s")
    worst = CAPS[3] / WINDOW_S
    print(f"worst case sustained damage {worst:.2f} HP/s vs {plain:.2f} HP/s: "
          f"{'a player who keeps eating cannot be out-damaged' if worst <= plain + 0.5 else 'NOT SAFE'}"
          f" (only a player who also fights is at risk)")

    print("\nFinal form attacks before the cap (single player, full gear):")
    for label, base in (("Soul Slash", 11.0), ("Sweep", 13.0), ("Slam", 14.0)):
        raw = base * MULT[3]
        print(f"  {label:10s} {raw:5.1f} raw -> {effective(raw):5.1f} HP -> {min(effective(raw), CAPS[3]):4.1f} HP after the cap")
    for window in (60, 70, 100):
        print(f"  (a {window / 20:.1f} s window would allow {CAPS[3] / (window / 20.0):.2f} HP/s)")
