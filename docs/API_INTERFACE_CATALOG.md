# CodeAgent API interface catalog

Complete request/response catalog for the **implemented** CodeAgent Webview, IDEA bridge, and deployed Agent backend.

The original Augment `0.482.3` plugin exposes a **much larger** multi-layer surface (local sidecar HTTP, cloud DTOs, webview Redux actions, gRPC). That inventory is in [`docs/ORIGINAL_PLUGIN_API_SURFACE.md`](ORIGINAL_PLUGIN_API_SURFACE.md) — do not confuse it with CodeAgent’s narrow gateway.

Related:

- Product UI contract: `docs/FINAL_PROTOTYPE_CONTRACT.md`
- Shorter bridge summary: `docs/FRONTEND_INTERFACES.md`
- Provider flow: `docs/PROVIDER_AND_DATA_FLOW.md`

---

## 1. Topology

```
Webview (Svelte)
  ↔ JBCef JSON bridge (commands / events)
  ↔ JVM capability gateway (IdeBridge)
       ├─ local tools: VFS / terminal / Git / Diff / ContextEngine sidecar
       └─ HTTP/SSE → Deployed Agent backend → model providers
```

Protocol version: **1** for bridge and backend.

---

## 2. Webview ↔ JVM bridge

### 2.1 Envelope

**Command (UI → JVM)**

```json
{
  "version": 1,
  "id": "uuid",
  "type": "sendMessage",
  "payload": {}
}
```

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `version` | number | yes | Must be `1` |
| `id` | string | yes | Correlation id for this command |
| `type` | string | yes | Command name |
| `payload` | object \| omit | no | Command-specific body |

**Event (JVM → UI)**

```json
{
  "version": 1,
  "type": "snapshot",
  "payload": {}
}
```

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `version` | number | yes | `1` |
| `type` | string | yes | Event name |
| `payload` | any | no | Event body |

Transport:

- UI posts: `window.codeAgentPost(JSON.stringify(command))`
- JVM pushes: `window.CodeAgent.receive(JSON.stringify(event))`

---

### 2.2 Commands (request parameters)

| type | Request payload | Response path |
| --- | --- | --- |
| `bootstrap` | none | `snapshot` + health/models refresh |
| `sendMessage` | `{ text: string, mode: "agent"\|"chat"\|"ask" }` | run starts; stream via events |
| `queueMessage` | `{ text: string, mode }` | `snapshot` with queue |
| `removeQueuedMessage` | `{ messageId: string }` | `snapshot` |
| `cancelRun` | none | `snapshot` runState idle |
| `setMode` | `{ mode }` | `snapshot` |
| `selectModel` | `{ modelId: string }` | `snapshot.models` |
| `newThread` | `{ mode?: string }` | `snapshot` |
| `selectThread` | `{ threadId: string }` | `snapshot` |
| `editAndResendMessage` | `{ messageId: string, text: string }` | rewind + `snapshot`/streamed run events |
| `toggleThreadPinned` | `{ threadId: string }` | `snapshot` |
| `deleteThread` | `{ threadId: string }` | `snapshot` |
| `deleteThreads` | `{ threadIds: string[] }` | `snapshot` |
| `renameThread` | `{ threadId: string, title: string }` | `snapshot` |
| `deleteRule` | `{ ruleId: string }` | refreshed customization `snapshot` |
| `saveGuidelines` | `{ content: string }` | refreshed customization `snapshot` |
| `configureByok` | `{ provider, apiKey?, baseUrl?, accessKeyId?, secretAccessKey?, sessionToken?, region?, model? }` | secure Password Safe write + refreshed `snapshot` |
| `clearByok` | `{ provider }` | secure credential removal + refreshed `snapshot` |
| `copyThread` | none | `notice` |
| `exportThread` | none | folder chooser + `notice`/`error` |
| `importThread` | none | file chooser + `snapshot`/`error` |
| `continueTasksInNewThread` | none | cloned task-list `snapshot` |
| `clearConversationSummary` | `{ threadId: string }` | updated thread-memory `snapshot` |
| `pickContext` | none | IDE file picker + `snapshot.attachments` |
| `removeContext` | `{ id: string }` | `snapshot` |
| `toggleSkill` | `{ skillId: string, selected: boolean }` | `snapshot` |
| `toggleRule` | `{ ruleId: string, selected: boolean }` | `snapshot` |
| `saveRule` | `{ fileName, content, trigger, description }` | `snapshot` |
| `refreshCustomization` | none | `snapshot.customization` |
| `resolveApproval` | `{ toolId: string, approved: boolean }` | tool continues/rejects |
| `openDiff` | `{ toolId: string }` | opens IDEA Diff |
| `revertChange` | `{ toolId: string }` | reverts if still matching |
| `reviewChanges` | `{ toolIds: string[] }` | opens first Diff |
| `keepChanges` | `{ toolIds: string[] }` | marks kept |
| `discardChanges` | `{ toolIds: string[] }` | reverts batch |
| `enhancePrompt` | `{ text: string, mode?: string }` | `promptEnhanced` or `error` |
| `createCheckpoint` | `{ label?: string }` | `checkpoints` + `notice` |
| `listCheckpoints` | none | `checkpoints` |
| `restoreCheckpoint` | `{ checkpointId: string }` | `snapshot` + `checkpoints` |
| `addTask` | `{ name: string }` | `snapshot.tasks` |
| `deleteTask` | `{ taskId: string }` | `snapshot` |
| `setTaskState` | `{ taskId, state }` | `snapshot` |
| `runTask` | `{ taskId }` | starts agent with task prompt |
| `runAllTasks` | none | starts agent with pending tasks |
| `clearTasks` / `clearCompletedTasks` | none | `snapshot` |
| `exportTasks` / `importTasks` | none | file IO + `snapshot`/`notice` |
| `refreshGit` | none | `gitSnapshot` |
| `stageGit` / `unstageGit` | `{ paths: string[] }` | `gitSnapshot` |
| `openGitDiff` | `{ path: string, staged: boolean }` | IDEA Diff |
| `suggestCommitMessage` | `{ files: { path, status }[] }` | `gitCommitSuggested` |
| `commitGit` | `{ message: string }` | `gitSnapshot`/`notice` |
| `refreshImageCanvas` | none | `imageCanvas` |
| `browseImageDirectory` | none | chooser + `imageCanvas` |
| `openImage` | `{ path: string }` | opens file |
| `attachImage` | `{ path: string }` | attachment chip |
| `openMermaidEditor` | `{ title: string, code: string }` | editor tab |
| `openTerminal` | none | focuses Terminal tool window |
| `saveSettings` | `{ backendUrl, nodePath, backendToken, autoApproveReadOnly }` | `snapshot.settings` |
| `refreshConfigurations` | none | `snapshot.configurations` |
| `saveConfiguration` | `{ kind, id, value }` | validates/persists then refreshes `snapshot.configurations` |
| `deleteConfiguration` | `{ kind, id }` | deletes then refreshes `snapshot.configurations` |
| `checkBackend` | none | `snapshot.backendHealth` / models / backendTools |
| `getContextStatus` | none | `snapshot.context` |
| `indexWorkspace` | none | indexing progress via `snapshot.context` |

Errors for failed commands: event `error` with `{ message: string }`.

---

### 2.3 Events (return payloads)

#### `snapshot` → `AppSnapshot`

| Field | Type | Description |
| --- | --- | --- |
| `projectName` | string | IDEA project name |
| `mode` | `agent`\|`chat`\|`ask` | Active mode |
| `runState` | `idle`\|`running`\|`awaiting_approval`\|`failed` | Run lifecycle |
| `messages` | `ChatMessage[]` | Ordered conversation |
| `tools` | `ToolRun[]` | Current run tool cards |
| `threads` | `ThreadSummary[]` | Thread list |
| `tasks` | `TaskItem[]` | Active thread tasks |
| `messageQueue` | `QueuedMessage[]` | Queued prompts |
| `attachments` | `ContextItem[]` | Composer chips |
| `settings` | object | Backend/context connection settings plus chatZoom, showTimestamps, showRunTelemetry, desktopNotifications, and autoDismissNotifications |
| `account` | object | authentication state, identity, session mode, usage, and label |
| `context` | object | ContextEngine state/label/files/chunks |
| `backendHealth` | object | online/offline + protocol/provider |
| `models` | object | state, provider, default/selected, options[] |
| `backendTools` | array | backend tool name/catalogId/availability/reason/required env |
| `configurations` | object | state, label, and typed MCP/command/hook/agent/plugin/tool-permission records |
| `customization` | object | rules[], skills[], maxSelectedSkills |

**ChatMessage**

| Field | Type |
| --- | --- |
| `id` | string |
| `role` | `user`\|`assistant`\|`system` |
| `content` | string |
| `createdAt` | number (epoch ms) |
| `turnIndex` | number? (assistant run messages) |

**ToolRun**

| Field | Type |
| --- | --- |
| `id` | string |
| `name` | string |
| `summary` | string |
| `status` | `running`\|`approval`\|`completed`\|`failed`\|`rejected` |
| `detail` | string? |
| `changePath` | string? |
| `canRevert` | boolean |
| `turnIndex` | number? |
| `runId` | string? |
| `createdAt` | number (epoch ms) |
| `updatedAt` | number (epoch ms; final state or latest transition) |

#### Other events

| type | payload |
| --- | --- |
| `stateChanged` | partial AppSnapshot fields |
| `messageDelta` | `{ id: string, delta: string, turnIndex: number }` |
| `error` | `{ message: string }` |
| `notice` | `{ message: string }` |
| `gitSnapshot` | `{ available, branch, repository, unstaged[], staged[], error? }` |
| `gitCommitSuggested` | `{ message: string }` |
| `imageCanvas` | `{ directory, images[{id,name,path,dataUrl,sizeBytes}], truncated, error? }` |

In-panel notices can auto-dismiss according to the persisted user-experience preference. When enabled, the JVM also emits IDE-native notifications for run completion, run failure, and approval requests; notification sound and display style remain controlled by JetBrains notification settings.

---

## 3. Deployed Agent backend HTTP/SSE

Base URL: settings `backendUrl` (fresh-install default `http://127.0.0.1:8788` for the local Docker deployment).
Auth header on protected routes: `Authorization: Bearer <CODEAGENT_AUTH_TOKEN>`.

### 3.1 `GET /health` (no auth required)

**Response 200**

```json
{
  "ok": true,
  "service": "codeagent-backend",
  "protocolVersion": 1,
  "provider": "openai|anthropic|xai|custom",
  "defaultModel": "string"
}
```

| Field | Type | Description |
| --- | --- | --- |
| `ok` | boolean | Process healthy |
| `service` | string | Constant service id |
| `protocolVersion` | number | Must match plugin expectation (`1`) |
| `provider` | string? | Active gateway family |
| `defaultModel` | string? | Default route model |

### 3.2 `GET /v1/models`

**Request headers:** `Authorization: Bearer …`

**Response 200**

```json
{
  "object": "list",
  "provider": "string",
  "defaultModel": "string",
  "data": [
    { "id": "gpt-5.6-sol", "ownedBy": "openai" }
  ]
}
```

| Field | Type |
| --- | --- |
| `data[].id` | string |
| `data[].ownedBy` | string? |


### 3.3 `GET /v1/tools`

Returns backend-owned tool schemas and runtime configuration status. The JVM advertises only `available=true` tools to the model.

```json
{
  "object": "list",
  "data": [
    {
      "name": "web_search",
      "catalogId": "web",
      "description": "Search the public web through the configured backend search provider",
      "parameters": { "type": "object" },
      "risk": "read_only",
      "available": false,
      "unavailableReason": "Set WEB_SEARCH_ENDPOINT",
      "requiredEnvironment": ["WEB_SEARCH_ENDPOINT"]
    }
  ]
}
```

`risk` is one of `read_only`, `local_state`, or `mutating`. The JVM carries this value into the run tool definition, filters mutating tools from Chat/Ask, and treats an unknown future risk value as mutating.

### 3.4 `POST /v1/tools/{toolName}`

Executes one configured backend-owned tool. Credentials remain in backend environment variables.

```json
{ "arguments": { "query": "CodeAgent" } }
```

Success returns `{ "output": "…", "summary": "…", "detail": "…" }`. Missing configuration returns `503 { "error": "…" }`; provider failures or invalid provider responses return `502`; unknown tools return `404`.

Current adapters: `web_search`, `github_search`, approval-gated `github_manage`, separately approval-gated `github_actions_manage` and `github_merge_pull_request`, `linear_search`, `notion_search`, `jira_search`, `confluence_search`, `glean_search`, `supabase_query`, and synchronous model-only `subagent`.

GitHub operations are split by effect and risk:

| Tool | Operations | Guardrails |
| --- | --- | --- |
| `github_search` | Search; read issues, pull requests, changed files, commits, discussion comments, reviews, line-level review comments, head-SHA Check Runs, PR workflow runs, jobs and failed steps, temporary job-log URLs, branch protection, repository rulesets, merge readiness, and bounded UTF-8 repository files | Read-only; list operations are bounded to 100 results, readiness audits at 1,000 reviews, logs at 24,000 output characters, and file reads to 512,000 bytes |
| `github_manage` | Create issues and pull requests, add comments, change issue/PR state, submit `APPROVE`/`REQUEST_CHANGES`/`COMMENT` reviews with up to 50 validated line comments, and request user or team reviewers | Mutating; every call requires IDE approval and sends a bounded REST payload |
| `github_actions_manage` | Rerun a workflow or only its failed jobs, cancel or force-cancel a workflow, or rerun one job | Separate mutating approval for every Actions control; requires GitHub Actions write access |
| `github_merge_pull_request` | Merge using `merge`, `squash`, or `rebase` | Separate mutating approval; requires an explicit 40- or 64-character `expected_head_sha`, re-reads the live PR, and blocks on a closed/draft/conflicted PR, insufficient approvals, requested changes, missing/pending/failed required checks, or an unresolved-conversation policy that REST cannot prove |

The adapter preserves `GITHUB_API_URL` for GitHub Enterprise Server and sends `X-GitHub-Api-Version: 2022-11-28`. A token must provide Metadata, Issues, Pull requests, Contents, Checks, and Actions read access for the complete read surface; branch protection and repository rulesets may require Administration read. Write operations additionally require the corresponding Issues, Pull requests, or Actions write permissions. Configuration alone does not imply that production credentials have been provisioned.

### 3.5 `POST /v1/enhance`

**Request headers:** `Authorization: Bearer …`

**Request body**

```json
{
  "text": "fix the null pointer",
  "mode": "agent",
  "model": "gpt-5.6-sol"
}
```

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `text` | string | yes | Original prompt (`1..12000` chars) |
| `mode` | `"agent"\|"chat"\|"ask"` | no | Composer mode hint |
| `model` | string | no | Override model; defaults to gateway default |

**Response 200**

```json
{
  "text": "Improved prompt…",
  "model": "gpt-5.6-sol",
  "provider": "unified-native"
}
```

### 3.6 Product configuration CRUD

Configuration kinds are `mcp`, `commands`, `hooks`, `agents`, `plugins`, and `tool-permissions`. IDs use 1-120 ASCII letters, numbers, dots, underscores, or hyphens. Values are normalized by kind and unknown fields are discarded.

- `GET /v1/configurations/{kind}` returns `{ "object": "list", "data": ProductConfiguration[] }`.
- `PUT /v1/configurations/{kind}/{id}` validates and account-persists one definition, then returns the normalized record.
- `DELETE /v1/configurations/{kind}/{id}` returns `204`; unknown records return `404`.

MCP definitions reference credential environment-variable names only. Remote endpoints require HTTPS except loopback HTTP, and URLs containing embedded credentials are rejected. After configuration refresh, enabled definitions are reconciled into the local managed MCP gateway; secret values are read only from explicitly allowlisted process environment names and never round-trip through the backend or Webview.

Plugin definitions contain `source`, optional exact `version`, optional `sha256:<hex>` `integrity`, and an explicit capability grant list. Sources require HTTPS except loopback HTTP and cannot contain credentials or fragments. The account record does not install a plugin by itself: the Webview invokes the IDE-owned `installPlugin`, `updatePlugin`, `testPlugin`, or `uninstallPlugin` bridge command, and the JVM downloads at most a 1 MiB declarative JSON manifest into the device-local cache. It rejects unknown manifest fields, mismatched identity/version/integrity, undeclared grants, unsupported capabilities, invalid declarative contributions, and command/prompt IDs that collide in the shared slash namespace. Removing the account configuration also removes the local cached manifest.

Manifest schema version `1` supports the declared capabilities `commands`, `agents`, `hooks`, `mcp`, `rules`, `skills`, `tools`, and `prompts`. Explicitly granted `commands` and `prompts` are exposed to the composer as namespaced slash templates and resolve through the same bounded command runtime as account commands. Explicitly granted `rules` and `skills` become read-only namespaced workspace context, with manual rules and skills selected per conversation. `agents` contributes request-scoped profiles that the backend reconstructs and validates on every run. `hooks` and `mcp` contribute namespaced configurations to the existing supervised Hook and MCP runtimes after local installation. `tools` contributes aliases and default argument templates for existing tools; aliases inherit the target schema, mode restriction, and approval risk and cannot register executable handlers.

### 3.7 Conversation synchronization

Conversation endpoints are account-isolated and require authentication outside local development mode:

- `GET /v1/conversations` returns lightweight summaries and message/task/tool counts.
- `POST /v1/conversations` creates a normalized conversation and returns version `1`.
- `GET /v1/conversations/{id}` returns messages, tasks, tool timeline records, summary, selected model/profile/customization IDs, and synchronization metadata.
- `PUT /v1/conversations/{id}` replaces the snapshot. An optional `If-Match: <version>` header enables optimistic concurrency and returns `409` when another client has already advanced the version.
- `DELETE /v1/conversations/{id}` removes the account copy and returns `204`.

The IDE keeps local history authoritative while offline, debounces uploads, retains deletion tombstones until the backend confirms removal, and resolves version conflicts using the newer client timestamp. Limits are 200 messages, 100 tasks, and 1,000 tool records per conversation; backend validation also bounds text and timeline fields.

### 3.8 `POST /v1/runs` (SSE)

**Request body: `RemoteRunRequest`**

```json
{
  "mode": "agent",
  "agentProfileId": "plugin.review-pack.reviewer",
  "agentProfile": {
    "id": "plugin.review-pack.reviewer",
    "pluginId": "review-pack",
    "pluginVersion": "1.0.0",
    "name": "Review Agent",
    "agentType": "loop",
    "allowedTools": ["plugin.review-pack.read-review-scope", "diagnostics"],
    "maxTurns": 16,
    "maxToolCalls": 64,
    "maxSubagentCalls": 3,
    "verificationPolicy": "after-mutation",
    "contextWindowTokens": 256000,
    "reservedOutputTokens": 8192
  },
  "model": "claude-sonnet-5",
  "messages": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ],
  "tools": [
    {
      "name": "read_file",
      "description": "...",
      "parameters": { "type": "object", "properties": {}, "required": [] }
    }
  ],
  "workspace": {
    "guidance": "AGENTS.md text",
    "rules": [{ "name": "style", "path": ".codeagent/rules/style.md", "content": "..." }],
    "skills": [{ "name": "api", "path": ".codeagent/skills/api/SKILL.md", "content": "..." }]
  }
}
```

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `mode` | string | yes | `agent`\|`chat`\|`ask` |
| `model` | string | no | Allowlisted model id |
| `messages` | array | yes | History; roles `user`\|`assistant` only from JVM history (tool msgs added by backend loop) |
| `messages[].role` | string | yes | |
| `messages[].content` | string? | no | Text content |
| `tools` | array | yes | JVM-advertised tool schemas only |
| `tools[].name` | string | yes | |
| `tools[].description` | string | yes | |
| `tools[].parameters` | object | yes | JSON Schema object |
| `workspace.guidance` | string? | no | Root AGENTS.md |
| `workspace.rules` | array | no | Selected/always rules |
| `workspace.skills` | array | no | Selected skills |

**Response:** `200 text/event-stream`  
Header: `x-codeagent-run-id: <runId>`

**SSE events**

| event | data fields | When |
| --- | --- | --- |
| `run.started` | `{ runId, protocolVersion, provider, model }` | Immediately |
| `turn.started` | `{ turnIndex }` | Each model turn |
| `message.delta` | `{ delta, turnIndex }` | Streamed assistant text |
| `assistant.completed` | `{ content, turnIndex }` | End of assistant text for turn |
| `tool.request` | `{ call: { id, name, arguments }, turnIndex }` | Model requested tool |
| `tool.completed` | `{ toolCallId, status, summary }` | After local result applied |
| `run.completed` | `{ turnCount }` | No more tools |
| `run.error` | `{ message }` | Fatal run error |
| comment heartbeat | `: heartbeat` | Every ~15s |

### 3.9 `POST /v1/runs/{runId}/tool-results`

**Request body**

```json
{
  "toolCallId": "call_abc",
  "status": "completed",
  "output": "tool stdout / structured text",
  "error": "",
  "summary": "short UI summary"
}
```

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `toolCallId` | string | yes | Matches `tool.request.call.id` |
| `status` | string | yes | `completed`\|`failed`\|`rejected` |
| `output` | string | no | Returned to model as tool message |
| `error` | string | no | Failure text |
| `summary` | string? | no | Optional short label |

**Response 202**

```json
{ "accepted": true }
```

### 3.10 `DELETE /v1/runs/{runId}`

**Response 202**

```json
{ "cancelled": true }
```

---

## 4. Multi-turn tool + text contract (frontend expectation)

Backend loop:

1. Stream assistant text (`message.delta` / `assistant.completed`) — may be empty if pure tool call.
2. Emit zero or more `tool.request` for that turn.
3. JVM executes/approves tools, posts `tool-results`.
4. Backend continues; **next turn may emit more assistant text** (post-tool narration).
5. Ends on a turn with no tool calls.

UI timeline (prototype-aligned):

```
user message
→ assistant text (pre-tool, optional)
→ tool cards (running / approve / done)
→ assistant text (post-tool / final, optional)
→ tasks panel (if any)
```

---

## 5. Original Augment 0.482.3 interface reference

Evidence from `extracted/plugin-jar` (not reimplemented 1:1).

### 5.1 Process topology

| Layer | Classes / assets | Role |
| --- | --- | --- |
| Webview | `webviews/*.html`, MainPanel/Settings bundles | Svelte UI |
| Messaging | `AugmentMessagingService`, `ChatMessagingService`, `SettingsMessagingService`, `RPCAdapter` | Webview ↔ JVM typed RPC |
| Client gRPC | `ClientGrpcService`, Ktor auth/CORS plugins | Local IDE capability HTTP/gRPC |
| Sidecar | `SidecarStartupActivity`, `sidecar/index.cjs` | Node agent/tools/MCP |
| Sidecar OpenAPI models | `com.augmentcode.intellij.sidecar.api.*` | Generated client models |
| Sidecar RPC | `sidecarrpc.IOConnection` | Stream message framing |

### 5.2 Observed sidecar/IDE API surface (class names)

**ACP / sessions**

- `POST` style models: `ApiAcpInitializePostRequest/Response`
- Completions: `ApiAcpCompletionsRequestPost*`, `Cancel`, `Resolve`
- Sessions: `ApiAcpSessionsPost*`, `Get*`, `SessionIdPromptPost*`, cancel/stop variants

**IDE capabilities (local)**

- `ApiIdeCapabilitiesGet*`
- Filesystem: `ApiIdeFilesystemListPost*`, `ApiIdeFilesystemReadPost*`
- Editor: `ApiIdeEditorOpenPost*`, `ApplyEditPost*`, `SelectionsPost*`
- Diagnostics: `ApiIdeDiagnosticsPost*`
- Terminal: `Create`, `Output`, `WaitForExit`, `Kill`
- SSE: `ApiSseIdeGet*`, `apiSseWebviewGet`

**Auth**

- `ApiKeyAuth`, `HttpBearerAuth`, `OAuth`, `AuthHandler`, MCP OAuth services in settings webview

### 5.3 Webview UI message nodes (from MainPanel/Store)

Conversation is a **node stream**, not a single assistant string:

| Node / selector | Meaning |
| --- | --- |
| `thinkingNodes` | Thinking summary blocks |
| `toolUseNodes` / `displayableToolUseNodes` | Tool cards with phase |
| `postToolUseMessagesByToolId` | Text after a specific tool |
| `responseText` | Main assistant response text |
| tool phases | running / completed / error / cancelled / approval |
| checkpoints | Revert to checkpoint interactions |

### 5.4 Mapping Augment → CodeAgent

| Augment | CodeAgent |
| --- | --- |
| Webview MessageBroker / RPCAdapter | `codeAgentPost` / `CodeAgent.receive` JSON |
| Sidecar agent loop + cloud | Deployed `POST /v1/runs` SSE |
| Sidecar IDE tool callbacks | JVM `AgentToolExecutor` |
| Cloud search/read tools | Backend `GET /v1/tools` + `POST /v1/tools/{toolName}` proxied by `AgentToolExecutor` |
| gRPC discovery + auth token | Settings backend URL + Password Safe token |
| MCP / OAuth / Account APIs | Managed stdio/Streamable HTTP/SSE MCP gateway with discovered Agent tools, provider OAuth PKCE/token refresh, ACP v1 sessions, and real backend account APIs |

### 5.5 Local Protobuf/gRPC transport

CodeAgent's local sidecar boundary is defined by `src/main/proto/com/codeagent/plugin/context/context_engine.proto` and loaded by the Node bundle as `context-engine.proto`. The current JVM client routes known operations through the server-streaming `ContextRuntimeRpc`, `McpRuntimeRpc`, and `AcpRuntimeRpc` services, with ordered progress, deadlines, cancellation of the client stream, and bearer authentication. `ContextEngineRpc.Execute` remains as a compatibility entry point for `0.7.18` clients and unknown operation names. MCP/ACP configuration and argument bodies use JSON bytes inside their typed methods while those schemas evolve. This is an intentional protocol-level alignment with the original plugin; it is not a claim of wire compatibility with Augment's private generated services.

---

## 6. Local capability sidecar (not HTTP backend)

Protocol: loopback Protobuf/gRPC with a private readiness pipe (Node ≥ 22.5); JSON Lines is an explicit diagnostic fallback.
Used when the model requests `codebase_retrieval`, the UI triggers index/status, or account MCP definitions must be reconciled with local and remote MCP servers.

Logical operations (capability gateway owned):

- index workspace / get status / progress
- start or stop debounced workspace watching
- expose automatic-index state, last duration, and changed/deleted file counts
- retrieve packed context under token budget
- text/symbol search helpers as implemented by vendor ContextEngine
 - launch, list, read, write, wait for, and terminate bounded project-root process sessions with stable IDs and capped output buffers
 - manage MCP stdio, Streamable HTTP, and legacy SSE transport lifecycle
 - discover and refresh namespaced MCP tools using the official TypeScript SDK
 - run MCP health checks, bounded reconnects, calls, timeout enforcement, and graceful shutdown
 - inherit only explicitly allowlisted environment variables and inject bearer credentials without exposing values to the Webview

These are **not** part of `/v1/*` backend routes.

---

## 7. Error model

| Layer | Shape |
| --- | --- |
| Bridge command failure | event `error` `{ message }` |
| Backend HTTP non-2xx | JVM surfaces root message to UI |
| SSE `run.error` | ends run; UI failed state |
| Tool failure | `tool-results.status=failed` + model continues if loop allows |

---

## 8. Security constraints

- Model API keys only in backend env.
- Bridge cannot send system prompts or raw provider credentials.
- File tools resolve canonical paths inside project.
- Mutating tools require approval (unless auto-run read-only).
- Unconnected integrations must not emit fake success events.
