#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));

function parseArguments(argv) {
  let increment = "--patch";
  let incrementSpecified = false;
  let current = false;
  let allowDirty = false;

  for (const argument of argv) {
    if (argument === "--allow-dirty") {
      allowDirty = true;
    } else if (argument === "--current") {
      current = true;
    } else if (["--patch", "--minor", "--major"].includes(argument)) {
      increment = argument;
      incrementSpecified = true;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }

  if (current && incrementSpecified) {
    throw new Error("--current cannot be combined with --patch, --minor, or --major");
  }

  return { increment, current, allowDirty };
}

function run(command, arguments_, options = {}) {
  console.log(`\n> ${command} ${arguments_.join(" ")}`);
  const result = spawnSync(command, arguments_, {
    cwd: repositoryRoot,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
  });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    if (options.capture && result.stderr) {
      process.stderr.write(result.stderr);
    }
    throw new Error(`${command} exited with status ${result.status}`);
  }

  return options.capture ? result.stdout : "";
}

function requireCleanWorktree(allowDirty) {
  const status = run("git", ["status", "--porcelain", "--untracked-files=all"], { capture: true }).trim();
  if (!status) {
    return;
  }
  if (!allowDirty) {
    throw new Error(
      "Release builds require a clean worktree. Commit or stash current changes, "
      + "or use --allow-dirty for a local verification build.",
    );
  }
  console.warn("\nWarning: building a release from a dirty worktree because --allow-dirty was supplied.");
}

function readVersion() {
  const properties = readFileSync(path.join(repositoryRoot, "gradle.properties"), "utf8");
  const version = properties.match(/^version=(\d+\.\d+\.\d+)$/m)?.[1];
  if (!version) {
    throw new Error("Could not read a stable semantic version from gradle.properties");
  }
  return version;
}

function findArtifact(version) {
  const distributionDirectory = path.join(repositoryRoot, "build", "distributions");
  const exactPath = path.join(distributionDirectory, `CodeAgent-${version}.zip`);
  if (existsSync(exactPath)) {
    return exactPath;
  }

  const matches = readdirSync(distributionDirectory)
    .filter((fileName) => fileName.endsWith(`-${version}.zip`))
    .map((fileName) => path.join(distributionDirectory, fileName));
  if (matches.length !== 1) {
    throw new Error(`Expected one plugin ZIP for version ${version}, found ${matches.length}`);
  }
  return matches[0];
}

function sha256(filePath) {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function main() {
  const { increment, current, allowDirty } = parseArguments(process.argv.slice(2));
  requireCleanWorktree(allowDirty);

  const sourceRevision = run("git", ["rev-parse", "HEAD"], { capture: true }).trim();
  const contextEngineRevision = run(
    "git",
    ["-C", "vendor/context-engine", "rev-parse", "HEAD"],
    { capture: true },
  ).trim();
  if (!current) {
    run(process.execPath, ["scripts/bump-version.mjs", increment]);
  }
  const version = readVersion();
  run(process.execPath, ["scripts/bump-version.mjs", "--check"]);

  run("npm", ["run", "check", "--prefix", "frontend"]);
  run("npm", ["run", "test:e2e", "--prefix", "frontend"]);
  run("npm", ["test", "--prefix", "sidecar"]);
  run("npm", ["test", "--prefix", "backend"]);
  run("npm", ["test", "--prefix", "vendor/context-engine"]);
  run("npm", ["run", "build", "--prefix", "vendor/context-engine"]);
  run(process.execPath, ["scripts/evaluate-parity.mjs"]);
  run(process.execPath, ["scripts/validate-github-live-evidence.mjs"]);
  run(process.execPath, ["scripts/evaluate-integration-readiness.mjs"]);
  run(process.execPath, ["scripts/evaluate-retrieval.mjs"]);
  run("./gradlew", [
    "clean",
    "test",
    "buildPlugin",
    "verifyPluginStructure",
    "--stacktrace",
  ]);
  run(process.execPath, ["scripts/verify-ides.mjs"]);

  const artifactPath = findArtifact(version);
  const artifactDigest = sha256(artifactPath);
  const reportPath = path.join(repositoryRoot, "build", "reports", "release-candidate.json");
  const report = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    version,
    mode: current ? "current" : increment.slice(2),
    sourceRevision,
    contextEngineRevision,
    artifact: {
      path: path.relative(repositoryRoot, artifactPath),
      sha256: artifactDigest,
    },
    verification: {
      versionMetadata: "passed",
      frontend: "passed",
      sidecar: "passed",
      backend: "passed",
      contextEngine: "passed",
      prototypeParity: "passed",
      githubLiveEvidence: "passed",
      integrationReadiness: "passed",
      repositoryRetrieval: "passed",
      pluginTests: "passed",
      pluginStructure: "passed",
      installedIdeVerifier: "passed",
    },
  };
  mkdirSync(path.dirname(reportPath), { recursive: true });
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  console.log("\nRelease build completed.");
  console.log(`Version: ${version}`);
  console.log(`Artifact: ${artifactPath}`);
  console.log(`SHA-256: ${artifactDigest}`);
  console.log(`Report: ${reportPath}`);
  if (current) {
    console.log("Current version metadata was verified without modification.");
  } else {
    console.log("Commit the synchronized version changes before creating the release tag.");
  }
}

try {
  main();
} catch (error) {
  console.error(`\nRelease build failed: ${error instanceof Error ? error.message : error}`);
  process.exitCode = 1;
}
