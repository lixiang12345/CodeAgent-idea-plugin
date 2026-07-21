#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const manifestPath = path.join(repositoryRoot, "evaluation/parity-codeagent.json");
const reportPath = path.join(repositoryRoot, "build/reports/prototype-parity.json");
const checks = [];

function read(relativePath) {
  return readFileSync(path.join(repositoryRoot, relativePath), "utf8");
}

function readJson(relativePath) {
  return JSON.parse(read(relativePath));
}

function sameOrdered(actual, expected) {
  return actual.length === expected.length && actual.every((value, index) => value === expected[index]);
}

function sameSet(actual, expected) {
  return sameOrdered([...actual].sort(), [...expected].sort());
}

function record(name, passed, expected, actual, details) {
  checks.push({ name, passed, expected, actual, ...(details ? { details } : {}) });
}

function validateManifest(manifest) {
  if (manifest.schemaVersion !== 1) throw new Error(`Unsupported schemaVersion ${manifest.schemaVersion}`);
  if (manifest.suite !== "codeagent-prototype-parity") throw new Error(`Unexpected suite ${manifest.suite}`);
  if (manifest.canonicalDocument !== manifest.documents?.implementation) {
    throw new Error("canonicalDocument must equal documents.implementation");
  }
  if (!Array.isArray(manifest.surfaces) || manifest.surfaces.length === 0) {
    throw new Error("surfaces must be a non-empty array");
  }
  const ids = manifest.surfaces.map((surface) => surface.id);
  if (new Set(ids).size !== ids.length) throw new Error("surface ids must be unique");
  for (const surface of manifest.surfaces) {
    if (!surface.id || !["implemented", "conditional"].includes(surface.status)) {
      throw new Error(`Invalid surface entry ${surface.id ?? "<missing>"}`);
    }
    if (!Array.isArray(surface.evidence) || surface.evidence.length === 0) {
      throw new Error(`${surface.id} must name at least one evidence file`);
    }
  }
}

function inspectPluginXml() {
  const output = execFileSync(
    "java",
    [
      path.join(repositoryRoot, "scripts/InspectPluginXml.java"),
      path.join(repositoryRoot, "src/main/resources/META-INF/plugin.xml"),
    ],
    { encoding: "utf8" },
  );
  const result = { actionIds: [], extensionCount: null, listenerCount: null };
  for (const line of output.trim().split("\n")) {
    const [kind, name, value] = line.split("\t");
    if (kind === "action") result.actionIds.push(name);
    if (kind === "metric" && name === "extensions") result.extensionCount = Number(value);
    if (kind === "metric" && name === "listeners") result.listenerCount = Number(value);
  }
  return result;
}

async function loadTypeScript() {
  const modulePath = path.join(repositoryRoot, "frontend/node_modules/typescript/lib/typescript.js");
  if (!existsSync(modulePath)) {
    throw new Error("TypeScript is not installed; run npm ci --prefix frontend first");
  }
  const imported = await import(pathToFileURL(modulePath).href);
  return imported.default ?? imported;
}

async function loadSvelteCompiler() {
  const modulePath = path.join(repositoryRoot, "frontend/node_modules/svelte/compiler/index.js");
  if (!existsSync(modulePath)) {
    throw new Error("Svelte is not installed; run npm ci --prefix frontend first");
  }
  const imported = await import(pathToFileURL(modulePath).href);
  return imported.default ?? imported;
}

function sourceFile(ts, relativePath) {
  return ts.createSourceFile(
    relativePath,
    read(relativePath),
    ts.ScriptTarget.Latest,
    true,
    relativePath.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
}

function toolCatalogIds(ts) {
  const source = sourceFile(ts, "frontend/src/lib/tools-catalog.ts");
  let ids = null;
  function visit(node) {
    if (ts.isVariableDeclaration(node)
      && ts.isIdentifier(node.name)
      && node.name.text === "TOOL_CATALOG"
      && node.initializer
      && ts.isArrayLiteralExpression(node.initializer)) {
      ids = node.initializer.elements.map((element) => {
        if (!ts.isObjectLiteralExpression(element)) throw new Error("TOOL_CATALOG entries must be object literals");
        const property = element.properties.find((candidate) =>
          ts.isPropertyAssignment(candidate)
          && ts.isIdentifier(candidate.name)
          && candidate.name.text === "id");
        if (!property || !ts.isPropertyAssignment(property) || !ts.isStringLiteral(property.initializer)) {
          throw new Error("Every TOOL_CATALOG entry must have a literal id");
        }
        return property.initializer.text;
      });
      return;
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  if (!ids) throw new Error("Could not find the TOOL_CATALOG array");
  return ids;
}

function bridgeCommands(ts, svelte) {
  const app = svelte.parse(read("frontend/src/App.svelte"), { modern: true });
  const protocol = sourceFile(ts, "frontend/src/lib/protocol.ts");
  const commands = new Set();
  const handlers = new Set(["bootstrap"]);

  const seen = new WeakSet();
  function visitCommands(node) {
    if (!node || typeof node !== "object" || seen.has(node)) return;
    seen.add(node);
    if (node.type === "CallExpression"
      && node.callee?.type === "Identifier"
      && node.callee.name === "sendCommand"
      && node.arguments?.[0]?.type === "Literal"
      && typeof node.arguments[0].value === "string") {
      commands.add(node.arguments[0].value);
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(visitCommands);
      else visitCommands(value);
    }
  }
  function visitHandlers(node) {
    if (ts.isBinaryExpression(node)
      && node.operatorToken.kind === ts.SyntaxKind.EqualsEqualsEqualsToken
      && ts.isPropertyAccessExpression(node.left)
      && ts.isIdentifier(node.left.expression)
      && node.left.expression.text === "command"
      && node.left.name.text === "type"
      && ts.isStringLiteral(node.right)) {
      handlers.add(node.right.text);
    }
    ts.forEachChild(node, visitHandlers);
  }
  visitCommands(app);
  visitHandlers(protocol);
  return {
    commands: [...commands].sort(),
    handlers: [...handlers].sort(),
    missingHandlers: [...commands].filter((command) => !handlers.has(command)).sort(),
  };
}

async function evaluate() {
  const manifest = readJson("evaluation/parity-codeagent.json");
  validateManifest(manifest);
  record("manifest schema", true, "valid schemaVersion 1", "valid schemaVersion 1");

  for (const documentPath of Object.values(manifest.documents)) {
    record(`document exists: ${documentPath}`, existsSync(path.join(repositoryRoot, documentPath)), true,
      existsSync(path.join(repositoryRoot, documentPath)));
  }
  for (const surface of manifest.surfaces) {
    const missing = surface.evidence.filter((relativePath) => !existsSync(path.join(repositoryRoot, relativePath)));
    record(`surface evidence: ${surface.id}`, missing.length === 0, "all evidence files exist", missing);
  }

  const plugin = inspectPluginXml();
  record("IDE action ids", sameSet(plugin.actionIds, manifest.contracts.ideaPlugin.actionIds),
    manifest.contracts.ideaPlugin.actionIds, plugin.actionIds);
  record("IDE extension count", plugin.extensionCount === manifest.contracts.ideaPlugin.extensionCount,
    manifest.contracts.ideaPlugin.extensionCount, plugin.extensionCount);
  record("IDE listener count", plugin.listenerCount === manifest.contracts.ideaPlugin.listenerCount,
    manifest.contracts.ideaPlugin.listenerCount, plugin.listenerCount);

  const settings = readJson("frontend/src/lib/settings-sections.json").flatMap((group) => group.items.map((item) => item.id));
  record("settings section ids", sameOrdered(settings, manifest.contracts.settingsSections),
    manifest.contracts.settingsSections, settings);

  const [ts, svelte] = await Promise.all([loadTypeScript(), loadSvelteCompiler()]);
  const frontendTools = toolCatalogIds(ts);
  record("frontend tool catalog ids", sameOrdered(frontendTools, manifest.contracts.toolCatalogIds),
    manifest.contracts.toolCatalogIds, frontendTools);

  const actualPaths = Object.keys(readJson("backend/openapi.json").paths);
  record("backend OpenAPI paths", sameSet(actualPaths, manifest.contracts.backendOpenApiPaths),
    manifest.contracts.backendOpenApiPaths, actualPaths);

  const bridge = bridgeCommands(ts, svelte);
  record("literal bridge command coverage", bridge.missingHandlers.length === 0,
    "every literal sendCommand has a development host handler", bridge.missingHandlers,
    `${bridge.commands.length} commands; ${bridge.handlers.length} handlers`);

  for (const marker of manifest.forbiddenCurrentStateMarkers) {
    const found = read(marker.path).includes(marker.text);
    record(`stale current-state marker: ${marker.path}`, !found, "absent", found ? marker.text : "absent");
  }
}

let fatalError = null;
try {
  await evaluate();
} catch (error) {
  fatalError = error instanceof Error ? error.message : String(error);
  record("evaluator completed", false, "no fatal error", fatalError);
}

const failedChecks = checks.filter((check) => !check.passed);
let sourceRevision = null;
try {
  sourceRevision = execFileSync("git", ["rev-parse", "HEAD"], { cwd: repositoryRoot, encoding: "utf8" }).trim();
} catch {
  sourceRevision = null;
}
const report = {
  schemaVersion: 1,
  suite: "codeagent-prototype-parity",
  generatedAt: new Date().toISOString(),
  sourceRevision,
  manifest: path.relative(repositoryRoot, manifestPath),
  passed: failedChecks.length === 0,
  summary: { checkCount: checks.length, passedCount: checks.length - failedChecks.length, failedCount: failedChecks.length },
  checks,
  ...(fatalError ? { fatalError } : {}),
};
mkdirSync(path.dirname(reportPath), { recursive: true });
writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

console.log(`Prototype parity: ${report.passed ? "PASS" : "FAIL"} (${report.summary.passedCount}/${report.summary.checkCount})`);
console.log(`Report: ${reportPath}`);
for (const check of failedChecks) console.error(`- ${check.name}: ${check.details ?? JSON.stringify(check.actual)}`);
if (!report.passed) process.exitCode = 1;
