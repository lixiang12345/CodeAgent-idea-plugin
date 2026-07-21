# Prototype Parity Evaluation

CodeAgent keeps its current prototype-alignment contract in
`evaluation/parity-codeagent.json`. The manifest lists every release surface,
its status, evidence files, and exact structural contracts that should change
only through an intentional review.

## Run The Gate

Install frontend dependencies, then run:

```bash
npm ci --prefix frontend
node scripts/evaluate-parity.mjs
```

The evaluator writes `build/reports/prototype-parity.json` and exits nonzero on
contract drift. It uses the JDK XML parser for `plugin.xml`, the Svelte compiler
for component commands, the TypeScript compiler API for typed source, and JSON
parsing for the remaining manifests.

The gate currently verifies:

- the exact IDEA action IDs plus extension and listener counts;
- the ordered Settings sections and 31-entry manifest/frontend tool catalog;
- the complete backend OpenAPI path set;
- literal frontend bridge commands against development-host handlers;
- unique surface IDs, valid statuses, and existing evidence files;
- removal of live-status claims from historical or requirements-only docs.

## Change The Contract

When an intentional product change alters one of these surfaces, update the
implementation and `evaluation/parity-codeagent.json` together, run the gate,
and review the JSON report. Do not update expected values only to silence an
unexplained mismatch.

This is a structural contract gate. It does not prove layout quality, runtime
provider behavior, or cross-product IDE compatibility. The 360/420 px visual
and browser workflow gate is implemented separately in
`frontend/e2e/product-alignment.spec.ts`; run it with
`npm run test:e2e --prefix frontend`. Plugin Verifier, the native IDE smoke,
GitHub acceptance, and provider tenant acceptance remain separate release
evidence.
