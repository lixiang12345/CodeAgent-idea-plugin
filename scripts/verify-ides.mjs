#!/usr/bin/env node

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const supportedProducts = ["IntelliJ IDEA", "PyCharm", "WebStorm", "CLion", "GoLand", "PhpStorm", "Rider"];
const verifierProductNames = {
  IC: "IntelliJ IDEA Community",
  IU: "IntelliJ IDEA Ultimate",
  PY: "PyCharm",
  WS: "WebStorm",
  CL: "CLion",
  GO: "GoLand",
  PS: "PhpStorm",
  RD: "Rider",
};
const verifierTargets = Object.entries(verifierProductNames).map(([productCode, productName]) => ({
  productCode,
  productName,
}));
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

function summarizeReport(verifierReport) {
  if (!verifierReport || !existsSync(verifierReport)) return {
    result: "report-not-found",
    verifierStatus: "unknown",
    experimentalApiUsages: null,
  };
  const source = readFileSync(verifierReport, "utf8")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/\s+/g, " ");
  const status = /\bIncompatible\b|Compatibility problems/i.test(source)
    ? "incompatible"
    : /\bCompatible\b/i.test(source)
      ? "compatible"
      : "unknown";
  const experimentalMatch = source.match(/(\d+) usages? of experimental API/i);
  return {
    result: "report-generated",
    verifierStatus: status,
    experimentalApiUsages: experimentalMatch ? Number(experimentalMatch[1]) : null,
  };
}

function reportEntries() {
  const root = path.join(repositoryRoot, "build/reports/pluginVerifier");
  if (!existsSync(root)) return [];
  return readdirSync(root, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => {
      const match = entry.name.match(/^([A-Z]{2})-(.+)$/);
      if (!match) return null;
      const [, productCode, buildNumber] = match;
      const verifierReport = reportPath(productCode, buildNumber);
      return {
        productName: verifierProductNames[productCode] ?? productCode,
        productCode,
        buildNumber,
        verifierReport,
        ...summarizeReport(verifierReport),
      };
    })
    .filter(Boolean);
}

function compareBuildNumbers(left, right) {
  const leftParts = String(left ?? "").match(/\d+/g)?.map(Number) ?? [];
  const rightParts = String(right ?? "").match(/\d+/g)?.map(Number) ?? [];
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

export function buildProductMatrix(products, installedProducts) {
  const installedCodes = new Set(installedProducts.map((product) => product.productCode).filter(Boolean));
  return verifierTargets.map((target) => {
    const latest = products
      .filter((product) => product.productCode === target.productCode)
      .sort((left, right) => compareBuildNumbers(left.buildNumber, right.buildNumber))
      .at(-1);
    const verificationState = latest?.verifierStatus === "compatible"
      ? "verified-compatible"
      : latest?.verifierStatus === "incompatible"
        ? "verified-incompatible"
        : latest
          ? "report-unknown"
          : "unverified";
    return {
      ...target,
      installationState: installedCodes.has(target.productCode) ? "installed" : "not-installed",
      verificationState,
      buildNumber: latest?.buildNumber ?? null,
      verifierReport: latest?.verifierReport ?? null,
      experimentalApiUsages: latest?.experimentalApiUsages ?? null,
    };
  });
}

function main() {
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

  const discoveredProducts = idePaths.map((idePath) => {
    const info = readProductInfo(idePath);
    const verifierReport = reportPath(info.productCode, info.buildNumber);
    return {
      path: idePath,
      productName: info.productName ?? path.basename(idePath, ".app"),
      productCode: info.productCode,
      buildNumber: info.buildNumber,
      result: verifierReport ? "report-generated" : "report-not-found",
      verifierReport,
      ...summarizeReport(verifierReport),
    };
  });

  const productsByKey = new Map(discoveredProducts.map((product) => [`${product.productCode}:${product.buildNumber}`, product]));
  for (const report of reportEntries()) {
    const key = `${report.productCode}:${report.buildNumber}`;
    productsByKey.set(key, { ...productsByKey.get(key), ...report });
  }
  const products = [...productsByKey.values()];
  const productMatrix = buildProductMatrix(products, discoveredProducts);
  const incompatibleProducts = products.filter((product) => product.verifierStatus === "incompatible");
  const unverifiedProducts = productMatrix.filter((product) => product.verificationState === "unverified");

  const outputPath = path.join(repositoryRoot, "build/reports/jetbrains-verifier.json");
  mkdirSync(path.dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    command: `./gradlew ${gradleArguments.join(" ")}`,
    verificationExitCode: verification.status ?? 1,
    discoveredProducts: products,
    productMatrix,
    compatibilityGate: incompatibleProducts.length === 0 && verification.status === 0 ? "pass" : "fail",
    incompatibleProducts: incompatibleProducts.map((product) => `${product.productCode}-${product.buildNumber}`),
    unverifiedProducts: unverifiedProducts.map((product) => product.productCode),
    note: unverifiedProducts.length > 0
      ? "Products without a generated verifier report remain explicitly unverified."
      : undefined,
  }, null, 2)}\n`);
  console.log(`Verifier evidence: ${outputPath}`);
  process.exitCode = incompatibleProducts.length > 0 || verification.status !== 0 ? 1 : 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
