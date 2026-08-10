#!/usr/bin/env bash
# e2e-probe.sh — drive the full takeover surface and assert every layer works.
#
# Usage:  ./scripts/e2e-probe.sh [OIDC_URL] [TENANT_URL]
# Defaults: http://127.0.0.1:8445  http://127.0.0.1:8787
# Exit code 0 = all green; 1 = a probe failed.

set -u
OIDC="${1:-http://127.0.0.1:8445}"
TENANT="${2:-http://127.0.0.1:8787}"
REQUIRE_MODEL_SUCCESS="${REQUIRE_MODEL_SUCCESS:-0}"
CONVERSATION_ID="e2e-$(date +%s)-$$"
PASS=0; FAIL=0

read -r PKCE_VERIFIER PKCE_CHALLENGE < <(python3 - <<'PY'
import base64
import hashlib
import secrets

verifier = secrets.token_urlsafe(48)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
print(verifier, challenge)
PY
)

ok()   { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ $1"; }

jqget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

echo "== OIDC IdP ($OIDC) =="
doc=$(curl -sf "$OIDC/.well-known/openid-configuration") && ok "discovery document" || bad "discovery document"
[[ -n "$doc" ]] && echo "$doc" | jqget "['authorization_endpoint']" | grep -q "$OIDC/authorize" && ok "authorize endpoint" || bad "authorize endpoint"

REDIRECT_URI="http://127.0.0.1:59999/augment"
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' --get "$OIDC/authorize" \
  --data-urlencode 'response_type=code' \
  --data-urlencode 'client_id=augment-intellij-plugin' \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode 'state=s' \
  --data-urlencode "code_challenge=$PKCE_CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256')
CODE=$(python3 -c 'import sys; from urllib.parse import parse_qs,urlparse; print(parse_qs(urlparse(sys.stdin.read()).query).get("code", [""])[0])' <<<"$LOC")
[[ -n "$CODE" ]] && ok "authorize -> code ($CODE)" || bad "authorize -> code"

TOK=$(curl -sf -X POST "$OIDC/oauth/token" \
  --data-urlencode 'grant_type=authorization_code' \
  --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "code_verifier=$PKCE_VERIFIER")
AT=$(echo "$TOK" | jqget "['access_token']")
TU=$(echo "$TOK" | jqget "['tenantUrl']")
[[ -n "$AT" && "$TU" == "$TENANT" ]] && ok "token with tenantUrl=$TU" || bad "token/tenantUrl"
echo "$TOK" | jqget "['id_token']" | grep -q '\.' && ok "id_token present" || bad "id_token"
JWT_CLAIMS=$(echo "$AT" | cut -d. -f2 | python3 -c "import base64,sys;p=sys.stdin.read().strip();p+='='*(-len(p)%4);import json;d=json.loads(base64.urlsafe_b64decode(p));print(d.get('tenantUrl',''),d.get('iss',''))")
echo "$JWT_CLAIMS" | grep -q "^$TENANT" && ok "JWT tenantUrl claim" || bad "JWT tenantUrl claim"

echo "== Tenant surface ($TENANT) =="
curl -sf "$TENANT/healthz" >/dev/null && ok "healthz" || bad "healthz"

GETMODELS=$(curl -sf -X POST "$TENANT/api-client/get-models" -d '{}')
echo "$GETMODELS" | jqget "['defaultModel']" | grep -q . && ok "GetModels (defaultModel=$(echo "$GETMODELS" | jqget "['defaultModel']"))" || bad "GetModels"

REMOTE_TOOLS=$(curl -sf -X POST "$TENANT/api-client/agents/list-remote-tools" -d '{}')
REMOTE_COUNT=$(echo "$REMOTE_TOOLS" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('tools', [])))")
[[ "$REMOTE_COUNT" == "0" ]] && ok "remote tool catalog is protocol-safe (IDE tools stay in sidecar)" || bad "remote tool catalog count=$REMOTE_COUNT"

CHAT_COMPLETION=$(curl -sf -X POST "$TENANT/api-client/chat-input-completion" -H 'Content-Type: application/json' -d '{"prompt":""}')
echo "$CHAT_COMPLETION" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d.get("completion_items"), list); assert isinstance(d.get("unknown_memory_names"), list)' \
  && ok "chat input completion contract" || bad "chat input completion contract"
CODE_COMPLETION=$(curl -sf -X POST "$TENANT/api-client/completion" -H 'Content-Type: application/json' -d '{"prompt":""}')
echo "$CODE_COMPLETION" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d.get("completion_items"), list); assert isinstance(d.get("unknown_memory_names"), list)' \
  && ok "code completion contract" || bad "code completion contract"

DISC=$(curl -sf -X POST "$TENANT/api-client/client-discovery" -d '{}')
N=$(echo "$DISC" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['transports'][0]['supported_services']))")
[[ "$N" == "22" ]] && ok "client-discovery 22 services" || bad "client-discovery ($N)"

CODE501=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$TENANT/api-client/checkpoint-blobs" -d '{}')
[[ "$CODE501" == "501" ]] && ok "unimplemented -> 501" || bad "unimplemented status=$CODE501"

echo "== chat-stream (SSE simulator) =="
CHAT=$(curl -sf -N -X POST "$TENANT/api-client/chat-stream" \
  -H 'Content-Type: application/json' \
  -d '{"message":"Reply with the single word ready.","conversation_id":"'"$CONVERSATION_ID"'"}')
CHAT_CHECK=$(echo "$CHAT" | python3 -c '
import json,sys
events=[json.loads(line) for line in sys.stdin if line.strip()]
types=[node.get("type") for event in events for node in event.get("nodes", [])]
text="".join(str(event.get("text", "")) for event in events)
stops=[event.get("stop_reason") for event in events if event.get("stop_reason")]
success=("THINKING" in types and "MAIN_TEXT_FINISHED" in types and stops == ["END_TURN"] and bool(text.strip()))
explicit_error=("MAIN_TEXT_FINISHED" in types and stops == ["STOP_REASON_ERROR"] and "模型调用失败" in text)
print("success" if success else "error" if explicit_error else "invalid")
')
if [[ "$CHAT_CHECK" == "success" ]]; then
  ok "chat-stream success terminal sequence"
elif [[ "$CHAT_CHECK" == "error" && "$REQUIRE_MODEL_SUCCESS" != "1" ]]; then
  ok "chat-stream explicit upstream error sequence"
else
  bad "chat-stream terminal sequence=$CHAT_CHECK"
fi
HIST=$(curl -sf -X POST "$TENANT/api-client/chat/exchanges/list" -d '{"conversation_id":"'"$CONVERSATION_ID"'"}' | python3 -c "import sys,json;print(len(json.load(sys.stdin)['chat_history']))")
[[ "$HIST" -ge 1 ]] && ok "exchange recorded for unique conversation (history=$HIST)" || bad "history recorded"

echo "== connect+json protocol =="
CC=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$TENANT/augment.public_api.Augment/GetCreditInfo" -H 'Content-Type: application/connect+json' -d '{}')
[[ "$CC" == "200" ]] && ok "connect+json GetCreditInfo" || bad "connect+json status=$CC"
CU=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$TENANT/augment.public_api.Augment/Edit" -H 'Content-Type: application/connect+json' -d '{}')
[[ "$CU" == "501" ]] && ok "connect+json unimplemented 501" || bad "connect+json unimplemented=$CU"

echo "== gRPC (h2c) =="
if command -v grpcurl >/dev/null 2>&1; then
  PROBE_DIR=$(mktemp -d)
  cat > "$PROBE_DIR/grpc.proto" <<'EOF'
syntax = "proto3";
package grpc.health.v1;
message HealthCheckRequest { string service = 1; }
message HealthCheckResponse { enum ServingStatus { UNKNOWN=0; SERVING=1; NOT_SERVING=2; SERVICE_UNKNOWN=3; } ServingStatus status = 1; }
service Health { rpc Check(HealthCheckRequest) returns (HealthCheckResponse); }
EOF
  HOSTPORT=$(echo "$TENANT" | sed 's|http://||')
  H=$(grpcurl -plaintext -import-path "$PROBE_DIR" -proto grpc.proto "$HOSTPORT" grpc.health.v1.Health/Check 2>/dev/null)
  echo "$H" | grep -q SERVING && ok "grpc Health SERVING" || bad "grpc Health"
  # public_api.Augment/Chat via minimal wire-shaped proto (varint stop_reason).
  cat > "$PROBE_DIR/chat.proto" <<'EOF'
syntax = "proto3";
package public_api;
message ChatRequest { string message = 6; string conversation_id = 32; }
message ChatResultNode { string content = 3; }
message ChatResponse { string text = 1; repeated ChatResultNode nodes = 6; int32 stop_reason = 7; }
service Augment { rpc Chat(ChatRequest) returns (ChatResponse); }
EOF
  # grpcurl marshals proto JSON to lowerCamelCase, so assert on "stopReason".
  C=$(grpcurl -plaintext -d '{"message":"容器探针","conversation_id":"grpc-e2e"}' -import-path "$PROBE_DIR" -proto chat.proto -max-msg-sz 1048576 "$HOSTPORT" public_api.Augment/Chat 2>/dev/null)
  echo "$C" | grep -q "stopReason" && ok "grpc Chat echo" || bad "grpc Chat"
  rm -rf "$PROBE_DIR"
else
  bad "grpcurl not installed"
fi

echo
echo "PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" -eq 0 ]] && echo "ALL GREEN" || echo "FAILURES PRESENT"
exit "$([[ $FAIL -eq 0 ]] && echo 0 || echo 1)"
