# CodeAgent for JetBrains

CodeAgent is an IDE-native AI coding agent for IntelliJ IDEA and other JetBrains IDEs. It combines a separately deployed Agent backend with the open [ContextEngine](https://github.com/lixiang12345/ContextEngine-plugin) retrieval component running locally beside the IDE.

The IDEA plugin is a capability gateway: it owns project access, ContextEngine, approvals, editor actions, terminal execution, and Diff/revert. The deployable backend in `backend/` owns prompts, model credentials, streamed model calls, and tool-call orchestration. See the [prototype parity contract](docs/PROTOTYPE_PARITY.md), [product definition](docs/PRODUCT.md), [Rules and Skills guide](docs/RULES_AND_SKILLS.md), [prompt architecture](docs/PROMPT_ARCHITECTURE.md), [declarative plugins guide](docs/PLUGINS.md), [ContextEngine deployment guide](docs/CONTEXT_ENGINE.md), [provider and data flow](docs/PROVIDER_AND_DATA_FLOW.md), and [architecture analysis](docs/ARCHITECTURE.md).

## Run the backend

The backend operator configures `backend/.env`, then starts the local Docker deployment from the repository root:

```bash
docker compose -f backend/compose.yaml up -d --build
```

Docker publishes the service at `http://127.0.0.1:8788`; fresh plugin installs use that address automatically. Direct development without Docker remains available through `cd backend && npm start` on port `8787`, which requires overriding the Backend URL in Advanced Settings.

For a hosted deployment, build `backend/Dockerfile` and configure either the unified model gateway contract (`MODEL_BASE_URL`, `MODEL_API_KEY`) or a fixed multi-provider allowlist using the `OPENAI_*`, `GROK_*`, and `ANTHROPIC_*` variables documented in `backend/.env.example`. Also provide `DATABASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `PUBLIC_BASE_URL`, and `SESSION_SIGNING_KEY`. The service exposes public OIDC bootstrap endpoints and authenticated account, model, tool, conversation, job, completion, and Agent APIs.

## Install and use

1. Install the ZIP from `build/distributions/` through **Settings > Plugins > Install Plugin from Disk**.
2. Open **Tools > Show CodeAgent** (`Ctrl+Alt+I`, or `Control+Command+I` on macOS).
3. With the local Docker backend running, no backend URL or token entry is required. For a hosted deployment, set its URL under **Settings > Services**, then sign in from **Settings > Account**. CodeAgent discovers `node` and common Node.js installation paths automatically; Advanced Settings can override the Node.js 22.5+ executable.
4. Select **Index project** once. The local sidecar then watches project files and incrementally updates changed or deleted files. Use **Agent** for approved code changes, **Chat** for code-aware collaboration, or **Ask** for read-only analysis.

The panel's workspace menu opens the prototype-aligned **Tasks**, **Git**, and **Image Canvas** pages. Tasks persist per thread and can be imported/exported as Markdown. Git reads the real index and working tree, opens JetBrains Diff, and requires explicit confirmation before committing. Image Canvas previews bounded raster assets from a user-selected directory inside the project and can attach them to the active conversation. Threads support pin, confirmed delete, and Markdown import/export. Messages entered during an active run are queued by the IDEA capability layer and dispatched in order.

Settings reports the result of real backend health, model, and tool-discovery requests, including protocol compatibility and missing integration configuration. A configured URL is not treated as an online backend.

OIDC access and refresh tokens (or the local shared backend token) are stored in JetBrains Password Safe; only expiry metadata is kept in plugin settings. Model and integration credentials stay on the deployed backend. ContextEngine's index stays local; selected repository context, rules, skills, messages, and tool results are sent to the configured backend for the active Agent run.

## Development prerequisites

- JDK 21 (Gradle can provision it through Foojay)
- Node.js 22.5 or newer
- npm 10 or newer

## Build

```bash
git submodule update --init --recursive
cd frontend && npm ci && cd ..
cd sidecar && npm ci && cd ..
cd backend && npm ci && cd ..
cd vendor/context-engine && npm ci && cd ../..
./gradlew buildPlugin
```

## Run in a sandbox IDE

```bash
./gradlew runIde
```

The installable ZIP is written to `build/distributions/`.

ContextEngine is pinned as a Git submodule and bundled into the local Node sidecar. Its SQLite index and file watcher run on each developer machine. Lexical, symbol, path, graph, and Git-lineage retrieval need no model. CodeAgent does not install or start a local embedding or reranker model; semantic retrieval is an explicit opt-in to an operator- or organization-hosted OpenAI-compatible endpoint and requires an explicit index rebuild. Its MIT license is included in the plugin distribution; see [third-party notices](THIRD_PARTY_NOTICES.md).

CodeAgent plugins are bounded declarative JSON manifests, not executable IDEA plugins. Account configuration records synchronize the source, exact version, integrity pin, and granted capabilities; each IDE installation explicitly validates and caches the manifest before activation. The runtime consumes explicitly granted namespaced commands and prompt templates plus read-only rules and selectable skills. Reserved execution capabilities do not load JVM, Node.js, shell, MCP, or tool-handler code.

## Verify

```bash
cd frontend && npm run check && cd ..
cd sidecar && npm test && cd ..
cd backend && npm test && cd ..
cd vendor/context-engine && npm test && npm run build && cd ../..
./gradlew test buildPlugin verifyPlugin
```

The first Gradle verification run downloads the target IntelliJ IDEA distribution and Plugin Verifier, so it is substantially slower than subsequent runs.

## Release

Release tags must exactly match the version in `gradle.properties`, for example `v0.7.0`. The `Publish plugin` workflow checks that relationship, installs and tests every JavaScript workspace, verifies the signed ZIP, publishes it to JetBrains Marketplace, and uploads the exact distribution as a workflow artifact.

Configure the protected `release` environment with these GitHub Actions secrets:

- `JETBRAINS_MARKETPLACE_TOKEN`
- `JETBRAINS_CERTIFICATE_CHAIN`
- `JETBRAINS_PRIVATE_KEY`
- `JETBRAINS_PRIVATE_KEY_PASSWORD`

The release workflow maps those secrets to the IntelliJ Platform Gradle Plugin only during `publishPlugin`; signing material is never committed to the repository.
