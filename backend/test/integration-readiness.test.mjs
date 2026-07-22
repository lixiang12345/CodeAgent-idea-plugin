import test from "node:test";
import assert from "node:assert/strict";
import { evaluateIntegrationReadiness } from "../../scripts/evaluate-integration-readiness.mjs";

test("integration readiness reports every unconfigured adapter without network access", async () => {
  const report = await evaluateIntegrationReadiness({});

  assert.equal(report.networkRequestsMade, false);
  assert.equal(report.summary.available, 0);
  assert.equal(report.summary.unavailable, report.summary.total);
  assert.equal(report.summary.missingCredentialProbesPassed, true);
  assert.ok(report.tools.length >= 10);
  assert.ok(report.tools.every((tool) => tool.missingCredentialProbe.status === 503));
});

test("readiness report exposes configured state without serializing credential values", async () => {
  const secret = "linear-test-secret";
  const report = await evaluateIntegrationReadiness({
    LINEAR_API_KEY: secret,
    LINEAR_API_URL: "https://linear.example.test/graphql",
  });
  const linear = report.tools.find((tool) => tool.name === "linear_search");

  assert.equal(linear.available, true);
  assert.deepEqual(linear.missingEnvironment, []);
  assert.equal(report.summary.available, 1);
  assert.equal(JSON.stringify(report).includes(secret), false);
});

test("readiness can enforce one provider catalog independently", async () => {
  const report = await evaluateIntegrationReadiness({}, { catalogIds: ["github"] });

  assert.deepEqual(report.selection.catalogIds, ["github"]);
  assert.equal(report.summary.total, 4);
  assert.ok(report.tools.every((tool) => tool.catalogId === "github"));
  assert.ok(report.tools.every((tool) => tool.missingCredentialProbe.status === 503));
});
