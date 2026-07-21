#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const supportedProducts = ["PyCharm", "WebStorm", "CLion", "GoLand", "PhpStorm", "Rider"];
const explicitPaths = process.env.CODEAGENT_VERIFIER_IDE_PATHS
  ?.split(/[;,]/)
  .map((value) => value.trim())
  .filter(Boolean) ?? [];

function walk(directory, depth, paths) {
  if (depth > 8) return;
  let entries;
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const candidate = path.join(directory, entry.name);
    if (supportedProducts.some((product) => entry.name === `${product}.app`)) {
      paths.add(candidate);
      continue;
    }
    walk(candidate, depth + 1, paths);
  }
}

function discoverPaths() {
  const roots = process.platform === "darwin"
    ? [
        "/Applications",
        path.join(process.env.HOME ?? "", "Applications"),
        path.join(process.env.HOME ?? "", "Library/Application Support/JetBrains/Toolbox/apps"),
      ]
    : [];
  const missingExplicitPaths = explicitPaths.filter((candidate) => !existsSync(candidate));
  if (missingExplicitPaths.length > 0) {
    throw new Error(`Configured IDE paths do not exist: ${missingExplicitPaths.join(", ")}`);
  }
  const paths = new Set(explicitPaths);
  for (const root of roots) {
    if (existsSync(root)) walk(root, 0, paths);
  }
  return [...paths].filter((candidate) => existsSync(candidate));
}

function readProductInfo(idePath) {
  const candidates = [
    path.join(idePath, "Contents/Resources/product-info.json"),
    path.join(idePath, "product-info.json"),
    path.join(idePath, "lib/product-info.json"),
  ];
  const infoPath = candidates.find(existsSync);
  if (!infoPath) return { productCode: null, buildNumber: null };
  try {
    const info = JSON.parse(readFileSync(infoPath, "utf8"));
    return {
      productCode: info.productCode ?? null,
      buildNumber: info.buildNumber ?? null,
      productName: info.name ?? path.basename(idePath, ".app"),
    };
  } catch {
    return { productCode: null, buildNumber: null };
  }
}

function reportPath(productCode, buildNumber) {
  if (!productCode || !buildNumber) return null;
  const candidate = path.join(
    repositoryRoot,
    "build/reports/pluginVerifier",
    `${productCode}-${buildNumber}`,
    "report.html",
  );
  return existsSync(candidate) ? candidate : null;
}

let idePaths;
try {
  idePaths = discoverPaths();
} catch (error) {
  console.error(`IDE discovery failed: ${error instanceof Error ? error.message : error}`);
  process.exit(1);
}
const gradleArguments = ["verifyPlugin"];
if (idePaths.length > 0) {
  gradleArguments.push(`-PcodeagentVerifierIdePaths=${idePaths.join(",")}`);
}
const verification = spawnSync("./gradlew", gradleArguments, {
  cwd: repositoryRoot,
  stdio: "inherit",
});
if (verification.error) throw verification.error;

const products = idePaths.map((idePath) => {
  const info = readProductInfo(idePath);
  const verifierReport = reportPath(info.productCode, info.buildNumber);
  return {
    path: idePath,
    productName: info.productName ?? path.basename(idePath, ".app"),
    productCode: info.productCode,
    buildNumber: info.buildNumber,
    result: verifierReport ? "report-generated" : "report-not-found",
    verifierReport,
  };
});

const outputPath = path.join(repositoryRoot, "build/reports/jetbrains-verifier.json");
mkdirSync(path.dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify({
  generatedAt: new Date().toISOString(),
  command: `./gradlew ${gradleArguments.join(" ")}`,
  verificationExitCode: verification.status ?? 1,
  discoveredProducts: products,
  note: products.length === 0
    ? "No additional supported JetBrains IDE installation was discovered."
    : undefined,
}, null, 2)}\n`);
console.log(`Verifier evidence: ${outputPath}`);
process.exitCode = verification.status ?? 1;
