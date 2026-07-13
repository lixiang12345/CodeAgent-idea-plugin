# CodeAgent for JetBrains

CodeAgent is a local-first AI coding agent for IntelliJ IDEA and other JetBrains IDEs. It combines an approval-driven agent loop with the open [ContextEngine](https://github.com/lixiang12345/ContextEngine-plugin) retrieval component.

The repository is under active staged development. See [architecture and original-plugin interaction analysis](docs/ARCHITECTURE.md).

## Development prerequisites

- JDK 21 (Gradle can provision it through Foojay)
- Node.js 22.5 or newer
- npm 10 or newer

## Build

```bash
./gradlew buildPlugin
```

## Run in a sandbox IDE

```bash
./gradlew runIde
```

The installable ZIP is written to `build/distributions/`.
