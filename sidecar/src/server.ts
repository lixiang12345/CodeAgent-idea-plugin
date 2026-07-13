#!/usr/bin/env node
import { createInterface } from "node:readline";
import path from "node:path";
import { ContextEngine } from "../../vendor/context-engine/src/engine.ts";

interface RequestEnvelope {
  id: string;
  type: "health" | "status" | "index" | "retrieve" | "search" | "shutdown";
  root?: string;
  payload?: Record<string, unknown>;
}

interface ResponseEnvelope {
  id: string;
  type: "result" | "progress" | "error";
  payload?: unknown;
}

const engines = new Map<string, ContextEngine>();
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });

function send(response: ResponseEnvelope): void {
  process.stdout.write(`${JSON.stringify(response)}\n`);
}

function getEngine(rootValue: string | undefined): ContextEngine {
  if (!rootValue) throw new Error("A project root is required");
  const root = path.resolve(rootValue);
  let engine = engines.get(root);
  if (!engine) {
    engine = ContextEngine.open({ root });
    engines.set(root, engine);
  }
  return engine;
}

async function handle(request: RequestEnvelope): Promise<void> {
  switch (request.type) {
    case "health":
      send({
        id: request.id,
        type: "result",
        payload: { ok: true, nodeVersion: process.versions.node, protocolVersion: 1 },
      });
      return;
    case "status": {
      const engine = getEngine(request.root);
      send({
        id: request.id,
        type: "result",
        payload: engine.hasIndex()
          ? { indexed: true, ...engine.stats() }
          : { indexed: false, root: engine.config.root },
      });
      return;
    }
    case "index": {
      const engine = getEngine(request.root);
      const result = await engine.index((progress) => {
        send({ id: request.id, type: "progress", payload: progress });
      });
      send({ id: request.id, type: "result", payload: result });
      return;
    }
    case "retrieve": {
      const engine = getEngine(request.root);
      if (!engine.hasIndex()) throw new Error("Project is not indexed");
      const informationRequest = String(request.payload?.informationRequest ?? "").trim();
      if (!informationRequest) throw new Error("informationRequest is required");
      const packed = await engine.codebaseRetrieval(informationRequest, {
        topK: toOptionalInteger(request.payload?.topK),
        maxTokens: toOptionalInteger(request.payload?.maxTokens),
      });
      send({ id: request.id, type: "result", payload: packed });
      return;
    }
    case "search": {
      const engine = getEngine(request.root);
      if (!engine.hasIndex()) throw new Error("Project is not indexed");
      const query = String(request.payload?.query ?? "").trim();
      if (!query) throw new Error("query is required");
      const hits = await engine.search({
        query,
        topK: toOptionalInteger(request.payload?.topK) ?? 10,
        pathPrefix: typeof request.payload?.pathPrefix === "string" ? request.payload.pathPrefix : undefined,
        mode: request.payload?.mode === "bm25" || request.payload?.mode === "semantic" || request.payload?.mode === "hybrid"
          ? request.payload.mode
          : "auto",
      });
      send({ id: request.id, type: "result", payload: hits });
      return;
    }
    case "shutdown":
      for (const engine of engines.values()) engine.close();
      send({ id: request.id, type: "result", payload: { ok: true } });
      process.exit(0);
  }
}

function toOptionalInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}

input.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let request: RequestEnvelope;
  try {
    request = JSON.parse(trimmed) as RequestEnvelope;
  } catch (error) {
    send({ id: "unknown", type: "error", payload: { message: `Invalid JSON: ${String(error)}` } });
    return;
  }

  void handle(request).catch((error) => {
    send({
      id: request.id,
      type: "error",
      payload: { message: error instanceof Error ? error.message : String(error) },
    });
  });
});

input.on("close", () => {
  for (const engine of engines.values()) engine.close();
});
