#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd -P)
JAR_PATH="${1:-$REPO_ROOT/releases/intellij-augment-0.482.3-beta.jar}"
UPSTREAM_ZIP="${UPSTREAM_ZIP:-$REPO_ROOT/intellij-augment-0.482.3-stable.zip}"
BRIDGE_SOURCE="$REPO_ROOT/re/patches/intellij-augment-0.482.3/AugmentWorkspaceBridge.java"
EXPECTED_UPSTREAM_SHA="303969f7df18b354768b9d17fd72982808f9f11e883e33d9c1f4f37b3bc4a5c2"
FIXED_DATE="2026-08-10T00:00:00Z"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$JAR_PATH" ]] || fail "patched JAR not found: $JAR_PATH"
[[ -f "$UPSTREAM_ZIP" ]] || fail "upstream plugin ZIP not found: $UPSTREAM_ZIP"
[[ -f "$BRIDGE_SOURCE" ]] || fail "bridge source not found: $BRIDGE_SOURCE"

ACTUAL_UPSTREAM_SHA=$(shasum -a 256 "$UPSTREAM_ZIP" | awk '{print $1}')
[[ "$ACTUAL_UPSTREAM_SHA" == "$EXPECTED_UPSTREAM_SHA" ]] \
  || fail "unexpected upstream ZIP sha256: $ACTUAL_UPSTREAM_SHA"

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
mkdir -p "$TEMP_DIR/classes" "$TEMP_DIR/sidecar"

unzip -p "$UPSTREAM_ZIP" \
  intellij-augment/lib/settings_webview_communication_java_binary_deploy.jar \
  >"$TEMP_DIR/settings-webview.jar"

javac --release 21 \
  -cp "$JAR_PATH:$TEMP_DIR/settings-webview.jar" \
  -d "$TEMP_DIR/classes" \
  "$BRIDGE_SOURCE"

unzip -p "$JAR_PATH" sidecar/index.cjs >"$TEMP_DIR/sidecar/index.cjs"
node "$SCRIPT_DIR/patch-sidecar.mjs" "$TEMP_DIR/sidecar/index.cjs"
node --check "$TEMP_DIR/sidecar/index.cjs"

cp "$JAR_PATH" "$TEMP_DIR/patched.jar"
jar --update --date="$FIXED_DATE" --file "$TEMP_DIR/patched.jar" \
  -C "$TEMP_DIR/classes" com/augmentcode/intellij/settings/AugmentWorkspaceBridge.class \
  -C "$TEMP_DIR" sidecar/index.cjs
cp "$TEMP_DIR/patched.jar" "$JAR_PATH"

"$SCRIPT_DIR/verify-patched-jar.sh" "$JAR_PATH"
shasum -a 256 "$JAR_PATH"
