"""UI art of the mod, drawn with Pillow:  python tools/gen_ui.py

  * the boss bar (textures/gui/boss_bar_*.png): an ornate frame (scythe blades at both ends, a skull on top, red
    gems), three fills (one per phase) and a gloss/notch overlay.  Drawn by BossBarRenderer on the client.
  * the red tooltip of the Reaper's Scythe (textures/gui/sprites/tooltip/reaper_background|frame.png + .mcmeta),
    selected by the item's minecraft:tooltip_style = entity303:reaper.

Run it again after changing a palette; preview/ui_preview.png shows the result on a dark background.
"""
import json, os
from PIL import Image, ImageDraw

ROOT = os.environ.get("ENTITY303_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GUI = os.path.join(ROOT, "src/main/resources/assets/entity303/textures/gui")
SPRITES = os.path.join(GUI, "sprites/tooltip")

# geometry shared with BossBarRenderer.java
FW, FH = 252, 46          # frame canvas
SLOT_X, SLOT_Y = 35, 19   # where the 182 x 7 bar sits inside the frame
SLOT_W, SLOT_H = 182, 7


def c(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


OUT = c("#0b0507")
IRON_TOP, IRON_BOT = c("#432832"), c("#1b0c11")
TRIM, TRIM_HI, TRIM_LO = c("#8c1a20"), c("#d8343a"), c("#4a0b10")
BONE, BONE_SH, BONE_HI = c("#d9cdb4"), c("#a99c82"), c("#f4ecd8")
STEEL, STEEL_DK, STEEL_HI = c("#4d4f5c"), c("#2a2b33"), c("#dfe1e8")
GEM, GEM_DK, GEM_HI = c("#c4262e"), c("#5c0f14"), c("#ff9a9a")
EYE = c("#ff2a2a")


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(4))


def gradient_rect(img, x0, y0, x1, y1, top, bottom):
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, (y1 - y0))
        for x in range(x0, x1 + 1):
            img.putpixel((x, y), lerp(top, bottom, t))


SKULL = [
    "...ooooooo...",
    "..obbbbbbbo..",
    ".obbhbbbbbbo.",
    ".obbbbbbbbso.",
    "obeeebbbeeebo",
    "obereebbereeo",
    ".obbbbnbbbbo.",
    ".obbbbbbbbbo.",
    "..obsbobsbo..",
    "...ooooooo...",
]
SKULL_COL = {"o": OUT, "b": BONE, "s": BONE_SH, "h": BONE_HI, "e": c("#16070b"), "r": EYE, "n": c("#6b5f4a")}


def draw_skull(img, cx, top):
    # the eye rows use a fixed pattern; make the glow symmetrical
    rows = [r for r in SKULL]
    rows[5] = "obereebbbereo"[:13] if len("obereebbbereo") == 13 else rows[5]
    for dy, row in enumerate(rows):
        assert len(row) == 13, (dy, row)
        for dx, ch in enumerate(row):
            if ch != ".":
                img.putpixel((cx - 6 + dx, top + dy), SKULL_COL[ch])


def draw_gem(img, cx, cy):
    shape = [(0, -3), (-1, -2), (0, -2), (1, -2), (-2, -1), (-1, -1), (0, -1), (1, -1), (2, -1),
             (-3, 0), (-2, 0), (-1, 0), (0, 0), (1, 0), (2, 0), (3, 0),
             (-2, 1), (-1, 1), (0, 1), (1, 1), (2, 1), (-1, 2), (0, 2), (1, 2), (0, 3)]
    for dx, dy in shape:
        col = GEM
        if dx + dy < -1:
            col = GEM_HI
        elif dx + dy > 2:
            col = GEM_DK
        img.putpixel((cx + dx, cy + dy), col)
    # outline
    pts = {(cx + dx, cy + dy) for dx, dy in shape}
    for x, y in list(pts):
        for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            p = (x + ox, y + oy)
            if p not in pts and 0 <= p[0] < FW and 0 <= p[1] < FH and img.getpixel(p)[3] == 0:
                img.putpixel(p, OUT)


def draw_blade(img, mirror=False):
    """A crescent scythe blade sweeping out and up from the left end of the plate (mirrored for the right one)."""
    def mx(x):
        return FW - 1 - x if mirror else x
    outer = [(31, 16), (26, 12), (20, 8), (13, 5), (7, 3), (2, 2), (0, 3)]
    inner = [(3, 6), (8, 9), (14, 13), (20, 18), (26, 22), (31, 24)]
    poly = [(mx(x), y) for x, y in outer + inner]
    d = ImageDraw.Draw(img)
    d.polygon(poly, fill=STEEL)
    d.line([(mx(x), y) for x, y in outer], fill=STEEL_DK, width=1)
    d.line([(mx(x), y) for x, y in inner], fill=STEEL_HI, width=1)            # the cutting edge
    d.line([(mx(x), y) for x, y in outer[:5]], fill=OUT, width=1)
    # thin outline of the tip
    d.point([(mx(0), 3), (mx(1), 2), (mx(2), 2)], fill=OUT)
    # a short dark spike under it
    spike = [(31, 27), (26, 31), (21, 35), (17, 40), (15, 44), (19, 41), (24, 37), (29, 33), (31, 31)]
    d.polygon([(mx(x), y) for x, y in spike], fill=STEEL_DK, outline=OUT)


def build_frame():
    img = Image.new("RGBA", (FW, FH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_blade(img, False)
    draw_blade(img, True)
    # plate
    px0, px1, py0, py1 = 29, FW - 30, 15, 29
    gradient_rect(img, px0, py0, px1, py1, IRON_TOP, IRON_BOT)
    d.rectangle([px0 - 1, py0 - 1, px1 + 1, py1 + 1], outline=OUT)
    d.line([(px0, py0), (px1, py0)], fill=c("#6a3f4a"))                       # top bevel
    d.line([(px0 + 2, py0 + 1), (px1 - 2, py0 + 1)], fill=TRIM)               # red inlay
    d.line([(px0 + 2, py1 - 1), (px1 - 2, py1 - 1)], fill=TRIM_LO)
    # slot (the bar sits here)
    d.rectangle([SLOT_X - 1, SLOT_Y - 1, SLOT_X + SLOT_W, SLOT_Y + SLOT_H], outline=OUT)
    gradient_rect(img, SLOT_X, SLOT_Y, SLOT_X + SLOT_W - 1, SLOT_Y + SLOT_H - 1, c("#1a080c"), c("#07030a"))
    # rivets
    for x in list(range(48, 120, 18)) + list(range(FW - 49, FW - 121, -18)):
        img.putpixel((x, py0 + 3), BONE_SH)
        img.putpixel((x, py1 - 3), BONE_SH)
    # spikes flanking the skull
    for sx in (-1, 1):
        base = FW // 2 + sx * 11
        for i in range(6):
            for w in range(max(0, 3 - i // 2)):
                img.putpixel((base + sx * w, 14 - i), TRIM_HI if i < 4 else TRIM)
    draw_skull(img, FW // 2, 4)
    # gems at both ends and a pendant
    draw_gem(img, 31, 22)
    draw_gem(img, FW - 32, 22)
    for i in range(3):
        img.putpixel((FW // 2, 31 + i), BONE_SH)
    draw_gem(img, FW // 2, 37)
    return img


def build_fill(top, mid, bot, shimmer):
    img = Image.new("RGBA", (SLOT_W, SLOT_H), (0, 0, 0, 0))
    for y in range(SLOT_H):
        t = y / (SLOT_H - 1)
        col = lerp(top, mid, t * 2) if t < 0.5 else lerp(mid, bot, (t - 0.5) * 2)
        for x in range(SLOT_W):
            k = 0.0
            if (x + y * 3) % 23 < 3:           # diagonal shimmer
                k = shimmer
            img.putpixel((x, y), lerp(col, c("#ffffff"), k))
    return img


def build_gloss():
    img = Image.new("RGBA", (SLOT_W, SLOT_H), (0, 0, 0, 0))
    for x in range(SLOT_W):
        img.putpixel((x, 0), c("#ffffff", 70))
        img.putpixel((x, SLOT_H - 1), c("#000000", 110))
    for i in range(1, 20):                      # 19 dividers = 20 segments (the boss bar has 20 notches)
        x = round(SLOT_W * i / 20)
        for y in range(SLOT_H):
            img.putpixel((min(SLOT_W - 1, x), y), c("#000000", 150))
    return img


def build_tooltip():
    bg = Image.new("RGBA", (100, 100), c("#12040a", 244))
    d = ImageDraw.Draw(bg)
    d.rectangle([2, 2, 97, 97], outline=c("#2a0a12", 255))
    d.rectangle([4, 4, 95, 95], outline=c("#1c070d", 255))
    fr = Image.new("RGBA", (100, 100), (0, 0, 0, 0))
    d = ImageDraw.Draw(fr)
    for y in range(100):                         # the border colour fades from bright red at the top to dark at the bottom
        t = y / 99
        col = lerp(c("#ff4a4a"), c("#7c1218"), t)
        d.point([(2, y), (3, y), (96, y), (97, y)], fill=col)
    for x in range(100):
        t = x / 99
        top = lerp(c("#ff4a4a"), c("#ff7a5a"), 0.5 - abs(t - 0.5))
        d.point([(x, 2), (x, 3)], fill=top)
        d.point([(x, 96), (x, 97)], fill=c("#7c1218"))
    d.rectangle([0, 0, 99, 99], outline=c("#0b0507"))                                    # outer black line
    d.rectangle([1, 1, 98, 98], outline=c("#3a0a10"))
    d.rectangle([4, 4, 95, 95], outline=c("#3a0a10"))                                    # inner shadow line
    # corner jewels: a bone spike with a red gem
    for cx, cy in ((5, 5), (94, 5), (5, 94), (94, 94)):
        for dx, dy in [(0, 0), (-1, 0), (1, 0), (0, -1), (0, 1), (-2, 0), (2, 0), (0, -2), (0, 2)]:
            col = GEM_HI if (dx, dy) == (-1, 0) else (GEM if abs(dx) + abs(dy) < 2 else GEM_DK)
            fr.putpixel((cx + dx, cy + dy), col)
        for ox, oy in ((-3, 0), (3, 0), (0, -3), (0, 3)):
            fr.putpixel((cx + ox, cy + oy), BONE)
    return bg, fr


def main():
    os.makedirs(GUI, exist_ok=True)
    os.makedirs(SPRITES, exist_ok=True)
    frame = build_frame()
    fills = [
        build_fill(c("#ff6a62"), c("#d4202a"), c("#7a0c14"), 0.10),     # phase 1: crimson
        build_fill(c("#e0508e"), c("#a81a58"), c("#4c0a2c"), 0.14),     # phase 2: enraged, towards magenta
        build_fill(c("#ffe0b0"), c("#ff3a1c"), c("#a00a0a"), 0.22),     # phase 3: final form, white-hot
    ]
    gloss = build_gloss()
    frame.save(os.path.join(GUI, "boss_bar_frame.png"))
    for i, f in enumerate(fills, 1):
        f.save(os.path.join(GUI, f"boss_bar_fill_{i}.png"))
    gloss.save(os.path.join(GUI, "boss_bar_gloss.png"))
    bg, fr = build_tooltip()
    bg.save(os.path.join(SPRITES, "reaper_background.png"))
    fr.save(os.path.join(SPRITES, "reaper_frame.png"))
    json.dump({"gui": {"scaling": {"type": "nine_slice", "width": 100, "height": 100, "border": 9}}},
              open(os.path.join(SPRITES, "reaper_background.png.mcmeta"), "w"), indent=4)
    json.dump({"gui": {"scaling": {"type": "nine_slice", "width": 100, "height": 100, "border": 10, "stretch_inner": True}}},
              open(os.path.join(SPRITES, "reaper_frame.png.mcmeta"), "w"), indent=4)

    # preview: the bar at 60 / 100 / 25 % in the three phases, and the tooltip frame
    S = 3
    sheet = Image.new("RGBA", (FW * S + 20, (FH * 3 + 40) * S // 1 + 0), c("#2a2d3a"))
    y = 6
    for i, (fill, frac) in enumerate(zip(fills, (1.0, 0.6, 0.25))):
        tile = frame.copy()
        w = round(SLOT_W * frac)
        tile.alpha_composite(fill.crop((0, 0, w, SLOT_H)), (SLOT_X, SLOT_Y))
        tile.alpha_composite(gloss, (SLOT_X, SLOT_Y))
        sheet.alpha_composite(tile.resize((FW * S, FH * S), Image.NEAREST), (10, y))
        y += FH * S + 6
    os.makedirs(os.path.join(ROOT, "preview"), exist_ok=True)
    sheet.crop((0, 0, sheet.width, y)).save(os.path.join(ROOT, "preview/ui_preview.png"))
    tip = Image.new("RGBA", (220, 120), c("#2a2d3a"))
    tip.alpha_composite(bg.resize((200, 100), Image.NEAREST), (10, 10))
    tip.alpha_composite(fr.resize((200, 100), Image.NEAREST), (10, 10))
    tip.resize((660, 360), Image.NEAREST).save(os.path.join(ROOT, "preview/tooltip_preview.png"))
    print("ui art written")


if __name__ == "__main__":
    main()
