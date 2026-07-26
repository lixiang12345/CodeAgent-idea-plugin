# CodeAgent backend contract for OMP

Contract version: `1`

The authoritative machine-readable schema is `openapi.json`, also served publicly as `GET /openapi.json`. Interactive documentation is served as `GET /docs`. This document records the streaming behavior that OpenAPI cannot express precisely.

## Connection

- Local backend default: `http://127.0.0.1:8787`
- Current local OMP integration instance: `http://127.0.0.1:8788`
- Authentication mode is discovered from public `GET /v1/auth/config`. Hosted deployments use backend-mediated OIDC Authorization Code + PKCE; local development may use local or shared-token mode.
- OIDC access and rotating refresh tokens are returned by `POST /v1/auth/token`. Every protected `/v1/*` request sends `Authorization: Bearer <access_token>`; `POST /v1/auth/logout` revokes the backing session.
- Browser development origins must be listed in `CORS_ALLOWED_ORIGINS` as a comma-separated exact-origin allowlist. Model and integration credentials never cross this API boundary.

Start or update the local deployment from the repository root:

```bash
docker compose -f backend/compose.yaml up -d --build
```

## Authentication flow

1. Read `GET /v1/auth/config`.
2. For `mode=oidc`, create a loopback callback, state, PKCE verifier, and S256 challenge; open `authorizationEndpoint` in the system browser.
3. Receive the one-time authorization code at the loopback callback and exchange it at `tokenEndpoint`. Store access and refresh tokens only in JetBrains Password Safe.
4. Refresh before expiry through the same token endpoint. Refresh tokens rotate and cannot be replayed.
5. Read the current user, usage, and session from `GET /v1/me`; revoke the session with `POST /v1/auth/logout`.

## Required flow

1. Call `GET /health` and reject any `protocolVersion` other than `1`.
2. Call `GET /v1/models`, then use only IDs returned in `data`.
3. Call `GET /v1/tools`; advertise only entries with `available=true`.
4. Start a run with `POST /v1/runs` and read its `text/event-stream` response incrementally.
5. Record `runId` from `run.started` or the `X-CodeAgent-Run-Id` response header.
6. On `tool.request`, execute local tools in IDEA or proxy discovered backend tools through `POST /v1/tools/{toolName}`, then post the result to `POST /v1/runs/{runId}/tool-results`.
7. Stop on `run.completed` or `run.error`. Cancel with `DELETE /v1/runs/{runId}`.

Use streaming `fetch`; browser `EventSource` cannot issue the authenticated JSON `POST` required by `/v1/runs`.

## Durable jobs

Durable analysis uses `POST /v1/jobs` with `type=subagent` or `type=history-summary`. Jobs are persisted before execution, queued again after a backend restart, listed with `GET /v1/jobs`, inspected with `GET /v1/jobs/{id}`, and cancelled with `DELETE /v1/jobs/{id}`. Subagent inputs may select a bounded specialist role, optional context and output contract, model, and a 1,024-16,000 output-token limit.

The IDEA client polls only while its Durable Jobs page contains queued or running work. Completed output can be returned to the conversation composer; retry creates a new persisted job from the prior normalized input, preserving the original record as audit history.

## Deployment-configured cloud surfaces

These routes are always mounted; what they report depends on deployment configuration documented in `.env.example`. An unconfigured deployment answers with an explicit unavailable state, never with a fabricated one. `openapi.json` does not describe them yet, so this section is their contract until it does.

- `GET /v1/conversations/{id}/share`, `POST /v1/conversations/{id}/share`, and `DELETE /v1/conversations/{id}/share` read, create or rotate, and revoke a shareable link for one account-owned conversation. `POST` accepts `{"ttlSeconds": 60..31536000}` and returns `url` plus `rotated`; that response is the only place the plaintext token exists, because the backend stores only its SHA-256 hash. Without `SHARE_BASE_URL` all three return `503 {"error":"Shareable links are not configured on this deployment"}`; an unshared conversation returns `404`.
- `GET /v1/share/{token}` is public, sends `cache-control: no-store`, and returns a read-only conversation projection with no account identity, revert affordances, or run correlation IDs. Every failure — malformed, unknown, revoked, expired, or deleted conversation — returns the same `404 {"error":"Shared session not found"}` so tokens cannot be enumerated.
- `GET /v1/notifications` returns the operator catalog already filtered for this principal; dismissed, expired, and out-of-audience entries are absent. An unconfigured deployment returns `{"data":[]}`, which is a healthy empty response. `POST /v1/notifications/{id}/dismiss` accepts an optional `actionItemTitle` and returns `204`; an id outside the catalog returns `404`.
- `GET /v1/me` carries a `subscription` object with `state` `ok`, `approaching`, `exhausted`, or `unknown`, per-quota counters computed from usage the backend counted, and one `warning` or `null`. `unknown` always carries a `reason` and must be surfaced as unavailable rather than as a healthy plan.

Clients must present each unavailable state with the backend's reason string; a `503` is never a silent no-op and never a success.

## SSE events

Each event is an SSE block with an `event` name and one JSON `data` line. Lines beginning with `:` are heartbeats and must be ignored.

| Event | Required payload | Meaning |
| --- | --- | --- |
| `run.started` | `runId`, `protocolVersion`, `provider`, `model`, Agent profile and token budgets | Run accepted and stream established |
| `turn.started` | `turnIndex` | A provider model turn started |
| `context.updated` | `turnIndex`, estimated and target input tokens, compaction counters, `overBudget` | Context budgeting and compaction state for the model turn |
| `model.retrying` | `turnIndex`, `attempt`, `maxAttempts`, `message` | A transient provider failure occurred before the first output token and the turn is being retried |
| `tool.catalog.updated` | `turnIndex`, active and catalog tool counts, active names, newly activated names | Lazy tool discovery state for the model turn |
| `verification.updated` | `turnIndex`, `status`, `message`, optional `toolName` | Post-mutation verification gate state |
| `message.delta` | `delta`, `turnIndex` | Append text to the current assistant message |
| `assistant.completed` | `content`, `turnIndex` | Canonical assistant content for that turn; `content` may be null for a tool-only turn |
| `tool.batch.started` | `turnIndex`, `total`, `names`, `execution` | The model requested a tool batch; current execution is sequential and each request is emitted only when it starts |
| `tool.request` | `call.id`, `call.name`, `call.arguments`, `turnIndex` | Execute one local tool; `arguments` is a JSON string |
| `tool.completed` | `toolCallId`, `status`, optional `summary` | Backend accepted the local result and resumed orchestration |
| `run.completed` | `turnCount` | Terminal success event |
| `run.error` | `message` | Terminal run failure event |

The backend can emit multiple tool requests in one model turn. It currently executes them sequentially, so the model receives the complete batch of tool results before its next turn. Submit exactly one result for every `call.id`; duplicate or unknown IDs are rejected.

## Error handling

- HTTP errors use `{ "error": "message" }`; request validation fails before SSE headers are sent.
- Backend tool execution returns `503` when the tool exists but required environment configuration is missing.
- Once an SSE response has started, run failures arrive as `run.error`, not as a new HTTP status.
- Transient provider disconnects before the first output token are retried twice. Disconnects after visible output are not retried because replaying the turn could duplicate content or tool calls.
- A tool result returns `202 { "accepted": true }`.
- Cancellation returns `202 { "cancelled": true }` and closes the stream.
- Clients should treat unknown future SSE event names as ignorable for forward compatibility.
