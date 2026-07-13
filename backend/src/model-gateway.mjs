export function createModelGatewayFromEnv(env = process.env, fetchImpl = fetch) {
  const routed = createRoutedGatewayFromEnv(env, fetchImpl);
  if (routed) return routed;

  const provider = (env.MODEL_PROVIDER || "openai-compatible").trim().toLowerCase();
  const options = {
    endpoint: requiredSetting(env, "MODEL_ENDPOINT"),
    apiKey: env.MODEL_API_KEY || "",
    model: requiredSetting(env, "MODEL"),
    fetchImpl,
  };

  if (["auto", "openai-compatible", "openai-chat"].includes(provider)) {
    return new OpenAIChatGateway(options);
  }
  if (provider === "openai") return new OpenAIResponsesGateway(options);
  if (["grok", "xai"].includes(provider)) return new OpenAIResponsesGateway({ ...options, provider: "grok" });
  if (["anthropic", "claude"].includes(provider)) {
    return new AnthropicMessagesGateway({
      ...options,
      maxTokens: positiveInteger(env.MODEL_MAX_OUTPUT_TOKENS, 8_192),
    });
  }
  if (["gemini", "google"].includes(provider)) {
    return new GeminiGateway(options);
  }
  throw new Error(`Unsupported MODEL_PROVIDER: ${provider}`);
}

export class RoutedModelGateway {
  constructor({ routes, defaultModel }) {
    if (!(routes instanceof Map) || routes.size === 0) throw new Error("At least one model route is required");
    if (!routes.has(defaultModel)) throw new Error(`Default model is not routed: ${defaultModel}`);
    this.routes = routes;
    this.defaultModel = defaultModel;
    this.provider = "multi-provider";
  }

  async listModels() {
    return [...this.routes.entries()].map(([id, gateway]) => ({ id, ownedBy: gateway.provider }));
  }

  async stream(request) {
    const model = request.model || this.defaultModel;
    const gateway = this.routes.get(model);
    if (!gateway) throw new Error(`Model is not enabled: ${model}`);
    return gateway.stream({ ...request, model });
  }
}

export class OpenAIResponsesGateway {
  constructor({ endpoint, apiKey, model, provider = "openai", fetchImpl = fetch }) {
    this.endpoint = endpoint.replace(/\/$/, "");
    this.apiKey = apiKey;
    this.defaultModel = model;
    this.provider = provider;
    this.fetchImpl = fetchImpl;
  }

  async listModels({ signal } = {}) {
    const response = await this.fetchImpl(openAIModelsUrl(this.endpoint), {
      signal,
      headers: this.#headers("application/json"),
    });
    const body = await readSuccessfulJson(response, "Model discovery");
    return normalizeModels(body?.data);
  }

  async stream({ messages, tools, model, signal, onTextDelta }) {
    const response = await this.fetchImpl(openAIResponsesUrl(this.endpoint), {
      method: "POST",
      signal,
      headers: this.#headers("text/event-stream, application/json"),
      body: JSON.stringify({
        model: model || this.defaultModel,
        input: toOpenAIResponseInput(messages),
        tools: tools.length ? tools.map(toOpenAIResponseTool) : undefined,
        stream: true,
      }),
    });

    await requireSuccessfulResponse(response, "Model request");
    if (!isEventStream(response)) return completeOpenAIResponseTurn(await response.json());

    const accumulator = new OpenAIResponsesStreamAccumulator(onTextDelta);
    await consumeSse(response.body, (event) => accumulator.accept(event.data));
    return accumulator.finish();
  }

  #headers(accept) {
    return {
      "content-type": "application/json",
      accept,
      ...(this.apiKey ? { authorization: `Bearer ${this.apiKey}` } : {}),
    };
  }
}

export class OpenAIChatGateway {
  constructor({ endpoint, apiKey, model, fetchImpl = fetch }) {
    this.endpoint = endpoint.replace(/\/$/, "");
    this.apiKey = apiKey;
    this.defaultModel = model;
    this.provider = "openai-compatible";
    this.fetchImpl = fetchImpl;
  }

  async listModels({ signal } = {}) {
    const response = await this.fetchImpl(openAIModelsUrl(this.endpoint), {
      signal,
      headers: this.#headers("application/json"),
    });
    const body = await readSuccessfulJson(response, "Model discovery");
    return normalizeModels(body?.data);
  }

  async stream({ messages, tools, model, signal, onTextDelta }) {
    const response = await this.fetchImpl(openAIChatUrl(this.endpoint), {
      method: "POST",
      signal,
      headers: this.#headers("text/event-stream, application/json"),
      body: JSON.stringify({
        model: model || this.defaultModel,
        messages: messages.map(toOpenAIMessage),
        tools: tools.length ? tools.map(toOpenAITool) : undefined,
        stream: true,
      }),
    });

    await requireSuccessfulResponse(response, "Model request");
    if (!isEventStream(response)) return completeOpenAITurn(await response.json());

    const accumulator = new OpenAIStreamAccumulator(onTextDelta);
    await consumeSse(response.body, (event) => accumulator.accept(event.data));
    return accumulator.finish();
  }

  #headers(accept) {
    return {
      "content-type": "application/json",
      accept,
      ...(this.apiKey ? { authorization: `Bearer ${this.apiKey}` } : {}),
    };
  }
}

export class AnthropicMessagesGateway {
  constructor({ endpoint, apiKey, model, maxTokens = 8_192, fetchImpl = fetch }) {
    this.endpoint = endpoint.replace(/\/$/, "");
    this.apiKey = apiKey;
    this.defaultModel = model;
    this.maxTokens = maxTokens;
    this.provider = "anthropic";
    this.fetchImpl = fetchImpl;
  }

  async listModels({ signal } = {}) {
    const response = await this.fetchImpl(anthropicModelsUrl(this.endpoint), {
      signal,
      headers: this.#headers("application/json"),
    });
    const body = await readSuccessfulJson(response, "Model discovery");
    return normalizeModels(body?.data);
  }

  async stream({ messages, tools, model, signal, onTextDelta }) {
    const { system, providerMessages } = toAnthropicMessages(messages);
    const response = await this.fetchImpl(anthropicMessagesUrl(this.endpoint), {
      method: "POST",
      signal,
      headers: this.#headers("text/event-stream, application/json"),
      body: JSON.stringify({
        model: model || this.defaultModel,
        max_tokens: this.maxTokens,
        system: system || undefined,
        messages: providerMessages,
        tools: tools.length ? tools.map(toAnthropicTool) : undefined,
        stream: true,
      }),
    });

    await requireSuccessfulResponse(response, "Model request");
    if (!isEventStream(response)) return completeAnthropicTurn(await response.json());

    const accumulator = new AnthropicStreamAccumulator(onTextDelta);
    await consumeSse(response.body, (event) => accumulator.accept(event.data));
    return accumulator.finish();
  }

  #headers(accept) {
    return {
      "content-type": "application/json",
      accept,
      "anthropic-version": "2023-06-01",
      ...(this.apiKey ? { "x-api-key": this.apiKey } : {}),
    };
  }
}

export class GeminiGateway {
  constructor({ endpoint, apiKey, model, fetchImpl = fetch }) {
    this.endpoint = endpoint.replace(/\/$/, "");
    this.apiKey = apiKey;
    this.defaultModel = stripGeminiModelPrefix(model);
    this.provider = "gemini";
    this.fetchImpl = fetchImpl;
  }

  async listModels({ signal } = {}) {
    const response = await this.fetchImpl(`${geminiVersionRoot(this.endpoint)}/models?pageSize=1000`, {
      signal,
      headers: this.#headers("application/json"),
    });
    const body = await readSuccessfulJson(response, "Model discovery");
    return normalizeModels(body?.models?.filter((model) =>
      !Array.isArray(model.supportedGenerationMethods) || model.supportedGenerationMethods.includes("generateContent")
    ), (model) => stripGeminiModelPrefix(model.name));
  }

  async stream({ messages, tools, model, signal, onTextDelta }) {
    const selectedModel = stripGeminiModelPrefix(model || this.defaultModel);
    const { systemInstruction, contents } = toGeminiContents(messages);
    const modelPath = selectedModel.split("/").map(encodeURIComponent).join("/");
    const url = `${geminiVersionRoot(this.endpoint)}/models/${modelPath}:streamGenerateContent?alt=sse`;
    const response = await this.fetchImpl(url, {
      method: "POST",
      signal,
      headers: this.#headers("text/event-stream, application/json"),
      body: JSON.stringify({
        systemInstruction: systemInstruction ? { parts: [{ text: systemInstruction }] } : undefined,
        contents,
        tools: tools.length ? [{ functionDeclarations: tools.map(toGeminiTool) }] : undefined,
      }),
    });

    await requireSuccessfulResponse(response, "Model request");
    if (!isEventStream(response)) return completeGeminiTurn(await response.json());

    const accumulator = new GeminiStreamAccumulator(onTextDelta);
    await consumeSse(response.body, (event) => accumulator.accept(event.data));
    return accumulator.finish();
  }

  #headers(accept) {
    return {
      "content-type": "application/json",
      accept,
      ...(this.apiKey ? { "x-goog-api-key": this.apiKey } : {}),
    };
  }
}

class OpenAIResponsesStreamAccumulator {
  constructor(onTextDelta) {
    this.onTextDelta = onTextDelta;
    this.content = "";
    this.toolCalls = new Map();
  }

  accept(chunk) {
    if (!chunk || chunk === "[DONE]") return;
    const event = JSON.parse(chunk);
    if (event.type === "error" || event.type === "response.failed") {
      throw new Error(event.error?.message || event.response?.error?.message || "Model stream failed");
    }
    if (event.type === "response.output_text.delta" && event.delta) {
      this.content += event.delta;
      this.onTextDelta(event.delta);
    }
    if (event.type === "response.output_item.added" && event.item?.type === "function_call") {
      this.#setTool(event.item, event.output_index);
    }
    if (event.type === "response.function_call_arguments.delta") {
      const key = event.item_id || `output-${event.output_index}`;
      const call = this.toolCalls.get(key) || {
        id: event.call_id || key,
        name: event.name || "",
        arguments: "",
      };
      call.arguments += event.delta || "";
      this.toolCalls.set(key, call);
    }
    if (event.type === "response.function_call_arguments.done") {
      const key = event.item_id || `output-${event.output_index}`;
      const call = this.toolCalls.get(key) || {
        id: event.call_id || key,
        name: event.name || "",
        arguments: "",
      };
      if (typeof event.arguments === "string") call.arguments = event.arguments;
      this.toolCalls.set(key, call);
    }
    if (event.type === "response.output_item.done" && event.item?.type === "function_call") {
      this.#setTool(event.item, event.output_index);
    }
  }

  #setTool(item, outputIndex) {
    const key = item.id || `output-${outputIndex}`;
    const existing = this.toolCalls.get(key);
    this.toolCalls.set(key, {
      id: item.call_id || existing?.id || item.id || `call-${outputIndex}`,
      name: item.name || existing?.name || "",
      arguments: item.arguments || existing?.arguments || "",
    });
  }

  finish() {
    return normalizedTurn(this.content, [...this.toolCalls.values()].map((call) => ({
      ...call,
      arguments: call.arguments || "{}",
    })));
  }
}

class OpenAIStreamAccumulator {
  constructor(onTextDelta) {
    this.onTextDelta = onTextDelta;
    this.content = "";
    this.toolCalls = new Map();
  }

  accept(chunk) {
    if (!chunk || chunk === "[DONE]") return;
    const parsed = JSON.parse(chunk);
    if (parsed.error) throw new Error(parsed.error.message || "Model stream failed");
    for (const choice of parsed.choices || []) {
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
    return normalizedTurn(this.content, [...this.toolCalls.entries()].map(([index, call]) => ({
      id: call.id || `call-${index}`,
      name: call.name,
      arguments: call.arguments,
    })));
  }
}

class AnthropicStreamAccumulator {
  constructor(onTextDelta) {
    this.onTextDelta = onTextDelta;
    this.content = "";
    this.toolCalls = new Map();
  }

  accept(chunk) {
    if (!chunk) return;
    const event = JSON.parse(chunk);
    if (event.type === "error") throw new Error(event.error?.message || "Model stream failed");
    if (event.type === "content_block_start") {
      const block = event.content_block || {};
      if (block.type === "text" && block.text) this.#appendText(block.text);
      if (block.type === "tool_use") {
        this.toolCalls.set(event.index, {
          id: block.id || `call-${event.index}`,
          name: block.name || "",
          arguments: hasKeys(block.input) ? JSON.stringify(block.input) : "",
        });
      }
    }
    if (event.type === "content_block_delta" && event.delta?.type === "text_delta") {
      this.#appendText(event.delta.text || "");
    }
    if (event.type === "content_block_delta" && event.delta?.type === "input_json_delta") {
      const call = this.toolCalls.get(event.index) || {
        id: `call-${event.index}`,
        name: "",
        arguments: "",
      };
      call.arguments += event.delta.partial_json || "";
      this.toolCalls.set(event.index, call);
    }
  }

  #appendText(delta) {
    if (!delta) return;
    this.content += delta;
    this.onTextDelta(delta);
  }

  finish() {
    return normalizedTurn(this.content, [...this.toolCalls.values()].map((call) => ({
      ...call,
      arguments: call.arguments || "{}",
    })));
  }
}

class GeminiStreamAccumulator {
  constructor(onTextDelta) {
    this.onTextDelta = onTextDelta;
    this.content = "";
    this.toolCalls = [];
  }

  accept(chunk) {
    if (!chunk) return;
    const event = JSON.parse(chunk);
    if (event.error) throw new Error(event.error.message || "Model stream failed");
    for (const part of event.candidates?.[0]?.content?.parts || []) {
      if (typeof part.text === "string" && part.text) {
        this.content += part.text;
        this.onTextDelta(part.text);
      }
      if (part.functionCall) {
        this.toolCalls.push({
          id: part.functionCall.id || `call-${this.toolCalls.length}`,
          name: part.functionCall.name,
          arguments: JSON.stringify(part.functionCall.args || {}),
        });
      }
    }
  }

  finish() {
    return normalizedTurn(this.content, this.toolCalls);
  }
}

function completeOpenAITurn(body) {
  const message = body?.choices?.[0]?.message;
  if (!message) throw new Error("Model returned no choices");
  return normalizedTurn(textContent(message.content), (message.tool_calls || []).map((call, index) => ({
    id: call.id || `call-${index}`,
    name: call.function?.name || "",
    arguments: call.function?.arguments || "{}",
  })));
}

function completeOpenAIResponseTurn(body) {
  if (!Array.isArray(body?.output)) throw new Error("Model returned no response output");
  const text = body.output.flatMap((item) => item.type === "message" ? item.content || [] : [])
    .filter((part) => part.type === "output_text")
    .map((part) => part.text || "")
    .join("");
  const toolCalls = body.output.filter((item) => item.type === "function_call").map((item, index) => ({
    id: item.call_id || item.id || `call-${index}`,
    name: item.name || "",
    arguments: item.arguments || "{}",
  }));
  return normalizedTurn(text, toolCalls);
}

function completeAnthropicTurn(body) {
  if (!Array.isArray(body?.content)) throw new Error("Model returned no content blocks");
  const text = body.content.filter((block) => block.type === "text").map((block) => block.text || "").join("");
  const toolCalls = body.content.filter((block) => block.type === "tool_use").map((block, index) => ({
    id: block.id || `call-${index}`,
    name: block.name || "",
    arguments: JSON.stringify(block.input || {}),
  }));
  return normalizedTurn(text, toolCalls);
}

function completeGeminiTurn(body) {
  const parts = body?.candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) throw new Error("Model returned no candidates");
  const text = parts.filter((part) => typeof part.text === "string").map((part) => part.text).join("");
  const toolCalls = parts.filter((part) => part.functionCall).map((part, index) => ({
    id: part.functionCall.id || `call-${index}`,
    name: part.functionCall.name || "",
    arguments: JSON.stringify(part.functionCall.args || {}),
  }));
  return normalizedTurn(text, toolCalls);
}

function toOpenAIMessage(message) {
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

function toOpenAITool(tool) {
  return {
    type: "function",
    function: { name: tool.name, description: tool.description, parameters: tool.parameters },
  };
}

function toOpenAIResponseInput(messages) {
  const input = [];
  for (const message of messages) {
    if (message.role === "tool") {
      input.push({ type: "function_call_output", call_id: message.toolCallId, output: message.content || "" });
      continue;
    }
    if (message.content) input.push({ role: message.role, content: message.content });
    for (const call of message.toolCalls || []) {
      input.push({
        type: "function_call",
        call_id: call.id,
        name: call.name,
        arguments: call.arguments || "{}",
      });
    }
  }
  return input;
}

function toOpenAIResponseTool(tool) {
  return { type: "function", name: tool.name, description: tool.description, parameters: tool.parameters };
}

function toAnthropicMessages(messages) {
  const system = messages.filter((message) => message.role === "system")
    .map((message) => message.content || "").filter(Boolean).join("\n\n");
  const providerMessages = [];
  for (const message of messages) {
    if (message.role === "system") continue;
    if (message.role === "tool") {
      appendContentMessage(providerMessages, "user", [{
        type: "tool_result",
        tool_use_id: message.toolCallId,
        content: message.content || "",
      }]);
      continue;
    }
    const blocks = [];
    if (message.content) blocks.push({ type: "text", text: message.content });
    for (const call of message.toolCalls || []) {
      blocks.push({ type: "tool_use", id: call.id, name: call.name, input: parseArguments(call.arguments) });
    }
    appendContentMessage(providerMessages, message.role, blocks.length ? blocks : [{ type: "text", text: "" }]);
  }
  return { system, providerMessages };
}

function toAnthropicTool(tool) {
  return { name: tool.name, description: tool.description, input_schema: tool.parameters };
}

function toGeminiContents(messages) {
  const systemInstruction = messages.filter((message) => message.role === "system")
    .map((message) => message.content || "").filter(Boolean).join("\n\n");
  const callNames = new Map();
  const contents = [];
  for (const message of messages) {
    if (message.role === "system") continue;
    if (message.role === "tool") {
      const name = callNames.get(message.toolCallId) || "tool";
      appendContentMessage(contents, "user", [{
        functionResponse: {
          id: message.toolCallId,
          name,
          response: { output: message.content || "" },
        },
      }], "parts");
      continue;
    }
    const parts = [];
    if (message.content) parts.push({ text: message.content });
    for (const call of message.toolCalls || []) {
      callNames.set(call.id, call.name);
      parts.push({ functionCall: { id: call.id, name: call.name, args: parseArguments(call.arguments) } });
    }
    appendContentMessage(contents, message.role === "assistant" ? "model" : "user", parts.length ? parts : [{ text: "" }], "parts");
  }
  return { systemInstruction, contents };
}

function toGeminiTool(tool) {
  return { name: tool.name, description: tool.description, parameters: tool.parameters };
}

function appendContentMessage(messages, role, content, field = "content") {
  const previous = messages.at(-1);
  if (previous?.role === role && Array.isArray(previous[field])) previous[field].push(...content);
  else messages.push({ role, [field]: content });
}

function normalizedTurn(content, toolCalls) {
  return { content: content || null, toolCalls };
}

async function consumeSse(body, onEvent) {
  const decoder = new TextDecoder();
  let buffer = "";
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    let boundary;
    while ((boundary = findEventBoundary(buffer)) !== null) {
      const raw = buffer.slice(0, boundary.index);
      buffer = buffer.slice(boundary.index + boundary.length);
      const event = parseSseEvent(raw);
      if (event.data) onEvent(event);
    }
  }
  buffer += decoder.decode();
  if (buffer.trim()) {
    const event = parseSseEvent(buffer);
    if (event.data) onEvent(event);
  }
}

function findEventBoundary(buffer) {
  const match = /\r?\n\r?\n/.exec(buffer);
  return match ? { index: match.index, length: match[0].length } : null;
}

function parseSseEvent(raw) {
  let type = "message";
  const data = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith("event:")) type = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  return { type, data: data.join("\n") };
}

async function requireSuccessfulResponse(response, operation) {
  if (response.ok) return;
  const body = await response.text();
  let message;
  try {
    const parsed = JSON.parse(body);
    message = parsed?.error?.message || parsed?.message;
  } catch {}
  throw new Error(message || `${operation} failed with HTTP ${response.status}`);
}

async function readSuccessfulJson(response, operation) {
  await requireSuccessfulResponse(response, operation);
  return response.json();
}

function isEventStream(response) {
  return (response.headers.get("content-type") || "").toLowerCase().includes("text/event-stream");
}

function normalizeModels(models, idOf = (model) => model?.id) {
  if (!Array.isArray(models)) return [];
  const seen = new Set();
  return models.flatMap((model) => {
    const id = idOf(model);
    if (typeof id !== "string" || !id.trim() || seen.has(id)) return [];
    seen.add(id);
    return [{ id, ownedBy: model.owned_by || model.ownedBy || model.publisher || undefined }];
  }).sort((left, right) => left.id.localeCompare(right.id));
}

function openAIChatUrl(endpoint) {
  return endpoint.endsWith("/chat/completions") ? endpoint : `${openAIVersionRoot(endpoint)}/chat/completions`;
}

function openAIResponsesUrl(endpoint) {
  return endpoint.endsWith("/responses") ? endpoint : `${openAIVersionRoot(endpoint)}/responses`;
}

function openAIModelsUrl(endpoint) {
  return `${openAIVersionRoot(endpoint)}/models`;
}

function openAIVersionRoot(endpoint) {
  const root = endpoint
    .replace(/\/chat\/completions$/, "")
    .replace(/\/responses$/, "")
    .replace(/\/models$/, "");
  return root.endsWith("/v1") ? root : `${root}/v1`;
}

function anthropicMessagesUrl(endpoint) {
  if (endpoint.endsWith("/v1/messages")) return endpoint;
  return endpoint.endsWith("/v1") ? `${endpoint}/messages` : `${endpoint}/v1/messages`;
}

function anthropicModelsUrl(endpoint) {
  if (endpoint.endsWith("/v1/messages")) return `${endpoint.slice(0, -"/messages".length)}/models`;
  return endpoint.endsWith("/v1") ? `${endpoint}/models` : `${endpoint}/v1/models`;
}

function geminiVersionRoot(endpoint) {
  return /\/v1(?:beta)?$/.test(endpoint) ? endpoint : `${endpoint}/v1beta`;
}

function stripGeminiModelPrefix(model) {
  return String(model).replace(/^models\//, "");
}

function parseArguments(value) {
  if (!value) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : { value: parsed };
  } catch {
    return { raw: value };
  }
}

function textContent(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.map((part) => typeof part === "string" ? part : part?.text || "").join("");
}

function hasKeys(value) {
  return value && typeof value === "object" && Object.keys(value).length > 0;
}

function positiveInteger(value, fallback) {
  const parsed = Number.parseInt(value || "", 10);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function requiredSetting(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function createRoutedGatewayFromEnv(env, fetchImpl) {
  const specs = [
    {
      provider: "openai",
      models: csv(env.OPENAI_MODELS),
      apiKey: env.OPENAI_API_KEY,
      endpoint: env.OPENAI_BASE_URL,
      create: (options) => new OpenAIResponsesGateway(options),
    },
    {
      provider: "anthropic",
      models: csv(env.ANTHROPIC_MODELS),
      apiKey: env.ANTHROPIC_API_KEY,
      endpoint: env.ANTHROPIC_BASE_URL,
      create: (options) => new AnthropicMessagesGateway({
        ...options,
        maxTokens: positiveInteger(env.MODEL_MAX_OUTPUT_TOKENS, 8_192),
      }),
    },
    {
      provider: "grok",
      models: csv(env.GROK_MODELS),
      apiKey: env.GROK_API_KEY,
      endpoint: env.GROK_BASE_URL,
      create: (options) => new OpenAIResponsesGateway({ ...options, provider: "grok" }),
    },
  ];
  if (!specs.some((spec) => spec.models.length > 0 || spec.apiKey)) return null;

  const routes = new Map();
  for (const spec of specs) {
    if (spec.models.length === 0) continue;
    if (!spec.apiKey?.trim()) throw new Error(`${spec.provider.toUpperCase()}_API_KEY is required`);
    const endpoint = spec.endpoint?.trim() || env.MODEL_ENDPOINT?.trim();
    if (!endpoint) throw new Error(`${spec.provider.toUpperCase()}_BASE_URL is required`);
    const gateway = spec.create({
      endpoint,
      apiKey: spec.apiKey,
      model: spec.models[0],
      fetchImpl,
    });
    for (const model of spec.models) {
      if (routes.has(model)) throw new Error(`Model is routed more than once: ${model}`);
      routes.set(model, gateway);
    }
  }

  const defaultModel = (env.MODEL || "").trim() || routes.keys().next().value;
  return new RoutedModelGateway({ routes, defaultModel });
}

function csv(value) {
  return String(value || "").split(",").map((item) => item.trim()).filter(Boolean);
}
