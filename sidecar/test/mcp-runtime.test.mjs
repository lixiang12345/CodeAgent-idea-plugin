import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { bearerFetch, McpRuntimeManager } from "../dist/mcp-runtime.mjs";

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
