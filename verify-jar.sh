#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_VERSION="$(sed -n 's/^mod_version=//p' "$SCRIPT_DIR/gradle.properties")"
[[ -n "$MOD_VERSION" ]] || { echo "Missing mod_version in gradle.properties" >&2; exit 1; }
JAR="${1:-$SCRIPT_DIR/build/libs/everforge-mod-${MOD_VERSION}.jar}"
[[ -f "$JAR" ]] || { echo "Missing JAR: $JAR" >&2; exit 1; }

required=(
  "META-INF/neoforge.mods.toml"
  "everforge_mod.mixins.json"
  "de/everforge/mod/EverforgeMod.class"
  "de/everforge/mod/client/TitleScreenRenderer.class"
  "de/everforge/mod/client/mixin/PanoramaRendererMixin.class"
  "de/everforge/mod/client/mixin/LogoRendererMixin.class"
  "de/everforge/mod/client/mixin/TitleScreenMixin.class"
  "de/everforge/mod/server/worldedit/WorldEditOpenPacIntegration.class"
  "de/everforge/mod/server/worldedit/OpenPacClaimExtent.class"
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
