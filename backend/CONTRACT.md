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

## SSE events

Each event is an SSE block with an `event` name and one JSON `data` line. Lines beginning with `:` are heartbeats and must be ignored.

| Event | Required payload | Meaning |
| --- | --- | --- |
| `run.started` | `runId`, `protocolVersion`, `provider`, `model`, Agent profile and token budgets | Run accepted and stream established |
| `turn.started` | `turnIndex` | A provider model turn started |
| `context.updated` | `turnIndex`, estimated and target input tokens, compaction counters, `overBudget` | Context budgeting and compaction state for the model turn |
| `message.delta` | `delta`, `turnIndex` | Append text to the current assistant message |
| `assistant.completed` | `content`, `turnIndex` | Canonical assistant content for that turn; `content` may be null for a tool-only turn |
| `tool.request` | `call.id`, `call.name`, `call.arguments`, `turnIndex` | Execute one local tool; `arguments` is a JSON string |
| `tool.completed` | `toolCallId`, `status`, optional `summary` | Backend accepted the local result and resumed orchestration |
| `run.completed` | `turnCount` | Terminal success event |
| `run.error` | `message` | Terminal run failure event |

The backend can emit multiple tool requests in one model turn. Submit exactly one result for every `call.id`; duplicate or unknown IDs are rejected.

## Error handling

- HTTP errors use `{ "error": "message" }`; request validation fails before SSE headers are sent.
- Backend tool execution returns `503` when the tool exists but required environment configuration is missing.
- Once an SSE response has started, run failures arrive as `run.error`, not as a new HTTP status.
- A tool result returns `202 { "accepted": true }`.
- Cancellation returns `202 { "cancelled": true }` and closes the stream.
- Clients should treat unknown future SSE event names as ignorable for forward compatibility.
