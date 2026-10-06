#!/bin/bash
# javap helper: inspect Minecraft 1.21.11 (Mojang-mapped) classes without decompiling.  usage: tools/jp.sh <class> [grep]
CP="C:\Users\adamk\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-clientonly\1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2\minecraft-clientonly-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar;C:\Users\adamk\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common\1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2\minecraft-common-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar;$(find ~/.gradle/caches -name 'fabric-rendering-v1*.jar' -not -name '*sources*' | head -1 | xargs -r cygpath -w)"
javap -cp "$CP" -p "$1" | { if [ -n "$2" ]; then grep -E "$2"; else cat; fi; }
