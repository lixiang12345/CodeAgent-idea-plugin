# Product definition

## Is the original product an AI agent?

Yes at the interaction level, but that description is incomplete. The analyzed Augment plugin presents Agent as its main coding workflow while packaging it inside a broader AI coding platform. Its product surface also includes chat, rules, skills, hooks, MCP, integrations, account/subscription services, analytics, and a proprietary Context Engine.

The more precise positioning is: **an AI coding platform for large codebases, led by an autonomous coding agent and differentiated by repository context**. Agent is the primary experience; Context Engine and the surrounding service ecosystem are the product moat.

## CodeAgent position

CodeAgent is positioned as **an IDE-native coding-agent product with a separately deployed Agent backend, a local Context Engine, and approval-controlled IDE capabilities**. Its interaction and information architecture follow the analyzed plugin prototype rather than a generic web chat layout.

| Dimension | Analyzed Augment plugin | CodeAgent target |
| --- | --- | --- |
| Primary workflow | Agent inside a broad coding platform | Focused project-level coding agent |
| Context | Proprietary Context Engine and cloud services | Reused open ContextEngine, local SQLite index |
| Runtime | JCEF + JVM + Node sidecar + remote product services | JCEF + JVM capability gateway + local ContextEngine + deployed Agent backend |
| Tools | Broad IDE, integration, MCP, and platform surface | Project files, search, editor, terminal, retrieval |
| Trust model | Product-managed policies and services | Read-only Ask mode and explicit mutation approvals |
| Data boundary | Product cloud plus local IDE processes | Local index and IDE tools; selected run context goes to the configured backend |

## Current implemented core

- Persistent project tasks with search and switching.
- ContextEngine indexing, health, progress, retrieval, and text search.
- Agent, Chat, and Ask modes with a backend-owned bounded multi-turn tool loop.
- Project-local file reads, writes, replacements, search, editor open, and terminal execution.
- Approval cards for file mutations and commands, plus run cancellation.
- Token-level streamed assistant responses and streamed tool-call assembly.
- IntelliJ-native Diff review and guarded per-edit revert for file tools.
- Project file attachments, backend/runtime settings, and Password Safe backend credentials.
- JCEF interface with a Swing fallback when JCEF is unavailable.
- Backend-owned prompt composition with root `AGENTS.md` workspace guidance.
- Always-on repository Rules and explicitly selected, per-task Skills validated by the JVM and composed by the backend.

## Prototype delivery boundary

The required surfaces are tracked in [PROTOTYPE_PARITY.md](PROTOTYPE_PARITY.md). A cloud integration may be shown as unavailable configuration, but no interaction may claim success without a connected implementation. Automatic rollback of arbitrary terminal side effects remains unsupported because the changed-file set cannot be inferred safely.
