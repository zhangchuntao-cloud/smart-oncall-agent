# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答

集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维

基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

-   ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
-   ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
-   ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
-   ✅ **会话管理**: 上下文维护、历史管理、自动清理
-   ✅ **Web 界面**: 提供测试界面和 RESTful API
-   ✅ **资源护栏**: 循环防护 + token 预算 + 并发限制 + 依赖降级

## 🛡️ 资源护栏

LLM 应用和传统服务的最大区别：**它的资源消耗是不可预测的**。同一段代码、
同一个任务，两次运行的 token 消耗可能差好几倍——因为模型自己决定走多少步。

实测同一个告警分析任务连跑三次：

| 运行 | LLM 调用 | token | 耗时 | 结果 |
|------|---------|-------|------|------|
| A | 9 次 | 23k | 35s | 完成 |
| B | 14 次 | 64k | 109s | 未完成 |
| C | 15 次 | 86k | 136s | 完成 |

**没有上限的话，一次失控的循环就能烧掉整月配额。** 所以护栏不是"锦上添花"，
而是 LLM 应用上生产的前提。

### 三层防护

| 层 | 机制 | 位置 |
|----|------|------|
| 迭代上限 | `CompileConfig.recursionLimit`，限制 Supervisor 图递归步数 | 框架层 |
| 资源预算 | 累计 LLM 调用数 / token / 工具调用数，超限即终止 | `AgentBudget` |
| 墙钟超时 | 每次调用前检查已耗时，到点终止 | `AgentBudget` |

其中 token 计量由 `MeteredChatModel` 完成——**包住 `ChatModel` 而不是用框架的
`ModelInterceptor`**，因为 Supervisor 编排的子 Agent 走的是子图，拦截器能否透传
不好验证；而所有模型调用都必然经过 `ChatModel`，计量因此是确定的。

### 两类超限，策略不同

- **同一工具 + 同参数反复调用** → 只拦截并提示模型换方向，**不终止**。
  模型钻牛角尖很常见，直接杀掉整次运行太浪费。
- **总量超限**（调用数 / token / 耗时）→ 立即终止。
  这说明运行已经失控，继续只会更贵。

### 依赖降级

非关键依赖不该有否决整个应用的权力：

- **Milvus 不可用** → 应用照常启动（`@Lazy`），RAG 工具降级，分析继续
- **MCP / 日志服务不可用** → 降级为仅使用内置工具，运行继续
- **模型服务不可重试错误**（欠费 / 鉴权失败 / 限流）→ 立即终止，不重试白烧配额

### 错误信息不外泄

供应商返回的原始报文（含 `request_id`、文档链接、账号信息）只进服务端日志；
用户看到的是分类后的可读说明，例如"模型服务调用失败：账户余额不足或已欠费"。

## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | 1.1.0 | AI 应用框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | Agent 编排（Supervisor / ReactAgent） |
| DashScope SDK | 2.17.0 | 阿里云 AI 服务（对话 + 文本向量化） |
| Milvus | 2.6.10 | 向量数据库 |

## 📦 核心模块

```
smart-oncall-agent/
├── src/main/java/com/smartoncall/
│   ├── controller/
│   │   └── ChatController.java        # 统一接口控制器 ⭐
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   └── Vector*.java               # 向量服务
│   ├── agent/
│   │   ├── guard/                     # 资源护栏：预算、循环防护、降级
│   │   │   ├── AgentBudget.java       # 单次运行的资源账本
│   │   │   ├── MeteredChatModel.java  # 包住 ChatModel 做 token 计量
│   │   │   └── LoopGuard*.java        # 工具循环拦截 / 结果截断
│   │   └── tool/                      # Agent 工具集
│   │       ├── DateTimeTools.java     # 时间工具
│   │       ├── InternalDocsTools.java # 文档检索
│   │       ├── QueryMetricsTools.java # 告警查询
│   │       └── QueryLogsTools.java    # 日志查询
│   ├── config/                        # 配置类
├── src/test/java/com/smartoncall/     # 单元测试
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```

## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**

```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```

支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**

```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```

一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```

自动执行告警分析流程，生成运维报告（SSE 流式输出）。

### 3. 会话管理

-   `POST /api/chat/clear` - 清空会话历史
-   `GET /api/chat/session/{sessionId}` - 获取会话信息

### 4. 文件管理

-   `POST /api/upload` - 上传文件并自动向量化
-   `GET /milvus/health` - Milvus 健康检查

## ⚙️ 核心配置

### application.yml

```yaml
server:
  port: 9900

# Milvus 向量数据库
milvus:
  host: localhost
  port: 19530

# 阿里云 DashScope
spring:
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}" // 环境变量

# RAG 配置
rag:
  top-k: 3
  model: ""          # 留空使用框架默认模型；填写则覆盖

# 文档分片
document:
  chunk:
    max-size: 800
    overlap: 100

# Agent 资源护栏（详见「资源护栏」一节）
agent:
  guard:
    max-iterations: 12
    max-model-calls: 30
    max-total-tokens: 120000
    max-tool-calls: 40
    max-same-tool-repeats: 3
    max-tool-result-chars: 3000
    timeout-seconds: 300
    max-concurrent-runs: 4
```

### API Key 配置

密钥通过项目根目录的 `.env` 文件提供，该文件已在 `.gitignore` 中：

```bash
# 在项目根目录创建 .env
DASHSCOPE_API_KEY=your-api-key
```

`application.yml` 里通过 `spring.config.import: optional:file:.env[.properties]` 读取它，
`optional:` 前缀保证文件不存在时静默跳过、不影响启动。

也可以改用系统环境变量（优先级更高）：

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## 🚀 快速开始

### 1. 环境准备

```bash
# 在项目根目录创建 .env，填入 API Key（该文件已被 gitignore）
echo "DASHSCOPE_API_KEY=your-api-key" > .env
```

### 2. 启动应用

方法一：手动启动

```bash
# 1. 启动向量数据库（注意 -f 必须在 up 之前）
docker compose -f vector-database.yml up -d

# 2. 启动服务
mvn spring-boot:run
```

方法二：一键启动

```bash
make init  # 会自动启动向量数据库并上传运维文档到向量库
```

### 3. 使用示例

**Web 界面**

```
http://localhost:9900
```

**命令行**

```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat 
  -H "Content-Type: application/json" 
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 健康检查
curl http://localhost:9900/milvus/health
```

**版本**: v1.0.0  
**作者**: chief  
**许可证**: MIT