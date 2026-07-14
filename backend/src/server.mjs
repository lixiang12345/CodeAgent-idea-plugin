#!/usr/bin/env node
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { AgentRunner, validateRunRequest } from "./agent-runner.mjs";
import { createIntegrationToolRegistryFromEnv } from "./integration-tools.mjs";
import { createModelGatewayFromEnv } from "./model-gateway.mjs";
import { createAuthenticatorFromEnv, SharedTokenAuthenticator } from "./auth.mjs";
import { ProductJobRunner } from "./job-runner.mjs";
import { createProductStoreFromEnv } from "./product-store.mjs";
import { createRuntimeManifestFromEnv, handleProductRequest, handlePublicProductRequest } from "./product-api.mjs";
import { applyAgentProfile, resolveAgentProfile } from "./agent-profile.mjs";
import { PROMPT_VERSION, promptEnhancementMessages } from "./prompt.mjs";
import { contextBudgetFor } from "./context-policy.mjs";
import { createToolCatalog } from "./tool-catalog.mjs";

const OPENAPI_DOCUMENT = JSON.parse(readFileSync(new URL("../openapi.json", import.meta.url), "utf8"));
const DOCS_HTML = readFileSync(new URL("../docs.html", import.meta.url), "utf8");

export function createCodeAgentServer({
  modelGateway,
  integrationTools,
  authenticator,
  productStore,
  productJobRunner,
  runtimeManifest,
  authToken = "",
  corsOrigins = [],
  logger = console,
} = {}) {
  const gateway = modelGateway || createModelGatewayFromEnv();
  const runner = new AgentRunner({ modelGateway: gateway });
  const backendTools = integrationTools || createIntegrationToolRegistryFromEnv(process.env, fetch, gateway);
  const store = productStore || createProductStoreFromEnv();
  const auth = authenticator || (authToken ? new SharedTokenAuthenticator(authToken) : createAuthenticatorFromEnv(process.env, fetch, store));
  const manifest = runtimeManifest || createRuntimeManifestFromEnv();
  const jobs = productJobRunner || new ProductJobRunner({ store, modelGateway: gateway, logger });
  const ready = Promise.resolve(store.initialize()).then(() => jobs.start());
  const runs = new Map();
  const allowedCorsOrigins = new Set(normalizeCorsOrigins(corsOrigins));

  const server = createServer(async (request, response) => {
    try {
      const corsAllowed = applyCors(request, response, allowedCorsOrigins);
      if (request.method === "OPTIONS") {
        if (!request.headers.origin || !corsAllowed) return json(response, 403, { error: "Origin is not allowed" });
        response.writeHead(204);
        response.end();
        return;
      }
      if (request.url === "/health" && request.method === "GET") {
        return json(response, 200, {
          ok: true,
          service: "codeagent-backend",
          protocolVersion: 1,
          provider: gateway.provider || "custom",
          defaultModel: gateway.defaultModel,
        });
      }
      if (request.url === "/openapi.json" && request.method === "GET") {
        return json(response, 200, OPENAPI_DOCUMENT);
      }
      if ((request.url === "/docs" || request.url === "/docs/") && request.method === "GET") {
        return html(response, 200, DOCS_HTML);
      }
      await ready;
      if (await handlePublicProductRequest(request, response, { authenticator: auth, runtimeManifest: manifest })) return;
      const principal = await auth.authenticate(request);
      await store.upsertUser(principal);
      if (await handleProductRequest(request, response, {
        principal,
        authenticator: auth,
        store,
        jobRunner: jobs,
        modelGateway: gateway,
        readJson,
      })) return;
      if (request.url === "/v1/models" && request.method === "GET") {
        const data = typeof gateway.listModels === "function" ? await gateway.listModels() : [];
        return json(response, 200, {
          object: "list",
          provider: gateway.provider || "custom",
          defaultModel: gateway.defaultModel || "",
          data,
        });
      }

      if (request.url === "/v1/tools" && request.method === "GET") {
        return json(response, 200, {
          object: "list",
          data: backendTools.list(),
        });
      }

      const backendToolMatch = request.url?.match(/^\/v1\/tools\/([^/?]+)$/);
      if (backendToolMatch && request.method === "POST") {
        const body = await readJson(request);
        const args = body?.arguments;
        const result = await backendTools.execute(decodeURIComponent(backendToolMatch[1]), args, {
          signal: AbortSignal.timeout(120_000),
        });
        return json(response, 200, result);
      }

      if (request.url === "/v1/enhance" && request.method === "POST") {
        const body = await readJson(request);
        const text = typeof body?.text === "string" ? body.text.trim() : "";
        if (!text) {
          const error = new Error("text is required");
          error.statusCode = 400;
          throw error;
        }
        if (text.length > 12_000) {
          const error = new Error("text must be at most 12000 characters");
          error.statusCode = 400;
          throw error;
        }
        const mode = typeof body?.mode === "string" ? body.mode : "agent";
        const model = typeof body?.model === "string" && body.model.trim() ? body.model.trim() : gateway.defaultModel;
        let enhanced = "";
        const turn = await gateway.stream({
          model,
          tools: [],
          messages: promptEnhancementMessages({ text, mode }),
          onTextDelta: (delta) => {
            enhanced += delta || "";
          },
          signal: undefined,
        });
        if (!enhanced.trim() && typeof turn?.content === "string") enhanced = turn.content;
        enhanced = String(enhanced || "").trim();
        if (!enhanced) {
          const error = new Error("Model returned an empty enhanced prompt");
          error.statusCode = 502;
          throw error;
        }
        return json(response, 200, {
          text: enhanced,
          model: model || gateway.defaultModel || "",
          provider: gateway.provider || "custom",
        });
      }

      if (request.url === "/v1/runs" && request.method === "POST") {
        const body = await readJson(request);
        validateRunRequest(body);
        const agentProfile = await resolveAgentProfile({
          store,
          userId: principal.id,
          profileId: body.agentProfileId,
        });
        const effectiveRequest = applyAgentProfile(body, agentProfile);
        const selectedModel = effectiveRequest.model || gateway.defaultModel || "";
        if (selectedModel) effectiveRequest.model = selectedModel;
        const initialToolCatalog = createToolCatalog(agentProfile, effectiveRequest.tools);
        const contextBudget = contextBudgetFor(agentProfile, initialToolCatalog.activeDefinitions());
        const run = new RunSession({ id: randomUUID(), userId: principal.id, response, onClose: () => runs.delete(run.id) });
        runs.set(run.id, run);
        run.emit("run.started", {
          runId: run.id,
          protocolVersion: 1,
          provider: gateway.provider || "custom",
          model: selectedModel,
          agentProfileId: agentProfile.id,
          agentType: agentProfile.agentType,
          maxTurns: agentProfile.maxTurns,
          maxToolCalls: agentProfile.maxToolCalls,
          maxSubagentCalls: agentProfile.maxSubagentCalls,
          verificationPolicy: agentProfile.verificationPolicy,
          promptVersion: PROMPT_VERSION,
          contextWindowTokens: contextBudget.contextWindowTokens,
          inputBudgetTokens: contextBudget.inputBudgetTokens,
          reservedOutputTokens: contextBudget.reservedOutputTokens,
          toolDefinitionTokens: contextBudget.toolDefinitionTokens,
        });
        void runner.run({
          request: effectiveRequest,
          agentProfile,
          emit: run.emit.bind(run),
          awaitToolResult: run.awaitToolResult.bind(run),
          signal: run.signal,
        }).catch((error) => {
          if (!run.signal.aborted) run.emit("run.error", { message: rootMessage(error) });
        }).finally(async () => {
          await store.recordUsage(principal.id, {
            kind: "agent-run",
            units: 1,
            metadata: {
              runId: run.id,
              model: selectedModel,
              agentProfileId: agentProfile.id,
              agentType: agentProfile.agentType,
              promptVersion: PROMPT_VERSION,
              contextWindowTokens: contextBudget.contextWindowTokens,
              inputBudgetTokens: contextBudget.inputBudgetTokens,
              reservedOutputTokens: contextBudget.reservedOutputTokens,
              toolDefinitionTokens: contextBudget.toolDefinitionTokens,
            },
          });
          run.close();
        });
        return;
      }

      const toolResultMatch = request.url?.match(/^\/v1\/runs\/([^/]+)\/tool-results$/);
      if (toolResultMatch && request.method === "POST") {
        const run = requireRun(runs, toolResultMatch[1], principal.id);
        const result = await readJson(request);
        validateToolResult(result);
        run.resolveToolResult(result);
        return json(response, 202, { accepted: true });
      }

      const runMatch = request.url?.match(/^\/v1\/runs\/([^/]+)$/);
      if (runMatch && request.method === "DELETE") {
        const run = requireRun(runs, runMatch[1], principal.id);
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
  server.on("close", () => { void store.close(); });
  return server;
}

class RunSession {
  constructor({ id, userId, response, onClose }) {
    this.id = id;
    this.userId = userId;
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
    const pending = this.pendingTools.get(result.toolCallId);
    if (!pending) throw new Error(`No pending tool call: ${result.toolCallId}`);
    pending.resolve({
      status: result.status,
      output: result.output || "",
      error: result.error || "",
      summary: result.summary,
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

function validateToolResult(result) {
  if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error("Tool result is required");
  if (typeof result.toolCallId !== "string" || !result.toolCallId.trim()) throw new Error("toolCallId is required");
  if (!["completed", "failed", "rejected"].includes(result.status)) throw new Error("Unsupported tool result status");
  for (const field of ["output", "error", "summary"]) {
    if (result[field] !== undefined && typeof result[field] !== "string") throw new Error(`${field} must be a string`);
  }
}


function requireRun(runs, id, userId) {
  const run = runs.get(id);
  if (!run || run.userId !== userId) {
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

function html(response, status, body) {
  response.writeHead(status, { "content-type": "text/html; charset=utf-8" });
  response.end(body);
}

function normalizeCorsOrigins(value) {
  const entries = Array.isArray(value) ? value : String(value || "").split(",");
  return entries.map((origin) => String(origin).trim()).filter(Boolean);
}

function applyCors(request, response, allowedOrigins) {
  const origin = request.headers.origin;
  if (!origin || (!allowedOrigins.has(origin) && !allowedOrigins.has("*"))) return false;
  response.setHeader("access-control-allow-origin", allowedOrigins.has("*") ? "*" : origin);
  response.setHeader("access-control-allow-methods", "GET, POST, PUT, DELETE, OPTIONS");
  response.setHeader("access-control-allow-headers", "Authorization, Content-Type, Accept");
  response.setHeader("access-control-expose-headers", "X-CodeAgent-Run-Id");
  response.setHeader("access-control-max-age", "600");
  response.setHeader("vary", "Origin");
  return true;
}

function rootMessage(error) {
  let current = error;
  while (current?.cause) current = current.cause;
  return current instanceof Error ? current.message : String(current);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number.parseInt(process.env.PORT || "8787", 10);
  const host = process.env.HOST || "127.0.0.1";
  const server = createCodeAgentServer({
    corsOrigins: process.env.CORS_ALLOWED_ORIGINS || "",
  });
  server.listen(port, host, () => console.log(`CodeAgent backend listening on http://${host}:${port}`));
}
