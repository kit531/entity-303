"""Does the scythe pass through Entity 303's own body?  Checks every animation, tick by tick.

    python tools/collide.py [anim ...]          (set ENTITY303_ROOT=C:/rb if the project path is long)

Uses the same forward kinematics as the game (translate to the pivot, rotate Z*Y*X) and an exact
oriented-box overlap test (separating axis theorem) between every scythe cube and every body cube.
Contact at the grip is expected, so the right hand (the last 5 px of the arm) is excluded.
Also reports how far the scythe dips below the ground.
"""
import json, math, os, sys

ROOT = os.environ.get("ENTITY303_ROOT", "C:/rb")
sys.path.insert(0, os.path.join(ROOT, "tools"))
import animations as A  # noqa: E402

SPEC = json.load(open(os.path.join(ROOT, "tools/entity303_spec.json")))["spec"]
MIN_DEPTH = 0.6          # px of overlap that counts as "passes through"
GROUND_Y = 24.016        # model-space y of the ground (the renderer translates by 1.501 blocks)
IGNORE_GROUND = False    # autofix sets this while it repairs an animation that is meant to dip into the ground


# ----------------------------------------------------------------- small math --
def mmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def mvec(a, v):
    return [sum(a[i][k] * v[k] for k in range(3)) for i in range(3)]


def rot_zyx(rx, ry, rz):
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    rx_ = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry_ = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz_ = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return mmul(rz_, mmul(ry_, rx_))


def add(a, b):
    return [a[i] + b[i] for i in range(3)]


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


# --------------------------------------------------------------------- pose ---
def pose_for(anim_name, t, spin0=0.0, spin_override=None):
    anim = A.ANIMS[anim_name]
    ch, _ = A.sample(anim, t)
    if spin_override is not None:
        sx, sy = spin_override
    else:
        sx, sy = A.spin_applied(anim_name, t, ch, spin0)
    rot = {p: [math.radians(v) for v in ch[i * 3:i * 3 + 3]] for i, p in enumerate(A.PARTS)}
    # the spin is applied exactly like Entity303Model does
    rot["scythe_pivot"][A.SPIN_AXIS] += sx
    rot["scythe"][1] += sy + math.radians(A.WHEEL_FLIP) * A.wheel_weight(ch)
    return rot, ch[len(A.PARTS) * 3:]


def boxes(rot, off, include_scythe):
    """world-space oriented boxes: (centre, axes[3], half[3], tag)"""
    out = []
    root_r = rot_zyx(*rot["root"])

    def walk(part, pr, pt):
        r = [part["rot"][i] + rot.get(part["name"], [0, 0, 0])[i] for i in range(3)]
        rr = mmul(pr, rot_zyx(*r))
        tt = add(pt, mvec(pr, part["pivot"]))
        is_scythe = part["name"] in ("scythe", "scythe_pivot")
        for c in part["cubes"]:
            if is_scythe != include_scythe:
                continue
            o, s, g = list(c["o"]), list(c["s"]), c["grow"]
            if c["name"] in ("right_arm", "right_sleeve"):        # exclude the hand: the grip is meant to touch it
                s[1] = s[1] - 5.0
            lc = [o[i] + s[i] / 2 for i in range(3)]
            half = [s[i] / 2 + g for i in range(3)]
            wc = add(tt, mvec(rr, lc))
            axes = [[rr[i][k] for i in range(3)] for k in range(3)]
            out.append((wc, axes, half, c["name"]))
        for ch in part["children"]:
            walk(ch, rr, tt)

    for p in SPEC:
        walk(p, root_r, [off[0], off[1], off[2]])
    return out


def overlap(a, b):
    """minimum penetration depth between two oriented boxes, or None if they are separated"""
    (ca, aa, ha, _), (cb, ab, hb, _) = a, b
    d = [cb[i] - ca[i] for i in range(3)]
    if dot(d, d) > (math.sqrt(dot(ha, ha)) + math.sqrt(dot(hb, hb))) ** 2:
        return None
    best = 1e9
    axes = list(aa) + list(ab)
    for i in range(3):
        for j in range(3):
            c = cross(aa[i], ab[j])
            n = math.sqrt(dot(c, c))
            if n > 1e-6:
                axes.append([x / n for x in c])
    for L in axes:
        ra = sum(ha[i] * abs(dot(aa[i], L)) for i in range(3))
        rb = sum(hb[i] * abs(dot(ab[i], L)) for i in range(3))
        o = ra + rb - abs(dot(d, L))
        if o <= 0:
            return None
        best = min(best, o)
    return best


def scan(anim_name, ticks, spin_override=None, verbose=True, spin0=0.0):
    worst, ground, hits = 0.0, -99.0, {}
    check_ground = anim_name not in getattr(A, "NO_GROUND_CHECK", ())
    for t in ticks:
        rot, off = pose_for(anim_name, t, spin0, spin_override)
        body = boxes(rot, off, False)
        sc = boxes(rot, off, True)
        for s in sc:
            # ground: the 8 corners of every scythe cube
            for sx in ((-1, 1) if check_ground else ()):
                for sy in (-1, 1):
                    for sz in (-1, 1):
                        p = add(s[0], [sum(s[1][k][i] * s[2][k] * (sx, sy, sz)[k] for k in range(3)) for i in range(3)])
                        ground = max(ground, p[1] - GROUND_Y)
            for b in body:
                d = overlap(s, b)
                if d is not None and d >= MIN_DEPTH:
                    key = (b[3])
                    hits.setdefault(key, []).append((t, round(d, 1)))
                    worst = max(worst, d)
    if verbose:
        status = "clean" if not hits else "; ".join(f"{k}: {len(v)} ticks (worst {max(x[1] for x in v)}px, first t={v[0][0]})" for k, v in hits.items())
        print(f"{anim_name:7s} ground dip {max(0.0, ground):4.1f}px | {status}")
    return worst, max(0.0, ground), hits



# ------------------------------------------------------------- static poses ---
def channels_from(pose):
    """{part: [rx, ry, rz] degrees, 'off': [x, y, z]} -> flat channel list"""
    ch = []
    for p in A.PARTS:
        ch += [float(v) for v in pose.get(p, [0, 0, 0])]
    ch += [float(v) for v in pose.get("off", [0, 0, 0])]
    return ch


def evaluate(ch, spin=(0.0, 0.0), margin=0.0):
    """cost of a static pose: body overlap (px, summed), worst overlap and ground dip (px).
    spin = the RAW accumulated twirl angles; they are applied like the game does (wrapped, times the wheel weight).
    margin inflates the body boxes."""
    rot = {p: [math.radians(v) for v in ch[i * 3:i * 3 + 3]] for i, p in enumerate(A.PARTS)}
    w = A.wheel_weight(ch)
    rot["scythe_pivot"][A.SPIN_AXIS] += A.wrap(spin[0]) * w
    rot["scythe"][1] += spin[1] + math.radians(A.WHEEL_FLIP) * w
    off = ch[len(A.PARTS) * 3:]
    body = boxes(rot, off, False)
    sc = boxes(rot, off, True)
    if margin:
        body = [(c, a, [h + margin for h in hh], n) for c, a, hh, n in body]
    total, worst, ground = 0.0, 0.0, -99.0
    for s_ in sc:
        for sx in ((-1, 1) if not IGNORE_GROUND else ()):
            for sy in (-1, 1):
                for sz in (-1, 1):
                    q = add(s_[0], [sum(s_[1][k][i] * s_[2][k] * (sx, sy, sz)[k] for k in range(3)) for i in range(3)])
                    ground = max(ground, q[1] - GROUND_Y)
        for b in body:
            d = overlap(s_, b)
            if d is not None:
                total += d
                worst = max(worst, d)
    return total, worst, max(0.0, ground)


def tune(pose, free, spins=((0.0, 0.0),), margin=1.0, reach=60.0, stiffness=0.02, rounds=5):
    """Nudge the `free` angles [(part, axis)] of a pose until the scythe clears the body and the ground
    for every spin angle in `spins`, staying as close as possible to the original.
    Returns (new pose, overlap, ground dip)."""
    start = {k: list(v) for k, v in pose.items()}
    cur = {k: list(v) for k, v in pose.items()}

    def cost(p):
        t = g = 0.0
        ch = channels_from(p)
        for sp in spins:
            tt, _, gg = evaluate(ch, sp, margin)
            t += tt
            g = max(g, gg)
        pen = sum((p[part][axis] - start[part][axis]) ** 2 for part, axis in free)
        return 10.0 * t + 8.0 * g + stiffness * pen, t, g

    best, bt, bg = cost(cur)
    for step in (16, 8, 4, 2, 1)[:rounds]:
        improved = True
        while improved:
            improved = False
            for part, axis in free:
                for d in (-step, step):
                    v = cur[part][axis] + d
                    if abs(v - start[part][axis]) > reach:
                        continue
                    trial = {k: list(x) for k, x in cur.items()}
                    trial[part][axis] = v
                    c, t, g = cost(trial)
                    if c < best - 1e-6:
                        best, bt, bg, cur, improved = c, t, g, trial, True
    return cur, bt, bg


if __name__ == "__main__":
    names = sys.argv[1:] or list(A.ANIMS)
    total = 0.0
    for n in names:
        anim = A.ANIMS[n]
        if anim["loop"]:
            # the twirl takes every angle: sample the whole turn at a few moments of the loop
            ticks = [round(anim["duration"] * q / 4) for q in range(4)]
            worst, g, h = 0.0, 0.0, {}
            for k in range(72):
                w, gg, hh = scan(n, ticks, spin_override=(math.radians(k * 5), 0.0), verbose=False)
                worst = max(worst, w); g = max(g, gg)
                for key, v in hh.items():
                    h.setdefault(key, []).extend(v)
            status = "clean" if not h else "; ".join(f"{k}: {len(v)} of 72 angles (worst {max(x[1] for x in v)}px)" for k, v in h.items())
            print(f"{n:7s} ground dip {g:4.1f}px | {status}")
            total += worst
        else:
            w, g, h = 0.0, 0.0, {}
            for start in (0.0, 1.57, 3.14, 4.71):          # the twirl can be at any angle when the attack begins
                ww, gg, hh = scan(n, list(range(0, anim['duration'] + 1)), verbose=False, spin0=start)
                w, g = max(w, ww), max(g, gg)
                for key, v in hh.items():
                    h.setdefault(key, []).extend(v)
            status = "clean" if not h else "; ".join(f"{k}: {len(set(x[0] for x in v))} ticks (worst {max(x[1] for x in v)}px, first t={min(x[0] for x in v)})" for k, v in h.items())
            print(f"{n:7s} ground dip {g:4.1f}px | {status}")
            total += w
    print("TOTAL worst overlap:", round(total, 1), "px")
