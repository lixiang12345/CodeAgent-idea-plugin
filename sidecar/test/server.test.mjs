import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, mkdir, rm, unlink, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import os from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { spawn } from "node:child_process";
import test from "node:test";

test("returns an error for unknown JSON Lines request types", async (t) => {
  const child = spawn(process.execPath, ["dist/server.mjs"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(() => child.kill());

  const lines = createInterface({ input: child.stdout });
  const responses = [];
  lines.on("line", (line) => responses.push(JSON.parse(line)));

  child.stdin.write(`${JSON.stringify({ id: "unknown-command", type: "does.not.exist" })}\n`);
  const response = await waitFor(
    responses,
    (item) => item.id === "unknown-command" && item.type === "error",
  );

  assert.match(response.payload.message, /Unknown request type: does\.not\.exist/);

  child.stdin.write(`${JSON.stringify({ id: "shutdown", type: "shutdown" })}\n`);
  await once(child, "exit");
});

test("indexes and retrieves project context over JSON Lines", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-context-"));
  await mkdir(path.join(root, "src"));
  await writeFile(
    path.join(root, "src", "auth.ts"),
    "export function verifyToken(token: string) { return token.startsWith('valid-'); }\n",
  );
  await mkdir(path.join(root, "frontend", "node_modules", "fixture"), { recursive: true });

  const child = spawn(process.execPath, ["dist/server.mjs"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(async () => {
    child.kill();
    await rm(root, { recursive: true, force: true });
  });

  const lines = createInterface({ input: child.stdout });
  const responses = [];
  lines.on("line", (line) => responses.push(JSON.parse(line)));

  child.stdin.write(`${JSON.stringify({ id: "health", type: "health" })}\n`);
  await waitFor(responses, (item) => item.id === "health" && item.type === "result");

  child.stdin.write(`${JSON.stringify({ id: "index", type: "index", root })}\n`);
  await waitFor(responses, (item) => item.id === "index" && item.type === "result", 15_000);

  child.stdin.write(`${JSON.stringify({
    id: "watch",
    type: "watch",
    root,
    payload: { debounceMs: 100 },
  })}\n`);
  const watch = await waitFor(responses, (item) => item.id === "watch" && item.type === "result");
  assert.equal(watch.payload.watching, true);

  await writeFile(path.join(root, "frontend", "node_modules", "fixture", "ignored.ts"), "export const ignored = true;\n");
  await new Promise((resolve) => setTimeout(resolve, 250));
  child.stdin.write(`${JSON.stringify({ id: "ignored-status", type: "status", root })}\n`);
  const ignoredStatus = await waitFor(responses, (item) => item.id === "ignored-status" && item.type === "result");
  assert.equal(ignoredStatus.payload.automaticIndexRuns, 0);

  child.stdin.write(`${JSON.stringify({
    id: "retrieve",
    type: "retrieve",
    root,
    payload: { informationRequest: "Where is token validation implemented?", maxTokens: 1200 },
  })}\n`);
  const result = await waitFor(responses, (item) => item.id === "retrieve" && item.type === "result");
  assert.match(result.payload.packedText, /verifyToken/);
  assert.match(result.payload.packedText, /src\/auth\.ts/);

  await writeFile(
    path.join(root, "src", "payments.ts"),
    "export function captureSettlement(reference: string) { return `settled:${reference}`; }\n",
  );
  const afterAdd = await waitForAutomaticIndex(child, responses, root, 1);
  assert.equal(afterAdd.payload.lastAutomaticFilesIndexed, 1);

  child.stdin.write(`${JSON.stringify({
    id: "search-added",
    type: "search",
    root,
    payload: { query: "captureSettlement", topK: 5, mode: "bm25" },
  })}\n`);
  const added = await waitFor(responses, (item) => item.id === "search-added" && item.type === "result");
  assert.equal(added.payload[0].chunk.path, "src/payments.ts");

  await writeFile(
    path.join(root, "src", "payments.ts"),
    "export function captureSettlement(reference: string) { return `completed:${reference}`; }\n",
  );
  await waitForAutomaticIndex(child, responses, root, 2);
  child.stdin.write(`${JSON.stringify({
    id: "search-updated",
    type: "search",
    root,
    payload: { query: "completed captureSettlement", topK: 5, mode: "bm25" },
  })}\n`);
  const updated = await waitFor(responses, (item) => item.id === "search-updated" && item.type === "result");
  assert.match(updated.payload[0].chunk.content, /completed:/);
  assert.doesNotMatch(updated.payload[0].chunk.content, /settled:/);

  await unlink(path.join(root, "src", "payments.ts"));
  const afterDelete = await waitForAutomaticIndex(child, responses, root, 3);
  assert.equal(afterDelete.payload.lastAutomaticFilesRemoved, 1);

  child.stdin.write(`${JSON.stringify({ id: "shutdown", type: "shutdown" })}\n`);
  await once(child, "exit");
});

test("indexes and incrementally watches additional project roots", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-main-"));
  const sharedRoot = await mkdtemp(path.join(os.tmpdir(), "codeagent-shared-"));
  await mkdir(path.join(root, "src"));
  await mkdir(path.join(sharedRoot, "src"));
  await writeFile(path.join(root, "src", "main.ts"), "export const mainWorkspace = true;\n");
  await writeFile(path.join(sharedRoot, "src", "shared.ts"), "export const sharedContract = 'v1';\n");

  const child = spawn(process.execPath, ["dist/server.mjs"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(async () => {
    child.kill();
    await rm(root, { recursive: true, force: true });
    await rm(sharedRoot, { recursive: true, force: true });
  });

  const lines = createInterface({ input: child.stdout });
  const responses = [];
  lines.on("line", (line) => responses.push(JSON.parse(line)));
  const extraRoots = [{ name: "shared", path: sharedRoot, kind: "code" }];

  child.stdin.write(`${JSON.stringify({ id: "index", type: "index", root, extraRoots })}\n`);
  const indexed = await waitFor(responses, (item) => item.id === "index" && item.type === "result", 15_000);
  assert.deepEqual(indexed.payload.roots, [root, sharedRoot]);

  child.stdin.write(`${JSON.stringify({
    id: "watch",
    type: "watch",
    root,
    extraRoots,
    payload: { debounceMs: 100 },
  })}\n`);
  const watch = await waitFor(responses, (item) => item.id === "watch" && item.type === "result");
  assert.equal(watch.payload.watching, true);
  assert.equal(watch.payload.watchedRootCount, 2);

  await writeFile(
    path.join(sharedRoot, "src", "shared.ts"),
    "export const sharedContract = 'v2-automatic-sync';\n",
  );
  await waitForAutomaticIndex(child, responses, root, 1, 15_000, extraRoots);

  child.stdin.write(`${JSON.stringify({
    id: "search-shared",
    type: "search",
    root,
    extraRoots,
    payload: { query: "v2 automatic sync sharedContract", topK: 5, mode: "bm25" },
  })}\n`);
  const updated = await waitFor(responses, (item) => item.id === "search-shared" && item.type === "result");
  assert.equal(updated.payload[0].chunk.path, "shared/src/shared.ts");
  assert.match(updated.payload[0].chunk.content, /v2-automatic-sync/);

  child.stdin.write(`${JSON.stringify({ id: "shutdown", type: "shutdown" })}\n`);
  await once(child, "exit");
});

test("syncs and retrieves project context through a remote HTTP deployment", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "codeagent-http-context-"));
  await mkdir(path.join(root, "src"));
  await writeFile(path.join(root, "src", "auth.ts"), "export const remoteToken = 'v1';\n");

  const remote = createFakeContextServer();
  remote.server.listen(0, "127.0.0.1");
  await once(remote.server, "listening");
  const address = remote.server.address();
  assert.ok(address && typeof address === "object");

  const child = spawn(process.execPath, ["dist/server.mjs"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    env: {
      ...process.env,
      CONTEXTENGINE_HTTP_URL: `http://127.0.0.1:${address.port}`,
      CONTEXTENGINE_HTTP_API_KEY: "test-context-secret",
    },
    stdio: ["pipe", "pipe", "inherit"],
  });
  t.after(async () => {
    child.kill();
    remote.server.close();
    await rm(root, { recursive: true, force: true });
  });

  const lines = createInterface({ input: child.stdout });
  const responses = [];
  lines.on("line", (line) => responses.push(JSON.parse(line)));

  child.stdin.write(`${JSON.stringify({ id: "remote-index", type: "index", root })}\n`);
  const indexed = await waitFor(
    responses,
    (item) => item.id === "remote-index" && item.type === "result",
    15_000,
  );
  assert.equal(indexed.payload.filesIndexed, 1);
  assert.equal(remote.state.files.get("src/auth.ts")?.content, "export const remoteToken = 'v1';\n");
  assert.equal(remote.state.authorizationFailures, 0);
  assert.ok(remote.state.mtimeValues.length > 0);
  assert.ok(remote.state.mtimeValues.every((value) => Number.isInteger(value) && value >= 0));

  child.stdin.write(`${JSON.stringify({
    id: "remote-retrieve",
    type: "retrieve",
    root,
    payload: { informationRequest: "Where is remoteToken defined?", maxTokens: 800 },
  })}\n`);
  const retrieved = await waitFor(
    responses,
    (item) => item.id === "remote-retrieve" && item.type === "result",
  );
  assert.match(retrieved.payload.packedText, /remoteToken/);

  child.stdin.write(`${JSON.stringify({
    id: "remote-watch",
    type: "watch",
    root,
    payload: { debounceMs: 100 },
  })}\n`);
  await waitFor(responses, (item) => item.id === "remote-watch" && item.type === "result");
  await writeFile(path.join(root, "src", "auth.ts"), "export const remoteToken = 'v2-incremental';\n");
  await waitForAutomaticIndex(child, responses, root, 1, 15_000);

  child.stdin.write(`${JSON.stringify({
    id: "remote-search",
    type: "search",
    root,
    payload: { query: "v2 incremental remoteToken", topK: 5, mode: "bm25" },
  })}\n`);
  const searched = await waitFor(
    responses,
    (item) => item.id === "remote-search" && item.type === "result",
  );
  assert.match(searched.payload[0].chunk.id, /^[0-9a-f]{64}$/);
  assert.equal(searched.payload[0].chunk.path, "src/auth.ts");
  assert.match(searched.payload[0].chunk.content, /v2-incremental/);

  child.stdin.write(`${JSON.stringify({ id: "shutdown", type: "shutdown" })}\n`);
  await once(child, "exit");
});

async function waitForAutomaticIndex(child, responses, root, expectedRuns, timeoutMs = 15_000, extraRoots = undefined) {
  const started = Date.now();
  let sequence = 0;
  while (Date.now() - started < timeoutMs) {
    const id = `watch-status-${expectedRuns}-${sequence++}`;
    child.stdin.write(`${JSON.stringify({ id, type: "status", root, extraRoots })}\n`);
    const status = await waitFor(responses, (item) => item.id === id && item.type === "result", 2_000);
    if (status.payload.automaticIndexRuns >= expectedRuns) return status;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Timed out waiting for automatic index run ${expectedRuns}`);
}

async function waitFor(items, predicate, timeoutMs = 5_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const found = items.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`Timed out waiting for sidecar response: ${JSON.stringify(items)}`);
}

function createFakeContextServer() {
  const state = {
    revision: 0,
    indexed: false,
    files: new Map(),
    blobs: new Map(),
    plan: null,
    authorizationFailures: 0,
    mtimeValues: [],
  };
  const server = createServer(async (request, response) => {
    const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
    if (requestUrl.pathname.startsWith("/v1/") && request.headers.authorization !== "Bearer test-context-secret") {
      state.authorizationFailures += 1;
      sendJson(response, 401, { error: { message: "Unauthorized" } });
      return;
    }

    if (request.method === "POST" && requestUrl.pathname === "/v1/workspaces") {
      sendJson(response, 201, { workspace: workspacePayload(state) });
      return;
    }
    if (request.method === "GET" && requestUrl.pathname === "/v1/workspaces/workspace-1") {
      sendJson(response, 200, { workspace: workspacePayload(state) });
      return;
    }
    if (request.method === "GET" && requestUrl.pathname === "/v1/workspaces/workspace-1/status") {
      sendJson(response, 200, {
        workspace: workspacePayload(state),
        indexed: state.indexed,
        stats: state.indexed ? {
          fileCount: state.files.size,
          chunkCount: state.files.size,
          hasEmbeddings: true,
          lastIndexedAt: "2026-07-15T12:00:00.000Z",
        } : null,
      });
      return;
    }
    if (request.method === "POST" && requestUrl.pathname === "/v1/workspaces/workspace-1/sync/plan") {
      const body = await readJsonRequest(request);
      if (body.base_revision !== state.revision) {
        sendJson(response, 409, { error: { message: "Revision conflict" } });
        return;
      }
      const invalidMtime = body.changes.find(
        (change) => change.mtime_ms !== undefined
          && (!Number.isInteger(change.mtime_ms) || change.mtime_ms < 0),
      );
      if (invalidMtime) {
        sendJson(response, 400, {
          error: {
            code: "validation_error",
            message: "Request validation failed",
            details: [{ path: ["changes", "mtime_ms"], message: "Expected an integer" }],
          },
        });
        return;
      }
      state.mtimeValues.push(
        ...body.changes
          .filter((change) => change.mtime_ms !== undefined)
          .map((change) => change.mtime_ms),
      );
      state.plan = body.changes;
      const missing = body.changes
        .filter((change) => change.op === "upsert" && !state.blobs.has(change.blob_hash))
        .map((change) => change.blob_hash);
      sendJson(response, 201, {
        sync_id: "sync-1",
        workspace_id: "workspace-1",
        base_revision: state.revision,
        missing_blobs: missing,
        expires_at: "2026-07-15T12:15:00.000Z",
      });
      return;
    }
    const blobMatch = /^\/v1\/blobs\/([0-9a-f]{64})$/.exec(requestUrl.pathname);
    if (request.method === "PUT" && blobMatch) {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      state.blobs.set(blobMatch[1], Buffer.concat(chunks));
      sendJson(response, 201, { ok: true, sha256: blobMatch[1] });
      return;
    }
    if (request.method === "POST" && requestUrl.pathname === "/v1/workspaces/workspace-1/sync/commit") {
      for (const change of state.plan ?? []) {
        if (change.op === "delete") {
          state.files.delete(change.path);
        } else if (change.op === "upsert") {
          state.files.set(change.path, {
            content: state.blobs.get(change.blob_hash)?.toString("utf8") ?? "",
            language: change.language,
          });
        }
      }
      state.plan = null;
      state.revision += 1;
      state.indexed = true;
      sendJson(response, 200, {
        ok: true,
        revision: state.revision,
        changed_paths: [...state.files.keys()],
        deleted_paths: [],
        index_job: { id: "job-1", status: "queued" },
      });
      return;
    }
    if (request.method === "GET" && requestUrl.pathname === "/v1/index-jobs/job-1") {
      sendJson(response, 200, {
        job: {
          id: "job-1",
          status: "succeeded",
          progress: { phase: "done", filesTotal: state.files.size, filesDone: state.files.size, chunksTotal: state.files.size },
          result: { chunksWritten: state.files.size, embeddingsWritten: state.files.size },
        },
      });
      return;
    }
    if (request.method === "POST" && requestUrl.pathname === "/v1/workspaces/workspace-1/search") {
      const results = [...state.files.entries()].map(([filePath, file]) => ({
        path: filePath,
        start_line: 1,
        end_line: 1,
        language: file.language,
        content: file.content,
        preview: file.content.trim(),
        score: 1,
        source: "bm25",
      }));
      sendJson(response, 200, { count: results.length, results });
      return;
    }
    if (request.method === "POST" && requestUrl.pathname === "/v1/workspaces/workspace-1/context") {
      const body = await readJsonRequest(request);
      sendJson(response, 200, {
        task: body.information_request,
        packed_text: [...state.files.entries()].map(([filePath, file]) => `${filePath}\n${file.content}`).join("\n"),
        estimated_tokens: 24,
        truncated: false,
        hits: [],
      });
      return;
    }

    sendJson(response, 404, { error: { message: `No fake route for ${request.method} ${requestUrl.pathname}` } });
  });
  return { server, state };
}

function workspacePayload(state) {
  return {
    id: "workspace-1",
    name: "CodeAgent test",
    source_mode: "blob",
    local_root: null,
    revision: state.revision,
  };
}

async function readJsonRequest(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

function sendJson(response, status, body) {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { "content-type": "application/json" });
  response.end(payload);
}
