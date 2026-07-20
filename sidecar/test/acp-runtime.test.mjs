import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { AcpRuntimeManager } from "../dist/acp-runtime.mjs";

const testRoot = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(testRoot, "fixtures", "echo-acp-agent.mjs");

test("negotiates ACP v1, creates sessions, and streams prompt updates", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-acp-"));
  const manager = new AcpRuntimeManager(root);
  try {
    const snapshot = await manager.reconcile([{
      id: "echo-agent",
      name: "Echo agent",
      enabled: true,
      command: process.execPath,
      args: [fixture],
      timeoutSeconds: 5,
    }]);
    assert.equal(snapshot.state, "ready");
    assert.equal(snapshot.agents[0].protocolVersion, 1);
    assert.equal(snapshot.agents[0].agentName, "CodeAgent Test ACP");
    assert.equal(snapshot.agents[0].loadSession, true);

    const first = await manager.prompt("echo-agent", "hello");
    assert.equal(first.stopReason, "end_turn");
    assert.equal(first.text, "echo:hello");
    assert.equal(first.sessionId, "session-1");

    const resumed = await manager.prompt("echo-agent", "again", first.sessionId);
    assert.equal(resumed.sessionId, first.sessionId);
    assert.equal(resumed.text, "echo:again");
    assert.equal(manager.status().agents[0].sessions.length, 1);
  } finally {
    await manager.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("rejects ACP working directories outside the project", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-acp-root-"));
  const manager = new AcpRuntimeManager(root);
  try {
    await assert.rejects(manager.reconcile([{
      id: "unsafe-agent",
      name: "Unsafe agent",
      enabled: true,
      command: process.execPath,
      args: [fixture],
      cwd: "../outside",
    }]), /must stay inside/);
  } finally {
    await manager.close();
    await rm(root, { recursive: true, force: true });
  }
});
