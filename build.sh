#!/usr/bin/env bash
set -Eeuo pipefail

./gradlew clean build

JAR="build/libs/everforge-mod-0.2.0.jar"
[[ -f "$JAR" ]] || { echo "Missing $JAR" >&2; exit 1; }

./verify-jar.sh "$JAR"

echo
echo "Built: $JAR"
echo
echo "NEXT:"
echo "  Replace the Everforge Mod JAR in Everforge-Test with this file."
echo "  Keep SimpleCustomEarlyLoading as the separate boot-screen dependency."
echo "  Start the client and verify the Everforge title/login screen."
