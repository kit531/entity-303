"""Entity 303 pose preview, rendered in Blender with the SAME model, texture and animation data as the mod.

    blender -b -P blender/preview_entity.py -- [--view front|side|back] [--out DIR] [anim:tick ...]

Example:   blender -b -P blender/preview_entity.py -- sweep:4 sweep:12 slam:36
Without frame arguments a default gallery of all attacks is rendered.  Frames land in <out>/<anim>_<tick>.png
(default out dir: preview/frames).  tools/sheet.py turns them into one contact sheet.

It mirrors what the game does: ModelPart transforms (translate to the pivot, rotate Z*Y*X), the vanilla cube UV
layout, the renderer's scale(-1,-1,1) / scale(1.5) / translate(0,-1.501,0), and the generated animation keyframes.
"""
import bpy, json, math, os, sys
from mathutils import Matrix, Vector

ROOT = os.environ.get("ENTITY303_ROOT", "C:/rb")
sys.path.insert(0, os.path.join(ROOT, "tools"))
import animations as A  # noqa: E402

SCALE = 1.5            # Entity303Renderer.SCALE
TEX = 128

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
view, outdir, frames = "front", os.path.join(ROOT, "preview/frames"), []
i = 0
while i < len(argv):
    if argv[i] == "--view":
        view = argv[i + 1]; i += 2
    elif argv[i] == "--out":
        outdir = argv[i + 1]; i += 2
    else:
        a, t = argv[i].split(":")
        frames.append((a, float(t))); i += 1
if not frames:
    frames = [("idle", 0), ("idle", 40), ("sweep", 4), ("sweep", 8), ("sweep", 12), ("sweep", 18),
              ("slash", 14), ("slash", 17), ("step", 8), ("step", 11), ("summon", 24), ("drain", 20),
              ("whirl", 14), ("whirl", 30), ("slam", 22), ("slam", 36), ("roar", 20)]
os.makedirs(outdir, exist_ok=True)

spec = json.load(open(os.path.join(ROOT, "tools/entity303_spec.json")))["spec"]


# ----------------------------------------------------------------- pose / FK --
def rot_zyx(rx, ry, rz):
    return Matrix.Rotation(rz, 4, "Z") @ Matrix.Rotation(ry, 4, "Y") @ Matrix.Rotation(rx, 4, "X")


def spin_angles(anim, t):
    """Scythe spin accumulated by the client over the first t ticks of the animation (like Entity303.tick())."""
    sx = sy = 0.0
    for k in range(int(t)):
        _, (rx, ry) = A.sample(A.ANIMS[anim], k)
        sx += rx
        sy += ry
    return sx, sy


def pose_for(anim_name, t):
    anim = A.ANIMS[anim_name]
    ch, _ = A.sample(anim, t)
    sx, sy = spin_angles(anim_name, t)
    out = {}
    for idx, p in enumerate(A.PARTS):
        out[p] = [math.radians(v) for v in ch[idx * 3:idx * 3 + 3]]
    out["scythe_pivot"][0] += sx
    out["scythe"][1] += sy
    off = ch[len(A.PARTS) * 3:]
    return out, off


# Minecraft faces -> 4 corners (xsel, ysel, zsel) with (us, vs) in the face's uv rect.  sel 0 = min, 1 = max.
FACES = {
    "front": [((0, 0, 0), (0, 0)), ((1, 0, 0), (1, 0)), ((1, 1, 0), (1, 1)), ((0, 1, 0), (0, 1))],
    "back":  [((1, 0, 1), (0, 0)), ((0, 0, 1), (1, 0)), ((0, 1, 1), (1, 1)), ((1, 1, 1), (0, 1))],
    "right": [((0, 0, 1), (0, 0)), ((0, 0, 0), (1, 0)), ((0, 1, 0), (1, 1)), ((0, 1, 1), (0, 1))],
    "left":  [((1, 0, 0), (0, 0)), ((1, 0, 1), (1, 0)), ((1, 1, 1), (1, 1)), ((1, 1, 0), (0, 1))],
    "top":   [((0, 0, 1), (0, 0)), ((1, 0, 1), (1, 0)), ((1, 0, 0), (1, 1)), ((0, 0, 0), (0, 1))],
    "bottom": [((0, 1, 1), (0, 0)), ((1, 1, 1), (1, 0)), ((1, 1, 0), (1, 1)), ((0, 1, 0), (0, 1))],
}


def face_rects(u, v, w, h, d):
    return {
        "top": (u + d, v, u + d + w, v + d),
        "bottom": (u + d + w, v, u + d + 2 * w, v + d),
        "right": (u, v + d, u + d, v + d + h),
        "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + 2 * d + w, v + d + h),
        "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


def to_blender(p):
    """model pixels (y down, front = -z) -> blender metres (z up, the entity faces -Y), as the renderer does."""
    mx, my, mz = p
    k = SCALE / 16.0
    return (mx * k, mz * k, (1.501 * 16 - my) * k)


def build_geometry(pose, off):
    verts, faces, uvs = [], [], []

    def walk(part, parent):
        r = pose.get(part["name"], [0, 0, 0])
        rx, ry, rz = (part["rot"][i] + r[i] for i in range(3))
        m = parent @ Matrix.Translation(Vector(part["pivot"])) @ rot_zyx(rx, ry, rz)
        for c in part["cubes"]:
            add_cube(c, m)
        for ch in part["children"]:
            walk(ch, m)

    def add_cube(c, m):
        (ox, oy, oz), (w, h, d), g = c["o"], c["s"], c["grow"]
        lo = (ox - g, oy - g, oz - g)
        hi = (ox + w + g, oy + h + g, oz + d + g)
        centre = to_blender(tuple(m @ Vector(((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2))))
        rects = face_rects(*c["uv"], w, h, d)
        for name, corners in FACES.items():
            x0, y0, x1, y1 = rects[name]
            pts, uv = [], []
            for (xs, ys, zs), (us, vs) in corners:
                lp = Vector((hi[0] if xs else lo[0], hi[1] if ys else lo[1], hi[2] if zs else lo[2]))
                pts.append(Vector(to_blender(tuple(m @ lp))))
                uv.append(((x0 + (x1 - x0) * us) / TEX, 1.0 - (y0 + (y1 - y0) * vs) / TEX))
            # make the winding point away from the cube centre (blender computes the normal from the order)
            n = (pts[1] - pts[0]).cross(pts[2] - pts[0])
            fc = sum(pts, Vector()) / 4.0
            if n.dot(fc - Vector(centre)) < 0:
                pts.reverse(); uv.reverse()
            base = len(verts)
            verts.extend(tuple(p) for p in pts)
            faces.append((base, base + 1, base + 2, base + 3))
            uvs.extend(uv)

    # the model's root part: the "root" channels (whole-body spin / jump) act on everything
    rr = pose["root"]
    root_m = Matrix.Translation(Vector(off)) @ rot_zyx(*rr)
    for p in spec:
        walk(p, root_m)
    return verts, faces, uvs


# -------------------------------------------------------------------- scene ---
def setup_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    tex_dir = os.path.join(ROOT, "src/main/resources/assets/entity303/textures/entity")
    main_img = bpy.data.images.load(os.path.join(tex_dir, "entity303.png"))
    eyes_img = bpy.data.images.load(os.path.join(tex_dir, "entity303_eyes.png"))
    for im in (main_img, eyes_img):
        im.alpha_mode = "STRAIGHT"

    mat = bpy.data.materials.new("entity303")
    mat.use_nodes = True
    try:
        mat.surface_render_method = "DITHERED"
    except Exception:
        pass
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    t_main = nt.nodes.new("ShaderNodeTexImage"); t_main.image = main_img; t_main.interpolation = "Closest"
    t_eyes = nt.nodes.new("ShaderNodeTexImage"); t_eyes.image = eyes_img; t_eyes.interpolation = "Closest"
    nt.links.new(t_main.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(t_main.outputs["Alpha"], bsdf.inputs["Alpha"])
    nt.links.new(t_eyes.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 6.0
    bsdf.inputs["Roughness"].default_value = 1.0
    bsdf.inputs["Specular IOR Level"].default_value = 0.0

    # ground + backdrop
    bpy.ops.mesh.primitive_plane_add(size=40, location=(0, 0, -0.001))
    ground = bpy.context.object
    gm = bpy.data.materials.new("ground"); gm.use_nodes = True
    gm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.05, 0.08, 1)
    gm.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 1.0
    ground.data.materials.append(gm)

    world = bpy.data.worlds.new("w"); world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.04, 0.04, 0.09, 1)
    scene.world = world
    for loc, rot, energy, col in (((0, 0, 0), (math.radians(55), 0, math.radians(35)), 3.2, (1, 0.95, 0.9)),
                                   ((0, 0, 0), (math.radians(70), 0, math.radians(-140)), 1.4, (0.55, 0.6, 1.0))):
        ld = bpy.data.lights.new("sun", "SUN"); ld.energy = energy; ld.color = col
        lo = bpy.data.objects.new("sun", ld); scene.collection.objects.link(lo); lo.rotation_euler = rot

    cam_data = bpy.data.cameras.new("cam"); cam_data.lens = 50
    cam = bpy.data.objects.new("cam", cam_data); scene.collection.objects.link(cam)
    target = bpy.data.objects.new("target", None); scene.collection.objects.link(target)
    target.location = (0, 0, 2.05)
    positions = {"front": (-3.4, -8.0, 2.8), "side": (-8.6, 0.6, 2.6), "back": (3.4, 8.0, 2.8)}
    cam.location = positions[view]
    con = cam.constraints.new("TRACK_TO"); con.target = target
    con.track_axis = "TRACK_NEGATIVE_Z"; con.up_axis = "UP_Y"
    scene.camera = cam

    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x, scene.render.resolution_y = 440, 640
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    return scene, mat


def main():
    scene, mat = setup_scene()
    obj = None
    for anim, t in frames:
        pose, off = pose_for(anim, t)
        verts, faces, uvs = build_geometry(pose, off)
        if obj:
            bpy.data.objects.remove(obj, do_unlink=True)
        mesh = bpy.data.meshes.new("entity303")
        mesh.from_pydata(verts, [], faces)
        mesh.update()
        layer = mesh.uv_layers.new(name="UV")
        for li, uv in enumerate(uvs):
            layer.data[li].uv = uv
        mesh.materials.append(mat)
        obj = bpy.data.objects.new("entity303", mesh)
        scene.collection.objects.link(obj)
        scene.render.filepath = os.path.join(outdir, f"{anim}_{int(t):03d}.png")
        bpy.ops.render.render(write_still=True)
        print("rendered", scene.render.filepath)


main()
