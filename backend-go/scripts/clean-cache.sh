#!/usr/bin/env bash
# 清空可重建缓存（IDEA / 插件文件缓存 / ContextEngine 索引）。
# 建议先关闭 IDEA 再执行（IDEA Caches 在运行时会被重建，且写入中删除可能损坏 mtime-cache）。
#
# 用法:
#   ./clean-cache.sh                 # 只清缓存，保留会话和插件状态
#   ./clean-cache.sh --reset-state   # 备份后额外重置持久状态
#   ./clean-cache.sh --force         # 跳过 IDEA 运行提示

set -uo pipefail

FORCE=false
RESET_STATE=false
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=true ;;
    --reset-state) RESET_STATE=true ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数: $arg" >&2
      exit 2
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$BACKEND_DIR/.." && pwd)"
IDEO="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1/options"
IDEC="$HOME/Library/Caches/JetBrains/IntelliJIdea2026.1"

# ── 0. 检查 IDEA ──────────────────────────────────────────────────────────
if pgrep -f "IntelliJ IDEA.app/Contents/MacOS/idea" >/dev/null 2>&1 && [[ "$FORCE" != "true" ]]; then
  echo "⚠️  IDEA 正在运行。清 IDEA Caches 会被运行时重建；且写入中删除可能损坏缓存文件。"
  echo "   建议先完整退出 IDEA 再执行本脚本。"
  read -r -p "  继续？[y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]] || { echo "已取消。"; exit 1; }
fi

BACKUP_DIR=""
if [[ "$RESET_STATE" == "true" ]]; then
  BACKUP_STAMP=$(date +%Y%m%d-%H%M%S)
  BACKUP_DIR="$HOME/.augmentcode/cache-reset-backups/$BACKUP_STAMP"
  mkdir -p "$BACKUP_DIR"
  echo "持久状态备份目录: $BACKUP_DIR"
fi

echo "== 1/8 后端 state =="
if [[ "$RESET_STATE" == "true" ]]; then
  docker cp augment-local:/app/state/augment-local.json "$BACKUP_DIR/augment-local.json" 2>/dev/null || true
  docker exec augment-local rm -f /app/state/augment-local.json 2>/dev/null || true
else
  echo "  已保留（使用 --reset-state 才会重置）"
fi

echo "== 2/8 webview 状态 (.idea) =="
if [[ "$RESET_STATE" == "true" ]]; then
  cp -p "$REPO_ROOT/.idea/AugmentWebviewStateStore.xml" "$BACKUP_DIR/" 2>/dev/null || true
  cp -p "$REPO_ROOT/.idea/AugmentWebviewStateStore.xml.bak" "$BACKUP_DIR/" 2>/dev/null || true
  rm -f "$REPO_ROOT/.idea/AugmentWebviewStateStore.xml" "$REPO_ROOT/.idea/AugmentWebviewStateStore.xml.bak" 2>/dev/null || true
else
  echo "  已保留"
fi

echo "== 3/8 项目缓存 (mtime/kv/plugin-file-store) =="
for p in "$HOME"/.augmentcode/intellij/projects/*/; do
	rm -rf "$p/mtime-cache" "$p/plugin-file-store"
	if [[ "$RESET_STATE" == "true" ]]; then
	  project_key=$(basename "${p%/}")
	  cp -R "$p/node-process/augment-kv-store" "$BACKUP_DIR/$project_key-augment-kv-store" 2>/dev/null || true
	  rm -rf "$p/node-process/augment-kv-store"
	fi
done

echo "== 4/8 ContextEngine workspaces（已建立索引）=="
KEY="$(grep '^CONTEXTENGINE_HTTP_API_KEY=' "$BACKEND_DIR/.env" 2>/dev/null | cut -d= -f2)"
if [ -n "$KEY" ]; then
  curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8790/v1/workspaces 2>/dev/null \
    | python3 -c 'import sys,json; [print(w["id"]) for w in json.load(sys.stdin)["workspaces"]]' 2>/dev/null \
    | while read -r id; do curl -s -X DELETE -H "Authorization: Bearer $KEY" "http://127.0.0.1:8790/v1/workspaces/$id" -o /dev/null; done
fi

echo "== 5/8 插件端状态 (options) =="
if [[ "$RESET_STATE" == "true" ]]; then
  cp -p "$IDEO/GlobalPluginStateForSidecar.xml" "$BACKUP_DIR/" 2>/dev/null || true
  cp -p "$IDEO/GlobalPluginStateForSidecar.xml.bak" "$BACKUP_DIR/" 2>/dev/null || true
  cp -p "$IDEO/AugmentSettings.xml" "$BACKUP_DIR/" 2>/dev/null || true
  cp -p "$IDEO/other.xml" "$BACKUP_DIR/other.xml" 2>/dev/null || true
  rm -f "$IDEO/GlobalPluginStateForSidecar.xml" "$IDEO/GlobalPluginStateForSidecar.xml.bak" "$IDEO/AugmentSettings.xml" 2>/dev/null || true
  python3 - "$IDEO/other.xml" <<'PY'
import re, sys
p = sys.argv[1]
try:
    data = open(p, encoding="utf-8").read()
except FileNotFoundError:
    sys.exit(0)
data = re.sub(r'"augment\.[^"]*":\s*"[^"]*",?\s*', "", data)
open(p, "w", encoding="utf-8").write(data)
PY
else
  echo "  已保留"
fi

echo "== 6/8 IDEA Caches (augmentworkspaceindex / plugin zip) =="
rm -rf "$IDEC/index/augmentworkspaceindex" "$IDEC/index/sih.augmentworkspaceindex" 2>/dev/null
rm -rf "$IDEC/index/shared_indexes/sih.AugmentWorkspaceIndex" 2>/dev/null
rm -rf "$IDEC/index/.persistent/augmentworkspaceindex" "$IDEC/index/.persistent/sih.augmentworkspaceindex" 2>/dev/null
rm -f "$IDEC/plugins/intellij-augment.zip" 2>/dev/null

echo "== 7/8 重启 augment-local =="
docker compose -f "$BACKEND_DIR/compose.yaml" restart augment-local 2>/dev/null || true

echo "== 8/8 确认 =="
STATE="$(docker exec augment-local sh -c 'ls /app/state/ 2>/dev/null | wc -l | tr -d " "' 2>/dev/null)"
echo "  后端 state 文件数: ${STATE:-0}（默认保留）"
if [ -n "$KEY" ]; then
  WS="$(curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8790/v1/workspaces 2>/dev/null | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["workspaces"]))' 2>/dev/null)"
  echo "  ContextEngine workspaces: ${WS:-0}"
fi

if [[ "$RESET_STATE" == "true" ]]; then
  echo "持久状态已重置，备份位于: $BACKUP_DIR"
fi
echo "✅ 可重建缓存已清空。重新打开 IDEA → 选项目 → 应触发全新索引。"
