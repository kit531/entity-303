"""Builds Entity 303's look from one spec.

Inputs
  skin/entity303_skin.png        the real 64x64 skin (classic 4px-arm layout). Optional: if it is
                                 missing a painted approximation is used instead.
  blender/scythe_boxes.json      the scythe, modelled in Blender (blender/make_scythe.py)

Outputs
  src/main/resources/assets/entity303/textures/entity/entity303.png         128x128 atlas
        (the skin in the top-left 64x64, the scythe's colour patches on the right)
  src/main/resources/assets/entity303/textures/entity/entity303_eyes.png    glowing eyes layer
  src/main/resources/assets/entity303/textures/entity/entity303_rage.png    phase-2 glow layer
  src/client/java/com/entity303/client/render/Entity303Geometry.java        the model parts
  tools/entity303_spec.json                                                 (for the Blender preview)

Run:  python tools/gen_assets.py     (set ENTITY303_ROOT=<short path> if the project path is >200 chars)
Model space = Minecraft model space: pixels, y points DOWN, the front of the entity is -z.
"""
import json, math, os, random, sys
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import animations as anim_data

ROOT = os.environ.get("ENTITY303_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD_ID = "entity303"
TEX_W = TEX_H = 128
TEX_DIR = os.path.join(ROOT, "src/main/resources/assets", MOD_ID, "textures/entity")
JAVA_OUT = os.path.join(ROOT, "src/client/java/com/entity303/client/render/Entity303Geometry.java")
ANIM_OUT = os.path.join(ROOT, "src/main/java/com/entity303/anim/Entity303Animations.java")
SKIN_IN = os.path.join(ROOT, "skin/entity303_skin.png")
SCYTHE_SCALE = 0.7          # scythe size relative to the Blender model (1.0 = as modelled)
ITEM_BLADE_LEFT = False     # the dropped scythe's icon: blade sweeping to the upper left (False = to the lower right)
NL = chr(10)


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# --------------------------------------------------------------------- spec ---
def cube(name, o, s, uv, grow=0.0, patch=None):
    """uv = (u, v) texOffs inside the atlas, or None to let the packer place it (scythe patches)."""
    return dict(name=name, o=list(o), s=list(s), uv=uv, grow=grow, patch=patch)


def part(name, pivot, cubes=(), children=(), rot=(0, 0, 0)):
    return dict(name=name, pivot=list(pivot), rot=list(rot), cubes=list(cubes), children=list(children))


def scythe_cubes():
    """Blender boxes (x right, y depth, z up) -> model space, scaled by SCYTHE_SCALE.
    blade direction  blender +x -> model -z (forward)
    handle up        blender +z -> model -y (up)
    thickness        blender  y -> model  x
    """
    data = json.load(open(os.path.join(ROOT, "blender/scythe_boxes.json")))
    k = SCYTHE_SCALE
    out = []
    for b in data["boxes"]:
        (x0, y0, z0), (x1, y1, z1) = b["min"], b["max"]
        o = (y0 * k, -z1 * k, -x1 * k)
        s = ((y1 - y0) * k, (z1 - z0) * k, (x1 - x0) * k)
        out.append(cube(b["name"], o, s, None, patch="scythe_" + b["mat"]))
    return out, data["colors"]


def build_spec():
    scythe_list, colors = scythe_cubes()
    scythe = part("scythe", (0, 0, 0), cubes=scythe_list)
    # the hand is at the bottom of the arm; the pivot sits in the middle of the black glove
    scythe_pivot = part("scythe_pivot", (-1, 10.5, 0), children=[scythe])
    spec = [
        part("head", (0, 0, 0), cubes=[
            cube("head", (-4, -8, -4), (8, 8, 8), (0, 0)),
            cube("hat", (-4, -8, -4), (8, 8, 8), (32, 0), grow=0.5),
        ]),
        part("body", (0, 0, 0), cubes=[
            cube("body", (-4, 0, -2), (8, 12, 4), (16, 16)),
            cube("jacket", (-4, 0, -2), (8, 12, 4), (16, 32), grow=0.25),
        ]),
        part("right_arm", (-5, 2, 0), cubes=[
            cube("right_arm", (-3, -2, -2), (4, 12, 4), (40, 16)),
            cube("right_sleeve", (-3, -2, -2), (4, 12, 4), (40, 32), grow=0.25),
        ], children=[scythe_pivot]),
        part("left_arm", (5, 2, 0), cubes=[
            cube("left_arm", (-1, -2, -2), (4, 12, 4), (32, 48)),
            cube("left_sleeve", (-1, -2, -2), (4, 12, 4), (48, 48), grow=0.25),
        ]),
        part("right_leg", (-1.9, 12, 0), cubes=[
            cube("right_leg", (-2, 0, -2), (4, 12, 4), (0, 16)),
            cube("right_pants", (-2, 0, -2), (4, 12, 4), (0, 32), grow=0.25),
        ]),
        part("left_leg", (1.9, 12, 0), cubes=[
            cube("left_leg", (-2, 0, -2), (4, 12, 4), (16, 48)),
            cube("left_pants", (-2, 0, -2), (4, 12, 4), (0, 48), grow=0.25),
        ]),
    ]
    return spec, colors


def collect_cubes(parts, acc=None):
    acc = [] if acc is None else acc
    for p in parts:
        acc += p["cubes"]
        collect_cubes(p["children"], acc)
    return acc


# -------------------------------------------------------------- uv packing ----
def net_size(s):
    w, h, d = s
    return math.ceil(2 * d + 2 * w), math.ceil(d + h)


def pack_patches(cubes):
    """Scythe cubes share one uv patch per material, packed into the right half of the atlas."""
    patches = {}
    for c in cubes:
        if c["uv"] is None:
            nw, nh = net_size(c["s"])
            pw, ph = patches.get(c["patch"], (0, 0))
            patches[c["patch"]] = (max(pw, nw), max(ph, nh))
    pos, x, y, row_h = {}, 64, 0, 0
    for key, (w, h) in sorted(patches.items(), key=lambda kv: (-kv[1][1], -kv[1][0])):
        if x + w > TEX_W:
            x, y, row_h = 64, y + row_h, 0
        pos[key] = (x, y)
        x += w
        row_h = max(row_h, h)
        assert y + row_h <= TEX_H, "atlas full"
    for c in cubes:
        if c["uv"] is None:
            c["uv"] = pos[c["patch"]]
    return patches, pos


# ---------------------------------------------------------------- painting ----
def faces(u, v, w, h, d):
    """Minecraft cuboid net -> rect (x0, y0, x1, y1) per face (front = -z)."""
    u, v, w, h, d = int(u), int(v), int(w), int(h), int(d)
    return {
        "top": (u + d, v, u + d + w, v + d),
        "bottom": (u + d + w, v, u + d + 2 * w, v + d),
        "right": (u, v + d, u + d, v + d + h),                  # -x side
        "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + 2 * d + w, v + d + h),   # +x side
        "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


# approximated from the reference render of the skin
WHITE = rgb("#f4f4f7")
LIGHT = rgb("#e2e3e8")
MID = rgb("#c8c9d1")
DARK = rgb("#a9aab4")
BLACK = rgb("#0b0b0e")
VOID = rgb("#050507")
EYE_TOP = rgb("#ff2d2d")
EYE_BOT = rgb("#b01212")


def marble_field(w, h, seed):
    """Low-res random field, smoothly upscaled and quantised to 4 shades: a pixel 'marble' camo."""
    r = random.Random(seed)
    lw, lh = max(2, w // 4), max(2, h // 4)
    low = Image.new("L", (lw, lh))
    low.putdata([r.randint(0, 255) for _ in range(lw * lh)])
    big = low.resize((w, h), Image.BICUBIC)
    shades = [DARK, MID, LIGHT, WHITE]
    out = Image.new("RGBA", (w, h))
    for y in range(h):
        for x in range(w):
            v = big.getpixel((x, y)) + r.randint(-18, 18)
            out.putpixel((x, y), shades[min(3, max(0, v // 64))])
    return out


def paint_fallback_skin():
    """Approximation of the skin until the real PNG is supplied: white/grey camo, black hands and
    feet, a hood with a black face, big red eyes and a black 'V' running down the chest."""
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    camo = marble_field(64, 64, 303)

    def put_region(rect, color=None):
        x0, y0, x1, y1 = rect
        for y in range(y0, y1):
            for x in range(x0, x1):
                img.putpixel((x, y), color if color else camo.getpixel((x, y)))

    def cube_regions(u, v, w, h, d, color=None):
        for r in faces(u, v, w, h, d).values():
            put_region(r, color)

    cube_regions(0, 0, 8, 8, 8)                 # head (inner) -- the face lives here
    cube_regions(32, 0, 8, 8, 8)                # hat = the hood
    cube_regions(16, 16, 8, 12, 4)              # body
    cube_regions(40, 16, 4, 12, 4)              # right arm
    cube_regions(32, 48, 4, 12, 4)              # left arm
    cube_regions(0, 16, 4, 12, 4)               # right leg
    cube_regions(16, 48, 4, 12, 4)              # left leg

    # black hands (bottom 3 rows of the arms) and feet (bottom 2 rows of the legs)
    for (u, v), rows in (((40, 16), 3), ((32, 48), 3), ((0, 16), 2), ((16, 48), 2)):
        f = faces(u, v, 4, 12, 4)
        for name in ("right", "front", "left", "back"):
            x0, y0, x1, y1 = f[name]
            put_region((x0, y1 - rows, x1, y1), BLACK)
        put_region(f["bottom"], BLACK)

    # the face: a black 'dagger' shape with big red eyes
    x0, y0, _, _ = faces(0, 0, 8, 8, 8)["front"]
    mask = {1: (2, 5), 2: (1, 6), 3: (1, 6), 4: (2, 5), 5: (3, 4), 6: (3, 4), 7: (3, 4)}
    for row, (a, b) in mask.items():
        for x in range(a, b + 1):
            img.putpixel((x0 + x, y0 + row), VOID)
    for ex in (1, 5):
        for dx in range(2):
            img.putpixel((x0 + ex + dx, y0 + 2), EYE_TOP)
            img.putpixel((x0 + ex + dx, y0 + 3), EYE_BOT)

    # the hood (hat layer) has an opening where the black face is
    hx, hy, _, _ = faces(32, 0, 8, 8, 8)["front"]
    for row, (a, b) in mask.items():
        for x in range(a, b + 1):
            img.putpixel((hx + x, hy + row), (0, 0, 0, 0))

    # the black V on the chest, continuing from the face
    bx, by, _, _ = faces(16, 16, 8, 12, 4)["front"]
    for row, (a, b) in {0: (2, 5), 1: (3, 4), 2: (3, 4), 3: (3, 4), 4: (3, 4)}.items():
        for x in range(a, b + 1):
            img.putpixel((bx + x, by + row), BLACK)
    return img


def load_skin():
    if os.path.exists(SKIN_IN):
        skin = Image.open(SKIN_IN).convert("RGBA")
        if skin.size == (64, 32):                   # legacy 64x32 skin: mirror the arm/leg to fill the rest
            full = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
            full.paste(skin, (0, 0))
            for src, dst in (((0, 16, 16, 32), (16, 48)), ((40, 16, 56, 32), (32, 48))):
                full.paste(skin.crop(src).transpose(Image.FLIP_LEFT_RIGHT), dst)
            skin = full
        assert skin.size == (64, 64), f"skin must be 64x64, got {skin.size}"
        return skin, True
    return paint_fallback_skin(), False


def paint_atlas(skin, cubes, patches, colors):
    img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
    img.paste(skin, (0, 0))
    done = set()
    for c in cubes:
        if c["patch"] and c["patch"] not in done:
            mat = c["patch"].replace("scythe_", "", 1)
            u, v = c["uv"]
            pw, ph = patches[c["patch"]]
            col = rgb(colors[mat])
            for y in range(v, v + ph):
                for x in range(u, u + pw):
                    img.putpixel((x, y), col)
            done.add(c["patch"])
    return img


def is_red(p):
    return p[3] > 200 and p[0] > 150 and p[1] < 90 and p[2] < 90


def paint_glow_layers(skin):
    """Emissive layers. eyes = every strongly red pixel of the skin (so it follows the real skin).
    rage = eyes + the black face glowing dark red."""
    eyes = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
    rage = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
    for y in range(64):
        for x in range(64):
            p = skin.getpixel((x, y))
            if is_red(p):
                eyes.putpixel((x, y), p)
                rage.putpixel((x, y), (255, 60, 40, 255))
    for (u, v) in ((0, 0), (32, 0)):                # head front, inner and hood layer
        x0, y0, x1, y1 = faces(u, v, 8, 8, 8)["front"]
        for y in range(y0, y1):
            for x in range(x0, x1):
                p = skin.getpixel((x, y))
                if p[3] > 200 and max(p[:3]) < 30:  # the black face
                    rage.putpixel((x, y), (96, 0, 0, 255))
    return eyes, rage


# -------------------------------------------------------------------- java ----
def f(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s + "F"


def java_part(p, parent_var, indent, lines):
    var = p["name"]
    pad = "\t" * indent
    expr = "CubeListBuilder.create()"
    for c in p["cubes"]:
        u, v = c["uv"]
        o, s = c["o"], c["s"]
        grow = f", new CubeDeformation({f(c['grow'])})" if c["grow"] else ""
        expr += f"\n{pad}\t.texOffs({u}, {v}).addBox({f(o[0])}, {f(o[1])}, {f(o[2])}, {f(s[0])}, {f(s[1])}, {f(s[2])}{grow})"
    px, py, pz = p["pivot"]
    rx, ry, rz = p["rot"]
    pose = (f"PartPose.offsetAndRotation({f(px)}, {f(py)}, {f(pz)}, {f(rx)}, {f(ry)}, {f(rz)})"
            if (rx or ry or rz) else f"PartPose.offset({f(px)}, {f(py)}, {f(pz)})")
    lines.append(f'{pad}PartDefinition {var} = {parent_var}.addOrReplaceChild("{p["name"]}", {expr},\n{pad}\t{pose});')
    for ch in p["children"]:
        java_part(ch, var, indent, lines)


def write_java(spec):
    lines = []
    for p in spec:
        java_part(p, "root", 2, lines)
    body = "\n\n".join(lines)
    src = f"""// GENERATED by tools/gen_assets.py (player-skin layout + the scythe from blender/scythe_boxes.json).
// Do not edit by hand: change the spec in the script and run it again.
package com.entity303.client.render;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

public final class Entity303Geometry {{
	private Entity303Geometry() {{
	}}

	public static LayerDefinition createBodyLayer() {{
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

{body}

		return LayerDefinition.create(mesh, {TEX_W}, {TEX_H});
	}}
}}
"""
    os.makedirs(os.path.dirname(JAVA_OUT), exist_ok=True)
    with open(JAVA_OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(src)


def write_animations_java():
    """tools/animations.py -> Entity303Animations.java (common code: used by the entity and the model)."""
    names = list(anim_data.ANIMS)
    by_id = sorted(anim_data.ANIMS.items(), key=lambda kv: kv[1]["id"])
    assert [a["id"] for _, a in by_id] == list(range(len(by_id))), "animation ids must be 0..n-1"
    L = []
    L.append("// GENERATED by tools/gen_assets.py from tools/animations.py. Do not edit by hand.")
    L.append("package com.entity303.anim;")
    L.append("")
    L.append("public final class Entity303Animations {")
    for name, a in by_id:
        L.append(f"	public static final int {name.upper()} = {a['id']};")
    L.append(f"	public static final int COUNT = {len(by_id)};")
    L.append("")
    L.append("	// channel layout: for every part rx, ry, rz in radians, then the root offset x, y, z in model pixels")
    for i, p in enumerate(anim_data.PARTS):
        L.append(f"	public static final int {p.upper()} = {i * 3};")
    L.append(f"	public static final int OFFSET = {len(anim_data.PARTS) * 3};")
    L.append(f"	public static final int CHANNELS = {anim_data.CHANNELS};")
    L.append(f"	/** rotation of scythe_pivot (0 = x, 1 = y, 2 = z) that the continuous spin is added to */")
    L.append(f"	public static final int SPIN_AXIS = {anim_data.SPIN_AXIS};")
    L.append("	/** roll (radians) of the scythe about its own handle while it is held as a wheel; scaled by the wheel weight */")
    L.append(f"	public static final float WHEEL_FLIP = {math.radians(anim_data.WHEEL_FLIP):.6f}F;")
    L.append("")
    L.append("	public static final int[] DURATION = {" + ", ".join(str(a["duration"]) for _, a in by_id) + "};")
    L.append("	public static final boolean[] LOOP = {" + ", ".join("true" if a["loop"] else "false" for _, a in by_id) + "};")
    L.append("")
    for name, a in by_id:
        for ev, tick in a["events"].items():
            L.append(f"	public static final int {name.upper()}_{ev.upper()} = {tick};")
    L.append("")
    L.append("	// KEYS[animation][key] = {time, spinX, spinY, channel0, channel1, ...}")
    L.append("	private static final float[][][] KEYS = {")
    for name, a in by_id:
        L.append("		{ // " + name)
        for t, sx, sy, ch in anim_data.resolve(a):
            vals = [t, sx, sy] + [math.radians(v) if i < len(anim_data.PARTS) * 3 else v for i, v in enumerate(ch)]
            L.append("			{" + ", ".join(f(v) for v in vals) + "},")
        L.append("		},")
    L.append("	};")
    L.append("""
	private Entity303Animations() {
	}

	/** Duration in ticks of an animation. */
	public static int duration(int anim) {
		return DURATION[anim];
	}

	/** True for the resting loops (normal idle and the final-phase one): he is not in the middle of a move. */
	public static boolean isIdle(int anim) {
		return anim == IDLE@IDLE_EXTRA@;
	}

	/**
	 * Samples an animation at tick t (blended with a smoothstep). Writes CHANNELS values into out and returns the
	 * windmill/roll spin rates (radians per tick) in spin[0], spin[1].
	 */
	public static void sample(int anim, float t, float[] out, float[] spin) {
		float[][] keys = KEYS[anim];
		if (LOOP[anim]) {
			t = t % DURATION[anim];
		}
		t = Math.max(keys[0][0], Math.min(keys[keys.length - 1][0], t));
		for (int i = 0; i < keys.length - 1; i++) {
			float[] a = keys[i];
			float[] b = keys[i + 1];
			if (t >= a[0] && t <= b[0]) {
				float x = b[0] > a[0] ? (t - a[0]) / (b[0] - a[0]) : 1.0F;
				float k = x * x * (3.0F - 2.0F * x);
				spin[0] = a[1] + (b[1] - a[1]) * k;
				spin[1] = a[2] + (b[2] - a[2]) * k;
				for (int c = 0; c < CHANNELS; c++) {
					out[c] = a[3 + c] + (b[3 + c] - a[3 + c]) * k;
				}
				return;
			}
		}
		float[] last = keys[keys.length - 1];
		spin[0] = last[1];
		spin[1] = last[2];
		for (int c = 0; c < CHANNELS; c++) {
			out[c] = last[3 + c];
		}
	}
}""".replace("@IDLE_EXTRA@", " || anim == IDLE_RAGE" if "idle_rage" in anim_data.ANIMS else ""))
    os.makedirs(os.path.dirname(ANIM_OUT), exist_ok=True)
    with open(ANIM_OUT, "w", encoding="utf-8", newline=NL) as fh:
        fh.write(NL.join(L) + NL)


def paint_egg():
    """16x16 spawn-egg icon: a pale egg with the black face and the red eyes."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    outline, light, base, shade = rgb("#3a3a42"), rgb("#f4f4f7"), rgb("#d6d7de"), rgb("#a9aab4")
    for y in range(1, 15):
        half = 6.6 * math.sqrt(max(0.0, 1.0 - ((y - 8.2) / 7.4) ** 2)) * (0.78 + 0.22 * (y - 1) / 13)
        for x in range(16):
            d = abs(x - 7.5)
            if d <= half:
                edge = d > half - 1.0 or y in (1, 14)
                col = outline if edge else (light if x < 6 else (shade if x > 10 else base))
                img.putpixel((x, y), col)
    for row, (a, b) in {4: (5, 10), 5: (5, 10), 6: (6, 9), 7: (7, 8), 8: (7, 8), 9: (7, 8)}.items():
        for x in range(a, b + 1):
            img.putpixel((x, row), VOID)
    for x in (5, 6, 9, 10):
        img.putpixel((x, 5), EYE_TOP)
    return img


def _scythe_item_from_boxes(size):
    """Fallback icon (no Blender needed): the Blender boxes seen from the side, turned 45 degrees clockwise."""
    data = json.load(open(os.path.join(ROOT, "blender/scythe_boxes.json")))
    cols = {k: rgb(v) for k, v in data["colors"].items()}
    flip = -1 if ITEM_BLADE_LEFT else 1          # -1: the blade sweeps to the upper left, over the handle
    boxes = [(min(flip * b["min"][0], flip * b["max"][0]), max(flip * b["min"][0], flip * b["max"][0]),
              b["min"][2], b["max"][2], cols[b["mat"]]) for b in data["boxes"]]   # later wins
    xs = [v for b in boxes for v in b[:2]]
    zs = [v for b in boxes for v in b[2:4]]
    cx, cz = (min(xs) + max(xs)) / 2, (min(zs) + max(zs)) / 2
    c = s = math.sqrt(0.5)
    # how large the drawing is after the 45 degree turn: scale it to fill the canvas (leaving a 2 px margin)
    corners = [(x - cx, z - cz) for x in (min(xs), max(xs)) for z in (min(zs), max(zs))]
    reach = max(max(abs(dx * c + dz * s), abs(-dx * s + dz * c)) for dx, dz in corners)
    k = (size / 2 - 2) / reach                    # output pixels per Blender pixel
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for py in range(size):
        for px in range(size):
            r, u = (px + 0.5 - size / 2) / k, (size / 2 - (py + 0.5)) / k
            x, z = cx + r * c - u * s, cz + r * s + u * c
            for x0, x1, z0, z1, col in boxes:
                if x0 <= x < x1 and z0 <= z < z1:
                    img.putpixel((px, py), col)
    return img


def paint_scythe_item(size=64):
    """Icon of the scythe the boss drops (a netherite axe wearing it): the render from blender/render_item_icon.py
    (blender -b -P blender/render_item_icon.py) when blender/scythe_item_raw.png exists, else a drawing made from
    the scythe boxes; with a dark outline, centred in the canvas."""
    raw = os.path.join(ROOT, "blender/scythe_item_raw.png")
    if os.path.exists(raw):
        img = Image.open(raw).convert("RGBA")
        if img.size != (size, size):
            img = img.resize((size, size), Image.NEAREST)
        img.putdata([(r, g, b, 255 if a >= 128 else 0) for r, g, b, a in img.getdata()])   # hard edges
    else:
        img = _scythe_item_from_boxes(size)
    out, dark = img.copy(), rgb("#17171c")
    for py in range(size):
        for px in range(size):
            if img.getpixel((px, py))[3] == 0 and any(
                    0 <= px + dx < size and 0 <= py + dy < size and img.getpixel((px + dx, py + dy))[3]
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.putpixel((px, py), dark)
    box = out.getbbox()                     # centre the drawing in the canvas
    cut = out.crop(box)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(cut, ((size - cut.width) // 2, (size - cut.height) // 2))
    return out


def main():
    spec, colors = build_spec()
    cubes = collect_cubes(spec)
    patches, _ = pack_patches(cubes)
    skin, real = load_skin()
    os.makedirs(TEX_DIR, exist_ok=True)
    paint_atlas(skin, cubes, patches, colors).save(os.path.join(TEX_DIR, "entity303.png"))
    eyes, rage = paint_glow_layers(skin)
    eyes.save(os.path.join(TEX_DIR, "entity303_eyes.png"))
    rage.save(os.path.join(TEX_DIR, "entity303_rage.png"))
    item_dir = os.path.join(ROOT, "src/main/resources/assets", MOD_ID, "textures/item")
    os.makedirs(item_dir, exist_ok=True)
    paint_egg().save(os.path.join(item_dir, "entity_303_spawn_egg.png"))
    paint_scythe_item().save(os.path.join(item_dir, "reaper_scythe.png"))
    write_java(spec)
    write_animations_java()
    with open(os.path.join(ROOT, "tools/entity303_spec.json"), "w") as fh:
        json.dump({"tex": [TEX_W, TEX_H], "spec": spec, "scythe_scale": SCYTHE_SCALE}, fh, indent=1)
    if not real:
        os.makedirs(os.path.join(ROOT, "skin"), exist_ok=True)
        skin.save(os.path.join(ROOT, "skin/_fallback_preview.png"))
    print(f"cubes={len(cubes)} skin={'REAL' if real else 'fallback (put the real PNG at skin/entity303_skin.png)'}")


if __name__ == "__main__":
    main()
