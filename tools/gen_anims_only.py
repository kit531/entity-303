"""Regenerates ONLY src/main/java/com/entity303/anim/Entity303Animations.java (no textures, no model).

    python tools/gen_anims_only.py

tools/gen_assets.py needs Pillow even though the animation code does not; use this when Pillow is not installed
(textures and the model only change when the skin / the scythe / the model spec change).
Run tools/autofix.py --write first when tools/animations.py changed (python tools/build_assets.py does both with Pillow).
"""
import os, sys, types

# gen_assets.py imports Pillow at the top; the animation writer never touches it
for name in ("PIL", "PIL.Image"):
    sys.modules.setdefault(name, types.ModuleType(name))
sys.modules["PIL"].Image = sys.modules["PIL.Image"]

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_assets  # noqa: E402

gen_assets.write_animations_java()
print("wrote", gen_assets.ANIM_OUT)
