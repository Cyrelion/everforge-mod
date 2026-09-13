#!/usr/bin/env bash
set -Eeuo pipefail

./gradlew clean build

./verify-jar.sh

echo
echo "NEXT:"
echo "  Replace the Everforge Mod JAR in Everforge-Test with this file."
echo "  Keep SimpleCustomEarlyLoading as the separate boot-screen dependency."
echo "  Start the client and verify the Everforge title/login screen."
