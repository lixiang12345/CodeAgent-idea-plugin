#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createServer } from "node:net";
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

  for (const argument of argv) {
    if (argument === "--current") {
      current = true;
    } else if (argument === "--allow-dirty") {
      throw new Error("--allow-dirty is not supported; release candidates require a clean worktree");
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

  return { increment, current };
}

function run(command, arguments_, options = {}) {
  console.log(`\n> ${command} ${arguments_.join(" ")}`);
  const result = spawnSync(command, arguments_, {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: { ...process.env, ...options.env },
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

function requireCleanWorktree() {
  const status = run("git", ["status", "--porcelain", "--untracked-files=all"], { capture: true }).trim();
  if (!status) {
    return;
  }
  throw new Error("Release candidates require a clean worktree. Commit or stash current changes.");
}

function requireJava21() {
  const gradleVersion = run("./gradlew", ["--version"], { capture: true });
  const javaRuntime = gradleVersion.match(/^Launcher JVM:\s+(.+)$/m)?.[1].trim();
  const javaMajor = Number(javaRuntime?.match(/^(\d+)/)?.[1]);
  if (javaMajor !== 21) {
    throw new Error(
      `Release builds require JDK 21; Gradle is using ${javaRuntime ?? "an unknown JVM"}`,
    );
  }
  return javaRuntime;
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

function findAvailablePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("Could not allocate a local Playwright port"));
        return;
      }
      server.close((error) => {
        if (error) reject(error);
        else resolve(address.port);
      });
    });
  });
}

async function main() {
  const { increment, current } = parseArguments(process.argv.slice(2));
  requireCleanWorktree();

  if (!current) {
    run(process.execPath, ["scripts/bump-version.mjs", increment]);
    run(process.execPath, ["scripts/bump-version.mjs", "--check"]);
    console.log("\nRelease metadata preparation completed.");
    console.log("Review and commit the synchronized version changes, then rerun with --current.");
    return;
  }

  const javaRuntime = requireJava21();
  const sourceRevision = run("git", ["rev-parse", "HEAD"], { capture: true }).trim();
  const contextEngineRevision = run(
    "git",
    ["-C", "vendor/context-engine", "rev-parse", "HEAD"],
    { capture: true },
  ).trim();
  const version = readVersion();
  run(process.execPath, ["scripts/bump-version.mjs", "--check"]);

  run("npm", ["run", "check", "--prefix", "frontend"]);
  const playwrightPort = await findAvailablePort();
  run("npm", ["run", "test:e2e", "--prefix", "frontend"], {
    env: {
      CI: "1",
      CODEAGENT_PLAYWRIGHT_PORT: String(playwrightPort),
    },
  });
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
    "--no-build-cache",
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
    mode: "current",
    sourceRevision,
    contextEngineRevision,
    buildEnvironment: {
      java: javaRuntime,
      os: `${process.platform}-${process.arch}`,
    },
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
  console.log("Current version metadata was verified without modification.");
}

try {
  await main();
} catch (error) {
  console.error(`\nRelease build failed: ${error instanceof Error ? error.message : error}`);
  process.exitCode = 1;
}
