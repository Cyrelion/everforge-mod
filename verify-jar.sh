#!/usr/bin/env bash
set -Eeuo pipefail

JAR="${1:-build/libs/everforge-mod-0.3.4.jar}"
[[ -f "$JAR" ]] || { echo "Missing JAR: $JAR" >&2; exit 1; }

required=(
  "META-INF/neoforge.mods.toml"
  "everforge_mod.mixins.json"
  "de/everforge/mod/EverforgeMod.class"
  "de/everforge/mod/client/TitleScreenRenderer.class"
  "de/everforge/mod/client/mixin/PanoramaRendererMixin.class"
  "de/everforge/mod/client/mixin/LogoRendererMixin.class"
  "de/everforge/mod/client/mixin/TitleScreenMixin.class
  "de/everforge/mod/server/worldedit/WorldEditOpenPacIntegration.class"
  "de/everforge/mod/server/worldedit/OpenPacClaimExtent.class""
  "assets/everforge_mod/textures/gui/title/background.png"
  "assets/everforge_mod/textures/gui/title/logo.png"
)

for e in "${required[@]}"; do
  jar tf "$JAR" | grep -Fxq "$e" || { echo "MISS $e"; exit 1; }
  echo "OK   $e"
done

for forbidden in \
  'META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider' \
  'de/everforge/mod/earlyloading/' \
  'de/everforge/bootstrap/'
do
  if jar tf "$JAR" | grep -Fq "$forbidden"; then
    echo "FAIL Early-loader/bootstrap residue found: $forbidden"
    exit 1
  fi
done

echo "OK   no integrated early-loader/bootstrap residue"
echo "PASS"
