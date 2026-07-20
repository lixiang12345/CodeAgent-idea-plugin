import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { once } from "node:events";
import { createInterface } from "node:readline";
import { spawn } from "node:child_process";
import path from "node:path";
import test from "node:test";
import os from "node:os";
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";

const root = path.resolve(import.meta.dirname, "..");
const definition = protoLoader.loadSync(path.join(root, "dist/context-engine.proto"), {
  defaults: true,
  enums: String,
  keepCase: false,
  longs: String,
  oneofs: true,
});
const loaded = grpc.loadPackageDefinition(definition);

test("serves the typed ContextRuntime health RPC", async (t) => {
  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => stopServer(child));

  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const service = loaded.codeagent.context.v1.ContextRuntimeRpc;
  const client = new service(`127.0.0.1:${ready.port}`, grpc.credentials.createInsecure());
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${ready.token}`);

  const event = await invoke(client, "health", { id: "typed-health" }, metadata);
  const payload = JSON.parse(Buffer.from(event.payloadJson).toString("utf8"));
  assert.equal(event.type, "RESULT");
  assert.equal(payload.rpcTransport, "grpc");
  assert.equal(payload.rpcProtocolVersion, 1);
  assert.ok(payload.capabilities.includes("protobuf-grpc"));
  client.close();
});

test("keeps the generic Execute RPC compatible with 0.7.18 clients", async (t) => {
  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => stopServer(child));

  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const service = loaded.codeagent.context.v1.ContextEngineRpc;
  const client = new service(`127.0.0.1:${ready.port}`, grpc.credentials.createInsecure());
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${ready.token}`);

  const event = await execute(client, {
    id: "compatibility-health",
    type: "health",
    payloadJson: Buffer.from("{}"),
  }, metadata);
  const payload = JSON.parse(Buffer.from(event.payloadJson).toString("utf8"));
  assert.equal(event.type, "RESULT");
  assert.equal(payload.rpcTransport, "grpc");
  client.close();
});

test("rejects sidecar RPCs without the local bearer token", async (t) => {
  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => stopServer(child));
  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const service = loaded.codeagent.context.v1.ContextEngineRpc;
  const client = new service(`127.0.0.1:${ready.port}`, grpc.credentials.createInsecure());

  await assert.rejects(
    execute(client, { id: "unauthorized", type: "health", payloadJson: Buffer.from("{}") }),
    (error) => error.code === grpc.status.UNAUTHENTICATED,
  );
  client.close();
});

test("streams an indexed result through the typed ContextRuntime service", async (t) => {
  const projectRoot = await mkdtemp(path.join(os.tmpdir(), "codeagent-grpc-"));
  await mkdir(path.join(projectRoot, "src"));
  await writeFile(path.join(projectRoot, "src", "billing.ts"), "export function captureInvoice() { return true; }\n");
  t.after(() => rm(projectRoot, { recursive: true, force: true }));

  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => stopServer(child));
  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const service = loaded.codeagent.context.v1.ContextRuntimeRpc;
  const client = new service(`127.0.0.1:${ready.port}`, grpc.credentials.createInsecure());
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${ready.token}`);

  const events = await invokeAll(client, "index", {
    id: "typed-index",
    root: projectRoot,
  }, metadata);
  assert.ok(events.some((event) => event.type === "PROGRESS"));
  const result = events.at(-1);
  assert.equal(result.type, "RESULT");
  assert.equal(JSON.parse(Buffer.from(result.payloadJson).toString("utf8")).filesIndexed, 1);
  client.close();
});

test("reports MCP and ACP status through their typed services", async (t) => {
  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => stopServer(child));
  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const address = `127.0.0.1:${ready.port}`;
  const credentials = grpc.credentials.createInsecure();
  const mcpClient = new loaded.codeagent.context.v1.McpRuntimeRpc(address, credentials);
  const acpClient = new loaded.codeagent.context.v1.AcpRuntimeRpc(address, credentials);
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${ready.token}`);

  const [mcpEvent, acpEvent] = await Promise.all([
    invoke(mcpClient, "status", { id: "typed-mcp-status" }, metadata),
    invoke(acpClient, "status", { id: "typed-acp-status" }, metadata),
  ]);
  const mcpStatus = JSON.parse(Buffer.from(mcpEvent.payloadJson).toString("utf8"));
  const acpStatus = JSON.parse(Buffer.from(acpEvent.payloadJson).toString("utf8"));
  assert.deepEqual(
    { type: mcpEvent.type, state: mcpStatus.state, servers: mcpStatus.servers },
    { type: "RESULT", state: "idle", servers: [] },
  );
  assert.deepEqual(
    { type: acpEvent.type, state: acpStatus.state, agents: acpStatus.agents },
    { type: "RESULT", state: "idle", agents: [] },
  );
  mcpClient.close();
  acpClient.close();
});

function execute(client, request, metadata) {
  return executeAll(client, request, metadata).then((events) => events.at(-1));
}

function executeAll(client, request, metadata) {
  return invokeAll(client, "execute", request, metadata);
}

function invoke(client, method, request, metadata) {
  return invokeAll(client, method, request, metadata).then((events) => events.at(-1));
}

function invokeAll(client, method, request, metadata) {
  return new Promise((resolve, reject) => {
    const call = client[method](request, metadata ?? new grpc.Metadata());
    const events = [];
    call.on("data", (event) => events.push(event));
    call.on("end", () => resolve(events));
    call.on("error", reject);
  });
}

async function stopServer(child) {
  if (child.exitCode !== null) return;
  const exited = once(child, "exit");
  child.stdin.end();
  await exited;
}
