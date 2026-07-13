# Product definition

## Is the original product an AI agent?

Yes at the interaction level, but that description is incomplete. The analyzed Augment plugin presents Agent as its main coding workflow while packaging it inside a broader AI coding platform. Its product surface also includes chat, rules, skills, hooks, MCP, integrations, account/subscription services, analytics, and a proprietary Context Engine.

The more precise positioning is: **an AI coding platform for large codebases, led by an autonomous coding agent and differentiated by repository context**. Agent is the primary experience; Context Engine and the surrounding service ecosystem are the product moat.

## CodeAgent position

CodeAgent deliberately starts with a smaller promise: **an IDE-native, local-first coding agent whose actions are inspectable and approval-controlled**.

| Dimension | Analyzed Augment plugin | CodeAgent 0.1 |
| --- | --- | --- |
| Primary workflow | Agent inside a broad coding platform | Focused project-level coding agent |
| Context | Proprietary Context Engine and cloud services | Reused open ContextEngine, local SQLite index |
| Runtime | JCEF + JVM + Node sidecar + remote product services | JCEF + JVM + local Node sidecar + chosen model endpoint |
| Tools | Broad IDE, integration, MCP, and platform surface | Project files, search, editor, terminal, retrieval |
| Trust model | Product-managed policies and services | Read-only Ask mode and explicit mutation approvals |
| Data boundary | Product cloud plus local IDE processes | Local index; model traffic only to the configured endpoint |

## Version 0.2 scope

- Persistent project tasks with search and switching.
- ContextEngine indexing, health, progress, retrieval, and text search.
- Agent and Ask modes with a bounded multi-turn tool loop.
- Project-local file reads, writes, replacements, search, editor open, and terminal execution.
- Approval cards for file mutations and commands, plus run cancellation.
- Project file attachments, model/runtime settings, and Password Safe credentials.
- JCEF interface with a Swing fallback when JCEF is unavailable.
- JVM-owned, versioned prompt composition with root `AGENTS.md` workspace guidance.

## Explicit boundaries

Version 0.2 does not provide team accounts, cloud synchronization, billing, analytics, remote integrations, MCP configuration UI, semantic embeddings, or token-level response streaming. These are future product decisions, not hidden dependencies of the local agent loop.
