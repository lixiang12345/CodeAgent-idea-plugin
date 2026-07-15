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

  assert.equal(registry.list().filter((tool) => tool.available).length, 9);
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
