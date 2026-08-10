import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./patch-webviews.mjs", import.meta.url));
const originalContextCategories =
  'const v=[{key:"systemPrompt",label:"System Prompt",color:"var(--ds-color-premium-9)",field:"systemPromptTokens"},{key:"chatHistory",label:"Chat History",color:"var(--ds-color-blue-9)",field:"chatHistoryTokens"},{key:"currentMessage",label:"Current Message",color:"var(--ds-color-success-9)",field:"currentMessageTokens"},{key:"toolResults",label:"Tool Results",color:"var(--ds-color-warning-9)",field:"toolResultTokens"},{key:"assistantResponse",label:"Assistant Response",color:"var(--ds-color-error-9)",field:"assistantResponseTokens"},{key:"toolDefinitions",label:"Built-in Tools",color:"var(--ds-color-plum-9)",field:"toolDefinitionsTokens"},{key:"mcpTools",label:"MCP Tools",color:"var(--ds-color-blue-11)",field:"mcpToolTokens"}]';
const originalContextUsage =
  'const Oq=fn;return{modelName:o,maxContextTokens:u,totalUsedTokens:V,systemPromptTokens:p,chatHistoryTokens:m,currentMessageTokens:h,toolResultTokens:b,assistantResponseTokens:f,systemToolTokens:l.systemToolTokens,mcpToolTokens:l.mcpToolTokens,systemToolBreakdown:l.systemToolBreakdown,mcpToolBreakdown:l.mcpToolBreakdown,toolDefinitionsTokens:g,freeSpaceTokens:E,usagePercentage:S,hasBackendData:c,hasDetailedBreakdown:d,isStreaming:r,exchangeCount:s}}';
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
  originalQuickAskPrompt +
  originalContextUsage;

const originalMainPanel =
  'He(()=>{const d=J(l,"$conversationCount",s);a.update(i=>i?.threadCount===d?i:{...i,threadCount:d})})' +
  originalContextCategories;
const previousPatchedMainPanel =
  'He(()=>{const d=J(l,"$conversationCount",s);fetch("http://127.0.0.1:8787/contextengine/thread-count",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({totalThreads:d})}).catch(()=>{})})' +
  originalContextCategories;

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

test("patches the supported IntelliJ webview bundle idempotently", () => {
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
    assert.match(
      patchedMainPanel,
      /key:"toolDefinitions",label:"Built-in Tools"[^}]+field:"systemToolTokens"/,
    );
    assert.doesNotMatch(
      patchedMainPanel,
      /key:"toolDefinitions",label:"Built-in Tools"[^}]+field:"toolDefinitionsTokens"/,
    );
    assert.match(
      patchedMainPanel,
      /key:"aggregateContext",label:"Input Context"[^}]+field:"aggregateContextTokens"/,
    );
    assert.match(
      patchedStore,
      /totalUsedTokens:V,aggregateContextTokens:d\?0:Math\.max\(0,V-l\.systemToolTokens-l\.mcpToolTokens\),/,
    );
    const aggregateExpression = patchedStore.match(
      /aggregateContextTokens:(d\?0:Math\.max\(0,V-l\.systemToolTokens-l\.mcpToolTokens\))/,
    )?.[1];
    assert.ok(aggregateExpression, "aggregate fallback expression should be extractable");
    const aggregateTokens = new Function("d", "V", "l", `return ${aggregateExpression};`);
    assert.equal(aggregateTokens(false, 81100, { systemToolTokens: 12134, mcpToolTokens: 0 }), 68966);
    assert.equal(aggregateTokens(true, 81100, { systemToolTokens: 12134, mcpToolTokens: 0 }), 0);
    assert.equal(aggregateTokens(false, 100, { systemToolTokens: 200, mcpToolTokens: 5 }), 0);
    assert.equal(
      patchedMainPanel.split('field:"aggregateContextTokens"').length - 1,
      1,
      "aggregate context category should be added exactly once",
    );
    assert.equal(
      patchedStore.split("aggregateContextTokens:").length - 1,
      1,
      "aggregate context value should be added exactly once",
    );

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
