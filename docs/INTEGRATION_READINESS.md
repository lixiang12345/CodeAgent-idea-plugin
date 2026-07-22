# Integration Readiness

`scripts/evaluate-integration-readiness.mjs` is the credential-safe gate for
cloud integration setup. It does not contact any provider. It builds the same
backend registry used by the Agent, records which required environment names
are present, and probes every unavailable tool to confirm that it fails closed
with HTTP 503.

Run the default configuration check with:

```bash
node scripts/evaluate-integration-readiness.mjs
```

The CLI reads `backend/.env` when it exists, matching the backend development
entry point. Existing process environment values retain precedence.

The report is written to
`build/reports/integration-readiness.json`. It contains tool names, catalog
IDs, risk classes, redacted environment presence, missing requirements, and
the unavailable probe result. Secret values, endpoints, and request bodies are
never written to the report. `networkRequestsMade` is always `false` for this
mode.

Use `--strict` only in an environment where every registered tool is expected
to be configured:

```bash
node scripts/evaluate-integration-readiness.mjs --strict
```

Strict mode still does not perform live provider calls. Live acceptance remains
a separate, provider-scoped operation requiring an isolated tenant, minimum
permissions, and explicit mutation approval. The CI and release workflows run
the non-strict gate and upload its report so missing credentials remain visible
without making a build depend on private test tenants.
