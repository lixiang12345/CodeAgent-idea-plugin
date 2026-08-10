#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd -P)
JAR_PATH="${1:-$REPO_ROOT/releases/intellij-augment-0.482.3-beta.jar}"
UPSTREAM_ZIP="${UPSTREAM_ZIP:-$REPO_ROOT/intellij-augment-0.482.3-stable.zip}"
BRIDGE_SOURCE="$REPO_ROOT/re/patches/intellij-augment-0.482.3/AugmentWorkspaceBridge.java"
EXPECTED_UPSTREAM_SHA="303969f7df18b354768b9d17fd72982808f9f11e883e33d9c1f4f37b3bc4a5c2"
UPSTREAM_MAIN_JAR="intellij-augment/lib/intellij-augment-0.482.3-beta.jar"
MAIN_PANEL_ENTRY="webviews/assets/MainPanel-SbgXAty1.js"
STORE_ENTRY="webviews/assets/Store-h-yCE5ok.js"
LOCAL_PLUGIN_VERSION="0.482.3.999-local"
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
mkdir -p "$TEMP_DIR/classes" "$TEMP_DIR/sidecar" "$TEMP_DIR/META-INF" "$TEMP_DIR/webviews/assets"

unzip -p "$UPSTREAM_ZIP" \
  intellij-augment/lib/settings_webview_communication_java_binary_deploy.jar \
  >"$TEMP_DIR/settings-webview.jar"
unzip -p "$UPSTREAM_ZIP" "$UPSTREAM_MAIN_JAR" >"$TEMP_DIR/upstream-main.jar"

javac --release 21 \
  -cp "$JAR_PATH:$TEMP_DIR/settings-webview.jar" \
  -d "$TEMP_DIR/classes" \
  "$BRIDGE_SOURCE"

unzip -p "$JAR_PATH" sidecar/index.cjs >"$TEMP_DIR/sidecar/index.cjs"
node "$SCRIPT_DIR/patch-sidecar.mjs" "$TEMP_DIR/sidecar/index.cjs"
node --check "$TEMP_DIR/sidecar/index.cjs"

unzip -p "$JAR_PATH" "$MAIN_PANEL_ENTRY" >"$TEMP_DIR/$MAIN_PANEL_ENTRY"
unzip -p "$JAR_PATH" "$STORE_ENTRY" >"$TEMP_DIR/$STORE_ENTRY"
node "$SCRIPT_DIR/patch-webviews.mjs" \
  "$TEMP_DIR/$MAIN_PANEL_ENTRY" "$TEMP_DIR/$STORE_ENTRY"
node --check "$TEMP_DIR/$MAIN_PANEL_ENTRY"
node --check "$TEMP_DIR/$STORE_ENTRY"

unzip -p "$JAR_PATH" META-INF/plugin.xml >"$TEMP_DIR/META-INF/plugin.xml"
unzip -p "$TEMP_DIR/upstream-main.jar" META-INF/MANIFEST.MF >"$TEMP_DIR/META-INF/MANIFEST.MF"
node "$SCRIPT_DIR/patch-plugin-metadata.mjs" \
  "$TEMP_DIR/META-INF/plugin.xml" "$LOCAL_PLUGIN_VERSION"
node "$SCRIPT_DIR/patch-plugin-metadata.mjs" \
  "$TEMP_DIR/META-INF/MANIFEST.MF" "$LOCAL_PLUGIN_VERSION"

cp "$JAR_PATH" "$TEMP_DIR/patched.jar"
jar --update --date="$FIXED_DATE" --file "$TEMP_DIR/patched.jar" \
  -C "$TEMP_DIR/classes" com/augmentcode/intellij/settings/AugmentWorkspaceBridge.class \
  -C "$TEMP_DIR" sidecar/index.cjs \
  -C "$TEMP_DIR" "$MAIN_PANEL_ENTRY" \
  -C "$TEMP_DIR" "$STORE_ENTRY" \
  -C "$TEMP_DIR" META-INF/plugin.xml

# The JDK jar tool treats META-INF/MANIFEST.MF specially and may drop it when
# supplied as a regular update entry. Replace that one entry with zip instead.
touch -t 202608100000.00 "$TEMP_DIR/META-INF/MANIFEST.MF"
if unzip -Z1 "$TEMP_DIR/patched.jar" | grep -Fxq META-INF/MANIFEST.MF; then
  zip -q -d "$TEMP_DIR/patched.jar" META-INF/MANIFEST.MF
fi
(
  cd "$TEMP_DIR"
  zip -q -X "$TEMP_DIR/patched.jar" META-INF/MANIFEST.MF
)

# Validate the temporary artifact before touching the tracked release.
"$SCRIPT_DIR/verify-patched-jar.sh" "$TEMP_DIR/patched.jar"

TARGET_TMP=$(mktemp "$(dirname "$JAR_PATH")/.patched-jar.XXXXXX")
cp "$TEMP_DIR/patched.jar" "$TARGET_TMP"
mv "$TARGET_TMP" "$JAR_PATH"

"$SCRIPT_DIR/verify-patched-jar.sh" "$JAR_PATH"
shasum -a 256 "$JAR_PATH"
