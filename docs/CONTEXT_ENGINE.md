# ContextEngine integration

CodeAgent vendors the upstream `lixiang12345/ContextEngine-plugin` commit as a pinned Git submodule and compiles its public `ContextEngine` API into the local Node sidecar. The IDE plugin does not maintain a second retrieval implementation.

- Current upstream pin: `ce361c499131dfcc2421fd066753da86b8f87f47` (`v0.4.0`, verified July 13, 2026).

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
| CodeAgent architecture queries | 13 / 18 | 0.722 | 0.577 | 0.633 |

The July 14, 2026 lexical run missed five conceptual targets: ContextEngine process ownership, tool-approval orchestration, JCEF asset serving, workspace rule/skill discovery, and remote SSE continuation. Adding a same-domain runtime-settings test displaced the ContextEngine client from the top results, which demonstrates that raw chunk-level lexical ranking remains sensitive to repository growth. These cases are retained as regression targets for file-level reranking, query planning, semantic retrieval, and the Context Agent. These measurements are development baselines, not release guarantees.

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
