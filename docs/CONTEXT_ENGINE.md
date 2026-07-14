# ContextEngine integration

CodeAgent vendors the upstream `lixiang12345/ContextEngine-plugin` commit as a pinned Git submodule and compiles its public `ContextEngine` API into the local Node sidecar. The IDE plugin does not maintain a second retrieval implementation.

## Runtime ownership

- The SQLite index, source scan, file watcher, searcher, and retrieval packing run on the developer machine.
- The deployed Agent backend never receives the complete index or direct filesystem access.
- Only context selected by the user or returned by an approved retrieval tool is sent to the model gateway.
- A project is indexed once manually. The sidecar then watches it recursively and runs an 800 ms debounced incremental pass.
- Incremental passes compare content hashes, rewrite only changed files, and remove deleted files. Reads wait for an active index pass so the Agent does not use a half-updated searcher.

## Model deployment

No model is required for the default local mode. ContextEngine combines SQLite FTS5, symbols, paths, graph expansion, Git lineage, reranking, and context packing.

Production deployments should support three modes:

| Mode | Deployment | Intended use |
| --- | --- | --- |
| Local lexical | Bundled sidecar only | Zero-setup default and air-gapped fallback |
| Private semantic | User or organization hosts an OpenAI-compatible embedding endpoint | Privacy-sensitive teams and stronger conceptual retrieval |
| Managed semantic | Product operator hosts the embedding endpoint | Managed accounts with centralized capacity and observability |

The plugin should not silently download or start a multi-gigabyte model. For private semantic retrieval, the current upstream production recommendation is `Qwen/Qwen3-Embedding-0.6B`, optionally paired with `Qwen/Qwen3-Reranker-0.6B`, served through an OpenAI-compatible endpoint. Endpoint settings and credentials belong in a dedicated Context settings page and JetBrains Password Safe.

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
| CodeAgent architecture queries | 14 / 18 | 0.778 | 0.619 | 0.697 |

The missed conceptual queries cover tool-approval orchestration, JCEF asset serving, workspace rule/skill discovery, and remote SSE continuation. They are retained as regression targets for query expansion, semantic retrieval, and the planned Context Agent. On the same checkout, a no-op incremental pass scanned 93 eligible files, rewrote 0 files, and completed in 151 ms. These measurements are development baselines, not release guarantees.

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
