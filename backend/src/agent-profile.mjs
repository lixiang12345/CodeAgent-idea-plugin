import {
  DEFAULT_CONTEXT_WINDOW_TOKENS,
  DEFAULT_RESERVED_OUTPUT_TOKENS,
  MAX_CONTEXT_WINDOW_TOKENS,
  MAX_RESERVED_OUTPUT_TOKENS,
  MIN_CONTEXT_WINDOW_TOKENS,
  MIN_RESERVED_OUTPUT_TOKENS,
} from "./context-policy.mjs";

export const AGENT_PROFILE_TYPES = Object.freeze(["general", "search", "context", "prompt", "loop"]);
export const VERIFICATION_POLICIES = Object.freeze(["none", "after-mutation"]);

const PROFILE_ID_PATTERN = /^[A-Za-z0-9._-]{1,120}$/;
const PLUGIN_ID_PATTERN = /^[A-Za-z0-9._-]{1,120}$/;
const PLUGIN_CONTRIBUTION_PATTERN = /^[A-Za-z0-9_-]{1,80}$/;
const TOOL_ID_PATTERN = /^[A-Za-z0-9._:-]{1,160}$/;
const READ_ONLY_PROFILE_TYPES = new Set(["search", "context", "prompt"]);
const TYPE_RUNTIME_DEFAULTS = {
  general: { maxTurns: 12, maxToolCalls: 48, maxSubagentCalls: 4, verificationPolicy: "after-mutation" },
  search: { maxTurns: 10, maxToolCalls: 24, maxSubagentCalls: 4, verificationPolicy: "none" },
  context: { maxTurns: 10, maxToolCalls: 24, maxSubagentCalls: 2, verificationPolicy: "none" },
  prompt: { maxTurns: 6, maxToolCalls: 12, maxSubagentCalls: 1, verificationPolicy: "none" },
  loop: { maxTurns: 24, maxToolCalls: 96, maxSubagentCalls: 6, verificationPolicy: "after-mutation" },
};
const TYPE_TOOL_DEFAULTS = {
  search: new Set([
    "codebase_retrieval",
    "read_file",
    "list_files",
    "search_text",
    "diagnostics",
    "git_history",
    "conversation_retrieval",
    "view_tasks",
    "web_fetch",
    "web_search",
    "github_search",
    "linear_search",
    "notion_search",
    "jira_search",
    "confluence_search",
    "glean_search",
    "supabase_query",
    "subagent",
  ]),
  context: new Set([
    "codebase_retrieval",
    "read_file",
    "list_files",
    "diagnostics",
    "git_history",
    "conversation_retrieval",
    "view_tasks",
  ]),
  prompt: new Set([
    "codebase_retrieval",
    "read_file",
    "list_files",
    "conversation_retrieval",
  ]),
};

const BUILT_INS = Object.freeze({
  general: profile({
    id: "general",
    name: "General Agent",
    description: "Balanced repository understanding, implementation, and verification.",
    agentType: "general",
    maxTurns: 12,
  }),
  search: profile({
    id: "search",
    name: "Search Agent",
    description: "Evidence-first research across repository and connected search sources.",
    agentType: "search",
    maxTurns: 10,
  }),
  context: profile({
    id: "context",
    name: "Context Agent",
    description: "Builds a compact, cited repository context pack before execution.",
    agentType: "context",
    maxTurns: 10,
  }),
  prompt: profile({
    id: "prompt",
    name: "Prompt Engineer",
    description: "Refines coding tasks into precise, verifiable execution briefs.",
    agentType: "prompt",
    maxTurns: 6,
  }),
  loop: profile({
    id: "loop",
    name: "Loop Agent",
    description: "Executes, verifies, reviews, and iterates within a bounded budget.",
    agentType: "loop",
    maxTurns: 24,
  }),
});

export function listBuiltInAgentProfiles() {
  return Object.values(BUILT_INS).map(cloneProfile);
}

export function builtInAgentProfile(id = "general") {
  return cloneProfile(BUILT_INS[id] || BUILT_INS.general);
}

export async function resolveAgentProfile({ store, userId, profileId, requestProfile = null }) {
  const id = normalizeProfileId(profileId);
  if (requestProfile !== null && requestProfile !== undefined) {
    return pluginRequestProfile(requestProfile, id);
  }
  const record = typeof store.getConfiguration === "function"
    ? await store.getConfiguration(userId, "agents", id)
    : (await store.listConfigurations(userId, "agents")).find((item) => item.id === id) || null;
  if (record) {
    if (record.value?.enabled === false && BUILT_INS[id]) return cloneProfile(BUILT_INS[id]);
    return configuredProfile(record);
  }
  if (BUILT_INS[id]) return cloneProfile(BUILT_INS[id]);
  throw validationError(`Unknown Agent profile: ${id}`);
}

function pluginRequestProfile(value, requestedId) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw validationError("Plugin Agent profile must be an object");
  }
  const allowedFields = new Set([
    "id", "pluginId", "pluginVersion", "name", "description", "agentType", "systemPrompt", "model",
    "allowedTools", "maxTurns", "maxToolCalls", "maxSubagentCalls", "verificationPolicy",
    "contextWindowTokens", "reservedOutputTokens",
  ]);
  if (Object.keys(value).some((field) => !allowedFields.has(field))) {
    throw validationError("Plugin Agent profile contains unsupported fields");
  }
  if (value.id !== requestedId) throw validationError("Plugin Agent profile ID does not match agentProfileId");
  if (typeof value.pluginId !== "string" || !PLUGIN_ID_PATTERN.test(value.pluginId)) {
    throw validationError("Plugin Agent profile pluginId is invalid");
  }
  const prefix = `plugin.${value.pluginId}.`;
  const localId = requestedId.startsWith(prefix) ? requestedId.slice(prefix.length) : "";
  if (!PLUGIN_CONTRIBUTION_PATTERN.test(localId)) {
    throw validationError("Plugin Agent profile is outside its plugin namespace");
  }
  if (typeof value.pluginVersion !== "string" || !value.pluginVersion.trim() || value.pluginVersion.length > 240) {
    throw validationError("Plugin Agent profile version is invalid");
  }
  if (typeof value.name !== "string" || !value.name.trim() || value.name.length > 160) {
    throw validationError("Plugin Agent profile name is invalid");
  }
  optionalBoundedText(value.description, "description", 2_000);
  optionalBoundedText(value.systemPrompt, "systemPrompt", 100_000);
  optionalBoundedText(value.model, "model", 240);
  if (!AGENT_PROFILE_TYPES.includes(value.agentType)) {
    throw validationError(`Unsupported Agent profile type: ${String(value.agentType)}`);
  }
  const allowedTools = value.allowedTools ?? [];
  if (!Array.isArray(allowedTools) || allowedTools.length > 128 ||
      allowedTools.some((tool) => typeof tool !== "string" || !TOOL_ID_PATTERN.test(tool))) {
    throw validationError("Plugin Agent allowedTools are invalid");
  }
  if (new Set(allowedTools).size !== allowedTools.length) {
    throw validationError("Plugin Agent allowedTools must be unique");
  }
  integerSetting(value.maxTurns, "maxTurns", 1, 64, 12);
  integerSetting(value.maxToolCalls, "maxToolCalls", 1, 256, 48);
  integerSetting(value.maxSubagentCalls, "maxSubagentCalls", 0, 16, 4);
  if (!VERIFICATION_POLICIES.includes(value.verificationPolicy)) {
    throw validationError("Plugin Agent verificationPolicy is invalid");
  }
  integerSetting(
    value.contextWindowTokens,
    "contextWindowTokens",
    MIN_CONTEXT_WINDOW_TOKENS,
    MAX_CONTEXT_WINDOW_TOKENS,
    DEFAULT_CONTEXT_WINDOW_TOKENS,
  );
  integerSetting(
    value.reservedOutputTokens,
    "reservedOutputTokens",
    MIN_RESERVED_OUTPUT_TOKENS,
    MAX_RESERVED_OUTPUT_TOKENS,
    DEFAULT_RESERVED_OUTPUT_TOKENS,
  );
  if (value.reservedOutputTokens >= value.contextWindowTokens) {
    throw validationError("reservedOutputTokens must be smaller than contextWindowTokens");
  }
  return profile({
    ...value,
    id: requestedId,
    allowedTools,
    builtin: false,
    pluginId: value.pluginId,
    pluginVersion: value.pluginVersion,
  });
}

export function applyAgentProfile(request, agentProfile) {
  const configuredTools = new Set(agentProfile.allowedTools);
  const typeDefaults = TYPE_TOOL_DEFAULTS[agentProfile.agentType];
  const readOnly = READ_ONLY_PROFILE_TYPES.has(agentProfile.agentType);
  const tools = request.tools.filter((toolDefinition) => {
    if (readOnly && toolDefinition.risk !== "read_only") return false;
    if (typeDefaults && !typeDefaults.has(toolDefinition.name)) return false;
    if (configuredTools.size > 0 && !configuredTools.has(toolDefinition.name)) return false;
    return true;
  });
  return {
    ...request,
    agentProfileId: agentProfile.id,
    model: request.model || agentProfile.model || undefined,
    tools,
  };
}

function configuredProfile(record) {
  const value = record.value || {};
  if (value.enabled === false) throw validationError(`Agent profile is disabled: ${record.id}`);
  return profile({
    id: record.id,
    name: value.name,
    description: value.description,
    agentType: value.agentType,
    systemPrompt: value.systemPrompt,
    model: value.model,
    allowedTools: value.allowedTools,
    maxTurns: value.maxTurns,
    maxToolCalls: value.maxToolCalls,
    maxSubagentCalls: value.maxSubagentCalls,
    verificationPolicy: value.verificationPolicy,
    contextWindowTokens: value.contextWindowTokens,
    reservedOutputTokens: value.reservedOutputTokens,
    builtin: false,
  });
}

function profile({
  id,
  name,
  description = null,
  agentType = "general",
  systemPrompt = null,
  model = null,
  allowedTools = [],
  maxTurns,
  maxToolCalls,
  maxSubagentCalls,
  verificationPolicy,
  contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS,
  reservedOutputTokens = DEFAULT_RESERVED_OUTPUT_TOKENS,
  builtin = true,
  pluginId = null,
  pluginVersion = null,
}) {
  if (!PROFILE_ID_PATTERN.test(id)) throw validationError("Agent profile ID is invalid");
  if (!AGENT_PROFILE_TYPES.includes(agentType)) throw validationError(`Unsupported Agent profile type: ${agentType}`);
  const runtimeDefaults = TYPE_RUNTIME_DEFAULTS[agentType] || TYPE_RUNTIME_DEFAULTS.general;
  const normalizedContextWindowTokens = integerSetting(
    contextWindowTokens,
    "contextWindowTokens",
    MIN_CONTEXT_WINDOW_TOKENS,
    MAX_CONTEXT_WINDOW_TOKENS,
    DEFAULT_CONTEXT_WINDOW_TOKENS,
  );
  const normalizedReservedOutputTokens = integerSetting(
    reservedOutputTokens,
    "reservedOutputTokens",
    MIN_RESERVED_OUTPUT_TOKENS,
    MAX_RESERVED_OUTPUT_TOKENS,
    DEFAULT_RESERVED_OUTPUT_TOKENS,
  );
  if (normalizedReservedOutputTokens >= normalizedContextWindowTokens) {
    throw validationError("reservedOutputTokens must be smaller than contextWindowTokens");
  }
  return {
    id,
    name: typeof name === "string" && name.trim() ? name.trim().slice(0, 160) : id,
    description: typeof description === "string" && description.trim() ? description.trim().slice(0, 2_000) : null,
    agentType,
    systemPrompt: typeof systemPrompt === "string" && systemPrompt.trim() ? systemPrompt.trim().slice(0, 100_000) : null,
    model: typeof model === "string" && model.trim() ? model.trim().slice(0, 240) : null,
    allowedTools: Array.isArray(allowedTools) ? [...new Set(allowedTools.filter((item) => typeof item === "string" && item.trim()).map((item) => item.trim()))].slice(0, 128) : [],
    maxTurns: integerRuntimeSetting(maxTurns, 1, 64, runtimeDefaults.maxTurns),
    maxToolCalls: integerRuntimeSetting(maxToolCalls, 1, 256, runtimeDefaults.maxToolCalls),
    maxSubagentCalls: integerRuntimeSetting(maxSubagentCalls, 0, 16, runtimeDefaults.maxSubagentCalls),
    verificationPolicy: VERIFICATION_POLICIES.includes(verificationPolicy) ? verificationPolicy : runtimeDefaults.verificationPolicy,
    contextWindowTokens: normalizedContextWindowTokens,
    reservedOutputTokens: normalizedReservedOutputTokens,
    builtin,
    pluginId,
    pluginVersion,
  };
}

function normalizeProfileId(value) {
  const id = typeof value === "string" && value.trim() ? value.trim() : "general";
  if (!PROFILE_ID_PATTERN.test(id)) throw validationError("Agent profile ID is invalid");
  return id;
}

function cloneProfile(value) {
  return { ...value, allowedTools: [...value.allowedTools] };
}

function integerSetting(value, name, min, max, fallback) {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value) || value < min || value > max) {
    throw validationError(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}

function integerRuntimeSetting(value, min, max, fallback) {
  return Number.isInteger(value) ? Math.min(max, Math.max(min, value)) : fallback;
}

function optionalBoundedText(value, name, maxLength) {
  if (value === undefined || value === null) return;
  if (typeof value !== "string" || value.length > maxLength) {
    throw validationError(`${name} must be a string of at most ${maxLength} characters`);
  }
}

function validationError(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}
