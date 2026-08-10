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
