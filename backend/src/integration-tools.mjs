import { delegatedSubagentMessages } from "./prompt.mjs";

const MAX_OUTPUT_CHARS = 24_000;

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
    description: "Search GitHub repositories, issues and pull requests, or code",
    parameters: objectSchema({
      query: stringSchema("GitHub search query, including qualifiers when useful"),
      type: enumSchema("Search index", ["issues", "repositories", "code"]),
      limit: integerSchema("Maximum results", 1, 20),
    }, ["query"]),
    available: Boolean(token),
    unavailableReason: "Set GITHUB_TOKEN on the backend",
    requiredEnvironment: ["GITHUB_TOKEN"],
    execute: async (args, { signal }) => {
      const query = requiredString(args, "query", 2_000);
      const type = optionalEnum(args.type, "issues", ["issues", "repositories", "code"]);
      const limit = boundedInteger(args.limit, 10, 1, 20);
      const url = new URL(`${baseUrl.replace(/\/$/, "")}/search/${type}`);
      url.searchParams.set("q", query);
      url.searchParams.set("per_page", String(limit));
      const body = await requestJson(fetchImpl, url, {
        headers: {
          accept: "application/vnd.github+json",
          authorization: `Bearer ${token}`,
          "x-github-api-version": "2022-11-28",
          "user-agent": "CodeAgent/0.6",
        },
        signal,
      }, "GitHub search");
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
    },
  });
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
    description: "Delegate a bounded analysis task to a separate model call without tools",
    parameters: objectSchema({
      task: stringSchema("Self-contained task for the delegated model"),
      context: stringSchema("Optional context the delegated model needs"),
    }, ["task"]),
    available,
    unavailableReason: "The backend model gateway is not configured",
    requiredEnvironment: ["MODEL"],
    execute: async (args, { signal }) => {
      const task = requiredString(args, "task", 12_000);
      const context = optionalString(args.context, "", 30_000);
      let output = "";
      const turn = await modelGateway.stream({
        model: modelGateway.defaultModel,
        tools: [],
        messages: delegatedSubagentMessages({ task, context }),
        signal,
        onTextDelta: (delta) => {
          output += delta || "";
        },
      });
      if (!output.trim() && typeof turn?.content === "string") output = turn.content;
      output = truncate(output.trim());
      if (!output) throw httpError(502, "Subagent model returned an empty response");
      return { output, summary: "Subagent completed", detail: output };
    },
  });
}

function tool(definition) {
  return {
    ...definition,
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

function optionalEnum(value, fallback, allowed) {
  if (value === undefined || value === null || value === "") return fallback;
  if (!allowed.includes(value)) throw httpError(400, `Expected one of: ${allowed.join(", ")}`);
  return value;
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
    const providerMessage = body?.message || body?.error?.message || body?.error || text || `HTTP ${response.status}`;
    throw httpError(502, `${operation} failed with HTTP ${response.status}: ${truncate(String(providerMessage), 1_000)}`);
  }
  return body;
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
