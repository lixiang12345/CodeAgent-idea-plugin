#!/usr/bin/env bash
set -u
OIDC="http://127.0.0.1:8445"
TENANT="http://127.0.0.1:8787"

echo "=== 1. OIDC Discovery ==="
curl -sf "$OIDC/.well-known/openid-configuration" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'  token_endpoint: {d[\"token_endpoint\"]}')
print(f'  issuer: {d[\"issuer\"]}')
"

echo ""
echo "=== 2. Authorize ==="
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' "$OIDC/authorize?response_type=code&code_challenge=test123&code_challenge_method=S256&client_id=augment-intellij-plugin&redirect_uri=http://127.0.0.1:63342/api/augment/auth/result&state=test&scope=email&prompt=login")
CODE=${LOC#*code=}; CODE=${CODE%%&*}
echo "  code=$CODE"

echo ""
echo "=== 3. Token Exchange (tenant surface /token) ==="
TOK=$(curl -sf -X POST "$TENANT/token" -H "Content-Type: application/json" \
  -d '{"grant_type":"authorization_code","code":"'"$CODE"'","redirect_uri":"http://127.0.0.1:63342/api/augment/auth/result","code_verifier":"test","client_id":"augment-intellij-plugin"}')
AT=$(echo "$TOK" | python3 -c "
import sys,json
print(json.load(sys.stdin).get('access_token','')[:50])
")
echo "  access_token: ${AT}..."
echo "$TOK" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'  tenantUrl: {d.get(\"tenantUrl\",\"MISSING\")}')
print(f'  tenantId: {d.get(\"tenantId\",\"MISSING\")}')
"

echo ""
echo "=== 4. GetModels with access_token ==="
curl -sf -X POST "$TENANT/api-client/get-models" \
  -H "Authorization: Bearer $AT" \
  -H "Content-Type: application/json" \
  -d '{}' | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'  default_model: {d.get(\"default_model\",\"N/A\")}')
print(f'  model_registry keys: {list(d.get(\"model_registry\",{}).keys()) if isinstance(d.get(\"model_registry\"), dict) else \"N/A\"}')
"

echo ""
echo "=== 5. Chat with real model ==="
RESULT=$(curl -sf -N -X POST "$TENANT/api-client/chat-stream" \
  -H "Authorization: Bearer $AT" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"Say hello in one word.","conversation_id":"e2e-test","model":"gpt-5.6-sol"}')
echo "$RESULT" | python3 -c "
import sys
lines = sys.stdin.read().strip().split('\n')
texts = []
for line in lines:
    if line.startswith('data: '):
        data = line[6:]
        try:
            import json
            obj = json.loads(data)
            if 'text' in obj:
                texts.append(obj['text'])
        except:
            pass
print(f'  chat response: {\"\".join(texts)[:100]}')
"

echo ""
echo "ALL GREEN"
