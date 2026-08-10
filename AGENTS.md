# Repository Guidelines

## Project Structure & Module Organization

This checkout is centered on the Go service in `backend-go/`. The executable entry point is `cmd/server`; implementation packages live under `internal/` (`oidc`, `tenant`, `surface`, `chat`, `tools`, `state`, and `discovery`). Go tests are colocated as `*_test.go`. Operational helpers are in `backend-go/scripts/`: `connect-ide.sh` configures JetBrains, `e2e-probe.sh` exercises the service, and `gen-routes.py` regenerates routes from descriptors. `re/` contains reverse-engineering notes and protocol descriptors; `releases/` contains patched plugin binaries. Compose expects a separate `ContextEngine-plugin` checkout at `../../ContextEngine-plugin`.

## Build, Test, and Development Commands

Run commands from `backend-go/` unless noted:

- `go test ./...` — run all unit tests.
- `go test ./internal/oidc -run TestPKCE` — run one focused test.
- `go build ./...` — compile every Go package.
- `go run ./cmd/server` — start the local OIDC and tenant servers (Go 1.26+).
- `cp .env.example .env && docker compose up -d --build` — start the full backend, ContextEngine, and PostgreSQL stack after setting local secrets and mount paths.
- `./scripts/e2e-probe.sh` — probe the live OAuth, tenant, discovery, and chat surfaces.

## Coding Style & Naming Conventions

Format Go changes with `gofmt` (tabs and standard imports) and keep packages lower-case. Use idiomatic names: exported `PascalCase`, locals `camelCase`, and contextual errors. Name tests `Test<Behavior>`. Shell scripts should quote paths and use strict error handling. Do not hand-edit `internal/surface/routes_gen.go`; regenerate it with `scripts/gen-routes.py` when descriptors change.

## Testing Guidelines

Tests use Go's standard `testing` package with `httptest`; add regression coverage beside changed code. Run `go test ./...` before a PR. For endpoint or Docker changes, also run `./scripts/e2e-probe.sh` against the local stack and report failures or required services. No coverage threshold is configured.

## Security & Configuration

Copy `backend-go/.env.example` to the gitignored `backend-go/.env`. Keep model/API keys, JWT material, and host paths out of source, logs, commits, and release artifacts. Review Compose volume mounts and exposed ports before sharing a setup.

## Commit & Pull Request Guidelines

Use a short, imperative, title-case commit subject without a mandatory prefix (for example, `Fix token refresh handling`). PRs should explain the behavior change, link issues, list validation commands and results, and attach screenshots or probe output for IDE/UI changes. Keep unrelated binary or generated updates out of the PR. CI runs on pushes and pull requests; report failures caused by its references to modules absent from this checkout as infrastructure issues.
