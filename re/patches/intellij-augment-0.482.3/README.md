# IntelliJ Augment 0.482.3 bridge hardening

This directory contains the source for the JVM workspace bridge added to the
patched plugin JAR. The companion scripts also apply narrowly anchored changes
to the bundled Node sidecar.

## Inputs

- Upstream plugin ZIP: `intellij-augment-0.482.3-stable.zip`
- Required upstream ZIP SHA-256:
  `303969f7df18b354768b9d17fd72982808f9f11e883e33d9c1f4f37b3bc4a5c2`
- Tracked JAR before this hardening stage SHA-256:
  `53953db220b53dbed419f52e177795d57bc679c01a560dbc89f5ce6f63b9ae34`

The upstream ZIP is intentionally gitignored. It supplies the generated
settings protobuf dependency used to compile the bridge.

## Build and verify

```bash
cd backend-go
./scripts/rebuild-patched-jar.sh
./scripts/verify-patched-jar.sh
```

The build compiles with `javac --release 21`, checks every sidecar patch anchor,
uses fixed ZIP timestamps, and verifies the resulting archive. Repeated builds
of the checked-in hardened JAR produce:

```text
c089b74683a7f55cc5b513847c4aed4673481542cd0f304f31a24b4cb9b7f7db
```

Relative to the previously tracked JAR, only these entries change:

- `com/augmentcode/intellij/settings/AugmentWorkspaceBridge.class`
- `sidecar/index.cjs`
- `META-INF/plugin.xml`
- `META-INF/MANIFEST.MF`

## Hardened behavior

- The bridge is Java 21 bytecode (`major version 65`), matching the original
  plugin instead of requiring Java 25.
- Bridge requests accept `-Daugmentcode.tenant.url=...`; the sidecar accepts
  `AUGMENT_TENANT_URL`. Both retain the loopback URL as the local default.
- Sidecar initialization, MCP configuration, Redux, and webview logs no longer
  persist payload values.
- ContextEngine polling uses one timer path, backs off to 10 seconds on errors,
  and drops to 30 seconds after indexing completes.
- Plugin version `0.482.3.999-local` stays ahead of the matching Marketplace
  stable build, preventing IntelliJ from silently replacing the local patch.
- Home reads the file count from ContextEngine and the existing thread count
  from the sidecar history database; the JVM bridge has a backend fallback.
- The local conversation workspace owns ContextEngine routing, so the sidecar
  no longer emits a misleading default-retrieval warning for `workspace_folder`.

This is not yet a complete reconstruction of all historical binary patches.
`SettingsService.class` and the two webview bundles still need source/transform
recipes before the entire plugin can be rebuilt from the upstream ZIP alone.
