import { composeSystemPrompt } from "./prompt.mjs";

export class AgentRunner {
  constructor({ modelGateway, maxTurns = 12 }) {
    this.modelGateway = modelGateway;
    this.maxTurns = maxTurns;
  }

  async run({ request, emit, awaitToolResult, signal }) {
    validateRequest(request);
    const allowedTools = new Set(request.tools.map((tool) => tool.name));
    const messages = [
      { role: "system", content: composeSystemPrompt(request) },
      ...request.messages.map(normalizeMessage),
    ];

    for (let turnIndex = 0; turnIndex < this.maxTurns; turnIndex += 1) {
      throwIfAborted(signal);
      emit("turn.started", { turnIndex });
      const turn = await this.modelGateway.stream({
        messages,
        tools: request.tools,
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
        emit("run.completed", { turnCount: turnIndex + 1 });
        return;
      }

      for (const call of turn.toolCalls) {
        throwIfAborted(signal);
        if (!allowedTools.has(call.name)) {
          messages.push(toolMessage(call.id, `Tool '${call.name}' is not available`));
          continue;
        }
        const resultPromise = awaitToolResult(call.id, signal);
        emit("tool.request", { call, turnIndex });
        const result = await resultPromise;
        emit("tool.completed", { toolCallId: call.id, status: result.status, summary: result.summary });
        messages.push(toolMessage(call.id, result.output || result.error || result.status));
      }
    }
    throw new Error(`Agent exceeded ${this.maxTurns} model turns`);
  }
}

function validateRequest(request) {
  if (!request || typeof request !== "object") throw new Error("Run request is required");
  if (!["agent", "chat", "ask"].includes(request.mode)) throw new Error("Unsupported run mode");
  if (!Array.isArray(request.messages)) throw new Error("messages must be an array");
  if (!Array.isArray(request.tools)) throw new Error("tools must be an array");
  for (const tool of request.tools) {
    if (!tool?.name || typeof tool.name !== "string") throw new Error("Every tool requires a name");
  }
}

function normalizeMessage(message) {
  if (!message || !["user", "assistant", "tool"].includes(message.role)) {
    throw new Error("Conversation messages may only use user, assistant, or tool roles");
  }
  return message;
}

function toolMessage(toolCallId, content) {
  return { role: "tool", toolCallId, content: String(content) };
}

function throwIfAborted(signal) {
  if (signal.aborted) throw signal.reason || new Error("Run cancelled");
}
