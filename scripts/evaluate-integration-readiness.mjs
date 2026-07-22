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
  const strict = argv.includes("--strict");
  const unknown = argv.filter((argument) => argument !== "--strict");
  if (unknown.length > 0) {
    throw new Error(`Unknown argument: ${unknown.join(", ")}`);
  }
  return { strict };
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

export async function evaluateIntegrationReadiness(env = process.env) {
  const registry = createIntegrationToolRegistryFromEnv(env);
  const tools = [];
  for (const tool of registry.list()) {
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
  const { strict } = parseArguments(process.argv.slice(2));
  if (existsSync(backendEnvPath)) process.loadEnvFile(backendEnvPath);
  const report = await evaluateIntegrationReadiness();
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
