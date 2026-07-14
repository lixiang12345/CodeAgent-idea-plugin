#!/usr/bin/env node
import { watch, type FSWatcher } from "node:fs";
import { createInterface } from "node:readline";
import path from "node:path";
import { ContextEngine } from "../../vendor/context-engine/src/engine.ts";
import type { IndexProgress } from "../../vendor/context-engine/src/types.ts";
import type { IndexResult } from "../../vendor/context-engine/src/indexer/indexer.ts";
import {
  McpRuntimeManager,
  type McpServerConfiguration,
} from "./mcp-runtime.ts";

interface RequestEnvelope {
  id: string;
  type:
    | "health"
    | "status"
    | "index"
    | "watch"
    | "unwatch"
    | "retrieve"
    | "search"
    | "mcp.reconcile"
    | "mcp.status"
    | "mcp.start"
    | "mcp.stop"
    | "mcp.restart"
    | "mcp.test"
    | "mcp.call"
    | "shutdown";
  root?: string;
  extraRoots?: IndexRootInput[];
  payload?: Record<string, unknown>;
}

interface IndexRootInput {
  name: string;
  path: string;
  kind?: "code" | "docs";
}

interface ResponseEnvelope {
  id: string;
  type: "result" | "progress" | "error";
  payload?: unknown;
}

interface WorkspaceRuntime {
  engine: ContextEngine;
  root: string;
  roots: string[];
  indexTail: Promise<void>;
  watchers: Map<string, FSWatcher>;
  watchTimer: NodeJS.Timeout | null;
  watchDebounceMs: number;
  pendingChanges: number;
  automaticIndexRuns: number;
  lastAutomaticIndexAt: string | null;
  lastAutomaticResult: IndexResult | null;
  lastWatchError: string | null;
}

const runtimes = new Map<string, WorkspaceRuntime>();
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
const mcpRuntime = new McpRuntimeManager(process.cwd());

function send(response: ResponseEnvelope): void {
  process.stdout.write(`${JSON.stringify(response)}\n`);
}

function getRuntime(rootValue: string | undefined, extraRootValues: IndexRootInput[] | undefined): WorkspaceRuntime {
  if (!rootValue) throw new Error("A project root is required");
  const root = path.resolve(rootValue);
  const extraRoots = normalizeExtraRoots(root, extraRootValues);
  const roots = [root, ...extraRoots.map((entry) => entry.path)];
  let runtime = runtimes.get(root);
  if (!runtime) {
    runtime = {
      engine: ContextEngine.open({ root, extraRoots }),
      root,
      roots,
      indexTail: Promise.resolve(),
      watchers: new Map(),
      watchTimer: null,
      watchDebounceMs: 800,
      pendingChanges: 0,
      automaticIndexRuns: 0,
      lastAutomaticIndexAt: null,
      lastAutomaticResult: null,
      lastWatchError: null,
    };
    runtimes.set(root, runtime);
  } else if (!sameRoots(runtime.roots, roots)) {
    throw new Error("ContextEngine roots changed; restart the sidecar before reconfiguring project roots");
  }
  return runtime;
}

async function handle(request: RequestEnvelope): Promise<void> {
  switch (request.type) {
    case "health":
      send({
        id: request.id,
        type: "result",
        payload: {
          ok: true,
          nodeVersion: process.versions.node,
          protocolVersion: 3,
          capabilities: ["incremental-index", "workspace-watch", "multi-root-index", "mcp-runtime"],
        },
      });
      return;
    case "status": {
      const runtime = getRuntime(request.root, request.extraRoots);
      await runtime.indexTail;
      send({
        id: request.id,
        type: "result",
        payload: statusPayload(runtime),
      });
      return;
    }
    case "index": {
      const runtime = getRuntime(request.root, request.extraRoots);
      const result = await queueIndex(runtime, false, (progress) => {
        send({ id: request.id, type: "progress", payload: progress });
      });
      send({ id: request.id, type: "result", payload: result });
      return;
    }
    case "watch": {
      const runtime = getRuntime(request.root, request.extraRoots);
      if (!runtime.engine.hasIndex()) throw new Error("Project is not indexed");
      startWatching(runtime, toDebounceMs(request.payload?.debounceMs));
      send({ id: request.id, type: "result", payload: statusPayload(runtime) });
      return;
    }
    case "unwatch": {
      const runtime = getRuntime(request.root, request.extraRoots);
      stopWatching(runtime);
      send({ id: request.id, type: "result", payload: statusPayload(runtime) });
      return;
    }
    case "retrieve": {
      const runtime = getRuntime(request.root, request.extraRoots);
      await runtime.indexTail;
      if (!runtime.engine.hasIndex()) throw new Error("Project is not indexed");
      const informationRequest = String(request.payload?.informationRequest ?? "").trim();
      if (!informationRequest) throw new Error("informationRequest is required");
      const packed = await runtime.engine.codebaseRetrieval(informationRequest, {
        topK: toOptionalInteger(request.payload?.topK),
        maxTokens: toOptionalInteger(request.payload?.maxTokens),
      });
      send({ id: request.id, type: "result", payload: packed });
      return;
    }
    case "search": {
      const runtime = getRuntime(request.root, request.extraRoots);
      await runtime.indexTail;
      if (!runtime.engine.hasIndex()) throw new Error("Project is not indexed");
      const query = String(request.payload?.query ?? "").trim();
      if (!query) throw new Error("query is required");
      const hits = await runtime.engine.search({
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
    case "mcp.reconcile": {
      const configurations = Array.isArray(request.payload?.configurations)
        ? request.payload.configurations as McpServerConfiguration[]
        : [];
      send({ id: request.id, type: "result", payload: await mcpRuntime.reconcile(configurations) });
      return;
    }
    case "mcp.status":
      send({ id: request.id, type: "result", payload: mcpRuntime.status() });
      return;
    case "mcp.start":
      send({ id: request.id, type: "result", payload: await mcpRuntime.start(requiredPayloadText(request, "serverId")) });
      return;
    case "mcp.stop":
      send({ id: request.id, type: "result", payload: await mcpRuntime.stop(requiredPayloadText(request, "serverId")) });
      return;
    case "mcp.restart":
      send({ id: request.id, type: "result", payload: await mcpRuntime.restart(requiredPayloadText(request, "serverId")) });
      return;
    case "mcp.test":
      send({ id: request.id, type: "result", payload: await mcpRuntime.test(requiredPayloadText(request, "serverId")) });
      return;
    case "mcp.call": {
      const argumentsValue = request.payload?.arguments;
      const args = argumentsValue && typeof argumentsValue === "object" && !Array.isArray(argumentsValue)
        ? argumentsValue as Record<string, unknown>
        : {};
      const result = await mcpRuntime.call(requiredPayloadText(request, "toolId"), args);
      send({ id: request.id, type: "result", payload: result });
      return;
    }
    case "shutdown":
      await closeAll();
      send({ id: request.id, type: "result", payload: { ok: true } });
      process.exit(0);
  }
}

function queueIndex(
  runtime: WorkspaceRuntime,
  automatic: boolean,
  onProgress?: (progress: IndexProgress) => void,
): Promise<IndexResult> {
  const run = runtime.indexTail
    .catch(() => undefined)
    .then(async () => {
      const result = await runtime.engine.index(onProgress);
      if (automatic) {
        runtime.automaticIndexRuns += 1;
        runtime.lastAutomaticIndexAt = new Date().toISOString();
        runtime.lastAutomaticResult = result;
        runtime.lastWatchError = null;
      }
      return result;
    });
  runtime.indexTail = run.then(
    () => undefined,
    () => undefined,
  );
  return run;
}

function startWatching(runtime: WorkspaceRuntime, debounceMs: number): void {
  runtime.watchDebounceMs = debounceMs;
  runtime.lastWatchError = null;
  for (const root of runtime.roots) {
    if (runtime.watchers.has(root)) continue;
    try {
      const watcher = watch(root, { recursive: true }, (_event, filename) => {
        if (filename && shouldIgnoreWatchPath(filename.toString())) return;
        scheduleAutomaticIndex(runtime);
      });
      watcher.on("error", (error) => {
        runtime.lastWatchError = `Watch failed for ${root}: ${errorMessage(error)}`;
        runtime.watchers.get(root)?.close();
        runtime.watchers.delete(root);
      });
      runtime.watchers.set(root, watcher);
    } catch (error) {
      runtime.lastWatchError = `Watch failed for ${root}: ${errorMessage(error)}`;
    }
  }
}

function scheduleAutomaticIndex(runtime: WorkspaceRuntime): void {
  runtime.pendingChanges += 1;
  if (runtime.watchTimer) clearTimeout(runtime.watchTimer);
  runtime.watchTimer = setTimeout(() => {
    runtime.watchTimer = null;
    runtime.pendingChanges = 0;
    void queueIndex(runtime, true).catch((error) => {
      runtime.lastWatchError = errorMessage(error);
      process.stderr.write(`ContextEngine automatic index failed for ${runtime.root}: ${runtime.lastWatchError}\n`);
    });
  }, runtime.watchDebounceMs);
}

function stopWatching(runtime: WorkspaceRuntime, clearError = true): void {
  if (runtime.watchTimer) clearTimeout(runtime.watchTimer);
  runtime.watchTimer = null;
  runtime.pendingChanges = 0;
  for (const watcher of runtime.watchers.values()) watcher.close();
  runtime.watchers.clear();
  if (clearError) runtime.lastWatchError = null;
}

function shouldIgnoreWatchPath(value: string): boolean {
  const relative = value.split(path.sep).join("/");
  const ignoredSegments = new Set([
    ".contextengine",
    ".git",
    ".idea",
    "node_modules",
    "dist",
    "build",
    "out",
  ]);
  return relative.split("/").some((segment) => ignoredSegments.has(segment));
}

function statusPayload(runtime: WorkspaceRuntime): Record<string, unknown> {
  const watch = {
    roots: runtime.roots,
    watching: runtime.watchers.size === runtime.roots.length,
    watchedRootCount: runtime.watchers.size,
    watchDebounceMs: runtime.watchDebounceMs,
    pendingChanges: runtime.pendingChanges,
    automaticIndexRuns: runtime.automaticIndexRuns,
    lastAutomaticIndexAt: runtime.lastAutomaticIndexAt,
    lastAutomaticDurationMs: runtime.lastAutomaticResult?.durationMs ?? null,
    lastAutomaticFilesIndexed: runtime.lastAutomaticResult?.filesIndexed ?? 0,
    lastAutomaticFilesRemoved: runtime.lastAutomaticResult?.filesRemoved ?? 0,
    lastAutomaticChunksWritten: runtime.lastAutomaticResult?.chunksWritten ?? 0,
    watchError: runtime.lastWatchError,
  };
  return runtime.engine.hasIndex()
    ? { indexed: true, ...runtime.engine.stats(), ...watch }
    : { indexed: false, root: runtime.root, ...watch };
}

async function closeAll(): Promise<void> {
  await mcpRuntime.close();
  for (const runtime of runtimes.values()) {
    stopWatching(runtime);
    runtime.engine.close();
  }
  runtimes.clear();
}

function normalizeExtraRoots(root: string, values: IndexRootInput[] | undefined): IndexRootInput[] {
  if (!Array.isArray(values)) return [];
  const names = new Set<string>();
  const paths = new Set<string>([root]);
  const normalized: IndexRootInput[] = [];
  for (const value of values) {
    if (!value || typeof value.name !== "string" || typeof value.path !== "string") continue;
    const name = value.name.trim();
    const resolved = path.resolve(value.path);
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(name) || name === "main" || names.has(name) || paths.has(resolved)) continue;
    names.add(name);
    paths.add(resolved);
    normalized.push({
      name,
      path: resolved,
      kind: value.kind === "docs" ? "docs" : "code",
    });
  }
  return normalized;
}

function sameRoots(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function requiredPayloadText(request: RequestEnvelope, field: string): string {
  const value = request.payload?.[field];
  if (typeof value !== "string" || !value.trim()) throw new Error(`${field} is required`);
  return value.trim();
}

function toOptionalInteger(value: unknown): number | undefined {
  return typeof value === "number" && Number.isInteger(value) ? value : undefined;
}

function toDebounceMs(value: unknown): number {
  const parsed = toOptionalInteger(value) ?? 800;
  return Math.min(10_000, Math.max(100, parsed));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
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
  void closeAll();
});
