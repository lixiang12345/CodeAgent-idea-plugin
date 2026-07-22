#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const evidencePath = path.join(repositoryRoot, "evaluation/github-live-acceptance.json");
const documentationPath = path.join(repositoryRoot, "docs/GITHUB_LIVE_ACCEPTANCE.md");
const reportPath = path.join(repositoryRoot, "build/reports/github-live-evidence-validation.json");
const requiredOutcomes = [
  "pullRequestRead",
  "filesAndCommitsRead",
  "reviewsAndLineCommentsRead",
  "discussionCommentsRead",
  "headShaCheckRunsRead",
  "workflowRunsAndJobsRead",
  "failedStepsRead",
  "temporaryLogRedirectRead",
  "branchProtectionRead",
  "repositoryRulesetsRead",
  "mergeReadinessRead",
  "lineLevelCommentReview",
  "discussionCommentMutation",
  "rerunWorkflow",
  "rerunFailedJobs",
  "rerunSingleJob",
  "cancelWorkflow",
  "forceCancelWorkflow",
  "staleHeadBlocked",
  "draftBlocked",
  "requestedChangesBlocked",
  "insufficientApprovalsBlocked",
  "missingRequiredCheckBlocked",
  "failedRequiredCheckBlocked",
  "dirtyMergeBlocked",
  "requiredConversationResolutionBlocked",
];

function main() {
  const evidenceSource = readFileSync(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceSource);
  const documentation = readFileSync(documentationPath, "utf8");
  const failures = [];

  if (evidence.schemaVersion !== 1) failures.push("schemaVersion must be 1");
  if (evidence.githubApiVersion !== "2022-11-28") failures.push("GitHub API version must be 2022-11-28");
  if (!/^https:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+$/.test(evidence.pullRequest?.url ?? "")) {
    failures.push("pullRequest.url must be a direct GitHub pull-request URL");
  }
  if (evidence.credential?.tokenRecorded !== false) failures.push("credential.tokenRecorded must be false");

  for (const outcome of requiredOutcomes) {
    if (!String(evidence.requestOutcomes?.[outcome] ?? "").startsWith("pass")) {
      failures.push(`requestOutcomes.${outcome} must start with pass`);
    }
  }

  if (evidence.gate?.liveApiBehavior !== "pass") failures.push("gate.liveApiBehavior must be pass");
  if (evidence.gate?.overall === "pass") {
    if (evidence.credential?.type !== "fine-grained-personal-access-token") {
      failures.push("a passing gate requires a fine-grained-personal-access-token");
    }
    if (evidence.credential?.leastPrivilegeCompliant !== true) {
      failures.push("a passing gate requires leastPrivilegeCompliant=true");
    }
    if (evidence.gate?.leastPrivilegeCredential !== "pass") {
      failures.push("a passing gate requires gate.leastPrivilegeCredential=pass");
    }
  } else if (evidence.gate?.overall === "partial") {
    if (evidence.credential?.leastPrivilegeCompliant !== false) {
      failures.push("a partial gate requires leastPrivilegeCompliant=false");
    }
    if (evidence.gate?.leastPrivilegeCredential !== "blocked") {
      failures.push("a partial gate requires gate.leastPrivilegeCredential=blocked");
    }
    if (!String(evidence.gate?.remainingRequirement ?? "").trim()) {
      failures.push("a partial gate requires a remainingRequirement");
    }
  } else {
    failures.push("gate.overall must be pass or partial");
  }

  const secretPatterns = [
    /\bgh[pousr]_[A-Za-z0-9_]{20,}\b/i,
    /\bgithub_pat_[A-Za-z0-9_]{20,}\b/i,
    /authorization\s*[:=]\s*["']?Bearer\s+/i,
    /https:\/\/[^\s"']*(?:actions\.githubusercontent\.com|blob\.core\.windows\.net)[^\s"']*/i,
    /[?&](?:token|sig|signature|se)=[^\s"']+/i,
  ];
  secretPatterns.forEach((pattern) => {
    if (pattern.test(evidenceSource) || pattern.test(documentation)) {
      failures.push(`evidence contains forbidden secret or temporary-URL pattern: ${pattern}`);
    }
  });

  if (!documentation.includes("release gate remains **partial**") && evidence.gate?.overall === "partial") {
    failures.push("documentation must explicitly describe a partial gate");
  }
  if (!documentation.includes("evaluation/github-live-acceptance.json")) {
    failures.push("documentation must link the machine-readable evidence");
  }

  const report = {
    generatedAt: new Date().toISOString(),
    evidence: path.relative(repositoryRoot, evidencePath),
    gate: evidence.gate,
    validatedOutcomes: requiredOutcomes.length,
    forbiddenPatternsChecked: secretPatterns.length,
    pass: failures.length === 0,
    failures,
  };
  mkdirSync(path.dirname(reportPath), { recursive: true });
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`GitHub live evidence: ${report.pass ? "PASS" : "FAIL"} (${requiredOutcomes.length} outcomes)`);
  console.log(`Report: ${reportPath}`);
  if (failures.length > 0) throw new Error(failures.join("\n"));
}

try {
  main();
} catch (error) {
  console.error(`GitHub live evidence validation failed: ${error instanceof Error ? error.message : error}`);
  process.exitCode = 1;
}
