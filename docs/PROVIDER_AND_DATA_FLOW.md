# Provider and conversation data flow

## Enabled model routes

The backend exposes a fixed allowlist through `GET /v1/models`; it does not expose every model returned by the upstream gateway. Credentials are backend environment variables and are never returned to the plugin.

| Models | Backend adapter | Upstream endpoint |
| --- | --- | --- |
| `gpt-5.6-sol` | OpenAI Responses | `POST /v1/responses` |
| `claude-fable-5`, `claude-opus-4-8`, `claude-sonnet-5` | Anthropic Messages | `POST /v1/messages` |
| `grok-4.5` | xAI Responses | `POST /v1/responses` |

The adapters translate the shared agent turn into each provider's request schema and normalize text deltas, tool calls, argument deltas, and tool results back into one internal representation. Protocol selection is configured by the route and never guessed from a model name. A native Gemini `streamGenerateContent` adapter remains covered by tests, but no Gemini model is enabled in the current allowlist.

Official protocol references: [OpenAI Responses](https://developers.openai.com/api/reference/resources/responses/methods/create), [Anthropic streaming Messages](https://docs.anthropic.com/en/api/messages-streaming), [xAI inference APIs](https://docs.x.ai/developers/rest-api-reference/inference/chat), and [Gemini function calling](https://ai.google.dev/gemini-api/docs/function-calling).

## CodeAgent request path

1. The Svelte Webview sends `sendMessage` with user text and mode through `JBCefJSQuery`. Model selection is a separate `selectModel` command.
2. The JVM owns the canonical conversation. It persists the selected model per thread, appends the user message, resolves selected file paths, Rules, Skills, and root guidance, and advertises only tools allowed for the active mode.
3. The JVM sends `RemoteRunRequest` to the separately deployed backend with `model`, conversation history, tool schemas, and bounded workspace customization.
4. The backend composes its server-owned system prompt and runs the bounded model/tool loop through the configured provider adapter.
5. Provider events become CodeAgent SSE events: `message.delta`, `assistant.completed`, `tool.request`, `tool.completed`, and `run.completed`.
6. A tool request returns to the JVM. The JVM validates the allowlist and path, asks for approval when required, executes locally, and posts a structured tool result to the run. The backend then continues the provider conversation.

The Webview cannot send a system prompt, provider credential, arbitrary tool definition, or tool result.

## Evidence from the analyzed Augment plugin

The analyzed Augment Webview uses Redux and sagas rather than calling model providers directly. Its send-exchange path builds rich request nodes for text, images, files, IDE state, tool results, slash commands, and Ask-specific content. It combines visible history, selected context, repository rules and skills, model/canvas identifiers, external sources, and subagent metadata before handing the stream request to the extension/sidecar boundary.

Provider formatting and credentials are not owned by that Webview. The local sidecar contains agent/tool orchestration prompt extensions and sends a service-level streaming request; the remote product service can also select or compose default prompt policy. This supports the CodeAgent decision to keep UI prompts limited to user-facing starters while placing the authoritative agent harness in the backend.

Current CodeAgent tool calls and results are structured. User-selected attachments, however, are currently rendered into a bounded text block of project-relative paths inside the user message. That is simpler than Augment's typed content-node model. Native image/file payloads and richer IDE-state nodes remain a compatibility gap; the current implementation does not claim parity for those data types.

## ContextEngine boundary

ContextEngine is connected locally, not installed in the deployed backend:

1. The IDEA plugin starts the bundled Node sidecar over a JSON Lines process protocol.
2. The sidecar imports the pinned `vendor/context-engine` submodule and owns its local SQLite index.
3. The JVM advertises `codebase_retrieval` as an Agent/Chat/Ask tool.
4. When the backend model requests that tool, the JVM calls the local ContextEngine, packs the retrieved project context, and returns it as untrusted tool output.

This keeps the repository index and filesystem authority on the developer machine while allowing the backend agent to decide when retrieval is useful. The current integration is just-in-time tool retrieval, not automatic retrieval before every chat request.

## Deployment and trust

Set provider credentials only in the deployed backend environment. Protect `/v1/models`, `/v1/runs`, and tool-result endpoints with `CODEAGENT_AUTH_TOKEN`, use TLS in non-local deployments, and treat any third-party model gateway as an external processor that can observe prompts, selected repository content, and tool results.
