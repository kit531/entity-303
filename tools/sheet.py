"""Contact sheet of the preview frames:  python tools/sheet.py <frames dir> <out.png> [columns]"""
import glob, os, sys
from PIL import Image, ImageDraw

src, out = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 4
files = sorted(glob.glob(os.path.join(src, "*.png")))
order = ["idle", "sweep", "slash", "step", "summon", "drain", "whirl", "slam", "roar"]
files.sort(key=lambda f: (order.index(os.path.basename(f).split("_")[0]) if os.path.basename(f).split("_")[0] in order else 99, f))
imgs = [Image.open(f).convert("RGB") for f in files]
w, h = imgs[0].size
rows = (len(imgs) + cols - 1) // cols
sheet = Image.new("RGB", (cols * w, rows * h), (10, 10, 20))
d = ImageDraw.Draw(sheet)
for i, (f, im) in enumerate(zip(files, imgs)):
    x, y = (i % cols) * w, (i // cols) * h
    sheet.paste(im, (x, y))
    name = os.path.splitext(os.path.basename(f))[0].replace("_", " t=")
    d.rectangle((x + 6, y + 6, x + 150, y + 26), fill=(0, 0, 0))
    d.text((x + 10, y + 10), name, fill=(255, 255, 255))
sheet.save(out)
print(out, sheet.size)
