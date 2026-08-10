# CodeAgent-idea-plugin

自托管 Augment IDE 云端替代层 + ContextEngine 代码检索，让 IntelliJ 里的 Augment 插件在本地运行核心工作流——包括真实模型对话、代码检索（`codebase-retrieval`）、Home 首页 Codebase 概览与索引进度。它不是 214 个 RPC 的完整云端复刻，Node Sidecar 仍是必要组件。

完整支持边界、RPC 统计、会话停止原因和剩余风险见 [`docs/compatibility.md`](docs/compatibility.md)。

## 架构

```
IntelliJ (Augment 插件, 闭源)
   │  0.482.3.999-local 插件（发布文件名保留 beta，见 releases/）
   ▼
sidecar (Node, 插件自带) ──────► augment-local (Go 后端, :8445/:8787)
   │                                    │
   │  generate-project-overview         │ codebase-retrieval 代理
   ▼                                    ▼
contextengine (Node, :8790) ◄────── ContextEngine 检索 (PostgreSQL+pgvector)
   │
   └─ 挂载 ~ → /host，索引当前打开的任意工程
```

| 组件 | 角色 |
|---|---|
| `augment-local` | Go 后端：OIDC、tenant surface、聊天流、工具调度、状态持久化；替代云端核心面 |
| `sidecar` | 插件自带 Node 运行时：IDE 文件/编辑器/终端、Rules、Skills、Hooks、MCP 和本地工具；不可移除 |
| `contextengine` | 代码检索：BM25 + 符号 + pgvector + graph，Augment 兼容 `codebase-retrieval` |
| `postgres` | pgvector 数据库（ContextEngine 存储） |
| 插件 jar（补丁版） | `releases/intellij-augment-0.482.3-beta.jar` |

## 快速开始

### 1. 依赖

- Docker + Docker Compose
- Node 22+（仅本机跑 ContextEngine 时可选）
- 插件源码：`git clone https://github.com/lixiang12345/ContextEngine-plugin ~/ContextEngine-plugin`

### 2. 配置 `.env`

复制 `.env.example` 为 `.env`（gitignored），至少设置：

```bash
# 模型网关（OpenAI 兼容 /v1/chat/completions）
MODEL_GATEWAY_URL=https://gateway.example.com/v1
MODEL_GATEWAY_API_KEY=sk-...                 # 未设置时后端走模拟器
MODEL_GATEWAY_MODEL=gpt-5.6-sol
MODEL_GATEWAY_REASONING_EFFORT=high           # 或 xhigh

# ContextEngine 检索（可选；不设则 codebase-retrieval 回退本地 grep）
CONTEXTENGINE_HTTP_API_KEY=<long-random-secret>
# 宿主机用户目录挂载根（contextengine 索引 ~ 下任意工程）
CONTEXTENGINE_HOST_MOUNT=/Users/<you>   # macOS；Linux 用 /home/<you>
```

### 3. 启动

```bash
cd backend-go
docker compose up -d --build
# augment-local :8445/:8787, contextengine :8790, postgres :54329
```

### 4. 安装插件 jar

将 `releases/intellij-augment-0.482.3-beta.jar` 复制到 IntelliJ 插件目录（覆盖原文件），重启 IDE：

```bash
cp releases/intellij-augment-0.482.3-beta.jar \
  ~/Library/Application\ Support/JetBrains/IntelliJIdea2026.1/plugins/intellij-augment/lib/
```

JAR 内部版本是 `0.482.3.999-local`，高于对应 Marketplace stable，避免 IDE 在重启后自动覆盖本地补丁。

> 原始未打补丁 jar 可随时从插件市场重新安装回滚。补丁内容：`SettingsService` 两个 bridge（workspace 列表 + 语言统计）、sidecar `generate-project-overview` handler、Home 文件/会话统计、webview 摘要链路、线程数失败重试、onboarding 空问题过滤，以及 Java 21/日志/轮询加固。当前 bridge 源码和验证流程见 `re/patches/intellij-augment-0.482.3/`。

### 5. 连接 IDE

启动 IntelliJ 后用 `-Daugmentcode.oauth.url=http://127.0.0.1:8445` 指向本地 OIDC（或在 IDE 登录页配置）。登录后：

- 聊天走 `MODEL_GATEWAY_URL`（krill-ai 等）
- agent 调用 `codebase-retrieval` 返回 ContextEngine 真实检索结果
- Home 首页显示 ContextEngine 文件数、真实本地会话数，以及 Codebase 语言条形图和 LLM 项目概览

当前支持的是“本地核心工作流接管”，不是完整云端能力替换。Cloud Agents、handoff、第三方集成、BYOK、远端 MCP、Smart Paste、Prompt Enhancer、file intake 和完整 protobuf/gRPC streaming 均已关闭或明确返回 501；请按兼容性报告验收。

## 动态工作区

ContextEngine 不再写死工程路径。每次聊天请求携带的 `workspace_folders` 会把当前打开的工程映射到容器索引：

- 宿主机用户目录挂载为容器 `/host`（`.env` 配 `CONTEXTENGINE_HOST_MOUNT`，macOS 示例 `/Users/<you>`，Linux `/home/<you>`）
- `CONTEXTENGINE_HOST_BASE` 默认取 `CONTEXTENGINE_HOST_MOUNT`（二者一致）
- 工程按名建 ContextEngine workspace（`/host/<工程>`），`codebase-retrieval` 检索当前工程
- **无硬编码路径**：迁移机器/打包分发只需改 `.env` 里的 `CONTEXTENGINE_HOST_MOUNT`
- 索引在打开 Project Home、首次聊天或首次检索时自动触发，未完成时工具返回“正在索引”

## 密钥安全

- API key / 网关地址只放 `.env`（gitignored），compose 通过 `${VAR}` 注入
- `compose.yaml`、`Dockerfile`、源码均无硬编码凭据
- 若密钥曾进过 git 历史，请轮换（撤销旧 key 换新）

## 常见问题

- **Home 首页 Codebase 为空或 Files/Threads 为 0**：确认安装的是内部版本 `0.482.3.999-local`、ContextEngine healthy（`curl :8790/health`），然后打开工程和 Project Home
- **出现 `workspace_folder ... not supported`**：这是官方 sidecar 的默认检索回退提示；本地补丁会按当前 conversation 绑定工程并移除该提示。若仍出现，说明 IDE 仍在运行旧 JAR 或补丁又被 Marketplace 覆盖
- **codebase-retrieval 返回“正在索引”**：首次索引需数秒~数分钟，稍后重试
- **模型无回复**：检查 `MODEL_GATEWAY_URL/API_KEY`，`curl :8787/api-client/chat-stream` 直测。网关空 choices 或 error envelope 会显示为 `STOP_REASON_ERROR`，不会伪装成成功结束。
- **旧日志仍显示 501、remote_tool_id 或超时**：先 `docker compose up -d --build --force-recreate augment-local`，再只看重建后的时间窗口；旧容器可能仍将端口暴露在所有网卡。
- **需要重建索引**：关闭 IDE 后运行 `backend-go/scripts/clean-cache.sh`。默认保留会话和插件设置；只有显式 `--reset-state` 才会先备份再清除持久状态。
