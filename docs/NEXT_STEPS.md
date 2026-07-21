# Next Steps and Release Gates

This document is the single backlog for work that is still required after the
GitHub pull-request workflow release, followed by completed release-gate
evidence. It does not relabel implemented surfaces as pending, and it does not
treat configuration-dependent integrations as connected before credentials and
live acceptance evidence exist.

## Priority 0: Live GitHub Acceptance

**Status:** blocked on a dedicated test repository and fine-grained
`GITHUB_TOKEN`.

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
`GITHUB_TOKEN` is configured. Unit coverage alone is not a substitute for this
acceptance gate.

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

## Priority 3: Integration Operations Readiness

**Status:** blocked until each provider supplies a test tenant and scoped
credentials.

For GitHub, Linear, Notion, Jira, Confluence, Glean, Supabase, MCP servers, and
model providers, maintain one isolated test configuration per provider. Verify
that missing credentials remain explicit, successful operations are correctly
reported, failures preserve provider status without leaking secrets, and every
remote mutation is approval-gated.

## Completed Gates

### Long-Conversation Product Alignment

**Status:** implemented on 2026-07-22.

Conversation items are grouped into request boundaries using the public
`runId`, `turnIndex`, and timeline sequence fields. The tool window exposes
previous/next request navigation, a current request count, and a jump-to-latest
control that appears only while the reader is away from the bottom. Incoming
tool, approval, queue, and response updates auto-follow only when the reader was
already at the latest content. The Threads drawer derives running, approval,
and failed indicators for the active thread, while the generation menu exposes
the current tool batch, stop, and jump-to-latest actions.

The Playwright suite covers a 12-request conversation, queued work, approval
updates while reading history, request navigation, new-content scroll
preservation, and responsive behavior at 360, 420, and 640 px. A canonical
420 px visual reference records the long-conversation navigation state.

### Browser Product Alignment

**Status:** implemented on 2026-07-22.

The Playwright suite runs the deterministic frontend host at 360, 420, and 640
px. It checks viewport integrity and committed visual references for the main
Agent workspace, Threads, Agent Edits, Tasks, MCP Settings, and explicit
mutation approval. CI and release verification upload the HTML report, failure
screenshots, video, and trace. The suite is informed by the locally supplied
Augment 0.482.3 UI source-map inventory but contains only CodeAgent-owned tests
and fixtures. Native smoke in each additional IDE remains required above.

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
