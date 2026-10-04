#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
java com.sun.tools.javac.Main -d "$classes" \
  src/main/java/de/everforge/mod/server/audit/CommandAuditStore.java \
  scripts/tests/CommandAuditStoreTest.java
java -cp "$classes" de.everforge.mod.server.audit.CommandAuditStoreTest
