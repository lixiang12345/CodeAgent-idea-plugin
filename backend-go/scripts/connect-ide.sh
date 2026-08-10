#!/usr/bin/env bash
# connect-ide.sh — point the installed JetBrains IDE(s) at the local Augment IdP.
#
# For every JetBrains product config dir under ~/Library/Application Support/
# JetBrains/, ensures the product .vmoptions file contains
#   -Daugmentcode.oauth.url=http://127.0.0.1:8445
# so the Augment plugin's OAuth flow hits our local IdP instead of the real one.
# No hosts edits, no CA installation.
#
# Usage:  ./scripts/connect-ide.sh          (add/refresh the override)
#         ./scripts/connect-ide.sh --off    (remove the override)

set -euo pipefail

OIDC_URL="${OIDC_URL:-http://127.0.0.1:8445}"
OVERRIDE="-Daugmentcode.oauth.url=${OIDC_URL}"
JB_ROOT="$HOME/Library/Application Support/JetBrains"
MODE="${1:-on}"
MODE="${MODE#--}"   # accept both `off` and `--off`

# JetBrains macOS layout: the "Edit Custom VM Options" file is
# <product>.vmoptions in the product config dir, e.g. idea.vmoptions.
vmoptions_name() {
  local product="$1"
  case "$product" in
    IntelliJIdea*) echo "idea.vmoptions" ;;
    PyCharm*)      echo "pycharm.vmoptions" ;;
    GoLand*)       echo "goland.vmoptions" ;;
    WebStorm*)     echo "webstorm.vmoptions" ;;
    PhpStorm*)     echo "phpstorm.vmoptions" ;;
    RubyMine*)     echo "rubymine.vmoptions" ;;
    CLion*)        echo "clion.vmoptions" ;;
    DataGrip*)     echo "datagrip.vmoptions" ;;
    Rider*)        echo "rider64.vmoptions" ;;
    AndroidStudio*) echo "studio64.vmoptions" ;;
    *)             echo "" ;;
  esac
}

if [[ ! -d "$JB_ROOT" ]]; then
  echo "JetBrains config dir not found: $JB_ROOT" >&2
  exit 1
fi

modified=0
any_file=0
for cfg in "$JB_ROOT"/*/; do
  product=$(basename "$cfg")
  standard=$(vmoptions_name "$product")

  # De-duplicated list of existing custom vmoptions files in this product dir.
  shopt -s nullglob
  existing=()
  for f in "$cfg"*.vmoptions; do
    [[ -e "$f" ]] || continue
    seen=0
    for ((k = 0; k < ${#existing[@]}; k++)); do
      [[ "${existing[$k]}" == "$f" ]] && seen=1
    done
    (( seen == 0 )) && existing+=("$f")
  done

  # On: if the product has a standard file name and none exist yet, create it.
  # Off: only touch files that already exist. Products with no standard name
  # (e.g. CodeAgent-plugin-backups) are skipped entirely.
  if (( ${#existing[@]} == 0 )); then
    if [[ "$MODE" == "on" && -n "$standard" ]]; then
      printf '%s\n' "$OVERRIDE" > "$cfg$standard"
      echo "created $product/$standard with $OVERRIDE"
      modified=1
      any_file=1
    fi
    continue
  fi
  any_file=1

  for vmpath in "${existing[@]}"; do
    name=$(basename "$vmpath")
    if [[ "$MODE" == "off" ]]; then
      if grep -qF -- "$OVERRIDE" "$vmpath"; then
        # Remove the override line, then drop the file entirely if nothing
        # meaningful remains. Avoid pipelines under pipefail (grep exits 1 on
        # no match, sed may SIGPIPE) — write the filtered body first.
        grep -vF -- "$OVERRIDE" "$vmpath" > "$vmpath.tmp" || true
        if [[ ! -s "$vmpath.tmp" ]]; then
          rm -f "$vmpath" "$vmpath.tmp"
          echo "removed $product/$name (now empty)"
        else
          mv "$vmpath.tmp" "$vmpath"
          echo "removed $OVERRIDE from $product/$name"
        fi
        modified=1
      fi
      continue
    fi
    if grep -qF -- "$OVERRIDE" "$vmpath"; then
      echo "already set: $product/$name"
    else
      printf '\n%s\n' "$OVERRIDE" >> "$vmpath"
      echo "set $OVERRIDE in $product/$name"
      modified=1
    fi
  done
done

if [[ "$MODE" == "off" ]]; then
  echo "done. Restart the IDE to re-enable the official login flow."
  exit 0
fi

if (( modified )); then
  cat <<EOF

done. Restart the IDE (Help > Restart IDE) and sign in to Augment.
Expected flow:
  1. Browser opens http://127.0.0.1:8445/authorize (local login page).
  2. Any email/password works; you are redirected back with a code.
  3. The plugin receives access_token + tenantUrl=http://127.0.0.1:8787 and
     routes ALL cloud calls to your Docker backend.

Verify the backend sees the flow:
  docker compose logs -f augment-local
EOF
elif (( ! any_file )); then
  echo "No vmoptions files found under $JB_ROOT — set the override manually:"
  echo "  Help > Edit Custom VM Options  ->  add:  $OVERRIDE"
fi
