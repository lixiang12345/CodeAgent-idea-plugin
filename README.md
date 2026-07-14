# CodeAgent for JetBrains

CodeAgent is an IDE-native AI coding agent for IntelliJ IDEA and other JetBrains IDEs. It combines a separately deployed Agent backend with the open [ContextEngine](https://github.com/lixiang12345/ContextEngine-plugin) retrieval component running locally beside the IDE.

The IDEA plugin is a capability gateway: it owns project access, ContextEngine, approvals, editor actions, terminal execution, and Diff/revert. The deployable backend in `backend/` owns prompts, model credentials, streamed model calls, and tool-call orchestration. See the [prototype parity contract](docs/PROTOTYPE_PARITY.md), [product definition](docs/PRODUCT.md), [Rules and Skills guide](docs/RULES_AND_SKILLS.md), [prompt architecture](docs/PROMPT_ARCHITECTURE.md), [provider and data flow](docs/PROVIDER_AND_DATA_FLOW.md), and [architecture analysis](docs/ARCHITECTURE.md).

## Run the backend

The backend operator configures `backend/.env`, then starts the local Docker deployment from the repository root:

```bash
docker compose -f backend/compose.yaml up -d --build
```

Docker publishes the service at `http://127.0.0.1:8788`; fresh plugin installs use that address automatically. Direct development without Docker remains available through `cd backend && npm start` on port `8787`, which requires overriding the Backend URL in Advanced Settings.

For a hosted deployment, build `backend/Dockerfile` and provide the unified model gateway contract (`MODEL_BASE_URL`, `MODEL_API_KEY`) plus `DATABASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `PUBLIC_BASE_URL`, and `SESSION_SIGNING_KEY`. Optional web/SaaS integration variables are listed in `backend/.env.example`. The service exposes public OIDC bootstrap endpoints and authenticated account, model, tool, conversation, job, completion, and Agent APIs.

## Install and use

1. Install the ZIP from `build/distributions/` through **Settings > Plugins > Install Plugin from Disk**.
2. Open **Tools > Show CodeAgent** (`Ctrl+Alt+I`, or `Control+Command+I` on macOS).
3. With the local Docker backend running, no backend URL or token entry is required. For a hosted deployment, set its URL under **Settings > Services**, then sign in from **Settings > Account**. CodeAgent discovers `node` and common Node.js installation paths automatically; Advanced Settings can override the Node.js 22.5+ executable.
4. Select **Index project**, then use **Agent** for approved code changes, **Chat** for code-aware collaboration, or **Ask** for read-only analysis.

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
cd backend && npm test && cd ..
./gradlew buildPlugin
```

## Run in a sandbox IDE

```bash
./gradlew runIde
```

The installable ZIP is written to `build/distributions/`.

ContextEngine is pinned as a Git submodule and bundled into the local Node sidecar. Its MIT license is included in the plugin distribution; see [third-party notices](THIRD_PARTY_NOTICES.md).

## Verify

```bash
cd frontend && npm run check && cd ..
cd sidecar && npm test && cd ..
cd backend && npm test && cd ..
./gradlew test buildPlugin verifyPlugin
```

The first Gradle verification run downloads the target IntelliJ IDEA distribution and Plugin Verifier, so it is substantially slower than subsequent runs.
