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

test("serves protobuf streaming RPCs with bearer authorization", async (t) => {
  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => child.kill());

  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const service = loaded.codeagent.context.v1.ContextEngineRpc;
  const client = new service(`127.0.0.1:${ready.port}`, grpc.credentials.createInsecure());
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${ready.token}`);

  const event = await execute(client, {
    id: "grpc-health",
    type: "health",
    payloadJson: Buffer.from("{}"),
  }, metadata);
  const payload = JSON.parse(Buffer.from(event.payloadJson).toString("utf8"));
  assert.equal(event.type, "RESULT");
  assert.equal(payload.rpcTransport, "grpc");
  assert.equal(payload.rpcProtocolVersion, 1);
  assert.ok(payload.capabilities.includes("protobuf-grpc"));
  client.close();
});

test("rejects sidecar RPCs without the local bearer token", async (t) => {
  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => child.kill());
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

test("streams an indexed ContextEngine result over protobuf", async (t) => {
  const projectRoot = await mkdtemp(path.join(os.tmpdir(), "codeagent-grpc-"));
  await mkdir(path.join(projectRoot, "src"));
  await writeFile(path.join(projectRoot, "src", "billing.ts"), "export function captureInvoice() { return true; }\n");
  t.after(() => rm(projectRoot, { recursive: true, force: true }));

  const child = spawn(process.execPath, ["dist/grpc-server.mjs"], {
    cwd: root,
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => child.kill());
  const lines = createInterface({ input: child.stdout });
  const [line] = await once(lines, "line");
  const ready = JSON.parse(String(line).replace("CODEAGENT_GRPC_READY ", ""));
  const service = loaded.codeagent.context.v1.ContextEngineRpc;
  const client = new service(`127.0.0.1:${ready.port}`, grpc.credentials.createInsecure());
  const metadata = new grpc.Metadata();
  metadata.set("authorization", `Bearer ${ready.token}`);

  const events = await executeAll(client, {
    id: "grpc-index",
    type: "index",
    root: projectRoot,
    payloadJson: Buffer.from("{}"),
  }, metadata);
  assert.ok(events.some((event) => event.type === "PROGRESS"));
  const result = events.at(-1);
  assert.equal(result.type, "RESULT");
  assert.equal(JSON.parse(Buffer.from(result.payloadJson).toString("utf8")).filesIndexed, 1);
  client.close();
});

function execute(client, request, metadata) {
  return executeAll(client, request, metadata).then((events) => events.at(-1));
}

function executeAll(client, request, metadata) {
  return new Promise((resolve, reject) => {
    const call = client.execute(request, metadata ?? new grpc.Metadata());
    const events = [];
    call.on("data", (event) => events.push(event));
    call.on("end", () => resolve(events));
    call.on("error", reject);
  });
}
