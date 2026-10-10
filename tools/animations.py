"""Entity 303 animation data: the single source for the in-game animation AND the Blender preview.

Rotations are in DEGREES here (converted to radians in the generated Java).  Each animation is a list of
keyframes {t: tick, <part>: [rx, ry, rz], spin: [x, y], off: [x, y, z]}; a part that a key doesn't mention
keeps the value of the previous key (0 at the start).  Values are blended with a smoothstep.

Conventions (Minecraft model space: +x = the entity's left, +y = down, front = -z):
  rx < 0 swings an arm FORWARD/UP, rx = -90 points it straight ahead, rx = -180 straight up.
  rz moves an arm sideways: for the RIGHT arm rz > 0 is outwards while it hangs down and rz < 0 is outwards
  while it is raised (mirror for the left arm).  With the arm pointing straight ahead rz only twists it.
  scythe_pivot is a child of the right arm (at the hand), so the scythe turns with the arm.

Two ways of holding the scythe:
  WHEEL  scythe=[0,0,90]: the blade's plane is perpendicular to the arm, so spinning it (spin = radians per
         tick around the arm's axis) turns the scythe like a wheel that never touches the arm or the body.
  SIDE   scythe=[0,0,0]: the blade's plane contains the arm.  Used for the chops.  The "world angle" of the
         scythe in the side plane: 0 = handle upright with the blade pointing forward, +90 = handle pointing
         forward with the blade hanging down, -90 = handle pointing back, blade up.
         sw(arm_rx, world) gives the scythe_pivot rotation that produces a world angle for a given arm_rx.
  off = root offset in model pixels (y < 0 lifts the whole body).

`events` are the ticks at which the server applies the effect, so the animation and the hit line up.
tools/collide.py checks that the scythe never passes through his body or the ground.
"""

import math

# the legs come last so the channel numbers of the older parts never change
# NOTE: the continuous twirl spins in the NEGATIVE direction (spin[0] < 0 in every key below)
PARTS = ["root", "head", "body", "right_arm", "left_arm", "scythe_pivot", "scythe", "right_leg", "left_leg"]
# rx < 0 swings a leg FORWARD, rx > 0 backwards (the walk cycle of the model is added on top)

# animations during which parts of him are meant to be under the ground (rising out of it, lying on it):
# tools/collide.py and tools/autofix.py do not check the scythe against the ground for these
NO_GROUND_CHECK = {"spawn", "death"}
SPIN_AXIS = 1        # which rotation of scythe_pivot the continuous spin is added to: 0 = rx, 1 = ry, 2 = rz
# degrees the scythe is rolled about its own handle while it is held as a WHEEL (scaled by wheel_weight): puts the
# blade on the other side of the handle; the SIDE holding of the chops is not touched
WHEEL_FLIP = 180.0
# radians per tick the scythe is twirled at while he is not attacking (idle_rage, the final phase, is faster: -0.3)
IDLE_SPIN = -0.24
CHANNELS = len(PARTS) * 3 + 3          # rotations of every part, then the root offset


def sw(arm_rx, world, rz=0.0):
    """scythe_pivot rotation giving the scythe a world angle (SIDE holding) with the arm at arm_rx."""
    return [world - arm_rx, 0, rz]


WHEEL = dict(scythe=[0, 0, 90], scythe_pivot=[0, 0, 0])
SIDE = dict(scythe=[0, 0, 0])

# the rest pose: right arm held out to the side, the scythe spinning like a wheel beside him (clear of his body at every angle)
IDLE = dict(WHEEL, right_arm=[-10, 0, 95], left_arm=[6, 0, -6], head=[0, 0, 0], body=[0, 0, 0],
            root=[0, 0, 0], off=[0, 0, 0], right_leg=[0, 0, 0], left_leg=[0, 0, 0])

# poses shared by several attacks (SIDE holding, see above)
RAISED = dict(SIDE, right_arm=[-165, 0, -25], scythe_pivot=sw(-165, -100), body=[14, 0, 0], head=[-10, 0, 0], spin=[0, 0])
CHOP = dict(SIDE, right_arm=[-50, 0, 0], scythe_pivot=sw(-50, 70), body=[-16, 0, 0], head=[10, 0, 0], spin=[0, 0])
FOLLOW = dict(SIDE, right_arm=[-40, 0, 5], scythe_pivot=sw(-40, 85), body=[-10, 0, 0], head=[6, 0, 0], spin=[0, 0])
GATHER = dict(right_arm=[-110, 0, -10], left_arm=[-100, 0, -20], body=[18, 0, 0], head=[10, 0, 0], spin=[-0.45, 0])
# arms thrown wide, head back: the roar / burst pose
BURST = dict(right_arm=[0, 0, 92], left_arm=[0, 0, -92], head=[-35, 0, 0], body=[-10, 0, 0], spin=[-0.5, 0])
# the rage stance he idles in during the final phase
RAGE = dict(IDLE, body=[8, 0, 0], head=[10, 0, 0], left_arm=[10, 0, -12], right_leg=[-8, 0, 0], left_leg=[10, 0, 0])


def key(t, *bases, **kw):
    """A keyframe: the given base poses are merged left to right, then the keyword overrides."""
    k = {}
    for b in bases:
        k.update(b)
    k.update(kw)
    k["t"] = t
    return k


ANIMS = {
    # id 0 -- loops forever while he is not attacking; the scythe is twirled in his hand like a baton
    "idle": dict(id=0, duration=80, loop=True, events={}, keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(40, IDLE, right_arm=[-16, 0, 99], left_arm=[-2, 0, -9], head=[3, 8, 0], body=[0, 3, 0], off=[0, -0.5, 0]),
        key(80, IDLE),
    ]),

    # overhead chop with a half-circle area hit in front of him
    "sweep": dict(id=1, duration=24, loop=False, events=dict(hit=12), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(3, IDLE, SIDE, right_arm=[-125, 0, -8], scythe_pivot=sw(-125, -30), body=[8, 0, 0], spin=[0, 0]),
        key(8, IDLE, SIDE, right_arm=[-165, 0, -25], scythe_pivot=sw(-165, -100), body=[14, 0, 0], head=[-8, 0, 0], spin=[0, 0]),
        key(10, IDLE, SIDE, right_arm=[-167, 0, -26], scythe_pivot=sw(-167, -102), body=[16, 0, 0], head=[-10, 0, 0], spin=[0, 0]),
        key(12, IDLE, SIDE, right_arm=[-50, 0, 0], scythe_pivot=sw(-50, 70), body=[-16, 0, 0], head=[10, 0, 0], spin=[0, 0]),
        key(16, IDLE, SIDE, right_arm=[-40, 0, 5], scythe_pivot=sw(-40, 85), body=[-10, 0, 0], head=[6, 0, 0], spin=[0, 0]),
        key(21, IDLE, spin=[0, 0]),
        key(24, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # winds up, then fires a beam of soul fire along the ground
    "slash": dict(id=2, duration=32, loop=False, events=dict(fire=16), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(12, IDLE, SIDE, right_arm=[-165, 0, -25], scythe_pivot=sw(-165, -105), body=[14, 0, 0], head=[-12, 0, 0], spin=[0, 0]),
        key(15, IDLE, SIDE, right_arm=[-167, 0, -26], scythe_pivot=sw(-167, -108), body=[16, 0, 0], head=[-12, 0, 0], spin=[0, 0]),
        key(17, IDLE, SIDE, right_arm=[-70, 0, 0], scythe_pivot=sw(-70, 60), body=[-14, 0, 0], head=[10, 0, 0], spin=[0, 0]),
        key(22, IDLE, SIDE, right_arm=[-60, 0, 0], scythe_pivot=sw(-60, 70), body=[-10, 0, 0], head=[8, 0, 0], spin=[0, 0]),
        key(28, IDLE, spin=[0, 0]),
        key(32, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # vanishes and reappears behind the target
    "step": dict(id=3, duration=22, loop=False, events=dict(tp=9), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(8, IDLE, right_arm=[-110, 0, -10], left_arm=[-100, 0, -20], body=[18, 0, 0], head=[10, 0, 0], spin=[-0.45, 0]),
        key(11, IDLE, right_arm=[-70, 0, -30], left_arm=[-20, 0, -40], body=[-6, 0, 0], head=[0, 0, 0], spin=[-0.45, 0]),
        key(22, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # raises both arms and calls vexes (his "souls") out of the air; the scythe spins like a rotor overhead
    "summon": dict(id=4, duration=44, loop=False, events=dict(spawn=20), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(14, IDLE, right_arm=[-165, 0, -35], left_arm=[-170, 0, 25], head=[-25, 0, 0], off=[0, -2, 0], spin=[-0.3, 0]),
        key(34, IDLE, right_arm=[-165, 0, -35], left_arm=[-170, 0, 25], head=[-25, 0, 0], off=[0, -2, 0], spin=[-0.3, 0]),
        key(44, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # holds a hand out and drains the life of the target
    "drain": dict(id=5, duration=40, loop=False, events=dict(start=8, end=34), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(8, IDLE, left_arm=[-90, 0, -5], right_arm=[-90, 6, 0], head=[0, 0, 0], spin=[-0.05, 0]),
        key(34, IDLE, left_arm=[-95, 6, -5], right_arm=[-90, 6, 0], head=[0, 0, 0], spin=[-0.05, 0]),
        key(40, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # REAPER'S DESCENT: he rises into the air spinning like a tornado, drags every player to the floor under him,
    # winds up, then dives and crashes down: whoever is hit loses half of their maximum health
    "whirl": dict(id=6, duration=104, loop=False, events=dict(rise=16, pull_end=72, dive=80, impact=88), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(14, IDLE, right_arm=[0, 0, 92], left_arm=[0, 0, -92], spin=[-0.9, 0]),
        key(72, IDLE, right_arm=[0, 0, 92], left_arm=[0, 0, -92], root=[0, 1080, 0], spin=[-0.9, 0]),
        key(78, IDLE, SIDE, right_arm=[-170, 0, -22], left_arm=[-170, 0, 22], scythe_pivot=sw(-170, -100), body=[12, 0, 0], head=[-15, 0, 0], root=[0, 1080, 0], spin=[0, 0]),
        key(80, IDLE, SIDE, right_arm=[-172, 0, -22], left_arm=[-172, 0, 22], scythe_pivot=sw(-172, -102), body=[14, 0, 0], head=[-16, 0, 0], root=[0, 1080, 0], spin=[0, 0]),
        key(88, IDLE, SIDE, right_arm=[-55, 0, 0], left_arm=[-45, 0, -6], scythe_pivot=sw(-55, 62), body=[-24, 0, 0], head=[14, 0, 0], root=[0, 1080, 0], spin=[0, 0]),
        key(96, IDLE, SIDE, right_arm=[-45, 0, 0], left_arm=[-30, 0, -6], scythe_pivot=sw(-45, 75), body=[-30, 0, 0], head=[16, 0, 0], root=[0, 1080, 0], spin=[0, 0]),
        key(104, IDLE, root=[0, 1080, 0], spin=[IDLE_SPIN, 0]),
    ]),

    # jumps, then slams down: delayed explosions under the players
    "slam": dict(id=7, duration=52, loop=False, events=dict(mark=6, strike=36), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(20, IDLE, SIDE, right_arm=[-165, 0, -25], left_arm=[-170, 0, 18], scythe_pivot=sw(-165, -100), body=[12, 0, 0], head=[-12, 0, 0], off=[0, -9, 0], spin=[0, 0]),
        key(30, IDLE, SIDE, right_arm=[-167, 0, -26], left_arm=[-172, 0, 18], scythe_pivot=sw(-167, -102), body=[14, 0, 0], head=[-14, 0, 0], off=[0, -10, 0], spin=[0, 0]),
        key(36, IDLE, SIDE, right_arm=[-50, 0, 0], left_arm=[-40, 0, -6], scythe_pivot=sw(-50, 65), body=[-20, 0, 0], head=[14, 0, 0], off=[0, 0, 0], spin=[0, 0]),
        key(44, IDLE, SIDE, right_arm=[-45, 0, 0], scythe_pivot=sw(-45, 75), body=[-10, 0, 0], head=[6, 0, 0], spin=[0, 0]),
        key(48, IDLE, spin=[0, 0]),
        key(52, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # phase change: roars, shockwave, summons
    "roar": dict(id=8, duration=44, loop=False, events=dict(burst=16), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(14, IDLE, right_arm=[0, 0, 60], left_arm=[0, 0, -60], head=[-35, 0, 0], body=[-10, 0, 0], spin=[-0.4, 0]),
        key(30, IDLE, right_arm=[0, 0, 62], left_arm=[0, 0, -62], head=[-38, 0, 0], body=[-12, 0, 0], off=[0, -1, 0], spin=[-0.4, 0]),
        key(44, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # ------------------------------------------------------------------ more moves --
    # CROSSING SLASHES: a diagonal cut from his right to his left, then an overhead chop that throws players up
    "combo": dict(id=9, duration=38, loop=False, events=dict(hit1=9, hit2=21), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(4, IDLE, SIDE, right_arm=[-130, 30, -20], scythe_pivot=sw(-130, -60), body=[8, 0, 0], head=[-6, 12, 0], left_arm=[-40, 0, -18], spin=[0, 0]),
        key(9, IDLE, SIDE, right_arm=[-85, -35, 10], scythe_pivot=sw(-85, 50), body=[-6, 0, 0], head=[4, -12, 0], left_arm=[-20, 0, 10], spin=[0, 0]),
        key(14, IDLE, SIDE, right_arm=[-80, -40, 8], scythe_pivot=sw(-80, 40), body=[-4, 0, 0], head=[2, -8, 0], spin=[0, 0]),
        key(17, IDLE, RAISED),
        key(21, IDLE, CHOP),
        key(26, IDLE, FOLLOW),
        key(32, IDLE, spin=[0, 0]),
        key(38, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # REAPER'S LUNGE: coils back, dashes forward and thrusts the scythe
    "lunge": dict(id=10, duration=34, loop=False, events=dict(dash=8, hit=14), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(6, IDLE, SIDE, right_arm=[-30, 10, -8], scythe_pivot=sw(-30, -25), left_arm=[-60, 0, -20], body=[16, 0, 0], head=[8, 0, 0],
            right_leg=[20, 0, 0], left_leg=[-22, 0, 0], off=[0, 1, 0], spin=[0, 0]),
        key(8, IDLE, SIDE, right_arm=[-30, 10, -8], scythe_pivot=sw(-30, -25), left_arm=[-60, 0, -20], body=[16, 0, 0], head=[8, 0, 0],
            right_leg=[20, 0, 0], left_leg=[-22, 0, 0], off=[0, 1, 0], spin=[0, 0]),
        key(14, IDLE, SIDE, right_arm=[-95, 0, 0], scythe_pivot=sw(-95, 88), left_arm=[-80, 0, -14], body=[26, 0, 0], head=[10, 0, 0],
            right_leg=[-40, 0, 0], left_leg=[34, 0, 0], off=[0, 2, 0], spin=[0, 0]),
        key(20, IDLE, SIDE, right_arm=[-95, 0, 0], scythe_pivot=sw(-95, 88), left_arm=[-80, 0, -14], body=[26, 0, 0], head=[10, 0, 0],
            right_leg=[-40, 0, 0], left_leg=[34, 0, 0], off=[0, 2, 0], spin=[0, 0]),
        key(28, IDLE, spin=[0, 0]),
        key(34, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # DEATH LEAP: crouches, jumps high at the target and crashes down on it
    "leap": dict(id=11, duration=46, loop=False, events=dict(takeoff=12, land=30), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(10, IDLE, SIDE, right_arm=[30, 0, 8], left_arm=[30, 0, -8], scythe_pivot=sw(30, -100), body=[22, 0, 0], head=[-10, 0, 0],
            right_leg=[-24, 0, 0], left_leg=[-24, 0, 0], off=[0, 2, 0], spin=[0, 0]),
        key(14, IDLE, SIDE, right_arm=[-170, 0, -20], left_arm=[-170, 0, 20], scythe_pivot=sw(-170, -102), body=[-10, 0, 0], head=[-14, 0, 0],
            right_leg=[-30, 0, 0], left_leg=[20, 0, 0], spin=[0, 0]),
        key(26, IDLE, SIDE, right_arm=[-168, 0, -24], left_arm=[-172, 0, 18], scythe_pivot=sw(-168, -105), body=[12, 0, 0], head=[-14, 0, 0],
            right_leg=[-20, 0, 0], left_leg=[14, 0, 0], spin=[0, 0]),
        key(30, IDLE, SIDE, right_arm=[-50, 0, 0], left_arm=[-45, 0, -6], scythe_pivot=sw(-50, 65), body=[-22, 0, 0], head=[14, 0, 0],
            right_leg=[-20, 0, 0], left_leg=[-20, 0, 0], off=[0, 2, 0], spin=[0, 0]),
        key(38, IDLE, SIDE, right_arm=[-45, 0, 0], scythe_pivot=sw(-45, 75), body=[-8, 0, 0], head=[6, 0, 0],
            right_leg=[-12, 0, 0], left_leg=[-12, 0, 0], off=[0, 2, 0], spin=[0, 0]),
        key(42, IDLE, spin=[0, 0]),
        key(46, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # SOUL HOOK: throws a chain of souls at the players far away and drags them to him
    "hook": dict(id=12, duration=40, loop=False, events=dict(throw=10, yank=18), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(8, IDLE, SIDE, right_arm=[-120, 0, -30], scythe_pivot=sw(-120, -70), left_arm=[-60, 0, -30], body=[10, 0, 0], head=[-4, 0, 0], spin=[0, 0]),
        key(10, IDLE, SIDE, right_arm=[-90, 4, 0], scythe_pivot=sw(-90, 75), left_arm=[-100, 0, 10], body=[-8, 0, 0], spin=[0, 0]),
        key(18, IDLE, SIDE, right_arm=[-45, 0, -10], scythe_pivot=sw(-45, -10), left_arm=[-30, 0, 0], body=[16, 0, 0], head=[6, 0, 0],
            right_leg=[20, 0, 0], left_leg=[-20, 0, 0], spin=[0, 0]),
        key(26, IDLE, SIDE, right_arm=[-45, 0, -10], scythe_pivot=sw(-45, -10), left_arm=[-30, 0, 0], body=[14, 0, 0], head=[6, 0, 0],
            right_leg=[20, 0, 0], left_leg=[-20, 0, 0], spin=[0, 0]),
        key(34, IDLE, spin=[0, 0]),
        key(40, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # SOUL RINGS: three stomps, every one sends a ring of soul fire across the floor (jump it!)
    "rings": dict(id=13, duration=56, loop=False, events=dict(stomp=14, stomp2=26, stomp3=38), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(10, IDLE, SIDE, right_arm=[-165, 0, -30], left_arm=[-170, 0, 25], scythe_pivot=sw(-165, -95), head=[-20, 0, 0], body=[-8, 0, 0],
            right_leg=[-25, 0, 0], off=[0, -4, 0], spin=[0, 0]),
        key(14, IDLE, SIDE, right_arm=[-60, 0, 0], left_arm=[-50, 0, -6], scythe_pivot=sw(-60, 70), body=[-18, 0, 0], head=[14, 0, 0],
            off=[0, 2, 0], spin=[0, 0]),
        key(22, IDLE, SIDE, right_arm=[-165, 0, -30], left_arm=[-170, 0, 25], scythe_pivot=sw(-165, -95), head=[-20, 0, 0], body=[-8, 0, 0],
            left_leg=[-25, 0, 0], off=[0, -4, 0], spin=[0, 0]),
        key(26, IDLE, SIDE, right_arm=[-60, 0, 0], left_arm=[-50, 0, -6], scythe_pivot=sw(-60, 70), body=[-18, 0, 0], head=[14, 0, 0],
            off=[0, 2, 0], spin=[0, 0]),
        key(34, IDLE, SIDE, right_arm=[-170, 0, -30], left_arm=[-172, 0, 25], scythe_pivot=sw(-170, -98), head=[-24, 0, 0], body=[-10, 0, 0],
            right_leg=[-30, 0, 0], off=[0, -6, 0], spin=[0, 0]),
        key(38, IDLE, SIDE, right_arm=[-60, 0, 0], left_arm=[-50, 0, -6], scythe_pivot=sw(-60, 70), body=[-22, 0, 0], head=[16, 0, 0],
            off=[0, 2, 0], spin=[0, 0]),
        key(46, IDLE, SIDE, right_arm=[-45, 0, 0], scythe_pivot=sw(-45, 75), body=[-8, 0, 0], head=[6, 0, 0], spin=[0, 0]),
        key(50, IDLE, spin=[0, 0]),
        key(56, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # PHANTOM DANCE: vanishes and reappears behind three different players, a chop after every jump
    "dance": dict(id=14, duration=66, loop=False, events=dict(tp1=8, hit1=14, tp2=26, hit2=32, tp3=44, hit3=50), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(6, IDLE, GATHER),
        key(10, IDLE, RAISED),
        key(14, IDLE, CHOP),
        key(18, IDLE, FOLLOW),
        key(24, IDLE, GATHER),
        key(28, IDLE, RAISED),
        key(32, IDLE, CHOP),
        key(36, IDLE, FOLLOW),
        key(42, IDLE, GATHER),
        key(46, IDLE, RAISED),
        key(50, IDLE, CHOP),
        key(56, IDLE, FOLLOW),
        key(62, IDLE, spin=[0, 0]),
        key(66, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # REAPER'S GUARD: braces behind the spinning scythe (hardly any damage gets through), then bursts outward
    "guard": dict(id=15, duration=56, loop=False, events=dict(up=8, burst=44), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(8, IDLE, right_arm=[-90, 0, 0], left_arm=[-90, 0, 0], head=[10, 0, 0], body=[8, 0, 0],
            right_leg=[-15, 0, 0], left_leg=[15, 0, 0], spin=[-0.9, 0]),
        key(40, IDLE, right_arm=[-90, 0, 0], left_arm=[-92, 0, 0], head=[12, 0, 0], body=[10, 0, 0],
            right_leg=[-15, 0, 0], left_leg=[15, 0, 0], spin=[-1.1, 0]),
        key(44, IDLE, BURST),
        key(52, IDLE, BURST, spin=[-0.3, 0]),
        key(56, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # SOUL RAIN: the scythe spins like a rotor over his head, columns of soul fire fall around the players
    "rain": dict(id=16, duration=70, loop=False, events=dict(start=14, end=52), keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(14, IDLE, right_arm=[-165, 0, -35], left_arm=[-170, 0, 25], head=[-25, 0, 0], off=[0, -2, 0], spin=[-0.6, 0]),
        key(30, IDLE, right_arm=[-170, 0, -30], left_arm=[-160, 0, 18], head=[-28, 4, 0], body=[0, 4, 0], off=[0, -3, 0], spin=[-0.7, 0]),
        key(42, IDLE, right_arm=[-160, 0, -38], left_arm=[-172, 0, 28], head=[-26, -4, 0], body=[0, -4, 0], off=[0, -2, 0], spin=[-0.7, 0]),
        key(56, IDLE, right_arm=[-165, 0, -35], left_arm=[-170, 0, 25], head=[-25, 0, 0], off=[0, -2, 0], spin=[-0.4, 0]),
        key(64, IDLE, spin=[IDLE_SPIN, 0]),
        key(70, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # he climbs out of the ground when he is summoned (played once, by the spawn)
    "spawn": dict(id=17, duration=64, loop=False, events=dict(burst=44), keys=[
        key(0, IDLE, SIDE, right_arm=[10, 0, 8], scythe_pivot=sw(10, 85), left_arm=[8, 0, -6], head=[-30, 0, 0], off=[0, 40, 0], spin=[0, 0]),
        key(20, IDLE, SIDE, right_arm=[-20, 0, 12], scythe_pivot=sw(-20, 70), left_arm=[-20, 0, -10], head=[-24, 0, 0], off=[0, 22, 0], spin=[0, 0]),
        key(36, IDLE, SIDE, right_arm=[-165, 0, -30], left_arm=[-170, 0, 22], scythe_pivot=sw(-165, -98), head=[-30, 0, 0], body=[-6, 0, 0],
            off=[0, 2, 0], spin=[0, 0]),
        key(44, IDLE, BURST, off=[0, -1, 0]),
        key(54, IDLE, BURST, off=[0, 0, 0], spin=[-0.3, 0]),
        key(64, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # death: staggers, sinks to his knees and falls on his back (played from the client death timer)
    "death": dict(id=18, duration=70, loop=False, events={}, keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(10, IDLE, right_arm=[-40, 0, 14], left_arm=[-20, 0, -20], head=[-25, 0, 0], body=[-10, 0, 0], right_leg=[-10, 0, 0], left_leg=[8, 0, 0], spin=[0, 0]),
        key(24, IDLE, SIDE, right_arm=[10, 0, 10], left_arm=[10, 0, -10], scythe_pivot=sw(10, 80), head=[30, 0, 0], body=[12, 0, 0],
            right_leg=[-85, 0, 0], left_leg=[-85, 0, 0], off=[0, 10, 0], spin=[0, 0]),
        key(38, IDLE, SIDE, right_arm=[-20, 0, 20], left_arm=[-20, 0, -20], scythe_pivot=sw(-20, 80), head=[10, 0, 0], body=[-4, 0, 0],
            right_leg=[-85, 0, 0], left_leg=[-85, 0, 0], off=[0, 10, 0], spin=[0, 0]),
        key(54, IDLE, SIDE, right_arm=[-10, 0, 30], left_arm=[-10, 0, -30], scythe_pivot=sw(-10, 80), root=[-90, 0, 0], off=[0, 19.5, 0], spin=[0, 0]),
        key(70, IDLE, SIDE, right_arm=[-10, 0, 30], left_arm=[-10, 0, -30], scythe_pivot=sw(-10, 80), root=[-90, 0, 0], off=[0, 19.5, 0], spin=[0, 0]),
    ]),

    # a taunt: the scythe held up, his free hand beckoning
    "taunt": dict(id=19, duration=60, loop=False, events={}, keys=[
        key(0, IDLE, spin=[IDLE_SPIN, 0]),
        key(10, IDLE, right_arm=[-140, 6, -15], left_arm=[-100, 0, -20], head=[-20, 0, 0], body=[-6, 0, 0], spin=[-0.6, 0]),
        key(25, IDLE, right_arm=[-145, 6, -18], left_arm=[-60, 0, -25], head=[-24, 0, 0], body=[-8, 3, 0], spin=[-0.6, 0]),
        key(40, IDLE, right_arm=[-140, 6, -15], left_arm=[-100, 0, -20], head=[-22, 0, 0], body=[-6, -3, 0], spin=[-0.6, 0]),
        key(50, IDLE, spin=[-0.3, 0]),
        key(60, IDLE, spin=[IDLE_SPIN, 0]),
    ]),

    # idle while he is in his final form: a hunched, restless stance and a faster twirl (loops)
    "idle_rage": dict(id=20, duration=56, loop=True, events={}, keys=[
        key(0, RAGE, spin=[-0.3, 0]),
        key(28, RAGE, right_arm=[-16, 0, 99], head=[14, 10, 0], body=[10, -4, 0], off=[0, -1, 0]),
        key(56, RAGE),
    ]),
}


# ------------------------------------------------------------------ helpers --
TWO_PI = 2 * math.pi


def wrap(a):
    """angle in (-pi, pi]"""
    return a - TWO_PI * round(a / TWO_PI)


def spin_at(anim_name, t, spin0=0.0):
    """Windmill / roll angles accumulated after t ticks of an animation, like Entity303.tick() does on the client
    (spin0 = the angle the idle twirl had when the attack began)."""
    anim = ANIMS[anim_name]
    sx, sy = spin0, 0.0
    for k in range(int(t)):
        _, (rx, ry) = sample(anim, k)
        sx += rx
        sy += ry
    return sx, sy


def wheel_weight(ch):
    """1 while the scythe is held as a WHEEL (scythe rz = 90), 0 while held SIDE-on; the spin only turns a wheel."""
    return max(0.0, min(1.0, ch[PARTS.index("scythe") * 3 + 2] / 90.0))


def spin_applied(anim_name, t, ch, spin0=0.0):
    """(angle added to scythe_pivot[SPIN_AXIS], roll added to scythe ry) in radians, exactly as Entity303Model does."""
    sx, sy = spin_at(anim_name, t, spin0)
    return wrap(sx) * wheel_weight(ch), sy

def resolve(anim):
    """Expand a keyframe list to full channel arrays: [(t, spinx, spiny, [channels...]), ...]."""
    out, cur, spin = [], [0.0] * CHANNELS, [0.0, 0.0]
    for k in anim["keys"]:
        for i, p in enumerate(PARTS):
            if p in k:
                cur[i * 3:i * 3 + 3] = [float(v) for v in k[p]]
        if "off" in k:
            cur[len(PARTS) * 3:] = [float(v) for v in k["off"]]
        if "spin" in k:
            spin = [float(v) for v in k["spin"]]
        out.append((float(k["t"]), spin[0], spin[1], list(cur)))
    return out


def sample(anim, t):
    """Channels (degrees / px) and spin rates of an animation at tick t. Mirrors the generated Java."""
    keys = resolve(anim)
    if anim["loop"]:
        t = t % anim["duration"]
    t = max(keys[0][0], min(keys[-1][0], t))
    for a, b in zip(keys, keys[1:]):
        if a[0] <= t <= b[0]:
            x = (t - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 1.0
            f = x * x * (3 - 2 * x)
            ch = [p + (q - p) * f for p, q in zip(a[3], b[3])]
            return ch, (a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f)
    return keys[-1][3], (keys[-1][1], keys[-1][2])


# tools/autofix.py (run by tools/gen_assets.py) writes animations_fixed.py: the same animations plus the
# corrective keyframes that stop the scythe from passing through his body or the ground.
ANIMS_AUTHORED = ANIMS
try:
    from animations_fixed import ANIMS as ANIMS  # noqa: E402,F811
except ImportError:
    pass
