# Prototype parity contract

`prototypes/augment-v9-tools-native.html` is the product acceptance baseline. Earlier prototypes and the extracted `0.482.3` plugin are supporting evidence only. CodeAgent keeps its own name, deployment configuration, and security policy, but reproduces the prototype's page structure, icon vocabulary, information density, states, and workflows.

## Deployment boundary

```mermaid
flowchart LR
    UI["Svelte Webview\nprototype-aligned pages"]
    JVM["IDEA plugin JVM\nIDE capabilities and approvals"]
    Context["Local ContextEngine\nindex and retrieval"]
    Backend["Separately deployed backend\nagent loop, prompts, model, sessions"]
    Model["Model provider"]

    UI <--> JVM
    JVM <--> Context
    JVM <--> Backend
    Backend <--> Model
```

The deployed backend owns prompts, model credentials, the bounded agent loop, streamed assistant output, task orchestration, and tool-call sequencing. The plugin owns project files, editor, diagnostics, terminal, Git, ContextEngine, user approval, and canonical path enforcement. The Webview owns rendering and user interaction only.

## Page and state acceptance

| Surface | Required prototype behavior |
| --- | --- |
| Main panel | Native tool-window header, active-thread header, context/repository strip, dense transcript, streamed thinking/answer states, bottom composer |
| Threads | Overlay drawer, search, Agent/Chat/Ask tags, create/select, pin/delete/export/import entry points |
| Composer | Agent/Chat/Ask selector, attachments, mentions, commands, Skills, model/auto controls, queue/stop/send states |
| Tools | Prototype card anatomy, expandable details, phase/status, approvals, file paths, Diff/open/revert, terminal actions |
| Agent edits | Changed-file summary, review, keep/discard, checkpoints, per-file Diff and undo |
| Tasks | Task tree, add/view/update/reorganize states, run/clear/import/export controls |
| Subagents | Synchronous and asynchronous run states, approval, stop, output navigation |
| Git | Unstaged and reviewed groups, stage/unstage, generated commit message, commit action |
| Settings | Home, Services, MCP, Rules, API keys, Commands, Skills, Hooks, Agents, Plugins, UX, feature flags, Beta, account, subscription |
| Rules editor | Description, Always/Manual/Agent trigger, Markdown editor, save/open/back actions |
| Image Canvas | Directory selection, refresh/settings, gallery, mention/open actions and empty states |
| Mermaid | Diagram/code modes, zoom, fit, open-in-tab and render failure state |
| IDE integration | Tool window, actions, status/completion states, file/editor/terminal/Git navigation |

## Current implementation status

This table is the release gate. `Partial` means the visible surface exists but at least one prototype workflow is still intentionally unavailable.

| Surface | Status | Real behavior in the current build |
| --- | --- | --- |
| Main panel | Implemented | 420 px IDEA tool window, interleaved user/assistant/tool timeline, context strip, tool cards, approvals, composer, stop/send states |
| Threads | Implemented | Create, select, search, mode tags, pin ordering, confirmed delete, and Markdown import/export work |
| Composer | Implemented | Modes, attachments, Skills, model picker, queue/stop/send, slash menu, @ mention menu, Auto, and real prompt enhancement via backend `/v1/enhance` |
| Tools | Partial | Local tools remain IDEA-owned; backend-owned discovery/execution connects configured cloud adapters and subagents, while the local MCP gateway contributes dynamically discovered, namespaced tools under the same approval policy |
| Agent edits | Implemented | Native Diff, undo, keep/discard, Agent Edits overlay, and local checkpoints with restore |
| Tasks | Implemented | Persistent per-thread tasks, filtering, add/delete/state, clear, Markdown import/export, run-one/run-all, and Agent task tools |
| Git | Implemented | Real branch/index/worktree status, stage/unstage, native Diff, local message draft, confirmation, and commit |
| Rules editor | Implemented | Repository Markdown, persisted description and trigger metadata, save, and manual per-thread selection work |
| Image Canvas | Implemented | Project-contained directory selection, bounded raster gallery, settings, refresh, open, mention, and empty/error states |
| Mermaid | Implemented | Strict rendering, diagram/code, zoom, fit, error states, and opening source in an IDEA editor tab work |
| Settings | Partial | Backend health, account, subscription usage, ContextEngine, Rules, Skills, persisted chat zoom/timestamps/run telemetry/native notifications, Commands, Hooks, Agent profiles, declarative plugin lifecycle, and MCP lifecycle controls are real; feature and Beta pages report live capability state instead of simulated toggles |
| Tools catalog / Icon gallery / Feedback | Implemented | UI overlays for insert-tool seeding, icon name copy, and local feedback notice |
| Cloud integrations | Conditional | Search/read adapters are advertised only when their backend environment is configured; provider errors and missing credentials remain explicit failures |
| Subagents | Implemented | Synchronous `subagent` plus durable asynchronous jobs support persisted partial output, polling progress, cancellation, retry, composer handoff, and read-only IDE result navigation |
| MCP | Implemented | Enabled stdio, Streamable HTTP, and legacy SSE definitions are reconciled by a local managed gateway with health checks, bounded reconnects, explicit start/stop/restart/test controls, tool-list refresh notifications, environment allowlisting, bearer-token injection, namespaced Agent tools, and approval-aware risk defaults. Provider OAuth remains a separate future adapter |
| Plugins | Implemented | Account-synchronized plugin definitions drive explicit per-device install, validate, update, and uninstall actions for bounded declarative manifests. Identity, exact version, SHA-256 integrity, capabilities, and command schemas are verified; granted command contributions become namespaced slash commands without loading arbitrary code |

## Tool catalog

The prototype defines 31 tool presentations. A card is shown as functional only when its backend or IDE capability is connected:

`context-engine`, `conversation-retrieval`, `str-replace`, `view`, `read-file`, `save-file`, `remove-files`, `apply-patch`, `grep`, `shell`, `web-fetch`, `web`, `open-browser`, `diagnostics`, `git-commit`, `mermaid`, `add-tasks`, `view-tasks`, `update-tasks`, `reorg-tasks`, `subagent`, `async-subagent`, `ask-user`, `github`, `linear`, `notion`, `jira`, `confluence`, `glean`, `supabase`, `mcp`.

Backend tools are discovered through authenticated `GET /v1/tools`. The JVM advertises only entries with `available=true` and proxies execution through `POST /v1/tools/{toolName}`. Required environment is documented in `backend/.env.example`; unavailable entries include a concrete reason and cannot be executed.

## Resource contract
- Use the icon names and placement from the v9 registry (`prototypes/assets/icons-registry.js`), shipped as `frontend/src/lib/icons.ts` and rendered through `frontend/src/lib/Icon.svelte`.
- Reuse the provided prototype status, service, and product image resources when licensing permits redistribution.
- Use prototype design tokens: compact 10/12/14 px type, JetBrains Mono for tool data, neutral IntelliJ surfaces (`--bg/#1e1e1e`, `--panel/#252526`, `--chrome/#3c3f41`, accent `#3574f0`), and 4-8 px radii.
- Validate at a 420 px tool-window viewport first (`--tw: 420px`), then 360 px and wider docked widths.
- Page chrome mirrors v9: tool-window header, chat header with context meter + zoom, repository chip strip, composer action bar (mode/model/canvas/@/slash/attach/enhance/auto/send), threads drawer, and overlay pages for Tasks / Git Changes / Context Canvas / Settings.

## No-fake rule

Unconnected cloud integrations may appear only as explicitly unavailable configuration rows. Buttons, approvals, tool cards, and success states must not claim an operation completed unless a real backend or IDEA capability performed it.
