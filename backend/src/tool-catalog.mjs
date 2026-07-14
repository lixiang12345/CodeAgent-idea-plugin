export const DISCOVER_TOOLS_NAME = "discover_tools";

const PROFILE_CORE_TOOLS = {
  general: new Set(["codebase_retrieval", "read_file", "list_files", "diagnostics", "view_tasks", "ask_user"]),
  search: new Set(["codebase_retrieval", "search_text", "read_file", "web_search", "web_fetch", "conversation_retrieval"]),
  context: new Set(["codebase_retrieval", "read_file", "diagnostics"]),
  prompt: new Set(["codebase_retrieval", "read_file", "conversation_retrieval"]),
  loop: new Set(["codebase_retrieval", "read_file", "diagnostics", "apply_patch", "run_terminal", "view_tasks", "update_tasks", "ask_user"]),
};

const DISCOVER_TOOLS_DEFINITION = Object.freeze({
  name: DISCOVER_TOOLS_NAME,
  description: "Find and activate policy-approved tools only when the current task needs a capability that is not already active. Prefer exact names when known; otherwise provide a concise capability query. Do not call repeatedly for tools already active.",
  risk: "read_only",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      query: { type: "string", description: "Capability needed, such as Git history, browser access, file mutation, or terminal verification", maxLength: 500 },
      names: { type: "array", description: "Exact tool names to activate when already known", items: { type: "string" }, maxItems: 12 },
      limit: { type: "integer", description: "Maximum matching tools to return and activate", minimum: 1, maximum: 12, default: 8 },
    },
  },
});

export function createToolCatalog(agentProfile = {}, tools = []) {
  return new ToolCatalog(agentProfile.agentType || "general", tools);
}

class ToolCatalog {
  constructor(agentType, tools) {
    this.tools = tools.map((tool) => ({ ...tool }));
    this.byName = new Map(this.tools.map((tool) => [tool.name, tool]));
    this.active = new Map();
    const core = PROFILE_CORE_TOOLS[agentType] || PROFILE_CORE_TOOLS.general;
    for (const tool of this.tools) {
      if (core.has(tool.name)) this.active.set(tool.name, tool);
    }
    if (this.active.size === 0) {
      const fallback = this.tools.filter((tool) => tool.risk === "read_only").slice(0, 4);
      for (const tool of fallback.length ? fallback : this.tools.slice(0, 4)) this.active.set(tool.name, tool);
    }
  }

  has(name) {
    return this.byName.has(name);
  }

  isActive(name) {
    return this.active.has(name);
  }

  discoveryAvailable() {
    return this.active.size < this.tools.length;
  }

  activeDefinitions() {
    const definitions = [...this.active.values()];
    if (this.discoveryAvailable()) definitions.push(DISCOVER_TOOLS_DEFINITION);
    return definitions;
  }

  snapshot(activated = []) {
    return {
      activeToolNames: this.activeDefinitions().map((tool) => tool.name),
      activeToolCount: this.active.size,
      catalogToolCount: this.tools.length,
      discoverableToolCount: this.tools.length - this.active.size,
      activated,
    };
  }

  discover(argumentsText) {
    let args;
    try {
      args = JSON.parse(argumentsText || "{}");
    } catch {
      throw new Error("discover_tools arguments must be valid JSON");
    }
    if (!args || typeof args !== "object" || Array.isArray(args)) {
      throw new Error("discover_tools arguments must be an object");
    }
    const names = Array.isArray(args.names)
      ? [...new Set(args.names.filter((name) => typeof name === "string" && name.trim()).map((name) => name.trim()))].slice(0, 12)
      : [];
    const query = typeof args.query === "string" ? args.query.trim().slice(0, 500) : "";
    const limit = Number.isInteger(args.limit) ? Math.min(12, Math.max(1, args.limit)) : 8;
    const requestedNames = new Set(names);
    const queryTokens = tokenize(query);
    const hasSelector = requestedNames.size > 0 || queryTokens.length > 0;
    const matches = this.tools
      .map((tool) => ({ tool, score: matchScore(tool, requestedNames, queryTokens, hasSelector) }))
      .filter(({ score }) => score > 0)
      .sort((left, right) => right.score - left.score || left.tool.name.localeCompare(right.tool.name))
      .slice(0, limit);
    const activated = [];
    if (hasSelector) {
      for (const { tool } of matches) {
        if (!this.active.has(tool.name)) {
          this.active.set(tool.name, tool);
          activated.push(tool.name);
        }
      }
    }
    const unknownNames = names.filter((name) => !this.byName.has(name));
    const output = {
      activated,
      matches: matches.map(({ tool }) => ({
        name: tool.name,
        description: tool.description,
        risk: tool.risk || "read_only",
        active: this.active.has(tool.name),
      })),
      unknownNames,
      activeToolNames: this.activeDefinitions().map((tool) => tool.name),
      remainingToolCount: this.tools.length - this.active.size,
      guidance: activated.length
        ? "The activated tools are available on the next model turn. IDE approval and risk policy still apply."
        : hasSelector
          ? "No additional matching tools were activated. Refine the capability query or use an exact catalog name."
          : "No tools were activated. Call again with a concise capability query or exact tool names.",
    };
    return {
      output: JSON.stringify(output, null, 2),
      summary: activated.length ? `Activated ${activated.join(", ")}` : `Found ${matches.length} tool matches`,
      activated,
    };
  }
}

function matchScore(tool, requestedNames, queryTokens, hasSelector) {
  if (requestedNames.has(tool.name)) return 10_000;
  if (!hasSelector) return tool.name === DISCOVER_TOOLS_NAME ? 0 : 1;
  if (queryTokens.length === 0) return 0;
  const name = tool.name.toLowerCase();
  const description = String(tool.description || "").toLowerCase();
  const risk = String(tool.risk || "read_only").toLowerCase();
  let score = 0;
  for (const token of queryTokens) {
    if (name === token) score += 100;
    else if (name.includes(token)) score += 30;
    if (description.includes(token)) score += 8;
    if (risk.includes(token)) score += 3;
  }
  return score;
}

function tokenize(value) {
  return [...new Set(String(value || "").toLowerCase().match(/[a-z0-9_.-]+/g) || [])].filter((token) => token.length >= 2);
}
