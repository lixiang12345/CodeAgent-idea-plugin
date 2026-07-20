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

type RpcCall = grpc.ServerWritableStream<Record<string, unknown>, Record<string, unknown>>;
type PendingCall = { call: RpcCall; requestType: string };

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
        ContextRuntimeRpc: grpc.ServiceClientConstructor & { service: grpc.ServiceDefinition };
        McpRuntimeRpc: grpc.ServiceClientConstructor & { service: grpc.ServiceDefinition };
        AcpRuntimeRpc: grpc.ServiceClientConstructor & { service: grpc.ServiceDefinition };
      };
    };
  };
};

const child = spawn(process.execPath, [serverPath], {
  cwd: process.cwd(),
  env: process.env,
  stdio: ["pipe", "pipe", "pipe"],
});
const pending = new Map<string, PendingCall>();
const token = randomBytes(32).toString("base64url");
let closing = false;

child.stderr.on("data", (chunk) => process.stderr.write(chunk));
child.once("exit", (code, signal) => {
  const details = `ContextEngine worker exited (${signal ?? code ?? "unknown"})`;
  for (const { call } of pending.values()) failCall(call, grpc.status.UNAVAILABLE, details);
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

  const pendingCall = pending.get(response.id);
  if (!pendingCall) return;
  const { call, requestType } = pendingCall;
  if (response.type === "progress") {
    call.write(toRpcEvent(response, requestType));
    return;
  }

  pending.delete(response.id);
  call.write(toRpcEvent(response, requestType));
  call.end();
});

function execute(call: RpcCall): void {
  const request = call.request as RpcRequest;
  const id = request.id?.trim() || randomUUID();
  if (!request.type?.trim()) {
    failCall(call, grpc.status.INVALID_ARGUMENT, "Request type is required");
    return;
  }
  let payload: Record<string, unknown> | undefined;
  const payloadBytes = request.payloadJson;
  if (payloadBytes && payloadBytes.length > 0) {
    try {
      payload = JSON.parse(Buffer.from(payloadBytes).toString("utf8")) as Record<string, unknown>;
    } catch (error) {
      failCall(call, grpc.status.INVALID_ARGUMENT, `Invalid payload JSON: ${String(error)}`);
      return;
    }
  }

  forward(call, {
    id,
    type: request.type,
    root: request.root || undefined,
    extraRoots: request.extraRoots?.map((entry) => ({
      name: entry.name,
      path: entry.path,
      kind: entry.kind === "docs" ? "docs" : "code",
    })),
    payload,
  });
}

function forward(call: RpcCall, request: {
  id?: string;
  type: string;
  root?: string;
  extraRoots?: IndexRootInput[];
  payload?: Record<string, unknown>;
}): void {
  if (!isAuthorized(call.metadata)) {
    failCall(call, grpc.status.UNAUTHENTICATED, "Missing or invalid sidecar authorization token");
    return;
  }
  const id = request.id?.trim() || randomUUID();
  if (pending.has(id)) {
    failCall(call, grpc.status.ALREADY_EXISTS, `Request '${id}' is already active`);
    return;
  }
  pending.set(id, { call, requestType: request.type });
  call.on("cancelled", () => pending.delete(id));
  child.stdin.write(`${JSON.stringify({ ...request, id })}\n`);
}

function context(call: RpcCall, request: Record<string, unknown>, type: string): void {
  forward(call, {
    id: stringField(request, "id"),
    type,
    root: stringField(request, "root"),
    extraRoots: rootsField(request),
  });
}

function contextWatch(call: RpcCall): void {
  const request = call.request;
  contextWithPayload(call, request, "watch", {
    debounceMs: numberField(request, "debounceMs") ?? 800,
  });
}

function contextWithPayload(
  call: RpcCall,
  request: Record<string, unknown>,
  type: string,
  payload: Record<string, unknown>,
): void {
  forward(call, {
    id: stringField(request, "id"),
    type,
    root: stringField(request, "root"),
    extraRoots: rootsField(request),
    payload,
  });
}

function contextRetrieve(call: RpcCall): void {
  const request = call.request;
  contextWithPayload(call, request, "retrieve", {
    informationRequest: stringField(request, "informationRequest") ?? "",
    ...(numberField(request, "topK") === undefined ? {} : { topK: numberField(request, "topK") }),
    ...(numberField(request, "maxTokens") === undefined ? {} : { maxTokens: numberField(request, "maxTokens") }),
  });
}

function contextSearch(call: RpcCall): void {
  const request = call.request;
  contextWithPayload(call, request, "search", {
    query: stringField(request, "query") ?? "",
    topK: numberField(request, "topK") ?? 10,
    pathPrefix: stringField(request, "pathPrefix"),
    mode: stringField(request, "mode") ?? "auto",
  });
}

function mcp(call: RpcCall, request: Record<string, unknown>, type: string): void {
  forward(call, {
    id: stringField(request, "id"),
    type,
    payload: type === "mcp.status" ? undefined : {
      ...(stringField(request, "serverId") ? { serverId: stringField(request, "serverId") } : {}),
      ...(bytesJsonField(request, "configurationsJson") === undefined ? {} : {
        configurations: bytesJsonField(request, "configurationsJson"),
      }),
    },
  });
}

function mcpCall(call: RpcCall): void {
  const request = call.request;
  forward(call, {
    id: stringField(request, "id"),
    type: "mcp.call",
    payload: {
      toolId: stringField(request, "toolId") ?? "",
      arguments: bytesJsonField(request, "argumentsJson") ?? {},
    },
  });
}

function acp(call: RpcCall, request: Record<string, unknown>, type: string): void {
  forward(call, {
    id: stringField(request, "id"),
    type,
    payload: type === "acp.status" ? undefined : {
      ...(stringField(request, "agentId") ? { agentId: stringField(request, "agentId") } : {}),
      ...(bytesJsonField(request, "configurationsJson") === undefined ? {} : {
        configurations: bytesJsonField(request, "configurationsJson"),
      }),
    },
  });
}

function acpPrompt(call: RpcCall): void {
  const request = call.request;
  forward(call, {
    id: stringField(request, "id"),
    type: "acp.prompt",
    payload: {
      agentId: stringField(request, "agentId") ?? "",
      prompt: stringField(request, "prompt") ?? "",
      sessionId: stringField(request, "sessionId") ?? null,
    },
  });
}

function acpCancel(call: RpcCall): void {
  const request = call.request;
  forward(call, {
    id: stringField(request, "id"),
    type: "acp.cancel",
    payload: {
      agentId: stringField(request, "agentId") ?? "",
      sessionId: stringField(request, "sessionId") ?? "",
    },
  });
}

function stringField(request: Record<string, unknown>, field: string): string | undefined {
  const value = request[field];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(request: Record<string, unknown>, field: string): number | undefined {
  const value = request[field];
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}

function rootsField(request: Record<string, unknown>): IndexRootInput[] | undefined {
  if (!Array.isArray(request.extraRoots)) return undefined;
  return request.extraRoots.map((entry) => {
    const root = entry as Record<string, unknown>;
    return {
      name: String(root.name ?? ""),
      path: String(root.path ?? ""),
      kind: root.kind === "docs" ? "docs" : "code",
    };
  });
}

function bytesJsonField(request: Record<string, unknown>, field: string): unknown {
  const value = request[field];
  if (!value || !(value instanceof Uint8Array) || value.length === 0) return undefined;
  try {
    return JSON.parse(Buffer.from(value).toString("utf8"));
  } catch {
    throw new Error(`${field} must contain valid JSON`);
  }
}

function handleRpc(call: RpcCall, action: () => void): void {
  try {
    action();
  } catch (error) {
    failCall(call, grpc.status.INVALID_ARGUMENT, error instanceof Error ? error.message : String(error));
  }
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

function failCall(call: RpcCall, code: grpc.status, details: string): void {
  call.emit("error", { code, details } satisfies grpc.ServiceError);
}

const grpcServer = new grpc.Server({
  "grpc.max_receive_message_length": 16 * 1024 * 1024,
  "grpc.max_send_message_length": 16 * 1024 * 1024,
});
const services = descriptor.codeagent.context.v1;
grpcServer.addService(services.ContextEngineRpc.service, {
  execute: (call: RpcCall) => handleRpc(call, () => execute(call)),
});
grpcServer.addService(services.ContextRuntimeRpc.service, {
  health: (call: RpcCall) => handleRpc(call, () => forward(call, {
    id: stringField(call.request, "id"),
    type: "health",
  })),
  status: (call: RpcCall) => handleRpc(call, () => context(call, call.request, "status")),
  index: (call: RpcCall) => handleRpc(call, () => context(call, call.request, "index")),
  watch: (call: RpcCall) => handleRpc(call, () => contextWatch(call)),
  unwatch: (call: RpcCall) => handleRpc(call, () => context(call, call.request, "unwatch")),
  retrieve: (call: RpcCall) => handleRpc(call, () => contextRetrieve(call)),
  search: (call: RpcCall) => handleRpc(call, () => contextSearch(call)),
});
grpcServer.addService(services.McpRuntimeRpc.service, {
  reconcile: (call: RpcCall) => handleRpc(call, () => mcp(call, call.request, "mcp.reconcile")),
  status: (call: RpcCall) => handleRpc(call, () => mcp(call, call.request, "mcp.status")),
  start: (call: RpcCall) => handleRpc(call, () => mcp(call, call.request, "mcp.start")),
  stop: (call: RpcCall) => handleRpc(call, () => mcp(call, call.request, "mcp.stop")),
  restart: (call: RpcCall) => handleRpc(call, () => mcp(call, call.request, "mcp.restart")),
  test: (call: RpcCall) => handleRpc(call, () => mcp(call, call.request, "mcp.test")),
  call: (call: RpcCall) => handleRpc(call, () => mcpCall(call)),
});
grpcServer.addService(services.AcpRuntimeRpc.service, {
  reconcile: (call: RpcCall) => handleRpc(call, () => acp(call, call.request, "acp.reconcile")),
  status: (call: RpcCall) => handleRpc(call, () => acp(call, call.request, "acp.status")),
  start: (call: RpcCall) => handleRpc(call, () => acp(call, call.request, "acp.start")),
  stop: (call: RpcCall) => handleRpc(call, () => acp(call, call.request, "acp.stop")),
  restart: (call: RpcCall) => handleRpc(call, () => acp(call, call.request, "acp.restart")),
  prompt: (call: RpcCall) => handleRpc(call, () => acpPrompt(call)),
  cancel: (call: RpcCall) => handleRpc(call, () => acpCancel(call)),
});
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
