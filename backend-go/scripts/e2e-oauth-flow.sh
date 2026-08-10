#!/usr/bin/env bash
set -euo pipefail

OIDC="${OIDC_URL:-http://127.0.0.1:8445}"
TENANT="${TENANT_URL:-http://127.0.0.1:8787}"
REDIRECT_URI="http://127.0.0.1:63342/api/augment/auth/result"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

read -r VERIFIER CHALLENGE < <(python3 - <<'PY'
import base64
import hashlib
import secrets

verifier = secrets.token_urlsafe(48)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
print(verifier, challenge)
PY
)

echo "=== 1. OIDC Discovery ==="
DISCOVERY=$(curl --fail-with-body -sS "$OIDC/.well-known/openid-configuration")
printf '%s' "$DISCOVERY" | python3 -c '
import json, sys
d = json.load(sys.stdin)
assert d["token_endpoint"].endswith("/oauth/token"), d
assert d["code_challenge_methods_supported"] == ["S256"], d
assert d["grant_types_supported"] == ["authorization_code"], d
print("  token_endpoint: {}".format(d["token_endpoint"]))
print("  issuer: {}".format(d["issuer"]))
'

echo ""
echo "=== 2. Authorize with S256 PKCE ==="
LOC=$(curl --fail-with-body -sS -o /dev/null -w '%{redirect_url}' --get "$OIDC/authorize" \
  --data-urlencode 'response_type=code' \
  --data-urlencode "code_challenge=$CHALLENGE" \
  --data-urlencode 'code_challenge_method=S256' \
  --data-urlencode 'client_id=augment-intellij-plugin' \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode 'state=e2e-oauth-state' \
  --data-urlencode 'scope=openid profile email')
[[ -n "$LOC" ]] || fail "authorize did not return a redirect"
CODE=$(python3 -c '
import sys
from urllib.parse import parse_qs, urlparse
q = parse_qs(urlparse(sys.stdin.read().strip()).query)
assert q.get("state") == ["e2e-oauth-state"], q
print(q.get("code", [""])[0])
' <<<"$LOC")
[[ -n "$CODE" ]] || fail "authorize redirect did not contain a code"
echo "  authorization code received"

token_exchange() {
  curl --fail-with-body -sS -X POST "$TENANT/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=authorization_code' \
    --data-urlencode "code=$CODE" \
    --data-urlencode "redirect_uri=$REDIRECT_URI" \
    --data-urlencode "code_verifier=$VERIFIER" \
    --data-urlencode 'client_id=augment-intellij-plugin'
}

echo ""
echo "=== 3. Token Exchange (tenant surface /token) ==="
TOK=$(token_exchange)
AT=$(printf '%s' "$TOK" | python3 -c '
import json, sys
d = json.load(sys.stdin)
token = d.get("access_token", "")
assert token.count(".") == 2, "missing compact JWT access_token"
assert d.get("id_token", "").count(".") == 2, "missing compact JWT id_token"
assert d.get("tenantUrl") == sys.argv[1], (d.get("tenantUrl"), sys.argv[1])
assert d.get("tenantId") == "local-tenant", d.get("tenantId")
print(token)
' "$TENANT")
[[ -n "$AT" ]] || fail "token response did not contain an access token"
echo "  access_token: ${AT:0:24}..."

echo ""
echo "=== 4. Authorization Code Replay Rejected ==="
REPLAY_FILE=$(mktemp)
trap 'rm -f "$REPLAY_FILE"' EXIT
REPLAY_STATUS=$(curl -sS -o "$REPLAY_FILE" -w '%{http_code}' -X POST "$TENANT/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=authorization_code' \
  --data-urlencode "code=$CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "code_verifier=$VERIFIER")
[[ "$REPLAY_STATUS" == "400" ]] || fail "authorization code replay returned HTTP $REPLAY_STATUS"
python3 -c '
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    d = json.load(f)
assert d.get("error") == "invalid_grant", d
' "$REPLAY_FILE"
echo "  replay rejected with invalid_grant"

echo ""
echo "=== 5. GetModels with access_token ==="
MODELS=$(curl --fail-with-body -sS -X POST "$TENANT/api-client/get-models" \
  -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' \
  -d '{}')
printf '%s' "$MODELS" | python3 -c '
import json, sys
d = json.load(sys.stdin)
default_model = d.get("default_model") or d.get("defaultModel")
models = d.get("models") or []
assert default_model, d
assert models, d
print(f"  default_model: {default_model}")
print(f"  models: {len(models)}")
'

echo ""
echo "=== 6. Chat with real model ==="
RESULT=$(curl --fail-with-body -sS -N -X POST "$TENANT/api-client/chat-stream" \
  -H "Authorization: Bearer $AT" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"Say hello in one word.","conversation_id":"e2e-oauth-flow","model":"gpt-5.6-sol"}')
printf '%s' "$RESULT" | python3 -c '
import json, sys

texts = []
stop_reasons = []
for line in sys.stdin:
    if not line.startswith("data: "):
        continue
    event = json.loads(line[6:])
    if event.get("text"):
        texts.append(event["text"])
    if event.get("stop_reason"):
        stop_reasons.append(event["stop_reason"])

text = "".join(texts).strip()
assert text, "chat stream returned no text"
assert "END_TURN" in stop_reasons, stop_reasons
assert "STOP_REASON_ERROR" not in stop_reasons, stop_reasons
print(f"  chat response: {text[:100]}")
'

echo ""
echo "ALL GREEN"
