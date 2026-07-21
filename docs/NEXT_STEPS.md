# Next Steps and Release Gates

This document is the single backlog for work that is still required after the
GitHub pull-request workflow release. It does not relabel implemented
surfaces as pending, and it does not treat configuration-dependent integrations
as connected before credentials and live acceptance evidence exist.

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

## Priority 2: Repository-Specific Retrieval Evaluation

**Status:** ready.

Create a small, versioned golden-query suite for this repository. Track path
accuracy, MRR, recall, latency, and incremental-index timing for architecture,
symbol, Git-history, and multi-root queries. A release should compare its
results against the previous baseline and explain meaningful regressions.

This is an evaluation and quality gate. It must not introduce a hidden local
embedding or reranker model; semantic retrieval remains opt-in and
operator-hosted.

## Priority 3: Integration Operations Readiness

**Status:** blocked until each provider supplies a test tenant and scoped
credentials.

For GitHub, Linear, Notion, Jira, Confluence, Glean, Supabase, MCP servers, and
model providers, maintain one isolated test configuration per provider. Verify
that missing credentials remain explicit, successful operations are correctly
reported, failures preserve provider status without leaking secrets, and every
remote mutation is approval-gated.

## Explicit Non-Goals

The private Augment cloud protocol, generated protobuf definitions,
`classic-level.node`, and proprietary service dependencies remain intentionally
out of scope. CodeAgent aligns its public product behavior through its own
versioned Protobuf/gRPC sidecar boundary and HTTP/SSE backend; it does not claim
wire compatibility with private Augment services.
