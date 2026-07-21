import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import {
  createIntegrationToolRegistryFromEnv,
} from "../src/integration-tools.mjs";
import { createCodeAgentServer } from "../src/server.mjs";

const PR_HEAD_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MERGE_COMMIT_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

test("reports missing integration credentials and rejects execution explicitly", async () => {
  const registry = createIntegrationToolRegistryFromEnv({}, async () => {
    throw new Error("fetch must not run for unavailable tools");
  }, {});
  const tools = registry.list();

  assert.equal(tools.find((tool) => tool.name === "web_search").available, false);
  assert.equal(tools.find((tool) => tool.name === "web_search").risk, "read_only");
  assert.match(tools.find((tool) => tool.name === "github_search").unavailableReason, /GITHUB_TOKEN/);
  assert.equal(tools.find((tool) => tool.name === "github_manage").risk, "mutating");
  assert.match(tools.find((tool) => tool.name === "github_manage").unavailableReason, /write access/);
  assert.equal(tools.find((tool) => tool.name === "github_actions_manage").risk, "mutating");
  assert.match(tools.find((tool) => tool.name === "github_actions_manage").unavailableReason, /Actions write access/);
  assert.equal(tools.find((tool) => tool.name === "github_merge_pull_request").risk, "mutating");
  assert.match(tools.find((tool) => tool.name === "github_merge_pull_request").unavailableReason, /Pull requests write access/);
  assert.equal(tools.find((tool) => tool.name === "subagent").available, false);
  await assert.rejects(
    registry.execute("web_search", { query: "CodeAgent" }),
    (error) => error.statusCode === 503 && /WEB_SEARCH/.test(error.message),
  );
});

test("surfaces upstream integration failures instead of returning success", async () => {
  const registry = createIntegrationToolRegistryFromEnv({
    WEB_SEARCH_PROVIDER: "custom",
    WEB_SEARCH_ENDPOINT: "https://search.test/query",
  }, async () => jsonResponse({ error: "provider rejected request" }, 401), {});

  await assert.rejects(
    registry.execute("web_search", { query: "CodeAgent" }),
    (error) => error.statusCode === 502 && /provider rejected request/.test(error.message),
  );
});

test("rejects malformed successful provider responses instead of returning empty success", async () => {
  const registry = createIntegrationToolRegistryFromEnv({
    WEB_SEARCH_PROVIDER: "custom",
    WEB_SEARCH_ENDPOINT: "https://search.test/query",
  }, async () => jsonResponse({ status: "ok" }), {});

  await assert.rejects(
    registry.execute("web_search", { query: "CodeAgent" }),
    (error) => error.statusCode === 502 && /unexpected response shape/.test(error.message),
  );
});

test("reads GitHub issues, pull requests, and text files through a scoped standard API", async () => {
  const calls = [];
  const registry = createIntegrationToolRegistryFromEnv({
    GITHUB_TOKEN: "github-token",
    GITHUB_API_URL: "https://github.example.test/api/v3",
  }, async (url, options = {}) => {
    const requestUrl = String(url);
    calls.push({ url: requestUrl, options });
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/issues/42") {
      return jsonResponse({
        number: 42,
        title: "Issue detail",
        html_url: "https://github.example.test/codeagent/idea/issues/42",
        state: "open",
        user: { login: "octo" },
        labels: [{ name: "bug" }],
        updated_at: "2026-07-21T00:00:00Z",
        body: "Issue body",
      });
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/7") {
      return jsonResponse({
        number: 7,
        title: "Pull request detail",
        html_url: "https://github.example.test/codeagent/idea/pull/7",
        state: "closed",
        merged: true,
        base: { ref: "main" },
        head: { ref: "feature", sha: PR_HEAD_SHA },
        changed_files: 3,
        body: "Pull request body",
      });
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/7/files?per_page=2") {
      return jsonResponse([
        {
          filename: "src/main.kt",
          status: "modified",
          additions: 5,
          deletions: 2,
          changes: 7,
          patch: "@@ -1 +1 @@\n-old\n+new",
          blob_url: "https://github.example.test/codeagent/idea/blob/head/src/main.kt",
        },
        { filename: "assets/icon.png", status: "added", additions: 0, deletions: 0, changes: 0 },
      ]);
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/issues/7/comments?per_page=1") {
      return jsonResponse([{
        user: { login: "reviewer" },
        created_at: "2026-07-21T01:00:00Z",
        html_url: "https://github.example.test/codeagent/idea/pull/7#issuecomment-1",
        body: "Please cover the error path.",
      }]);
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/7/commits?per_page=1") {
      return jsonResponse([{
        sha: PR_HEAD_SHA,
        html_url: `https://github.example.test/codeagent/idea/commit/${PR_HEAD_SHA}`,
        author: { login: "developer" },
        commit: {
          message: "Add PR review support\n\nCover checks and review comments.",
          author: { name: "Developer", date: "2026-07-21T02:00:00Z" },
        },
      }]);
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/7/reviews?per_page=1") {
      return jsonResponse([{
        id: 17,
        user: { login: "maintainer" },
        state: "APPROVED",
        submitted_at: "2026-07-21T03:00:00Z",
        commit_id: PR_HEAD_SHA,
        html_url: "https://github.example.test/codeagent/idea/pull/7#pullrequestreview-17",
        body: "The failure path is covered.",
      }]);
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/7/comments?per_page=1") {
      return jsonResponse([{
        id: 18,
        user: { login: "maintainer" },
        path: "src/main.kt",
        line: 12,
        side: "RIGHT",
        commit_id: PR_HEAD_SHA,
        created_at: "2026-07-21T03:01:00Z",
        html_url: "https://github.example.test/codeagent/idea/pull/7#discussion_r18",
        body: "Keep this branch exhaustive.",
      }]);
    }
    if (requestUrl === `https://github.example.test/api/v3/repos/codeagent/idea/commits/${PR_HEAD_SHA}/check-runs?per_page=1`) {
      return jsonResponse({
        total_count: 1,
        check_runs: [{
          id: 19,
          name: "Plugin Verifier",
          status: "completed",
          conclusion: "success",
          app: { slug: "github-actions" },
          started_at: "2026-07-21T03:02:00Z",
          completed_at: "2026-07-21T03:05:00Z",
          details_url: "https://github.example.test/codeagent/idea/actions/runs/19",
          output: { summary: "IDEA and PyCharm checks passed." },
        }],
      });
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/contents/src/main.kt?ref=main") {
      return jsonResponse({
        type: "file",
        encoding: "base64",
        content: Buffer.from("fun main() = Unit\n").toString("base64"),
        html_url: "https://github.example.test/codeagent/idea/blob/main/src/main.kt",
      });
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/contents/assets/icon.bin") {
      return jsonResponse({
        type: "file",
        encoding: "base64",
        content: Buffer.from([0, 1, 2]).toString("base64"),
        html_url: "https://github.example.test/codeagent/idea/blob/main/assets/icon.bin",
      });
    }
    if (requestUrl === "https://github.example.test/api/v3/repos/codeagent/idea/contents/bad.txt") {
      return jsonResponse({ type: "file", encoding: "base64", content: "not-valid@" });
    }
    throw new Error(`Unexpected request: ${requestUrl}`);
  }, {});

  const issue = await registry.execute("github_search", {
    operation: "get_issue",
    repository: "codeagent/idea",
    number: 42,
  });
  const pullRequest = await registry.execute("github_search", {
    operation: "get_pull_request",
    repository: "codeagent/idea",
    number: 7,
  });
  const file = await registry.execute("github_search", {
    operation: "read_file",
    repository: "codeagent/idea",
    path: "src/main.kt",
    ref: "main",
  });
  const binary = await registry.execute("github_search", {
    operation: "read_file",
    repository: "codeagent/idea",
    path: "assets/icon.bin",
  });
  const pullRequestFiles = await registry.execute("github_search", {
    operation: "list_pull_request_files",
    repository: "codeagent/idea",
    number: 7,
    limit: 2,
  });
  const comments = await registry.execute("github_search", {
    operation: "list_comments",
    repository: "codeagent/idea",
    number: 7,
    limit: 1,
  });
  const commits = await registry.execute("github_search", {
    operation: "list_pull_request_commits",
    repository: "codeagent/idea",
    number: 7,
    limit: 1,
  });
  const reviews = await registry.execute("github_search", {
    operation: "list_pull_request_reviews",
    repository: "codeagent/idea",
    number: 7,
    limit: 1,
  });
  const reviewComments = await registry.execute("github_search", {
    operation: "list_review_comments",
    repository: "codeagent/idea",
    number: 7,
    limit: 1,
  });
  const checks = await registry.execute("github_search", {
    operation: "list_pull_request_checks",
    repository: "codeagent/idea",
    number: 7,
    limit: 1,
  });

  assert.match(issue.output, /Issue body/);
  assert.match(issue.output, /labels=bug/);
  assert.match(pullRequest.output, /merged=true/);
  assert.match(pullRequest.output, /changed_files=3/);
  assert.match(pullRequest.output, new RegExp(`head_sha=${PR_HEAD_SHA}`));
  assert.match(file.output, /fun main\(\) = Unit/);
  assert.match(binary.output, /Binary file content is not included/);
  assert.match(pullRequestFiles.output, /src\/main\.kt/);
  assert.match(pullRequestFiles.output, /additions=5/);
  assert.match(pullRequestFiles.output, /patch:/);
  assert.match(comments.output, /reviewer/);
  assert.match(comments.output, /Please cover the error path/);
  assert.match(commits.output, /Add PR review support/);
  assert.match(commits.output, /author=developer/);
  assert.match(reviews.output, /maintainer: APPROVED/);
  assert.match(reviewComments.output, /src\/main\.kt/);
  assert.match(reviewComments.output, /line=12/);
  assert.match(checks.output, /Plugin Verifier/);
  assert.match(checks.output, /conclusion=success/);
  assert.match(checks.output, new RegExp(`head_sha=${PR_HEAD_SHA}`));
  assert.equal(calls.length, 11);
  assert.equal(calls[0].options.headers.authorization, "Bearer github-token");
  assert.equal(calls[0].options.headers["x-github-api-version"], "2022-11-28");

  await assert.rejects(
    registry.execute("github_search", { operation: "read_file", repository: "codeagent/idea", path: "../.env" }),
    (error) => error.statusCode === 400 && /within the repository/.test(error.message),
  );
  await assert.rejects(
    registry.execute("github_search", { operation: "get_issue", repository: "../secrets", number: 1 }),
    (error) => error.statusCode === 400 && /owner\/name/.test(error.message),
  );
  await assert.rejects(
    registry.execute("github_search", { operation: "read_file", repository: "codeagent/idea", path: "bad.txt" }),
    (error) => error.statusCode === 502 && /invalid base64/.test(error.message),
  );
});

test("executes approval-classified GitHub writes with bounded standard REST payloads", async () => {
  const calls = [];
  const registry = createIntegrationToolRegistryFromEnv({
    GITHUB_TOKEN: "github-write-token",
    GITHUB_API_URL: "https://github.example.test/api/v3/",
  }, async (url, options = {}) => {
    const call = { url: String(url), options };
    calls.push(call);
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/issues" && options.method === "POST") {
      return jsonResponse({
        number: 51,
        title: "Regression in login flow",
        state: "open",
        html_url: "https://github.example.test/codeagent/idea/issues/51",
      }, 201);
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/issues/51/comments" && options.method === "POST") {
      return jsonResponse({
        id: 9,
        html_url: "https://github.example.test/codeagent/idea/issues/51#issuecomment-9",
        user: { login: "codeagent" },
      }, 201);
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/issues/51" && options.method === "PATCH") {
      return jsonResponse({
        number: 51,
        state: "closed",
        state_reason: "completed",
        html_url: "https://github.example.test/codeagent/idea/issues/51",
      });
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/pulls" && options.method === "POST") {
      return jsonResponse({
        number: 52,
        title: "Add GitHub PR review support",
        state: "open",
        draft: true,
        html_url: "https://github.example.test/codeagent/idea/pull/52",
        head: { sha: PR_HEAD_SHA },
      }, 201);
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/52/reviews" && options.method === "POST") {
      return jsonResponse({
        id: 20,
        state: "CHANGES_REQUESTED",
        html_url: "https://github.example.test/codeagent/idea/pull/52#pullrequestreview-20",
        commit_id: PR_HEAD_SHA,
      }, 200);
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/52/requested_reviewers" && options.method === "POST") {
      return jsonResponse({
        number: 52,
        html_url: "https://github.example.test/codeagent/idea/pull/52",
        requested_reviewers: [{ login: "octocat" }],
        requested_teams: [{ slug: "platform" }],
      }, 201);
    }
    throw new Error(`Unexpected request: ${call.url}`);
  }, {});

  const capability = registry.list().find((tool) => tool.name === "github_manage");
  assert.equal(capability.risk, "mutating");
  assert.equal(capability.catalogId, "github");
  assert.equal(capability.available, true);

  const issue = await registry.execute("github_manage", {
    operation: "create_issue",
    repository: "codeagent/idea",
    title: "Regression in login flow",
    body: "Steps to reproduce",
  });
  const comment = await registry.execute("github_manage", {
    operation: "add_comment",
    repository: "codeagent/idea",
    number: 51,
    body: "Fixed by #52",
  });
  const state = await registry.execute("github_manage", {
    operation: "set_issue_state",
    repository: "codeagent/idea",
    number: 51,
    state: "closed",
    state_reason: "completed",
  });
  const pullRequest = await registry.execute("github_manage", {
    operation: "create_pull_request",
    repository: "codeagent/idea",
    title: "Add GitHub PR review support",
    body: "Adds checks and review endpoints.",
    head: "feature/github-reviews",
    base: "main",
    draft: true,
  });
  const review = await registry.execute("github_manage", {
    operation: "submit_pull_request_review",
    repository: "codeagent/idea",
    number: 52,
    event: "REQUEST_CHANGES",
    body: "Please add the merge-race test.",
    commit_id: PR_HEAD_SHA,
    comments: [{
      path: "backend/src/integration-tools.mjs",
      body: "Reject an out-of-date head before merging.",
      line: 704,
      side: "RIGHT",
      start_line: 700,
      start_side: "RIGHT",
    }],
  });
  const reviewers = await registry.execute("github_manage", {
    operation: "request_reviewers",
    repository: "codeagent/idea",
    number: 52,
    reviewers: ["octocat"],
    team_reviewers: ["platform"],
  });

  assert.match(issue.output, /Created codeagent\/idea#51/);
  assert.match(comment.output, /Commented on codeagent\/idea#51/);
  assert.match(state.output, /state_reason=completed/);
  assert.match(pullRequest.output, /Created pull request codeagent\/idea#52/);
  assert.match(pullRequest.output, /draft=true/);
  assert.match(review.output, /Submitted REQUEST_CHANGES review/);
  assert.match(reviewers.output, /reviewers=octocat/);
  assert.match(reviewers.output, /team_reviewers=platform/);
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    title: "Regression in login flow",
    body: "Steps to reproduce",
  });
  assert.deepEqual(JSON.parse(calls[1].options.body), { body: "Fixed by #52" });
  assert.deepEqual(JSON.parse(calls[2].options.body), { state: "closed", state_reason: "completed" });
  assert.deepEqual(JSON.parse(calls[3].options.body), {
    title: "Add GitHub PR review support",
    head: "feature/github-reviews",
    base: "main",
    body: "Adds checks and review endpoints.",
    draft: true,
  });
  assert.deepEqual(JSON.parse(calls[4].options.body), {
    event: "REQUEST_CHANGES",
    body: "Please add the merge-race test.",
    commit_id: PR_HEAD_SHA,
    comments: [{
      path: "backend/src/integration-tools.mjs",
      body: "Reject an out-of-date head before merging.",
      line: 704,
      side: "RIGHT",
      start_line: 700,
      start_side: "RIGHT",
    }],
  });
  assert.deepEqual(JSON.parse(calls[5].options.body), {
    reviewers: ["octocat"],
    team_reviewers: ["platform"],
  });
  assert.equal(calls[0].options.headers.authorization, "Bearer github-write-token");
  assert.equal(calls[0].options.headers["content-type"], "application/json");

  await assert.rejects(
    registry.execute("github_manage", {
      operation: "set_issue_state",
      repository: "codeagent/idea",
      number: 51,
      state: "open",
      state_reason: "completed",
    }),
    (error) => error.statusCode === 400 && /only use state_reason=reopened/.test(error.message),
  );
  await assert.rejects(
    registry.execute("github_manage", {
      operation: "add_comment",
      repository: "codeagent/idea",
      number: 51,
      body: " ",
    }),
    (error) => error.statusCode === 400 && /body is required/.test(error.message),
  );
  await assert.rejects(
    registry.execute("github_manage", {
      operation: "submit_pull_request_review",
      repository: "codeagent/idea",
      number: 52,
      event: "COMMENT",
    }),
    (error) => error.statusCode === 400 && /body is required when event=COMMENT/.test(error.message),
  );
  await assert.rejects(
    registry.execute("github_manage", {
      operation: "submit_pull_request_review",
      repository: "codeagent/idea",
      number: 52,
      event: "COMMENT",
      comments: [{ path: "src/main.kt", body: "Cannot use both forms.", position: 2, line: 12, side: "RIGHT" }],
    }),
    (error) => error.statusCode === 400 && /cannot set both position and line/.test(error.message),
  );
  await assert.rejects(
    registry.execute("github_manage", {
      operation: "request_reviewers",
      repository: "codeagent/idea",
      number: 52,
    }),
    (error) => error.statusCode === 400 && /At least one reviewer/.test(error.message),
  );
  const malformedRegistry = createIntegrationToolRegistryFromEnv({ GITHUB_TOKEN: "github-write-token" }, async () => (
    jsonResponse({ status: "ok" }, 201)
  ), {});
  await assert.rejects(
    malformedRegistry.execute("github_manage", {
      operation: "create_issue",
      repository: "codeagent/idea",
      title: "Missing provider fields",
    }),
    (error) => error.statusCode === 502 && /unexpected response shape/.test(error.message),
  );
  assert.equal(calls.length, 6);
});

test("merges only the explicitly approved live GitHub pull-request head", async () => {
  const calls = [];
  const registry = createIntegrationToolRegistryFromEnv({
    GITHUB_TOKEN: "github-merge-token",
    GITHUB_API_URL: "https://github.example.test/api/v3",
  }, async (url, options = {}) => {
    const call = { url: String(url), options };
    calls.push(call);
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/52" && !options.method) {
      return jsonResponse({
        number: 52,
        state: "open",
        merged: false,
        draft: false,
        mergeable: true,
        mergeable_state: "clean",
        base: { ref: "main" },
        head: { sha: PR_HEAD_SHA },
      });
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/52/reviews?per_page=100") {
      return jsonResponse([]);
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/branches/main/protection") {
      return jsonResponse({ message: "Branch not protected" }, 404);
    }
    if (call.url === `https://github.example.test/api/v3/repos/codeagent/idea/commits/${PR_HEAD_SHA}/check-runs?per_page=100`) {
      return jsonResponse({ check_runs: [] });
    }
    if (call.url === "https://github.example.test/api/v3/repos/codeagent/idea/pulls/52/merge" && options.method === "PUT") {
      return jsonResponse({ merged: true, sha: MERGE_COMMIT_SHA, message: "Pull Request successfully merged" });
    }
    throw new Error(`Unexpected request: ${call.url}`);
  }, {});

  const capability = registry.list().find((tool) => tool.name === "github_merge_pull_request");
  assert.equal(capability.risk, "mutating");
  assert.deepEqual(capability.parameters.required, ["repository", "number", "expected_head_sha", "merge_method"]);

  const merged = await registry.execute("github_merge_pull_request", {
    repository: "codeagent/idea",
    number: 52,
    expected_head_sha: PR_HEAD_SHA,
    merge_method: "squash",
    commit_title: "GitHub PR review support (#52)",
    commit_message: "Verified with backend integration tests.",
  });

  assert.match(merged.output, /Merged codeagent\/idea#52/);
  assert.match(merged.output, new RegExp(`approved_head_sha=${PR_HEAD_SHA}`));
  assert.match(merged.output, new RegExp(`merge_commit_sha=${MERGE_COMMIT_SHA}`));
  assert.equal(calls.length, 5);
  assert.deepEqual(JSON.parse(calls[4].options.body), {
    sha: PR_HEAD_SHA,
    merge_method: "squash",
    commit_title: "GitHub PR review support (#52)",
    commit_message: "Verified with backend integration tests.",
  });

  await assert.rejects(
    registry.execute("github_merge_pull_request", {
      repository: "codeagent/idea",
      number: 52,
      expected_head_sha: "invalid",
      merge_method: "merge",
    }),
    (error) => error.statusCode === 400 && /commit SHA/.test(error.message),
  );
  assert.equal(calls.length, 5);

  const mismatchCalls = [];
  const mismatchRegistry = createIntegrationToolRegistryFromEnv({ GITHUB_TOKEN: "github-merge-token" }, async (url, options = {}) => {
    mismatchCalls.push({ url: String(url), options });
    return jsonResponse({ number: 52, state: "open", merged: false, head: { sha: PR_HEAD_SHA } });
  }, {});
  await assert.rejects(
    mismatchRegistry.execute("github_merge_pull_request", {
      repository: "codeagent/idea",
      number: 52,
      expected_head_sha: "cccccccccccccccccccccccccccccccccccccccc",
      merge_method: "rebase",
    }),
    (error) => error.statusCode === 409 && /Pull request head changed/.test(error.message),
  );
  assert.equal(mismatchCalls.length, 1);

  const rejectedCalls = [];
  const rejectedRegistry = createIntegrationToolRegistryFromEnv({ GITHUB_TOKEN: "github-merge-token" }, async (url, options = {}) => {
    const requestUrl = String(url);
    rejectedCalls.push({ url: requestUrl, options });
    if (requestUrl.endsWith("/pulls/52")) {
      return jsonResponse({
        number: 52,
        state: "open",
        merged: false,
        draft: false,
        mergeable: true,
        mergeable_state: "clean",
        base: { ref: "main" },
        head: { sha: PR_HEAD_SHA },
      });
    }
    if (requestUrl.endsWith("/pulls/52/reviews?per_page=100")) return jsonResponse([]);
    if (requestUrl.endsWith("/branches/main/protection")) return jsonResponse({ message: "Branch not protected" }, 404);
    if (requestUrl.endsWith(`/commits/${PR_HEAD_SHA}/check-runs?per_page=100`)) return jsonResponse({ check_runs: [] });
    if (requestUrl.endsWith("/pulls/52/merge") && options.method === "PUT") {
      return jsonResponse({ merged: false, sha: null, message: "Required checks have not passed" });
    }
    throw new Error(`Unexpected request: ${requestUrl}`);
  }, {});
  await assert.rejects(
    rejectedRegistry.execute("github_merge_pull_request", {
      repository: "codeagent/idea",
      number: 52,
      expected_head_sha: PR_HEAD_SHA,
      merge_method: "merge",
    }),
    (error) => error.statusCode === 409 && /Required checks have not passed/.test(error.message),
  );
  assert.equal(rejectedCalls.length, 5);
});

test("reads GitHub Actions, policy, rulesets, logs, and merge readiness", async () => {
  const calls = [];
  const registry = createIntegrationToolRegistryFromEnv({
    GITHUB_TOKEN: "github-read-token",
    GITHUB_API_URL: "https://github.example.test/api/v3",
  }, async (url, options = {}) => {
    const requestUrl = String(url);
    calls.push({ url: requestUrl, options });
    if (requestUrl.endsWith("/pulls/7")) {
      return jsonResponse({
        number: 7,
        state: "open",
        merged: false,
        draft: false,
        mergeable: true,
        mergeable_state: "clean",
        base: { ref: "main" },
        head: { sha: PR_HEAD_SHA },
      });
    }
    if (requestUrl.endsWith(`/actions/runs?head_sha=${PR_HEAD_SHA}&per_page=1`)) {
      return jsonResponse({ workflow_runs: [{ id: 19, name: "CI", status: "completed", conclusion: "success", html_url: "https://github.example.test/actions/runs/19" }] });
    }
    if (requestUrl.endsWith("/actions/runs/19/jobs?per_page=1")) {
      return jsonResponse({ jobs: [{ id: 29, name: "test", status: "completed", conclusion: "success", steps: [{ number: 1, name: "npm test", status: "completed", conclusion: "success" }] }] });
    }
    if (requestUrl.endsWith("/actions/jobs/29/logs")) {
      return new Response(null, { status: 302, headers: { location: "https://logs.example.test/job-29.zip?token=short-lived" } });
    }
    if (requestUrl.endsWith("/branches/main/protection")) {
      return jsonResponse({
        required_status_checks: { strict: true, checks: [{ context: "CI", app_id: 123 }] },
        required_pull_request_reviews: { required_approving_review_count: 1, dismiss_stale_reviews: true },
        required_conversation_resolution: { enabled: false },
      });
    }
    if (requestUrl.endsWith("/rulesets")) {
      return jsonResponse([{ id: 4, name: "main policy", target: "branch", enforcement: "active", rules: [{ type: "required_status_checks" }] }]);
    }
    if (requestUrl.endsWith("/pulls/7/reviews?per_page=100")) {
      return jsonResponse([{ user: { login: "maintainer" }, state: "APPROVED", commit_id: PR_HEAD_SHA, submitted_at: "2026-07-21T03:00:00Z" }]);
    }
    if (requestUrl.endsWith(`/commits/${PR_HEAD_SHA}/check-runs?per_page=100`)) {
      return jsonResponse({ check_runs: [{ id: 31, name: "CI", status: "completed", conclusion: "success", app: { id: 123 } }] });
    }
    throw new Error(`Unexpected request: ${requestUrl}`);
  }, {});

  const runs = await registry.execute("github_search", { operation: "list_pull_request_workflow_runs", repository: "codeagent/idea", number: 7, limit: 1 });
  const jobs = await registry.execute("github_search", { operation: "list_workflow_jobs", repository: "codeagent/idea", run_id: 19, limit: 1 });
  const logs = await registry.execute("github_search", { operation: "get_workflow_job_logs", repository: "codeagent/idea", job_id: 29 });
  const protection = await registry.execute("github_search", { operation: "get_branch_protection", repository: "codeagent/idea", branch: "main" });
  const rulesets = await registry.execute("github_search", { operation: "list_repository_rulesets", repository: "codeagent/idea" });
  const readiness = await registry.execute("github_search", { operation: "get_pull_request_merge_readiness", repository: "codeagent/idea", number: 7 });

  assert.match(runs.output, /run_id=19/);
  assert.match(jobs.output, /npm test/);
  assert.match(logs.output, /short-lived/);
  assert.match(protection.output, /required_approvals=1/);
  assert.match(rulesets.output, /main policy/);
  assert.match(readiness.output, /ready=true/);
  assert.match(readiness.output, /CI=passed/);

  const blockedRegistry = createIntegrationToolRegistryFromEnv({ GITHUB_TOKEN: "github-read-token" }, async (url) => {
    const requestUrl = String(url);
    if (requestUrl.endsWith("/pulls/7")) return jsonResponse({ number: 7, state: "open", merged: false, draft: false, mergeable: true, mergeable_state: "blocked", base: { ref: "main" }, head: { sha: PR_HEAD_SHA } });
    if (requestUrl.endsWith("/pulls/7/reviews?per_page=100")) return jsonResponse([]);
    if (requestUrl.endsWith("/branches/main/protection")) return jsonResponse({ message: "not protected" }, 404);
    if (requestUrl.endsWith(`/commits/${PR_HEAD_SHA}/check-runs?per_page=100`)) return jsonResponse({ check_runs: [] });
    throw new Error(`Unexpected blocked-readiness request: ${requestUrl}`);
  }, {});
  const blocked = await blockedRegistry.execute("github_search", { operation: "get_pull_request_merge_readiness", repository: "codeagent/idea", number: 7 });
  assert.match(blocked.output, /ready=false/);
  assert.match(blocked.output, /mergeable_state=blocked/);
  assert.equal(calls.length, 10);
});

test("does not send a merge request when GitHub branch policy is not satisfied", async () => {
  const calls = [];
  const registry = createIntegrationToolRegistryFromEnv({ GITHUB_TOKEN: "github-merge-token" }, async (url, options = {}) => {
    const requestUrl = String(url);
    calls.push({ url: requestUrl, options });
    if (requestUrl.endsWith("/pulls/52")) {
      return jsonResponse({ number: 52, state: "open", merged: false, draft: false, mergeable: true, mergeable_state: "clean", base: { ref: "main" }, head: { sha: PR_HEAD_SHA } });
    }
    if (requestUrl.endsWith("/pulls/52/reviews?per_page=100")) {
      return jsonResponse([
        { id: 1, user: { login: "former-reviewer" }, state: "APPROVED", submitted_at: "2026-07-21T01:00:00Z" },
        { id: 2, user: { login: "former-reviewer" }, state: "DISMISSED", submitted_at: "2026-07-21T02:00:00Z" },
      ]);
    }
    if (requestUrl.endsWith("/branches/main/protection")) {
      return jsonResponse({
        required_status_checks: { checks: [{ context: "Plugin Verifier", app_id: 123 }] },
        required_pull_request_reviews: { required_approving_review_count: 1 },
      });
    }
    if (requestUrl.endsWith(`/commits/${PR_HEAD_SHA}/check-runs?per_page=100`)) {
      return jsonResponse({ check_runs: [{ id: 1, name: "Plugin Verifier", status: "completed", conclusion: "failure", app: { id: 123 } }] });
    }
    throw new Error(`Unexpected merge-readiness request: ${requestUrl}`);
  }, {});

  await assert.rejects(
    registry.execute("github_merge_pull_request", {
      repository: "codeagent/idea",
      number: 52,
      expected_head_sha: PR_HEAD_SHA,
      merge_method: "squash",
    }),
    (error) => error.statusCode === 409 && /required approvals not met/.test(error.message) && /failed required checks/.test(error.message),
  );
  assert.equal(calls.some((call) => call.url.endsWith("/pulls/52/merge")), false);
  assert.equal(calls.length, 4);
});

test("executes approval-gated GitHub Actions controls", async () => {
  const calls = [];
  const registry = createIntegrationToolRegistryFromEnv({ GITHUB_TOKEN: "github-actions-token", GITHUB_API_URL: "https://github.example.test/api/v3" }, async (url, options = {}) => {
    calls.push({ url: String(url), options });
    return new Response(null, { status: 202 });
  }, {});
  const capability = registry.list().find((tool) => tool.name === "github_actions_manage");
  assert.equal(capability.risk, "mutating");
  const operations = [
    ["rerun_workflow", { run_id: 19 }],
    ["rerun_failed_jobs", { run_id: 19 }],
    ["cancel_workflow", { run_id: 19 }],
    ["force_cancel_workflow", { run_id: 19 }],
    ["rerun_job", { job_id: 29 }],
  ];
  for (const [operation, ids] of operations) {
    const result = await registry.execute("github_actions_manage", { operation, repository: "codeagent/idea", ...ids });
    assert.match(result.output, /codeagent\/idea/);
  }
  assert.deepEqual(calls.map((call) => call.options.method), ["POST", "POST", "POST", "POST", "POST"]);
  assert.match(calls[0].url, /actions\/runs\/19\/rerun$/);
  assert.match(calls[4].url, /actions\/jobs\/29\/rerun$/);
});

test("executes configured HTTP integration adapters and normalizes real provider results", async () => {
  const calls = [];
  const fetchImpl = async (url, options = {}) => {
    const requestUrl = String(url);
    calls.push({ url: requestUrl, options });
    if (requestUrl === "https://search.test/query") {
      return jsonResponse({ results: [{ title: "Web result", url: "https://example.test", snippet: "Found it" }] });
    }
    if (requestUrl.startsWith("https://api.github.test/search/issues")) {
      return jsonResponse({ items: [{ number: 7, title: "GitHub issue", html_url: "https://github.test/i/7", state: "open" }] });
    }
    if (requestUrl === "https://linear.test/graphql") {
      return jsonResponse({
        data: {
          issues: {
            nodes: [{ identifier: "ENG-1", title: "Linear issue", url: "https://linear.test/ENG-1", state: { name: "Todo" } }],
          },
        },
      });
    }
    if (requestUrl === "https://notion.test/v1/search") {
      return jsonResponse({
        results: [{
          object: "page",
          id: "page-1",
          url: "https://notion.test/page-1",
          properties: { Name: { type: "title", title: [{ plain_text: "Notion page" }] } },
        }],
      });
    }
    if (requestUrl === "https://jira.test/rest/api/3/search/jql") {
      return jsonResponse({
        issues: [{ key: "ENG-2", fields: { summary: "Jira issue", status: { name: "Open" } } }],
      });
    }
    if (requestUrl.startsWith("https://confluence.test/wiki/rest/api/search")) {
      return jsonResponse({
        results: [{ title: "Confluence page", url: "/wiki/spaces/ENG/pages/1", excerpt: "<b>Matched</b> text" }],
      });
    }
    if (requestUrl === "https://glean.test/rest/api/v1/search") {
      return jsonResponse({
        results: [{ document: { title: "Glean result", url: "https://glean.test/doc/1", description: "Enterprise result" } }],
      });
    }
    if (requestUrl.startsWith("https://project.supabase.co/rest/v1/tickets")) {
      return jsonResponse([{ id: 1, status: "open" }]);
    }
    throw new Error(`Unexpected request: ${requestUrl}`);
  };
  const modelCalls = [];
  const modelGateway = {
    defaultModel: "model-a",
    async stream(request) {
      modelCalls.push(request);
      request.onTextDelta("Delegated finding");
      return { content: "Delegated finding", toolCalls: [] };
    },
  };
  const registry = createIntegrationToolRegistryFromEnv({
    WEB_SEARCH_PROVIDER: "custom",
    WEB_SEARCH_ENDPOINT: "https://search.test/query",
    WEB_SEARCH_API_KEY: "web-key",
    GITHUB_TOKEN: "github-key",
    GITHUB_API_URL: "https://api.github.test",
    LINEAR_API_KEY: "linear-key",
    LINEAR_API_URL: "https://linear.test/graphql",
    NOTION_TOKEN: "notion-key",
    NOTION_API_URL: "https://notion.test/v1",
    JIRA_BASE_URL: "https://jira.test",
    JIRA_EMAIL: "dev@example.test",
    JIRA_API_TOKEN: "jira-key",
    CONFLUENCE_BASE_URL: "https://confluence.test",
    CONFLUENCE_BEARER_TOKEN: "confluence-key",
    GLEAN_SEARCH_ENDPOINT: "https://glean.test/rest/api/v1/search",
    GLEAN_API_TOKEN: "glean-key",
    SUPABASE_URL: "https://project.supabase.co",
    SUPABASE_KEY: "supabase-key",
    SUPABASE_TABLES: "tickets,projects",
  }, fetchImpl, modelGateway);

  assert.equal(registry.list().filter((tool) => tool.available).length, 12);
  assert.match((await registry.execute("web_search", { query: "web" })).output, /Web result/);
  assert.match((await registry.execute("github_search", { query: "bug" })).output, /GitHub issue/);
  assert.match((await registry.execute("linear_search", { query: "linear" })).output, /ENG-1/);
  assert.match((await registry.execute("notion_search", { query: "notion" })).output, /Notion page/);
  assert.match((await registry.execute("jira_search", { jql: "project = ENG" })).output, /ENG-2/);
  assert.match((await registry.execute("confluence_search", { cql: "text ~ codeagent" })).output, /Confluence page/);
  assert.match((await registry.execute("glean_search", { query: "glean" })).output, /Glean result/);
  assert.match((await registry.execute("supabase_query", {
    table: "tickets",
    match: { status: "open" },
  })).output, /"status": "open"/);
  await assert.rejects(
    registry.execute("supabase_query", { table: "secrets" }),
    /not allowlisted/,
  );
  assert.match((await registry.execute("subagent", {
    task: "Review this",
    role: "review",
    expected_output: "Prioritized findings",
    max_output_tokens: 2048,
  })).output, /Delegated finding/);

  assert.equal(JSON.parse(calls.find((call) => call.url === "https://search.test/query").options.body).query, "web");
  assert.match(calls.find((call) => call.url.startsWith("https://api.github.test")).options.headers.authorization, /github-key/);
  assert.match(calls.find((call) => call.url.startsWith("https://project.supabase.co")).url, /status=eq.open/);
  assert.equal(modelCalls[0].tools.length, 0);
  assert.equal(modelCalls[0].maxOutputTokens, 2048);
  assert.match(modelCalls[0].messages[0].content, /review subagent/);
  assert.match(modelCalls[0].messages[1].content, /Prioritized findings/);
});

test("serves tool discovery and returns 503 for an unconfigured backend tool", async () => {
  const registry = createIntegrationToolRegistryFromEnv({}, async () => {
    throw new Error("fetch must not run");
  }, {});
  const server = createCodeAgentServer({
    modelGateway: {},
    integrationTools: registry,
    authToken: "secret",
    logger: { error() {} },
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  try {
    const listResponse = await fetch(`${baseUrl}/v1/tools`, {
      headers: { authorization: "Bearer secret" },
    });
    assert.equal(listResponse.status, 200);
    const list = await listResponse.json();
    assert.equal(list.object, "list");
    assert.equal(list.data.find((tool) => tool.name === "web_search").available, false);
    assert.equal(list.data.find((tool) => tool.name === "web_search").risk, "read_only");

    const executeResponse = await fetch(`${baseUrl}/v1/tools/web_search`, {
      method: "POST",
      headers: {
        authorization: "Bearer secret",
        "content-type": "application/json",
      },
      body: JSON.stringify({ arguments: { query: "CodeAgent" } }),
    });
    assert.equal(executeResponse.status, 503);
    assert.match((await executeResponse.json()).error, /WEB_SEARCH/);
  } finally {
    server.close();
    await once(server, "close");
  }
});

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
