# CodeAgent-idea-plugin

自托管 Augment IDE 后端 + ContextEngine 代码检索，让 IntelliJ 里的 Augment 插件在本地完整工作——包括真实模型对话、代码检索（`codebase-retrieval`）、Home 首页 Codebase 概览与索引进度。

## 架构

```
IntelliJ (Augment 插件, 闭源)
   │  0.482.3-beta 插件 jar（含 5 个补丁，见 releases/）
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
| `augment-local` | Go 后端：OIDC、tenant surface、聊天流、工具调度、状态持久化 |
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

> 原始未打补丁 jar 可随时从插件市场重新安装回滚。补丁内容：`SettingsService` 两个 bridge（workspace 列表 + 语言统计）、sidecar `generate-project-overview` handler、webview 摘要链路、onboarding 空问题过滤。

### 5. 连接 IDE

启动 IntelliJ 后用 `-Daugmentcode.oauth.url=http://127.0.0.1:8445` 指向本地 OIDC（或在 IDE 登录页配置）。登录后：

- 聊天走 `MODEL_GATEWAY_URL`（krill-ai 等）
- agent 调用 `codebase-retrieval` 返回 ContextEngine 真实检索结果
- Home 首页 Codebase 区块显示：语言条形图 + LLM 生成的项目概览

## 动态工作区

ContextEngine 不再写死工程路径。每次聊天请求携带的 `workspace_folders` 会把当前打开的工程映射到容器索引：

- 宿主机用户目录挂载为容器 `/host`（`.env` 配 `CONTEXTENGINE_HOST_MOUNT`，macOS 示例 `/Users/<you>`，Linux `/home/<you>`）
- `CONTEXTENGINE_HOST_BASE` 默认取 `CONTEXTENGINE_HOST_MOUNT`（二者一致）
- 工程按名建 ContextEngine workspace（`/host/<工程>`），`codebase-retrieval` 检索当前工程
- **无硬编码路径**：迁移机器/打包分发只需改 `.env` 里的 `CONTEXTENGINE_HOST_MOUNT`
- 索引在后端启动/首次检索时自动触发，未完成时工具返回“正在索引”

## 密钥安全

- API key / 网关地址只放 `.env`（gitignored），compose 通过 `${VAR}` 注入
- `compose.yaml`、`Dockerfile`、源码均无硬编码凭据
- 若密钥曾进过 git 历史，请轮换（撤销旧 key 换新）

## 常见问题

- **Home 首页 Codebase 为空**：确认 jar 已安装（releases 版）、ContextEngine healthy（`curl :8790/health`）、已打开工程并触发过聊天
- **codebase-retrieval 返回“正在索引”**：首次索引需数秒~数分钟，稍后重试
- **模型无回复**：检查 `MODEL_GATEWAY_URL/API_KEY`，`curl :8787/api-client/chat-stream` 直测
