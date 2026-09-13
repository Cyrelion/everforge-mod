#!/usr/bin/env bash
set -euo pipefail

NEW_VERSION="${1:-}"

if [[ -z "$NEW_VERSION" ]]; then
    echo "Usage: $0 <new-version>"
    echo "Example: $0 0.3.5"
    exit 1
fi

if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid version: $NEW_VERSION"
    exit 1
fi

OLD_VERSION="$(
    sed -n "s/^version = '\([^']*\)'/\1/p" build.gradle
)"

if [[ -z "$OLD_VERSION" ]]; then
    echo "Could not determine current version from build.gradle"
    exit 1
fi

echo "Bumping Everforge Mod:"
echo "  $OLD_VERSION -> $NEW_VERSION"
echo

replace_version() {
    local file="$1"

    if [[ ! -f "$file" ]]; then
        echo "Missing: $file"
        exit 1
    fi

    if ! grep -qF "$OLD_VERSION" "$file"; then
        echo "Old version $OLD_VERSION not found in $file"
        exit 1
    fi

    sed -i "s/${OLD_VERSION//./\\.}/${NEW_VERSION}/g" "$file"
    echo "Updated: $file"
}

replace_version "build.gradle"
replace_version "src/main/resources/META-INF/neoforge.mods.toml"
replace_version "verify-jar.sh"

echo
echo "Remaining references to $OLD_VERSION:"
grep -RInF \
    --exclude-dir=.git \
    --exclude-dir=build \
    --exclude='*.jar' \
    "$OLD_VERSION" . || true

echo
echo "Git diff:"
git diff -- \
    build.gradle \
    src/main/resources/META-INF/neoforge.mods.toml \
    verify-jar.sh
    