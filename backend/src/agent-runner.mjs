import { composeSystemPrompt } from "./prompt.mjs";
import { builtInAgentProfile } from "./agent-profile.mjs";
import { contextBudgetFor, prepareModelMessages } from "./context-policy.mjs";
import { isRetryableModelError } from "./model-gateway.mjs";
import { createToolCatalog, DISCOVER_TOOLS_NAME } from "./tool-catalog.mjs";

export class AgentRunner {
  constructor({
    modelGateway,
    maxTurns = 64,
    maxToolCalls = 128,
    modelTurnTimeoutMs = 120_000,
    modelTurnRetryCount = 2,
    modelTurnRetryDelayMs = 500,
    contextQueryTimeoutMs = 10_000,
  }) {
    this.modelGateway = modelGateway;
    this.maxTurns = maxTurns;
    this.maxToolCalls = maxToolCalls;
    this.modelTurnTimeoutMs = modelTurnTimeoutMs;
    this.modelTurnRetryCount = modelTurnRetryCount;
    this.modelTurnRetryDelayMs = modelTurnRetryDelayMs;
    this.contextQueryTimeoutMs = contextQueryTimeoutMs;
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
    const pendingMutationPaths = new Set();
    let pendingUnknownMutation = false;
    let verificationReminderCount = 0;
    let pendingTaskReview = false;
    let taskReviewReminderCount = 0;
    let previousToolSignature = null;
    let repeatedToolCallCount = 0;
    const messages = [
      { role: "system", content: "" },
      ...request.messages.map(normalizeMessage),
    ];
    const latestUserRequest = [...request.messages].reverse()
      .find((message) => message.role === "user" && typeof message.content === "string")?.content || "";
    emit("tool.catalog.updated", { turnIndex: 0, ...toolCatalog.snapshot() });

    for (let turnIndex = 0; turnIndex < maxTurns; turnIndex += 1) {
      throwIfAborted(signal);
      emit("turn.started", { turnIndex });
      const activeTools = toolCatalog.activeDefinitions();
      messages[0] = {
        role: "system",
        content: composeSystemPrompt({ ...request, tools: activeTools, agentProfile }),
      };
      const contextBudget = contextBudgetFor(
        agentProfile,
        activeTools,
      );
      const prepared = prepareModelMessages(messages, contextBudget);
      emit("context.updated", { turnIndex, ...prepared.stats });
      const turn = await this.#streamTurn({
        messages: prepared.messages,
        tools: activeTools,
        model: request.model,
        maxOutputTokens: contextBudget.reservedOutputTokens,
        signal,
        turnIndex,
        onRetry: (attempt, error) => emit("model.retrying", {
          turnIndex,
          attempt,
          maxAttempts: this.modelTurnRetryCount + 1,
          message: rootMessage(error),
        }),
        onTextDelta: (delta) => emit("message.delta", { delta, turnIndex }),
      });
      turn.toolCalls = await this.#rewriteContextQueries(turn.toolCalls, latestUserRequest, signal);
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
          const scope = pendingMutationPaths.size
            ? ` Pending changed paths: ${[...pendingMutationPaths].slice(0, 8).join(", ")}.`
            : pendingUnknownMutation
              ? " The mutation scope could not be derived from tool arguments."
              : "";
          const message = `A project mutation completed without relevant follow-up verification. Inspect the changed behavior and run the smallest meaningful diagnostic, test, build, or focused read of every changed path before finishing.${scope}`;
          emit("verification.updated", { turnIndex, status: "required", message });
          messages.push({ role: "system", content: `Runtime verification gate: ${message}` });
          continue;
        }
        if (pendingTaskReview) {
          if (taskReviewReminderCount >= 1) {
            throw new Error("Agent finished after task mutation without reviewing task state");
          }
          taskReviewReminderCount += 1;
          const message = "A task-list mutation completed without a follow-up task review. Call view_tasks, then complete or cancel every task created or changed in this run, or report a concrete blocker before finishing.";
          messages.push({ role: "system", content: `Runtime task gate: ${message}` });
          continue;
        }
        emit("run.completed", { turnCount: turnIndex + 1 });
        return;
      }
      if (turn.toolCalls.length > 32) throw new Error("Model requested too many tools in one turn");
      emit("tool.batch.started", {
        turnIndex,
        total: turn.toolCalls.length,
        names: turn.toolCalls.map((call) => call.name),
        execution: "sequential",
      });

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
            const paths = mutationPaths(call);
            if (paths.length === 0) pendingUnknownMutation = true;
            for (const path of paths) pendingMutationPaths.add(path);
            pendingVerification = true;
            verificationReminderCount = 0;
            emit("verification.updated", { turnIndex, status: "required", toolName: call.name, message: "Project mutation requires follow-up verification" });
          } else if (pendingVerification) {
            const verification = consumeVerificationCall(
              call,
              pendingMutationPaths,
              pendingUnknownMutation,
            );
            pendingUnknownMutation = verification.unknownPending;
            if (verification.completed) {
              pendingVerification = false;
              verificationReminderCount = 0;
              emit("verification.updated", { turnIndex, status: "verified", toolName: call.name, message: "Post-mutation verification completed" });
            }
          }
          if (["add_tasks", "update_tasks", "reorg_tasks"].includes(call.name)) {
            pendingTaskReview = true;
            taskReviewReminderCount = 0;
          } else if (call.name === "view_tasks" && pendingTaskReview) {
            pendingTaskReview = false;
            taskReviewReminderCount = 0;
          }
        }
      }
    }
    throw new Error(`Agent exceeded ${maxTurns} model turns`);
  }

  async #streamTurn({ signal, turnIndex, onRetry = () => {}, onTextDelta = () => {}, ...request }) {
    for (let attempt = 0; attempt <= this.modelTurnRetryCount; attempt += 1) {
      let emittedText = false;
      try {
        return await this.#streamTurnAttempt({
          ...request,
          signal,
          turnIndex,
          onTextDelta: (delta) => {
            if (delta) emittedText = true;
            onTextDelta(delta);
          },
        });
      } catch (error) {
        const canRetry = !emittedText
          && !signal.aborted
          && attempt < this.modelTurnRetryCount
          && isRetryableModelError(error);
        if (!canRetry) throw error;
        onRetry(attempt + 2, error);
        await abortableDelay(this.modelTurnRetryDelayMs * (attempt + 1), signal);
      }
    }
    throw new Error(`Model turn ${turnIndex + 1} failed after retries`);
  }

  async #streamTurnAttempt({ signal, turnIndex, ...request }) {
    const controller = new AbortController();
    const abortFromRun = () => controller.abort(signal.reason || new Error("Run cancelled"));
    const timeout = setTimeout(
      () => controller.abort(new Error(`Model turn ${turnIndex + 1} timed out after ${this.modelTurnTimeoutMs / 1_000} seconds`)),
      this.modelTurnTimeoutMs,
    );
    signal.addEventListener("abort", abortFromRun, { once: true });
    try {
      return await this.modelGateway.stream({ ...request, signal: controller.signal });
    } catch (error) {
      if (controller.signal.aborted && !signal.aborted) throw controller.signal.reason;
      throw error;
    } finally {
      clearTimeout(timeout);
      signal.removeEventListener("abort", abortFromRun);
    }
  }

  async #rewriteContextQueries(calls, userRequest, signal) {
    if (!this.modelGateway.contextQueryModel || typeof this.modelGateway.streamInternal !== "function") return calls;
    return Promise.all(calls.map(async (call) => {
      if (call.name !== "codebase_retrieval") return call;
      const argumentsObject = parseArguments(call);
      const originalQuery = typeof argumentsObject?.information_request === "string"
        ? argumentsObject.information_request.trim()
        : "";
      if (!originalQuery) return call;

      const controller = new AbortController();
      const abortFromRun = () => controller.abort(signal.reason || new Error("Run cancelled"));
      const timeout = setTimeout(
        () => controller.abort(new Error(`Context query rewrite timed out after ${this.contextQueryTimeoutMs / 1_000} seconds`)),
        this.contextQueryTimeoutMs,
      );
      signal.addEventListener("abort", abortFromRun, { once: true });
      try {
        const rewritten = await this.modelGateway.streamInternal({
          model: this.modelGateway.contextQueryModel,
          tools: [
            {
              name: "rewrite_context_query",
              description: "Return one compact codebase retrieval query and nothing else",
              parameters: {
                type: "object",
                properties: {
                  query: { type: "string", description: "Retrieval-oriented symbols, paths, behaviors, callers, tests, configuration, and data-flow terms" },
                },
                required: ["query"],
                additionalProperties: false,
              },
            },
          ],
          maxOutputTokens: 256,
          messages: [
            {
              role: "system",
              content: "You are a code-search query compiler. Call rewrite_context_query exactly once. Never answer, explain, diagnose, plan, or use Markdown. Preserve exact symbols, class names, method names, paths, error text, and quoted literals. Produce a compact retrieval description covering likely implementations, callers, tests, configuration, state transitions, and data flow only when relevant.",
            },
            {
              role: "user",
              content: `User request:\n${userRequest || "(not available)"}\n\nCurrent retrieval request:\n${originalQuery}`,
            },
          ],
          signal: controller.signal,
          onTextDelta() {},
        });
        const informationRequest = extractContextQuery(rewritten, originalQuery);
        if (!informationRequest || informationRequest === originalQuery) return call;
        return {
          ...call,
          arguments: JSON.stringify({ ...argumentsObject, information_request: informationRequest }),
        };
      } catch (error) {
        if (signal.aborted) throw signal.reason || error;
        return call;
      } finally {
        clearTimeout(timeout);
        signal.removeEventListener("abort", abortFromRun);
      }
    }));
  }
}

function extractContextQuery(turn, originalQuery) {
  const toolCall = Array.isArray(turn?.toolCalls)
    ? turn.toolCalls.find((call) => call.name === "rewrite_context_query")
    : null;
  const toolArguments = toolCall ? parseArguments(toolCall) : null;
  const candidate = normalizeContextQueryCandidate(toolArguments?.query);
  if (!candidate || !isUsefulContextQuery(candidate, originalQuery)) return "";
  return candidate;
}

function normalizeContextQueryCandidate(value) {
  if (typeof value !== "string") return "";
  return value
    .replace(/^```(?:text)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim()
    .replace(/^["']|["']$/g, "")
    .replace(/\s+/g, " ")
    .slice(0, 1_000);
}

function isUsefulContextQuery(candidate, originalQuery) {
  if (candidate.length < 3 || candidate.length > 1_000) return false;
  if (/(?:^|\b)(?:i(?:'m| am) going to|i will|here are|the reason is|let me|通常|常见原因|我会|我将|可以帮你|无法|不能)/i.test(candidate)) return false;
  const identifiers = String(originalQuery || "").match(/`[^`]+`|"[^"]+"|'[^']+'|[A-Z][A-Za-z0-9_$]{2,}|[A-Za-z_$][A-Za-z0-9_$]*(?:[./:#_-][A-Za-z0-9_$.-]+)+/g) || [];
  const lowerCandidate = candidate.toLowerCase();
  return identifiers.every((identifier) => lowerCandidate.includes(identifier.replace(/^[`"']|[`"']$/g, "").toLowerCase()));
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
  if (risk !== "mutating" || isGlobalVerificationCall(call)) return false;
  if (isRemoteStateMutation(call)) return false;
  if (call.name === "run_terminal" && isReadOnlyTerminalCall(call)) return false;
  return true;
}

function isRemoteStateMutation(call) {
  return [
    "github_manage",
    "github_actions_manage",
    "github_merge_pull_request",
  ].includes(call.name);
}

function isReadOnlyTerminalCall(call) {
  const command = String(parseArguments(call)?.command || "").trim();
  if (!command || /[\r\n`]|(?:^|[^>])>>?|<<|\$\(/.test(stripDevNullRedirects(command))) return false;
  const segments = command.split(/\s*(?:&&|\|\||;|\|)\s*/).filter(Boolean);
  return segments.length > 0 && segments.every(isReadOnlyShellSegment);
}

function stripDevNullRedirects(command) {
  return command.replace(/(?:\d?>|&>)\s*\/dev\/null\b/g, "");
}

function isReadOnlyShellSegment(segment) {
  const normalized = segment
    .trim()
    .replace(/^(?:env\s+)?(?:[A-Za-z_][A-Za-z0-9_]*=\S+\s+)*/, "")
    .replace(/^(?:command|time)\s+/, "")
    .replace(/^timeout\s+\S+\s+/, "");
  if (!normalized) return false;
  const [commandName = "", ...args] = normalized.split(/\s+/);
  const executable = commandName.replace(/^.*\//, "");
  if (["pwd", "ls", "rg", "grep", "cat", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "stat", "file", "realpath", "dirname", "basename", "which"].includes(executable)) {
    return executable !== "grep" || !args.includes("--include-dir");
  }
  if (executable === "sed") return !args.some((argument) => /^-.*i/.test(argument));
  if (executable === "find") return !args.some((argument) => ["-delete", "-exec", "-execdir", "-ok", "-okdir"].includes(argument));
  if (executable === "git") return isReadOnlyGitCommand(args);
  if (["gradle", "gradlew"].includes(executable)) return isReadOnlyGradleCommand(args);
  if (["mvn", "mvnw"].includes(executable)) return isReadOnlyMavenCommand(args);
  if (["npm", "pnpm", "yarn", "bun"].includes(executable)) {
    return ["list", "ls", "why", "outdated", "view", "info"].includes(args.find((argument) => !argument.startsWith("-")) || "");
  }
  return false;
}

function isReadOnlyGitCommand(args) {
  const subcommandIndex = args.findIndex((argument) => !argument.startsWith("-") && !/^[A-Za-z_][A-Za-z0-9_]*=/.test(argument));
  const subcommand = subcommandIndex >= 0 ? args[subcommandIndex] : "";
  const rest = subcommandIndex >= 0 ? args.slice(subcommandIndex + 1) : [];
  if (["status", "diff", "log", "show", "rev-parse", "ls-files", "grep", "blame", "describe", "shortlog"].includes(subcommand)) return true;
  if (subcommand === "branch") return rest.length === 0 || rest.every((argument) => ["--list", "-l", "--show-current", "-a", "--all", "-r", "--remotes", "-v", "-vv", "--contains", "--no-contains"].includes(argument));
  if (subcommand === "tag") return rest.length === 0 || rest.includes("--list") || rest.includes("-l");
  if (subcommand === "remote") return rest.length === 0 || rest[0] === "-v" || rest[0] === "get-url";
  if (subcommand === "config") return rest.some((argument) => ["--get", "--get-all", "--get-regexp", "--list", "-l", "--show-origin"].includes(argument));
  return false;
}

function isReadOnlyGradleCommand(args) {
  const tasks = args.filter((argument) => !argument.startsWith("-"));
  return tasks.length === 0 || tasks.every((task) => [
    "tasks",
    "properties",
    "projects",
    "dependencies",
    "dependencyInsight",
    "buildEnvironment",
    "components",
    "model",
    "help",
  ].includes(task));
}

function isReadOnlyMavenCommand(args) {
  const goals = args.filter((argument) => !argument.startsWith("-"));
  return goals.length === 0 || goals.every((goal) => goal === "help" || goal.startsWith("help:") || goal === "dependency:tree");
}

function consumeVerificationCall(call, pendingPaths, unknownPending) {
  if (isGlobalVerificationCall(call)) {
    pendingPaths.clear();
    return { completed: true, unknownPending: false };
  }
  if (!["diagnostics", "read_file"].includes(call.name)) {
    return { completed: false, unknownPending };
  }
  const path = normalizeToolPath(parseArguments(call)?.path);
  if (!path) return { completed: false, unknownPending };
  for (const pendingPath of [...pendingPaths]) {
    if (pathsRelated(path, pendingPath)) pendingPaths.delete(pendingPath);
  }
  return {
    completed: pendingPaths.size === 0 && !unknownPending,
    unknownPending,
  };
}

function isGlobalVerificationCall(call) {
  if (call.name !== "run_terminal") return false;
  const command = String(parseArguments(call)?.command || "").toLowerCase();
  if (!command) return false;
  if (/\bgit\s+diff\s+--check\b/.test(command)) return true;
  return /(?:^|[;&|]\s*)(?:env\s+(?:\S+=\S+\s+)*)?(?:(?:\.\/)?(?:gradlew|gradle)\s+(?:[^;&|]*\s)?(?:test|check|build|lint|verify)\b|(?:\.\/)?(?:mvnw|mvn)\s+(?:[^;&|]*\s)?(?:test|verify|package)\b|(?:npm|pnpm|yarn|bun)\s+(?:test|check|lint|build|typecheck|run\s+(?:test|check|lint|build|typecheck|verify))\b|cargo\s+(?:test|check|clippy|build)\b|go\s+test\b|pytest\b|python\s+-m\s+(?:pytest|unittest)\b|node\s+--test\b|(?:jest|vitest|tsc|eslint|biome|ruff|mypy|ctest|ninja|xcodebuild)\b|swift\s+test\b)/.test(command);
}

function mutationPaths(call) {
  const args = parseArguments(call);
  if (!args) return [];
  const paths = new Set();
  const add = (value) => {
    const normalized = normalizeToolPath(value);
    if (normalized) paths.add(normalized);
  };
  add(args.path);
  add(args.filePath);
  for (const value of Array.isArray(args.paths) ? args.paths : []) add(value);
  for (const value of Array.isArray(args.files) ? args.files : []) {
    if (typeof value === "string") add(value);
    else if (value && typeof value === "object") add(value.path);
  }
  if (call.name === "apply_patch" && typeof args.patch === "string") {
    for (const line of args.patch.split(/\r?\n/)) {
      const structured = line.match(/^\*\*\* (?:Add|Update|Delete) File:\s+(.+)$/);
      if (structured) add(structured[1]);
      const unified = line.match(/^(?:---|\+\+\+)\s+(.+)$/);
      if (unified) add(unified[1].split("\t", 1)[0]);
    }
  }
  return [...paths];
}

function parseArguments(call) {
  try {
    const value = JSON.parse(call.arguments || "{}");
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

function normalizeToolPath(value) {
  if (typeof value !== "string") return "";
  let path = value.trim().replaceAll("\\", "/");
  if (!path || path === "/dev/null") return "";
  path = path.replace(/^["']|["']$/g, "");
  path = path.replace(/^(?:a|b)\//, "").replace(/^\.\//, "");
  return path.replace(/\/{2,}/g, "/").replace(/\/$/, "");
}

function pathsRelated(left, right) {
  return left === right || left.startsWith(`${right}/`) || right.startsWith(`${left}/`);
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
  const attachments = message.attachments === undefined ? [] : message.attachments;
  if (!Array.isArray(attachments) || attachments.length > 8) {
    throw new Error("Message attachments must contain at most 8 entries");
  }
  return {
    ...message,
    attachments: attachments.map(normalizeAttachment),
  };
}

function normalizeAttachment(attachment) {
  if (!attachment || typeof attachment !== "object" || Array.isArray(attachment)) {
    throw new Error("Attachment entries must be objects");
  }
  if (!["file", "image", "ide_state"].includes(attachment.type)) {
    throw new Error("Attachment type is unsupported");
  }
  validateRequiredString(attachment.id, "Attachment id", 240);
  validateRequiredString(attachment.label, "Attachment label", 1_000);
  validateOptionalString(attachment.path, "Attachment path", 4_000);
  validateOptionalString(attachment.mimeType, "Attachment MIME type", 240);
  validateOptionalString(attachment.data, "Attachment data", 700_000);
  validateOptionalString(attachment.textExcerpt, "Attachment text excerpt", 24_000);
  if (!Number.isSafeInteger(attachment.sizeBytes) || attachment.sizeBytes < 0 || attachment.sizeBytes > 1_000_000) {
    throw new Error("Attachment sizeBytes is invalid");
  }
  if (attachment.metadata !== undefined) {
    if (!attachment.metadata || typeof attachment.metadata !== "object" || Array.isArray(attachment.metadata)) {
      throw new Error("Attachment metadata must be an object");
    }
    const entries = Object.entries(attachment.metadata);
    if (entries.length > 16) throw new Error("Attachment metadata may contain at most 16 entries");
    for (const [key, value] of entries) {
      validateRequiredString(key, "Attachment metadata key", 120);
      validateRequiredString(value, "Attachment metadata value", 1_000);
    }
  }
  if (attachment.type === "image") {
    if (!attachment.mimeType?.startsWith("image/") || !attachment.data) {
      throw new Error("Image attachments require image MIME type and data");
    }
  }
  if (attachment.type === "file" && !attachment.data) {
    throw new Error("File attachments require data");
  }
  if (attachment.type === "ide_state" && !attachment.textExcerpt) {
    throw new Error("IDE-state attachments require a text excerpt");
  }
  return attachment;
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

function validateRequiredString(value, field, maxLength) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${field} is required`);
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

function rootMessage(error) {
  let current = error;
  const seen = new Set();
  while (current?.cause && !seen.has(current.cause)) {
    seen.add(current);
    current = current.cause;
  }
  return String(current?.message || error?.message || error || "Unknown model error");
}

function abortableDelay(delayMs, signal) {
  if (delayMs <= 0) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, delayMs);
    const onAbort = () => {
      clearTimeout(timeout);
      reject(signal.reason || new Error("Run cancelled"));
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function throwIfAborted(signal) {
  if (signal?.aborted) throw signal.reason || new Error("Run cancelled");
}
