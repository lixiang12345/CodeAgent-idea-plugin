import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { statSync } from "node:fs";
import path from "node:path";
import type { IndexProgress } from "../../vendor/context-engine/src/types.ts";
import type { IndexResult } from "../../vendor/context-engine/src/indexer/indexer.ts";
import { languageForPath, walkSourceFiles } from "../../vendor/context-engine/src/util/fs.ts";

interface IndexRootInput {
  name: string;
  path: string;
  kind?: "code" | "docs";
}

interface RemoteManifestEntry {
  blobHash: string;
  size: number;
  mtimeMs: number;
  language: string;
  rootAlias: string;
}

interface RemoteState {
  workspaceId: string;
  revision: number;
  manifest: Record<string, RemoteManifestEntry>;
}

interface ScannedFile extends RemoteManifestEntry {
  path: string;
  absPath: string;
}

interface RemoteStats {
  root: string;
  roots: string[];
  chunkCount: number;
  fileCount: number;
  hasEmbeddings: boolean;
  lastIndexedAt: string | null;
}

export class HttpContextRuntime {
  private readonly baseUrl: string;
  private readonly apiKey: string;
  private readonly roots: Array<{ name: string; path: string }>;
  private readonly statePath: string;
  private state: RemoteState | null = null;
  private indexed = false;
  private currentStats: RemoteStats;

  constructor(
    private readonly root: string,
    extraRoots: IndexRootInput[],
  ) {
    this.baseUrl = requiredEnvironment("CONTEXTENGINE_HTTP_URL").replace(/\/+$/, "");
    this.apiKey = process.env.CONTEXTENGINE_HTTP_API_KEY?.trim() ?? "";
    this.roots = [
      { name: "main", path: root },
      ...extraRoots.map((entry) => ({ name: entry.name, path: entry.path })),
    ];
    const deploymentHash = createHash("sha256").update(this.baseUrl).digest("hex").slice(0, 12);
    this.statePath = path.join(root, ".contextengine", `http-sync-${deploymentHash}.json`);
    this.currentStats = emptyStats(root, this.roots.map((entry) => entry.path));
  }

  async hasIndex(): Promise<boolean> {
    const workspace = await this.ensureWorkspace();
    const status = await this.jsonRequest(`/v1/workspaces/${encodeURIComponent(workspace.workspaceId)}/status`);
    this.state!.revision = numberValue(status.workspace?.revision, this.state!.revision);
    this.indexed = Boolean(status.indexed);
    this.currentStats = statsPayload(this.root, this.roots, status.stats);
    await this.persistState();
    return this.indexed;
  }

  async stats(): Promise<RemoteStats> {
    await this.hasIndex();
    return this.currentStats;
  }

  async index(onProgress?: (progress: IndexProgress) => void): Promise<IndexResult> {
    const started = Date.now();
    await this.ensureWorkspace();
    const files = await this.scanFiles(onProgress);
    const nextManifest = Object.fromEntries(files.map((file) => [file.path, manifestEntry(file)]));
    const previousManifest = this.state!.manifest;
    const changes: Record<string, unknown>[] = [];
    let filesIndexed = 0;
    let filesRemoved = 0;

    for (const file of files) {
      const previous = previousManifest[file.path];
      if (previous && sameManifestEntry(previous, file)) continue;
      filesIndexed += 1;
      changes.push({
        op: "upsert",
        path: file.path,
        blob_hash: file.blobHash,
        size: file.size,
        mtime_ms: file.mtimeMs,
        language: file.language,
        root_alias: file.rootAlias,
      });
    }
    for (const existingPath of Object.keys(previousManifest)) {
      if (nextManifest[existingPath]) continue;
      filesRemoved += 1;
      changes.push({ op: "delete", path: existingPath });
    }

    let lastJob: Record<string, unknown> | null = null;
    if (changes.length > 0) {
      const filesByHash = new Map(files.map((file) => [file.blobHash, file]));
      const batches = chunk(changes, 2_000);
      for (let index = 0; index < batches.length; index += 1) {
        const batch = batches[index];
        const plan = await this.createPlan(batch);
        for (const hash of stringArray(plan.missing_blobs)) {
          const source = filesByHash.get(hash);
          if (!source) throw new Error(`ContextEngine requested an unknown Blob: ${hash}`);
          await this.uploadBlob(hash, source.absPath);
        }
        const commit = await this.jsonRequest(
          `/v1/workspaces/${encodeURIComponent(this.state!.workspaceId)}/sync/commit`,
          { method: "POST", body: { sync_id: plan.sync_id, auto_index: index === batches.length - 1 } },
        );
        this.state!.revision = numberValue(commit.revision, this.state!.revision + 1);
        lastJob = objectValue(commit.index_job);
      }
      this.state!.manifest = nextManifest;
      await this.persistState();
    } else if (!this.indexed && files.length > 0) {
      const created = await this.jsonRequest(
        `/v1/workspaces/${encodeURIComponent(this.state!.workspaceId)}/index-jobs`,
        { method: "POST", body: { mode: "rebuild" } },
      );
      lastJob = objectValue(created.job);
    }

    let remoteResult: Record<string, unknown> | null = null;
    if (lastJob?.id) {
      remoteResult = await this.waitForIndex(String(lastJob.id), onProgress);
      this.indexed = true;
    } else {
      this.indexed = await this.hasIndex();
    }
    this.currentStats = await this.stats();
    return {
      filesScanned: files.length,
      filesIndexed,
      filesRemoved,
      chunksWritten: numberValue(remoteResult?.chunksWritten ?? remoteResult?.chunks_written, 0),
      embeddingsWritten: numberValue(remoteResult?.embeddingsWritten ?? remoteResult?.embeddings_written, 0),
      durationMs: Date.now() - started,
      roots: this.roots.map((entry) => entry.path),
    };
  }

  async search(input: {
    query: string;
    topK: number;
    pathPrefix?: string;
    mode?: "auto" | "bm25" | "semantic" | "hybrid";
  }): Promise<unknown[]> {
    const workspace = await this.ensureWorkspace();
    const response = await this.jsonRequest(
      `/v1/workspaces/${encodeURIComponent(workspace.workspaceId)}/search`,
      {
        method: "POST",
        body: {
          query: input.query,
          top_k: input.topK,
          path_prefix: input.pathPrefix,
          mode: input.mode ?? "auto",
        },
      },
    );
    return arrayValue(response.results).map((value) => {
      const hit = objectValue(value) ?? {};
      return {
        chunk: {
          path: String(hit.path ?? ""),
          startLine: numberValue(hit.start_line, 1),
          endLine: numberValue(hit.end_line, 1),
          symbol: typeof hit.symbol === "string" ? hit.symbol : undefined,
          language: String(hit.language ?? "text"),
          content: String(hit.content ?? ""),
        },
        preview: String(hit.preview ?? ""),
        score: numberValue(hit.score, 0),
        source: String(hit.source ?? "remote"),
        intent: hit.intent,
        channels: hit.channels,
      };
    });
  }

  async codebaseRetrieval(
    informationRequest: string,
    options: { topK?: number; maxTokens?: number },
  ): Promise<Record<string, unknown>> {
    const workspace = await this.ensureWorkspace();
    const response = await this.jsonRequest(
      `/v1/workspaces/${encodeURIComponent(workspace.workspaceId)}/context`,
      {
        method: "POST",
        body: {
          information_request: informationRequest,
          top_k: options.topK,
          max_tokens: options.maxTokens,
        },
      },
    );
    return {
      task: String(response.task ?? informationRequest),
      packedText: String(response.packed_text ?? ""),
      estimatedTokens: numberValue(response.estimated_tokens, 0),
      truncated: Boolean(response.truncated),
    };
  }

  close(): void {
    // The HTTP deployment owns its own lifecycle; only local watch handles are closed by the sidecar.
  }

  private async scanFiles(onProgress?: (progress: IndexProgress) => void): Promise<ScannedFile[]> {
    const multiRoot = this.roots.length > 1;
    const files: ScannedFile[] = [];
    for (const root of this.roots) {
      for (const walked of walkSourceFiles(root.path, maxFileBytes())) {
        const content = await readFile(walked.absPath);
        const mtimeMs = Math.max(
          0,
          Math.floor(runCatchingNumber(() => statSync(walked.absPath).mtimeMs, Date.now())),
        );
        files.push({
          path: multiRoot ? `${root.name}/${walked.relPath}` : walked.relPath,
          absPath: walked.absPath,
          blobHash: createHash("sha256").update(content).digest("hex"),
          size: content.byteLength,
          mtimeMs,
          language: languageForPath(walked.relPath),
          rootAlias: root.name,
        });
      }
    }
    onProgress?.({
      phase: "scan",
      filesTotal: files.length,
      filesDone: 0,
      chunksTotal: 0,
      message: `Found ${files.length} files across ${this.roots.length} root(s)`,
    });
    return files;
  }

  private async createPlan(changes: Record<string, unknown>[]): Promise<Record<string, unknown>> {
    const endpoint = `/v1/workspaces/${encodeURIComponent(this.state!.workspaceId)}/sync/plan`;
    const create = () => this.jsonRequest(endpoint, {
      method: "POST",
      body: { base_revision: this.state!.revision, changes },
    });
    try {
      return await create();
    } catch (error) {
      if (!(error instanceof ContextHttpError) || error.statusCode !== 409) throw error;
      const workspace = await this.jsonRequest(`/v1/workspaces/${encodeURIComponent(this.state!.workspaceId)}`);
      this.state!.revision = numberValue(workspace.workspace?.revision, this.state!.revision);
      return create();
    }
  }

  private async uploadBlob(hash: string, absPath: string): Promise<void> {
    const content = await readFile(absPath);
    await this.rawRequest(`/v1/blobs/${hash}`, {
      method: "PUT",
      body: new Uint8Array(content),
      headers: { "Content-Type": "application/octet-stream" },
    });
  }

  private async waitForIndex(
    jobId: string,
    onProgress?: (progress: IndexProgress) => void,
  ): Promise<Record<string, unknown> | null> {
    const deadline = Date.now() + 20 * 60_000;
    while (Date.now() < deadline) {
      const response = await this.jsonRequest(`/v1/index-jobs/${encodeURIComponent(jobId)}`);
      const job = objectValue(response.job) ?? {};
      const progress = objectValue(job.progress);
      if (progress) {
        onProgress?.({
          phase: String(progress.phase ?? "index"),
          filesTotal: numberValue(progress.filesTotal ?? progress.files_total, 0),
          filesDone: numberValue(progress.filesDone ?? progress.files_done, 0),
          chunksTotal: numberValue(progress.chunksTotal ?? progress.chunks_total, 0),
          message: typeof progress.message === "string" ? progress.message : undefined,
        });
      }
      const status = String(job.status ?? "");
      if (status === "completed" || status === "succeeded") return objectValue(job.result);
      if (status === "failed" || status === "cancelled") {
        throw new Error(`ContextEngine index job ${status}: ${String(job.error ?? "unknown error")}`);
      }
      await delay(250);
    }
    throw new Error(`ContextEngine index job timed out: ${jobId}`);
  }

  private async ensureWorkspace(): Promise<RemoteState> {
    if (this.state) return this.state;
    this.state = await this.loadState();
    if (this.state) {
      try {
        const response = await this.jsonRequest(`/v1/workspaces/${encodeURIComponent(this.state.workspaceId)}`);
        this.state.revision = numberValue(response.workspace?.revision, this.state.revision);
        return this.state;
      } catch (error) {
        if (!(error instanceof ContextHttpError) || error.statusCode !== 404) throw error;
        this.state = null;
      }
    }
    const workspaceName = `CodeAgent ${path.basename(this.root)} ${createHash("sha256").update(this.root).digest("hex").slice(0, 8)}`;
    const response = await this.jsonRequest("/v1/workspaces", {
      method: "POST",
      body: { name: workspaceName, source_mode: "blob" },
    });
    const workspace = objectValue(response.workspace) ?? {};
    this.state = {
      workspaceId: String(workspace.id ?? ""),
      revision: numberValue(workspace.revision, 0),
      manifest: {},
    };
    if (!this.state.workspaceId) throw new Error("ContextEngine did not return a workspace ID");
    await this.persistState();
    return this.state;
  }

  private async loadState(): Promise<RemoteState | null> {
    try {
      const parsed = JSON.parse(await readFile(this.statePath, "utf8")) as Partial<RemoteState>;
      if (!parsed.workspaceId || !Number.isInteger(parsed.revision) || !parsed.manifest) return null;
      return { workspaceId: parsed.workspaceId, revision: parsed.revision, manifest: parsed.manifest };
    } catch {
      return null;
    }
  }

  private async persistState(): Promise<void> {
    if (!this.state) return;
    await mkdir(path.dirname(this.statePath), { recursive: true });
    await writeFile(this.statePath, `${JSON.stringify(this.state)}\n`, "utf8");
  }

  private async jsonRequest(
    endpoint: string,
    options: { method?: string; body?: unknown } = {},
  ): Promise<Record<string, any>> {
    const response = await this.rawRequest(endpoint, {
      method: options.method,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      headers: options.body === undefined ? undefined : { "Content-Type": "application/json" },
    });
    if (response.status === 204) return {};
    return await response.json() as Record<string, any>;
  }

  private async rawRequest(endpoint: string, init: RequestInit): Promise<Response> {
    const headers = new Headers(init.headers);
    if (this.apiKey) headers.set("Authorization", `Bearer ${this.apiKey}`);
    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${endpoint}`, { ...init, headers, signal: AbortSignal.timeout(120_000) });
    } catch (error) {
      throw new Error(`ContextEngine HTTP request failed for ${this.baseUrl}: ${errorMessage(error)}`);
    }
    if (response.ok) return response;
    const body = await response.text();
    throw new ContextHttpError(response.status, httpErrorMessage(body));
  }
}

class ContextHttpError extends Error {
  constructor(readonly statusCode: number, message: string) {
    super(`ContextEngine HTTP ${statusCode}: ${message}`);
  }
}

function manifestEntry(file: ScannedFile): RemoteManifestEntry {
  return {
    blobHash: file.blobHash,
    size: file.size,
    mtimeMs: file.mtimeMs,
    language: file.language,
    rootAlias: file.rootAlias,
  };
}

function sameManifestEntry(left: RemoteManifestEntry, right: RemoteManifestEntry): boolean {
  return left.blobHash === right.blobHash
    && left.size === right.size
    && left.mtimeMs === right.mtimeMs
    && left.language === right.language
    && left.rootAlias === right.rootAlias;
}

function statsPayload(
  root: string,
  roots: Array<{ path: string }>,
  value: unknown,
): RemoteStats {
  const stats = objectValue(value) ?? {};
  return {
    root,
    roots: roots.map((entry) => entry.path),
    chunkCount: numberValue(stats.chunkCount ?? stats.chunk_count, 0),
    fileCount: numberValue(stats.fileCount ?? stats.file_count, 0),
    hasEmbeddings: Boolean(stats.hasEmbeddings ?? stats.has_embeddings),
    lastIndexedAt: stringValue(stats.lastIndexedAt ?? stats.last_indexed_at),
  };
}

function emptyStats(root: string, roots: string[]): RemoteStats {
  return { root, roots, chunkCount: 0, fileCount: 0, hasEmbeddings: false, lastIndexedAt: null };
}

function maxFileBytes(): number {
  const parsed = Number(process.env.CONTEXTENGINE_MAX_FILE_BYTES ?? 2 * 1024 * 1024);
  return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 2 * 1024 * 1024;
}

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required for remote ContextEngine mode`);
  return value;
}

function httpErrorMessage(body: string): string {
  try {
    const parsed = JSON.parse(body);
    const message = String(parsed?.error?.message ?? parsed?.message ?? body);
    const details = Array.isArray(parsed?.error?.details)
      ? parsed.error.details.map((issue: unknown) => {
        const value = objectValue(issue) ?? {};
        const issuePath = arrayValue(value.path).map(String).join(".");
        const issueMessage = String(value.message ?? "invalid value");
        return issuePath ? `${issuePath}: ${issueMessage}` : issueMessage;
      }).join("; ")
      : "";
    return details ? `${message} (${details})` : message;
  } catch {
    return body || "Empty response body";
  }
}

function objectValue(value: unknown): Record<string, any> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, any> : null;
}

function arrayValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function stringArray(value: unknown): string[] {
  return arrayValue(value).filter((entry): entry is string => typeof entry === "string");
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}

function runCatchingNumber(block: () => number, fallback: number): number {
  try {
    return block();
  } catch {
    return fallback;
  }
}

function chunk<T>(values: T[], size: number): T[][] {
  const result: T[][] = [];
  for (let index = 0; index < values.length; index += size) result.push(values.slice(index, index + size));
  return result;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
