import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { createCodeAgentServer } from "../src/server.mjs";
import {
  AnthropicMessagesGateway,
  GeminiGateway,
  OpenAIChatGateway,
  OpenAIResponsesGateway,
  createModelGatewayFromEnv,
} from "../src/model-gateway.mjs";
import { composeSystemPrompt } from "../src/prompt.mjs";

test("composes server-owned policy before repository customization", () => {
  const prompt = composeSystemPrompt({
    mode: "agent",
    workspace: {
      guidance: "Use project conventions.",
      rules: [{ name: "Tests", path: ".codeagent/rules/tests.md", content: "Add regression tests." }],
      skills: [{ name: "Review", path: ".agents/skills/review/SKILL.md", content: "Inspect the diff." }],
    },
  });
  assert.ok(prompt.indexOf("Safety and authority") < prompt.indexOf("Workspace rules"));
  assert.match(prompt, /Add regression tests/);
  assert.match(prompt, /Inspect the diff/);
});

test("streams a run and resumes after an IDE tool result", async () => {
  const turns = [
    { content: null, toolCalls: [{ id: "call-1", name: "read_file", arguments: "{\"path\":\"README.md\"}" }] },
    { content: "The README describes CodeAgent.", toolCalls: [] },
  ];
  const modelGateway = {
    async stream({ onTextDelta }) {
      const turn = turns.shift();
      if (turn.content) {
        onTextDelta("The README ");
        onTextDelta("describes CodeAgent.");
      }
      return turn;
    },
  };
  const server = createCodeAgentServer({ modelGateway, authToken: "test-token", logger: { error() {} } });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  try {
    const response = await fetch(`${baseUrl}/v1/runs`, {
      method: "POST",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({
        mode: "agent",
        messages: [{ role: "user", content: "Read the README" }],
        tools: [{ name: "read_file", description: "Read", parameters: { type: "object" } }],
        workspace: {},
      }),
    });
    assert.equal(response.status, 200);
    const events = [];
    let runId;
    for await (const event of parseEvents(response.body)) {
      events.push(event);
      if (event.type === "run.started") runId = event.data.runId;
      if (event.type === "tool.request") {
        const toolResponse = await fetch(`${baseUrl}/v1/runs/${runId}/tool-results`, {
          method: "POST",
          headers: { authorization: "Bearer test-token", "content-type": "application/json" },
          body: JSON.stringify({ toolCallId: "call-1", status: "completed", output: "README contents" }),
        });
        assert.equal(toolResponse.status, 202);
      }
    }

    assert.deepEqual(events.filter((event) => event.type === "message.delta").map((event) => event.data.delta), [
      "The README ",
      "describes CodeAgent.",
    ]);
    assert.ok(events.some((event) => event.type === "run.completed"));
  } finally {
    server.close();
    await once(server, "close");
  }
});

test("rejects unauthorized run requests", async () => {
  const server = createCodeAgentServer({ modelGateway: {}, authToken: "secret", logger: { error() {} } });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/v1/runs`, { method: "POST" });
    assert.equal(response.status, 401);
  } finally {
    server.close();
    await once(server, "close");
  }
});

test("parses CRLF model SSE across chunk boundaries", async () => {
  const encoder = new TextEncoder();
  const chunks = [
    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\r",
    "\n\r\ndata: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}}]}\r\n\r\n",
    "data: [DONE]\r\n\r\n",
  ];
  const gateway = new OpenAIChatGateway({
    endpoint: "https://model.test/v1",
    apiKey: "test",
    model: "test-model",
    fetchImpl: async () => new Response(new ReadableStream({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } }),
  });
  const deltas = [];
  const result = await gateway.stream({
    messages: [{ role: "user", content: "Test" }],
    tools: [],
    signal: new AbortController().signal,
    onTextDelta: (delta) => deltas.push(delta),
  });

  assert.deepEqual(deltas, ["Hello"]);
  assert.equal(result.content, "Hello");
  assert.deepEqual(result.toolCalls, [{ id: "call-1", name: "read_file", arguments: "{}" }]);
});

test("discovers OpenAI-compatible models and keeps protocol selection separate from model names", async () => {
  const requests = [];
  const gateway = createModelGatewayFromEnv({
    MODEL_PROVIDER: "openai-compatible",
    MODEL_ENDPOINT: "https://gateway.test/v1",
    MODEL_API_KEY: "secret",
    MODEL: "claude-via-openai",
  }, async (url, options = {}) => {
    requests.push({ url, options });
    return Response.json({ object: "list", data: [{ id: "claude-via-openai" }, { id: "grok-via-openai" }] });
  });

  const models = await gateway.listModels();

  assert.equal(gateway.provider, "openai-compatible");
  assert.deepEqual(models.map((model) => model.id), ["claude-via-openai", "grok-via-openai"]);
  assert.equal(requests[0].url, "https://gateway.test/v1/models");
  assert.equal(requests[0].options.headers.authorization, "Bearer secret");
});

test("normalizes OpenAI Responses streaming and function call events", async () => {
  let captured;
  const gateway = new OpenAIResponsesGateway({
    endpoint: "https://api.openai.test",
    apiKey: "openai-secret",
    model: "gpt-test",
    fetchImpl: async (url, options) => {
      captured = { url, options, body: JSON.parse(options.body) };
      return sseResponse([
        ["response.output_text.delta", { type: "response.output_text.delta", delta: "Checking " }],
        ["response.output_item.added", { type: "response.output_item.added", output_index: 1, item: { type: "function_call", id: "fc-1", call_id: "call-2", name: "diagnostics", arguments: "" } }],
        ["response.function_call_arguments.delta", { type: "response.function_call_arguments.delta", output_index: 1, item_id: "fc-1", delta: "{\"path\":" }],
        ["response.function_call_arguments.done", { type: "response.function_call_arguments.done", output_index: 1, item_id: "fc-1", arguments: "{\"path\":\"README.md\"}" }],
      ]);
    },
  });
  const deltas = [];

  const result = await gateway.stream({
    messages: [
      { role: "system", content: "System policy" },
      { role: "user", content: "Read it" },
      { role: "assistant", content: null, toolCalls: [{ id: "call-1", name: "read_file", arguments: "{\"path\":\"README.md\"}" }] },
      { role: "tool", toolCallId: "call-1", content: "README contents" },
    ],
    tools: [{ name: "diagnostics", description: "Check", parameters: { type: "object" } }],
    model: "gpt-selected",
    signal: new AbortController().signal,
    onTextDelta: (delta) => deltas.push(delta),
  });

  assert.equal(captured.url, "https://api.openai.test/v1/responses");
  assert.equal(captured.body.model, "gpt-selected");
  assert.equal(captured.body.input[0].role, "system");
  assert.equal(captured.body.input.at(-1).type, "function_call_output");
  assert.equal(captured.body.tools[0].name, "diagnostics");
  assert.deepEqual(deltas, ["Checking "]);
  assert.deepEqual(result.toolCalls, [{ id: "call-2", name: "diagnostics", arguments: "{\"path\":\"README.md\"}" }]);
});

test("builds the fixed multi-provider model registry from environment routes", async () => {
  const gateway = createModelGatewayFromEnv({
    MODEL: "gpt-5.6-sol",
    MODEL_ENDPOINT: "https://gateway.test",
    OPENAI_API_KEY: "gpt-secret",
    OPENAI_MODELS: "gpt-5.6-sol",
    ANTHROPIC_API_KEY: "claude-secret",
    ANTHROPIC_MODELS: "claude-fable-5,claude-opus-4-8,claude-sonnet-5",
    GROK_API_KEY: "grok-secret",
    GROK_MODELS: "grok-4.5",
  });

  assert.equal(gateway.provider, "multi-provider");
  assert.deepEqual((await gateway.listModels()).map((model) => model.id), [
    "gpt-5.6-sol",
    "claude-fable-5",
    "claude-opus-4-8",
    "claude-sonnet-5",
    "grok-4.5",
  ]);
});

test("normalizes Anthropic Messages streaming text and tool calls", async () => {
  let captured;
  const gateway = new AnthropicMessagesGateway({
    endpoint: "https://api.anthropic.test",
    apiKey: "anthropic-secret",
    model: "claude-test",
    fetchImpl: async (url, options) => {
      captured = { url, options, body: JSON.parse(options.body) };
      return sseResponse([
        ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
        ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "Inspecting " } }],
        ["content_block_start", { type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "toolu-1", name: "read_file", input: {} } }],
        ["content_block_delta", { type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: "{\"path\":\"README.md\"}" } }],
        ["message_stop", { type: "message_stop" }],
      ]);
    },
  });
  const deltas = [];

  const result = await gateway.stream({
    messages: [{ role: "system", content: "System policy" }, { role: "user", content: "Read it" }],
    tools: [{ name: "read_file", description: "Read", parameters: { type: "object" } }],
    signal: new AbortController().signal,
    onTextDelta: (delta) => deltas.push(delta),
  });

  assert.equal(captured.url, "https://api.anthropic.test/v1/messages");
  assert.equal(captured.options.headers["x-api-key"], "anthropic-secret");
  assert.equal(captured.body.system, "System policy");
  assert.deepEqual(captured.body.tools[0].input_schema, { type: "object" });
  assert.deepEqual(deltas, ["Inspecting "]);
  assert.equal(result.content, "Inspecting ");
  assert.deepEqual(result.toolCalls, [{ id: "toolu-1", name: "read_file", arguments: "{\"path\":\"README.md\"}" }]);
});

test("normalizes Gemini streaming and sends native function responses", async () => {
  let captured;
  const gateway = new GeminiGateway({
    endpoint: "https://generativelanguage.test",
    apiKey: "gemini-secret",
    model: "models/gemini-test",
    fetchImpl: async (url, options) => {
      captured = { url, options, body: JSON.parse(options.body) };
      return sseResponse([
        ["message", { candidates: [{ content: { parts: [{ text: "Done. " }] } }] }],
        ["message", { candidates: [{ content: { parts: [{ functionCall: { id: "call-2", name: "diagnostics", args: { path: "README.md" } } }] } }] }],
      ]);
    },
  });
  const deltas = [];

  const result = await gateway.stream({
    messages: [
      { role: "system", content: "System policy" },
      { role: "user", content: "Read it" },
      { role: "assistant", content: null, toolCalls: [{ id: "call-1", name: "read_file", arguments: "{\"path\":\"README.md\"}" }] },
      { role: "tool", toolCallId: "call-1", content: "README contents" },
    ],
    tools: [{ name: "diagnostics", description: "Check", parameters: { type: "object" } }],
    signal: new AbortController().signal,
    onTextDelta: (delta) => deltas.push(delta),
  });

  assert.match(captured.url, /\/v1beta\/models\/gemini-test:streamGenerateContent\?alt=sse$/);
  assert.equal(captured.options.headers["x-goog-api-key"], "gemini-secret");
  assert.equal(captured.body.systemInstruction.parts[0].text, "System policy");
  assert.equal(captured.body.contents.at(-1).parts[0].functionResponse.name, "read_file");
  assert.equal(captured.body.tools[0].functionDeclarations[0].name, "diagnostics");
  assert.deepEqual(deltas, ["Done. "]);
  assert.deepEqual(result.toolCalls, [{ id: "call-2", name: "diagnostics", arguments: "{\"path\":\"README.md\"}" }]);
});

test("serves the normalized backend model registry", async () => {
  const modelGateway = {
    provider: "openai-compatible",
    defaultModel: "model-a",
    async listModels() { return [{ id: "model-a" }, { id: "model-b" }]; },
  };
  const server = createCodeAgentServer({ modelGateway, authToken: "test-token", logger: { error() {} } });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/v1/models`, {
      headers: { authorization: "Bearer test-token" },
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      object: "list",
      provider: "openai-compatible",
      defaultModel: "model-a",
      data: [{ id: "model-a" }, { id: "model-b" }],
    });
  } finally {
    server.close();
    await once(server, "close");
  }
});

function sseResponse(events) {
  const body = events.map(([type, data]) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`).join("");
  return new Response(body, { headers: { "content-type": "text/event-stream" } });
}

async function* parseEvents(body) {
  const decoder = new TextDecoder();
  let buffer = "";
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    let boundary;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      if (raw.startsWith(":")) continue;
      const type = raw.split("\n").find((line) => line.startsWith("event:"))?.slice(6).trim();
      const data = raw.split("\n").find((line) => line.startsWith("data:"))?.slice(5).trim();
      if (type && data) yield { type, data: JSON.parse(data) };
    }
  }
}
