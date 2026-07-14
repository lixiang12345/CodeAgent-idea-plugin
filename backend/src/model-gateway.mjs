export function createModelGatewayFromEnv(env = process.env, fetchImpl = fetch) {
  return new UnifiedNativeGateway({
    endpoint: requiredSetting(env, "MODEL_BASE_URL"),
    apiKey: requiredSetting(env, "MODEL_API_KEY"),
    fetchImpl,
  });
}


export class UnifiedNativeGateway {
  constructor({ endpoint, apiKey, maxTokens = 8_192, fetchImpl = fetch }) {
    this.endpoint = normalizeGatewayEndpoint(endpoint);
    this.apiKey = apiKey;
    this.defaultModel = "";
    this.maxTokens = maxTokens;
    this.fetchImpl = fetchImpl;
    this.provider = "unified-native";
    this.models = null;
    this.routes = new Map();
    this.discoveryPromise = null;
  }

  async listModels({ signal } = {}) {
    await this.#ensureDiscovered(signal);
    return this.models.map(({ id, ownedBy, protocol }) => ({ id, ownedBy, protocol }));
  }

  async stream(request) {
    await this.#ensureDiscovered(request.signal);
    const model = request.model || this.defaultModel;
    const gateway = this.routes.get(model);
    if (!gateway) throw new Error(`Model is not enabled: ${model}`);
    return gateway.stream({ ...request, model });
  }

  async #ensureDiscovered(signal) {
    if (this.models) return;
    if (!this.discoveryPromise) {
      this.discoveryPromise = this.#discover(signal).catch((error) => {
        this.discoveryPromise = null;
        throw error;
      });
    }
    await this.discoveryPromise;
  }

  async #discover(signal) {
    const response = await this.fetchImpl(openAIModelsUrl(this.endpoint), {
      signal,
      headers: {
        accept: "application/json",
        authorization: `Bearer ${this.apiKey}`,
      },
    });
    const body = await readSuccessfulJson(response, "Unified model discovery");
    const models = normalizeUnifiedModels(body?.data);
    if (models.length === 0) throw new Error("Unified model discovery returned no routable models");

    const routes = new Map();
    for (const model of models) {
      routes.set(model.id, createUnifiedRoute(model, {
        endpoint: this.endpoint,
        apiKey: this.apiKey,
        maxTokens: this.maxTokens,
        fetchImpl: this.fetchImpl,
      }));
    }
    const defaultModel = this.defaultModel || models[0].id;
    if (!routes.has(defaultModel)) throw new Error(`Default model is not routed: ${defaultModel}`);
    this.defaultModel = defaultModel;
    this.routes = routes;
    this.models = models;
  }
}

export class OpenAIResponsesGateway {
  constructor({ endpoint, apiKey, model, provider = "openai", maxTokens = 8_192, fetchImpl = fetch }) {
    this.endpoint = endpoint.replace(/\/$/, "");
    this.apiKey = apiKey;
    this.defaultModel = model;
    this.provider = provider;
    this.maxTokens = maxTokens;
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

  async stream({ messages, tools, model, maxOutputTokens, signal, onTextDelta }) {
    const response = await this.fetchImpl(openAIResponsesUrl(this.endpoint), {
      method: "POST",
      signal,
      headers: this.#headers("text/event-stream, application/json"),
      body: JSON.stringify({
        model: model || this.defaultModel,
        input: toOpenAIResponseInput(messages),
        tools: tools.length ? tools.map(toOpenAIResponseTool) : undefined,
        max_output_tokens: outputTokenLimit(maxOutputTokens, this.maxTokens),
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

  async stream({ messages, tools, model, maxOutputTokens, signal, onTextDelta }) {
    const { system, providerMessages } = toAnthropicMessages(messages);
    const response = await this.fetchImpl(anthropicMessagesUrl(this.endpoint), {
      method: "POST",
      signal,
      headers: this.#headers("text/event-stream, application/json"),
      body: JSON.stringify({
        model: model || this.defaultModel,
        max_tokens: outputTokenLimit(maxOutputTokens, this.maxTokens),
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
    const value = idOf(model);
    const id = typeof value === "string" ? value.trim() : "";
    if (!id || seen.has(id)) return [];
    seen.add(id);
    return [{ id, ownedBy: model.owned_by || model.ownedBy || model.publisher || undefined }];
  }).sort((left, right) => left.id.localeCompare(right.id));
}

function normalizeUnifiedModels(models) {
  if (!Array.isArray(models)) return [];
  const seen = new Set();
  return models.flatMap((model) => {
    const id = typeof model?.id === "string" ? model.id.trim() : "";
    if (!id || seen.has(id)) return [];
    const ownedBy = String(model.provider || model.owned_by || model.ownedBy || model.publisher || "").trim().toLowerCase();
    const protocol = normalizeUnifiedProtocol(model.protocol || model.api_protocol || model.apiProtocol);
    if (!protocol) throw new Error(`Model '${id}' is missing supported provider/protocol metadata`);
    seen.add(id);
    return [{ id, ownedBy: ownedBy || protocolProvider(protocol), protocol }];
  }).sort((left, right) => left.id.localeCompare(right.id));
}

function normalizeUnifiedProtocol(value) {
  const protocol = String(value || "").trim().toLowerCase();
  if (["openai", "openai-responses", "responses"].includes(protocol)) return "openai-responses";
  if (["anthropic", "anthropic-messages", "messages"].includes(protocol)) return "anthropic-messages";
  if (["xai", "grok", "xai-responses", "grok-responses"].includes(protocol)) return "xai-responses";
  return null;
}

function protocolProvider(protocol) {
  if (protocol === "anthropic-messages") return "anthropic";
  if (protocol === "xai-responses") return "grok";
  return "openai";
}

function createUnifiedRoute(model, { endpoint, apiKey, maxTokens, fetchImpl }) {
  const options = { endpoint, apiKey, model: model.id, maxTokens, fetchImpl };
  if (model.protocol === "anthropic-messages") return new AnthropicMessagesGateway(options);
  if (model.protocol === "xai-responses") return new OpenAIResponsesGateway({ ...options, provider: "grok" });
  return new OpenAIResponsesGateway(options);
}

function outputTokenLimit(requested, fallback) {
  const configured = Number.isInteger(fallback) && fallback > 0 ? fallback : 8_192;
  return Number.isInteger(requested) && requested > 0 ? Math.min(requested, configured) : configured;
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

function normalizeGatewayEndpoint(value) {
  const url = new URL(value);
  const localHttp = url.protocol === "http:" && ["127.0.0.1", "localhost"].includes(url.hostname);
  if (url.protocol !== "https:" && !localHttp) throw new Error("MODEL_BASE_URL must use HTTPS");
  if (url.username || url.password || url.search || url.hash) {
    throw new Error("MODEL_BASE_URL must not contain credentials, a query, or a fragment");
  }
  return url.toString().replace(/\/$/, "");
}

function requiredSetting(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}
