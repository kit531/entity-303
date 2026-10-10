"""Icon of the Reaper's Scythe that the boss drops, modelled and rendered in Blender.

Run (headless):
    blender -b -P blender/render_item_icon.py

Writes blender/scythe_item_raw.png (64x64, transparent, no anti-aliasing).  tools/gen_assets.py adds the dark outline
and saves it as assets/entity303/textures/item/reaper_scythe.png (it falls back to a drawing made from
scythe_boxes.json when this file is missing).

The scythe is modelled upright (x right, z up, thickness along y) with a real curved blade instead of the
stepped boxes of the in-game model, then the camera is rolled 45 degrees so the handle runs from the bottom left
to the top right like the vanilla tools.
"""
import bpy, math, os

HERE = os.path.dirname(os.path.abspath(__file__))
SIZE = 64
MARGIN = 2                      # px of empty border
# blade sweeps to the lower right of the icon (True = over the handle to the upper left);
# SCYTHE_BLADE=left|right in the environment overrides it for a quick comparison
BLADE_LEFT = os.environ.get("SCYTHE_BLADE", "right") == "left"
ROLL = 45.0                     # degrees clockwise

COLORS = {
    "handle": "#5b3b20", "grip": "#3e2713", "collar": "#2f3037",
    "steel": "#9fa1aa", "spine": "#5a5c66", "edge": "#e4e6ec", "tip": "#25262c",
}


def srgb_to_linear(h):
    h = h.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4) for v in c] + [1.0]


MATS = {}
VERTS = []                      # every (x, z) of the model, to frame the camera


def material(name):
    if name not in MATS:
        m = bpy.data.materials.new(name)
        m.diffuse_color = srgb_to_linear(COLORS[name])
        MATS[name] = m
    return MATS[name]


def prism(name, poly, mat, thickness=2.0):
    """Extrude a polygon given as (x, z) points along y (centred on y = 0)."""
    h = thickness / 2.0
    n = len(poly)
    verts = [(x, -h, z) for x, z in poly] + [(x, h, z) for x, z in poly]
    faces = [list(range(n)), list(range(2 * n - 1, n - 1, -1))]
    for i in range(n):
        j = (i + 1) % n
        faces.append([i, j, n + j, n + i])
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    obj.data.materials.append(material(mat))
    bpy.context.scene.collection.objects.link(obj)
    VERTS.extend(poly)
    return obj


def rect(name, x0, x1, z0, z1, mat, thickness=2.0):
    return prism(name, [(x0, z0), (x1, z0), (x1, z1), (x0, z1)], mat, thickness)


def bezier(p0, p1, p2, t):
    a, b, c = (1 - t) ** 2, 2 * t * (1 - t), t * t
    return (a * p0[0] + b * p1[0] + c * p2[0], a * p0[1] + b * p1[1] + c * p2[1])


def blade(sx):
    """A tapering crescent: centre line = a quadratic Bezier from the collar, up and over, down to the tip.
    s = -1 is the cutting edge (lower side), +1 the spine (upper side)."""
    p0, p1, p2 = (-1.0, 21.0), (-12.0, 33.0), (-24.0, 9.0)
    half0 = 3.4
    steps = 28

    def point(t, s):
        x, z = bezier(p0, p1, p2, t)
        e = 1e-3
        x2, z2 = bezier(p0, p1, p2, min(1.0, t + e))
        x1, z1 = bezier(p0, p1, p2, max(0.0, t - e))
        tx, tz = x2 - x1, z2 - z1
        ln = math.hypot(tx, tz)
        tx, tz = tx / ln, tz / ln
        nx, nz = -tz, tx                      # left normal; for a blade running to -x this points down
        half = half0 * (1.0 - t) ** 0.75 + 0.05
        # s = +1 (spine) is the upper side = -normal
        return (sx * (x - nx * s * half), z - nz * s * half)

    def strip(name, t0, t1, s0, s1, mat):
        ts = [t0 + (t1 - t0) * i / steps for i in range(steps + 1)]
        poly = [point(t, s0) for t in ts] + [point(t, s1) for t in reversed(ts)]
        prism(name, poly, mat, 2.0 if mat != "edge" else 1.0)

    for tag, t0, t1 in (("a", 0.0, 0.86), ("tip", 0.86, 1.0)):
        dark = tag == "tip"
        strip("edge_" + tag, t0, t1, -1.0, -0.45, "tip" if dark else "edge")
        strip("body_" + tag, t0, t1, -0.45, 0.4, "tip" if dark else "steel")
        strip("spine_" + tag, t0, t1, 0.4, 1.0, "tip" if dark else "spine")
    # the short spike at the back of the blade
    prism("spike", [(sx * -1.6, 21.0), (sx * 1.4, 21.0), (sx * 0.1, 29.5)], "steel", 2.0)


def build():
    rect("handle", -1.0, 1.0, -16.0, 21.0, "handle")
    rect("grip", -1.5, 1.5, -5.0, 3.0, "grip", 3.0)
    rect("pommel", -1.3, 1.3, -18.5, -16.0, "collar", 2.6)
    rect("collar", -2.0, 2.0, 18.0, 21.5, "collar", 3.0)
    blade(1.0 if BLADE_LEFT else -1.0)


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    build()

    # frame: rotate the points like the camera roll will, then centre them and fit the largest reach
    a = math.radians(ROLL)
    ca, sa = math.cos(a), math.sin(a)
    cx = (min(x for x, _ in VERTS) + max(x for x, _ in VERTS)) / 2
    cz = (min(z for _, z in VERTS) + max(z for _, z in VERTS)) / 2
    reach = max(max(abs((x - cx) * ca + (z - cz) * sa), abs(-(x - cx) * sa + (z - cz) * ca)) for x, z in VERTS)

    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = 2.0 * reach * SIZE / (SIZE - 2 * MARGIN)
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    cam.location = (cx, -60.0, cz)
    # looking along +y; the roll about the view axis turns the picture clockwise by ROLL degrees
    cam.rotation_euler = (math.radians(90.0), math.radians(-ROLL), 0.0)
    cam_data.clip_end = 500
    scene.camera = cam

    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "FLAT"
    scene.display.shading.color_type = "MATERIAL"
    scene.display.render_aa = "OFF"
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.render.resolution_x = scene.render.resolution_y = SIZE
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.filepath = os.path.join(HERE, "scythe_item_raw.png")
    bpy.ops.render.render(write_still=True)
    print("DONE", scene.render.filepath)


main()
