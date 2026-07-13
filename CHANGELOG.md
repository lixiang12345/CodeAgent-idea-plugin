# Changelog

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
