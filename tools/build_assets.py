"""One command for everything generated:   python tools/build_assets.py

  1. tools/autofix.py   keeps the scythe out of his body and the ground  ->  tools/animations_fixed.py
  2. tools/gen_assets.py  textures + model + animation code for the game
"""
import os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
root = os.environ.get("ENTITY303_ROOT", os.path.dirname(here))
env = dict(os.environ, ENTITY303_ROOT=root)
for script, args in (("autofix.py", ["--write"]), ("gen_assets.py", [])):
    print("==", script)
    subprocess.run([sys.executable, os.path.join(root, "tools", script)] + args, check=True, env=env, cwd=root)
