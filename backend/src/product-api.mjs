import { randomUUID } from "node:crypto";
import { inlineCompletionMessages } from "./prompt.mjs";

const CONFIGURATION_KINDS = new Set(["mcp", "hooks", "commands", "agents", "plugins", "tool-permissions"]);
const AGENT_CONFIGURATION_DEFAULTS = {
  general: { maxTurns: 12, maxToolCalls: 48, maxSubagentCalls: 4, verificationPolicy: "after-mutation" },
  search: { maxTurns: 10, maxToolCalls: 24, maxSubagentCalls: 4, verificationPolicy: "none" },
  context: { maxTurns: 10, maxToolCalls: 24, maxSubagentCalls: 2, verificationPolicy: "none" },
  prompt: { maxTurns: 6, maxToolCalls: 12, maxSubagentCalls: 1, verificationPolicy: "none" },
  loop: { maxTurns: 24, maxToolCalls: 96, maxSubagentCalls: 6, verificationPolicy: "after-mutation" },
};

export function createRuntimeManifestFromEnv(env = process.env) {
  const raw = env.RUNTIME_MANIFEST_JSON?.trim();
  if (!raw) return { version: 1, runtimes: [] };
  const manifest = JSON.parse(raw);
  validateRuntimeManifest(manifest);
  return manifest;
}

export async function handlePublicProductRequest(request, response, { authenticator, runtimeManifest }) {
  if (request.url === "/v1/auth/config" && request.method === "GET") {
    return sendJson(response, 200, await authenticator.publicConfig());
  }
  if (typeof authenticator.handlePublicRequest === "function" &&
      await authenticator.handlePublicRequest(request, response)) return true;
  if (request.url === "/v1/runtime/manifest" && request.method === "GET") {
    return sendJson(response, 200, runtimeManifest);
  }
  return false;
}

export async function handleProductRequest(request, response, { principal, authenticator, store, jobRunner, modelGateway, readJson }) {
  const url = new URL(request.url, "http://codeagent.local");
  const path = url.pathname;

  if (path === "/v1/me" && request.method === "GET") {
    const user = await store.getUser(principal.id);
    const usage = await store.getUsage(principal.id);
    const session = await authenticator.sessionInfo(principal);
    return sendJson(response, 200, { user, usage, session });
  }

  if (path === "/v1/auth/logout" && request.method === "POST") {
    await authenticator.logout(principal);
    response.writeHead(204, { "cache-control": "no-store" });
    response.end();
    return true;
  }

  if (path === "/v1/usage" && request.method === "GET") {
    return sendJson(response, 200, { data: await store.getUsage(principal.id) });
  }

  if (path === "/v1/conversations" && request.method === "GET") {
    return sendJson(response, 200, { data: await store.listConversations(principal.id) });
  }
  if (path === "/v1/conversations" && request.method === "POST") {
    const body = await readJson(request);
    const conversation = normalizeConversation(body, body.id || randomUUID());
    const stored = await store.putConversation(principal.id, conversation);
    return sendJson(response, 201, stored);
  }

  const conversationMatch = path.match(/^\/v1\/conversations\/([^/]+)$/);
  if (conversationMatch) {
    const id = decodeURIComponent(conversationMatch[1]);
    if (request.method === "GET") {
      const conversation = await store.getConversation(principal.id, id);
      if (!conversation) throw notFound("Conversation not found");
      return sendJson(response, 200, conversation);
    }
    if (request.method === "PUT") {
      const body = await readJson(request);
      const expectedVersion = parseExpectedVersion(request.headers["if-match"]);
      const stored = await store.putConversation(principal.id, normalizeConversation(body, id), expectedVersion);
      return sendJson(response, 200, stored);
    }
    if (request.method === "DELETE") {
      if (!await store.deleteConversation(principal.id, id)) throw notFound("Conversation not found");
      response.writeHead(204);
      response.end();
      return true;
    }
  }

  if (path === "/v1/jobs" && request.method === "GET") {
    const limit = boundedInteger(url.searchParams.get("limit"), 1, 100, 50);
    return sendJson(response, 200, { data: await store.listJobs(principal.id, limit) });
  }
  if (path === "/v1/jobs" && request.method === "POST") {
    const job = await jobRunner.create(principal.id, await readJson(request));
    return sendJson(response, 202, job);
  }

  const jobMatch = path.match(/^\/v1\/jobs\/([^/]+)$/);
  if (jobMatch) {
    const id = decodeURIComponent(jobMatch[1]);
    if (request.method === "GET") {
      const job = await store.getJob(principal.id, id);
      if (!job) throw notFound("Job not found");
      return sendJson(response, 200, job);
    }
    if (request.method === "DELETE") {
      const job = await jobRunner.cancel(principal.id, id);
      if (!job) throw notFound("Job not found");
      return sendJson(response, 202, job);
    }
  }

  const configurationListMatch = path.match(/^\/v1\/configurations\/([^/]+)$/);
  if (configurationListMatch && request.method === "GET") {
    const kind = configurationKind(configurationListMatch[1]);
    return sendJson(response, 200, { data: await store.listConfigurations(principal.id, kind) });
  }

  const configurationMatch = path.match(/^\/v1\/configurations\/([^/]+)\/([^/]+)$/);
  if (configurationMatch) {
    const kind = configurationKind(configurationMatch[1]);
    const id = configurationId(configurationMatch[2]);
    if (request.method === "PUT") {
      const body = await readJson(request);
      return sendJson(response, 200, await store.putConfiguration(principal.id, kind, id, normalizeConfiguration(kind, body)));
    }
    if (request.method === "DELETE") {
      if (!await store.deleteConfiguration(principal.id, kind, id)) throw notFound("Configuration not found");
      response.writeHead(204);
      response.end();
      return true;
    }
  }

  if (path === "/v1/completions" && request.method === "POST") {
    const body = await readJson(request);
    const prefix = boundedText(body.prefix, "prefix", 100_000, true);
    const suffix = boundedText(body.suffix || "", "suffix", 100_000, true);
    if (!prefix.trim()) throw badRequest("prefix is required");
    const path = boundedText(body.path || "unknown", "path", 4_000, true);
    const language = boundedText(body.language || "unknown", "language", 100, true);
    let completion = "";
    const turn = await modelGateway.stream({
      model: typeof body.model === "string" ? body.model : undefined,
      messages: inlineCompletionMessages({ path, language, prefix, suffix }),
      tools: [],
      signal: AbortSignal.timeout(30_000),
      onTextDelta: (delta) => { completion += delta || ""; },
    });
    if (!completion && typeof turn?.content === "string") completion = turn.content;
    completion = completion.slice(0, 20_000);
    await store.recordUsage(principal.id, { kind: "completion", units: 1, metadata: { model: body.model || modelGateway.defaultModel || "" } });
    return sendJson(response, 200, { completion, model: body.model || modelGateway.defaultModel || "" });
  }

  return false;
}

function normalizeConversation(body, id) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw badRequest("Conversation is required");
  const messages = normalizeMessages(body.messages);
  const tasks = normalizeTasks(body.tasks);
  const totalMessageChars = messages.reduce((total, message) => total + message.content.length, 0);
  if (totalMessageChars > 2_000_000) throw badRequest("Conversation message content is too large");
  return {
    id: boundedText(id, "id", 200),
    title: boundedText(body.title || "New conversation", "title", 200),
    mode: ["agent", "chat", "ask"].includes(body.mode) ? body.mode : "agent",
    updatedAt: boundedTimestamp(body.updatedAt),
    selectedAgentProfileId: optionalText(body.selectedAgentProfileId, "selectedAgentProfileId", 120) || "general",
    selectedModelId: optionalText(body.selectedModelId, "selectedModelId", 240),
    selectedSkillIds: stringList(body.selectedSkillIds, "selectedSkillIds", 8, 500),
    selectedRuleIds: stringList(body.selectedRuleIds, "selectedRuleIds", 32, 500),
    pinned: body.pinned === true,
    summary: optionalText(body.summary, "summary", 20_000),
    messages,
    tasks,
  };
}

function normalizeMessages(value) {
  if (!Array.isArray(value) || value.length > 200) throw badRequest("messages must contain at most 200 items");
  const seen = new Set();
  return value.map((message) => {
    if (!message || typeof message !== "object" || Array.isArray(message)) throw badRequest("Message is invalid");
    const id = boundedText(message.id, "message.id", 200);
    if (seen.has(id)) throw badRequest("Message IDs must be unique");
    seen.add(id);
    if (!["user", "assistant"].includes(message.role)) throw badRequest("Message role is invalid");
    return {
      id,
      role: message.role,
      content: boundedText(message.content, "message.content", 100_000, true),
      createdAt: boundedTimestamp(message.createdAt),
    };
  });
}

function normalizeTasks(value) {
  if (!Array.isArray(value) || value.length > 100) throw badRequest("tasks must contain at most 100 items");
  const seen = new Set();
  return value.map((task) => {
    if (!task || typeof task !== "object" || Array.isArray(task)) throw badRequest("Task is invalid");
    const id = boundedText(task.id, "task.id", 200);
    if (seen.has(id)) throw badRequest("Task IDs must be unique");
    seen.add(id);
    if (!["not_started", "in_progress", "completed", "cancelled"].includes(task.state)) throw badRequest("Task state is invalid");
    return { id, name: boundedText(task.name, "task.name", 240), state: task.state };
  });
}

function stringList(value, field, maxItems, maxChars) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > maxItems) throw badRequest(`${field} has too many items`);
  const normalized = value.map((item) => boundedText(item, field, maxChars));
  if (new Set(normalized).size !== normalized.length) throw badRequest(`${field} must contain unique values`);
  return normalized;
}

function optionalText(value, field, max) {
  if (value === undefined || value === null || value === "") return null;
  return boundedText(value, field, max, true);
}

function boundedTimestamp(value) {
  const timestamp = value === undefined ? Date.now() : value;
  if (!Number.isSafeInteger(timestamp) || timestamp < 0) throw badRequest("updatedAt/createdAt must be a non-negative integer");
  return timestamp;
}


function configurationKind(value) {
  const kind = decodeURIComponent(value);
  if (!CONFIGURATION_KINDS.has(kind)) throw badRequest("Unsupported configuration kind");
  return kind;
}

function configurationId(value) {
  const id = decodeURIComponent(value);
  if (!/^[A-Za-z0-9._-]{1,120}$/.test(id)) {
    throw badRequest("Configuration ID must use 1-120 letters, numbers, dots, underscores, or hyphens");
  }
  return id;
}

function normalizeConfiguration(kind, value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw badRequest("Configuration value must be an object");
  }

  if (kind === "tool-permissions") {
    return {
      tool: boundedText(value.tool || value.name, "tool", 240),
      policy: enumValue(value.policy, "policy", ["ask", "allow", "deny"], "ask"),
      scope: enumValue(value.scope, "scope", ["workspace", "account"], "workspace"),
      enabled: optionalBoolean(value.enabled, true),
    };
  }

  const common = {
    name: boundedText(value.name, "name", 160),
    description: optionalText(value.description, "description", 2_000),
    enabled: optionalBoolean(value.enabled, true),
  };

  switch (kind) {
    case "commands":
      return {
        ...common,
        prompt: boundedText(value.prompt, "prompt", 100_000),
        argumentHint: optionalText(value.argumentHint, "argumentHint", 500),
      };
    case "hooks":
      return {
        ...common,
        event: enumValue(value.event, "event", ["before-run", "after-run", "before-tool", "after-tool", "on-error"]),
        command: boundedText(value.command, "command", 8_000),
        timeoutSeconds: boundedInteger(value.timeoutSeconds, 1, 600, 60),
      };
    case "agents": {
      const agentType = enumValue(value.agentType, "agentType", ["general", "search", "context", "prompt", "loop"], "general");
      const runtimeDefaults = AGENT_CONFIGURATION_DEFAULTS[agentType];
      const contextWindowTokens = boundedInteger(value.contextWindowTokens, 32_768, 2_000_000, 64_000);
      const reservedOutputTokens = boundedInteger(value.reservedOutputTokens, 1_024, 65_536, 8_192);
      if (reservedOutputTokens >= contextWindowTokens) {
        throw badRequest("reservedOutputTokens must be smaller than contextWindowTokens");
      }
      return {
        ...common,
        agentType,
        systemPrompt: optionalText(value.systemPrompt, "systemPrompt", 100_000),
        model: optionalText(value.model, "model", 240),
        allowedTools: stringList(value.allowedTools, "allowedTools", 128, 240),
        maxTurns: boundedInteger(value.maxTurns, 1, 64, runtimeDefaults.maxTurns),
        maxToolCalls: boundedInteger(value.maxToolCalls, 1, 256, runtimeDefaults.maxToolCalls),
        maxSubagentCalls: boundedInteger(value.maxSubagentCalls, 0, 16, runtimeDefaults.maxSubagentCalls),
        verificationPolicy: enumValue(value.verificationPolicy, "verificationPolicy", ["none", "after-mutation"], runtimeDefaults.verificationPolicy),
        contextWindowTokens,
        reservedOutputTokens,
      };
    }
    case "plugins":
      return {
        ...common,
        source: boundedText(value.source, "source", 2_000),
        version: optionalText(value.version, "version", 240),
        capabilities: stringList(value.capabilities, "capabilities", 128, 240),
      };
    case "mcp":
      return normalizeMcpConfiguration(value, common);
    default:
      throw badRequest("Unsupported configuration kind");
  }
}

function normalizeMcpConfiguration(value, common) {
  const transport = enumValue(value.transport, "transport", ["stdio", "streamable-http", "sse"], "stdio");
  const command = transport === "stdio" ? boundedText(value.command, "command", 4_000) : null;
  const url = transport === "stdio" ? null : normalizedRemoteUrl(value.url);
  const requiredEnvironment = stringList(value.requiredEnvironment, "requiredEnvironment", 64, 128);
  for (const name of requiredEnvironment) {
    if (!/^[A-Z][A-Z0-9_]{0,127}$/.test(name)) {
      throw badRequest("requiredEnvironment must contain uppercase environment variable names");
    }
  }
  const authMode = transport === "stdio"
    ? "none"
    : enumValue(value.authMode, "authMode", ["none", "bearer-environment"], "none");
  const tokenEnvironment = authMode === "bearer-environment"
    ? boundedText(value.tokenEnvironment, "tokenEnvironment", 128)
    : null;
  if (tokenEnvironment && !/^[A-Z][A-Z0-9_]{0,127}$/.test(tokenEnvironment)) {
    throw badRequest("tokenEnvironment must be an uppercase environment variable name");
  }
  return {
    ...common,
    transport,
    command,
    args: transport === "stdio" ? stringList(value.args, "args", 128, 2_000) : [],
    cwd: transport === "stdio" ? optionalText(value.cwd, "cwd", 4_000) : null,
    url,
    authMode,
    tokenEnvironment,
    requiredEnvironment,
    timeoutSeconds: boundedInteger(value.timeoutSeconds, 1, 600, 60),
  };
}

function enumValue(value, field, values, fallback) {
  const normalized = value === undefined || value === null || value === "" ? fallback : value;
  if (typeof normalized !== "string" || !values.includes(normalized)) {
    throw badRequest(`${field} must be one of: ${values.join(", ")}`);
  }
  return normalized;
}

function optionalBoolean(value, fallback) {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "boolean") throw badRequest("enabled must be a boolean");
  return value;
}

function normalizedRemoteUrl(value) {
  const text = boundedText(value, "url", 4_000);
  let url;
  try {
    url = new URL(text);
  } catch {
    throw badRequest("url must be a valid absolute URL");
  }
  const loopback = ["localhost", "127.0.0.1", "::1"].includes(url.hostname);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) {
    throw badRequest("Remote MCP URLs must use https; loopback http is allowed for local development");
  }
  if (url.username || url.password) throw badRequest("MCP URLs must not contain credentials");
  return url.toString();
}

function parseExpectedVersion(value) {
  if (value === undefined) return null;
  const normalized = String(value).replace(/^W\//, "").replaceAll('"', "");
  const parsed = Number.parseInt(normalized, 10);
  if (!Number.isInteger(parsed) || parsed < 0) throw badRequest("If-Match must be a non-negative conversation version");
  return parsed;
}

function boundedInteger(value, min, max, fallback) {
  if (value === null || value === undefined || value === "") return fallback;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) throw badRequest(`Value must be between ${min} and ${max}`);
  return parsed;
}

function boundedText(value, field, max, allowEmpty = false) {
  if (typeof value !== "string") throw badRequest(`${field} must be a string`);
  if (!allowEmpty && !value.trim()) throw badRequest(`${field} is required`);
  if (value.length > max) throw badRequest(`${field} is too long`);
  return value;
}

function validateRuntimeManifest(manifest) {
  if (!manifest || typeof manifest !== "object" || !Array.isArray(manifest.runtimes)) throw new Error("RUNTIME_MANIFEST_JSON must contain runtimes[]");
  for (const runtime of manifest.runtimes) {
    if (!["darwin", "win32", "linux"].includes(runtime.platform)) throw new Error("Runtime platform is invalid");
    if (!["arm64", "x64"].includes(runtime.arch)) throw new Error("Runtime architecture is invalid");
    for (const field of ["version", "url", "sha256", "executable"]) {
      if (typeof runtime[field] !== "string" || !runtime[field].trim()) throw new Error(`Runtime ${field} is required`);
    }
    if (!/^[a-f0-9]{64}$/i.test(runtime.sha256)) throw new Error("Runtime sha256 is invalid");
    const url = new URL(runtime.url);
    if (url.protocol !== "https:") throw new Error("Runtime URL must use https");
  }
}

function sendJson(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
  return true;
}

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}

function notFound(message) {
  const error = new Error(message);
  error.statusCode = 404;
  return error;
}
