"""Suggest corrections for the key poses of tools/animations.py that make the scythe pass through his body.

    python tools/tune_poses.py [anim ...]
Prints, per problematic key, the nudged values to paste back into animations.py.
"""
import math, os, sys

ROOT = os.environ.get("ENTITY303_ROOT", "C:/rb")
sys.path.insert(0, os.path.join(ROOT, "tools"))
import animations as A  # noqa: E402
import collide as C  # noqa: E402

names = sys.argv[1:] or [n for n in A.ANIMS if n != "idle"]
for n in names:
    anim = A.ANIMS[n]
    for (t, sx, sy, ch), k in zip(A.resolve(anim), anim["keys"]):
        pose = {p: ch[i * 3:i * 3 + 3] for i, p in enumerate(A.PARTS)}
        pose["off"] = ch[len(A.PARTS) * 3:]
        wheel = abs(pose["scythe"][2] - 90) < 1
        spins = [(math.radians(a), 0.0) for a in range(0, 360, 45)] if (wheel and sx != 0) else [(0.0, 0.0)]
        total = sum(C.evaluate(ch, sp, 0.8)[0] for sp in spins)
        ground = max(C.evaluate(ch, sp, 0.8)[2] for sp in spins)
        if total < 0.5 and ground < 0.3:
            continue
        free = [("right_arm", 0), ("right_arm", 1), ("right_arm", 2)] if wheel else \
               [("right_arm", 0), ("right_arm", 2), ("scythe_pivot", 0), ("body", 0)]
        new, tt, gg = C.tune(pose, free, spins)
        print(f"{n} t={int(t)}: overlap {total:.1f}px ground {ground:.1f}px  ->  overlap {tt:.1f}px ground {gg:.1f}px")
        for part, axis in free:
            if abs(new[part][axis] - pose[part][axis]) > 0.5:
                print(f"     {part}[{axis}]: {pose[part][axis]:.0f} -> {new[part][axis]:.0f}")
