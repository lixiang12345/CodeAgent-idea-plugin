import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
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
    id: "retrieve",
    type: "retrieve",
    root,
    payload: { informationRequest: "Where is token validation implemented?", maxTokens: 1200 },
  })}\n`);
  const result = await waitFor(responses, (item) => item.id === "retrieve" && item.type === "result");
  assert.match(result.payload.packedText, /verifyToken/);
  assert.match(result.payload.packedText, /src\/auth\.ts/);

  child.stdin.write(`${JSON.stringify({ id: "shutdown", type: "shutdown" })}\n`);
  await once(child, "exit");
});

async function waitFor(items, predicate, timeoutMs = 5_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const found = items.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`Timed out waiting for sidecar response: ${JSON.stringify(items)}`);
}
