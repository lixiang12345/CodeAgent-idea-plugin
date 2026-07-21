#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { performance } from "node:perf_hooks";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const requiredCategories = ["architecture", "symbol", "git-history", "multi-root"];
const baselineMetricKeys = [
  "pathAccuracy",
  "meanRecallAtK",
  "meanMrr",
  "symbolAccuracy",
  "meanLatencyMs",
  "p95LatencyMs",
  "fullIndexMs",
  "incrementalNoChangeMs",
  "incrementalOneFileMs",
];

function parseArguments(argv) {
  const options = {
    root: repositoryRoot,
    cases: path.join(repositoryRoot, "evaluation/context-codeagent.json"),
    baseline: path.join(repositoryRoot, "evaluation/context-codeagent-baseline.json"),
    report: path.join(repositoryRoot, "build/reports/context-retrieval.json"),
    updateBaseline: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--update-baseline") {
      options.updateBaseline = true;
      continue;
    }
    if (["--root", "--cases", "--baseline", "--report"].includes(argument)) {
      const value = argv[index + 1];
      if (!value) throw new Error(`${argument} requires a value`);
      options[argument.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = path.resolve(value);
      index += 1;
      continue;
    }
    if (argument === "--help") {
      console.log(
        "Usage: node scripts/evaluate-retrieval.mjs [--update-baseline] "
          + "[--root DIR] [--cases FILE] [--baseline FILE] [--report FILE]",
      );
      process.exit(0);
    }
    throw new Error(`Unknown argument: ${argument}`);
  }

  return options;
}

function readJson(filePath) {
  return JSON.parse(readFileSync(filePath, "utf8"));
}

function finiteNumber(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(`${label} must be a finite number`);
  }
  return value;
}

function validateSuite(suite, suitePath, root) {
  if (suite?.schemaVersion !== 1) {
    throw new Error(`${suitePath}: unsupported schemaVersion ${suite?.schemaVersion ?? "<missing>"}`);
  }
  if (typeof suite.suite !== "string" || !suite.suite.trim()) {
    throw new Error(`${suitePath}: suite must be a non-empty string`);
  }
  if (!Array.isArray(suite.cases) || suite.cases.length === 0) {
    throw new Error(`${suitePath}: cases must be a non-empty array`);
  }

  const seenIds = new Set();
  const seenCategories = new Set();
  for (const testCase of suite.cases) {
    if (typeof testCase.id !== "string" || !testCase.id.trim()) {
      throw new Error(`${suitePath}: every case needs a non-empty id`);
    }
    if (seenIds.has(testCase.id)) {
      throw new Error(`${suitePath}: duplicate case id ${testCase.id}`);
    }
    seenIds.add(testCase.id);
    if (!requiredCategories.includes(testCase.category)) {
      throw new Error(`${suitePath}: ${testCase.id} has unsupported category ${testCase.category}`);
    }
    seenCategories.add(testCase.category);
    if (typeof testCase.query !== "string" || !testCase.query.trim()) {
      throw new Error(`${suitePath}: ${testCase.id} needs a query`);
    }
    if (!Array.isArray(testCase.expectPaths) || testCase.expectPaths.length === 0) {
      throw new Error(`${suitePath}: ${testCase.id} needs at least one expectPaths entry`);
    }
    if (!testCase.expectPaths.every((entry) => typeof entry === "string" && entry.length > 0)) {
      throw new Error(`${suitePath}: ${testCase.id} has an invalid expected path`);
    }
    if (testCase.expectSymbols !== undefined
      && (!Array.isArray(testCase.expectSymbols)
        || !testCase.expectSymbols.every((entry) => typeof entry === "string" && entry.length > 0))) {
      throw new Error(`${suitePath}: ${testCase.id} has invalid expectSymbols`);
    }
    if (testCase.topK !== undefined
      && (!Number.isInteger(testCase.topK) || testCase.topK < 1 || testCase.topK > 50)) {
      throw new Error(`${suitePath}: ${testCase.id} topK must be an integer from 1 to 50`);
    }
    if (testCase.pathPrefix !== undefined
      && (typeof testCase.pathPrefix !== "string" || !testCase.pathPrefix.trim())) {
      throw new Error(`${suitePath}: ${testCase.id} pathPrefix must be a non-empty string`);
    }
  }

  for (const category of requiredCategories) {
    if (!seenCategories.has(category)) {
      throw new Error(`${suitePath}: missing required category ${category}`);
    }
  }

  const engine = suite.engine ?? {};
  if (!Array.isArray(engine.extraRoots) || engine.extraRoots.length === 0) {
    throw new Error(`${suitePath}: engine.extraRoots must exercise at least one additional root`);
  }
  for (const extraRoot of engine.extraRoots) {
    if (typeof extraRoot.name !== "string" || typeof extraRoot.path !== "string") {
      throw new Error(`${suitePath}: each extra root needs name and path strings`);
    }
    const absolutePath = path.resolve(root, extraRoot.path);
    if (!existsSync(absolutePath)) {
      throw new Error(`${suitePath}: extra root does not exist: ${absolutePath}`);
    }
  }

  const thresholds = suite.thresholds ?? {};
  for (const key of [
    "minPathAccuracy",
    "minMeanRecallAtK",
    "minMeanMrr",
    "minSymbolAccuracy",
    "maxMeanLatencyMs",
    "maxP95LatencyMs",
    "maxFullIndexMs",
    "maxIncrementalNoChangeMs",
    "maxIncrementalOneFileMs",
    "maxQualityDrop",
    "maxLatencyMultiplier",
    "latencyAllowanceMs",
  ]) {
    finiteNumber(thresholds[key], `thresholds.${key}`);
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function git(root, arguments_) {
  return execFileSync("git", arguments_, {
    cwd: root,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  }).trim();
}

function mean(values) {
  return values.length === 0 ? 0 : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function percentile(values, ratio) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * ratio) - 1)];
}

function rounded(value) {
  return Math.round(value * 1000) / 1000;
}

function matchesPath(actual, expected) {
  return actual === expected;
}

function reciprocalRank(hitPaths, expectedPaths) {
  const rank = hitPaths.findIndex((actual) => expectedPaths.some((expected) => matchesPath(actual, expected)));
  return rank < 0 ? 0 : 1 / (rank + 1);
}

function recallAtK(hitPaths, expectedPaths) {
  const hits = expectedPaths.filter((expected) => hitPaths.some((actual) => matchesPath(actual, expected)));
  return hits.length / expectedPaths.length;
}

function symbolRecall(hitSymbols, expectedSymbols) {
  if (!expectedSymbols?.length) return null;
  const normalizedHits = hitSymbols.map((symbol) => symbol.toLowerCase());
  const hits = expectedSymbols.filter((expected) => {
    const normalized = expected.toLowerCase();
    return normalizedHits.some((actual) => actual.includes(normalized));
  });
  return hits.length / expectedSymbols.length;
}

function summarizeCases(cases) {
  const latencies = cases.map((testCase) => testCase.latencyMs);
  const symbolCases = cases.filter((testCase) => testCase.symbolRecallAtK !== null);
  const summary = {
    caseCount: cases.length,
    pathAccuracy: mean(cases.map((testCase) => testCase.top1PathHit ? 1 : 0)),
    meanRecallAtK: mean(cases.map((testCase) => testCase.recallAtK)),
    meanMrr: mean(cases.map((testCase) => testCase.mrr)),
    symbolAccuracy: symbolCases.length === 0
      ? null
      : mean(symbolCases.map((testCase) => testCase.symbolRecallAtK)),
    meanLatencyMs: mean(latencies),
    p95LatencyMs: percentile(latencies, 0.95),
  };
  return Object.fromEntries(
    Object.entries(summary).map(([key, value]) => [key, typeof value === "number" ? rounded(value) : value]),
  );
}

function categorySummaries(cases) {
  return Object.fromEntries(
    requiredCategories.map((category) => [
      category,
      summarizeCases(cases.filter((testCase) => testCase.category === category)),
    ]),
  );
}

function absoluteGateFailures(metrics, indexing, stats, thresholds) {
  const failures = [];
  const requireAtLeast = (label, actual, expected) => {
    if (actual < expected) failures.push(`${label} ${actual} is below ${expected}`);
  };
  const requireAtMost = (label, actual, expected) => {
    if (actual > expected) failures.push(`${label} ${actual} exceeds ${expected}`);
  };

  requireAtLeast("pathAccuracy", metrics.pathAccuracy, thresholds.minPathAccuracy);
  requireAtLeast("meanRecallAtK", metrics.meanRecallAtK, thresholds.minMeanRecallAtK);
  requireAtLeast("meanMrr", metrics.meanMrr, thresholds.minMeanMrr);
  requireAtLeast("symbolAccuracy", metrics.symbolAccuracy, thresholds.minSymbolAccuracy);
  requireAtMost("meanLatencyMs", metrics.meanLatencyMs, thresholds.maxMeanLatencyMs);
  requireAtMost("p95LatencyMs", metrics.p95LatencyMs, thresholds.maxP95LatencyMs);
  requireAtMost("full.durationMs", indexing.full.durationMs, thresholds.maxFullIndexMs);
  requireAtMost(
    "incremental.noChange.durationMs",
    indexing.incremental.noChange.durationMs,
    thresholds.maxIncrementalNoChangeMs,
  );
  requireAtMost(
    "incremental.oneFileAdded.durationMs",
    indexing.incremental.oneFileAdded.durationMs,
    thresholds.maxIncrementalOneFileMs,
  );
  if (stats.hasEmbeddings) failures.push("evaluation index unexpectedly contains embeddings");
  if (indexing.incremental.noChange.filesIndexed !== 0
    || indexing.incremental.noChange.chunksWritten !== 0) {
    failures.push("no-change indexing rewrote files or chunks");
  }
  if (indexing.incremental.oneFileAdded.filesIndexed !== 1
    || indexing.incremental.oneFileAdded.chunksWritten < 1) {
    failures.push("one-file incremental indexing did not index exactly one new file");
  }
  if (indexing.incremental.probeRemoved.filesRemoved !== 1) {
    failures.push("incremental cleanup did not remove exactly one probe file");
  }
  return failures;
}

function baselineComparison(current, baseline, thresholds) {
  const failures = [];
  const changes = {};
  for (const key of ["pathAccuracy", "meanRecallAtK", "meanMrr", "symbolAccuracy"]) {
    const delta = rounded(current[key] - baseline[key]);
    changes[key] = { baseline: baseline[key], current: current[key], delta };
    if (delta < -thresholds.maxQualityDrop) {
      failures.push(`${key} regressed by ${Math.abs(delta)}, allowed ${thresholds.maxQualityDrop}`);
    }
  }
  for (const key of [
    "meanLatencyMs",
    "p95LatencyMs",
    "fullIndexMs",
    "incrementalNoChangeMs",
    "incrementalOneFileMs",
  ]) {
    const limit = rounded(baseline[key] * thresholds.maxLatencyMultiplier + thresholds.latencyAllowanceMs);
    changes[key] = { baseline: baseline[key], current: current[key], limit };
    if (current[key] > limit) failures.push(`${key} ${current[key]} exceeds regression limit ${limit}`);
  }
  return { changes, failures };
}

function comparableMetrics(retrieval, indexing) {
  return {
    pathAccuracy: retrieval.pathAccuracy,
    meanRecallAtK: retrieval.meanRecallAtK,
    meanMrr: retrieval.meanMrr,
    symbolAccuracy: retrieval.symbolAccuracy,
    meanLatencyMs: retrieval.meanLatencyMs,
    p95LatencyMs: retrieval.p95LatencyMs,
    fullIndexMs: indexing.full.durationMs,
    incrementalNoChangeMs: indexing.incremental.noChange.durationMs,
    incrementalOneFileMs: indexing.incremental.oneFileAdded.durationMs,
  };
}

async function evaluate(options, suite) {
  const engineEntry = path.join(options.root, "vendor/context-engine/dist/engine.js");
  const configEntry = path.join(options.root, "vendor/context-engine/dist/config.js");
  if (!existsSync(engineEntry) || !existsSync(configEntry)) {
    throw new Error(
      "ContextEngine is not built. Run: npm run build --prefix vendor/context-engine",
    );
  }

  process.env.CONTEXTENGINE_COMMIT_LIMIT = String(suite.engine.commitLimit);
  const [{ ContextEngine }, { resolveEngineConfig }] = await Promise.all([
    import(pathToFileURL(engineEntry).href),
    import(pathToFileURL(configEntry).href),
  ]);

  const dataDir = path.join(options.root, `build/tmp/context-retrieval-index-${process.pid}`);
  const probePath = path.join(options.root, `context-retrieval-probe-${process.pid}.kt`);
  rmSync(dataDir, { recursive: true, force: true });
  const extraRoots = suite.engine.extraRoots.map((entry) => ({
    name: entry.name,
    path: path.resolve(options.root, entry.path),
    kind: entry.kind,
  }));
  const config = resolveEngineConfig({
    root: options.root,
    dataDir,
    extraRoots,
    extraIgnores: suite.engine.exclude,
  });
  config.embeddings = undefined;
  config.neuralRerank = undefined;
  const engine = new ContextEngine(config);

  try {
    const fullStarted = performance.now();
    const full = await engine.index();
    const fullDurationMs = rounded(performance.now() - fullStarted);
    const stats = engine.stats();
    const cases = [];

    for (const testCase of suite.cases) {
      const started = performance.now();
      const hits = await engine.search({
        query: testCase.query,
        topK: testCase.topK ?? 8,
        mode: suite.engine.mode,
        pathPrefix: testCase.pathPrefix,
        expandGraph: suite.engine.expandGraph,
        diversify: suite.engine.diversify,
        includeCommits: true,
      });
      const latencyMs = rounded(performance.now() - started);
      const hitPaths = hits.map((hit) => hit.chunk.path);
      const hitSymbols = hits
        .map((hit) => hit.chunk.symbol)
        .filter((symbol) => typeof symbol === "string" && symbol.length > 0);
      const symbolRecallAtK = symbolRecall(hitSymbols, testCase.expectSymbols);
      cases.push({
        id: testCase.id,
        category: testCase.category,
        query: testCase.query,
        expectPaths: testCase.expectPaths,
        expectSymbols: testCase.expectSymbols ?? [],
        hitPaths,
        hitSymbols,
        top1PathHit: testCase.expectPaths.some((expected) => matchesPath(hitPaths[0], expected)),
        recallAtK: rounded(recallAtK(hitPaths, testCase.expectPaths)),
        mrr: rounded(reciprocalRank(hitPaths, testCase.expectPaths)),
        symbolRecallAtK: symbolRecallAtK === null ? null : rounded(symbolRecallAtK),
        latencyMs,
      });
    }

    const noChange = await engine.index();
    writeFileSync(
      probePath,
      "package com.codeagent.evaluation\n\nclass ContextRetrievalIncrementalProbe\n",
    );
    const oneFileAdded = await engine.index();
    rmSync(probePath, { force: true });
    const probeRemoved = await engine.index();

    return {
      stats,
      cases,
      full: { ...full, durationMs: fullDurationMs },
      incremental: {
        noChange,
        oneFileAdded,
        probeRemoved,
      },
    };
  } finally {
    engine.close();
    rmSync(probePath, { force: true });
    rmSync(dataDir, { recursive: true, force: true });
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const suite = readJson(options.cases);
  validateSuite(suite, options.cases, options.root);
  const suiteDigest = sha256(JSON.stringify({ engine: suite.engine, cases: suite.cases }));
  const sourceRevision = git(options.root, ["rev-parse", "HEAD"]);
  const engineRevision = git(path.join(options.root, "vendor/context-engine"), ["rev-parse", "HEAD"]);
  const workspaceDirty = git(options.root, ["status", "--porcelain", "--untracked-files=no"]).length > 0;

  console.log(`Retrieval suite: ${suite.suite} (${suite.cases.length} cases)`);
  console.log("Retrieval mode: local lexical, symbol, path, graph, and Git lineage only");
  const result = await evaluate(options, suite);
  const retrieval = summarizeCases(result.cases);
  const categories = categorySummaries(result.cases);
  const indexing = {
    full: result.full,
    incremental: result.incremental,
  };
  const currentComparable = comparableMetrics(retrieval, indexing);
  const absoluteFailures = absoluteGateFailures(
    retrieval,
    indexing,
    result.stats,
    suite.thresholds,
  );

  let comparison = null;
  const gateFailures = [...absoluteFailures];
  if (!options.updateBaseline) {
    if (!existsSync(options.baseline)) {
      gateFailures.push(`baseline is missing: ${options.baseline}`);
    } else {
      const baseline = readJson(options.baseline);
      if (baseline.schemaVersion !== 1 || baseline.suite !== suite.suite) {
        gateFailures.push("baseline schema or suite does not match the query suite");
      } else if (baseline.suiteDigest !== suiteDigest) {
        gateFailures.push("query suite changed; review results and run with --update-baseline");
      } else {
        const invalidMetrics = baselineMetricKeys.filter(
          (key) => typeof baseline.metrics?.[key] !== "number"
            || !Number.isFinite(baseline.metrics[key]),
        );
        if (invalidMetrics.length > 0) {
          gateFailures.push(`baseline has invalid metrics: ${invalidMetrics.join(", ")}`);
        } else {
          comparison = baselineComparison(currentComparable, baseline.metrics, suite.thresholds);
          gateFailures.push(...comparison.failures);
        }
      }
    }
  }

  const report = {
    schemaVersion: 1,
    suite: suite.suite,
    suiteDigest,
    evaluatedAt: new Date().toISOString(),
    sourceRevision,
    engineRevision,
    workspaceDirty,
    semanticRetrievalEnabled: false,
    corpus: result.stats,
    retrieval,
    categories,
    indexing,
    comparison,
    gate: {
      passed: gateFailures.length === 0,
      failures: gateFailures,
    },
    cases: result.cases,
  };
  mkdirSync(path.dirname(options.report), { recursive: true });
  writeFileSync(options.report, `${JSON.stringify(report, null, 2)}\n`);

  if (options.updateBaseline) {
    if (absoluteFailures.length > 0) {
      throw new Error(`Cannot update a failing baseline:\n- ${absoluteFailures.join("\n- ")}`);
    }
    const baseline = {
      schemaVersion: 1,
      suite: suite.suite,
      suiteDigest,
      recordedAt: report.evaluatedAt,
      sourceRevision,
      engineRevision,
      metrics: currentComparable,
    };
    mkdirSync(path.dirname(options.baseline), { recursive: true });
    writeFileSync(options.baseline, `${JSON.stringify(baseline, null, 2)}\n`);
    console.log(`Updated baseline: ${options.baseline}`);
  }

  for (const testCase of result.cases) {
    const status = testCase.recallAtK === 1 && (testCase.symbolRecallAtK ?? 1) === 1 ? "PASS" : "MISS";
    console.log(
      `${status.padEnd(4)} ${testCase.category.padEnd(11)} ${testCase.id.padEnd(34)}`
        + ` MRR=${testCase.mrr.toFixed(3)} R@k=${testCase.recallAtK.toFixed(3)}`
        + ` ${testCase.latencyMs.toFixed(1)}ms`,
    );
  }
  console.log(
    `Summary: pathAccuracy=${retrieval.pathAccuracy.toFixed(3)} `
      + `MRR=${retrieval.meanMrr.toFixed(3)} recall=${retrieval.meanRecallAtK.toFixed(3)} `
      + `meanLatency=${retrieval.meanLatencyMs.toFixed(1)}ms`,
  );
  console.log(
    `Index: full=${result.full.durationMs.toFixed(1)}ms `
      + `noChange=${result.incremental.noChange.durationMs}ms `
      + `oneFile=${result.incremental.oneFileAdded.durationMs}ms`,
  );
  console.log(`Report: ${options.report}`);

  if (gateFailures.length > 0) {
    throw new Error(`Retrieval quality gate failed:\n- ${gateFailures.join("\n- ")}`);
  }
}

try {
  await main();
} catch (error) {
  console.error(`\nRetrieval evaluation failed: ${error instanceof Error ? error.message : error}`);
  process.exitCode = 1;
}
