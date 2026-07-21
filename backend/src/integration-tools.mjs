import { delegatedSubagentMessages } from "./prompt.mjs";

const MAX_OUTPUT_CHARS = 24_000;
const MAX_GITHUB_FILE_BYTES = 512_000;

export class IntegrationToolRegistry {
  constructor(tools) {
    this.tools = new Map(tools.map((tool) => [tool.name, tool]));
  }

  list() {
    return [...this.tools.values()].map((tool) => ({
      name: tool.name,
      catalogId: tool.catalogId,
      description: tool.description,
      parameters: tool.parameters,
      risk: tool.risk,
      available: tool.available,
      unavailableReason: tool.unavailableReason || undefined,
      requiredEnvironment: tool.requiredEnvironment || [],
    }));
  }

  async execute(name, args, { signal } = {}) {
    const tool = this.tools.get(name);
    if (!tool) throw httpError(404, `Unknown backend tool: ${name}`);
    if (!tool.available) {
      throw httpError(503, tool.unavailableReason || `Backend tool '${name}' is not configured`);
    }
    if (!args || typeof args !== "object" || Array.isArray(args)) {
      throw httpError(400, "arguments must be an object");
    }
    return normalizeExecutionResult(await tool.execute(args, { signal }));
  }
}

export function createIntegrationToolRegistryFromEnv(
  env = process.env,
  fetchImpl = fetch,
  modelGateway = undefined,
) {
  return new IntegrationToolRegistry([
    createWebSearchTool(env, fetchImpl),
    createGitHubTool(env, fetchImpl),
    createGitHubManageTool(env, fetchImpl),
    createGitHubActionsManageTool(env, fetchImpl),
    createGitHubMergeTool(env, fetchImpl),
    createLinearTool(env, fetchImpl),
    createNotionTool(env, fetchImpl),
    createJiraTool(env, fetchImpl),
    createConfluenceTool(env, fetchImpl),
    createGleanTool(env, fetchImpl),
    createSupabaseTool(env, fetchImpl),
    createSubagentTool(modelGateway),
  ]);
}

function createWebSearchTool(env, fetchImpl) {
  const provider = setting(env, "WEB_SEARCH_PROVIDER").toLowerCase()
    || (setting(env, "WEB_SEARCH_ENDPOINT") ? "custom" : "");
  const apiKey = setting(env, "WEB_SEARCH_API_KEY");
  const configured = provider === "brave"
    ? Boolean(apiKey)
    : provider === "custom"
      ? Boolean(setting(env, "WEB_SEARCH_ENDPOINT"))
      : false;
  const reason = provider && !["brave", "custom"].includes(provider)
    ? `Unsupported WEB_SEARCH_PROVIDER: ${provider}`
    : "Set WEB_SEARCH_PROVIDER=brave with WEB_SEARCH_API_KEY, or configure WEB_SEARCH_ENDPOINT";

  return tool({
    name: "web_search",
    catalogId: "web",
    description: "Search the public web through the configured backend search provider",
    parameters: objectSchema({
      query: stringSchema("Search query"),
      limit: integerSchema("Maximum results", 1, 10),
    }, ["query"]),
    available: configured,
    unavailableReason: configured ? "" : reason,
    requiredEnvironment: provider === "brave"
      ? ["WEB_SEARCH_PROVIDER", "WEB_SEARCH_API_KEY"]
      : ["WEB_SEARCH_ENDPOINT"],
    execute: async (args, { signal }) => {
      const query = requiredString(args, "query", 2_000);
      const limit = boundedInteger(args.limit, 5, 1, 10);
      let body;
      if (provider === "brave") {
        const endpoint = setting(env, "WEB_SEARCH_ENDPOINT")
          || "https://api.search.brave.com/res/v1/web/search";
        const url = new URL(endpoint);
        url.searchParams.set("q", query);
        url.searchParams.set("count", String(limit));
        body = await requestJson(fetchImpl, url, {
          headers: {
            accept: "application/json",
            "x-subscription-token": apiKey,
          },
          signal,
        }, "Web search");
      } else {
        const endpoint = requiredUrl(setting(env, "WEB_SEARCH_ENDPOINT"), "WEB_SEARCH_ENDPOINT");
        body = await requestJson(fetchImpl, endpoint, {
          method: "POST",
          headers: jsonAuthHeaders(apiKey, setting(env, "WEB_SEARCH_API_KEY_HEADER")),
          body: JSON.stringify({ query, limit }),
          signal,
        }, "Web search");
      }
      const rawResults = body?.web?.results ?? body?.results ?? body?.items;
      const results = normalizeSearchItems(requiredArray(rawResults, "Web search"), limit);
      return searchResult("web", results);
    },
  });
}

function createGitHubTool(env, fetchImpl) {
  const token = setting(env, "GITHUB_TOKEN");
  const baseUrl = setting(env, "GITHUB_API_URL") || "https://api.github.com";
  return tool({
    name: "github_search",
    catalogId: "github",
    description: "Search GitHub; inspect pull-request reviews, checks, Actions runs and jobs, branch policy, rulesets, merge readiness, and bounded repository files",
    parameters: objectSchema({
      operation: enumSchema("GitHub operation; defaults to search", [
        "search",
        "get_issue",
        "get_pull_request",
        "list_pull_request_files",
        "list_pull_request_commits",
        "list_pull_request_reviews",
        "list_review_comments",
        "list_pull_request_checks",
        "list_pull_request_workflow_runs",
        "list_workflow_jobs",
        "get_workflow_job_logs",
        "get_branch_protection",
        "list_repository_rulesets",
        "get_pull_request_merge_readiness",
        "list_comments",
        "read_file",
      ]),
      query: stringSchema("GitHub search query, including qualifiers when useful; required for search"),
      type: enumSchema("Search index when operation is search", ["issues", "repositories", "code"]),
      repository: stringSchema("Repository in owner/name form; required for every operation except search"),
      number: integerSchema("Issue or pull request number", 1, 1_000_000),
      path: stringSchema("Repository-relative UTF-8 file path; required for read_file"),
      ref: stringSchema("Optional Git ref, branch, tag, or commit for read_file"),
      branch: stringSchema("Repository branch name; required for get_branch_protection"),
      run_id: integerSchema("GitHub Actions workflow run ID", 1, Number.MAX_SAFE_INTEGER),
      job_id: integerSchema("GitHub Actions job ID", 1, Number.MAX_SAFE_INTEGER),
      limit: integerSchema("Maximum search results, files, commits, reviews, comments, or checks", 1, 100),
    }),
    available: Boolean(token),
    unavailableReason: "Set GITHUB_TOKEN on the backend",
    requiredEnvironment: ["GITHUB_TOKEN"],
    execute: async (args, { signal }) => {
      const operation = optionalEnum(args.operation, "search", [
        "search",
        "get_issue",
        "get_pull_request",
        "list_pull_request_files",
        "list_pull_request_commits",
        "list_pull_request_reviews",
        "list_review_comments",
        "list_pull_request_checks",
        "list_pull_request_workflow_runs",
        "list_workflow_jobs",
        "get_workflow_job_logs",
        "get_branch_protection",
        "list_repository_rulesets",
        "get_pull_request_merge_readiness",
        "list_comments",
        "read_file",
      ]);
      if (operation === "search") return githubSearch(baseUrl, token, fetchImpl, args, signal);
      const repository = githubRepository(args.repository);
      if (operation === "read_file") return githubFile(baseUrl, token, fetchImpl, repository, args, signal);
      if (operation === "list_pull_request_files") {
        return githubPullRequestFiles(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_pull_request_commits") {
        return githubPullRequestCommits(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_pull_request_reviews") {
        return githubPullRequestReviews(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_review_comments") {
        return githubReviewComments(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_pull_request_checks") {
        return githubPullRequestChecks(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_pull_request_workflow_runs") {
        return githubPullRequestWorkflowRuns(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_workflow_jobs") {
        return githubWorkflowJobs(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "get_workflow_job_logs") {
        return githubWorkflowJobLogs(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "get_branch_protection") {
        return githubBranchProtection(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_repository_rulesets") {
        return githubRepositoryRulesets(baseUrl, token, fetchImpl, repository, signal);
      }
      if (operation === "get_pull_request_merge_readiness") {
        return githubPullRequestMergeReadiness(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "list_comments") return githubComments(baseUrl, token, fetchImpl, repository, args, signal);
      return githubWorkItem(baseUrl, token, fetchImpl, repository, args, signal, operation);
    },
  });
}

function createGitHubManageTool(env, fetchImpl) {
  const token = setting(env, "GITHUB_TOKEN");
  const baseUrl = setting(env, "GITHUB_API_URL") || "https://api.github.com";
  return tool({
    name: "github_manage",
    catalogId: "github",
    description: "Create GitHub issues or pull requests, comment, change state, submit a PR review, or request reviewers. Every operation writes remote state and requires approval",
    parameters: objectSchema({
      operation: enumSchema("GitHub write operation", [
        "create_issue",
        "add_comment",
        "set_issue_state",
        "create_pull_request",
        "submit_pull_request_review",
        "request_reviewers",
      ]),
      repository: stringSchema("Repository in owner/name form"),
      number: integerSchema("Issue or pull request number; required for item-specific operations", 1, 1_000_000),
      title: stringSchema("Issue or pull-request title"),
      body: stringSchema("Issue, pull-request, comment, or review body"),
      state: enumSchema("Target state for set_issue_state", ["open", "closed"]),
      state_reason: enumSchema("Optional GitHub state reason", ["completed", "not_planned", "reopened"]),
      head: stringSchema("Head branch for create_pull_request, optionally owner:branch"),
      base: stringSchema("Base branch for create_pull_request"),
      draft: booleanSchema("Whether a new pull request is a draft"),
      event: enumSchema("Review decision for submit_pull_request_review", ["APPROVE", "REQUEST_CHANGES", "COMMENT"]),
      commit_id: stringSchema("Optional 40- or 64-character commit SHA to attach a review to"),
      comments: githubReviewCommentsSchema(),
      reviewers: stringArraySchema("GitHub users to request as reviewers", 15),
      team_reviewers: stringArraySchema("GitHub team slugs to request as reviewers", 15),
    }, ["operation", "repository"]),
    risk: "mutating",
    available: Boolean(token),
    unavailableReason: "Set GITHUB_TOKEN with GitHub Issues or Pull requests write access on the backend",
    requiredEnvironment: ["GITHUB_TOKEN"],
    execute: async (args, { signal }) => {
      const operation = optionalEnum(args.operation, null, [
        "create_issue",
        "add_comment",
        "set_issue_state",
        "create_pull_request",
        "submit_pull_request_review",
        "request_reviewers",
      ]);
      if (!operation) throw httpError(400, "operation is required");
      const repository = githubRepository(args.repository);
      if (operation === "create_issue") {
        return githubCreateIssue(baseUrl, token, fetchImpl, repository, args, signal);
      }
      if (operation === "create_pull_request") {
        return githubCreatePullRequest(baseUrl, token, fetchImpl, repository, args, signal);
      }
      const number = githubNumber(args);
      if (operation === "add_comment") {
        return githubAddComment(baseUrl, token, fetchImpl, repository, number, args, signal);
      }
      if (operation === "set_issue_state") {
        return githubSetIssueState(baseUrl, token, fetchImpl, repository, number, args, signal);
      }
      if (operation === "submit_pull_request_review") {
        return githubSubmitPullRequestReview(baseUrl, token, fetchImpl, repository, number, args, signal);
      }
      return githubRequestReviewers(baseUrl, token, fetchImpl, repository, number, args, signal);
    },
  });
}

function createGitHubActionsManageTool(env, fetchImpl) {
  const token = setting(env, "GITHUB_TOKEN");
  const baseUrl = setting(env, "GITHUB_API_URL") || "https://api.github.com";
  return tool({
    name: "github_actions_manage",
    catalogId: "github",
    description: "Rerun, cancel, or force-cancel GitHub Actions workflow runs and jobs. Every operation writes remote state and requires approval",
    parameters: objectSchema({
      operation: enumSchema("GitHub Actions write operation", [
        "rerun_workflow",
        "rerun_failed_jobs",
        "cancel_workflow",
        "force_cancel_workflow",
        "rerun_job",
      ]),
      repository: stringSchema("Repository in owner/name form"),
      run_id: integerSchema("GitHub Actions workflow run ID", 1, Number.MAX_SAFE_INTEGER),
      job_id: integerSchema("GitHub Actions job ID", 1, Number.MAX_SAFE_INTEGER),
    }, ["operation", "repository"]),
    risk: "mutating",
    available: Boolean(token),
    unavailableReason: "Set GITHUB_TOKEN with GitHub Actions write access on the backend",
    requiredEnvironment: ["GITHUB_TOKEN"],
    execute: async (args, { signal }) => {
      const operation = optionalEnum(args.operation, null, [
        "rerun_workflow",
        "rerun_failed_jobs",
        "cancel_workflow",
        "force_cancel_workflow",
        "rerun_job",
      ]);
      if (!operation) throw httpError(400, "operation is required");
      const repository = githubRepository(args.repository);
      return githubActionsManage(baseUrl, token, fetchImpl, repository, operation, args, signal);
    },
  });
}

function createGitHubMergeTool(env, fetchImpl) {
  const token = setting(env, "GITHUB_TOKEN");
  const baseUrl = setting(env, "GITHUB_API_URL") || "https://api.github.com";
  return tool({
    name: "github_merge_pull_request",
    catalogId: "github",
    description: "Merge a GitHub pull request only when its live head SHA matches the explicitly approved SHA",
    parameters: objectSchema({
      repository: stringSchema("Repository in owner/name form"),
      number: integerSchema("Pull request number", 1, 1_000_000),
      expected_head_sha: stringSchema("Exact 40- or 64-character pull-request head commit SHA approved for merge"),
      merge_method: enumSchema("GitHub merge method", ["merge", "squash", "rebase"]),
      commit_title: stringSchema("Optional merge commit title"),
      commit_message: stringSchema("Optional merge commit message"),
    }, ["repository", "number", "expected_head_sha", "merge_method"]),
    risk: "mutating",
    available: Boolean(token),
    unavailableReason: "Set GITHUB_TOKEN with Pull requests write access on the backend",
    requiredEnvironment: ["GITHUB_TOKEN"],
    execute: async (args, { signal }) => {
      const repository = githubRepository(args.repository);
      const number = githubNumber(args);
      return githubMergePullRequest(baseUrl, token, fetchImpl, repository, number, args, signal);
    },
  });
}

async function githubSearch(baseUrl, token, fetchImpl, args, signal) {
  const query = requiredString(args, "query", 2_000);
  const type = optionalEnum(args.type, "issues", ["issues", "repositories", "code"]);
  const limit = boundedInteger(args.limit, 10, 1, 100);
  const url = githubApiUrl(baseUrl, "search", type);
  url.searchParams.set("q", query);
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub search");
  const results = requiredArray(body?.items, "GitHub search").slice(0, limit).map((item) => {
    if (type === "repositories") {
      return {
        title: item.full_name || item.name,
        url: item.html_url,
        snippet: [item.description, item.language, item.stargazers_count != null ? `${item.stargazers_count} stars` : ""]
          .filter(Boolean).join(" | "),
      };
    }
    if (type === "code") {
      return {
        title: [item.repository?.full_name, item.path || item.name].filter(Boolean).join(":"),
        url: item.html_url,
        snippet: item.name || item.path || "",
      };
    }
    return {
      title: item.title || `#${item.number}`,
      url: item.html_url,
      snippet: [item.state, item.pull_request ? "pull request" : "issue", item.user?.login]
        .filter(Boolean).join(" | "),
    };
  });
  return searchResult(`GitHub ${type}`, results);
}

async function githubWorkItem(baseUrl, token, fetchImpl, repository, args, signal, operation) {
  const number = githubNumber(args);
  const kind = operation === "get_pull_request" ? "pulls" : "issues";
  const label = operation === "get_pull_request" ? "pull request" : "issue";
  const body = await requestJson(
    fetchImpl,
    githubApiUrl(baseUrl, "repos", repository.owner, repository.name, kind, String(number)),
    { headers: githubHeaders(token), signal },
    `GitHub ${label}`,
  );
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw httpError(502, `GitHub ${label} returned an unexpected response shape`);
  }
  const labels = Array.isArray(body.labels)
    ? body.labels.map((item) => item?.name).filter(Boolean)
    : [];
  const output = buildString([
    `${repository.slug}#${body.number || number}: ${body.title || `GitHub ${label}`}`,
    body.html_url,
    [
      `state=${body.state || "unknown"}`,
      `author=${body.user?.login || "unknown"}`,
      body.updated_at ? `updated_at=${body.updated_at}` : "",
      labels.length ? `labels=${labels.join(", ")}` : "",
      operation === "get_pull_request" && typeof body.merged === "boolean" ? `merged=${body.merged}` : "",
      operation === "get_pull_request" && body.base?.ref ? `base=${body.base.ref}` : "",
      operation === "get_pull_request" && body.head?.ref ? `head=${body.head.ref}` : "",
      operation === "get_pull_request" && body.head?.sha ? `head_sha=${body.head.sha}` : "",
      operation === "get_pull_request" && Number.isInteger(body.changed_files) ? `changed_files=${body.changed_files}` : "",
    ].filter(Boolean).join("\n"),
    "",
    "Description:",
    body.body || "No description provided.",
  ]);
  return {
    output: truncate(output),
    summary: `Read GitHub ${label} ${repository.slug}#${body.number || number}`,
    detail: truncate(output),
  };
}

async function githubPullRequestFiles(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "pulls", String(number), "files");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(
    fetchImpl,
    url,
    { headers: githubHeaders(token), signal },
    "GitHub pull request files",
  );
  const files = requiredArray(body, "GitHub pull request files").slice(0, limit);
  const output = files.length
    ? files.map((file, index) => buildString([
      `${index + 1}. ${file.filename || "Unknown file"}`,
      [
        `status=${file.status || "unknown"}`,
        Number.isInteger(file.additions) ? `additions=${file.additions}` : "",
        Number.isInteger(file.deletions) ? `deletions=${file.deletions}` : "",
        Number.isInteger(file.changes) ? `changes=${file.changes}` : "",
      ].filter(Boolean).join(" "),
      file.blob_url,
      file.patch ? `patch:\n${truncate(file.patch, 8_000)}` : "patch unavailable (binary or too large)",
    ])).join("\n\n")
    : `No changed files found for ${repository.slug}#${number}`;
  return {
    output: truncate(output),
    summary: `Read ${files.length} changed file${files.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail: truncate(output),
  };
}

async function githubComments(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "issues", String(number), "comments");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub comments");
  const comments = requiredArray(body, "GitHub comments").slice(0, limit);
  const output = comments.length
    ? comments.map((comment, index) => buildString([
      `${index + 1}. ${comment.user?.login || "unknown"} at ${comment.created_at || "unknown time"}`,
      comment.html_url,
      comment.body ? truncate(comment.body, 8_000) : "No comment body.",
    ])).join("\n\n")
    : `No comments found for ${repository.slug}#${number}`;
  return {
    output: truncate(output),
    summary: `Read ${comments.length} comment${comments.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail: truncate(output),
  };
}

async function githubPullRequestCommits(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "pulls", String(number), "commits");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub pull request commits");
  const commits = requiredArray(body, "GitHub pull request commits").slice(0, limit);
  const output = commits.length
    ? commits.map((commit, index) => buildString([
      `${index + 1}. ${commit.sha || "unknown SHA"} ${firstLine(commit.commit?.message) || "No commit message"}`,
      [
        `author=${commit.author?.login || commit.commit?.author?.name || "unknown"}`,
        commit.commit?.author?.date ? `authored_at=${commit.commit.author.date}` : "",
      ].filter(Boolean).join(" "),
      commit.html_url,
      commit.commit?.message ? truncate(commit.commit.message, 4_000) : "",
    ])).join("\n\n")
    : `No commits found for ${repository.slug}#${number}`;
  return {
    output: truncate(output),
    summary: `Read ${commits.length} commit${commits.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail: truncate(output),
  };
}

async function githubPullRequestReviews(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const reviews = await githubPullRequestReviewsData(baseUrl, token, fetchImpl, repository, number, limit, signal);
  const output = reviews.length
    ? reviews.map((review, index) => buildString([
      `${index + 1}. ${review.user?.login || "unknown"}: ${review.state || "UNKNOWN"}`,
      [
        review.submitted_at ? `submitted_at=${review.submitted_at}` : "",
        review.commit_id ? `commit=${review.commit_id}` : "",
      ].filter(Boolean).join(" "),
      review.html_url,
      review.body ? truncate(review.body, 8_000) : "No review body.",
    ])).join("\n\n")
    : `No reviews found for ${repository.slug}#${number}`;
  return {
    output: truncate(output),
    summary: `Read ${reviews.length} review${reviews.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail: truncate(output),
  };
}

async function githubPullRequestReviewsData(baseUrl, token, fetchImpl, repository, number, limit, signal) {
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "pulls", String(number), "reviews");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub pull request reviews");
  return requiredArray(body, "GitHub pull request reviews").slice(0, limit);
}

async function githubReviewComments(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "pulls", String(number), "comments");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub review comments");
  const comments = requiredArray(body, "GitHub review comments").slice(0, limit);
  const output = comments.length
    ? comments.map((comment, index) => buildString([
      `${index + 1}. ${comment.user?.login || "unknown"} on ${comment.path || "unknown file"}`,
      [
        Number.isInteger(comment.line) ? `line=${comment.line}` : "",
        comment.side ? `side=${comment.side}` : "",
        Number.isInteger(comment.original_line) ? `original_line=${comment.original_line}` : "",
        comment.commit_id ? `commit=${comment.commit_id}` : "",
        comment.created_at ? `created_at=${comment.created_at}` : "",
      ].filter(Boolean).join(" "),
      comment.html_url,
      comment.body ? truncate(comment.body, 8_000) : "No comment body.",
    ])).join("\n\n")
    : `No line-level review comments found for ${repository.slug}#${number}`;
  return {
    output: truncate(output),
    summary: `Read ${comments.length} line-level review comment${comments.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail: truncate(output),
  };
}

async function githubPullRequestChecks(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const pullRequest = await githubPullRequestMetadata(baseUrl, token, fetchImpl, repository, number, signal);
  const headSha = githubCommitSha(pullRequest.head?.sha, "GitHub pull request head SHA", 502);
  const checks = await githubCheckRunsForSha(baseUrl, token, fetchImpl, repository, headSha, limit, signal);
  const output = checks.length
    ? checks.map((check, index) => buildString([
      `${index + 1}. ${check.name || "Unnamed check"}`,
      [
        `status=${check.status || "unknown"}`,
        check.conclusion ? `conclusion=${check.conclusion}` : "",
        check.app?.slug ? `app=${check.app.slug}` : "",
        check.started_at ? `started_at=${check.started_at}` : "",
        check.completed_at ? `completed_at=${check.completed_at}` : "",
      ].filter(Boolean).join(" "),
      check.details_url,
      check.output?.summary ? truncate(check.output.summary, 4_000) : "",
    ])).join("\n\n")
    : `No check runs found for ${repository.slug}#${number} at ${headSha}`;
  return {
    output: truncate(buildString([`head_sha=${headSha}`, output])),
    summary: `Read ${checks.length} check run${checks.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail: truncate(buildString([`head_sha=${headSha}`, output])),
  };
}

async function githubCheckRunsForSha(baseUrl, token, fetchImpl, repository, headSha, limit, signal) {
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "commits", headSha, "check-runs");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub check runs");
  return requiredArray(body?.check_runs, "GitHub check runs").slice(0, limit);
}

async function githubPullRequestWorkflowRuns(baseUrl, token, fetchImpl, repository, args, signal) {
  const number = githubNumber(args);
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const pullRequest = await githubPullRequestMetadata(baseUrl, token, fetchImpl, repository, number, signal);
  const headSha = githubCommitSha(pullRequest.head?.sha, "GitHub pull request head SHA", 502);
  const runs = await githubWorkflowRunsForSha(baseUrl, token, fetchImpl, repository, headSha, limit, signal);
  const output = runs.length
    ? runs.map((run, index) => buildString([
      `${index + 1}. ${run.name || run.workflow_name || "Unnamed workflow"}`,
      [
        Number.isInteger(run.id) ? `run_id=${run.id}` : "",
        `status=${run.status || "unknown"}`,
        run.conclusion ? `conclusion=${run.conclusion}` : "",
        run.event ? `event=${run.event}` : "",
        Number.isInteger(run.run_number) ? `run_number=${run.run_number}` : "",
        run.created_at ? `created_at=${run.created_at}` : "",
      ].filter(Boolean).join(" "),
      run.html_url,
    ])).join("\n\n")
    : `No GitHub Actions workflow runs found for ${repository.slug}#${number} at ${headSha}`;
  const detail = truncate(buildString([`head_sha=${headSha}`, output]));
  return {
    output: detail,
    summary: `Read ${runs.length} GitHub Actions workflow run${runs.length === 1 ? "" : "s"} from ${repository.slug}#${number}`,
    detail,
  };
}

async function githubWorkflowRunsForSha(baseUrl, token, fetchImpl, repository, headSha, limit, signal) {
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "actions", "runs");
  url.searchParams.set("head_sha", headSha);
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub Actions workflow runs");
  return requiredArray(body?.workflow_runs, "GitHub Actions workflow runs").slice(0, limit);
}

async function githubWorkflowJobs(baseUrl, token, fetchImpl, repository, args, signal) {
  const runId = githubActionId(args.run_id, "run_id");
  const limit = boundedInteger(args.limit, 30, 1, 100);
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "actions", "runs", String(runId), "jobs");
  url.searchParams.set("per_page", String(limit));
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub Actions workflow jobs");
  const jobs = requiredArray(body?.jobs, "GitHub Actions workflow jobs").slice(0, limit);
  const output = jobs.length
    ? jobs.map((job, index) => {
      const steps = Array.isArray(job.steps)
        ? job.steps.slice(0, 100).map((step) => [
          Number.isInteger(step.number) ? `${step.number}.` : "-",
          step.name || "Unnamed step",
          `status=${step.status || "unknown"}`,
          step.conclusion ? `conclusion=${step.conclusion}` : "",
        ].filter(Boolean).join(" ")).join("\n")
        : "";
      return buildString([
        `${index + 1}. ${job.name || "Unnamed job"}`,
        [
          Number.isInteger(job.id) ? `job_id=${job.id}` : "",
          `status=${job.status || "unknown"}`,
          job.conclusion ? `conclusion=${job.conclusion}` : "",
          job.started_at ? `started_at=${job.started_at}` : "",
          job.completed_at ? `completed_at=${job.completed_at}` : "",
        ].filter(Boolean).join(" "),
        job.html_url,
        steps ? `steps:\n${steps}` : "",
      ]);
    }).join("\n\n")
    : `No GitHub Actions jobs found for workflow run ${runId}`;
  const detail = truncate(output);
  return {
    output: detail,
    summary: `Read ${jobs.length} GitHub Actions job${jobs.length === 1 ? "" : "s"} from workflow run ${runId}`,
    detail,
  };
}

async function githubWorkflowJobLogs(baseUrl, token, fetchImpl, repository, args, signal) {
  const jobId = githubActionId(args.job_id, "job_id");
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "actions", "jobs", String(jobId), "logs");
  let response;
  try {
    response = await fetchImpl(url, { headers: githubHeaders(token), signal, redirect: "manual" });
  } catch (error) {
    throw httpError(502, `GitHub Actions job logs request failed: ${error instanceof Error ? error.message : String(error)}`);
  }
  if (response.status >= 300 && response.status < 400) {
    const location = response.headers.get("location");
    if (!location) throw httpError(502, "GitHub Actions job logs returned a redirect without a download URL");
    const output = buildString([
      `GitHub Actions job ${jobId} logs are available from a short-lived download URL.`,
      location,
    ]);
    return { output, summary: `Retrieved a temporary log download URL for GitHub Actions job ${jobId}`, detail: output };
  }
  const text = await response.text();
  if (!response.ok) {
    const message = githubProviderMessage(text) || `HTTP ${response.status}`;
    throw httpError(502, `GitHub Actions job logs failed with HTTP ${response.status}: ${truncate(message, 1_000)}`);
  }
  const contentType = response.headers.get("content-type") || "";
  if (/application\/(?:zip|octet-stream)/i.test(contentType) || text.includes("\0")) {
    throw httpError(502, "GitHub Actions job logs returned a binary archive without a download redirect");
  }
  const output = truncate(buildString([
    `GitHub Actions job ${jobId} logs:`,
    text || "No log output was returned.",
  ]));
  return { output, summary: `Read GitHub Actions job ${jobId} logs`, detail: output };
}

async function githubBranchProtection(baseUrl, token, fetchImpl, repository, args, signal) {
  const branch = githubBranch(args.branch);
  const protection = await githubBranchProtectionData(baseUrl, token, fetchImpl, repository, branch, signal);
  if (!protection) {
    const output = `No GitHub branch protection configuration was returned for ${repository.slug}:${branch}.`;
    return { output, summary: `No branch protection configured for ${repository.slug}:${branch}`, detail: output };
  }
  const report = formatBranchProtection(protection);
  const output = buildString([`${repository.slug}:${branch}`, report]);
  return { output: truncate(output), summary: `Read branch protection for ${repository.slug}:${branch}`, detail: truncate(output) };
}

async function githubBranchProtectionData(baseUrl, token, fetchImpl, repository, branch, signal) {
  return requestJsonAllowNotFound(
    fetchImpl,
    githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "branches", branch, "protection"),
    { headers: githubHeaders(token), signal },
    "GitHub branch protection",
  );
}

async function githubRepositoryRulesets(baseUrl, token, fetchImpl, repository, signal) {
  const body = await requestJson(
    fetchImpl,
    githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "rulesets"),
    { headers: githubHeaders(token), signal },
    "GitHub repository rulesets",
  );
  const rulesets = requiredArray(body, "GitHub repository rulesets").slice(0, 100);
  const output = rulesets.length
    ? rulesets.map((ruleset, index) => buildString([
      `${index + 1}. ${ruleset.name || "Unnamed ruleset"}`,
      [
        Number.isInteger(ruleset.id) ? `ruleset_id=${ruleset.id}` : "",
        ruleset.target ? `target=${ruleset.target}` : "",
        ruleset.enforcement ? `enforcement=${ruleset.enforcement}` : "",
        ruleset.source_type ? `source_type=${ruleset.source_type}` : "",
      ].filter(Boolean).join(" "),
      Array.isArray(ruleset.conditions?.ref_name?.include) ? `include=${ruleset.conditions.ref_name.include.join(", ")}` : "",
      Array.isArray(ruleset.conditions?.ref_name?.exclude) ? `exclude=${ruleset.conditions.ref_name.exclude.join(", ")}` : "",
      Array.isArray(ruleset.rules) ? `rules=${ruleset.rules.map((rule) => rule?.type).filter(Boolean).join(", ") || "none"}` : "",
    ])).join("\n\n")
    : `No repository rulesets found for ${repository.slug}`;
  return {
    output: truncate(output),
    summary: `Read ${rulesets.length} GitHub repository ruleset${rulesets.length === 1 ? "" : "s"} from ${repository.slug}`,
    detail: truncate(output),
  };
}

async function githubCreateIssue(baseUrl, token, fetchImpl, repository, args, signal) {
  const title = requiredString(args, "title", 256);
  const bodyText = optionalString(args.body, "", 65_536);
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["issues"],
    { title, ...(bodyText ? { body: bodyText } : {}) },
    signal,
    "GitHub issue creation",
  );
  if (!Number.isInteger(body.number) || body.number < 1) {
    throw httpError(502, "GitHub issue creation returned an unexpected response shape");
  }
  const output = buildString([
    `Created ${repository.slug}#${body.number}: ${body.title || title}`,
    body.html_url,
    `state=${body.state || "open"}`,
  ]);
  return { output, summary: `Created GitHub issue ${repository.slug}#${body.number}`, detail: output };
}

async function githubCreatePullRequest(baseUrl, token, fetchImpl, repository, args, signal) {
  const title = requiredString(args, "title", 256);
  const head = requiredString(args, "head", 255);
  const base = requiredString(args, "base", 255);
  const bodyText = optionalString(args.body, "", 65_536);
  const draft = optionalBoolean(args.draft, false, "draft");
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["pulls"],
    { title, head, base, ...(bodyText ? { body: bodyText } : {}), draft },
    signal,
    "GitHub pull request creation",
  );
  if (!Number.isInteger(body.number) || body.number < 1 || typeof body.html_url !== "string") {
    throw httpError(502, "GitHub pull request creation returned an unexpected response shape");
  }
  const output = buildString([
    `Created pull request ${repository.slug}#${body.number}: ${body.title || title}`,
    body.html_url,
    `state=${body.state || "open"}`,
    `draft=${typeof body.draft === "boolean" ? body.draft : draft}`,
    body.head?.sha ? `head_sha=${body.head.sha}` : "",
  ]);
  return { output, summary: `Created GitHub pull request ${repository.slug}#${body.number}`, detail: output };
}

async function githubAddComment(baseUrl, token, fetchImpl, repository, number, args, signal) {
  const comment = requiredString(args, "body", 65_536);
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["issues", String(number), "comments"],
    { body: comment },
    signal,
    "GitHub comment creation",
  );
  if (!Number.isInteger(body.id) || body.id < 1 || typeof body.html_url !== "string") {
    throw httpError(502, "GitHub comment creation returned an unexpected response shape");
  }
  const output = buildString([
    `Commented on ${repository.slug}#${number}`,
    body.html_url,
    body.user?.login ? `author=${body.user.login}` : "",
  ]);
  return { output, summary: `Commented on GitHub item ${repository.slug}#${number}`, detail: output };
}

async function githubSetIssueState(baseUrl, token, fetchImpl, repository, number, args, signal) {
  const state = optionalEnum(args.state, null, ["open", "closed"]);
  if (!state) throw httpError(400, "state is required");
  const stateReason = optionalEnum(args.state_reason, "", ["completed", "not_planned", "reopened"]);
  if (state === "open" && stateReason && stateReason !== "reopened") {
    throw httpError(400, "An open issue can only use state_reason=reopened");
  }
  if (state === "closed" && stateReason === "reopened") {
    throw httpError(400, "A closed issue cannot use state_reason=reopened");
  }
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["issues", String(number)],
    { state, ...(stateReason ? { state_reason: stateReason } : {}) },
    signal,
    "GitHub issue state update",
    "PATCH",
  );
  if (!Number.isInteger(body.number) || body.number < 1 || !["open", "closed"].includes(body.state)) {
    throw httpError(502, "GitHub issue state update returned an unexpected response shape");
  }
  const output = buildString([
    `Updated ${repository.slug}#${body.number || number}`,
    body.html_url,
    `state=${body.state || state}`,
    body.state_reason ? `state_reason=${body.state_reason}` : "",
  ]);
  return { output, summary: `Updated GitHub item ${repository.slug}#${body.number || number}`, detail: output };
}

async function githubSubmitPullRequestReview(baseUrl, token, fetchImpl, repository, number, args, signal) {
  const event = optionalEnum(args.event, null, ["APPROVE", "REQUEST_CHANGES", "COMMENT"]);
  if (!event) throw httpError(400, "event is required");
  const reviewBody = optionalString(args.body, "", 65_536);
  const commitId = args.commit_id === undefined || args.commit_id === null || args.commit_id === ""
    ? ""
    : githubCommitSha(args.commit_id, "commit_id");
  const comments = githubReviewCommentList(args.comments);
  if (event !== "APPROVE" && !reviewBody && !comments.length) {
    throw httpError(400, `body is required when event=${event}`);
  }
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["pulls", String(number), "reviews"],
    {
      event,
      ...(reviewBody ? { body: reviewBody } : {}),
      ...(commitId ? { commit_id: commitId } : {}),
      ...(comments.length ? { comments } : {}),
    },
    signal,
    "GitHub pull request review submission",
  );
  if (!Number.isInteger(body.id) || body.id < 1 || typeof body.state !== "string") {
    throw httpError(502, "GitHub pull request review submission returned an unexpected response shape");
  }
  const output = buildString([
    `Submitted ${event} review on ${repository.slug}#${number}`,
    body.html_url,
    `state=${body.state}`,
    body.commit_id ? `commit=${body.commit_id}` : "",
    comments.length ? `line_comments=${comments.length}` : "",
  ]);
  return { output, summary: `Submitted GitHub review on ${repository.slug}#${number}`, detail: output };
}

async function githubActionsManage(baseUrl, token, fetchImpl, repository, operation, args, signal) {
  const operations = {
    rerun_workflow: { id: "run", path: (id) => ["actions", "runs", String(id), "rerun"], label: "Reran workflow" },
    rerun_failed_jobs: { id: "run", path: (id) => ["actions", "runs", String(id), "rerun-failed-jobs"], label: "Reran failed jobs for workflow" },
    cancel_workflow: { id: "run", path: (id) => ["actions", "runs", String(id), "cancel"], label: "Cancelled workflow" },
    force_cancel_workflow: { id: "run", path: (id) => ["actions", "runs", String(id), "force-cancel"], label: "Force-cancelled workflow" },
    rerun_job: { id: "job", path: (id) => ["actions", "jobs", String(id), "rerun"], label: "Reran job" },
  };
  const action = operations[operation];
  const idName = `${action.id}_id`;
  const id = githubActionId(args[idName], idName);
  await requestJson(
    fetchImpl,
    githubApiUrl(baseUrl, "repos", repository.owner, repository.name, ...action.path(id)),
    { method: "POST", headers: githubHeaders(token), signal },
    `GitHub Actions ${operation}`,
  );
  const output = `${action.label} ${id} in ${repository.slug}`;
  return { output, summary: output, detail: output };
}

async function githubRequestReviewers(baseUrl, token, fetchImpl, repository, number, args, signal) {
  const reviewers = githubActorList(args.reviewers, "reviewers", 15);
  const teams = githubActorList(args.team_reviewers, "team_reviewers", 15);
  if (!reviewers.length && !teams.length) {
    throw httpError(400, "At least one reviewer or team_reviewer is required");
  }
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["pulls", String(number), "requested_reviewers"],
    { ...(reviewers.length ? { reviewers } : {}), ...(teams.length ? { team_reviewers: teams } : {}) },
    signal,
    "GitHub reviewer request",
  );
  if (!Number.isInteger(body.number) || body.number < 1) {
    throw httpError(502, "GitHub reviewer request returned an unexpected response shape");
  }
  const requestedUsers = Array.isArray(body.requested_reviewers)
    ? body.requested_reviewers.map((reviewer) => reviewer?.login).filter(Boolean)
    : reviewers;
  const requestedTeams = Array.isArray(body.requested_teams)
    ? body.requested_teams.map((team) => team?.slug).filter(Boolean)
    : teams;
  const output = buildString([
    `Requested reviewers for ${repository.slug}#${number}`,
    body.html_url,
    requestedUsers.length ? `reviewers=${requestedUsers.join(", ")}` : "",
    requestedTeams.length ? `team_reviewers=${requestedTeams.join(", ")}` : "",
  ]);
  return { output, summary: `Requested GitHub reviewers for ${repository.slug}#${number}`, detail: output };
}

async function githubPullRequestMergeReadiness(
  baseUrl,
  token,
  fetchImpl,
  repository,
  args,
  signal,
  providedPullRequest = null,
) {
  const number = githubNumber(args);
  const report = await githubPullRequestMergeReadinessData(
    baseUrl,
    token,
    fetchImpl,
    repository,
    number,
    signal,
    providedPullRequest,
  );
  const output = formatMergeReadiness(repository, number, report);
  return {
    output: truncate(output),
    summary: `GitHub pull request ${repository.slug}#${number} is ${report.ready ? "ready" : "not ready"} to merge`,
    detail: truncate(output),
    report,
  };
}

async function githubPullRequestMergeReadinessData(
  baseUrl,
  token,
  fetchImpl,
  repository,
  number,
  signal,
  providedPullRequest = null,
) {
  const pullRequest = providedPullRequest
    || await githubPullRequestMetadata(baseUrl, token, fetchImpl, repository, number, signal);
  const headSha = githubCommitSha(pullRequest.head?.sha, "GitHub pull request head SHA", 502);
  const baseBranch = githubBranch(pullRequest.base?.ref, "GitHub pull request base branch", 502);
  const [reviewData, protection] = await Promise.all([
    githubPullRequestReviewsForReadiness(baseUrl, token, fetchImpl, repository, number, signal),
    githubBranchProtectionData(baseUrl, token, fetchImpl, repository, baseBranch, signal),
  ]);
  const checkRuns = await githubCheckRunsForSha(baseUrl, token, fetchImpl, repository, headSha, 100, signal);
  const requiredChecks = githubRequiredChecks(protection);
  const needsCommitStatuses = requiredChecks.some((check) => check.appId === null);
  const commitStatuses = needsCommitStatuses
    ? await githubCommitStatuses(baseUrl, token, fetchImpl, repository, headSha, signal)
    : [];

  const reviewPolicy = protection?.required_pull_request_reviews || null;
  const requiredApprovals = Number.isInteger(reviewPolicy?.required_approving_review_count)
    ? Math.max(0, reviewPolicy.required_approving_review_count)
    : 0;
  const latestReviews = githubLatestReviews(reviewData.reviews);
  const dismissesStaleReviews = reviewPolicy?.dismiss_stale_reviews === true;
  const approvals = latestReviews.filter((review) => (
    review.state === "APPROVED"
      && (!dismissesStaleReviews || String(review.commit_id || "").toLowerCase() === headSha.toLowerCase())
  ));
  const changesRequested = latestReviews.filter((review) => review.state === "CHANGES_REQUESTED");
  const checkResults = requiredChecks.map((requiredCheck) => (
    githubRequiredCheckResult(requiredCheck, checkRuns, commitStatuses)
  ));
  const missingChecks = checkResults.filter((check) => check.state === "missing").map((check) => check.context);
  const pendingChecks = checkResults.filter((check) => check.state === "pending").map((check) => check.context);
  const failedChecks = checkResults.filter((check) => check.state === "failed").map((check) => check.context);
  const blockers = [];
  if (pullRequest.state !== "open" || pullRequest.merged === true) blockers.push("pull request is not open");
  if (pullRequest.draft === true) blockers.push("pull request is a draft");
  if (pullRequest.mergeable !== true) blockers.push(`mergeable=${String(pullRequest.mergeable)}`);
  const mergeableState = typeof pullRequest.mergeable_state === "string" ? pullRequest.mergeable_state : "unknown";
  if (["dirty", "blocked", "behind", "draft", "unknown", "unstable"].includes(mergeableState)) {
    blockers.push(`mergeable_state=${mergeableState}`);
  }
  if (!reviewData.complete) blockers.push("review history exceeded the bounded 1000-review audit window");
  if (changesRequested.length) {
    blockers.push(`changes requested by ${changesRequested.map((review) => review.user?.login || "unknown").join(", ")}`);
  }
  if (approvals.length < requiredApprovals) {
    blockers.push(`required approvals not met (${approvals.length}/${requiredApprovals})`);
  }
  if (missingChecks.length) blockers.push(`missing required checks: ${missingChecks.join(", ")}`);
  if (pendingChecks.length) blockers.push(`pending required checks: ${pendingChecks.join(", ")}`);
  if (failedChecks.length) blockers.push(`failed required checks: ${failedChecks.join(", ")}`);
  if (protection?.required_conversation_resolution?.enabled === true) {
    blockers.push("required conversation resolution cannot be proven through this REST audit");
  }
  return {
    ready: blockers.length === 0,
    headSha,
    baseBranch,
    state: pullRequest.state || "unknown",
    draft: pullRequest.draft === true,
    merged: pullRequest.merged === true,
    mergeable: pullRequest.mergeable === true,
    mergeableState,
    branchProtectionConfigured: Boolean(protection),
    reviewsInspected: reviewData.reviews.length,
    reviewHistoryComplete: reviewData.complete,
    requiredApprovals,
    approvals: approvals.map((review) => review.user?.login || "unknown"),
    changesRequested: changesRequested.map((review) => review.user?.login || "unknown"),
    requiredChecks: checkResults,
    missingChecks,
    pendingChecks,
    failedChecks,
    requiredConversationResolution: protection?.required_conversation_resolution?.enabled === true,
    blockers,
  };
}

async function githubPullRequestReviewsForReadiness(baseUrl, token, fetchImpl, repository, number, signal) {
  const reviews = [];
  for (let page = 1; page <= 10; page += 1) {
    const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "pulls", String(number), "reviews");
    url.searchParams.set("per_page", "100");
    if (page > 1) url.searchParams.set("page", String(page));
    const body = await requestJson(
      fetchImpl,
      url,
      { headers: githubHeaders(token), signal },
      "GitHub pull request reviews",
    );
    const pageReviews = requiredArray(body, "GitHub pull request reviews");
    reviews.push(...pageReviews);
    if (pageReviews.length < 100) return { reviews, complete: true };
  }
  return { reviews, complete: false };
}

async function githubCommitStatuses(baseUrl, token, fetchImpl, repository, headSha, signal) {
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "commits", headSha, "status");
  url.searchParams.set("per_page", "100");
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub commit statuses");
  return requiredArray(body?.statuses, "GitHub commit statuses").slice(0, 100);
}

function githubLatestReviews(reviews) {
  const latestByUser = new Map();
  for (const review of reviews) {
    const user = typeof review?.user?.login === "string" ? review.user.login.toLowerCase() : "";
    const state = typeof review?.state === "string" ? review.state.toUpperCase() : "";
    if (!user || state === "PENDING") continue;
    const current = latestByUser.get(user);
    const currentTime = Date.parse(current?.submitted_at || 0) || 0;
    const reviewTime = Date.parse(review.submitted_at || 0) || 0;
    if (!current || reviewTime >= currentTime) latestByUser.set(user, { ...review, state });
  }
  return [...latestByUser.values()].filter((review) => review.state !== "DISMISSED");
}

function githubRequiredChecks(protection) {
  const statusChecks = protection?.required_status_checks;
  if (!statusChecks) return [];
  const checks = [];
  const modernContexts = new Set();
  if (Array.isArray(statusChecks.checks)) {
    for (const check of statusChecks.checks) {
      if (typeof check?.context !== "string" || !check.context.trim()) continue;
      const context = check.context.trim();
      modernContexts.add(context);
      checks.push({
        context,
        appId: Number.isInteger(check.app_id) && check.app_id >= 0 ? check.app_id : null,
      });
    }
  }
  if (Array.isArray(statusChecks.contexts)) {
    for (const value of statusChecks.contexts) {
      if (typeof value !== "string" || !value.trim() || modernContexts.has(value.trim())) continue;
      checks.push({ context: value.trim(), appId: null });
    }
  }
  return checks;
}

function githubRequiredCheckResult(requiredCheck, checkRuns, commitStatuses) {
  const matchingRuns = checkRuns.filter((check) => (
    check?.name === requiredCheck.context
      && (requiredCheck.appId === null || requiredCheck.appId === check.app?.id)
  ));
  const latestRun = matchingRuns.sort((left, right) => (
    Date.parse(right.completed_at || right.started_at || 0) - Date.parse(left.completed_at || left.started_at || 0)
      || (Number(right.id) || 0) - (Number(left.id) || 0)
  ))[0];
  if (latestRun) {
    if (latestRun.status !== "completed") return { ...requiredCheck, state: "pending", source: "check_run" };
    const conclusion = typeof latestRun.conclusion === "string" ? latestRun.conclusion.toLowerCase() : "";
    return {
      ...requiredCheck,
      state: ["success", "neutral", "skipped"].includes(conclusion) ? "passed" : "failed",
      source: "check_run",
      conclusion: conclusion || "unknown",
    };
  }
  if (requiredCheck.appId === null) {
    const status = commitStatuses.find((item) => item?.context === requiredCheck.context);
    if (status) {
      const state = typeof status.state === "string" ? status.state.toLowerCase() : "unknown";
      return {
        ...requiredCheck,
        state: state === "success" ? "passed" : state === "pending" ? "pending" : "failed",
        source: "commit_status",
        conclusion: state,
      };
    }
  }
  return { ...requiredCheck, state: "missing", source: "none" };
}

function formatMergeReadiness(repository, number, report) {
  const requiredChecks = report.requiredChecks.length
    ? report.requiredChecks.map((check) => `${check.context}=${check.state}`).join(", ")
    : "none";
  return buildString([
    `${repository.slug}#${number} merge readiness`,
    `ready=${report.ready}`,
    `state=${report.state} draft=${report.draft} merged=${report.merged}`,
    `mergeable=${report.mergeable} mergeable_state=${report.mergeableState}`,
    `head_sha=${report.headSha}`,
    `base=${report.baseBranch} branch_protection=${report.branchProtectionConfigured ? "configured" : "not_configured"}`,
    `reviews_inspected=${report.reviewsInspected} review_history_complete=${report.reviewHistoryComplete}`,
    `approvals=${report.approvals.length}/${report.requiredApprovals}${report.approvals.length ? ` (${report.approvals.join(", ")})` : ""}`,
    `changes_requested=${report.changesRequested.length ? report.changesRequested.join(", ") : "none"}`,
    `required_checks=${requiredChecks}`,
    `required_conversation_resolution=${report.requiredConversationResolution}`,
    report.blockers.length ? `blockers:\n${report.blockers.map((blocker) => `- ${blocker}`).join("\n")}` : "blockers=none",
  ]);
}

async function githubMergePullRequest(baseUrl, token, fetchImpl, repository, number, args, signal) {
  const expectedHeadSha = githubCommitSha(requiredString(args, "expected_head_sha", 64), "expected_head_sha");
  const mergeMethod = optionalEnum(args.merge_method, null, ["merge", "squash", "rebase"]);
  if (!mergeMethod) throw httpError(400, "merge_method is required");
  const commitTitle = optionalString(args.commit_title, "", 256);
  const commitMessage = optionalString(args.commit_message, "", 65_536);
  const pullRequest = await githubPullRequestMetadata(baseUrl, token, fetchImpl, repository, number, signal);
  const liveHeadSha = githubCommitSha(pullRequest.head?.sha, "GitHub pull request head SHA", 502);
  if (liveHeadSha.toLowerCase() !== expectedHeadSha.toLowerCase()) {
    throw httpError(409, `Pull request head changed: expected ${expectedHeadSha}, found ${liveHeadSha}`);
  }
  if (pullRequest.merged === true || pullRequest.state !== "open") {
    throw httpError(409, `Pull request ${repository.slug}#${number} is not open and mergeable by this operation`);
  }
  const readiness = await githubPullRequestMergeReadinessData(
    baseUrl,
    token,
    fetchImpl,
    repository,
    number,
    signal,
    pullRequest,
  );
  if (!readiness.ready) {
    throw httpError(409, `Pull request ${repository.slug}#${number} is not ready to merge: ${readiness.blockers.join("; ")}`);
  }
  const body = await githubMutation(
    baseUrl,
    token,
    fetchImpl,
    repository,
    ["pulls", String(number), "merge"],
    {
      sha: expectedHeadSha,
      merge_method: mergeMethod,
      ...(commitTitle ? { commit_title: commitTitle } : {}),
      ...(commitMessage ? { commit_message: commitMessage } : {}),
    },
    signal,
    "GitHub pull request merge",
    "PUT",
  );
  if (body.merged !== true || typeof body.sha !== "string") {
    if (body.merged === false) {
      throw httpError(409, `GitHub did not merge ${repository.slug}#${number}: ${body.message || "merge rejected"}`);
    }
    throw httpError(502, "GitHub pull request merge returned an unexpected response shape");
  }
  const output = buildString([
    `Merged ${repository.slug}#${number}`,
    `merge_method=${mergeMethod}`,
    `approved_head_sha=${expectedHeadSha}`,
    `merge_commit_sha=${body.sha}`,
    body.message,
  ]);
  return { output, summary: `Merged GitHub pull request ${repository.slug}#${number}`, detail: output };
}

async function githubPullRequestMetadata(baseUrl, token, fetchImpl, repository, number, signal) {
  const body = await requestJson(
    fetchImpl,
    githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "pulls", String(number)),
    { headers: githubHeaders(token), signal },
    "GitHub pull request",
  );
  if (!body || typeof body !== "object" || Array.isArray(body) || !Number.isInteger(body.number)) {
    throw httpError(502, "GitHub pull request returned an unexpected response shape");
  }
  return body;
}

async function githubMutation(
  baseUrl,
  token,
  fetchImpl,
  repository,
  pathSegments,
  requestBody,
  signal,
  operation,
  method = "POST",
) {
  const body = await requestJson(
    fetchImpl,
    githubApiUrl(baseUrl, "repos", repository.owner, repository.name, ...pathSegments),
    {
      method,
      headers: { ...githubHeaders(token), "content-type": "application/json" },
      body: JSON.stringify(requestBody),
      signal,
    },
    operation,
  );
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw httpError(502, `${operation} returned an unexpected response shape`);
  }
  return body;
}

async function githubFile(baseUrl, token, fetchImpl, repository, args, signal) {
  const path = githubFilePath(args.path);
  const ref = args.ref === undefined || args.ref === null || args.ref === "" ? "" : requiredString(args, "ref", 500);
  const url = githubApiUrl(baseUrl, "repos", repository.owner, repository.name, "contents", ...path.segments);
  if (ref) url.searchParams.set("ref", ref);
  const body = await requestJson(fetchImpl, url, { headers: githubHeaders(token), signal }, "GitHub file read");
  if (!body || Array.isArray(body) || body.type !== "file" || typeof body.content !== "string") {
    throw httpError(502, "GitHub file read returned an unexpected response shape");
  }
  if (body.encoding && body.encoding !== "base64") {
    throw httpError(502, `GitHub file read returned unsupported encoding: ${body.encoding}`);
  }
  const encoded = body.content.replace(/\s/g, "");
  if (!encoded || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) {
    throw httpError(502, "GitHub file read returned invalid base64 content");
  }
  const bytes = Buffer.from(encoded, "base64");
  if (bytes.length > MAX_GITHUB_FILE_BYTES) {
    throw httpError(400, `GitHub file is too large to read (${bytes.length} bytes; maximum ${MAX_GITHUB_FILE_BYTES})`);
  }
  const metadata = [
    `${repository.slug}:${path.value}${ref ? `@${ref}` : ""}`,
    body.html_url,
    `size=${bytes.length}`,
  ];
  if (isBinary(bytes)) {
    const output = [...metadata, "", "Binary file content is not included."].filter(Boolean).join("\n");
    return { output, summary: `Read binary GitHub file ${repository.slug}:${path.value}`, detail: output };
  }
  const output = [...metadata, "", bytes.toString("utf8")].filter(Boolean).join("\n");
  return {
    output: truncate(output),
    summary: `Read GitHub file ${repository.slug}:${path.value}`,
    detail: truncate(output),
  };
}

function createLinearTool(env, fetchImpl) {
  const apiKey = setting(env, "LINEAR_API_KEY");
  const endpoint = setting(env, "LINEAR_API_URL") || "https://api.linear.app/graphql";
  return tool({
    name: "linear_search",
    catalogId: "linear",
    description: "Search Linear issues by title or description",
    parameters: objectSchema({
      query: stringSchema("Text to match in Linear issues"),
      limit: integerSchema("Maximum issues", 1, 20),
    }, ["query"]),
    available: Boolean(apiKey),
    unavailableReason: "Set LINEAR_API_KEY on the backend",
    requiredEnvironment: ["LINEAR_API_KEY"],
    execute: async (args, { signal }) => {
      const query = requiredString(args, "query", 1_000);
      const limit = boundedInteger(args.limit, 10, 1, 20);
      const body = await requestJson(fetchImpl, endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: apiKey,
        },
        body: JSON.stringify({
          query: `query SearchIssues($query: String!, $first: Int!) {
            issues(
              first: $first
              orderBy: updatedAt
              filter: { or: [
                { title: { containsIgnoreCase: $query } }
                { description: { containsIgnoreCase: $query } }
              ] }
            ) {
              nodes { identifier title url updatedAt state { name } team { name } }
            }
          }`,
          variables: { query, first: limit },
        }),
        signal,
      }, "Linear search");
      if (body?.errors?.length) throw upstreamGraphqlError("Linear search", body.errors);
      const results = requiredArray(body?.data?.issues?.nodes, "Linear search").map((issue) => ({
        title: [issue.identifier, issue.title].filter(Boolean).join(" "),
        url: issue.url,
        snippet: [issue.state?.name, issue.team?.name, issue.updatedAt].filter(Boolean).join(" | "),
      }));
      return searchResult("Linear issues", results);
    },
  });
}

function createNotionTool(env, fetchImpl) {
  const token = setting(env, "NOTION_TOKEN");
  const endpoint = setting(env, "NOTION_API_URL") || "https://api.notion.com/v1";
  const notionVersion = setting(env, "NOTION_VERSION") || "2025-09-03";
  return tool({
    name: "notion_search",
    catalogId: "notion",
    description: "Search pages and data sources shared with the configured Notion integration",
    parameters: objectSchema({
      query: stringSchema("Text to match in Notion page or data-source titles"),
      limit: integerSchema("Maximum results", 1, 20),
    }, ["query"]),
    available: Boolean(token),
    unavailableReason: "Set NOTION_TOKEN on the backend",
    requiredEnvironment: ["NOTION_TOKEN"],
    execute: async (args, { signal }) => {
      const query = requiredString(args, "query", 1_000);
      const limit = boundedInteger(args.limit, 10, 1, 20);
      const body = await requestJson(fetchImpl, `${endpoint.replace(/\/$/, "")}/search`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${token}`,
          "notion-version": notionVersion,
        },
        body: JSON.stringify({ query, page_size: limit }),
        signal,
      }, "Notion search");
      const results = requiredArray(body?.results, "Notion search").map((item) => ({
        title: notionTitle(item) || item.object || item.id,
        url: item.url,
        snippet: [item.object, item.last_edited_time].filter(Boolean).join(" | "),
      }));
      return searchResult("Notion", results);
    },
  });
}

function createJiraTool(env, fetchImpl) {
  const baseUrl = setting(env, "JIRA_BASE_URL");
  const auth = atlassianAuth(env, "JIRA");
  const available = Boolean(baseUrl && auth);
  return tool({
    name: "jira_search",
    catalogId: "jira",
    description: "Search Jira issues with JQL",
    parameters: objectSchema({
      jql: stringSchema("Jira Query Language expression"),
      limit: integerSchema("Maximum issues", 1, 50),
    }, ["jql"]),
    available,
    unavailableReason: "Set JIRA_BASE_URL and JIRA_EMAIL + JIRA_API_TOKEN, or JIRA_BEARER_TOKEN",
    requiredEnvironment: setting(env, "JIRA_BEARER_TOKEN")
      ? ["JIRA_BASE_URL", "JIRA_BEARER_TOKEN"]
      : ["JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN"],
    execute: async (args, { signal }) => {
      const jql = requiredString(args, "jql", 4_000);
      const limit = boundedInteger(args.limit, 20, 1, 50);
      const endpoint = `${baseUrl.replace(/\/$/, "")}/rest/api/3/search/jql`;
      const body = await requestJson(fetchImpl, endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          accept: "application/json",
          ...auth,
        },
        body: JSON.stringify({
          jql,
          maxResults: limit,
          fields: ["summary", "status", "assignee", "updated", "issuetype", "project"],
        }),
        signal,
      }, "Jira search");
      const results = requiredArray(body?.issues, "Jira search").map((issue) => ({
        title: [issue.key, issue.fields?.summary].filter(Boolean).join(" "),
        url: issue.key ? `${baseUrl.replace(/\/$/, "")}/browse/${encodeURIComponent(issue.key)}` : "",
        snippet: [
          issue.fields?.status?.name,
          issue.fields?.issuetype?.name,
          issue.fields?.assignee?.displayName,
          issue.fields?.updated,
        ].filter(Boolean).join(" | "),
      }));
      return searchResult("Jira issues", results);
    },
  });
}

function createConfluenceTool(env, fetchImpl) {
  const baseUrl = setting(env, "CONFLUENCE_BASE_URL");
  const auth = atlassianAuth(env, "CONFLUENCE");
  const available = Boolean(baseUrl && auth);
  return tool({
    name: "confluence_search",
    catalogId: "confluence",
    description: "Search Confluence content with CQL",
    parameters: objectSchema({
      cql: stringSchema("Confluence Query Language expression"),
      limit: integerSchema("Maximum results", 1, 50),
    }, ["cql"]),
    available,
    unavailableReason: "Set CONFLUENCE_BASE_URL and CONFLUENCE_EMAIL + CONFLUENCE_API_TOKEN, or CONFLUENCE_BEARER_TOKEN",
    requiredEnvironment: setting(env, "CONFLUENCE_BEARER_TOKEN")
      ? ["CONFLUENCE_BASE_URL", "CONFLUENCE_BEARER_TOKEN"]
      : ["CONFLUENCE_BASE_URL", "CONFLUENCE_EMAIL", "CONFLUENCE_API_TOKEN"],
    execute: async (args, { signal }) => {
      const cql = requiredString(args, "cql", 4_000);
      const limit = boundedInteger(args.limit, 20, 1, 50);
      const base = baseUrl.replace(/\/$/, "");
      const path = base.endsWith("/wiki") ? "/rest/api/search" : "/wiki/rest/api/search";
      const url = new URL(`${base}${path}`);
      url.searchParams.set("cql", cql);
      url.searchParams.set("limit", String(limit));
      const body = await requestJson(fetchImpl, url, {
        headers: { accept: "application/json", ...auth },
        signal,
      }, "Confluence search");
      const results = requiredArray(body?.results, "Confluence search").map((item) => {
        const webui = item.url || item._links?.webui || item.content?._links?.webui || "";
        return {
          title: item.title || item.content?.title || item.content?.id,
          url: absoluteProviderUrl(baseUrl, webui),
          snippet: stripMarkup(item.excerpt || item.body || item.content?.type || ""),
        };
      });
      return searchResult("Confluence", results);
    },
  });
}

function createGleanTool(env, fetchImpl) {
  const endpoint = setting(env, "GLEAN_SEARCH_ENDPOINT");
  const token = setting(env, "GLEAN_API_TOKEN");
  const available = Boolean(endpoint && token);
  return tool({
    name: "glean_search",
    catalogId: "glean",
    description: "Search the configured Glean deployment",
    parameters: objectSchema({
      query: stringSchema("Enterprise search query"),
      limit: integerSchema("Maximum results", 1, 20),
    }, ["query"]),
    available,
    unavailableReason: "Set GLEAN_SEARCH_ENDPOINT and GLEAN_API_TOKEN on the backend",
    requiredEnvironment: ["GLEAN_SEARCH_ENDPOINT", "GLEAN_API_TOKEN"],
    execute: async (args, { signal }) => {
      const query = requiredString(args, "query", 2_000);
      const limit = boundedInteger(args.limit, 10, 1, 20);
      const body = await requestJson(fetchImpl, requiredUrl(endpoint, "GLEAN_SEARCH_ENDPOINT"), {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ query, pageSize: limit }),
        signal,
      }, "Glean search");
      const rawResults = body?.results ?? body?.data?.results ?? body?.documents;
      requiredArray(rawResults, "Glean search");
      const results = rawResults.slice(0, limit).map((item) => {
        const document = item.document || item;
        return {
          title: document.title || item.title || document.name || document.id,
          url: document.url || item.url,
          snippet: stripMarkup(
            item.snippet?.text
              || item.snippet
              || document.snippet
              || document.description
              || item.description
              || "",
          ),
        };
      });
      return searchResult("Glean", results);
    },
  });
}

function createSupabaseTool(env, fetchImpl) {
  const baseUrl = setting(env, "SUPABASE_URL");
  const apiKey = setting(env, "SUPABASE_KEY");
  const tables = splitSetting(env, "SUPABASE_TABLES");
  const available = Boolean(baseUrl && apiKey && tables.length);
  const tableSchema = {
    type: "string",
    description: "Allowlisted table or view",
    ...(tables.length ? { enum: tables } : {}),
  };
  return tool({
    name: "supabase_query",
    catalogId: "supabase",
    description: "Read rows from an allowlisted Supabase table or view",
    parameters: objectSchema({
      table: tableSchema,
      select: stringSchema("PostgREST select expression, or *"),
      match: {
        type: "object",
        description: "Column equality filters",
        additionalProperties: { type: ["string", "number", "boolean"] },
      },
      limit: integerSchema("Maximum rows", 1, 100),
    }, ["table"]),
    available,
    unavailableReason: "Set SUPABASE_URL, SUPABASE_KEY, and comma-separated SUPABASE_TABLES on the backend",
    requiredEnvironment: ["SUPABASE_URL", "SUPABASE_KEY", "SUPABASE_TABLES"],
    execute: async (args, { signal }) => {
      const table = requiredString(args, "table", 200);
      if (!tables.includes(table)) throw httpError(400, `Supabase table is not allowlisted: ${table}`);
      const select = optionalString(args.select, "*", 2_000);
      const limit = boundedInteger(args.limit, 25, 1, 100);
      const match = args.match === undefined ? {} : args.match;
      if (!match || typeof match !== "object" || Array.isArray(match)) {
        throw httpError(400, "match must be an object");
      }
      const url = new URL(`/rest/v1/${encodeURIComponent(table)}`, ensureTrailingSlash(baseUrl));
      url.searchParams.set("select", select);
      url.searchParams.set("limit", String(limit));
      for (const [column, value] of Object.entries(match)) {
        if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(column)) throw httpError(400, `Invalid Supabase column: ${column}`);
        if (!["string", "number", "boolean"].includes(typeof value)) {
          throw httpError(400, `Supabase match value for '${column}' must be scalar`);
        }
        url.searchParams.set(column, `eq.${String(value)}`);
      }
      const schema = setting(env, "SUPABASE_SCHEMA");
      const body = await requestJson(fetchImpl, url, {
        headers: {
          accept: "application/json",
          apikey: apiKey,
          authorization: `Bearer ${apiKey}`,
          ...(schema ? { "accept-profile": schema } : {}),
        },
        signal,
      }, "Supabase query");
      const rows = requiredArray(body, "Supabase query");
      const output = truncate(JSON.stringify(rows, null, 2));
      return {
        output,
        summary: `Read ${rows.length} Supabase row${rows.length === 1 ? "" : "s"} from ${table}`,
        detail: output,
      };
    },
  });
}

function createSubagentTool(modelGateway) {
  const available = typeof modelGateway?.stream === "function";
  return tool({
    name: "subagent",
    catalogId: "subagent",
    description: "Delegate one bounded specialist analysis task to a separate model call without tools. Use only when independent research, review, test, security, or planning work reduces parent-agent context",
    parameters: objectSchema({
      task: stringSchema("Self-contained task for the delegated model"),
      context: stringSchema("Optional context the delegated model needs"),
      role: enumSchema("Specialist role", ["research", "review", "test", "security", "planner"]),
      expected_output: stringSchema("Optional concise output contract for the specialist"),
      max_output_tokens: integerSchema("Maximum specialist output tokens", 1024, 16000),
    }, ["task"]),
    available,
    unavailableReason: "The backend model gateway is not configured",
    requiredEnvironment: ["MODEL"],
    execute: async (args, { signal }) => {
      const task = requiredString(args, "task", 12_000);
      const context = optionalString(args.context, "", 30_000);
      const role = optionalEnum(args.role, "research", ["research", "review", "test", "security", "planner"]);
      const expectedOutput = optionalString(args.expected_output, "", 4_000);
      const maxOutputTokens = boundedInteger(args.max_output_tokens, 4_096, 1_024, 16_000);
      let output = "";
      const turn = await modelGateway.stream({
        model: modelGateway.defaultModel,
        tools: [],
        messages: delegatedSubagentMessages({ task, context, role, expectedOutput }),
        maxOutputTokens,
        signal,
        onTextDelta: (delta) => {
          output += delta || "";
        },
      });
      if (!output.trim() && typeof turn?.content === "string") output = turn.content;
      output = truncate(output.trim());
      if (!output) throw httpError(502, "Subagent model returned an empty response");
      return { output, summary: `${role} subagent completed`, detail: output };
    },
  });
}

function tool(definition) {
  const risk = definition.risk || "read_only";
  if (!["read_only", "local_state", "mutating"].includes(risk)) {
    throw new Error(`Unsupported backend tool risk: ${risk}`);
  }
  return {
    ...definition,
    risk,
    available: Boolean(definition.available),
    execute: definition.execute || (async () => {
      throw httpError(503, definition.unavailableReason || "Tool is unavailable");
    }),
  };
}

function objectSchema(properties, required = []) {
  return {
    type: "object",
    properties,
    ...(required.length ? { required } : {}),
    additionalProperties: false,
  };
}

function stringSchema(description) {
  return { type: "string", description };
}

function booleanSchema(description) {
  return { type: "boolean", description };
}

function stringArraySchema(description, maxItems) {
  return {
    type: "array",
    description,
    items: { type: "string" },
    minItems: 1,
    maxItems,
    uniqueItems: true,
  };
}

function githubReviewCommentsSchema() {
  return {
    type: "array",
    description: "Optional line-level comments for submit_pull_request_review; each needs path, body, and either position or line plus side",
    minItems: 1,
    maxItems: 50,
    items: objectSchema({
      path: stringSchema("Repository-relative file path"),
      body: stringSchema("Line-level review comment body"),
      position: integerSchema("Diff position for an older GitHub review-comment form", 1, 1_000_000),
      line: integerSchema("Line number in the pull-request diff", 1, 1_000_000),
      side: enumSchema("Diff side for line; LEFT or RIGHT", ["LEFT", "RIGHT"]),
      start_line: integerSchema("Optional first line for a multi-line comment", 1, 1_000_000),
      start_side: enumSchema("Diff side for start_line; LEFT or RIGHT", ["LEFT", "RIGHT"]),
    }, ["path", "body"]),
  };
}

function integerSchema(description, minimum, maximum) {
  return { type: "integer", description, minimum, maximum };
}

function enumSchema(description, values) {
  return { type: "string", description, enum: values };
}

function requiredString(args, name, maxLength) {
  const value = args[name];
  if (typeof value !== "string" || !value.trim()) throw httpError(400, `${name} is required`);
  if (value.length > maxLength) throw httpError(400, `${name} must be at most ${maxLength} characters`);
  return value.trim();
}

function optionalString(value, fallback, maxLength) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value !== "string") throw httpError(400, "Expected a string value");
  if (value.length > maxLength) throw httpError(400, `String value must be at most ${maxLength} characters`);
  return value.trim();
}

function optionalBoolean(value, fallback, name) {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "boolean") throw httpError(400, `${name} must be a boolean`);
  return value;
}

function optionalEnum(value, fallback, allowed) {
  if (value === undefined || value === null || value === "") return fallback;
  if (!allowed.includes(value)) throw httpError(400, `Expected one of: ${allowed.join(", ")}`);
  return value;
}

function githubHeaders(token) {
  return {
    accept: "application/vnd.github+json",
    authorization: `Bearer ${token}`,
    "x-github-api-version": "2022-11-28",
    "user-agent": "CodeAgent",
  };
}

function githubApiUrl(baseUrl, ...segments) {
  const url = requiredUrl(baseUrl, "GITHUB_API_URL");
  const prefix = url.pathname.replace(/\/+$/, "");
  url.pathname = `${prefix}/${segments.map(encodeURIComponent).join("/")}`;
  url.search = "";
  url.hash = "";
  return url;
}

function githubRepository(value) {
  if (typeof value !== "string") throw httpError(400, "repository is required in owner/name form");
  const normalized = value.trim();
  const match = /^([A-Za-z0-9_.-]{1,100})\/([A-Za-z0-9_.-]{1,100})$/.exec(normalized);
  if (!match || match[1] === "." || match[1] === ".." || match[2] === "." || match[2] === "..") {
    throw httpError(400, "repository must use owner/name form");
  }
  return { owner: match[1], name: match[2], slug: normalized };
}

function githubFilePath(value) {
  if (typeof value !== "string") throw httpError(400, "path is required");
  const normalized = value.trim();
  if (!normalized || normalized.length > 1_000 || normalized.includes("\\0")) {
    throw httpError(400, "path is invalid");
  }
  const segments = normalized.split("/");
  if (segments.some((segment) => !segment || segment === "." || segment === "..")) {
    throw httpError(400, "path must stay within the repository");
  }
  return { value: normalized, segments };
}

function githubNumber(args) {
  const number = boundedInteger(args.number, null, 1, 1_000_000);
  if (number === null) throw httpError(400, "number is required");
  return number;
}

function githubActionId(value, name) {
  const id = boundedInteger(value, null, 1, Number.MAX_SAFE_INTEGER);
  if (id === null) throw httpError(400, `${name} is required`);
  return id;
}

function githubBranch(value, name = "branch", statusCode = 400) {
  if (typeof value !== "string") throw httpError(statusCode, `${name} is required`);
  const branch = value.trim();
  if (!branch || branch.length > 255 || /[\0-\x20~^:?*\\[\\]/.test(branch) || branch.includes("..") || branch.endsWith(".") || branch.endsWith("/")) {
    throw httpError(statusCode, `${name} is invalid`);
  }
  return branch;
}

function githubCommitSha(value, name, statusCode = 400) {
  if (typeof value !== "string" || !/^(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})$/.test(value)) {
    throw httpError(statusCode, `${name} must be a 40- or 64-character hexadecimal commit SHA`);
  }
  return value;
}

function githubActorList(value, name, maxItems) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length < 1 || value.length > maxItems) {
    throw httpError(400, `${name} must contain 1 to ${maxItems} entries`);
  }
  const actors = value.map((actor) => {
    if (typeof actor !== "string" || !/^[A-Za-z0-9_.-]{1,100}$/.test(actor)) {
      throw httpError(400, `${name} entries must be GitHub login or team-slug values`);
    }
    return actor;
  });
  if (new Set(actors).size !== actors.length) throw httpError(400, `${name} must not contain duplicates`);
  return actors;
}

function githubReviewCommentList(value) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length < 1 || value.length > 50) {
    throw httpError(400, "comments must contain 1 to 50 entries");
  }
  return value.map((comment, index) => {
    const prefix = `comments[${index}]`;
    if (!comment || typeof comment !== "object" || Array.isArray(comment)) {
      throw httpError(400, `${prefix} must be an object`);
    }
    const allowed = new Set(["path", "body", "position", "line", "side", "start_line", "start_side"]);
    if (Object.keys(comment).some((key) => !allowed.has(key))) {
      throw httpError(400, `${prefix} contains an unsupported field`);
    }
    const path = githubFilePath(comment.path).value;
    const body = requiredString(comment, "body", 65_536);
    const position = optionalPositiveInteger(comment.position, `${prefix}.position`);
    const line = optionalPositiveInteger(comment.line, `${prefix}.line`);
    const side = optionalEnum(comment.side, "", ["LEFT", "RIGHT"]);
    const startLine = optionalPositiveInteger(comment.start_line, `${prefix}.start_line`);
    const startSide = optionalEnum(comment.start_side, "", ["LEFT", "RIGHT"]);
    if (position !== null && line !== null) {
      throw httpError(400, `${prefix} cannot set both position and line`);
    }
    if (position === null && line === null) {
      throw httpError(400, `${prefix} requires position or line`);
    }
    if (line !== null && !side) throw httpError(400, `${prefix}.side is required with line`);
    if (position !== null && (side || startLine !== null || startSide)) {
      throw httpError(400, `${prefix} position comments cannot set side, start_line, or start_side`);
    }
    if (startLine !== null && !startSide) throw httpError(400, `${prefix}.start_side is required with start_line`);
    if (startLine === null && startSide) throw httpError(400, `${prefix}.start_line is required with start_side`);
    if (startLine !== null && (line === null || startLine >= line)) {
      throw httpError(400, `${prefix}.start_line must be before line`);
    }
    return {
      path,
      body,
      ...(position !== null ? { position } : {}),
      ...(line !== null ? { line, side } : {}),
      ...(startLine !== null ? { start_line: startLine, start_side: startSide } : {}),
    };
  });
}

function optionalPositiveInteger(value, name) {
  if (value === undefined || value === null) return null;
  if (!Number.isInteger(value) || value < 1 || value > 1_000_000) {
    throw httpError(400, `${name} must be an integer from 1 to 1000000`);
  }
  return value;
}

function formatBranchProtection(protection) {
  const statusChecks = protection?.required_status_checks || null;
  const reviewPolicy = protection?.required_pull_request_reviews || null;
  const requiredChecks = githubRequiredChecks(protection).map((check) => check.context);
  return buildString([
    `required_checks=${requiredChecks.length ? requiredChecks.join(", ") : "none"}`,
    statusChecks?.strict === true ? "require_branches_up_to_date=true" : "",
    `required_approvals=${Number.isInteger(reviewPolicy?.required_approving_review_count) ? reviewPolicy.required_approving_review_count : 0}`,
    reviewPolicy?.dismiss_stale_reviews === true ? "dismiss_stale_reviews=true" : "",
    reviewPolicy?.require_code_owner_reviews === true ? "require_code_owner_reviews=true" : "",
    protection?.required_conversation_resolution?.enabled === true ? "required_conversation_resolution=true" : "",
    protection?.enforce_admins?.enabled === true ? "enforce_admins=true" : "",
    protection?.required_linear_history?.enabled === true ? "required_linear_history=true" : "",
    protection?.required_signatures?.enabled === true ? "required_signatures=true" : "",
  ]);
}

function firstLine(value) {
  return typeof value === "string" ? value.split(/\r?\n/, 1)[0].trim() : "";
}

function isBinary(bytes) {
  return bytes.includes(0);
}

function buildString(values) {
  return values.filter((value) => typeof value === "string" && value.length > 0).join("\n");
}

function boundedInteger(value, fallback, minimum, maximum) {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw httpError(400, `Expected an integer from ${minimum} to ${maximum}`);
  }
  return value;
}

function normalizeExecutionResult(result) {
  if (!result || typeof result !== "object" || typeof result.output !== "string") {
    throw httpError(502, "Backend tool returned an invalid result");
  }
  return {
    output: truncate(result.output),
    summary: typeof result.summary === "string" && result.summary.trim()
      ? result.summary.trim()
      : "Backend tool completed",
    detail: typeof result.detail === "string" ? truncate(result.detail) : undefined,
  };
}

function normalizeSearchItems(items, limit) {
  return items.slice(0, limit).map((item) => ({
    title: item?.title || item?.name || item?.url || item?.link || "Untitled result",
    url: item?.url || item?.link || "",
    snippet: stripMarkup(item?.snippet || item?.description || item?.content || ""),
  }));
}

function requiredArray(value, operation) {
  if (!Array.isArray(value)) throw httpError(502, `${operation} returned an unexpected response shape`);
  return value;
}

function searchResult(label, results) {
  const output = results.length
    ? results.map((result, index) => [
      `${index + 1}. ${result.title || "Untitled result"}`,
      result.url || "",
      result.snippet || "",
    ].filter(Boolean).join("\n")).join("\n\n")
    : `No ${label} results`;
  return {
    output: truncate(output),
    summary: `Found ${results.length} ${label} result${results.length === 1 ? "" : "s"}`,
    detail: truncate(output),
  };
}

async function requestJson(fetchImpl, url, options, operation) {
  let response;
  try {
    response = await fetchImpl(url, options);
  } catch (error) {
    throw httpError(502, `${operation} request failed: ${error instanceof Error ? error.message : String(error)}`);
  }
  const text = await response.text();
  let body = {};
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      if (response.ok) throw httpError(502, `${operation} returned invalid JSON`);
    }
  }
  if (!response.ok) {
    const providerMessage = githubProviderMessage(body, text) || `HTTP ${response.status}`;
    const error = httpError(502, `${operation} failed with HTTP ${response.status}: ${truncate(String(providerMessage), 1_000)}`);
    error.providerStatus = response.status;
    throw error;
  }
  return body;
}

async function requestJsonAllowNotFound(fetchImpl, url, options, operation) {
  try {
    return await requestJson(fetchImpl, url, options, operation);
  } catch (error) {
    if (error?.providerStatus === 404) return null;
    throw error;
  }
}

function githubProviderMessage(body, fallback = "") {
  if (typeof body === "string") {
    try {
      return githubProviderMessage(JSON.parse(body), body);
    } catch {
      return body;
    }
  }
  return body?.message || body?.error?.message || body?.error || fallback || "";
}

function upstreamGraphqlError(operation, errors) {
  const message = errors.map((error) => error?.message).filter(Boolean).join("; ") || "Unknown GraphQL error";
  return httpError(502, `${operation} failed: ${truncate(message, 1_000)}`);
}

function notionTitle(item) {
  if (Array.isArray(item?.title)) return richText(item.title);
  for (const property of Object.values(item?.properties || {})) {
    if (property?.type === "title" && Array.isArray(property.title)) return richText(property.title);
  }
  return "";
}

function richText(parts) {
  return parts.map((part) => part?.plain_text || part?.text?.content || "").join("").trim();
}

function atlassianAuth(env, prefix) {
  const bearer = setting(env, `${prefix}_BEARER_TOKEN`);
  if (bearer) return { authorization: `Bearer ${bearer}` };
  const email = setting(env, `${prefix}_EMAIL`);
  const token = setting(env, `${prefix}_API_TOKEN`);
  if (!email || !token) return null;
  return { authorization: `Basic ${Buffer.from(`${email}:${token}`).toString("base64")}` };
}

function jsonAuthHeaders(apiKey, headerName) {
  const headers = { "content-type": "application/json", accept: "application/json" };
  if (!apiKey) return headers;
  const name = headerName || "Authorization";
  headers[name] = name.toLowerCase() === "authorization" ? `Bearer ${apiKey}` : apiKey;
  return headers;
}

function absoluteProviderUrl(baseUrl, path) {
  if (!path) return "";
  try {
    return new URL(path, ensureTrailingSlash(baseUrl)).toString();
  } catch {
    return path;
  }
}

function stripMarkup(value) {
  return String(value || "")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function requiredUrl(value, name) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw httpError(500, `${name} must be an absolute http(s) URL`);
  }
  if (!["http:", "https:"].includes(url.protocol)) throw httpError(500, `${name} must use http or https`);
  return url;
}

function ensureTrailingSlash(value) {
  return value.endsWith("/") ? value : `${value}/`;
}

function splitSetting(env, name) {
  return setting(env, name).split(",").map((value) => value.trim()).filter(Boolean);
}

function setting(env, name) {
  return typeof env?.[name] === "string" ? env[name].trim() : "";
}

function truncate(value, maxLength = MAX_OUTPUT_CHARS) {
  const text = String(value || "");
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}\n...[truncated]`;
}

function httpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}
