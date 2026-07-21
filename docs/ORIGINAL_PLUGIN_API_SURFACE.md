# Original Augment 0.482.3 API surface inventory

Evidence-only inventory from `intellij-augment-0.482.3-stable.zip` → `extracted/plugin-jar/`.  
This is **not** CodeAgent’s implemented API. It answers: “how many interfaces does the original plugin actually expose?”

CodeAgent’s **implemented** surface remains small by design (see `docs/API_INTERFACE_CATALOG.md` §2–3).  
This document catalogs the **original** product’s multi-layer interface graph.

---

## 0. Honest scope

| Layer | Approx. size in jar | Fully reverse-engineered request/response bodies? |
| --- | --- | --- |
| Sidecar HTTP `DefaultApi` paths | **19** concrete routes | Partial (paths + model class names; bodies from OpenAPI model class names) |
| Cloud / product DTO types under `com.augmentcode.api` + related | **100+** type names | Names only (protobuf/JSON DTOs; not full field dumps) |
| Webview Redux action namespaces | **200+** action type strings in Store | Action names only |
| Tool lifecycle actions | **30+** `tools/*` actions | Names + phase semantics |
| Kotlin packages | **61** packages, **1326** classes | Structural |
| Webview assets | **861** files, **387** source maps | UI extraction partial |

**Bottom line:** the original plugin is a **platform** (webview RPC + local IDE HTTP + sidecar tools + cloud agent/chat/tools/billing).  
CodeAgent currently implements a **narrow agent gateway**. Saying “only 5 endpoints” is correct **for CodeAgent**, and incomplete **as a description of Augment**.

---

## 1. Topology (original)

```
Svelte Webviews (7 HTML entries)
  ↔ MessageBroker / RPCAdapter / ChatMessagingService / SettingsMessagingService
  ↔ JVM (chat, settings, auth, index, completion, VCS, …)
  ↔ ClientGrpcService (Ktor local server: auth + CORS)
  ↔ Node Sidecar (agent/tools/MCP orchestration)
       ├─ local IDE callback HTTP: /api/ide/* , /api/sse/*
       ├─ ACP: /api/acp/*
       └─ cloud product APIs (chat-stream, agent retrieval, tools, billing, …)
```

---

## 2. Sidecar local HTTP routes (DefaultApi) — complete path list

Extracted as string constants from `com.augmentcode.intellij.sidecar.api.client.DefaultApi`.

### 2.1 ACP (Agent Client Protocol style)

| Method (client) | Path | Request model (class) | Response model (class) |
| --- | --- | --- | --- |
| `apiAcpInitializePost` | `POST /api/acp/initialize` | `ApiAcpInitializePostRequest` | `ApiAcpInitializePost200Response` |
| `apiAcpSessionsGet` | `GET /api/acp/sessions` | — | `ApiAcpSessionsGet200Response` (+ `SessionsInner`) |
| `apiAcpSessionsPost` | `POST /api/acp/sessions` | `ApiAcpSessionsPostRequest` | `ApiAcpSessionsPost200Response` |
| `apiAcpSessionsSessionIdPromptPost` | `POST /api/acp/sessions/{sessionId}/prompt` | `ApiAcpSessionsSessionIdPromptPostRequest` | `…PromptPost200Response` (+ `UpdateType`) |
| `apiAcpSessionsSessionIdCancelPost` | `POST /api/acp/sessions/{sessionId}/cancel` | sessionId path | object |
| `apiAcpSessionsSessionIdStopPost` | `POST /api/acp/sessions/{sessionId}/stop` | sessionId path | object |
| `apiAcpCompletionsRequestPost` | `POST /api/acp/completions/request` | `ApiAcpCompletionsRequestPostRequest` (blobs, location, editEvents, recencyInfo, …) | `…RequestPost200Response` (+ completion items) |
| `apiAcpCompletionsResolvePost` | `POST /api/acp/completions/resolve` | `ApiAcpCompletionsResolvePostRequest` (+ resolutions) | object |
| `apiAcpCompletionsCancelPost` | `POST /api/acp/completions/cancel` | `ApiAcpCompletionsCancelPostRequest` | object |

**Request nesting observed in model names (completions/request):**

- `Blobs`
- `CompletionLocation`
- `EditEventsInner` / `EditsInner`
- `RecencyInfo` → `RecentChangesInner`, `TabSwitchEventsInner`, `ViewedContentsInner`

### 2.2 IDE capability callbacks (sidecar → IDE host)

| Method (client) | Path | Request model | Response model |
| --- | --- | --- | --- |
| `apiIdeCapabilitiesGet` | `GET /api/ide/capabilities` | — | `ApiIdeCapabilitiesGet200Response` (+ capabilities[]) |
| `apiIdeFilesystemListPost` | `POST /api/ide/filesystem/list` | `ApiIdeFilesystemListPostRequest` | `…ListPost200Response` (+ entries[]) |
| `apiIdeFilesystemReadPost` | `POST /api/ide/filesystem/read` | `ApiIdeFilesystemReadPostRequest` | `…ReadPost200Response` |
| `apiIdeEditorOpenPost` | `POST /api/ide/editor/open` | `ApiIdeEditorOpenPostRequest` | object |
| `apiIdeEditorApplyEditPost` | `POST /api/ide/editor/apply-edit` | `ApiIdeEditorApplyEditPostRequest` (+ edits[]) | `…ApplyEditPost200Response` |
| `apiIdeEditorSelectionsPost` | `POST /api/ide/editor/selections` | (diagnostics-shaped request reused in signatures) | `…SelectionsPost200Response` |
| `apiIdeDiagnosticsPost` | `POST /api/ide/diagnostics` | `ApiIdeDiagnosticsPostRequest` | `…DiagnosticsPost200Response` (+ location, severity) |
| `apiIdeTerminalCreatePost` | `POST /api/ide/terminal/create` | `ApiIdeTerminalCreatePostRequest` | `…CreatePost200Response` (terminal handle) |
| `apiIdeTerminalOutputPost` | `POST /api/ide/terminal/output` | terminal handle | `…OutputPost200Response` |
| `apiIdeTerminalWaitForExitPost` | `POST /api/ide/terminal/wait-for-exit` | terminal handle | `…WaitForExitPost200Response` |
| `apiIdeTerminalKillPost` | `POST /api/ide/terminal/kill` | terminal handle | object |

### 2.3 SSE

| Method | Path | Response |
| --- | --- | --- |
| `apiSseIdeGet` | `GET /api/sse/ide` | `ApiSseIdeGet200Response` (+ `EventType`) |
| `apiSseWebviewGet` | `GET /api/sse/webview` | stream to webview |

**Count:** 9 ACP + 11 IDE + 2 SSE = **22 client operations / 19 unique path templates**.

---

## 3. Sidecar process tools (local tool manager)

Package: `com.augmentcode.intellij.sidecar.tools`

| Class | Role |
| --- | --- |
| `ToolsManager` | Registry / dispatch |
| `IdeTool` | IDE-facing tool base |
| `LaunchProcessTool` | start process |
| `KillProcessTool` | kill process |
| `ListProcessesTool` | list processes |
| `ReadProcessTool` | read process output |
| `WriteProcessTool` | write to process stdin |
| `AugmentTerminalInfo` | terminal metadata |

These are **in-process sidecar tools**, not the same as cloud `RunRemoteTool*`.

---

## 4. Sidecar client interfaces (JVM ↔ sidecar connection)

Package: `com.augmentcode.intellij.sidecar.clientinterfaces` (selected)

| Type | Role |
| --- | --- |
| `AuthHandler` | connection auth registration |
| `ClientWorkspaces` | workspace binding; `readFile` |
| `FileReadResult`, `ListDirectoryResult`, `PathInfo` | FS results |
| `PluginFileStore` / `PluginFileStoreHandler` | plugin file store over connection |
| `PluginClientSecrets` | secrets bridge |
| `PluginMcpConfig` | MCP config registration |
| `GlobalPluginStateForSidecar` / `GlobalPluginStateValues` | shared plugin state |

RPC framing: `com.augmentcode.intellij.sidecarrpc.IOConnection` (+ request/response/notification/progress writers).

---

## 5. Cloud / product DTO & client surface (type inventory)

From class-string inventory under `com.augmentcode` (names are API *types*, not always HTTP paths).

### 5.1 Chat / agent request graph

| Type | Meaning |
| --- | --- |
| `ChatRequest` | Top-level chat/agent send |
| `ChatRequestNode` | Polymorphic request node |
| `ChatRequestText` | Text node |
| `ChatRequestImage` / `ChatRequestImageId` | Image nodes |
| `ChatRequestToolResult` | Tool result node |
| `ChatRequestSkill` | Skill attachment |
| `ChatRequestContentNode` | Content wrapper |
| `ChatRequestIdeState` | IDE state snapshot in request |
| `ChatInstructionStreamPayload` | Streaming instruction payload |
| `ChatInputCompletionRequest` / `Response` / `Item` | Input completion |
| `ChatInputBlobsPayload` / `ChatInputFileEdit` / `FileEditEvent` | Edit/blob context |
| `ChatInputRecencyInfo` / `ViewedContent` / `ReplacementText` | Recency context |
| `AgentCodebaseRetrievalRequest` / `Response` | Context engine retrieval |
| `AgentRequestEvent` / `AgentSessionEvent` (+ Data) | Analytics/session events |
| `AgentInterruptionData` / `AgentTracingData` | Interrupt + tracing |
| `ChatHistorySummarizationData` | History summarization |
| `SaveChatRequest` / `SaveChatResponse` | Persist chat |
| `CheckpointBlobsRequest` / `Response` / `CheckpointResult` | Checkpoints |

### 5.2 Chat result graph (response nodes)

| Type | Meaning |
| --- | --- |
| `ChatResultNode` | Result node envelope |
| `ChatResultNodeMetadata` | Metadata |
| `ChatResultThinking` | Thinking block |
| `ChatResultToolUse` | Tool use block |
| `ChatResultTokenUsage` | Tokens |
| `ChatResultBillingMetadata` | Billing |
| `ChatResultAgentMemory` | Memory |
| `ToolResultContentNode` / `ToolResultImageContent` | Tool result content |
| `ToolUseData` | Tool use payload |

### 5.3 Remote tools / MCP / permissions

| Type | Meaning |
| --- | --- |
| `ListRemoteToolsRequest` / `Response` / `Result` | List remote/MCP tools |
| `RunRemoteToolRequest` / `RunRemoteToolResult` | Execute remote tool |
| `RemoteToolId` / `RemoteToolResponseStatus` / `RemoteToolsInput` | Remote tool ids/IO |
| `ToolDefinition` / `ToolIDList` | Definitions |
| `ToolPermission` / `ToolPermissionRule` (+ Wire) | Permissions |
| `ToolPermissionsSettings` (+ Wire) | Settings |
| `TenantToolPermissions` (+ Wire) | Tenant policy |
| `CheckToolSafety` / `CheckToolSafetyResponse` / `ToolSafety` | Safety check |
| `RevokeToolAccessRequest` / `Result` / `Status` | Revoke access |
| `ToolAvailabilityStatus` | Availability |
| `ExtraToolInput` / `GitHubToolExtraInput` / `LinearToolExtraInput` / `NotionToolExtraInput` / `AtlassianToolExtraInput` | Integration-specific tool inputs |

### 5.4 Auth / billing / models / errors

| Type | Meaning |
| --- | --- |
| `AuthTokenRequest` / `AuthTokenResult` | Token exchange |
| `GetSubscriptionBannerResponse` / `BannerInfo` / `BannerButton` | Subscription UI |
| `GetModelsResponseExtensionsKt` / `Model` / `EloModelConfiguration` | Models |
| `GetCreditInfo` (client path string `/api-client/get-credit-info`) | Credits |
| `UserTier` / `Enterprise` | Plan |
| `ErrorResponse` / `ErrorCode` / `ErrorDetails` / `AugmentAPIException` | Errors |
| `ReportErrorRequest` | Client error report |
| `FeatureDetectionFlags` / `FeatureFlagsExtensionsKt` | Flags |
| `MemorizeResponse` | Memories |
| `Rule` / `RuleType` | Rules |
| `WorkspaceFolderInfo` | Workspace |
| `ConnectionDetails` / `SessionId` | Connection |
| `Exchange` | Exchange unit |
| `PermissionOneof` / `SimplePermissionWire` / `ScriptPolicyWire` / `WebhookPolicyWire` | Policy wires |

### 5.5 Client path strings (cloud-facing)

Observed string constants (not full OpenAPI):

| Path / client key | Role |
| --- | --- |
| `/api-client/chat-stream` | Chat/agent streaming |
| `/api-client/agent-codebase-retrieval` | Retrieval |
| `/api-client/check-tool-safety` | Tool safety |
| `/api-client/list-remote-tools` (via types) | Remote tools |
| `/api-client/get-credit-info` | Credits |
| `/api-client/log-agent-request-event` | Telemetry |
| `/api-client/log-agent-session-event` | Telemetry |
| `/api-client/log-tool-use-request-event` | Telemetry |
| `/api/augment-mcp/auth/mcp/` | MCP OAuth |
| `/api/augment/auth/result` | Auth result callback |

Also: `AugmentAPI`, `AugmentHttpClient`, `HttpClientProvider` / `Impl`.

---

## 6. Webview Redux / saga interface surface (Store)

Extracted action-type strings from `Store-*.js` (subset of product domains).

### 6.1 Tools lifecycle (`tools/*`) — 30+

`approveTool`, `batchUpdateToolStates`, `callTool`, `cancelAllActiveToolsRun`, `cancelToolRun`, `clearActiveTool`, `clearAllTools`, `clearSentExchanges`, `clearToolsProcessingStatus`, `hasExchangeBeenSent`, `identifyTool`, `interruptAllTools`, `interruptToolsConversation`, `markExchangeSent`, `markToolAsAwaitingUserInput`, `markToolAsCancelled`, `markToolAsCancelling`, `markToolAsCheckingSafety`, `markToolAsCompleted`, `markToolAsError`, `markToolAsNew`, `markToolAsRunnable`, `markToolAsRunning`, `markToolsProcessingError|InProgress|Success`, `removeToolUseState`, `removeToolsForRequest(s)`, `respondToAskUser`, `setToolIdentity`, `setToolTiming`, `skipToolRun`, `triggerToolCompletionHook`, `updateToolUseState`

### 6.2 Skills / rules / hooks / MCP / settings / subagents

| Namespace | Examples |
| --- | --- |
| `skills/*` | fetchSkills, createSkill, deleteSkill, setSkillMode, install dialogs |
| `rules/*` | saveRule, updateRules |
| `hooks/*` | fetchHooks, setHooks, loading |
| `settings/*` | navigateToSection, fetchSharedAppState, breadcrumbs, drawer |
| `subagents/*` | hydrate, spawn, updateIteration |
| `chat/*` | addDisplayedAnnouncement |
| sagas | `chatModeSaga`, `mcpServersSaga`, `skillsSaga`, `hooksSaga`, `toolConfigsSaga`, `agentsConfigSaga`, `deleteConversationsSaga`, history load/save/delete request/response pairs, `updateConversationTitleSaga` |

### 6.3 UI node selectors (MainPanel)

| Selector | Interface meaning |
| --- | --- |
| `$thinkingNodes` | Thinking blocks in a turn |
| `$toolUseNodes` / `$displayableToolUseNodes` | Tool cards |
| `$postToolUseMessagesByToolId` | **Post-tool assistant text keyed by tool id** |
| `$responseText` | Primary assistant text |
| `$hookMessages` / `$stopHookMessages` | Hook outputs |
| tool phases | new / runnable / running / checking safety / awaiting user / completed / error / cancelling / cancelled |

---

## 7. JVM host packages (capability domains)

| Package | Classes (approx.) | Domain |
| --- | --- | --- |
| `intellij/api` | 98 | Product API client DTOs |
| `api` | 101 | Shared API models |
| `intellij/sidecar` | 70 | Sidecar lifecycle |
| `intellij/chat` | 68 | Chat tool window / selection / editor tracking |
| `intellij/webviews` | 65 | Webview factory, messaging, state |
| `intellij/sidecar/tools` | 49 | Process tools |
| `intellij/sidecar/api/model` | 46 | Local HTTP models |
| `intellij/workspacemanagement/*` | 40+ | Index coordination, checkpoints |
| `intellij/settings` | 39 | Settings state |
| `intellij/actions` | 37 | IDE actions (settings sections, sign-in, export logs, …) |
| `intellij/auth` | 30 | Auth |
| `intellij/completion` | 22 | Inline completions |
| `intellij/grpc` + `clients` | 25 | Intake + repository allowlist gRPC |
| `intellij/vcs` | 13 | Git |
| `intellij/byok` | 13 | BYOK keys |
| `intellij/hooks` | 19 | Hooks |
| `intellij/index` | 14 | Indexing |
| … | … | metrics, sentry, notifications, onboarding, status |

**gRPC clients found:** `IntakeServiceClient`, `RepositoryAllowlistServiceClient`.

**Webview services:** `ChatMessagingService`, `SettingsMessagingService`, `RPCAdapter`, `AugmentMessagingService`.

---

## 8. Webview HTML entrypoints (UI API surface)

| HTML | Bundle | Product surface |
| --- | --- | --- |
| `main-panel.html` | MainPanel | Agent/Chat primary UI |
| `settings.html` | Settings | Full settings app |
| `acp-settings.html` | AcpSettings | ACP settings shell |
| `rules.html` | rules | Single rule Monaco editor |
| `image-gallery.html` | ImageGallery | Context canvas images |
| `mermaid-diagram.html` | mermaid-diagram | Diagram canvas |
| `index.html` | static | HMR diagnostics |

---

## 9. Mapping: original surface → CodeAgent today

| Original family | Scale | CodeAgent status |
| --- | --- | --- |
| Sidecar `/api/ide/*` + `/api/sse/*` | 13 routes | **Replaced** by JVM tools + bridge events (no sidecar IDE HTTP) |
| Sidecar launch/list/read/write/kill process tools | 5 tools | **Implemented** as approval-aware project services with stable process IDs, bounded output cursors, stdin, wait, graceful/forced termination, and project-disposal cleanup |
| Sidecar `/api/acp/*` | 9 routes | **Implemented by the ACP v1 sidecar runtime** (official SDK, stdio agents, sessions, prompt/update/cancel) |
| Cloud chat-stream + node-typed requests | large DTO graph | **Replaced** by `POST /v1/runs` + simple messages[] |
| Remote tools / MCP / OAuth / billing | large | **Typed backend tools plus managed MCP and provider OAuth; billing remains backend/account dependent** |
| Completions ACP | 3 routes + rich recency blobs | **Inline completion is implemented with bounded context, LRU/TTL cache, install listener, element manipulator, cancellation, and telemetry** |
| Webview `tools/*` state machine | 30+ actions | **Collapsed** into `ToolRun` statuses + approvals |
| `postToolUseMessagesByToolId` | first-class | **Partial**: multi assistant messages + interleaved timeline |
| History load/save/delete cloud | request/response pairs | Local thread store + MD import/export only |
| gRPC intake/allowlist | 2 clients | **Replaced by the typed HTTP/SSE backend contract and JVM bridge; proprietary binary services are intentionally not bundled** |

### CodeAgent implemented count (for contrast)

| Channel | Count |
| --- | --- |
| Backend HTTP | **5** (`/health`, `/v1/models`, `/v1/runs`, tool-results, cancel) + optional `/docs` `/openapi.json` |
| Bridge commands | **~50** |
| Bridge events | **~8** primary types |
| Local tools | **~15** |

---

## 10. What “full analysis” would still require

To turn §2–5 from **route/type inventory** into **field-level OpenAPI for every original call**:

1. Decompile / parse protobuf descriptors in `*_proto*` jars (`libpublic_api_proto`, `sidecar_rpc_protos`, …).
2. Walk `Store-*.js.map` / `MainPanel-*.js.map` sources for MessageBroker method tables.
3. Capture live traffic from a signed-in Augment install (auth-gated cloud paths).
4. Reconstruct MCP OAuth and integration tool schemas per provider.

That is multi-day reverse engineering, not a missing paragraph in CodeAgent’s 5-route backend.

---

## 11. Conclusion

- **Yes, the original plugin was analyzed** (zip extracted, 1326 classes, webviews, DefaultApi paths, Store actions, tool node model).
- **No, “5 backend endpoints” is not the original plugin’s full interface surface** — it is CodeAgent’s **chosen** gateway.
- Original order of magnitude: **~20 local sidecar HTTP routes + 100+ cloud/product DTOs + 200+ webview actions + IDE/gRPC/auth/MCP subsystems**.
- CodeAgent docs now split:
  - **Implemented:** `docs/API_INTERFACE_CATALOG.md`
  - **Original inventory:** this file
