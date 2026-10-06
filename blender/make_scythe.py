"""Reaper scythe, modelled in Blender.

Run (headless):
    blender -b -P make_scythe.py

Outputs (next to this script):
    scythe.blend        - the model with the twirl animation
    scythe.glb          - same, as glTF (for other tools / previews)
    scythe_boxes.json   - every box of the model in Minecraft pixels; tools/gen_assets.py
                          turns this into the Java model code the mod uses in-game
    preview_sheet.png   - 8 frames of the twirl

Coordinates: 1 unit = 16 px = 1 Minecraft block.  X = right, Z = up, Y = depth.
The origin is the point where the Reaper's hand grips the handle, and the
twirl rotates around the Y axis (perpendicular to the blade), like a baton.
"""
import bpy, math, os, json
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PX = 1 / 16

# ---------------------------------------------------------------- geometry ---
# material -> colour (sRGB hex), taken from the reference render: brown wooden handle,
# grey steel blade with a lighter cutting edge, darker spine and a near-black tip.
COLORS = {
    "handle": "#5b3b20",
    "grip":   "#3e2713",
    "collar": "#2f3037",
    "steel":  "#9fa1aa",
    "spine":  "#5a5c66",
    "edge":   "#d6d8df",
    "tip":    "#25262c",
}
EMISSIVE = {}

HANDLE_BOTTOM, HANDLE_TOP = -14, 18          # z range of the handle (px); origin = centre of the grip
BLADE_BASE = 20                               # z where the blade starts (px)

# Blade as pixel columns: x -> (zmin, zmax) relative to BLADE_BASE, inclusive pixels.
BLADE_COLUMNS = {
    2: (-1, 3), 3: (-1, 4), 4: (-1, 5), 5: (0, 5), 6: (0, 6), 7: (1, 6),
    8: (1, 7), 9: (2, 7), 10: (2, 7), 11: (2, 7), 12: (2, 6), 13: (1, 6),
    14: (1, 5), 15: (0, 5), 16: (-1, 4), 17: (-2, 4), 18: (-4, 3), 19: (-6, 3),
    20: (-8, 2), 21: (-10, 1), 22: (-12, 0),
}
# short spike pointing up at the back of the blade (x -> zmax relative to base)
SPIKE_COLUMNS = {-1: 4, 0: 7, 1: 6}


def build_boxes():
    """Return a list of dicts {name, mat, min, max} in px (Blender axes)."""
    boxes = []

    def add(name, mat, x0, x1, y0, y1, z0, z1):
        boxes.append({"name": name, "mat": mat, "min": [x0, y0, z0], "max": [x1, y1, z1]})

    add("handle", "handle", -1, 1, -1, 1, HANDLE_BOTTOM, HANDLE_TOP)
    add("grip", "grip", -1.5, 1.5, -1.5, 1.5, -4, 3)        # the hand wraps this part
    add("pommel", "collar", -1, 1, -1, 1, HANDLE_BOTTOM - 2, HANDLE_BOTTOM)
    add("collar", "collar", -2, 2, -2, 2, HANDLE_TOP, BLADE_BASE)

    for x, zmax in SPIKE_COLUMNS.items():
        add(f"spike_{x}", "steel", x, x + 1, -1, 1, BLADE_BASE, BLADE_BASE + zmax)

    # merge neighbouring columns that have identical spans
    cols = sorted(BLADE_COLUMNS.items())
    runs = []
    for x, span in cols:
        if runs and runs[-1]["span"] == span and runs[-1]["x1"] == x:
            runs[-1]["x1"] = x + 1
        else:
            runs.append({"x0": x, "x1": x + 1, "span": span})
    for i, r in enumerate(runs):
        zmin, zmax = r["span"]
        z0 = BLADE_BASE + zmin
        z1 = BLADE_BASE + zmax + 1
        tip = r["x0"] >= 20                      # the last columns are near-black, like in the render
        # cutting edge = lowest pixel row, 1 px thick and brightest
        add(f"edge_{i}", "tip" if tip else "edge", r["x0"], r["x1"], -0.5, 0.5, z0, z0 + 1)
        # body of the blade, 2 px thick; the top pixel row is the darker spine
        add(f"blade_{i}", "tip" if tip else "steel", r["x0"], r["x1"], -1, 1, z0 + 1, z1 - 1)
        add(f"spine_{i}", "tip" if tip else "spine", r["x0"], r["x1"], -1, 1, z1 - 1, z1)
    return boxes


# --------------------------------------------------------------- blender ----
def srgb_to_linear(h):
    h = h.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4) for v in c] + [1.0]


def make_material(name):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    col = srgb_to_linear(COLORS[name])
    b.inputs["Base Color"].default_value = col
    b.inputs["Roughness"].default_value = 0.55 if name in ("steel", "edge", "spine") else 0.8
    b.inputs["Metallic"].default_value = 0.7 if name in ("steel", "edge", "spine") else 0.0
    if name in EMISSIVE:
        b.inputs["Emission Color"].default_value = col
        b.inputs["Emission Strength"].default_value = EMISSIVE[name]
    return m


def add_box_object(box, mats):
    (x0, y0, z0), (x1, y1, z1) = box["min"], box["max"]
    bpy.ops.mesh.primitive_cube_add(size=1,
        location=(((x0 + x1) / 2) * PX, ((y0 + y1) / 2) * PX, ((z0 + z1) / 2) * PX))
    o = bpy.context.object
    o.name = box["name"]
    o.scale = ((x1 - x0) * PX, (y1 - y0) * PX, (z1 - z0) * PX)
    bpy.ops.object.transform_apply(scale=True)
    o.data.materials.append(mats[box["mat"]])
    return o


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    mats = {n: make_material(n) for n in COLORS}
    boxes = build_boxes()

    objs = [add_box_object(b, mats) for b in boxes]
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    scythe = bpy.context.object
    scythe.name = "reaper_scythe"
    # pivot = the grip point = world origin
    bpy.context.scene.cursor.location = (0, 0, 0)
    bpy.ops.object.origin_set(type="ORIGIN_CURSOR")
    bpy.ops.object.shade_flat()

    # ---- twirl animation: one full turn around Y (perpendicular to the blade)
    scene.render.fps = 20
    scene.frame_start, scene.frame_end = 1, 20
    scythe.rotation_mode = "XYZ"
    scythe.rotation_euler = (0, 0, 0)
    scythe.keyframe_insert("rotation_euler", frame=1)
    scythe.rotation_euler = (0, math.radians(-360), 0)
    scythe.keyframe_insert("rotation_euler", frame=21)
    action = scythe.animation_data.action
    fcurves = []
    if hasattr(action, "layers"):               # Blender 4.4+ layered actions
        for layer in action.layers:
            for strip in layer.strips:
                for bag in strip.channelbags:
                    fcurves += list(bag.fcurves)
    else:
        fcurves = list(action.fcurves)
    for fc in fcurves:
        for kp in fc.keyframe_points:
            kp.interpolation = "LINEAR"

    # ---- preview scene
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = 3.5
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    cam.location = (0, -6, 0)
    cam.rotation_euler = (math.radians(90), 0, 0)
    scene.camera = cam
    for loc, energy in (((3, -4, 5), 4.0), ((-4, -3, -2), 1.5)):
        ld = bpy.data.lights.new("sun", "SUN")
        ld.energy = energy
        lo = bpy.data.objects.new("sun", ld)
        scene.collection.objects.link(lo)
        lo.location = loc
        lo.rotation_euler = (math.radians(55), 0, math.radians(30 if energy > 2 else -140))
    world = bpy.data.worlds.new("w")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.05, 0.05, 0.09, 1)
    scene.world = world
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = scene.render.resolution_y = 256
    scene.render.image_settings.file_format = "PNG"

    # ---- render an 8-frame contact sheet of the twirl
    tmp = []
    for i in range(8):
        scene.frame_set(1 + round(i * 20 / 8))
        p = os.path.join(HERE, f"_f{i}.png")
        scene.render.filepath = p
        bpy.ops.render.render(write_still=True)
        tmp.append(p)
    size = 256
    sheet = np.zeros((size * 2, size * 4, 4), dtype=np.float32)
    for i, p in enumerate(tmp):
        img = bpy.data.images.load(p)
        arr = np.empty(size * size * 4, dtype=np.float32)
        img.pixels.foreach_get(arr)
        arr = arr.reshape(size, size, 4)
        r, c = divmod(i, 4)
        sheet[(1 - r) * size:(2 - r) * size, c * size:(c + 1) * size] = arr
        bpy.data.images.remove(img)
        os.remove(p)
    out = bpy.data.images.new("sheet", size * 4, size * 2)
    out.pixels.foreach_set(sheet.ravel())
    out.filepath_raw = os.path.join(HERE, "preview_sheet.png")
    out.file_format = "PNG"
    out.save()

    # ---- outputs for the mod
    with open(os.path.join(HERE, "scythe_boxes.json"), "w") as f:
        json.dump({"unit": "minecraft_px", "axes": "blender (x right, y depth, z up)",
                   "colors": COLORS, "boxes": boxes}, f, indent=1)
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(HERE, "scythe.blend"))
    bpy.ops.export_scene.gltf(filepath=os.path.join(HERE, "scythe.glb"), export_animations=True)
    print("DONE boxes:", len(boxes))


main()
