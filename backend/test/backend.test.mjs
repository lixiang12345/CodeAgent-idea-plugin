import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { createCodeAgentServer } from "../src/server.mjs";
import { OpenAIChatGateway } from "../src/model-gateway.mjs";
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
