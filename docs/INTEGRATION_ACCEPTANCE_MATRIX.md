# External integration least-privilege acceptance matrix

This document is the canonical preflight and live-evidence contract for
CodeAgent integrations that do not yet have provider-specific acceptance
evidence. It complements `INTEGRATION_READINESS.md`, which proves only that
unconfigured tools fail closed without contacting a provider. GitHub already
has a separate completed record in `GITHUB_LIVE_ACCEPTANCE.md`.

The implementation in `backend/src/integration-tools.mjs` is authoritative for
environment names, operations, limits, and tool risk. Provider documentation is
authoritative for tenant permissions. When either side changes, update this
matrix before claiming the adapter is live-verified.

## Evidence states

Keep these states distinct in reports and Notion:

1. **Planned**: this matrix names the fixture, credential, cases, and evidence.
2. **Code-complete**: the adapter and focused tests exist.
3. **Readiness-verified**: the credential-safe readiness evaluator reports the
   expected available or unavailable state; it has made no provider request.
4. **Live read-verified**: a real isolated tenant returned the expected bounded
   read result and negative cases.
5. **Live write-verified**: every supported remote mutation was individually
   approved, observed in the disposable fixture, and reconciled or cleaned up.

Environment presence means only that discovery may advertise a tool. It does
not prove that the credential is valid, least-privileged, or accepted upstream.

## Universal fixture and credential rules

- Use a dedicated test tenant, workspace, project, space, schema, or server.
- Use a dedicated expiring credential. Do not reuse a personal administrator,
  production service-role, connected Codex, browser-session, or CLI credential.
- Restrict the credential at the provider first, then restrict content access
  to the smallest disposable fixture. A CodeAgent table allowlist is an
  additional boundary, not a substitute for provider authorization.
- Store backend credentials only in the deployment secret store or local
  ignored `backend/.env`. MCP static bearer environment names and OAuth tokens
  remain device-local; OAuth tokens are stored in JetBrains Password Safe.
- Never put a credential in a command argument, URL, request body fixture,
  screenshot, Notion page, issue, commit, CI artifact, or support bundle.
- Rotate or revoke a test credential after live acceptance. Record only the
  credential type, provider-visible identifier in redacted form, expiration,
  and the time revocation was confirmed.

## Adapter matrix

| Adapter and CodeAgent tool | Configuration contract | Minimum provider-side access and fixture | Live success case | Remote mutation and approval |
| --- | --- | --- | --- | --- |
| Notion: `notion_search`, `notion_manage` | `NOTION_TOKEN`; optional `NOTION_API_URL`, `NOTION_VERSION` | Isolated connection shared only with one disposable parent page. Enable **Read content**, **Insert content**, and **Update content**; request no user or comment capability. Parent sharing is inherited by children, so do not share a production ancestor. | Search a unique marker, read the returned page plus first 100 blocks, then create one child, update its title, and append unique plain text. | `create_page`, `update_page`, and `append_content` are `mutating`. Each call must stop at an IDE approval card; approve one operation at a time and verify the exact target and payload before proceeding. |
| Linear: `linear_search` | `LINEAR_API_KEY`; optional `LINEAR_API_URL` | Dedicated user in a disposable workspace with view access only to the fixture team/issues. CodeAgent currently accepts a personal API key, whose effective access is the user's access; do not give that user admin or write responsibilities. If the adapter later supports OAuth, request only `read`. | Search a unique title and a unique description marker; confirm returned identifier, state, team, URL, and update time. | None. The implemented adapter issues only a GraphQL query and remains `read_only`. |
| Jira: `jira_search` | `JIRA_BASE_URL` plus either `JIRA_BEARER_TOKEN`, or `JIRA_EMAIL` + `JIRA_API_TOKEN` | Dedicated account with **Browse projects** only in one disposable project and only the issue-security visibility needed for fixtures. For OAuth bearer credentials, prefer classic `read:jira-work` or the endpoint's narrower granular read scopes. | Run bounded JQL for one unique issue and confirm key, summary, status, issue type, assignee, and update time. | None. The implemented adapter only calls enhanced JQL search and remains `read_only`. |
| Confluence: `confluence_search` | `CONFLUENCE_BASE_URL` plus either `CONFLUENCE_BEARER_TOKEN`, or `CONFLUENCE_EMAIL` + `CONFLUENCE_API_TOKEN` | Dedicated account that can view only one disposable space/page tree. For OAuth bearer credentials use classic `search:confluence` or granular `read:content-details:confluence`; do not grant write or space-admin access. | Search a unique CQL marker and confirm the page title, absolute URL, and sanitized excerpt. | None. The implemented adapter only searches and remains `read_only`. |
| Glean: `glean_search` | `GLEAN_SEARCH_ENDPOINT`, `GLEAN_API_TOKEN` | Dedicated Client API token with only the **SEARCH** scope, an expiration, and the narrowest permission mode supported by the test deployment. The current adapter does not send `X-Scio-ActAs`; do not provision a token that requires that header. Seed a non-sensitive unique document. | Search the unique marker and confirm title, permission-filtered URL, and sanitized snippet. | None. The implemented adapter only searches and remains `read_only`. |
| Supabase: `supabase_query` | `SUPABASE_URL`, `SUPABASE_KEY`, comma-separated `SUPABASE_TABLES`; optional `SUPABASE_SCHEMA` | Disposable project/schema with RLS enabled and `SELECT` allowed only on dedicated tables or views. Prefer a low-privilege JWT such as the legacy `anon` key plus restrictive RLS. Never use `service_role` or a secret key: both bypass RLS. The current adapter sends the same key as `apikey` and bearer token, so modern `sb_publishable_...` keys need compatibility work before they are a valid acceptance credential. | Read one allowlisted table/view with equality filters and a bounded limit; prove a non-allowlisted table is rejected before network access and an RLS-hidden row is absent. | None. The implemented adapter performs only PostgREST `GET` and remains `read_only`. |
| MCP servers: dynamically namespaced tools | Per-server Settings record. Stdio credentials use explicitly allowlisted environment names. HTTP uses a bearer environment name or OAuth; OAuth access/refresh tokens stay in Password Safe. | One disposable server identity with server-specific minimum scopes. For HTTP OAuth, use PKCE and the discovered protected-resource/authorization-server metadata. For stdio, pass only named variables required by that server. | Start/test the server, verify protocol/version/ping and tool discovery, call one annotated read-only tool, then exercise one denied or expired credential. | A tool is `read_only` only when MCP declares `readOnlyHint=true` and not `destructiveHint=true`; every other MCP tool is conservatively `mutating` and must obtain IDE approval per call. Chat and Ask never receive mutating tools. |
| Unified model gateway | `MODEL_BASE_URL`, `MODEL_API_KEY`; optional `MODEL`, `CONTEXT_QUERY_MODEL` | Dedicated project/workspace/team credential limited to model listing and inference for the exact allowlisted models. Endpoint must use HTTPS outside loopback and must return explicit `openai-responses`, `anthropic-messages`, or `xai-responses` metadata. | List models, complete one text-only turn, stream deltas, complete one read-only tool continuation, reject an unknown model, and preserve a real upstream 401/403/429/5xx. | No provider-side data mutation is implemented. Sending prompts, selected repository content, attachments, and tool results is still an external data transfer and must use non-sensitive fixtures. |
| Fixed OpenAI provider | `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODELS`; optional `OPENAI_INTERNAL_MODELS` | Dedicated OpenAI project key/custom role with **List models: Read** and **Model capabilities: Request**, limited to the accepted project and model allowlist. No files, assistants, fine-tuning, administration, or key-management rights. | Discover or use the configured models, stream one Responses turn, continue one tool call, and verify model-denied and rate-limit errors. | No provider-side mutation. |
| Fixed Anthropic provider | `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_MODELS`; optional `ANTHROPIC_INTERNAL_MODELS` | Expiring API key in a dedicated workspace containing only the acceptance workload and spend limit. Workspace scoping is the provider boundary; no organization or workspace-admin role is required to run inference. | List or use the configured models, stream one Messages turn, continue one tool call, and verify model-denied and rate-limit errors. | No provider-side mutation. |
| Fixed xAI/Grok provider | `GROK_BASE_URL`, `GROK_API_KEY`, `GROK_MODELS`; optional `GROK_INTERNAL_MODELS` | Dedicated team key with ACLs only for the required inference endpoint and exact accepted model IDs. Set expiration and bounded QPS/QPM/TPM; do not use endpoint or model wildcards. | Use the configured model, stream one xAI Responses turn, continue one tool call, and verify ACL-denied and rate-limit errors. | No provider-side mutation. |
| OpenAI/Anthropic/AWS Bedrock BYOK | JetBrains Password Safe plus provider base URL. Bedrock also needs region, access key ID, secret, optional session token, and exact model ID. | OpenAI/Anthropic follow the same dedicated project/workspace rules above. Prefer temporary AWS credentials; allow only `bedrock:InvokeModel` for the exact model and region used by CodeAgent's non-streaming Converse call. No Bedrock administration, marketplace, training, agents, knowledge bases, or S3 access. | Discover OpenAI/Anthropic models; for Bedrock use the configured model; run one text turn and one tool continuation per provider, then verify incomplete credentials are excluded. | No provider-side mutation. |

## Required cases for every live run

Every adapter record must include all applicable cases. A skipped case needs a
reason and remains an open gate.

| Case | Required observation |
| --- | --- |
| Missing credential | Services reports `Unavailable`; discovery names missing environment variables; execution fails closed with HTTP 503; no network request is made. |
| Success | Result came from the production adapter against the disposable fixture and contains a unique marker that a canned response could not know. |
| Permission insufficient | Provider returns its real 401/403 or GraphQL error; CodeAgent reports failure, never success, while keeping secrets redacted. |
| Upstream error | Real or controlled upstream 429/5xx/timeout is preserved as an error; retry is bounded and does not duplicate a mutation. |
| Input boundary | Oversized, malformed, out-of-allowlist, or invalid-ID input is rejected before the provider request where the implementation promises local validation. |
| Empty result | A legitimate empty list is rendered as a healthy empty response, not an unavailable or fabricated success state. |
| Revocation | The credential is revoked or expired after acceptance; the next probe becomes unavailable or unauthorized without leaking the credential. |

## Mutation approval protocol

For every `mutating` backend or MCP call:

1. Use Agent mode. Chat and Ask must not receive the tool definition.
2. Capture the approval card before approval, with credential fields absent.
3. Verify provider, operation, tenant, target ID, and bounded payload.
4. Approve exactly one call. A prior approval never authorizes a retry or a
   different target.
5. Capture the completed tool card and independently read the provider state.
6. For a negative pass, reject the same class of call and prove no remote state
   changed.
7. Reconcile or delete disposable content through an explicitly approved call
   when the adapter supports it; otherwise record the manual cleanup owner.

Notion currently supports three remote write operations, so its live run needs
three separate approvals. MCP approval count depends on the tools exposed by
the selected server. The other adapters in this matrix are read-only today.

## Evidence redaction contract

### Forbidden in every artifact

- Token, API key, password, secret, session token, OAuth code/verifier, cookie,
  `Authorization`/`x-api-key`/`apikey` header value, or raw environment value.
- Temporary or signed download/log URL, URL query containing a credential, or
  provider response that echoes a credential.
- Full `.env`, Keychain, Password Safe, process environment, HTTP trace, or
  provider admin screenshot containing adjacent credentials.
- Production page content, issue text, source document, database row, prompt,
  repository excerpt, or personal user data.

### Allowed evidence

- Adapter/tool name, risk, CodeAgent version/commit, UTC timestamp, fixture type,
  operation, HTTP/GraphQL status, provider request ID when it is non-secret,
  bounded unique marker, redacted resource ID, and result summary.
- Environment variable **names** plus boolean configured/missing state.
- Stable provider page/issue URLs only when the disposable resource is intended
  to remain visible; otherwise store a redacted ID such as `abcd…wxyz`.
- Screenshots cropped to the CodeAgent surface after confirming no credential,
  personal data, or unrelated project content is visible.

Before attaching evidence, search it for at least these case-insensitive terms:
`authorization`, `bearer`, `token`, `api_key`, `api-key`, `secret`, `password`,
`session`, `cookie`, `x-api-key`, `apikey`, `sig`, `signature`, and `expires`.
Any match must be reviewed manually; do not blindly replace status text such as
`token missing` that contains no value.

## Evidence record template

```markdown
## <adapter> live acceptance — <UTC date/time>

- CodeAgent commit/version:
- State reached: live read-verified | live write-verified | blocked
- Disposable fixture:
- Credential type and redacted provider identifier:
- Credential expiration/revocation:
- Minimum provider permissions:
- Environment names configured (names only):
- Operation and risk:
- Approval decision and screenshot path:
- Provider status / non-secret request ID:
- Unique marker and redacted resource ID:
- Result summary:
- Negative cases:
- Redaction scan command/result:
- Independent read-back or no-mutation proof:
- Cleanup and revocation evidence:
- Remaining risks:
```

## Credential-readiness execution order

Never wait on a lower-ready provider while another isolated fixture is ready.
Choose the first row whose complete prerequisite set is available; use the
order below only as a tie-breaker.

1. Run `node scripts/evaluate-integration-readiness.mjs` with no credentials to
   prove every unavailable tool fails closed.
2. Notion: it covers both read and per-call approval-gated mutation and is the
   project's required non-GitHub dogfood gate.
3. Linear, Jira, and Confluence: read-only fixtures with small, easily audited
   content boundaries.
4. Supabase: only after RLS and a low-privilege JWT fixture are reviewed; do not
   substitute a secret or `service_role` key.
5. Glean: only after a dedicated enterprise Client API token and non-sensitive
   permission-filtered fixture exist.
6. MCP: one server at a time after transport auth and every discovered tool's
   risk annotation are reviewed.
7. Model providers: one dedicated provider project/workspace/team at a time,
   with cost caps and non-sensitive prompts; verify unified, fixed, and BYOK
   routes only when their exact credentials are ready.

Run a provider-scoped readiness check immediately before live acceptance:

```bash
node scripts/evaluate-integration-readiness.mjs --catalog notion --strict
node scripts/evaluate-integration-readiness.mjs --catalog linear --strict
node scripts/evaluate-integration-readiness.mjs --catalog jira --strict
```

The readiness evaluator covers backend integration tools only. MCP and model
providers use their Settings/runtime snapshots and provider-specific checks.

## Official permission references

- [Notion connection capabilities](https://developers.notion.com/reference/capabilities)
- [Linear OAuth scopes and read-only scope](https://linear.app/developers/oauth-2-0-authentication)
- [Jira issue search permissions and scopes](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/)
- [Confluence content search permissions and scopes](https://developer.atlassian.com/cloud/confluence/rest/v1/api-group-search/)
- [Glean Client API token permissions and scopes](https://developers.glean.com/docs/client_api_scopes/)
- [Supabase API keys and RLS boundary](https://supabase.com/docs/guides/api/api-keys)
- [MCP authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [OpenAI platform RBAC permissions](https://developers.openai.com/api/docs/guides/rbac)
- [Anthropic authentication and workspace-scoped keys](https://platform.claude.com/docs/en/manage-claude/authentication)
- [xAI API-key endpoint/model ACLs](https://docs.x.ai/developers/rest-api-reference/management/auth)
- [Amazon Bedrock inference permissions](https://docs.aws.amazon.com/bedrock/latest/userguide/inference-prereq.html)
