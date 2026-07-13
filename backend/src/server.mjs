#!/usr/bin/env node
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { AgentRunner } from "./agent-runner.mjs";
import { OpenAIChatGateway } from "./model-gateway.mjs";

export function createCodeAgentServer({ modelGateway, authToken = "", logger = console } = {}) {
  const gateway = modelGateway || new OpenAIChatGateway({
    endpoint: requiredEnv("MODEL_ENDPOINT"),
    apiKey: process.env.MODEL_API_KEY || "",
    model: requiredEnv("MODEL"),
  });
  const runner = new AgentRunner({ modelGateway: gateway });
  const runs = new Map();

  return createServer(async (request, response) => {
    try {
      if (request.url === "/health" && request.method === "GET") {
        return json(response, 200, { ok: true, service: "codeagent-backend", protocolVersion: 1 });
      }
      authorize(request, authToken);

      if (request.url === "/v1/runs" && request.method === "POST") {
        const body = await readJson(request);
        const run = new RunSession({ id: randomUUID(), response, onClose: () => runs.delete(run.id) });
        runs.set(run.id, run);
        run.emit("run.started", { runId: run.id, protocolVersion: 1 });
        void runner.run({
          request: body,
          emit: run.emit.bind(run),
          awaitToolResult: run.awaitToolResult.bind(run),
          signal: run.signal,
        }).catch((error) => {
          if (!run.signal.aborted) run.emit("run.error", { message: rootMessage(error) });
        }).finally(() => run.close());
        return;
      }

      const toolResultMatch = request.url?.match(/^\/v1\/runs\/([^/]+)\/tool-results$/);
      if (toolResultMatch && request.method === "POST") {
        const run = requireRun(runs, toolResultMatch[1]);
        run.resolveToolResult(await readJson(request));
        return json(response, 202, { accepted: true });
      }

      const runMatch = request.url?.match(/^\/v1\/runs\/([^/]+)$/);
      if (runMatch && request.method === "DELETE") {
        const run = requireRun(runs, runMatch[1]);
        run.cancel();
        return json(response, 202, { cancelled: true });
      }

      json(response, 404, { error: "Not found" });
    } catch (error) {
      logger.error?.(error);
      if (!response.headersSent) json(response, error.statusCode || 400, { error: rootMessage(error) });
      else response.end();
    }
  });
}

class RunSession {
  constructor({ id, response, onClose }) {
    this.id = id;
    this.response = response;
    this.onClose = onClose;
    this.abortController = new AbortController();
    this.pendingTools = new Map();
    this.closed = false;
    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-codeagent-run-id": id,
    });
    this.heartbeat = setInterval(() => response.write(": heartbeat\n\n"), 15_000);
    response.on("close", () => {
      if (!this.closed) this.cancel(new Error("Client disconnected"));
    });
  }

  get signal() { return this.abortController.signal; }

  emit(type, payload) {
    if (this.closed) return;
    this.response.write(`event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`);
  }

  awaitToolResult(toolCallId, signal) {
    if (this.pendingTools.has(toolCallId)) throw new Error(`Duplicate tool call ID: ${toolCallId}`);
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (callback, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        signal.removeEventListener("abort", onAbort);
        callback(value);
      };
      const timeout = setTimeout(
        () => finish(reject, new Error(`Timed out waiting for tool result: ${toolCallId}`)),
        600_000,
      );
      const onAbort = () => finish(reject, signal.reason || new Error("Run cancelled"));
      signal.addEventListener("abort", onAbort, { once: true });
      this.pendingTools.set(toolCallId, {
        resolve: (value) => finish(resolve, value),
        reject: (error) => finish(reject, error),
      });
    }).finally(() => this.pendingTools.delete(toolCallId));
  }

  resolveToolResult(result) {
    if (!result?.toolCallId) throw new Error("toolCallId is required");
    const pending = this.pendingTools.get(result.toolCallId);
    if (!pending) throw new Error(`No pending tool call: ${result.toolCallId}`);
    pending.resolve({
      status: String(result.status || "completed"),
      output: typeof result.output === "string" ? result.output : "",
      error: typeof result.error === "string" ? result.error : "",
      summary: typeof result.summary === "string" ? result.summary : undefined,
    });
  }

  cancel(reason = new Error("Run cancelled")) {
    if (!this.signal.aborted) this.abortController.abort(reason);
    this.close();
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.heartbeat);
    for (const pending of this.pendingTools.values()) pending.reject(new Error("Run closed"));
    this.pendingTools.clear();
    this.response.end();
    this.onClose();
  }
}

function authorize(request, token) {
  if (!token) return;
  if (request.headers.authorization !== `Bearer ${token}`) {
    const error = new Error("Unauthorized");
    error.statusCode = 401;
    throw error;
  }
}

function requireRun(runs, id) {
  const run = runs.get(id);
  if (!run) {
    const error = new Error("Run not found");
    error.statusCode = 404;
    throw error;
  }
  return run;
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 2_000_000) throw new Error("Request body exceeds 2 MB");
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function json(response, status, body) {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(body));
}

function requiredEnv(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function rootMessage(error) {
  let current = error;
  while (current?.cause) current = current.cause;
  return current instanceof Error ? current.message : String(current);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number.parseInt(process.env.PORT || "8787", 10);
  const server = createCodeAgentServer({ authToken: process.env.CODEAGENT_AUTH_TOKEN || "" });
  server.listen(port, "0.0.0.0", () => console.log(`CodeAgent backend listening on :${port}`));
}
