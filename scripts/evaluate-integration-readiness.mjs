#!/usr/bin/env node

import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createIntegrationToolRegistryFromEnv } from "../backend/src/integration-tools.mjs";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const reportPath = path.join(repositoryRoot, "build", "reports", "integration-readiness.json");
const backendEnvPath = path.join(repositoryRoot, "backend", ".env");

function parseArguments(argv) {
  let strict = false;
  const catalogIds = [];
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--strict") {
      strict = true;
    } else if (argument === "--catalog") {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) throw new Error("--catalog requires a catalog ID");
      catalogIds.push(value);
      index += 1;
    } else if (argument.startsWith("--catalog=")) {
      catalogIds.push(argument.slice("--catalog=".length));
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  const normalizedCatalogIds = [...new Set(catalogIds.map((value) => value.trim()).filter(Boolean))];
  if (normalizedCatalogIds.some((value) => !/^[a-z0-9-]+$/.test(value))) {
    throw new Error("Catalog IDs may contain only lowercase letters, numbers, and hyphens");
  }
  return { strict, catalogIds: normalizedCatalogIds };
}

function configured(value) {
  return typeof value === "string" && value.trim().length > 0;
}

async function probeUnavailableTool(registry, tool) {
  try {
    await registry.execute(tool.name, {});
    return { passed: false, status: "unexpected-success" };
  } catch (error) {
    const status = Number(error?.statusCode ?? 0);
    return {
      passed: status === 503,
      status: status || "error",
    };
  }
}

export async function evaluateIntegrationReadiness(env = process.env, { catalogIds = [] } = {}) {
  const registry = createIntegrationToolRegistryFromEnv(env);
  const selectedCatalogIds = new Set(catalogIds);
  const selectedTools = registry.list().filter((tool) => selectedCatalogIds.size === 0 || selectedCatalogIds.has(tool.catalogId));
  if (selectedTools.length === 0) {
    throw new Error(`No integration tools matched catalog selection: ${catalogIds.join(", ")}`);
  }
  const tools = [];
  for (const tool of selectedTools) {
    const requirements = tool.requiredEnvironment.map((name) => ({
      name,
      configured: configured(env[name]),
    }));
    const missingEnvironment = requirements.filter((item) => !item.configured).map((item) => item.name);
    const probe = tool.available
      ? { passed: true, status: "not-run" }
      : await probeUnavailableTool(registry, tool);
    tools.push({
      name: tool.name,
      catalogId: tool.catalogId,
      risk: tool.risk,
      available: tool.available,
      requiredEnvironment: requirements,
      missingEnvironment,
      unavailableReason: tool.available ? undefined : tool.unavailableReason,
      missingCredentialProbe: probe,
    });
  }

  const unavailable = tools.filter((tool) => !tool.available);
  const available = tools.filter((tool) => tool.available);
  const probesPassed = tools.every((tool) => tool.missingCredentialProbe.passed);
  const allConfigured = tools.every((tool) => tool.missingEnvironment.length === 0);
  const report = {
    version: 1,
    generatedAt: new Date().toISOString(),
    networkRequestsMade: false,
    selection: {
      catalogIds,
      matchedTools: tools.map((tool) => tool.name),
    },
    tools,
    summary: {
      total: tools.length,
      available: available.length,
      unavailable: unavailable.length,
      missingCredentialProbesPassed: probesPassed,
      environmentState: allConfigured ? "configured" : available.length > 0 ? "partial" : "unconfigured",
    },
  };
  return report;
}

async function main() {
  const { strict, catalogIds } = parseArguments(process.argv.slice(2));
  if (existsSync(backendEnvPath)) process.loadEnvFile(backendEnvPath);
  const report = await evaluateIntegrationReadiness(process.env, { catalogIds });
  mkdirSync(path.dirname(reportPath), { recursive: true });
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

  const { total, available, unavailable, missingCredentialProbesPassed, environmentState } = report.summary;
  console.log(`Integration readiness: ${missingCredentialProbesPassed ? "PASS" : "FAIL"}`);
  console.log(`Tools: ${available}/${total} available; ${unavailable} unavailable; environment=${environmentState}`);
  console.log(`Report: ${reportPath}`);
  if (!missingCredentialProbesPassed || (strict && unavailable > 0)) {
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`Integration readiness failed: ${error instanceof Error ? error.message : error}`);
    process.exitCode = 1;
  });
}
