# Declarative plugins

CodeAgent plugins extend product configuration without extending the IDE process trust boundary. A plugin is one bounded JSON manifest fetched from an operator-controlled URL. It cannot contain or load JVM classes, Node.js modules, shell scripts, native libraries, or Webview code.

## Lifecycle

Plugin account configuration and local installation are deliberately separate:

1. An account configuration synchronizes the plugin ID, manifest source, optional exact version, optional SHA-256 integrity pin, enabled state, and capability grants.
2. The user validates or installs the plugin from Settings > Plugins on each device.
3. The IDEA-owned runtime downloads at most 1 MiB, validates the exact response bytes, and atomically caches the manifest under the JetBrains system directory.
4. Reconciliation restores valid cached manifests when the project opens or account configuration refreshes.
5. Check for updates validates the remote manifest without replacing the installed copy. Update performs the explicit replacement.
6. Uninstall removes the device-local cache while retaining account configuration. Deleting account configuration also removes the cached manifest.

An installed plugin can remain present but disabled. Disabled plugins contribute nothing until the synchronized configuration is enabled again.

## Account configuration

```json
{
  "name": "Review pack",
  "description": "Shared review workflows",
  "enabled": true,
  "source": "https://plugins.example.com/review-pack.json",
  "version": "1.0.0",
  "integrity": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "capabilities": ["commands", "prompts", "rules", "skills", "agents", "hooks", "mcp", "tools"]
}
```

- `source` must be HTTPS. Loopback HTTP is allowed for local development.
- Embedded URL credentials and fragments are rejected.
- `version`, when present, is an exact manifest-version requirement.
- `integrity`, when present, is a lowercase SHA-256 digest over the exact downloaded bytes.
- `capabilities` are permissions granted by the account owner. Every grant must also be declared by the manifest.

## Manifest schema

```json
{
  "schemaVersion": 1,
  "id": "review-pack",
  "name": "Review pack",
  "version": "1.0.0",
  "description": "Shared review workflows",
  "publisher": "Example Engineering",
  "homepage": "https://plugins.example.com/review-pack",
  "capabilities": ["commands", "prompts", "rules", "skills", "agents", "hooks", "mcp", "tools"],
  "commands": [
    {
      "id": "review",
      "name": "Review scope",
      "description": "Review the requested files and report prioritized findings",
      "prompt": "Review {{arguments}} in {{project}}.",
      "argumentHint": "[scope]",
      "mode": "ask",
      "agentProfileId": "loop"
    }
  ],
  "prompts": [
    {
      "id": "security-review",
      "name": "Security review",
      "description": "Review a scope for security regressions",
      "prompt": "Review {{arguments}} in {{project}} for security regressions.",
      "argumentHint": "[scope]"
    }
  ],
  "rules": [
    {
      "id": "secure-defaults",
      "name": "Secure defaults",
      "description": "Apply secure defaults when explicitly selected",
      "content": "Prefer deny-by-default authorization and redact secrets.",
      "trigger": "manual"
    }
  ],
  "skills": [
    {
      "id": "threat-model",
      "name": "Threat model",
      "description": "Build a compact threat model before implementation",
      "content": "# Threat model\n\nIdentify assets, trust boundaries, entry points, and mitigations."
    }
  ],
  "agents": [
    {
      "id": "reviewer",
      "name": "Review Agent",
      "description": "Evidence-first repository review",
      "agentType": "loop",
      "systemPrompt": "Lead with concrete findings and verify every mutation.",
      "allowedTools": ["read-review-scope", "search_text", "diagnostics"],
      "maxTurns": 16,
      "maxToolCalls": 64,
      "maxSubagentCalls": 3,
      "verificationPolicy": "after-mutation",
      "contextWindowTokens": 256000,
      "reservedOutputTokens": 8192
    }
  ],
  "hooks": [
    {
      "id": "verify",
      "name": "Verify after run",
      "event": "after-run",
      "command": "./gradlew test",
      "timeoutSeconds": 600,
      "runPolicy": "manual",
      "failurePolicy": "continue",
      "requiredEnvironment": ["JAVA_HOME"]
    }
  ],
  "mcp": [
    {
      "id": "project-tools",
      "name": "Project tools",
      "transport": "stdio",
      "command": "node",
      "args": ["tools/mcp-server.mjs"],
      "timeoutSeconds": 60
    }
  ],
  "tools": [
    {
      "id": "read-review-scope",
      "name": "Read review scope",
      "description": "Read the default review target",
      "target": "read_file",
      "defaults": { "path": "README.md" }
    }
  ]
}
```

Unknown fields are rejected so that unsupported behavior cannot be mistaken for active functionality. IDs use bounded ASCII letters, numbers, dots, underscores, or hyphens. Command and prompt IDs must also be unique across both arrays because they share the `/<plugin-id>.<contribution-id>` namespace. Plugin rules and skills receive stable namespaced IDs and read-only `plugin://` paths.

Command templates use the existing command placeholders:

- `{{arguments}}`: text following the slash command.
- `{{project}}`: current project name.

`mode` is one of `inherit`, `agent`, `chat`, or `ask`. `agentProfileId` can reference a built-in profile or an Agent declared by the same plugin. Local contribution references such as `reviewer` and `read-review-scope` are converted to stable `plugin.<plugin-id>.<contribution-id>` runtime IDs.

## Capabilities

Schema version 1 recognizes:

- `commands`: contributes namespaced slash commands after explicit grant.
- `prompts`: contributes namespaced prompt templates through the bounded slash-template runtime.
- `rules`: contributes read-only `always`, `agent`, or per-thread `manual` rules.
- `skills`: contributes read-only skills that users select per conversation.
- `agents`: contributes request-scoped Agent profiles. Profiles are selectable per thread and are revalidated by the backend on every run; they are never written into account configuration.
- `hooks`: contributes lifecycle hooks to the existing supervised Hook runtime. Manual hooks can be tested from the plugin details; automatic hooks follow their declared event and failure policy.
- `mcp`: contributes stdio, Streamable HTTP, or SSE MCP servers to the existing sidecar runtime. OAuth tokens remain device-local in Password Safe.
- `tools`: contributes namespaced aliases and default argument templates for tools already available in the current run. It cannot install a new handler or change the target tool's risk classification.

## Security properties

- Installation is explicit per device.
- Account configuration alone cannot activate downloaded content.
- The manifest size, schema, identity, version, integrity, capabilities, contribution counts, IDs, text lengths, URLs, environment names, runtime budgets, rule triggers, command modes, Hook policies, MCP transports/authentication, Agent profiles, and tool aliases are validated.
- Remote sources use HTTPS; redirects are subject to the same final-URL validation.
- Cached writes are atomic where the filesystem supports them.
- Plugin commands and prompt assets pass through the existing command-template, Agent-policy, tool-approval, and prompt-priority layers.
- Plugin rules and skills remain lower-priority workspace context and cannot override server-owned safety policy or the current user request.
- Plugin Agent profiles are sent only with the selected run and are strictly reconstructed by the backend before policy is applied.
- Plugin Tool aliases inherit the target definition, schema, mode restrictions, and approval risk. Call arguments override declared defaults.
- Hook commands and stdio MCP processes run only after the capability is both granted in account configuration and installed on the current device. They remain subject to the existing environment allowlist, timeout, process supervision, and audit surfaces.
- A plugin cannot register executable tool handlers, load code into the IDE process, embed credentials, or bypass IDEA approvals.

This design keeps plugins useful for portable workflows while leaving code execution to CodeAgent's reviewed IDE, backend, sidecar, and MCP capability boundaries.
