import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import {
  createIntegrationToolRegistryFromEnv,
} from "../src/integration-tools.mjs";
import { createCodeAgentServer } from "../src/server.mjs";

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
        head: { ref: "feature" },
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

  assert.match(issue.output, /Issue body/);
  assert.match(issue.output, /labels=bug/);
  assert.match(pullRequest.output, /merged=true/);
  assert.match(pullRequest.output, /changed_files=3/);
  assert.match(file.output, /fun main\(\) = Unit/);
  assert.match(binary.output, /Binary file content is not included/);
  assert.match(pullRequestFiles.output, /src\/main\.kt/);
  assert.match(pullRequestFiles.output, /additions=5/);
  assert.match(pullRequestFiles.output, /patch:/);
  assert.match(comments.output, /reviewer/);
  assert.match(comments.output, /Please cover the error path/);
  assert.equal(calls.length, 6);
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

  assert.match(issue.output, /Created codeagent\/idea#51/);
  assert.match(comment.output, /Commented on codeagent\/idea#51/);
  assert.match(state.output, /state_reason=completed/);
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    title: "Regression in login flow",
    body: "Steps to reproduce",
  });
  assert.deepEqual(JSON.parse(calls[1].options.body), { body: "Fixed by #52" });
  assert.deepEqual(JSON.parse(calls[2].options.body), { state: "closed", state_reason: "completed" });
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
  assert.equal(calls.length, 3);
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

  assert.equal(registry.list().filter((tool) => tool.available).length, 10);
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
