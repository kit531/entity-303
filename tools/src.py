"""Print decompiled Minecraft / Fabric API sources (Mojang names, 1.21.11) from the Loom cache.

  python tools/src.py net.minecraft.world.entity.Mob            print a class
  python tools/src.py -f BossEvent                              find classes whose name contains BossEvent
  python tools/src.py -g "hurtServer" net.minecraft.world.entity.LivingEntity   grep inside a class
"""
import glob, os, re, sys, zipfile

ROOT = os.environ.get("ENTITY303_ROOT", "C:/rb")
JARS = sorted(glob.glob(ROOT + "/.gradle/loom-cache/**/*-sources.jar", recursive=True))


def find(sub):
    sub = sub.lower()
    for j in JARS:
        with zipfile.ZipFile(j) as z:
            for n in z.namelist():
                if n.endswith(".java") and sub in n.lower().rsplit("/", 1)[-1]:
                    print(n[:-5].replace("/", "."))


def read(cls):
    path = cls.replace(".", "/") + ".java"
    for j in JARS:
        with zipfile.ZipFile(j) as z:
            if path in z.namelist():
                return z.read(path).decode("utf-8", "replace")
    sys.exit(f"{cls}: not found in {len(JARS)} source jars (client sources may still be generating)")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "-f":
        find(a[1])
    elif a and a[0] == "-g":
        text = read(a[2])
        for i, line in enumerate(text.splitlines(), 1):
            if re.search(a[1], line):
                print(f"{i}: {line}")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(read(a[0]))
