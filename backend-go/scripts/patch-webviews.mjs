import fs from "node:fs";

const [mainPanelFile, storeFile] = process.argv.slice(2);
if (!mainPanelFile || !storeFile) {
  throw new Error("usage: node patch-webviews.mjs <MainPanel.js> <Store.js>");
}

function replaceOnce(file, name, before, after) {
  const source = fs.readFileSync(file, "utf8");
  const beforeCount = source.split(before).length - 1;
  const afterCount = source.split(after).length - 1;
  if (beforeCount === 0 && afterCount === 1) {
    return;
  }
  if (beforeCount !== 1 || afterCount !== 0) {
    throw new Error(`${name}: expected one original or patched anchor, found ${beforeCount}/${afterCount}`);
  }
  fs.writeFileSync(file, source.replace(before, after));
}

function replaceOnceFromAny(file, name, beforeOptions, after) {
  const source = fs.readFileSync(file, "utf8");
  const afterCount = source.split(after).length - 1;
  if (afterCount === 1 && beforeOptions.every((before) => !source.includes(before))) {
    return;
  }
  const matches = beforeOptions.map((before) => ({
    before,
    count: source.split(before).length - 1,
  }));
  const beforeCount = matches.reduce((total, match) => total + match.count, 0);
  if (beforeCount !== 1 || afterCount !== 0) {
    throw new Error(`${name}: expected one historical or patched anchor, found ${beforeCount}/${afterCount}`);
  }
  const match = matches.find((candidate) => candidate.count === 1);
  fs.writeFileSync(file, source.replace(match.before, after));
}

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
const boundedQuickAskPrompt = `const WN=\`# For this specific question, follow these ask mode guidelines:
- Focus on providing clear, accurate information
- Use code examples when helpful
- Use available read-only tools such as web-fetch, codebase-retrieval, view, view-range-untruncated, search-untruncated, grep-search, and git-commit-retrieval
- Never use tools that modify files or have side effects, including str-replace-editor, save-file, remove-files, launch-process, and task-management tools
- When the relevant location is unknown, start with codebase-retrieval and treat its results as a map to relevant code, not a complete file dump
- Once a concrete file is known, use view or view-range-untruncated to read it
- Use grep-search only for exact symbols, literals, errors, patterns, counts, or repository-wide references
- Never reconstruct a file through repeated grep-search calls
- Reformulate semantic retrieval only when required evidence is missing; do not repeat the same query
- Stop using tools as soon as you have enough evidence to answer
- If the question is unclear, ask for clarification

User message:
\``;

replaceOnceFromAny(
  mainPanelFile,
  "conversation count",
  [
    'He(()=>{const d=J(l,"$conversationCount",s);a.update(i=>i?.threadCount===d?i:{...i,threadCount:d})})',
    'He(()=>{const d=J(l,"$conversationCount",s);fetch("http://127.0.0.1:8787/contextengine/thread-count",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({totalThreads:d})}).catch(()=>{})})',
  ],
  'He(()=>{const d=J(l,"$conversationCount",s);let stopped=!1,delay=1e3;const sync=()=>{if(stopped)return;const base=(globalThis.__AUGMENT_TENANT_URL__||"http://127.0.0.1:8787").replace(/\\/+$/g,""),workspaceRoot=globalThis.__AUGMENT_WORKSPACE_ROOT__||"";fetch(base+"/contextengine/thread-count",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({totalThreads:d,workspaceRoot})}).then(r=>{if(!r.ok)throw new Error("thread count sync failed");delay=3e4}).catch(()=>{delay=Math.min(2*delay,3e4)}).finally(()=>{stopped||setTimeout(sync,delay)})};sync();return()=>{stopped=!0}})',
);

replaceOnce(
  storeFile,
  "shared webview state request",
  'settingsSaga:function*(){yield*F(Mz,function*(){yield*A(At({type:x.getSharedWebviewState,id:P0,data:{}}))})}',
  'settingsSaga:function*(){yield*F(Mz,function*(){})}',
);

replaceOnce(
  storeFile,
  "bounded Quick Ask retrieval",
  originalQuickAskPrompt,
  boundedQuickAskPrompt,
);
