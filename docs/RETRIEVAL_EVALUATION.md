# Repository Retrieval Evaluation

CodeAgent uses a versioned repository-specific retrieval suite as a CI and
release quality gate. The suite exercises the local ContextEngine behavior that
the plugin ships without assuming access to a model provider.

## Coverage

`evaluation/context-codeagent.json` contains goldens in four required
categories:

- `architecture`: natural-language requests for component ownership and data
  flow.
- `symbol`: exact or near-exact identifiers across Kotlin and JavaScript.
- `git-history`: known commit subjects within the indexed Git lineage.
- `multi-root`: documentation queries restricted to the configured `docs`
  index root, including its path prefix.

The evaluator excludes `evaluation/**`, its own script, and generated build
output from the primary corpus. This prevents queries and expected paths in the
test data from becoming retrieval candidates.

## Run The Gate

Install and build ContextEngine before running the repository evaluator:

```bash
npm ci --prefix vendor/context-engine
npm run build --prefix vendor/context-engine
node scripts/evaluate-retrieval.mjs
```

The command writes `build/reports/context-retrieval.json` and exits nonzero when
an absolute threshold fails or a metric regresses beyond the tolerance recorded
in the suite. The report contains per-query ranked paths, category summaries,
the current-versus-baseline comparison, and full and incremental index timing.
Full indexing, no-change indexing, and one-file incremental indexing each have
an absolute limit and participate in the baseline latency comparison.
CI and release workflows upload the same JSON file as a build artifact, including
when the gate fails after producing a report.

The evaluator always creates a fresh temporary index. It explicitly removes
embedding and neural-reranker configuration from that index, even when provider
credentials exist in the caller's environment. Semantic evaluation remains a
separate opt-in operator concern.

## Update The Baseline

Only update the baseline after reviewing the complete report:

```bash
node scripts/evaluate-retrieval.mjs --update-baseline
git diff -- evaluation/context-codeagent.json evaluation/context-codeagent-baseline.json
node scripts/evaluate-retrieval.mjs
```

The update command refuses to record a baseline that fails an absolute quality,
latency, or incremental-index correctness threshold. A baseline change must be
committed with the retrieval change that caused it. The pull request or release
notes must explain any lower path accuracy, MRR, or recall and any material
latency or indexing increase, even when the change remains inside the configured
tolerance. Do not refresh the baseline solely to make a regression pass.

Latency depends on the host filesystem and CPU. Review both the absolute values
and the multiplier-based comparison; reproduce unexpected changes on the same
host before accepting a new baseline.
