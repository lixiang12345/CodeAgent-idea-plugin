# Changelog

## Unreleased

- Added GitHub pull-request workflow-run, job, step, temporary log-download, branch-protection, repository-ruleset, and merge-readiness reads; approval-gated Actions rerun/cancel controls; validated line-level review submission; and branch-policy gates before merge.
- Added bounded GitHub pull-request commit, review, line-comment, and head-SHA Check Run reads; approval-gated PR creation, review submission, and reviewer requests; and a separately approved merge tool with live head-SHA race protection.
- Added approval-gated GitHub issue creation, issue/pull-request comments, and state changes as a separate remote-state tool, plus bounded pull-request file and discussion-comment reads.
- Added approval-aware project process sessions with the original `terminal_id`/`input_text` contract, backward-compatible aliases, project-contained working directories, incremental bounded output, stdin, wait/list/read controls, interactive-input detection, full process-tree termination, and automatic cleanup when a project closes.
- Published the account conversation list/create/read/update/delete contract in OpenAPI, including normalized timeline schemas and optimistic `If-Match` conflict handling.
- Expanded the real GitHub adapter from search-only access to validated issue, pull-request, and bounded repository-file reads through the standard GitHub REST API.
- Added the versioned `codeagent.context.v1` Protobuf/gRPC sidecar transport with loopback bearer authentication, streaming progress events, deadline handling, and an explicit JSON Lines rollback mode.
- Split the local RPC surface into typed Context, MCP, and ACP services while retaining `ContextEngineRpc.Execute` for `0.7.18` clients; evolving MCP/ACP configuration and tool arguments remain versioned JSON bytes inside their typed envelopes.
- Added an official release build command that increments and synchronizes version metadata before full-stack verification and plugin packaging.

## 0.7.18 - 2026-07-20

- Switched the default JVM-to-ContextEngine sidecar boundary to the authenticated Protobuf/gRPC transport and bundled its generated JVM stubs and Node runtime.
- Added end-to-end health, authorization, indexing, and stream-event coverage for the new local RPC path.

## 0.7.0 - 2026-07-14

### Agent, Context, and collaboration

- Persisted local and synchronized tool cards so completed tool activity remains visible after a run ends or a cloud conversation is restored.
- Added bounded cloud conversation recovery, multi-device session restoration, and stored conversation summaries.
- Added specialized Agent policies, context-first retrieval guidance, scoped verification gates, and bounded prompt enhancement over untrusted repository and conversation context.
- Added multi-root ContextEngine watchers so external IntelliJ content roots receive automatic add, update, and delete indexing without local embedding or reranker models.
- Preserved Kotlin constructor-lambda class bodies during ContextEngine chunking to improve lexical and symbol retrieval quality.
- Added durable subagent live output, cancellation/retry state, and read-only IDE result opening.

### Extensibility and product workflow

- Added managed MCP runtime tooling, reusable slash commands, lifecycle hooks, and declarative extension manifests.
- Added persistent notifications and activity timeline telemetry for long-running Agent work.
- Added a live, redacted System Audit and bounded local support bundle without exposing credentials or tool payloads.
- Lazy-loaded Mermaid so the main JCEF bundle stays smaller while retaining diagram tool support.

### Delivery

- Added backend and ContextEngine test coverage, production dependency audits, plugin ZIP artifacts, signed Marketplace publication, and tag/version validation to GitHub Actions.
- Verified the 0.7.0 plugin with JetBrains Plugin Verifier against IntelliJ IDEA Community 2025.2.6.2.

### IDEA plugin

- Matched the extracted Augment 0.482.3 message anatomy: assistant content renders without an avatar/name row, live generation stays in the transcript, and tool-resolution status follows active tool output.
- Made fresh local installs target the Docker backend on `127.0.0.1:8788` automatically while preserving Advanced Settings overrides for direct or hosted deployments.

## 0.6.0 - 2026-07-13

### Models and backend

- Added a fixed five-model allowlist with native OpenAI Responses, Anthropic Messages, and xAI Responses protocol routing.
- Added provider-specific streaming text, function-call argument, tool-result, and error normalization behind the backend Agent loop.
- Added authenticated model discovery and per-run model routing while keeping model credentials exclusively in backend environment configuration.
- Retained tested native Gemini and legacy OpenAI-compatible adapters without exposing either in the current model allowlist.

### IDEA plugin

- Added the prototype-aligned model picker backed by the deployed service's model registry.
- Persisted model selection independently for each conversation and included it in remote Agent requests.
- Added provider and default-model health metadata plus failure states for model discovery.

### Verification and documentation

- Verified real streamed chat through all five configured gateway models and a complete tool-result continuation loop through each enabled provider protocol.
- Documented the analyzed Augment request-node organization, CodeAgent's current request path, and the local ContextEngine execution boundary.
- Kept typed image/file and richer IDE-state request nodes as an explicit prototype compatibility gap.

## 0.5.0 - 2026-07-13

### Product and architecture

- Rebuilt the product as an IntelliJ IDEA plugin using the v9 prototype as the visual and workflow acceptance baseline.
- Split the Agent runtime into the independently deployable `backend/` service. The backend owns prompts, model credentials, bounded Agent orchestration, tool sequencing, and SSE streaming.
- Kept project access, approvals, editor/Diff, diagnostics, terminal, Git, and the reusable ContextEngine sidecar inside the IDEA capability boundary.
- Added authenticated backend runs, tool-result continuation, cancellation, health/protocol checks, Docker packaging, and server-owned prompt composition.

### IDEA plugin

- Added the 420 px prototype-aligned Agent/Chat/Ask panel, thread drawer, dense tool cards, approvals, attachments, Skills, message queue, change review, and settings workspace.
- Added persistent threads with pin, confirmed delete, and Markdown import/export.
- Added persistent task management with filters, import/export, run-one/run-all, and Agent task tools.
- Added real Git status, stage/unstage, native Diff, commit-message drafting, confirmed commit, and Git history retrieval.
- Added Rules & Guidelines with persisted description and Always/Manual/Agent triggers.
- Added Image Canvas for bounded project-local raster previews, open, and mention actions.
- Added strict Mermaid rendering with diagram/code modes, zoom, fit, failure state, and IDEA editor opening.
- Added local ContextEngine indexing/retrieval, diagnostics, file search/read/write/edit, terminal execution, and guarded Diff/revert flows.

### Deliberate limits

- MCP, Subagents, GitHub/Linear/Notion/Jira/Confluence/Glean/Supabase, account, and billing remain explicitly unavailable until real separately deployed providers are connected.
- The prompt enhancer remains unavailable; system prompts stay backend-owned.
- See `docs/PROTOTYPE_PARITY.md` for the release status of every prototype surface.
