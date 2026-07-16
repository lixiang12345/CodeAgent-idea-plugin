# ContextEngine integration

CodeAgent vendors the upstream `lixiang12345/ContextEngine-plugin` commit as a pinned Git submodule and compiles its public `ContextEngine` API into the local Node sidecar. The IDE plugin does not maintain a second retrieval implementation.

- Current upstream pin: `2490d91cfc36b0f0e76a409ff164aecd62caad6a` (`v0.4.0` plus no-model lexical retrieval and Kotlin chunking fixes, verified July 14, 2026).

## Runtime ownership

- The SQLite index, source scan, file watcher, searcher, and retrieval packing run on the developer machine.
- The deployed Agent backend never receives the complete index or direct filesystem access.
- Only context selected by the user or returned by an approved retrieval tool is sent to the model gateway.
- The plugin initializes an unindexed project automatically. The sidecar then watches the primary project root and any external IntelliJ content roots recursively, and runs one 800 ms debounced incremental pass across the complete root set.
- Incremental passes compare content hashes, rewrite only changed files, and remove deleted files. Reads wait for an active index pass so the Agent does not use a half-updated searcher.

## Model-neutral retrieval output

ContextEngine does not maintain a model registry and does not infer output size
from model names or context windows. It owns indexing, multi-signal retrieval,
reranking, deduplication, and evidence formatting only.

`codebase_retrieval` returns the complete reranked evidence pack selected by
the query plan and `top_k`. Callers may provide `max_tokens` when they explicitly
need a smaller transport payload. Model-specific conversation compaction remains
the responsibility of the host Agent runtime and does not alter retrieval recall.

## Model deployment

No model is required for the default local mode. ContextEngine combines SQLite FTS5, symbols, paths, graph expansion, Git lineage, reranking, and context packing.

Production deployments should support three modes:

| Mode | Deployment | Intended use |
| --- | --- | --- |
| Local lexical | Bundled sidecar only | Zero-setup default and air-gapped fallback |
| Private semantic | User or organization hosts an OpenAI-compatible embedding endpoint | Privacy-sensitive teams and stronger conceptual retrieval |
| Managed semantic | Product operator hosts the embedding endpoint | Managed accounts with centralized capacity and observability |

The current plugin exposes the first two modes. Managed semantic remains deliberately disabled until the backend has an authenticated short-lived proxy/token-refresh path suitable for a long-lived local sidecar.

- Local lexical is the default and requires no model, account, or download.
- Private semantic accepts an OpenAI-compatible `/v1/embeddings` endpoint and optional `/v1/rerank` endpoint.
- The plugin does not silently download or start a multi-gigabyte model.
- The production open-weight recommendation is `Qwen/Qwen3-Embedding-0.6B`, optionally paired with `Qwen/Qwen3-Reranker-0.6B`.
- Endpoint credentials are stored in JetBrains Password Safe and are injected only into the ContextEngine child process.
- Switching retrieval mode restarts the sidecar. Enabling embeddings requires an explicit index rebuild so a settings save never starts an unexpected GPU or network workload.

## Accuracy baseline

The vendored upstream evaluation reports:

| Configuration | Top-1 | Top-3 | Top-5 |
| --- | ---: | ---: | ---: |
| Multi-repository lexical macro | about 0.48 | about 0.79 | about 0.91 |
| Nine-repository Qwen3 embedding macro | about 0.877 | about 0.968 | about 0.984 |

These are upstream benchmark results, not a claim about every CodeAgent project. CodeAgent must keep a repository-specific golden-query suite and report path accuracy, MRR, recall, latency, and incremental-index timings before release.

The current local lexical baseline for this repository is stored in `evaluation/context-codeagent.json`:

| Suite | Passed | Recall@8 | MRR | nDCG@8 |
| --- | ---: | ---: | ---: | ---: |
| CodeAgent architecture queries | 18 / 18 | 1.000 | 0.736 | 0.801 |

Five fresh, isolated no-model indexes produced the same July 14, 2026 result: all 18 retained architecture queries passed with identical ranking metrics. The upstream fixes remove ordinary prose from the symbol channel, filter common query stop words, aggregate evidence across chunks at file level, deepen the lexical candidate pool before aggregation, avoid over-penalizing files in the same source package, prevent duplicate files from consuming result slots, and enforce the requested `topK`. The five previous misses remain in the suite as regression targets. MRR and nDCG still show room for query planning and stronger top-rank precision, so these measurements are development baselines rather than release guarantees.

## Verification

```bash
cd sidecar && npm test
cd vendor/context-engine && npm test
cd vendor/context-engine && npm run eval:self
cd vendor/context-engine && npm run build
node dist/cli.js eval \
  --root ../.. \
  --cases ../../evaluation/context-codeagent.json \
  --reindex
```

The sidecar integration test verifies initial indexing, automatic add/update/delete synchronization, and retrieval of newly indexed content over the JSON Lines protocol.
