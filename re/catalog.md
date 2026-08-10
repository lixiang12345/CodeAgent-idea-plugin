# 逆向产物目录 — intellij-augment-0.482.3-stable

来源：仓库根 `intellij-augment-0.482.3-stable.zip`（152MB 原版 IntelliJ 插件）。
解包内容在 `re/extracted/`（`.gitignore` 排除，勿提交）；本目录存放可复现的分析工具与固化产物。

## 一、工具

### `tools/dumpproto/`
反射式 protobuf descriptor 提取器（Java）。

```bash
cd tools/dumpproto
javac DumpProto.java
# 参数：<jar 目录> [类前缀过滤] [输出目录]
java -cp .:<PROTO_JARS> DumpProto /path/to/jars  public_api  descriptors/
```

行为：用单个 `URLClassLoader` 加载 jar 下每个 class，凡暴露静态
`Descriptors.FileDescriptor getDescriptor()` 的（生成代码的 proto 外层类），把其
FileDescriptorProto 以 text 格式写入 `descriptors/<文件名>.txt`。`*OuterClass` 后缀类
全量抓取；无后缀但命中前缀过滤的类也抓（`public_api`、`auth` 均属此类）。

产出：`tools/dumpproto/descriptors/*.txt` 共 77 个 FileDescriptor（关键两个见下节）。

### `tools/grpcprobe/`
e2e 探针用的最小 `.proto`（health、public_api 子集），供 `grpcurl` 直连本地后端。

## 二、关键发现（供后端实现引用）

### 1. 云端面 = 单一 connect/gRPC 服务 + grpc-gateway REST
`tools/dumpproto/descriptors/services_api_proxy_public_api_proto.txt` — package `public_api`，
**service `Augment`，214 个 RPC**，每个都带 `[google.api.http]` 注解把 REST 路径映射出来：

| 方法 | 输入 | REST 路径 |
|---|---|---|
| `ChatStream` | ChatRequest | `POST /chat-stream` |
| `Chat` | ChatRequest | `POST /chat` |
| `GetModels` | GetModelsRequest | `POST /get-models` |
| `GetCreditInfo` | GetCreditInfoRequest | `POST /get-credit-info` |
| `ListRemoteTools` | ListRemoteToolsRequest | `POST /agents/list-remote-tools` |
| `CheckToolSafety` | CheckToolSafetyRequest | `POST /agents/check-tool-safety` |
| `SaveChat` | SaveChatRequest | `POST /save-chat` |
| `CreateConversation` | CreateConversationRequest | `POST /chat/conversation/create` |
| … 共 214 项 | | |

sidecar 的 `/api-client/*` 就是这些路径带部署前缀后的形态（`backend-go` 以
`/api-client<path>` 与裸 `<path>` 同时挂载）。

**完整 214 方法→路径映射已代码固化**：`backend-go/internal/surface/routes_gen.go`
（由本描述符自动生成，`google.api.http` 注解解码）。

### 2. 认证锚点（接管的关键）
- `AugmentOAuthService.getServiceUrlWithPropOverride()` →
  `System.getProperty("augmentcode.oauth.url")`，默认 `https://auth.augmentcode.com`。
- 登录凭据 `AugmentCredentials(accessToken, tenantUrl)`：token 响应里的 `tenantUrl`
  决定插件把所有云端调用打到哪里。`backend-go` 的 IdP 签发 JWT 时在
  `access_token`/`id_token`/token 响应三层都带 `tenantUrl`。
- 全插件仅此一处 base-url 覆盖；无环境变量、无 hosts 依赖。

### 3. client_discovery（22 服务类型）
`descriptors/clients_sidecar_libs_protos_client_discovery.proto.txt` — package
`augment.client_discovery`，`enum ClientServiceType`（ECHO..ACP，共 22），消息
`GrpcTransportConfig{base_url,rpc_path,full_rpc_url,port}`、`ClientDiscovery{grpc|direct}`、
`ClientDiscoveryRequest/Response`。proto **无 service 块** → discovery 以 REST/配置表形态服务，
`backend-go` 在 `/api-client/client-discovery` 返回全 22 项指向本机 :8787。

### 4. 顶层 DTO 要点（chat 模拟器对齐）
`ChatRequest`：44 字段。`message`=6、`nodes`=25（`ChatRequestNode`）、`conversation_id`=32、
`turn_id`=38、`parent_conversation_id`=39、`root_conversation_id`=40。

`ChatResponse`：`text`=1、`nodes`=6（repeated `ChatResultNode`）、`stop_reason`=7
（`ChatStopReason`：`END_TURN`=1）。

`ChatResultNode`：`id`=1、`type`=2（`ChatResultNodeType`：`TOOL_USE`=5、`TOOL_USE_START`=7、
`THINKING`=8、`MAIN_TEXT_FINISHED`=2）、`content`=3、`tool_use`=4（`ChatResultToolUse`：
`tool_use_id`=1、`tool_name`=2、`input_json`=3、`is_partial`=4）、`timestamp_ms`=10。

`GetModelsResponse`：`default_model`、`models[]`{name=5,internal_name=6,is_default=7}、
`user_tier`、`feature_flags`（~180 项 FeatureFlags）、`user`{id,email,tenant_id,tenant_name}。

`GetCreditInfoResponse`：`usage_units_remaining`=1、`usage_units_total_current_billing_cycle`=2、
`is_credit_balance_low`=4、`included_usage_units_per_billing_cycle`=7、
`current_billing_cycle_end_date_iso`=8、`credit_details`=9、`usage_units_total`=10。

### 5. 传输指纹（原厂后端 = Go）
sidecar 内容协商 content-type：`application/connect+proto`、`application/connect+json`、
`application/grpc-web+proto`、`application/grpc-web+json`；错误串
`Unimplemented`/`Method not found`/`FailedPrecondition`；`Connect-Protocol-Version` header；
Bazel 大仓（`*_proto-speed.jar`、`java_binary_deploy.jar`、`.aspect_rules_js`）。
→ **connect-go / grpc-go 微服务栈**。`backend-go` 用纯 stdlib + `x/net/http2` 手写协议
多路复用器，同端口覆盖 connect/gRPC/grpc-web/REST 四协议。

## 三、复现路径

1. `unzip intellij-augment-0.482.3-stable.zip -d re/extracted/`（已被 .gitignore 排除）
2. `re/extracted/...` 中找 `*-proto-speed.jar` / `java_binary_deploy.jar`
3. `java -cp ... DumpProto <jar 目录>` → `tools/dumpproto/descriptors/*.txt`
4. `backend-go/internal/surface/routes_gen.go` 由步骤 3 的 public_api 描述符经
   `scripts/gen-routes.py` 再生成
