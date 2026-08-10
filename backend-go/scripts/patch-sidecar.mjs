import fs from "node:fs";

const file = process.argv[2];
if (!file) {
  throw new Error("usage: node patch-sidecar.mjs <sidecar/index.cjs>");
}

let source = fs.readFileSync(file, "utf8");

function replaceOnce(name, before, after) {
  const beforeCount = source.split(before).length - 1;
  const afterCount = source.split(after).length - 1;
  if (beforeCount === 0 && afterCount === 1) {
    return;
  }
  if (beforeCount !== 1 || afterCount !== 0) {
    throw new Error(`${name}: expected one original or patched anchor, found ${beforeCount}/${afterCount}`);
  }
  source = source.replace(before, after);
}

replaceOnce(
  "initialization payload log",
  'Sn.info(`Initializing Language Server: ${JSON.stringify(e)}`);',
  'Sn.info("Initializing Language Server");',
);
replaceOnce(
  "parsed initialization payload log",
  'Sn.info(`Initializing Language Server....parsed proto: ${W0(GX,N)}`),',
  'Sn.info("Parsed language server initialization parameters"),',
);
replaceOnce(
  "MCP configuration log",
  'this._logger.info(`Received ${t.length} MCP servers from IntelliJ: ${JSON.stringify(t,null,2)}`),t',
  'this._logger.info(`Received ${t.length} MCP servers from IntelliJ`),t',
);
replaceOnce(
  "Redux payload log",
  'function KGt(e){const t=e.payload===void 0?"":` ${JSON.stringify(e.payload)}`;return`[${m2e(e.timestamp)}] [INFO] [REDUX] ${g2e(e.type)}${t}`}',
  'function KGt(e){const t=e.payload===void 0?0:JSON.stringify(e.payload).length;return`[${m2e(e.timestamp)}] [INFO] [REDUX] ${g2e(e.type)} payload_bytes=${t}`}',
);
replaceOnce(
  "webview message log",
  'function jGt(e){return`[${m2e(e.timestamp)}] [${e.level.toUpperCase()}] [WEBVIEW] ${g2e(e.message)}`}',
  'function jGt(e){return`[${m2e(e.timestamp)}] [${e.level.toUpperCase()}] [WEBVIEW] message_bytes=${String(e.message||"").length}`}',
);
replaceOnce(
  "project overview endpoint",
  'fetch("http://127.0.0.1:8787/generate-project-overview",',
  'fetch((process.env.AUGMENT_TENANT_URL||"http://127.0.0.1:8787").replace(/\\/+$/g,"")+"/generate-project-overview",',
);

const oldPoller = ';var prevDone=false;setTimeout((function p(){try{var b=bu();if(!b||!b.broadcastMessageToWebviews)return void setTimeout(p,3000);fetch("http://127.0.0.1:8787/contextengine/index-status").then(function(r){if(!r.ok)throw 0;return r.json()}).then(function(j){var st=j.stats||{},pr=j.progress||{},done=!!j.indexed,fd=done?(st.fileCount||0):(pr.filesDone||0),ft=done?(st.fileCount||0):(pr.filesTotal||0);b.broadcastMessageToWebviews({type:"source-folders-sync-status",data:{status:done?"done":"running",foldersProgress:[{folder:j.root||"",progress:{trackedFiles:ft,backlogSize:Math.max(0,ft-fd)},isInitialIndexing:!done}],indeterminate:!done&&ft<=0,isInitialIndexing:!done}}),done&&!prevDone&&b.broadcastMessageToWebviews({type:"ws-context-source-folders-changed",data:{}}),prevDone=done}).catch(function(){})}finally{setTimeout(p,3000)}})(),3000);';
const newPoller = ';var prevDone=false;setTimeout(function p(){try{var b=bu();if(!b||!b.broadcastMessageToWebviews){setTimeout(p,3000);return}fetch((process.env.AUGMENT_TENANT_URL||"http://127.0.0.1:8787").replace(/\\/+$/g,"")+"/contextengine/index-status").then(function(r){if(!r.ok)throw 0;return r.json()}).then(function(j){var st=j.stats||{},pr=j.progress||{},done=!!j.indexed,fd=done?(st.fileCount||0):(pr.filesDone||0),ft=done?(st.fileCount||0):(pr.filesTotal||0);b.broadcastMessageToWebviews({type:"source-folders-sync-status",data:{status:done?"done":"running",foldersProgress:[{folder:j.root||"",progress:{trackedFiles:ft,backlogSize:Math.max(0,ft-fd)},isInitialIndexing:!done}],indeterminate:!done&&ft<=0,isInitialIndexing:!done}});if(done&&!prevDone)b.broadcastMessageToWebviews({type:"ws-context-source-folders-changed",data:{}});prevDone=done;return done}).then(function(done){setTimeout(p,done?30000:3000)}).catch(function(){setTimeout(p,10000)})}catch(e){setTimeout(p,10000)}},3000);';
replaceOnce("ContextEngine status poller", oldPoller, newPoller);

fs.writeFileSync(file, source);
