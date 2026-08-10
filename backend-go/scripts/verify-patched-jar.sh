#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd -P)
JAR_PATH="${1:-$REPO_ROOT/releases/intellij-augment-0.482.3-beta.jar}"
BRIDGE_CLASS="com.augmentcode.intellij.settings.AugmentWorkspaceBridge"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$JAR_PATH" ]] || fail "JAR not found: $JAR_PATH"
unzip -tq "$JAR_PATH" >/dev/null || fail "invalid JAR archive"

TEMP_DIR=$(mktemp -d)
SIDECAR_FILE="$TEMP_DIR/index.cjs"
trap 'rm -rf "$TEMP_DIR"' EXIT
unzip -p "$JAR_PATH" sidecar/index.cjs >"$SIDECAR_FILE" || fail "sidecar/index.cjs missing"
node --check "$SIDECAR_FILE" || fail "sidecar syntax check failed"

MAJOR=$(javap -verbose -classpath "$JAR_PATH" "$BRIDGE_CLASS" 2>/dev/null | awk '/major version:/ {print $3; exit}')
[[ "$MAJOR" =~ ^[0-9]+$ ]] || fail "cannot read bridge class version"
(( MAJOR <= 65 )) || fail "bridge class version $MAJOR requires newer than Java 21"

if grep -Fq 'Initializing Language Server: ${JSON.stringify(e)}' "$SIDECAR_FILE"; then
  fail "sidecar logs the complete initialization payload"
fi
if grep -Fq 'MCP servers from IntelliJ: ${JSON.stringify(t,null,2)}' "$SIDECAR_FILE"; then
  fail "sidecar logs persisted MCP configuration values"
fi
if grep -Fq 'e.payload===void 0?"":` ${JSON.stringify(e.payload)}`' "$SIDECAR_FILE"; then
  fail "sidecar logs complete Redux action payloads"
fi
if grep -Fq '[WEBVIEW] ${g2e(e.message)}' "$SIDECAR_FILE"; then
  fail "sidecar logs complete webview messages"
fi
if grep -Fq 'return void setTimeout(p,3000)' "$SIDECAR_FILE" && grep -Fq 'finally{setTimeout(p,3000)}' "$SIDECAR_FILE"; then
  fail "ContextEngine poller can schedule duplicate timers"
fi

echo "JAR verification passed: class_major=$MAJOR"
