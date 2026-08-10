# Augment 本地接管兼容性报告

结论先说：当前版本可以接管 Augment IntelliJ 插件的本地核心工作流，但不能完全替代插件、Node Sidecar 或 Augment 云端的全部能力。可工作的边界是：

```text
JetBrains JVM 插件 <-> Node Sidecar <-> Go tenant/OIDC 后端 <-> ContextEngine
```

Go 后端替代云端身份、模型、聊天、工具调度和检索代理；Sidecar 继续提供 IDE 文件、编辑器、终端、Rules、Skills、Hooks、MCP、workspace 和本地工具。移除 Sidecar 会失去 JVM 的文件和编辑器接口，Go 进程也无法直接访问 IntelliJ 的项目模型。

## 支持矩阵

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| 本地 OIDC、S256 PKCE、JWT、tenant discovery | 支持 | 授权码单次消费、过期、redirect 绑定和并发兑换均有测试。 |
| GetModels、credits、subscription 基础信息 | 支持 | 模型列表来自 `CUSTOM_MODELS` 或环境变量。 |
| Chat / ChatStream | 支持 | OpenAI 兼容网关或 Anthropic 兼容网关；没有可用网关时显式 `STOP_REASON_ERROR`，不会伪装成 `END_TURN`。 |
| 本地 agent 工具 | 支持 | Sidecar 执行 view、search、edit、save、terminal、tasks、diagnostics 等；Go 负责工具结果和远程工具 DTO。 |
| codebase-retrieval | 支持 | 按 conversation 绑定 workspace，ContextEngine 为空或失败时回退本地检索，并返回非空的可解释结果。 |
| Code/Chat 输入补全 | 支持 | 3 秒超时降级；`ChatInputCompletionResponse` 使用 descriptor 要求的 `unknown_memory_names`。 |
| 会话、历史、状态快照 | 基础支持 | 内存态加原子 JSON 快照；会话 wrapper、批量计数和历史导入遵循当前 descriptor。 |
| Home 概览和索引进度 | 依赖补丁 JAR | Sidecar bridge 调 Go 的 overview/index-status；不是 Go 单独提供的 IntelliJ UI。 |
| Cloud Agents、handoff、协作 workspace | 不支持 | 相关入口关闭，未实现 RPC 显式返回 501。 |
| GitHub/Slack/Linear、云端 secrets、远端 MCP/Figma | 不支持 | 不伪造第三方连接或密钥存储。 |
| BYOK、Prompt Enhancer、Smart Paste、Context Canvas、Conversation Retrieval | 不支持 | feature flags 已关闭。 |
| File intake、CheckpointBlobs、BatchUpload、CodebaseRetrievalRaw | 不支持 | file-intake/raw-retrieval flags 已关闭，相关 RPC 不再返回错误形状的成功响应。 |

## RPC 覆盖

`routes_gen.go` 当前描述 `public_api.Augment` 的 214 个 RPC。实际 dispatch 状态如下：

- 90 个 unary RPC 名称注册到 `surface.Implemented`；其中 57 个只是有意的空 ACK，33 个返回模型、会话、检索、工具或事件数据。
- `ChatStream` 是唯一走真正 server-streaming 路径的 RPC，因此总共 91 个 dispatch entry 有处理逻辑。
- 其余 123 个 RPC 没有 handler，REST 和 Connect JSON 会明确返回 501，而不是 404 或空成功。
- descriptor 中有 9 个 server-streaming RPC；除 `ChatStream` 外的 8 个没有实现 streaming envelope。gRPC/proto 仅实现 Health 和 unary Chat，不能宣称完整 protobuf 兼容。
- Connect JSON 的聊天流是插件当前可消费的 NDJSON 兼容路径；通用 Connect streaming envelope、完整 grpc-web 和所有 RPC 的 protobuf marshaler 仍未提供。

这些数字是“协议表覆盖”而不是“功能等价率”。空 ACK 不代表后端拥有原服务的业务语义。

## 会话自动停止的证据

历史日志里的 ContextEngine 调用并不是空返回：IDE/Sidecar 日志显示检索结果包含约 14 个候选 chunks，输出长度约 16–42 KB。旧部署同时出现：

1. 工具结果被放进后续 exchange，旧解析路径没有按 `tool_use_id` 关联，模型看到的是 `[tool executed by IDE]` 占位符。
2. `RemoteToolHost` 重复报 `remote_tool_id must not be null`，因为本地工具被错误注册到云端 remote-tool catalog。
3. `ChatInputCompletion` 原来返回 501，Sidecar 请求等待后超时。
4. xhigh 模型返回空 choices，低推理重试又收到网关 overload/error envelope。

旧代码将“无文本、无工具调用”变成 `Done.` 和 `END_TURN`，所以 UI 看起来像会话自动停止。当前代码把它变成可见错误文本和 `STOP_REASON_ERROR`；工具空结果本身会继续进入模型，并被明确标记为“没有找到上下文”，不会静默结束会话。

## Sidecar/JAR 剩余风险

补丁 JAR 的 workspace bridge 现在有版本化 Java 源码、Java 21 编译、锚点计数 sidecar patch 和自动验证流程；连续构建 SHA 一致。初始化 token、MCP env、Redux payload 和 webview message 不再原文落盘，index-status 不再双 timer 调度，完成后降频为 30 秒。

仍存在的 bridge 限制：

- Java bridge 可用 `-Daugmentcode.tenant.url`、sidecar 可用 `AUGMENT_TENANT_URL` 覆盖地址，但还没有直接复用 OIDC 会话里的动态 `tenantUrl` 和 Bearer token；默认仍是本机 `127.0.0.1:8787`。
- JVM status 请求仍是最多 3 秒的同步 `HttpClient.send`，异常会降级为空或 running；语言统计只递归有限深度。
- `totalThreads` 已从伪造的 1 改为未知值 0，但真实 Sidecar LevelDB thread count 尚未接入 bridge。
- `SettingsService.class` 和两个 webview bundle 的历史补丁仍没有源码或 transformer；当前脚本可重建本轮 bridge/sidecar 加固，但还不能从原始 ZIP 重建全部 6 个变更 entry。
- Sidecar 及第三方库仍有其他 debug 日志；共享日志前仍应做二次脱敏。

因此当前交付应视为“本机、指定插件版本”的可用接管层，而不是已认证的远程多租户插件后端。

## 验收顺序

从仓库根目录执行：

```bash
cd backend-go
gofmt -l .
go test ./... -count=1
go vet ./...
go test -race ./... -count=1
go build ./...
bash -n scripts/*.sh
./scripts/verify-patched-jar.sh
docker compose up -d --build --force-recreate augment-local
docker compose ps
./scripts/e2e-oauth-flow.sh                 # 要求真实模型成功
REQUIRE_MODEL_SUCCESS=1 ./scripts/e2e-probe.sh
```

重建后确认 8445/8787 只绑定 `127.0.0.1`，ContextEngine health/index-status 正常，并在新时间窗口的日志中确认不再出现 `remote_tool_id must not be null`、ChatInputCompletion timeout、静默 `END_TURN` 或正文泄漏。旧容器日志不能作为新代码的在线验收证据。

当前 Go 仓库整体覆盖率约 40.8%；变更包有回归测试和 race 测试，但项目尚未达到 80% 全局覆盖率。真实 IntelliJ/JAR 行为、项目切换、Sidecar 重启、Connect protobuf envelope 和 grpc-web 仍需要手工 IDE 验收。
