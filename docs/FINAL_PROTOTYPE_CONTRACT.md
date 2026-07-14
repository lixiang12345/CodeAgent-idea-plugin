# CodeAgent final frontend contract

Acceptance baseline for the last prototype and the plugin Webview.

Source of structure: `prototypes/augment-v9-tools-native.html`  
Product name and trust model: CodeAgent (not Augment cloud platform)

## Goals

1. **Resources**: ship the v9 icon registry, design tokens, tool catalog metadata, and status assets used by the UI.
2. **Pages**: every v9 surface exists in the final prototype and in the plugin shell (including unavailable shells).
3. **Icons**: v9 names; product logo is robot-only (`plugin-icon` without white disc).
4. **Interactions**: menus, drawers, overlays, composer controls, settings navigation are clickable end-to-end in the prototype; plugin wires the same chrome to real bridge commands or explicit unavailable states.
5. **Interfaces**: document bridge commands and backend HTTP/SSE once the UI contract is frozen.

## Page map

| ID | Surface | Prototype | Plugin target |
| --- | --- | --- | --- |
| `chat` | Main agent panel | required | required |
| `threads` | Threads drawer | required | required |
| `tasks` | Active Tasklist overlay | required | required |
| `git` | Git Changes overlay | required | required |
| `edits` | Agent Edits overlay | required | required |
| `canvas` | Context Canvas / images | required | required |
| `tools` | Insert Tool Call catalog | required | required (connected tools only executable) |
| `icons` | Icon registry viewer | required | required (dev/demo) |
| `feedback` | Report issue | required | shell + export logs if available |
| `settings/*` | Full settings nav | required | all sections; unconnected = explicit |
| `mermaid` | Diagram canvas | required | required |
| `loading` | Loading | required | required |
| `index` | Index codebase states | required | map to ContextEngine states |

Intentionally **not** productized as success paths: Augment sign-in cloud, Extension Retired / Cosmos, org repository blocklist OAuth.

## Composer interactions

- Mode menu: Agent / Chat / Ask
- Model picker
- Context chips + add context
- `@` mention menu (files / rules / guidelines)
- `/` slash menu (seed commands)
- Attach file/image
- Skills popover
- Context Canvas shortcut
- Prompt enhancer control (visible; disabled until backend capability exists)
- Auto-run toggle
- Send / Queue / Stop
- Message queue panel
- Agent edits summary bar → full edits overlay

## Tool card interactions

- Expand / collapse / expand-all
- Status phases: running, approve, done, failed, rejected
- Details, path, View Diff, Undo, Open Terminal, Open Canvas
- Approval: Approve / Skip

## Settings sections

Home, Services, MCP Servers, Rules & Guidelines, API Keys, Commands, Skills, Hooks, Agents, Plugins, User Experience, Feature Flags, Beta, Account, Subscription, plus the settings-header System Audit action.

Connected in plugin today: Home (partial), Services, API Keys (gateway token), Rules, Skills, Context index actions.  
Others: visible shell + **Not connected** (no fake success).

## Resource packing

| Resource | Location |
| --- | --- |
| Icons | `frontend/src/lib/icons.ts` from `prototypes/assets/icons-registry.js` |
| Icon component | `frontend/src/lib/Icon.svelte` |
| Tokens | `frontend/src/styles.css` DS variables |
| Tool metadata | `frontend/src/lib/tools-catalog.ts` from `prototypes/assets/tools.json` |
| Final HTML prototype | `prototypes/codeagent-final.html` |

## No-fake rule

Buttons may open shells. They must not claim MCP connect, OAuth, cloud sync, subagent success, or enhancer rewrite unless a real implementation ran.
