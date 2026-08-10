# augment-local — 接管 Augment 插件的自托管后端

原版 Augment IntelliJ 插件直连本后端的 Docker 部署，接管其全部云端接口。
不依赖 Augment 官方云服务：登录、discovery、REST、connect/gRPC、chat 全部落在本机。

```
JetBrains IDE (原版插件 0.482.3)
  │  -Daugmentcode.oauth.url=http://127.0.0.1:8445   ← 唯一改址点（JVM 系统属性覆盖）
  ▼
┌─────────────────────────┐        ┌──────────────────────────────────────┐
│ :8445  OIDC IdP          │ token  │ :8787  tenant surface                 │
│  discovery / jwks /      │───────▶│  /api-client/*  REST (grpc-gateway)   │
│  authorize / token       │ JWT    │  /augment.public_api.Augment/* connect│
│  (tenantUrl claim)       │        │  + gRPC(h2c) + grpc-web               │
└─────────────────────────┘        │  client-discovery (22 services)        │
                                   │  chat-stream SSE 模拟器                 │
                                   └──────────────────────────────────────┘
```

## 原理（逆向结论，仓库 `re/` 可复现）

- 插件的唯一云端地址覆盖点是 `AugmentOAuthService.getServiceUrlWithPropOverride()`
  → `System.getProperty("augmentcode.oauth.url")`。把登录流指向本地 IdP 即可拿到
  带 `tenantUrl` claim 的 JWT；插件随后把**所有**云端调用打到该 tenantUrl。
- 云端面 = 单一 connect/gRPC 服务 `public_api.Augment`（214 个 RPC），经
  grpc-gateway 注解暴露 REST `/api-client/*`（`re/descriptors/services_api_proxy_public_api.proto.txt`）。
  本后端同端口同时接受 REST(HTTP/1.1)、connect+json、connect+proto、gRPC(h2c)、grpc-web。
- `client_discovery.proto` 的 22 个 `ClientServiceType` 全部指向本地 :8787。

## 快速开始

```bash
# 1. 起后端（Docker）
cd backend-go
docker compose up -d --build

# 2. 健康检查
curl -s http://127.0.0.1:8787/healthz            # {"status":"SERVING"}

# 3. 把 IDE 指向本地 IdP（重启 IDE 生效）
./scripts/connect-ide.sh
#   等价手动：Help ▸ Edit Custom VM Options 追加
#   -Daugmentcode.oauth.url=http://127.0.0.1:8445

# 4. 插件里 Sign in：浏览器弹本地登录页（任意口令）→ 回调 → 接管完成
```

不装 Docker 也能跑：`go run ./cmd/server`（需要 Go 1.26+，`golang.org/x/net` 已入 go.mod）。

## 验证

```bash
./scripts/e2e-probe.sh                # 全量驱动探针：OIDC→token→REST→chat→discovery→connect→gRPC
go test ./...                          # 单元测试（oidc + tenant 协议面）
```

## 已实现 vs 显式 stub

| 面 | 状态 |
|---|---|
| OIDC：discovery / jwks / authorize（本地登录页，S256 PKCE）/ token（RS256 JWT） | ✅ |
| `client-discovery` 22 服务表 → :8787 | ✅ |
| `/api-client/get-models`、`get-credit-info`、`subscription-info`、`subscription-banner` | ✅ |
| `/api-client/chat-stream` ChatStream 模拟流（THINKING→TOOL_USE→text→END_TURN），NDJSON/SSE/connect+proto 三格式 | ✅ |
| `/api-client/chat`（unary）、`prompt-enhancer` | ✅ |
| 会话/历史：`chat/conversation/*`、`chat/exchanges/list`、`count`、`save-chat`（内存态） | ✅ |
| 工具面：`agents/list-remote-tools`(空)、`agents/check-tool-safety`(safe)、`agents/codebase-retrieval`(空) | ✅ |
| 录账：`record-request-events`、`record-session-events`、`report-client-metrics` | ✅ |
| gRPC：`grpc.health.v1.Health` SERVING；`public_api.Augment/Chat`（hand-encode protobuf） | ✅ |
| 其余全部 RPC | `501 / Unimplemented`（显式、带方法名） |

`MODEL_GATEWAY_URL` 指向 OpenAI 兼容 `/chat/completions` 时，chat-stream 的最终
文本由真实模型生成（其余节点骨架保持模拟）。

## 接线细节与已知限制

- **`_isAugmentcodeDomain`**：插件对非 augmentcode 域做 token 展开时会打日志并原样
  发送 token——对我们的本地后端无影响（本地接受任意 token），属已知行为。
- **JWT 验签**：`iss=http://127.0.0.1:8445`、`kid=augment-local-1`，JWKS 同源返回，
  密钥默认每次启动临时生成；要跨重启稳定可设 `JWT_PRIVATE_KEY`（PKCS#8 PEM）。
- **JetBrains OAuth 端点严格性**：若登录流对端点名/参数有额外要求，以插件
  `Help ▸ Debug Log Settings` 抓 `com.augmentcode.intellij.auth` 日志为准，本后端
  对未知路径统一回 `404` 并记日志，便于补表。
- **gRPC-proto 非 Chat 方法**：刻意不生成全量 protobuf marshaler（JSON REST 为主面），
  gRPC-proto 只实现了 Health 与 Chat；其余返回 `Unimplemented`。
