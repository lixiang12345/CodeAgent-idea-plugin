# Provider and conversation data flow

## Unified native model gateway

The backend accepts exactly two model-gateway settings: `MODEL_BASE_URL` and `MODEL_API_KEY`. It discovers the model registry from `GET MODEL_BASE_URL/v1/models`; every returned model must include an explicit native protocol (`openai-responses`, `anthropic-messages`, or `xai-responses`). The same origin and bearer key cover discovery and all three protocols.

| Protocol metadata | Backend adapter | Upstream endpoint |
| --- | --- | --- |
| `openai-responses` | OpenAI Responses | `POST /v1/responses` |
| `anthropic-messages` | Anthropic Messages | `POST /v1/messages` |
| `xai-responses` | xAI Responses | `POST /v1/responses` |

The adapters translate the shared agent turn into each provider's native request schema and normalize text deltas, tool calls, argument deltas, and tool results back into one internal representation. Routing is determined only by discovery metadata and is never guessed from a model name. Remote gateways must use HTTPS.

Official protocol references: [OpenAI Responses](https://developers.openai.com/api/reference/resources/responses/methods/create), [Anthropic streaming Messages](https://docs.anthropic.com/en/api/messages-streaming), and [xAI inference APIs](https://docs.x.ai/developers/rest-api-reference/inference/chat).

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
3. After the first index, the sidecar watches the project tree and runs hash-based incremental indexing after an 800 ms debounce. Added, changed, and deleted files are synchronized without rebuilding unchanged files.
4. The JVM advertises `codebase_retrieval` as an Agent/Chat/Ask tool.
5. When the backend model requests that tool, the JVM calls the local ContextEngine, packs the retrieved project context, and returns it as untrusted tool output.

This keeps the repository index and filesystem authority on the developer machine while allowing the backend agent to decide when retrieval is useful. The current integration is just-in-time tool retrieval, not automatic retrieval before every chat request.

## Deployment and trust

Set only the unified gateway Base URL and Key in the deployed backend environment. Protect hosted product APIs with OIDC sessions, use TLS outside loopback development, and treat the model gateway as an external processor that can observe prompts, selected repository content, and tool results.
