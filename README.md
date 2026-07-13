# CodeAgent for JetBrains

CodeAgent is a local-first AI coding agent for IntelliJ IDEA and other JetBrains IDEs. It combines an approval-driven agent loop with the open [ContextEngine](https://github.com/lixiang12345/ContextEngine-plugin) retrieval component.

Version `0.3.0` provides a complete local coding loop: persistent tasks, project indexing and retrieval, streamed OpenAI-compatible model calls, IDE-native read/write/search/terminal tools, explicit approval for mutations, cancellation, project-file attachments, and native Diff/revert controls for agent file edits. Agent policy and prompt composition live in the JVM backend, with optional repository guidance loaded from a root `AGENTS.md`. See the [product definition](docs/PRODUCT.md), [prompt architecture](docs/PROMPT_ARCHITECTURE.md), and [architecture/original-plugin interaction analysis](docs/ARCHITECTURE.md).

## Install and use

1. Install `build/distributions/CodeAgent-0.3.0.zip` from **Settings > Plugins > Install Plugin from Disk**.
2. Open **Tools > Show CodeAgent** (`Ctrl+Alt+I`, or `Control+Command+I` on macOS).
3. Open Settings in the CodeAgent panel and configure an OpenAI-compatible endpoint, model, API key, and a Node.js 22.5+ executable.
4. Select **Index project**, then use **Agent** for approved code changes or **Ask** for read-only analysis.

The configured model endpoint must implement Chat Completions function/tool calls and should support SSE streaming. Endpoints that return a normal JSON completion remain supported. The API key is stored in JetBrains Password Safe. ContextEngine runs locally; only model requests are sent to the endpoint you configure.

## Development prerequisites

- JDK 21 (Gradle can provision it through Foojay)
- Node.js 22.5 or newer
- npm 10 or newer

## Build

```bash
git submodule update --init --recursive
cd frontend && npm ci && cd ..
cd sidecar && npm ci && cd ..
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
./gradlew test buildPlugin verifyPlugin
```

The first Gradle verification run downloads the target IntelliJ IDEA distribution and Plugin Verifier, so it is substantially slower than subsequent runs.
