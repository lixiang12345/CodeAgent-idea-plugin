# Next Steps and Release Gates

This document is the single backlog for work that is still required after the
GitHub pull-request workflow release, followed by completed release-gate
evidence. It does not relabel implemented surfaces as pending, and it does not
treat configuration-dependent integrations as connected before credentials and
live acceptance evidence exist.

## Priority 0: Live GitHub Acceptance

**Status:** completed on 2026-07-22 against `lixiang12345/test` with a
repository-scoped fine-grained personal access token.

Use a non-production GitHub repository with a protected base branch. Grant only
the permissions exercised by the test: Metadata, Pull requests, Checks,
Contents, Actions, and Administration read; grant Pull requests and Actions
write only when exercising the approval-gated mutations.

Acceptance evidence must cover:

1. Read a pull request, reviews, line comments, Check Runs, workflow runs,
   jobs, failed steps, branch protection, and repository rulesets.
2. Submit a review containing a line-level comment anchored to the tested head
   commit.
3. Rerun a workflow, rerun failed jobs, rerun one job, cancel, and
   force-cancel only disposable workflow runs.
4. Verify that stale SHA, draft, requested changes, insufficient approvals,
   missing checks, failed checks, and a dirty merge state all block merge before
   a merge request is sent.
5. Record the exact token permissions, GitHub API version, test PR URL, and
   anonymized request outcomes in the release evidence without committing a
   token or temporary log URL.

The deployed backend intentionally reports GitHub tooling as unavailable until
`GITHUB_TOKEN` is configured. The acceptance credential is stored separately in
the macOS Keychain service `CodeAgent-GitHub-Acceptance`; deployments must
inject it into the backend environment without committing it.

The live run covered all read, review, Actions, and merge-blocker behaviors
listed above, including a real line-level review, requested-changes review,
failed Check Run, cancellation, force-cancellation, and dirty merge state. See
`docs/GITHUB_LIVE_ACCEPTANCE.md` and
`evaluation/github-live-acceptance.json`. A final rerun used a 30-day
fine-grained token restricted to `lixiang12345/test` with Actions and Pull
requests read/write plus Administration, Contents, and Metadata read-only. The
production adapter also read Check Runs successfully; GitHub did not expose a
separate Checks selector in the fine-grained token form. The evidence gate is
now `pass`.

## Priority 1: Broaden Deployment Verification

**Status:** ready when the relevant IDE installations are available.

The current release is verified against IntelliJ IDEA Community and PyCharm.
Extend the Plugin Verifier matrix to installed WebStorm, CLion, GoLand, PhpStorm,
and Rider builds that the product claims to support. Each product result must be
recorded with the IDE build number and the verifier report path.

Run the 420 px tool-window smoke workflow in each added IDE: create a thread,
stream an Agent run, approve a local edit, inspect a Diff, use inline
completion, open Settings, and recover a persisted conversation. Resolve API
or layout incompatibilities before listing that product as verified.

Run `node scripts/verify-ides.mjs` after installing an additional product. The
runner discovers standard macOS and JetBrains Toolbox installations and writes
the exact product build and Plugin Verifier report path to
`build/reports/jetbrains-verifier.json`. This automates compatibility evidence;
the interactive smoke workflow remains a separate required acceptance step.

## Priority 2: Advanced Settings and Rules Alignment

**Status:** next implementation stage.

Audit the remaining Settings pages against the local Augment source-map
inventory, replace generic capability summaries where the original exposes a
real management workflow, and raise the Rules editor from a plain textarea to
an IDE-grade Markdown editing surface with validation and unsaved-change
protection. Keep provider-dependent controls explicitly unavailable until a
backend contract exists, and add responsive interaction evidence for each
newly completed workflow.

## Priority 3: Integration Operations Readiness

**Status:** blocked until each provider supplies a test tenant and scoped
credentials.

For GitHub, Linear, Notion, Jira, Confluence, Glean, Supabase, MCP servers, and
model providers, maintain one isolated test configuration per provider. Verify
that missing credentials remain explicit, successful operations are correctly
reported, failures preserve provider status without leaking secrets, and every
remote mutation is approval-gated.

## Completed Gates

### Threads and Task Continuation Alignment

**Status:** implemented on 2026-07-22.

The Threads drawer groups pinned, today, yesterday, recent, and older history;
retains search, mode, activity, and unread indicators; and exposes a compact
per-row menu for rename, pin/unpin, and confirmed delete. Non-pinned time groups
support a separate two-step bulk clear action. Bulk deletion preserves cloud
tombstones, cancels an active deleted run, and restores a valid active thread.

The original plugin source-map evidence places `Continue in New Chat` in Task
List actions, where it clones the task tree before creating a conversation. The
CodeAgent implementation now follows that behavior: it creates a new thread
with fresh task identities and the current mode, model, Agent profile, Skills,
and Rules, while preserving the source thread and omitting its messages, tools,
summary, attachments, queue, and run state. JVM and 360/420/640 px Playwright
coverage verify the persistence and interaction boundaries; a canonical 420 px
reference records advanced thread management.

### Rules Editor Lifecycle Alignment

**Status:** implemented on 2026-07-22.

Rule creation and editing validates Markdown filenames, non-empty content, and
description length before crossing the bridge. Back, Cancel, and Settings
navigation preserve dirty editor state behind an explicit Keep editing /
Discard confirmation; successful saves reset the editor baseline. The 420 px
Playwright workflow covers invalid input and both discard branches.

### Composer and Message Lifecycle Alignment

**Status:** implemented on 2026-07-22.

Idle user messages expose Copy, Edit, and Resend actions. Edit uses an inline
textarea with Cancel, Apply & Resend, Escape, and Command/Ctrl+Enter behavior.
Applying or resending reuses the original message identity, removes the target
request and all later transcript/tool history, clears the stale conversation
summary, clamps the persisted read cursor, and starts a real Agent run with the
thread's current mode and customization selections. The rewind is atomic and
fails closed while later revertible file changes or queued messages remain.
The main Composer now grows with multiline input up to its bounded maximum.

JVM tests cover successful rewind and atomic rejection. Playwright covers
Cancel, edit-and-resend, later-history removal, original-content resend,
adaptive Composer height, and viewport integrity at 360, 420, and 640 px. A
canonical 420 px reference records the inline edit state.

### Long-Conversation Product Alignment

**Status:** implemented on 2026-07-22.

Conversation items are grouped into request boundaries using the public
`runId`, `turnIndex`, and timeline sequence fields. The tool window exposes
previous/next request navigation, a current request count, and a jump-to-latest
control that appears only while the reader is away from the bottom. Incoming
tool, approval, queue, and response updates auto-follow only when the reader was
already at the latest content. The Threads drawer derives running, approval,
and failed indicators for the active thread, plus unread reply counts backed by
a persisted per-thread timeline cursor. Reaching the latest content marks only
the observed assistant replies as read; cloud-history replacement preserves and
clamps the local cursor. The generation menu exposes the current tool batch,
stop, and jump-to-latest actions.

The Playwright suite covers a 12-request conversation, queued work, approval
updates while reading history, request navigation, new-content scroll
preservation, and responsive behavior at 360, 420, and 640 px. A canonical
420 px visual reference records the long-conversation navigation state.

### Browser Product Alignment

**Status:** implemented on 2026-07-22.

The Playwright suite runs the deterministic frontend host at 360, 420, and 640
px. It checks viewport integrity and committed visual references for the main
Agent workspace, Threads with unread state, long-conversation navigation, Agent
Edits, Tasks, MCP Settings, explicit mutation approval, and specialized
file/search/Web/integration/task/subagent/diagnostics result cards. CI and
release verification upload the HTML report, failure screenshots, video, and
trace. The suite is informed by the locally supplied
Augment 0.482.3 UI source-map inventory but contains only CodeAgent-owned tests
and fixtures. Native smoke in each additional IDE remains required above.

### Installed IDE Verifier Evidence

**Status:** refreshed on 2026-07-22.

The latest `node scripts/verify-ides.mjs` run completed successfully against
the targeted IntelliJ IDEA Community `IC-252.28539.54` platform and the
installed PyCharm `PY-261.24374.152` build. Both Plugin Verifier reports are
`Compatible`; the only findings are the 12 expected Inline Completion
experimental-API usages. The machine has no WebStorm, CLion, GoLand, PhpStorm,
or Rider installation, so those products remain unverified. The machine-readable
evidence is written to `build/reports/jetbrains-verifier.json` and the per-IDE
HTML reports under `build/reports/pluginVerifier/`.

### Machine-Readable Prototype Parity

**Status:** implemented on 2026-07-22.

`evaluation/parity-codeagent.json` is the versioned companion to the canonical
implementation table in `docs/PROTOTYPE_PARITY.md`. The evaluator checks IDEA
registrations, Settings sections, tool catalogs, OpenAPI paths, bridge-command
coverage, surface evidence, and document ownership, then writes
`build/reports/prototype-parity.json`. CI and release verification upload the
report. This structural gate does not replace the outstanding visual smoke or
live acceptance work above.

### Repository-Specific Retrieval Evaluation

**Status:** implemented on 2026-07-21.

The versioned `evaluation/context-codeagent.json` suite covers architecture,
symbol, Git-history, and multi-root queries. `scripts/evaluate-retrieval.mjs`
tracks Top-1 path accuracy, MRR, Recall@K, symbol hits, query latency, full
indexing, no-change indexing, and a one-file incremental add/remove cycle. CI,
release verification, and the local release builder compare every run with the
committed baseline and fail on configured quality or latency regressions.

The runner creates a fresh temporary index and explicitly disables embeddings
and neural reranking. See `docs/RETRIEVAL_EVALUATION.md` for the baseline review
and regression-explanation procedure.

## Explicit Non-Goals

The private Augment cloud protocol, generated protobuf definitions,
`classic-level.node`, and proprietary service dependencies remain intentionally
out of scope. CodeAgent aligns its public product behavior through its own
versioned Protobuf/gRPC sidecar boundary and HTTP/SSE backend; it does not claim
wire compatibility with private Augment services.
