import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  bearerFetch,
  expandEnvironmentReferences,
  headerFetch,
  importMcpServerConfigurations,
  McpRuntimeManager,
} from "../dist/mcp-runtime.mjs";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(testRoot, "fixtures", "echo-mcp-server.mjs");

test("connects a stdio MCP server, discovers namespaced tools, and executes them", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-mcp-"));
  const manager = new McpRuntimeManager(root, 60_000);
  try {
    const snapshot = await manager.reconcile([
      {
        id: "echo-server",
        name: "Echo server",
        enabled: true,
        transport: "stdio",
        command: process.execPath,
        args: [fixture],
        requiredEnvironment: [],
        timeoutSeconds: 5,
      },
    ]);

    assert.equal(snapshot.state, "ready");
    assert.equal(snapshot.servers[0].state, "ready");
    assert.equal(snapshot.servers[0].serverName, "codeagent-test-mcp");
    assert.equal(snapshot.tools.length, 1);
    assert.match(snapshot.tools[0].id, /^mcp__echo-server_[a-f0-9]{8}__echo_[a-f0-9]{8}$/);
    assert.equal(snapshot.tools[0].risk, "read_only");

    const result = await manager.call(snapshot.tools[0].id, { text: "hello" });
    assert.equal(result.output, "echo:hello");

    const stopped = await manager.stop("echo-server");
    assert.equal(stopped.servers[0].state, "stopped");
    assert.equal(stopped.tools.length, 0);

    const reconciled = await manager.reconcile([
      {
        id: "echo-server",
        name: "Echo server",
        enabled: true,
        transport: "stdio",
        command: process.execPath,
        args: [fixture],
        requiredEnvironment: [],
        timeoutSeconds: 5,
      },
    ]);
    assert.equal(reconciled.servers[0].state, "stopped");
    assert.equal(reconciled.tools.length, 0);

    const restarted = await manager.start("echo-server");
    assert.equal(restarted.servers[0].state, "ready");
    assert.equal(restarted.tools.length, 1);
  } finally {
    await manager.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("reports missing allowlisted environment without leaking a value", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-mcp-env-"));
  const manager = new McpRuntimeManager(root, 60_000);
  try {
    const snapshot = await manager.reconcile([
      {
        id: "missing-env",
        name: "Missing environment",
        enabled: true,
        transport: "stdio",
        command: process.execPath,
        args: [fixture],
        requiredEnvironment: ["CODEAGENT_TEST_MISSING_SECRET"],
        timeoutSeconds: 5,
      },
    ]);

    assert.equal(snapshot.state, "degraded");
    assert.equal(snapshot.servers[0].state, "error");
    assert.match(snapshot.servers[0].lastError, /CODEAGENT_TEST_MISSING_SECRET/);
  } finally {
    await manager.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("injects an OAuth bearer token without exposing it in the URL", async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push({ input: String(input), headers: new Headers(init?.headers) });
    return new Response("ok");
  };
  try {
    const response = await bearerFetch("oauth-secret")("https://mcp.example.test/rpc?visible=yes", {
      headers: { "x-client": "CodeAgent" },
    });
    assert.equal(await response.text(), "ok");
    assert.equal(requests.length, 1);
    assert.equal(requests[0].headers.get("authorization"), "Bearer oauth-secret");
    assert.equal(requests[0].headers.get("x-client"), "CodeAgent");
    assert.doesNotMatch(requests[0].input, /oauth-secret/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("expands ${VAR} and ${VAR:-default} environment references", () => {
  const environment = { PRESENT: "value", EMPTY: "" };
  assert.equal(expandEnvironmentReferences("${PRESENT}", environment), "value");
  assert.equal(expandEnvironmentReferences("a-${PRESENT}-b", environment), "a-value-b");
  assert.equal(expandEnvironmentReferences("${MISSING:-fallback}", environment), "fallback");
  assert.equal(expandEnvironmentReferences("${EMPTY:-fallback}", environment), "fallback");
  assert.equal(expandEnvironmentReferences("${MISSING}", environment), "");
  assert.equal(expandEnvironmentReferences("\\${PRESENT}", environment), "${PRESENT}");
  assert.equal(expandEnvironmentReferences("${A}${B:-b}", { A: "a" }), "ab");
});

test("rejects oversized environment values", () => {
  assert.throws(() => expandEnvironmentReferences("x".repeat(5_000)), /exceeds/);
});

test("imports the original plugin's MCP container shapes", () => {
  const fromArray = importMcpServerConfigurations([
    { name: "Echo", command: "node", args: ["server.mjs"] },
  ]);
  assert.equal(fromArray.length, 1);
  assert.equal(fromArray[0].id, "Echo");
  assert.equal(fromArray[0].transport, "stdio");

  const fromServersKey = importMcpServerConfigurations({
    servers: [{ name: "Remote", type: "http", url: "https://mcp.example.test/rpc" }],
  });
  assert.equal(fromServersKey[0].transport, "streamable-http");

  const fromNameKeyedMap = importMcpServerConfigurations({
    mcpServers: {
      "my server": { command: "node", args: ["a.mjs"] },
      disabledOne: { command: "node", disabled: true },
    },
  });
  assert.equal(fromNameKeyedMap.length, 2);
  assert.equal(fromNameKeyedMap[0].id, "my-server");
  assert.equal(fromNameKeyedMap[0].name, "my server");
  assert.equal(fromNameKeyedMap[1].enabled, false);
});

test("imports literal env and header maps with expansion", () => {
  process.env.CODEAGENT_TEST_MCP_TOKEN = "secret-token";
  try {
    const [configuration] = importMcpServerConfigurations({
      mcpServers: {
        remote: {
          type: "sse",
          url: "https://mcp.example.test/sse",
          env: { API_MODE: "live", API_KEY: "${CODEAGENT_TEST_MCP_TOKEN}" },
          headers: { "X-Api-Key": "${CODEAGENT_TEST_MCP_TOKEN}", "X-Fallback": "${MISSING:-none}" },
        },
      },
    });
    assert.equal(configuration.transport, "sse");
    assert.deepEqual(configuration.env, { API_MODE: "live", API_KEY: "secret-token" });
    assert.deepEqual(configuration.headers, { "X-Api-Key": "secret-token", "X-Fallback": "none" });
  } finally {
    delete process.env.CODEAGENT_TEST_MCP_TOKEN;
  }
});

test("rejects malformed env and header maps", () => {
  assert.throws(
    () => importMcpServerConfigurations({ mcpServers: { a: { command: "node", env: ["x"] } } }),
    /env must be an object/,
  );
  assert.throws(
    () => importMcpServerConfigurations({ mcpServers: { a: { command: "node", env: { "bad name": "x" } } } }),
    /Invalid MCP env name/,
  );
  assert.throws(
    () => importMcpServerConfigurations({ mcpServers: { a: { command: "node", headers: { "X-A": 5 } } } }),
    /must be a string/,
  );
});

test("rejects a configuration without a server container", () => {
  assert.throws(() => importMcpServerConfigurations({ other: [] }), /servers or mcpServers/);
});

test("merges configured headers with an OAuth bearer token", async () => {
  const requests = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    requests.push(new Headers(init?.headers));
    return new Response("ok");
  };
  try {
    await bearerFetch("token", { "X-Api-Key": "k" })("https://mcp.example.test/rpc");
    assert.equal(requests[0].get("authorization"), "Bearer token");
    assert.equal(requests[0].get("x-api-key"), "k");

    await headerFetch({ "X-Only": "1" })("https://mcp.example.test/rpc");
    assert.equal(requests[1].get("x-only"), "1");
    assert.equal(requests[1].get("authorization"), null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
