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
a7ef4fd7f78ea665e51cbed40ca220e95146e1bccbd18454424652d051795d1c
```

Relative to the previously tracked JAR, only these entries change:

- `com/augmentcode/intellij/settings/AugmentWorkspaceBridge.class`
- `sidecar/index.cjs`

## Hardened behavior

- The bridge is Java 21 bytecode (`major version 65`), matching the original
  plugin instead of requiring Java 25.
- Bridge requests accept `-Daugmentcode.tenant.url=...`; the sidecar accepts
  `AUGMENT_TENANT_URL`. Both retain the loopback URL as the local default.
- Sidecar initialization, MCP configuration, Redux, and webview logs no longer
  persist payload values.
- ContextEngine polling uses one timer path, backs off to 10 seconds on errors,
  and drops to 30 seconds after indexing completes.
- The Home thread count is reported as unknown (`0`) instead of a fabricated
  constant (`1`).

This is not yet a complete reconstruction of all historical binary patches.
`SettingsService.class` and the two webview bundles still need source/transform
recipes before the entire plugin can be rebuilt from the upstream ZIP alone.
