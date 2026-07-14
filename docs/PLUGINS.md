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
  "capabilities": ["commands"]
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
  "capabilities": ["commands"],
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
  ]
}
```

Unknown fields are rejected so that unsupported behavior cannot be mistaken for active functionality. IDs use bounded ASCII letters, numbers, dots, underscores, or hyphens. A command contribution is exposed as `/<plugin-id>.<command-id>`.

Command templates use the existing command placeholders:

- `{{arguments}}`: text following the slash command.
- `{{project}}`: current project name.

`mode` is one of `inherit`, `agent`, `chat`, or `ask`. `agentProfileId` can reference only a built-in profile: `general`, `search`, `context`, `prompt`, or `loop`.

## Capabilities

Schema version 1 recognizes:

- `commands`: active; contributes namespaced slash commands after explicit grant.
- `agents`, `hooks`, `mcp`, `rules`, `skills`, `tools`, `prompts`: reserved permission names.

Reserved capabilities are visible in configuration and runtime metadata, but CodeAgent does not consume their manifest content yet. Adding an implementation requires a typed schema, a dedicated validator, an explicit activation path, lifecycle tests, and the same user-visible permission boundary.

## Security properties

- Installation is explicit per device.
- Account configuration alone cannot activate downloaded content.
- The manifest size, schema, identity, version, integrity, capabilities, command IDs, prompt length, mode, and Agent profile are validated.
- Remote sources use HTTPS; redirects are subject to the same final-URL validation.
- Cached writes are atomic where the filesystem supports them.
- Plugin commands pass through the existing command-template, Agent-policy, tool-approval, and prompt-priority layers.
- A plugin cannot register executable tool handlers or bypass IDEA approvals.

This design keeps plugins useful for portable workflows while leaving code execution to CodeAgent's reviewed IDE, backend, sidecar, and MCP capability boundaries.
