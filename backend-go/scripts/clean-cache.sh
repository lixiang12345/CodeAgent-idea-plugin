#!/usr/bin/env bash
# 清空所有缓存（后端 / IDEA / 插件本地 / ContextEngine 索引）。
# 建议先关闭 IDEA 再执行（IDEA Caches 在运行时会被重建，且写入中删除可能损坏 mtime-cache）。
#
# 用法:
#   ./clean-cache.sh            # 全部清理
#   ./clean-cache.sh --force    # 跳过 IDEA 运行提示

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$BACKEND_DIR/.." && pwd)"
IDEO="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1/options"
IDEC="$HOME/Library/Caches/JetBrains/IntelliJIdea2026.1"

# ── 0. 检查 IDEA ──────────────────────────────────────────────────────────
if pgrep -f "IntelliJ IDEA.app/Contents/MacOS/idea" >/dev/null 2>&1 && [[ "${1:-}" != "--force" ]]; then
  echo "⚠️  IDEA 正在运行。清 IDEA Caches 会被运行时重建；且写入中删除可能损坏缓存文件。"
  echo "   建议先完整退出 IDEA 再执行本脚本。"
  read -r -p "  继续？[y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]] || { echo "已取消。"; exit 1; }
fi

echo "== 1/8 后端 state =="
docker exec augment-local rm -f /app/state/augment-local.json 2>/dev/null || true

echo "== 2/8 webview 状态 (.idea) =="
rm -f "$REPO_ROOT/.idea/AugmentWebviewStateStore.xml" "$REPO_ROOT/.idea/AugmentWebviewStateStore.xml.bak" 2>/dev/null || true

echo "== 3/8 项目缓存 (mtime/kv/plugin-file-store) =="
for p in "$HOME"/.augmentcode/intellij/projects/*/; do
  rm -rf "$p/mtime-cache" "$p/node-process/augment-kv-store" "$p/plugin-file-store"
done

echo "== 4/8 ContextEngine workspaces（已建立索引）=="
KEY="$(grep '^CONTEXTENGINE_HTTP_API_KEY=' "$BACKEND_DIR/.env" 2>/dev/null | cut -d= -f2)"
if [ -n "$KEY" ]; then
  curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8790/v1/workspaces 2>/dev/null \
    | python3 -c 'import sys,json; [print(w["id"]) for w in json.load(sys.stdin)["workspaces"]]' 2>/dev/null \
    | while read -r id; do curl -s -X DELETE -H "Authorization: Bearer $KEY" "http://127.0.0.1:8790/v1/workspaces/$id" -o /dev/null; done
fi

echo "== 5/8 插件端状态 (options) =="
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

echo "== 6/8 IDEA Caches (augmentworkspaceindex / plugin zip) =="
rm -rf "$IDEC/index/augmentworkspaceindex" "$IDEC/index/sih.augmentworkspaceindex" 2>/dev/null
rm -rf "$IDEC/index/shared_indexes/sih.AugmentWorkspaceIndex" 2>/dev/null
rm -rf "$IDEC/index/.persistent/augmentworkspaceindex" "$IDEC/index/.persistent/sih.augmentworkspaceindex" 2>/dev/null
rm -f "$IDEC/plugins/intellij-augment.zip" 2>/dev/null

echo "== 7/8 重启 augment-local =="
docker compose -f "$BACKEND_DIR/compose.yaml" restart augment-local 2>/dev/null || true

echo "== 8/8 确认 =="
STATE="$(docker exec augment-local sh -c 'ls /app/state/ 2>/dev/null | wc -l | tr -d " "' 2>/dev/null)"
echo "  后端 state 文件数: ${STATE:-0}"
if [ -n "$KEY" ]; then
  WS="$(curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8790/v1/workspaces 2>/dev/null | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["workspaces"]))' 2>/dev/null)"
  echo "  ContextEngine workspaces: ${WS:-0}"
fi

echo "✅ 缓存已清空。重新打开 IDEA → 选项目 → 应触发全新索引。"
