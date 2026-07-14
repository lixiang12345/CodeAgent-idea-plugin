import {
  DEFAULT_CONTEXT_WINDOW_TOKENS,
  DEFAULT_RESERVED_OUTPUT_TOKENS,
  MAX_CONTEXT_WINDOW_TOKENS,
  MAX_RESERVED_OUTPUT_TOKENS,
  MIN_CONTEXT_WINDOW_TOKENS,
  MIN_RESERVED_OUTPUT_TOKENS,
} from "./context-policy.mjs";

export const AGENT_PROFILE_TYPES = Object.freeze(["general", "search", "context", "prompt", "loop"]);

const PROFILE_ID_PATTERN = /^[A-Za-z0-9._-]{1,120}$/;
const READ_ONLY_PROFILE_TYPES = new Set(["search", "context", "prompt"]);
const TYPE_TOOL_DEFAULTS = {
  context: new Set([
    "codebase_retrieval",
    "read_file",
    "list_files",
    "search_text",
    "diagnostics",
    "git_history",
    "conversation_retrieval",
    "view_tasks",
  ]),
  prompt: new Set([
    "codebase_retrieval",
    "read_file",
    "list_files",
    "search_text",
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

export async function resolveAgentProfile({ store, userId, profileId }) {
  const id = normalizeProfileId(profileId);
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
  maxTurns = 12,
  contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS,
  reservedOutputTokens = DEFAULT_RESERVED_OUTPUT_TOKENS,
  builtin = true,
}) {
  if (!PROFILE_ID_PATTERN.test(id)) throw validationError("Agent profile ID is invalid");
  if (!AGENT_PROFILE_TYPES.includes(agentType)) throw validationError(`Unsupported Agent profile type: ${agentType}`);
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
    maxTurns: Number.isInteger(maxTurns) ? Math.min(64, Math.max(1, maxTurns)) : 12,
    contextWindowTokens: normalizedContextWindowTokens,
    reservedOutputTokens: normalizedReservedOutputTokens,
    builtin,
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

function validationError(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}
