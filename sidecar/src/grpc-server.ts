#!/usr/bin/env node
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import { randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import path from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

interface IndexRootInput {
  name: string;
  path: string;
  kind?: "code" | "docs";
}

interface RpcRequest {
  id?: string;
  type?: string;
  root?: string;
  extraRoots?: IndexRootInput[];
  payloadJson?: Buffer | Uint8Array;
}

interface JsonLineResponse {
  id: string;
  type: "result" | "progress" | "error";
  payload?: unknown;
}

type ExecuteCall = grpc.ServerWritableStream<RpcRequest, Record<string, unknown>>;

const directory = path.dirname(fileURLToPath(import.meta.url));
const protoPath = path.join(directory, "context-engine.proto");
const serverPath = path.join(directory, "server.mjs");
const packageDefinition = protoLoader.loadSync(protoPath, {
  defaults: true,
  enums: String,
  keepCase: false,
  longs: String,
  oneofs: true,
});
const descriptor = grpc.loadPackageDefinition(packageDefinition) as unknown as {
  codeagent: {
    context: {
      v1: {
        ContextEngineRpc: grpc.ServiceClientConstructor & { service: grpc.ServiceDefinition };
      };
    };
  };
};

const child = spawn(process.execPath, [serverPath], {
  cwd: process.cwd(),
  env: process.env,
  stdio: ["pipe", "pipe", "pipe"],
});
const pending = new Map<string, ExecuteCall>();
const token = randomBytes(32).toString("base64url");
let closing = false;

child.stderr.on("data", (chunk) => process.stderr.write(chunk));
child.once("exit", (code, signal) => {
  const details = `ContextEngine worker exited (${signal ?? code ?? "unknown"})`;
  for (const call of pending.values()) failCall(call, grpc.status.UNAVAILABLE, details);
  pending.clear();
  if (!closing) process.exitCode = 1;
});

const lines = createInterface({ input: child.stdout });
lines.on("line", (line) => {
  let response: JsonLineResponse;
  try {
    response = JSON.parse(line) as JsonLineResponse;
  } catch (error) {
    process.stderr.write(`Invalid ContextEngine worker response: ${String(error)}\n`);
    return;
  }

  const call = pending.get(response.id);
  if (!call) return;
  if (response.type === "progress") {
    call.write(toRpcEvent(response, call.request.type));
    return;
  }

  pending.delete(response.id);
  call.write(toRpcEvent(response, call.request.type));
  call.end();
});

function execute(call: ExecuteCall): void {
  if (!isAuthorized(call.metadata)) {
    failCall(call, grpc.status.UNAUTHENTICATED, "Missing or invalid sidecar authorization token");
    return;
  }

  const id = call.request.id?.trim() || randomUUID();
  if (!call.request.type?.trim()) {
    failCall(call, grpc.status.INVALID_ARGUMENT, "Request type is required");
    return;
  }
  if (pending.has(id)) {
    failCall(call, grpc.status.ALREADY_EXISTS, `Request '${id}' is already active`);
    return;
  }

  let payload: Record<string, unknown> | undefined;
  const payloadBytes = call.request.payloadJson;
  if (payloadBytes && payloadBytes.length > 0) {
    try {
      payload = JSON.parse(Buffer.from(payloadBytes).toString("utf8")) as Record<string, unknown>;
    } catch (error) {
      failCall(call, grpc.status.INVALID_ARGUMENT, `Invalid payload JSON: ${String(error)}`);
      return;
    }
  }

  pending.set(id, call);
  call.on("cancelled", () => pending.delete(id));
  const request = {
    id,
    type: call.request.type,
    root: call.request.root || undefined,
    extraRoots: call.request.extraRoots?.map((entry) => ({
      name: entry.name,
      path: entry.path,
      kind: entry.kind === "docs" ? "docs" : "code",
    })),
    payload,
  };
  child.stdin.write(`${JSON.stringify(request)}\n`);
}

function toRpcEvent(response: JsonLineResponse, requestType?: string): Record<string, unknown> {
  const type = response.type === "result" ? "RESULT" : response.type === "progress" ? "PROGRESS" : "ERROR";
  const payload = requestType === "health" && response.type === "result"
    ? withGrpcCapabilities(response.payload)
    : response.payload;
  return {
    id: response.id,
    type,
    payloadJson: payload === undefined
      ? Buffer.alloc(0)
      : Buffer.from(JSON.stringify(payload), "utf8"),
    errorMessage: response.type === "error"
      ? errorMessageFromPayload(response.payload)
      : "",
  };
}

function withGrpcCapabilities(payload: unknown): unknown {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return payload;
  const record = payload as Record<string, unknown>;
  const capabilities = Array.isArray(record.capabilities) ? record.capabilities.map(String) : [];
  return {
    ...record,
    rpcTransport: "grpc",
    rpcProtocolVersion: 1,
    capabilities: [...new Set([...capabilities, "protobuf-grpc"])],
  };
}

function errorMessageFromPayload(payload: unknown): string {
  if (payload && typeof payload === "object" && "message" in payload) {
    return String((payload as { message?: unknown }).message ?? "ContextEngine request failed");
  }
  return "ContextEngine request failed";
}

function isAuthorized(metadata: grpc.Metadata): boolean {
  return metadata.get("authorization").some((value) => value === `Bearer ${token}`);
}

function failCall(call: ExecuteCall, code: grpc.status, details: string): void {
  call.emit("error", { code, details } satisfies grpc.ServiceError);
}

const grpcServer = new grpc.Server({
  "grpc.max_receive_message_length": 16 * 1024 * 1024,
  "grpc.max_send_message_length": 16 * 1024 * 1024,
});
grpcServer.addService(descriptor.codeagent.context.v1.ContextEngineRpc.service, { execute });
grpcServer.bindAsync("127.0.0.1:0", grpc.ServerCredentials.createInsecure(), (error, port) => {
  if (error) {
    process.stderr.write(`Failed to bind ContextEngine gRPC server: ${error.message}\n`);
    process.exit(1);
    return;
  }
  process.stdout.write(`CODEAGENT_GRPC_READY ${JSON.stringify({ port, token, protocolVersion: 1 })}\n`);
});

async function shutdown(): Promise<void> {
  if (closing) return;
  closing = true;
  grpcServer.tryShutdown(() => undefined);
  if (child.exitCode === null) {
    child.stdin.write(`${JSON.stringify({ id: randomUUID(), type: "shutdown" })}\n`);
    setTimeout(() => child.kill("SIGTERM"), 1_500).unref();
  }
}

process.once("SIGINT", () => void shutdown());
process.once("SIGTERM", () => void shutdown());
process.stdin.resume();
process.stdin.once("close", () => void shutdown());
