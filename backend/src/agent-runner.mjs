import { composeSystemPrompt } from "./prompt.mjs";
import { builtInAgentProfile } from "./agent-profile.mjs";
import { contextBudgetFor, prepareModelMessages } from "./context-policy.mjs";
import { createToolCatalog, DISCOVER_TOOLS_NAME } from "./tool-catalog.mjs";

export class AgentRunner {
  constructor({ modelGateway, maxTurns = 64, maxToolCalls = 128 }) {
    this.modelGateway = modelGateway;
    this.maxTurns = maxTurns;
    this.maxToolCalls = maxToolCalls;
  }

  async run({ request, agentProfile = builtInAgentProfile("general"), emit, awaitToolResult, signal }) {
    validateRunRequest(request);
    const maxTurns = Math.min(this.maxTurns, agentProfile.maxTurns);
    const maxToolCalls = Math.min(this.maxToolCalls, agentProfile.maxToolCalls || this.maxToolCalls);
    const maxSubagentCalls = Math.max(0, agentProfile.maxSubagentCalls ?? 0);
    const toolCatalog = createToolCatalog(agentProfile, request.tools);
    const toolRiskByName = new Map(request.tools.map((tool) => [tool.name, tool.risk || "read_only"]));
    const seenToolCallIds = new Set();
    let toolCallCount = 0;
    let subagentCallCount = 0;
    let pendingVerification = false;
    let verificationReminderCount = 0;
    let previousToolSignature = null;
    let repeatedToolCallCount = 0;
    const messages = [
      { role: "system", content: composeSystemPrompt({ ...request, tools: toolCatalog.activeDefinitions(), agentProfile }) },
      ...request.messages.map(normalizeMessage),
    ];
    emit("tool.catalog.updated", { turnIndex: 0, ...toolCatalog.snapshot() });

    for (let turnIndex = 0; turnIndex < maxTurns; turnIndex += 1) {
      throwIfAborted(signal);
      emit("turn.started", { turnIndex });
      const activeTools = toolCatalog.activeDefinitions();
      const contextBudget = contextBudgetFor(agentProfile, activeTools);
      const prepared = prepareModelMessages(messages, contextBudget);
      emit("context.updated", { turnIndex, ...prepared.stats });
      const turn = await this.modelGateway.stream({
        messages: prepared.messages,
        tools: activeTools,
        model: request.model,
        maxOutputTokens: contextBudget.reservedOutputTokens,
        signal,
        onTextDelta: (delta) => emit("message.delta", { delta, turnIndex }),
      });
      messages.push({
        role: "assistant",
        content: turn.content,
        toolCalls: turn.toolCalls.length ? turn.toolCalls : undefined,
      });
      emit("assistant.completed", { content: turn.content, turnIndex });

      if (turn.toolCalls.length === 0) {
        if (pendingVerification && agentProfile.verificationPolicy === "after-mutation") {
          if (verificationReminderCount >= 1) {
            throw new Error("Agent finished without required post-mutation verification");
          }
          verificationReminderCount += 1;
          const message = "A project mutation completed without follow-up verification. Inspect the changed behavior and run the smallest meaningful diagnostic, test, build, or focused read before finishing.";
          emit("verification.updated", { turnIndex, status: "required", message });
          messages.push({ role: "system", content: `Runtime verification gate: ${message}` });
          continue;
        }
        emit("run.completed", { turnCount: turnIndex + 1 });
        return;
      }
      if (turn.toolCalls.length > 32) throw new Error("Model requested too many tools in one turn");

      for (const call of turn.toolCalls) {
        throwIfAborted(signal);
        validateToolCall(call);
        if (seenToolCallIds.has(call.id)) throw new Error(`Duplicate tool call ID: ${call.id}`);
        seenToolCallIds.add(call.id);
        const signature = toolCallSignature(call);
        if (signature === previousToolSignature) repeatedToolCallCount += 1;
        else {
          previousToolSignature = signature;
          repeatedToolCallCount = 1;
        }
        if (repeatedToolCallCount > 2) {
          const summary = "Repeated tool call blocked";
          messages.push(toolMessage(call.id, `The same ${call.name} call was already attempted twice consecutively. Inspect prior results or change the approach.`, summary));
          emit("tool.completed", { toolCallId: call.id, status: "failed", summary });
          continue;
        }
        toolCallCount += 1;
        if (toolCallCount > maxToolCalls) throw new Error(`Agent exceeded ${maxToolCalls} tool calls`);
        if (call.name === "subagent") {
          subagentCallCount += 1;
          if (subagentCallCount > maxSubagentCalls) {
            const summary = "Subagent budget exceeded";
            messages.push(toolMessage(call.id, `This Agent profile allows at most ${maxSubagentCalls} subagent calls per run.`, summary));
            emit("tool.completed", { toolCallId: call.id, status: "failed", summary });
            continue;
          }
        }
        if (call.name === DISCOVER_TOOLS_NAME && toolCatalog.discoveryAvailable()) {
          const discovery = toolCatalog.discover(call.arguments);
          emit("tool.catalog.updated", { turnIndex, ...toolCatalog.snapshot(discovery.activated) });
          emit("tool.completed", { toolCallId: call.id, status: "completed", summary: discovery.summary });
          messages.push(toolMessage(call.id, discovery.output, discovery.summary));
          continue;
        }
        if (!toolCatalog.has(call.name)) {
          messages.push(toolMessage(call.id, `Tool '${call.name}' is not available`, "Unavailable tool request"));
          emit("tool.completed", { toolCallId: call.id, status: "failed", summary: "Unavailable tool request" });
          continue;
        }
        if (!toolCatalog.isActive(call.name)) {
          const summary = "Tool is not active";
          messages.push(toolMessage(call.id, `Tool '${call.name}' is policy-approved but not active. Call ${DISCOVER_TOOLS_NAME} with names: ["${call.name}"] on a separate turn.`, summary));
          emit("tool.completed", { toolCallId: call.id, status: "failed", summary });
          continue;
        }
        const resultPromise = awaitToolResult(call.id, signal);
        emit("tool.request", { call, turnIndex });
        const result = await resultPromise;
        emit("tool.completed", { toolCallId: call.id, status: result.status, summary: result.summary });
        messages.push(toolMessage(call.id, result.output || result.error || result.status, result.summary));
        if (result.status === "completed") {
          const risk = toolRiskByName.get(call.name) || "read_only";
          if (isMutationCall(call, risk)) {
            pendingVerification = true;
            verificationReminderCount = 0;
            emit("verification.updated", { turnIndex, status: "required", toolName: call.name, message: "Project mutation requires follow-up verification" });
          } else if (pendingVerification && isVerificationCall(call)) {
            pendingVerification = false;
            verificationReminderCount = 0;
            emit("verification.updated", { turnIndex, status: "verified", toolName: call.name, message: "Post-mutation verification completed" });
          }
        }
      }
    }
    throw new Error(`Agent exceeded ${maxTurns} model turns`);
  }
}

function toolCallSignature(call) {
  try {
    return `${call.name}:${JSON.stringify(sortJson(JSON.parse(call.arguments)))}`;
  } catch {
    return `${call.name}:${String(call.arguments || "").trim()}`;
  }
}

function sortJson(value) {
  if (Array.isArray(value)) return value.map(sortJson);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortJson(value[key])]));
}

function isMutationCall(call, risk) {
  return risk === "mutating" && !isVerificationCall(call);
}

function isVerificationCall(call) {
  if (["diagnostics", "read_file", "search_text", "codebase_retrieval"].includes(call.name)) return true;
  if (call.name !== "run_terminal") return false;
  try {
    const command = String(JSON.parse(call.arguments)?.command || "").toLowerCase();
    return /(^|\s|\/)(test|tests|check|build|lint|verify|gradle|gradlew|mvn|npm|pnpm|yarn|cargo|pytest|jest|vitest|git)(\s|$)/.test(command);
  } catch {
    return false;
  }
}

export function validateRunRequest(request) {
  if (!request || typeof request !== "object") throw new Error("Run request is required");
  if (!["agent", "chat", "ask"].includes(request.mode)) throw new Error("Unsupported run mode");
  if (!Array.isArray(request.messages)) throw new Error("messages must be an array");
  if (!Array.isArray(request.tools)) throw new Error("tools must be an array");
  if (!request.workspace || typeof request.workspace !== "object" || Array.isArray(request.workspace)) {
    throw new Error("workspace must be an object");
  }
  validateOptionalString(request.workspace.guidance, "workspace.guidance", 200_000);
  validateOptionalString(request.workspace.historySummary, "workspace.historySummary", 200_000);
  validateWorkspaceEntries(request.workspace.rules, "workspace.rules");
  validateWorkspaceEntries(request.workspace.skills, "workspace.skills");
  if (request.model !== undefined && (typeof request.model !== "string" || !request.model.trim())) {
    throw new Error("model must be a non-empty string when provided");
  }
  if (request.agentProfileId !== undefined &&
      (typeof request.agentProfileId !== "string" || !/^[A-Za-z0-9._-]{1,120}$/.test(request.agentProfileId))) {
    throw new Error("agentProfileId is invalid");
  }
  for (const message of request.messages) normalizeMessage(message);
  const toolNames = new Set();
  for (const tool of request.tools) {
    if (!tool?.name || typeof tool.name !== "string") throw new Error("Every tool requires a name");
    if (toolNames.has(tool.name)) throw new Error(`Tool names must be unique: ${tool.name}`);
    toolNames.add(tool.name);
    if (typeof tool.description !== "string") throw new Error("Every tool requires a description");
    if (!tool.parameters || typeof tool.parameters !== "object" || Array.isArray(tool.parameters)) {
      throw new Error("Every tool requires object parameters");
    }
    if (tool.risk !== undefined && !["read_only", "local_state", "mutating"].includes(tool.risk)) {
      throw new Error(`Unsupported tool risk: ${tool.risk}`);
    }
  }
}

function validateToolCall(call) {
  if (!call || typeof call !== "object") throw new Error("Model returned an invalid tool call");
  if (typeof call.id !== "string" || !call.id.trim()) throw new Error("Model tool call requires an ID");
  if (typeof call.name !== "string" || !call.name.trim()) throw new Error("Model tool call requires a name");
  if (typeof call.arguments !== "string") throw new Error("Model tool call arguments must be JSON text");
}

function normalizeMessage(message) {
  if (!message || !["user", "assistant"].includes(message.role)) {
    throw new Error("Conversation messages may only use user or assistant roles");
  }
  if (message.content !== undefined && message.content !== null && typeof message.content !== "string") {
    throw new Error("Message content must be a string or null");
  }
  return message;
}

function toolMessage(toolCallId, content, summary) {
  return {
    role: "tool",
    toolCallId,
    content: String(content),
    summary: typeof summary === "string" && summary.trim() ? summary.trim().slice(0, 4_000) : undefined,
  };
}

function validateOptionalString(value, field, maxLength) {
  if (value === undefined || value === null) return;
  if (typeof value !== "string") throw new Error(`${field} must be a string when provided`);
  if (value.length > maxLength) throw new Error(`${field} exceeds ${maxLength} characters`);
}

function validateWorkspaceEntries(value, field) {
  if (value === undefined) return;
  if (!Array.isArray(value)) throw new Error(`${field} must be an array`);
  if (value.length > 256) throw new Error(`${field} may contain at most 256 entries`);
  for (const entry of value) {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
      throw new Error(`${field} entries must be objects`);
    }
    validateOptionalString(entry.name, `${field}.name`, 1_000);
    validateOptionalString(entry.path, `${field}.path`, 4_000);
    validateOptionalString(entry.content, `${field}.content`, 200_000);
    if (typeof entry.content !== "string") throw new Error(`${field}.content is required`);
  }
}

function throwIfAborted(signal) {
  if (signal?.aborted) throw signal.reason || new Error("Run cancelled");
}
