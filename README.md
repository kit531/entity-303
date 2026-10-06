# Entity 303 — Minecraft boss mod (Fabric, 1.21.11)

> **Read TODO_HANDOFF.md first** — it says what is done, what is untested and what is left to do.

Adds **Entity 303** (the white hooded figure with the black face, red eyes and a scythe) as a boss.

* 1000 HP (built for a fight with ~5 players), double damage, boss bar with 20 notches that changes colour/title per phase (white -> purple "ENRAGED" -> flashing red "FINAL FORM")
* the scythe is twirled in his hand all the time (modelled in Blender, see `blender/`)
* three phases (66% / 33% HP): faster, harder hitting, more attacks, a roar + shockwave at every phase change
* special attacks: **Sweep** (overhead chop), **Soul Slash** (beam of soul fire, 3-way fan from phase 2),
  **Shadow Step** (teleports behind you and chops), **Summon** (vexes), **Soul Drain** (slows, blinds and heals him),
  **Reaper's Descent** (flies up spinning, drags everyone to the floor, crashes down: half of the max health of whoever is hit, 75% in the final phase),
  **Reaper's Wrath** (marks the ground under every player, then explodes)
* more moves: **Crossing Slashes** (two-hit combo), **Reaper's Lunge** (dash + thrust), **Death Leap** (jumps at you and crashes down),
  **Soul Hook** (chain that drags far players to him, followed by a sweep), **Soul Rings** (three stomps, rings of soul fire - jump them),
  **Phantom Dance** (teleports behind three different players, a chop after each), **Reaper's Guard** (takes 20% damage while bracing, then bursts:
  stronger for every hit he absorbed), **Soul Rain** (marked columns of soul fire fall around the players)
* extras: climbs out of the ground when summoned, a death animation (falls on his back), a taunt, a restless "rage" idle in the final phase,
  legs in the animations (crouch, lunge, leap)
* **final form (phase 3):** immune to every effect (good or bad, potions included), all his attacks hit twice as hard, and he heals for 50% of the health he takes from players
* he counts the players around him: more beams, drains and minions, shorter pauses (work in progress, see TODO_HANDOFF.md)
* drops: 2 nether stars, 16-32 diamonds, 4-8 netherite scrap, a totem of undying, 1-3 enchanted golden apples, 500 XP

## Play it

1. Fabric Loader >= 0.18.0 and Fabric API `0.141.x+1.21.11` (install Fabric API in the same profile) for Minecraft 1.21.11
2. put `entity-303-1.0.0.jar` in `.minecraft/mods`
3. creative tab "Spawn Eggs" -> *Entity 303 Spawn Egg*, or `/summon entity303:entity_303`
   (he ignores creative/spectator players - test in survival)

## Updates

The mod updates itself from the GitHub Releases of its repository (`ModUpdater.DEFAULT_REPO`). When the game starts it checks, in the
background, for a newer release; it downloads the jar, verifies it (SHA-256 from the release's `.sha256` file, and that it really is this
mod) and swaps it for the installed jar **when the game closes**. The new version is active after the next start (a chat message tells you
when an update is waiting). Nothing is replaced while the game is running.

* turn it off: `"autoUpdate": false` in `config/entity303.json` (the file is created on the first start); the same file holds `repo`
* does nothing in a development environment, and never touches a mod that is not a single jar file
* publish a version: bump nothing, just `git tag v1.2.2 && git push origin v1.2.2`; the *Release* GitHub Action builds it with that version
  and publishes `entity-303-1.2.2.jar` + `entity-303-1.2.2.jar.sha256`

## Build

```
./gradlew build        # -> build/libs/entity-303-1.0.0.jar     (Java 21)
./gradlew runClient    # dev client
```

## Make it yours

| What                    | Where |
|-------------------------|-------|
| The skin                | put the real 64x64 skin at `skin/entity303_skin.png`, then `python tools/gen_assets.py` (eyes glow layer is derived from the red pixels of the skin) |
| The scythe              | `blender -b -P blender/make_scythe.py` (edit `BLADE_COLUMNS`, colours ...), then `python tools/gen_assets.py` |
| Animations / timing     | `tools/animations.py` (keyframes + the tick at which each attack lands), then `python tools/gen_assets.py` |
| Preview of every pose   | `blender -b -P blender/preview_entity.py` then `python tools/sheet.py preview/frames preview/sheet.png 6` |
| Damage, ranges, cooldowns | `src/main/java/com/entity303/entity/Entity303AttackGoal.java` |
| HP, speed, boss bar     | `src/main/java/com/entity303/entity/Entity303.java` |

`tools/gen_assets.py` generates `Entity303Geometry.java`, `Entity303Animations.java` and the textures, so the model in the
game, the Blender preview and the server-side timing always come from the same data.

Windows note: the project path must be short (Windows 260-character limit for some tools); if the folder is deeply nested,
set `ENTITY303_ROOT` to a short path (a junction works) before running the Python/Blender scripts.
