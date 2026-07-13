# Changelog

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
- The prompt enhancer and client-side model selector remain unavailable; model selection and system prompts stay backend-owned.
- See `docs/PROTOTYPE_PARITY.md` for the release status of every prototype surface.
