#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const semverPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

function parseArguments(argv) {
  let increment = "patch";
  let dryRun = false;
  let checkOnly = false;

  for (const argument of argv) {
    if (argument === "--dry-run") {
      dryRun = true;
    } else if (argument === "--check") {
      checkOnly = true;
    } else if (["--patch", "--minor", "--major"].includes(argument)) {
      increment = argument.slice(2);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }

  if (checkOnly && dryRun) {
    throw new Error("--check and --dry-run cannot be used together");
  }

  return { increment, dryRun, checkOnly };
}

function read(relativePath) {
  return readFileSync(path.join(repositoryRoot, relativePath), "utf8");
}

function parseJson(relativePath) {
  return JSON.parse(read(relativePath));
}

function currentVersion() {
  const properties = read("gradle.properties");
  const matches = [...properties.matchAll(/^version=(.+)$/gm)];
  if (matches.length !== 1 || !semverPattern.test(matches[0][1])) {
    throw new Error("gradle.properties must contain exactly one stable semantic version");
  }
  return matches[0][1];
}

function incrementVersion(version, increment) {
  const match = semverPattern.exec(version);
  if (!match) {
    throw new Error(`Cannot increment invalid version: ${version}`);
  }

  let major = Number(match[1]);
  let minor = Number(match[2]);
  let patch = Number(match[3]);

  if (increment === "major") {
    major += 1;
    minor = 0;
    patch = 0;
  } else if (increment === "minor") {
    minor += 1;
    patch = 0;
  } else {
    patch += 1;
  }

  return `${major}.${minor}.${patch}`;
}

function assertSynchronized(version) {
  const errors = [];
  const jsonSources = [
    ["frontend/package.json", (json) => json.version],
    ["frontend/package-lock.json", (json) => json.version],
    ["frontend/package-lock.json packages[\"\"]", () => parseJson("frontend/package-lock.json").packages?.[""]?.version],
    ["sidecar/package.json", (json) => json.version],
    ["sidecar/package-lock.json", (json) => json.version],
    ["sidecar/package-lock.json packages[\"\"]", () => parseJson("sidecar/package-lock.json").packages?.[""]?.version],
    ["backend/package.json", (json) => json.version],
    ["backend/package-lock.json", (json) => json.version],
    ["backend/package-lock.json packages[\"\"]", () => parseJson("backend/package-lock.json").packages?.[""]?.version],
    ["backend/openapi.json info", (json) => json.info?.version],
  ];

  for (const [label, selector] of jsonSources) {
    const relativePath = label.split(" ")[0];
    const actual = selector(parseJson(relativePath));
    if (actual !== version) {
      errors.push(`${label}: expected ${version}, found ${actual ?? "<missing>"}`);
    }
  }

  const composeImage = read("backend/compose.yaml").match(/^\s*image:\s*codeagent-backend:(\d+\.\d+\.\d+)\s*$/m)?.[1];
  if (composeImage !== version) {
    errors.push(`backend/compose.yaml image: expected ${version}, found ${composeImage ?? "<missing>"}`);
  }

  const runtimeVersion = read("sidecar/src/mcp-runtime.ts").match(
    /\{\s*name:\s*"CodeAgent",\s*version:\s*"(\d+\.\d+\.\d+)"\s*\}/,
  )?.[1];
  if (runtimeVersion !== version) {
    errors.push(`sidecar/src/mcp-runtime.ts client: expected ${version}, found ${runtimeVersion ?? "<missing>"}`);
  }

  const acpRuntimeVersion = read("sidecar/src/acp-runtime.ts").match(
    /clientInfo:\s*\{\s*name:\s*"CodeAgent",\s*title:\s*"CodeAgent for JetBrains",\s*version:\s*"(\d+\.\d+\.\d+)"\s*\}/,
  )?.[1];
  if (acpRuntimeVersion !== version) {
    errors.push(`sidecar/src/acp-runtime.ts client: expected ${version}, found ${acpRuntimeVersion ?? "<missing>"}`);
  }

  if (errors.length > 0) {
    throw new Error(`Version metadata is not synchronized:\n- ${errors.join("\n- ")}`);
  }
}

function updateJson(relativePath, update) {
  const json = parseJson(relativePath);
  update(json);
  return `${JSON.stringify(json, null, 2)}\n`;
}

function buildUpdates(fromVersion, toVersion) {
  const updates = new Map();
  updates.set(
    "gradle.properties",
    read("gradle.properties").replace(/^version=.+$/m, `version=${toVersion}`),
  );

  for (const relativePath of [
    "frontend/package.json",
    "sidecar/package.json",
    "backend/package.json",
  ]) {
    updates.set(relativePath, updateJson(relativePath, (json) => {
      json.version = toVersion;
    }));
  }

  for (const relativePath of [
    "frontend/package-lock.json",
    "sidecar/package-lock.json",
    "backend/package-lock.json",
  ]) {
    updates.set(relativePath, updateJson(relativePath, (json) => {
      json.version = toVersion;
      json.packages[""].version = toVersion;
    }));
  }

  updates.set(
    "backend/openapi.json",
    read("backend/openapi.json").replace(
      `    "version": "${fromVersion}",`,
      `    "version": "${toVersion}",`,
    ),
  );
  updates.set(
    "backend/compose.yaml",
    read("backend/compose.yaml").replace(
      `image: codeagent-backend:${fromVersion}`,
      `image: codeagent-backend:${toVersion}`,
    ),
  );
  updates.set(
    "sidecar/src/mcp-runtime.ts",
    read("sidecar/src/mcp-runtime.ts").replace(
      `{ name: "CodeAgent", version: "${fromVersion}" }`,
      `{ name: "CodeAgent", version: "${toVersion}" }`,
    ),
  );
  updates.set(
    "sidecar/src/acp-runtime.ts",
    read("sidecar/src/acp-runtime.ts").replace(
      `clientInfo: { name: "CodeAgent", title: "CodeAgent for JetBrains", version: "${fromVersion}" }`,
      `clientInfo: { name: "CodeAgent", title: "CodeAgent for JetBrains", version: "${toVersion}" }`,
    ),
  );

  return updates;
}

function main() {
  const { increment, dryRun, checkOnly } = parseArguments(process.argv.slice(2));
  const fromVersion = currentVersion();
  assertSynchronized(fromVersion);

  if (checkOnly) {
    console.log(`Version metadata is synchronized at ${fromVersion}.`);
    return;
  }

  const toVersion = incrementVersion(fromVersion, increment);
  const updates = buildUpdates(fromVersion, toVersion);

  if (!dryRun) {
    for (const [relativePath, contents] of updates) {
      writeFileSync(path.join(repositoryRoot, relativePath), contents);
    }
  }

  const action = dryRun ? "Would update" : "Updated";
  console.log(`${action} version ${fromVersion} -> ${toVersion}:`);
  for (const relativePath of updates.keys()) {
    console.log(`- ${relativePath}`);
  }
}

try {
  main();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}
