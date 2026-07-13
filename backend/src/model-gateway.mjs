export class OpenAIChatGateway {
  constructor({ endpoint, apiKey, model, fetchImpl = fetch }) {
    this.endpoint = endpoint.replace(/\/$/, "");
    this.apiKey = apiKey;
    this.model = model;
    this.fetchImpl = fetchImpl;
  }

  async stream({ messages, tools, signal, onTextDelta }) {
    const url = this.endpoint.endsWith("/chat/completions")
      ? this.endpoint
      : `${this.endpoint}/chat/completions`;
    const response = await this.fetchImpl(url, {
      method: "POST",
      signal,
      headers: {
        "content-type": "application/json",
        accept: "text/event-stream, application/json",
        ...(this.apiKey ? { authorization: `Bearer ${this.apiKey}` } : {}),
      },
      body: JSON.stringify({
        model: this.model,
        messages: messages.map(toApiMessage),
        tools: tools.length ? tools.map(toApiTool) : undefined,
        stream: true,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      let message;
      try { message = JSON.parse(body)?.error?.message; } catch {}
      throw new Error(message || `Model request failed with HTTP ${response.status}`);
    }

    const contentType = response.headers.get("content-type")?.toLowerCase() || "";
    if (!contentType.includes("text/event-stream")) {
      return completeTurn(await response.json());
    }

    const accumulator = new StreamAccumulator(onTextDelta);
    const decoder = new TextDecoder();
    let buffer = "";
    for await (const chunk of response.body) {
      buffer += decoder.decode(chunk, { stream: true });
      let boundary;
      while ((boundary = findEventBoundary(buffer)) !== null) {
        const event = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary.length);
        consumeEvent(event, accumulator);
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) consumeEvent(buffer, accumulator);
    return accumulator.finish();
  }
}

function findEventBoundary(buffer) {
  const match = /\r?\n\r?\n/.exec(buffer);
  return match ? { index: match.index, length: match[0].length } : null;
}

function consumeEvent(raw, accumulator) {
  const data = raw.split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n");
  if (!data || data === "[DONE]") return;
  accumulator.accept(JSON.parse(data));
}

class StreamAccumulator {
  constructor(onTextDelta) {
    this.onTextDelta = onTextDelta;
    this.content = "";
    this.toolCalls = new Map();
  }

  accept(chunk) {
    for (const choice of chunk.choices || []) {
      const delta = choice.delta || {};
      if (typeof delta.content === "string" && delta.content) {
        this.content += delta.content;
        this.onTextDelta(delta.content);
      }
      for (const part of delta.tool_calls || []) {
        const call = this.toolCalls.get(part.index) || { id: "", name: "", arguments: "" };
        if (part.id) call.id += part.id;
        if (part.function?.name) call.name += part.function.name;
        if (part.function?.arguments) call.arguments += part.function.arguments;
        this.toolCalls.set(part.index, call);
      }
    }
  }

  finish() {
    return {
      content: this.content || null,
      toolCalls: [...this.toolCalls.entries()].sort(([a], [b]) => a - b).map(([index, call]) => ({
        id: call.id || `call-${index}`,
        name: call.name,
        arguments: call.arguments,
      })),
    };
  }
}

function completeTurn(body) {
  const message = body?.choices?.[0]?.message;
  if (!message) throw new Error("Model returned no choices");
  return {
    content: message.content ?? null,
    toolCalls: (message.tool_calls || []).map((call) => ({
      id: call.id,
      name: call.function.name,
      arguments: call.function.arguments,
    })),
  };
}

function toApiMessage(message) {
  return {
    role: message.role,
    content: message.content ?? null,
    tool_calls: message.toolCalls?.map((call) => ({
      id: call.id,
      type: "function",
      function: { name: call.name, arguments: call.arguments },
    })),
    tool_call_id: message.toolCallId,
  };
}

function toApiTool(tool) {
  return {
    type: "function",
    function: {
      name: tool.name,
      description: tool.description,
      parameters: tool.parameters,
    },
  };
}
