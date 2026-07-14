import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, mkdir, rm, unlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createInterface } from "node:readline";
import { spawn } from "node:child_process";
import test from "node:test";

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
