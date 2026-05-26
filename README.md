# MVP CLAUDE CODE — 纯 Java AI 编程助手

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> **后端实习项目。** 用 2000 行纯 Java 从零实现类 Claude Code 的 AI Agent，深入理解 LLM 应用架构。
>
> 不依赖 Spring Boot。核心命题：Model 是司机（决定做什么），Harness 是车辆（决定能做什么）——后端工程师造的是车。

## 整体流程图

```mermaid
flowchart TD
    A([用户输入]) --> B[Main.java\nPicocli CLI 入口]
    B --> C{模式}
    C -->|交互模式 -i| D[AgentLoop.process]
    C -->|单次模式 -p| D

    D --> D2[BackgroundManager.drain\n后台任务通知注入]
    D2 --> D3[microCompact\n旧工具输出裁剪]
    D3 --> D4{token > 阈值?}
    D4 -->|是 autoCompact| D5[LLM 摘要压缩]
    D4 -->|否| E
    D5 --> E

    E[ContextBuilder.build\n可缓存前缀 + 动态历史\nSystemPrompt + Memory + Tools + History]

    E --> F[AIService.streamingChat\n流式输出 token\nDeepSeek Streaming API]

    F -->|LLM 异常| G[注入 error 消息\n继续下一轮]
    G --> H{达到 MAX_ROUNDS=30?}

    F -->|纯文本响应| I([流式输出给用户 ✅])

    F -->|含工具调用| TC[ToolExecutionConfirmation.ask\n用户确认 y/n/a]

    TC -->|用户拒绝 n| RJ[写入 denied 消息\nLLM 调整策略]
    RJ --> H

    TC -->|用户批准 y/a| J[ToolDispatcher.execute\nDispatch Map 路由]

    J --> K{工具类型}
    K -->|file| L[FileTool\n读写/列目录]
    K -->|bash| M[BashTool\nShell 命令 30s超时]
    K -->|search| N[SearchTool\n文本搜索]
    K -->|task| O[TaskTool → SubagentRunner\n独立上下文 + microCompact\n+ 错误注入 + 防递归]
    K -->|TodoWrite| P[TodoWriteTool\nTodo 状态管理]
    K -->|MCP 工具| Q[MCPClient\nJSON-RPC over stdio\n外部 MCP Server]

    L & M & N & P & Q --> S[ToolExecutionResultMessage\n写回 history]
    S --> T{Todo 3轮未更新?}
    T -->|是| U[注入 reminder 提醒]
    T -->|否| H
    U --> H
    H -->|未达到| E
    H -->|已达到| Y([返回超限提示])

    subgraph 缓存优化["Prompt Caching 缓存优化"]
        CP[可缓存前缀\nSystem Prompt + Memory + 工具声明\n构造时固定 → 自动缓存命中]
        CP -.->|复用| E
    end

    subgraph 记忆系统
        Z[MemoryManager\nMEMORY.md 索引\n~/.agent/memory/]
        Z -->|全量注入| E
    end

    style A fill:#4CAF50,color:#fff
    style I fill:#4CAF50,color:#fff
    style Y fill:#f44336,color:#fff
    style F fill:#2196F3,color:#fff
    style TC fill:#FF9800,color:#fff
    style Q fill:#9C27B0,color:#fff
    style CP fill:#00BCD4,color:#fff
```

## 项目演示

```
> 请用 task 工具分别统计 pom.xml 依赖数和 src 目录 .java 文件数

📊 汇总结果
├── 📦 Maven 依赖数: 7
└── 📄 Java 源文件数: 25
```

## 项目特性

- **Agent 核心循环** — while(true) 永不改动，所有功能底盘
- **流式输出** — 同步 API 用于摘要，流式 API 用于主对话，CompletableFuture 桥接
- **并行工具执行** — LLM 一次返回的互不依赖工具调用，确认后 CompletableFuture 线程池并行执行
- **四种 SubAgent 类型** — Explore（只读探索）/ Plan（方案设计）/ Verification（验证审查）/ General（通用），对标 Claude Code
- **SubAgent 双层安全** — 第一道物理隔离（工具不在 Dispatch Map 里就调不了），第二道 Prompt 否定指令（NEVER/FORBIDDEN），Prompt 失效 ≠ 安全失效
- **工具执行确认** — 交互模式 y/n/a 三级确认，用户否决写回 history 让 LLM 调整策略
- **Prompt Caching 架构** — 可缓存前缀分离（System + Memory + Tools），跨请求复用，节省 30-50% token
- **手写 MCP 协议** — JSON-RPC over stdio，零 MCP SDK 依赖，面试能深讲 10 分钟
- **三层上下文压缩** — Micro（静默裁剪）→ Auto（LLM 摘要）→ Manual（用户触发）
- **文件型记忆系统** — MEMORY.md 索引 + 四种类型，跨会话持久化，人可读
- **子 Agent 隔离** — 独立上下文 + 防递归，天然线程安全
- **Todo 追踪** — TodoWrite 工具 + 3 条硬约束（最多20条、仅1条in_progress、禁止批量completed）+ 3 轮 nag 提醒
- **后台异步任务** — BackgroundManager 线程池 + 通知队列，长时间任务不阻塞主循环
- **错误自愈** — LLM 异常 → `<error>` 注入上下文继续，不崩溃
- **MCP 生态接入** — 自动发现外部 MCP Server 工具（filesystem/github/postgres 等）
- **极简依赖** — 仅 7 个 Maven 依赖，无数据库，无 DI 容器

## 项目结构

```
mvp-claude-code/
├── 📁 src/main/java/com/agent/
│   ├── Main.java                       # 🎯 Picocli CLI 入口（-i 交互 / -p 单次）
│   │
│   ├── 📁 core/                        # 🔧 核心引擎
│   │   ├── AgentLoop.java              # while(true) 主循环（流式 + 确认）
│   │   ├── AIService.java              # DeepSeek API 封装（双模：同步+流式）
│   │   ├── BackgroundManager.java      # 后台异步任务管理器（线程池+通知队列）
│   │   ├── CompactService.java         # 三层上下文压缩
│   │   ├── ContextBuilder.java         # ChatRequest 拼装 + Prompt Caching 前缀分离
│   │   ├── SubagentRunner.java         # 子 Agent 隔离执行（对齐 AgentLoop）
│   │   ├── ToolDispatcher.java         # Dispatch Map 工具分发
│   │   └── ToolExecutionConfirmation.java  # 工具执行确认（y/n/a 三级）
│   │
│   ├── 📁 tools/                       # 🔨 工具集合
│   │   ├── BaseTool.java               # 工具抽象基类
│   │   ├── BashTool.java               # Shell 命令执行
│   │   ├── BackgroundRunTool.java      # 后台任务启动工具
│   │   ├── CheckBackgroundTool.java    # 后台任务状态查询工具
│   │   ├── FileTool.java               # 文件读写（含 ~ 路径展开）
│   │   ├── SearchTool.java             # 文本搜索（支持 ext 过滤）
│   │   ├── TaskTool.java               # 子任务委托（调用 SubagentRunner）
│   │   ├── TodoManager.java            # Todo 状态管理（内存）
│   │   ├── TodoWriteTool.java          # TodoWrite 工具（Claude Code 风格）
│   │   ├── ToolRegistry.java           # 工具注册表（ToolSpecification 管理）
│   │   ├── ToolResult.java             # 工具执行返回值
│   │   └── 📁 mcp/                     # 🔌 MCP 协议实现
│   │       ├── MCPClient.java          # JSON-RPC over stdio 客户端
│   │       └── MCPToolAdapter.java     # MCP 工具 → BaseTool 适配
│   │
│   ├── 📁 memory/                      # 💾 记忆系统
│   │   ├── MemoryItem.java             # 记忆项数据结构
│   │   └── MemoryManager.java          # MEMORY.md 索引 + 四种类型文件
│   │
│   └── 📁 config/                      # ⚙️ 配置管理
│       └── AppConfig.java              # SnakeYAML 配置加载 + 环境变量插值
│
├── 📁 docs/
│   └── HARNESS_DESIGN.md               # 📖 设计思路 + 面试话术 + 踩坑记录
│
├── ⚙️ config.yaml.example              # 配置模板（可提交）
├── 📜 pom.xml                          # Maven 配置
├── 📖 README.md                        # 项目说明
└── 🙈 .gitignore                       # 排除 target/ config.yaml .agent/
```

## 模块说明

### `core/` — 核心引擎

**AgentLoop.java** — 永不改动的循环（流式 + 确认 + 并行执行）

while(true) 是所有功能的底盘。每轮迭代：microCompact → autoCompact → 流式 LLM 调用（异常不崩溃）→ 工具确认（y/n/a，串行）→ 工具执行（CompletableFuture 线程池并行）→ Todo nag → 循环。30 轮上限保护。

核心流程：
```
用户输入 → background drain → microCompact → autoCompact?
→ streamingChat (流式打印token) → 有工具调用?
  → 是: ToolExecutionConfirmation (y/n/a, 串行确认)
       → CompletableFuture.supplyAsync (并行执行)
       → 收集结果写回 history → nag? → 下一轮
  → 否: 返回最终文本
```

**AIService.java** — AI 服务封装（双模）

基于 LangChain4j，提供两种 API：
- `ChatLanguageModel`（同步）— 用于 CompactService 摘要压缩，无需流式
- `StreamingChatLanguageModel`（异步）— 用于 AgentLoop 主对话，`CompletableFuture<AiMessage>` 桥接 async → sync

```java
CompletableFuture<AiMessage> future = ai.streamingChat(messages, toolSpecs,
    token -> System.out.print(token));  // 实时流式打印
AiMessage aiMsg = future.get();          // 阻塞等待，拿到含工具调用的完整响应
```

设计要点：手写 `StreamingResponseHandler` 而不是用 `AiServices` 自动流式代理——我们需要控制 onNext（打印 token）、onComplete（拿工具调用）、onError（注入上下文）的每一步。

**ToolExecutionConfirmation.java** — 工具执行确认

交互模式 y/n/a 三级确认。y 执行本次、n 跳过本次并写入 history 让 LLM 调整策略、a 后续全部批准。单次模式（-p）自动批准所有工具。

**CompactService.java** — 三层上下文压缩

| 层 | 触发 | 策略 | API 成本 |
|---|------|------|---------|
| Micro | 每轮 | 旧工具输出 > 2000 字符 → 截到 500 + 标记 | 零 |
| Auto | token > 阈值 | LLM 摘要旧消息 + 保留最近 10 轮 | 一次 LLM 调用 |
| Manual | `/compact` | 全量压缩 | 一次 LLM 调用 |

**ContextBuilder.java** — 请求拼装 + Prompt Caching

每次 LLM 调用前组装完整 `ChatRequest`：`可缓存前缀（System Prompt + Memory + 工具声明）→ 对话历史`。前缀在构造时计算一次，后续每次 `build()` 复用同一个 `List<ChatMessage>` 引用。

**缓存原理：** LLM API 比较相邻请求的公共前缀。前缀分离后，System Prompt、Memory 索引、工具声明在每次请求中完全相同——DeepSeek/Claude 自动识别并缓存，节省 30-50% token 成本。不需要显式 cache_control 标记，结构设计本身就自带缓存优化。

**SubagentRunner.java** — 四种专用子 Agent + 双层安全

四种类型对标 Claude Code 泄露架构。第一道防线：物理隔离（工具不在 Dispatch Map 里就调不了）；第二道防线：Prompt 否定指令（NEVER/FORBIDDEN）。Prompt 失效 ≠ 安全失效。

| 类型 | 可用工具 | 移除 | 防线模式 |
|------|---------|------|---------|
| EXPLORE | file, search | bash, write, task, TodoWrite | 黑名单 |
| PLAN | file, search | 其他全部 | **白名单（最严）** |
| VERIFICATION | file, search, bash | write, task, TodoWrite | 黑名单 |
| GENERAL | 除 task 外全有 | task | 黑名单 |

**ToolDispatcher.java** — Dispatch Map

`Map<String, BaseTool>` 查表执行。支持 `without(name...)` 黑名单和 `only(name...)` 白名单两种过滤模式，运行时动态注册/注销（MCP 工具）。白名单模式用于 Plan Agent（只留 file+search，其他全部砍掉）。

**BackgroundManager.java** — 后台异步任务管理器

线程池执行长时间命令（编译、大型搜索），通知队列异步返回结果。AgentLoop 每轮 `drain()` 通知队列，自动注入 `<background-results>` 到上下文，不阻塞主循环。

### `tools/` — 工具集合

**FileTool** — 文件操作（read/write/list/exists），含 `~` 路径展开和 50K 输出截断

**BashTool** — Shell 命令执行，30 秒超时，输出自动截断

**SearchTool** — 文本搜索，支持 pattern + ext 过滤，最多 50 条结果

**TaskTool** — 子任务委托，调用 SubagentRunner。参数：prompt + agent_type(explore/plan/verification/general) + maxRounds。LLM 按需选择 Agent 类型，每种有不同的安全边界

**BackgroundRunTool** — 启动后台任务，立即返回任务 ID，不阻塞 Agent 循环。适用于编译等耗时操作

**CheckBackgroundTool** — 查询后台任务状态，支持查询单个（taskId）或全部任务

**TodoWriteTool** — Claude Code 风格 Todo 管理，嵌套 JSON Schema（items 数组）

**TodoManager** — Todo 状态管理（内存），`hasOpenItems()` 供 AgentLoop nag 检测

**ToolRegistry** — 工具注册表，管理 `List<ToolSpecification>` + `Map<String, BaseTool>`，`without()` 副本用于子 Agent

**ToolResult** — 统一工具返回值，空文本防护

### `mcp/` — MCP 协议

**MCPClient.java** — 标准 JSON-RPC 2.0 客户端，通过子进程 stdio 通信。支持 initialize → tools/list → tools/call 完整握手流程。使用 `java.util.concurrent.atomic` 管理请求 ID。

**MCPToolAdapter.java** — MCP Server 的 `inputSchema` → LangChain4j `JsonObjectSchema` 转换。按类型动态构建 properties（string/integer/boolean/array）。

### `memory/` — 记忆系统

**MemoryManager.java** — `~/.agent/memory/` 目录下管理 MEMORY.md 索引 + 四种类型文件（user/feedback/project/reference）。索引全量注入 System Prompt，具体文件按需加载。

### `config/` — 配置管理

**AppConfig.java** — SnakeYAML 解析 `config.yaml`。支持 `${ENV_VAR}` 环境变量插值。优先文件系统 → fallback classpath。

## 配置说明

### config.yaml

```yaml
model:
  provider: deepseek
  name: deepseek-chat
  baseUrl: https://api.deepseek.com/v1
  apiKey: ${DEEPSEEK_API_KEY}        # 环境变量，不要硬编码
  temperature: 0.7
  maxTokens: 4096

tools:
  builtin: [file, bash, search]
  mcp:
    enabled: false                     # 设为 true 启用 MCP
    servers:
      - name: filesystem
        command: npx                   # Windows 需全路径如 D:\Node.js\npx.cmd
        args: ["-y", "@modelcontextprotocol/server-filesystem", "."]

context:
  compactThreshold: 50000              # token 阈值，触发 auto compact

memory:
  dir: ~/.agent/memory                 # 记忆文件存储目录

security:
  workspace: ""                        # 留空=当前目录；填绝对路径=限制文件操作范围
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+
- DeepSeek API Key（[申请地址](https://platform.deepseek.com/)）
- （可选）Node.js 16+ 用于 MCP 工具

### 安装运行

```bash
# 1. 克隆
git clone https://github.com/cookie250825/mvp-claude-code.git
cd mvp-claude-code

# 2. 配置 API Key
cp config.yaml.example config.yaml
export DEEPSEEK_API_KEY="你的key"

# 3. 构建
mvn package -DskipTests

# 4. 交互模式
java -Dfile.encoding=UTF-8 -jar target/mvp-claude-code-1.0-SNAPSHOT.jar -i

# 5. 单次提问
java -Dfile.encoding=UTF-8 -jar target/mvp-claude-code-1.0-SNAPSHOT.jar -p "帮我读 pom.xml"
```

### 交互命令

| 命令 | 功能 |
|------|------|
| `/compact` | 手动触发上下文压缩 |
| `/memory` | 查看当前记忆索引 |
| `/quit` | 退出 |

### MCP 使用

1. 编辑 `config.yaml`，设置 `tools.mcp.enabled: true`
2. 配置 MCP 服务器（command + args）
3. 启动 Agent，MCP 工具自动发现并注册

```yaml
mcp:
  enabled: true
  servers:
    - name: filesystem
      command: npx
      args: ["-y", "@modelcontextprotocol/server-filesystem", "."]
```

## 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| AI 框架 | LangChain4j 0.36.2 | Function Calling + 流式 |
| CLI | Picocli 4.7.5 | 注解式命令行解析 |
| JSON | Jackson 2.16.1 | 序列化 + MCP 协议解析 |
| 配置 | SnakeYAML 2.2 | YAML 配置加载 |
| HTTP | LangChain4j 内置 | 通过 OpenAiChatModel 封装 |
| MCP | 手写 JSON-RPC | ProcessBuilder + stdio 通信 |
| 日志 | SLF4J 2.0.9 + Logback | 标准日志门面 |
| 构建 | Maven + shade 插件 | Fat JAR 打包 |

**不引入：Spring Boot、JLine、任何数据库。**

## 核心设计决策 — 我们为什么这么做

> 参照：[learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)（Python 参考实现，Anthropic 官方教程）

---

### AgentLoop：为什么不用 AiServices 自动代理

| 项目 | 做法 |
|------|------|
| learn-claude-code | Python 手写 `while True` |
| **我们** | **手写 `while(true)` + 手动 Dispatch Map** |

- **理由：** `AiServices` 是黑盒——工具怎么调用、何时重试、异常怎么处理，全在框架内部。手写循环让我们控制每一步：micro compact 在 LLM 调用前执行、异常包装成 `<error>` 注入上下文继续、工具结果超 2000 字符自动截断。`AiServices` 做不到这种粒度。
- **代价：** 多了约 200 行样板代码。但面试能讲清楚"while(true) 里每一步为什么放在这个位置"——这是理解 Agent 的标志。

---

### ToolDispatcher：为什么不用 @Tool 注解反射

| 项目 | 做法 |
|------|------|
| learn-claude-code | `Map<String, Function>` 函数字典 |
| LangChain4j 常规 | `@Tool` 注解 + `AiServices` 反射代理 |
| **我们** | **抽象类 `BaseTool` + Dispatch Map** |

- **理由：** MCP 工具是运行时从外部服务器下发的，不可能提前写 `@Tool` 注解。Dispatch Map 支持运行时 `register()`/`unregister()`。`withoutTaskTool()` 一行代码创建不含 "task" 的 Map 副本实现子 Agent 防递归——注解反射做不到这种运行时裁剪。

---

### CompactService：三层压缩，每层细节不同

learn-claude-code 也是三层（micro/auto/manual），但每层的策略和我们不一样：

**Micro 层对比：**

| 细节 | learn-claude-code | 我们 |
|------|------------------|------|
| 触发 | 每轮 | 每轮 |
| 策略 | 替换为占位符 `[Previous: used {tool_name}]` | 截断到 500 字符 + 标记 |
| 保留条数 | 最近 3 条 | 最近 10 条 |
| 保护规则 | `read_file` 结果永不裁剪 | 无特殊保护 |
| 最小阈值 | 内容 > 100 字符才处理 | 内容 > 2000 字符才处理 |

**Auto 层对比：**

| 细节 | learn-claude-code | 我们 |
|------|------------------|------|
| 触发 | token 估算 > 50K | token 估算 > 配置阈值 |
| 摘要后 | **全量替换** — 只剩 1 条摘要消息 | 保留最近 10 条 + 摘要 |
| 转录格式 | JSONL | 纯文本 |
| 保存时机 | 压缩前落盘 | 压缩前落盘 |

**Manual 层对比：**

| 细节 | learn-claude-code | 我们 |
|------|------------------|------|
| 触发者 | **模型调 `compact` 工具** | 用户终端敲 `/compact` |
| 执行后 | 返回 `agent_loop`，结束本次对话 | 压缩 history，继续对话 |

**为什么我们保持 KEEP_RECENT=10 而不是 learn-cc 的 3？** learn-cc 替换成占位符，3 条足够保证上下文连贯。我们做截断保留部分内容，需要 10 条来让 LLM 看到更多上下文的碎片。

**为什么我们没有 PRESERVE_RESULT_TOOLS？** 因为我们用截断而非占位符——截断后的内容仍可读，不需要保护 `read_file`。

---

### MemoryManager：为什么用 Markdown 文件而不是数据库

| 项目 | 做法 |
|------|------|
| learn-claude-code | ❌ **无独立语义记忆** — 只有转录落盘（S06）+ 任务 JSON（S07）+ 团队配置（S09） |
| **我们** | **MEMORY.md 索引 + 四种类型文件** — 自研，灵感来自 Claude Code 产品行为 |

- **理由：** Markdown 文件人可读、Git 友好、零依赖。四种类型（user/feedback/project/reference）让 MEMORY.md 索引可读。
- **二层模式：** 索引全量注入 System Prompt（轻量），Agent 需要时用 FileTool 读取具体文件（按需）。
- **本质区别：** learn-claude-code 记的是"发生过什么"（操作记录）。我们记的是"应该记住什么"（语义记忆）。

---

### MCPClient：为什么手写而不用 MCP SDK

| 项目 | 做法 |
|------|------|
| learn-claude-code | ❌ 不含 MCP |
| **我们** | **纯 Java 标准库实现 JSON-RPC over stdio** |

- **理由：** 手写的过程就是理解协议的过程——JSON-RPC 握手（initialize → initialized）、tools/list 解析、tools/call 参数适配——面试能讲 10 分钟。不依赖任何 MCP SDK。
- **遇过的坑：** Windows 下 `ProcessBuilder` 找不到 `npx`（Bash PATH ≠ Windows PATH），需全路径。`readResponse()` 每行读一次，MCP Server 发多行 JSON 会损坏——已知限制，面试讲 trade-off 的好素材。

---

### SubagentRunner：为什么是上下文隔离而不是线程隔离

| 项目 | 做法 |
|------|------|
| learn-claude-code (s_full) | `agent_type` 区分工具集（Explore 默认只给 bash+read；非 Explore 给全部） |
| **我们** | **一律去 task 工具，其他全给** — 更简单 |

- **理由：** 子 Agent 在父线程同步执行（父需等子返回结果）。独立性靠 `subHistory`（新 ArrayList）保证，不共享任何可变状态，天然线程安全。
- **防递归：** 在工具定义层面切断——子 Agent 的 ToolSpec 列表里没有 "task"，LLM 根本不知道有这个工具。

---

### 与 learn-claude-code 的核心差异总结

| 设计点 | learn-claude-code | 我们的选择 | 为什么不同 |
|--------|------------------|-----------|-----------|
| 语言 | Python | Java 17 | 静态类型适合面试讲"架构" |
| AI 框架 | 手写 HTTP | LangChain4j 封装 | 框架处理 API 细节，聚焦 Harness |
| API 协议 | Anthropic Native | OpenAI 兼容（DeepSeek） | 国内更便宜、更稳定 |
| 工具定义 | 函数字典 | 抽象类 + Dispatch Map | 类型安全 + 运行时动态注册 |
| 压缩策略 | 三层（Micro 占位符 / Auto 全量替换 / Manual 模型触发） | 三层（Micro 截断 / Auto 保留 10 条 / Manual 用户命令） | 同是三层，每层策略不同 |
| 记忆系统 | ❌ 无（仅转录 + 任务） | MEMORY.md + 四种类型 | 自研，灵感来自 Claude Code 产品 |
| Teammate | ❌ | ❌ | 偏离定位，面试讲不清 |
| Todo 提醒 | ✅ 3 轮 nag | ✅ 同样实现 + 3 条硬约束 | Claude Code 真实行为 |
| 流式输出 | ❌（同步阻塞调用） | **✅ CompletableFuture 桥接 async→sync** | 用户体验底线 |
| 工具确认 | ❌ | **✅ y/n/a 三级确认** | 安全防线 |
| 并行工具执行 | ❌ | **✅ CompletableFuture 线程池** | 减少 API 调用轮次 |
| Prompt Caching | ❌ | **✅ 前缀分离架构** | 结构自带缓存，面试加分 |
| SubAgent 类型 | 1 种（Explore） | **✅ 4 种（Explore/Plan/Verification/General）** | 对标 Claude Code 泄露架构 |
| SubAgent 安全 | 工具层去 task | **✅ 双层（物理隔离 + Prompt NEVER）** | Prompt 失效 ≠ 安全失效 |
| 会话管理 | ❌（无持久化会话） | ❌ | MVP 阶段不需要 |

## 为什么你应该关注这个项目

- 想理解 **Agent 循环的本质** → 看 `core/AgentLoop.java`（100 行）
- 想理解 **流式输出怎么实现** → 看 `core/AIService.java`（70 行）
- 想理解 **MCP 协议怎么实现** → 看 `tools/mcp/MCPClient.java`（150 行）
- 想理解 **上下文压缩怎么做** → 看 `core/CompactService.java`（110 行）
- 想理解 **Prompt Caching 怎么架构化** → 看 `core/ContextBuilder.java`（105 行）
- 想理解 **AI 记忆系统怎么设计** → 看 `memory/MemoryManager.java`（100 行）
- 想理解 **子 Agent 怎么隔离** → 看 `core/SubagentRunner.java`（85 行）
- 想理解 **工具确认怎么实现** → 看 `core/ToolExecutionConfirmation.java`（50 行）
- 想理解 **完整设计哲学** → 看 `docs/HARNESS_DESIGN.md`（11 章）

## 设计哲学

**Harness Engineering** — Agent = Model（司机）+ Harness（车辆）。你不造司机，你造车。

- 代码只定义工具权限，不定义执行流程 — 模型自己规划步骤
- 循环永远不变 — while(true) 是所有功能的底盘
- MVP 优先 — 先做面试能深讲 10 分钟的亮点，不做 UI 花活

详见 [`docs/HARNESS_DESIGN.md`](docs/HARNESS_DESIGN.md)

## 致谢

本项目深受以下项目启发：

- **[learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)** — Anthropic 官方教程。Harness Engineering 范式、三层压缩、TodoWrite、Subagent 等核心概念均源于此。
- **[ThoughtCoding](https://github.com/zengxinyueooo/ThoughtCoding)** — 优秀的 Java + LangChain4j 实现。MCP 客户端设计、工具适配模式、项目工程化结构提供了重要参考。

站在巨人的肩膀上。

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request。
