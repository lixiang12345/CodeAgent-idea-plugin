import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./patch-webviews.mjs", import.meta.url));
const originalMainPanel =
  'He(()=>{const d=J(l,"$conversationCount",s);a.update(i=>i?.threadCount===d?i:{...i,threadCount:d})})';
const previousPatchedMainPanel =
  'He(()=>{const d=J(l,"$conversationCount",s);fetch("http://127.0.0.1:8787/contextengine/thread-count",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({totalThreads:d})}).catch(()=>{})})';
const originalQuickAskPrompt = `const WN=\`# For this specific question, follow these ask mode guidelines:
- Focus on providing clear, accurate information
- Use code examples when helpful
- ONLY use retrieval tools (web-fetch, codebase-retrieval, grep-search) to gather information
- Do NOT use any tools that modify files (str-replace-editor, save-file, remove-files, etc.)
- Do NOT make any changes to the codebase - this is for information gathering only
- If the question is unclear, ask for clarification
- If you need to search for information, use the available retrieval tools extensively

User message:
\``;
const originalStore =
  'settingsSaga:function*(){yield*F(Mz,function*(){yield*A(At({type:x.getSharedWebviewState,id:P0,data:{}}))})}' +
  originalQuickAskPrompt;

function withFixture(mainPanelSource, storeSource, run) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "augment-webview-patch-"));
  const mainPanel = path.join(directory, "MainPanel.js");
  const store = path.join(directory, "Store.js");
  fs.writeFileSync(mainPanel, mainPanelSource);
  fs.writeFileSync(store, storeSource);
  try {
    run(mainPanel, store);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

test("patches IntelliJ conversation count synchronization idempotently", () => {
  withFixture(originalMainPanel, originalStore, (mainPanel, store) => {
    execFileSync(process.execPath, [script, mainPanel, store]);
    const patchedMainPanel = fs.readFileSync(mainPanel, "utf8");
    const patchedStore = fs.readFileSync(store, "utf8");

    assert.match(patchedMainPanel, /contextengine\/thread-count/);
    assert.match(patchedMainPanel, /JSON\.stringify\(\{totalThreads:d,workspaceRoot\}/);
    assert.match(patchedMainPanel, /setTimeout/);
    assert.match(patchedMainPanel, /globalThis\.__AUGMENT_TENANT_URL__/);
    assert.doesNotMatch(patchedMainPanel, /a\.update\(i=>i\?\.threadCount===d/);
    assert.doesNotMatch(patchedStore, /type:x\.getSharedWebviewState,id:P0/);
    assert.doesNotMatch(patchedStore, /ONLY use retrieval tools/);
    assert.doesNotMatch(patchedStore, /retrieval tools extensively/);
    assert.match(patchedStore, /available read-only tools/);
    assert.match(patchedStore, /view-range-untruncated/);
    assert.match(patchedStore, /Never reconstruct a file through repeated grep-search calls/);
    assert.match(patchedStore, /Stop using tools as soon as you have enough evidence/);

    execFileSync(process.execPath, [script, mainPanel, store]);
    assert.equal(fs.readFileSync(mainPanel, "utf8"), patchedMainPanel);
    assert.equal(fs.readFileSync(store, "utf8"), patchedStore);
  });
});

test("rejects a webview bundle without the expected anchor", () => {
  withFixture("const unrelated=true;", originalStore, (mainPanel, store) => {
    let error;
    try {
      execFileSync(process.execPath, [script, mainPanel, store], { stdio: "pipe" });
    } catch (caught) {
      error = caught;
    }
    assert.ok(error, "patcher should reject an unknown MainPanel bundle");
    assert.match(error.stderr.toString(), /conversation count: expected one historical or patched anchor/);
  });
});

test("upgrades the previous conversation patch without duplicating it", () => {
  withFixture(previousPatchedMainPanel, originalStore, (mainPanel, store) => {
    execFileSync(process.execPath, [script, mainPanel, store]);
    const patchedMainPanel = fs.readFileSync(mainPanel, "utf8");
    assert.match(patchedMainPanel, /globalThis\.__AUGMENT_TENANT_URL__/);
    assert.doesNotMatch(patchedMainPanel, /JSON\.stringify\(\{totalThreads:d\}\)/);
  });
});
