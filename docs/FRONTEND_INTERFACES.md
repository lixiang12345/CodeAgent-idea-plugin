# Frontend interfaces

Contract between the CodeAgent Webview, IDEA bridge, and deployed Agent backend.  

Full request/response tables (including original Augment interface map): [`docs/API_INTERFACE_CATALOG.md`](API_INTERFACE_CATALOG.md).
Product UI baseline: `docs/FINAL_PROTOTYPE_CONTRACT.md` and `prototypes/codeagent-final.html`.

## 1. Webview ↔ JVM bridge

Transport: `window.codeAgentPost(json)` / `window.CodeAgent.receive(json)`  
Envelope version: `1`

### Commands (UI → JVM)

| type | payload | Behavior |
| --- | --- | --- |
| `bootstrap` | — | Emit full snapshot + health/models refresh |
| `sendMessage` | `{ text, mode }` | Start agent run or fail if invalid |
| `queueMessage` | `{ text, mode }` | Queue while busy |
| `removeQueuedMessage` | `{ messageId }` | Drop queued item |
| `cancelRun` | — | Abort active run |
| `setMode` | `{ mode }` | `agent` \| `chat` \| `ask` |
| `selectModel` | `{ modelId }` | Persist per-thread model |
| `newThread` | `{ mode? }` | Create + activate thread |
| `selectThread` | `{ threadId }` | Switch thread |
| `editAndResendMessage` | `{ messageId, text }` | Rewind from a user message and rerun it with edited or original content |
| `toggleThreadPinned` | `{ threadId }` | Pin ordering |
| `deleteThread` | `{ threadId }` | Confirmed delete |
| `deleteThreads` | `{ threadIds }` | Confirmed time-group delete |
| `renameThread` | `{ threadId, title }` | Rename active/history title |
| `deleteRule` | `{ ruleId }` | Delete a workspace-owned Markdown rule and its metadata |
| `saveGuidelines` | `{ content }` | Persist bounded workspace Markdown used by Agent runs |
| `copyThread` | — | Copy Markdown to clipboard |
| `exportThread` | — | Save Markdown via folder chooser |
| `importThread` | — | Import Markdown thread |
| `continueTasksInNewThread` | — | Clone the active task list and thread customization into a fresh conversation |
| `clearConversationSummary` | `{ threadId }` | Clear one stored summary without deleting transcript history |
| `pickContext` / `removeContext` | path/id | Attachments |
| `toggleSkill` / `toggleRule` | selection | Composer/rules selection |
| `saveRule` / `refreshCustomization` | rule fields | Rules/Skills disk |
| `resolveApproval` | `{ toolId, approved }` | Tool approval |
| `openDiff` / `revertChange` / `reviewChanges` / `keepChanges` / `discardChanges` | tool ids | Agent edits |
| `createCheckpoint` / `listCheckpoints` / `restoreCheckpoint` | checkpoint fields | Local edit checkpoints |
| `enhancePrompt` | `{ text, mode }` | Backend prompt rewrite → `promptEnhanced` |
| `addTask` / `deleteTask` / `setTaskState` / `runTask` / `runAllTasks` / `clearTasks` / `clearCompletedTasks` / `exportTasks` / `importTasks` | task fields | Tasklist |
| `refreshGit` / `stageGit` / `unstageGit` / `openGitDiff` / `suggestCommitMessage` / `commitGit` | git fields | Git overlay |
| `refreshImageCanvas` / `browseImageDirectory` / `openImage` / `attachImage` | image fields | Context Canvas |
| `openMermaidEditor` | `{ title, code }` | Open `.mmd` in editor |
| `openTerminal` | — | Focus IDE Terminal |

### Events (JVM → UI)

| type | payload |
| --- | --- |
| `snapshot` | `AppSnapshot` |
| `stateChanged` | partial snapshot fields |
| `messageDelta` | `{ id, delta, turnIndex }` |
| `error` / `notice` | `{ message }` |
| `promptEnhanced` | `{ text }` |
| `checkpoints` | `CheckpointSummary[]` |
| `gitSnapshot` | Git status |
| `gitCommitSuggested` | `{ message }` |
| `imageCanvas` | gallery snapshot |

## 2. AppSnapshot (UI model)

Core fields: `projectName`, `mode`, `runState`, `messages[]`, `tools[]`, `threads[]`, `tasks[]`, `messageQueue[]`, `attachments[]`, `settings`, `context`, `backendHealth`, `models`, `customization`. Assistant messages and tool runs include persisted run/turn identity where available. Tool records carry `createdAt` and `updatedAt` so restored cards retain start time and duration.

Tool card statuses: `running` \| `approval` \| `completed` \| `failed` \| `rejected`.

User Experience settings persist chat zoom, timeline timestamps, run telemetry, IDE-native notifications, and in-panel notice auto-dismiss behavior. Native notification sound and presentation are delegated to JetBrains settings.

## 3. Deployed backend HTTP/SSE

Base URL from settings (fresh-install default `http://127.0.0.1:8788` for the local Docker deployment).
Auth: `Authorization: Bearer <CODEAGENT_AUTH_TOKEN>` on protected routes.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/health` | Unauthenticated health + `protocolVersion` |
| `GET` | `/v1/models` | Allowlisted models |
| `POST` | `/v1/enhance` | Rewrite a prompt via the model gateway |
| `POST` | `/v1/runs` | Opens SSE stream for one run |
| `POST` | `/v1/runs/{id}/tool-results` | Continue after local tool execution |
| `DELETE` | `/v1/runs/{id}` | Cancel |

### SSE event types

`run.started`, `turn.started`, `message.delta`, `assistant.completed`, `tool.request`, `tool.completed`, `run.completed`, `run.error`, plus `: heartbeat` comments. Turn-scoped text and tool events include `turnIndex`.

### Run request (conceptual)

- `model`
- conversation history
- mode
- advertised tool schemas (JVM allowlist)
- selected rules/skills/guidance (bounded)
- attachment path summaries

System prompts and provider credentials **never** leave the backend process.

## 4. UI surfaces vs connection

| Surface | Bridge/backend |
| --- | --- |
| Chat / composer / tools cards | bridge + backend runs |
| Threads rename/export/import | bridge only |
| Tasks / Git / Image / Mermaid / Rules / Skills | bridge / local IDE |
| Tools catalog / Icon gallery | pure UI (seed prompt / copy name) |
| Feedback | local notice only |
| MCP | typed account configuration CRUD plus local gateway state, lifecycle controls, discovered tools, latency, process ID, errors, and approval risk |
| Commands / Hooks / Agents | typed account configuration CRUD plus their bounded command, lifecycle-hook, and Agent-profile runtimes |
| Plugins | typed account configuration CRUD plus device-local install, validate, update, uninstall, runtime state, permission display, namespaced command and prompt contributions, and read-only rule/skill context |
| Account / Subscription | real backend identity, session state, and metered usage; no simulated billing actions |
| API Keys | Inline per-provider Add/Update forms for OpenAI, Anthropic, and AWS Bedrock; Enter saves, Escape cancels, validation runs before Password Safe writes, and snapshots stay redacted |
| Settings Home | Live ContextEngine file/chunk/root/sync metrics with separate status refresh and full rebuild commands, plus direct customization navigation |
| Feature Flags / Beta | read-only live capability and maturity reports |
| Unconfigured cloud tools | explicit **Not connected** states |

Integration readiness is evaluated by the backend-side
`scripts/evaluate-integration-readiness.mjs` gate. The Webview only receives
the resulting tool availability and reason; credential values and readiness
reports never cross into rendered UI state.

## 5. Final prototype

Open:

```bash
open prototypes/codeagent-final.html
```

Uses v9 interaction matrix rebranded to CodeAgent, with robot-only product icon override.
