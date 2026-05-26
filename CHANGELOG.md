# Changelog

## v1.5.0 — Token 预算制替代轮次上限 (2026-05-26)

### 变更
- **Token 预算制** — 用上下文水位替代硬编码 30 轮上限。对标 Claude Code AUTOCOMPACT_BUFFER_TOKENS 机制
- **三级熔断 + 自主卸载**：水位 <90% 继续；90-95% 压缩；连续 3 次压不下来 → 注入 `<warning>` 给 LLM 自主决策（调 Plan Agent 拆分 / task 卸载 / 返回结果）。给 LLM 一轮机会反应，无视则硬终止
- **LLM 自主卸载** — 熔断时不直接 return，而是注入警告让 LLM 自己决定：调 task(agent_type='plan') 拆分方案 → task(agent_type='general') 逐个子任务执行（子 Agent 独立上下文不占主空间）

---

## v1.4.0 — 并行工具执行 + 四种 SubAgent 类型 (2026-05-26)

### 新增
- **并行工具执行** — AgentLoop 中 LLM 一次返回的多个互不依赖的工具调用，确认后通过 CompletableFuture 线程池并行执行。确认串行（用户逐个看参数），执行并行
- **四种 SubAgent 类型** — 对标 Claude Code 泄露架构，SubagentRunner 支持四种专用 Agent：
  - **EXPLORE**（探索） — 只能 file + search，物理移除 bash/write/task，Prompt 层 NEVER 指令
  - **PLAN**（规划） — 白名单仅 file + search，FORBIDDEN 所有执行操作，只输出方案文本
  - **VERIFICATION**（验证） — 可读文件 + 诊断 bash，NEVER 修改代码
  - **GENERAL**（通用） — 除 task 外全部工具，防递归
- **SubAgent 双层安全保障** — 第一道：工具层物理隔离（不在 Dispatch Map 里就调不了）；第二道：Prompt 层否定指令（NEVER/FORBIDDEN）。Prompt 失效 ≠ 安全失效

### 增强
- **TaskTool** — 新增 `agent_type` 枚举参数（explore/plan/verification/general），LLM 可按需选择 Agent 类型
- **ToolDispatcher/ToolRegistry** — 新增 `without(String...)` 黑名单和 `only(String...)` 白名单过滤方法
- **ContextBuilder System Prompt** — 新增 TodoWrite 三条硬约束（最多20条、仅1条in_progress、禁止批量completed）+ 并行工具调用提示 + task agent_type 参数说明

---

## v1.3.0 — 流式输出 + 工具确认 + Prompt Caching (2026-05-26)

### 新增
- **流式输出** — AIService 双模设计：同步 `ChatLanguageModel` 用于摘要压缩，异步 `StreamingChatLanguageModel` 用于主对话。`CompletableFuture<AiMessage>` 桥接 async→sync，onNext 实时打印 token
- **ToolExecutionConfirmation** — 工具执行确认组件，交互模式 y/n/a 三级确认。拒绝后写入 history 让 LLM 调整策略。单次模式（-p）自动批准
- **Prompt Caching 架构** — ContextBuilder 拆分「可缓存前缀」（System Prompt + Memory + 工具声明，构造时固定）+「动态历史」。每次 build() 复用同一前缀引用，DeepSeek/Claude 自动缓存命中

### 增强
- **SubagentRunner 架构对齐** — 补齐 microCompact（静默裁剪）+ 错误注入（LLM 异常不崩溃），与 AgentLoop 完全对等
- **AgentLoop 集成** — 流式输出 + 工具确认 + Todo nag + 错误注入，完整对齐

### 改进
- **ToolRegistry** — 新增 `describeNames()` 方法，用于 System Prompt 中的工具声明
- **ContextBuilder** — 改进 System Prompt（工具使用指南 + 行为规范），提升 LLM 工具调用准确率

### 文档
- **README.md** — 更新流程图（流式 + 确认 + 缓存节点）、特性列表（+4 项）、模块说明（AIService/AgentLoop/ContextBuilder/SubagentRunner）、项目结构
- **docs/HARNESS_DESIGN.md** — 新增 4 章设计文档：流式输出、工具确认、Prompt Caching、SubagentRunner 对齐。总结表扩展到 10 个组件

---

## v1.2.0 — 后台异步任务 (2026-05-25)

### 新增
- **BackgroundManager** — 线程池执行长时间命令，通知队列异步返回结果
- **background_run** 工具 — 启动后台任务，立即返回 ID，不阻塞 Agent 循环
- **check_background** 工具 — 查询后台任务状态（单个/全部）
- AgentLoop 每轮 `drain()` 通知队列，自动注入 `<background-results>` 到上下文

## v1.1.2 — 修正记忆类型 (2026-05-25)

### 修复
- **MemoryManager 移除 TASK 类型** — 对齐 Claude Code 四种分类（user/feedback/project/reference），TASK 属于 .tasks/ 任务系统

## v1.1.1 — 可配置沙箱 (2026-05-23)

### 新增
- **可配置工作目录** — `config.yaml` 新增 `security.workspace` 配置项，`FileTool.initWorkspace()` 动态设置沙箱边界

## v1.1.0 — 沙箱安全 (2026-05-23)

### 新增
- **FileTool 路径沙箱** — `safePath()` 限制文件操作在工作目录内，防止 `../` 越界访问
- **BashTool 危险命令拦截** — 黑名单拦截 `rm -rf /`、`sudo`、`shutdown`、`mkfs.` 等危险命令

---

## v1.0.0 — 首次发布 (2026-05-22)

### 核心引擎
- **AgentLoop** — while(true) 主循环，永不改动。集成 microCompact / autoCompact / error 注入 / Todo nag / 30 轮上限
- **AIService** — LangChain4j `OpenAiChatModel` 封装，对接 DeepSeek API，复用为子 Agent
- **ToolDispatcher** — `Map<String, BaseTool>` Dispatch Map，支持运行时注册、MCP 动态工具、`withoutTaskTool()` 子 Agent 隔离

### 工具系统
- **FileTool** — 文件读写/列表/存在检查，含 `~` 路径展开和 50K 输出截断
- **BashTool** — Shell 命令执行，30 秒超时，输出自动截断
- **SearchTool** — 文本搜索，支持 pattern + ext 过滤，最多 50 条结果
- **TaskTool** — 子任务委托，调用 SubagentRunner，防递归
- **TodoWriteTool** — Claude Code 风格 Todo 管理，3 轮未更新自动提醒
- **ToolRegistry** — ToolSpecification 管理 + 工具注册，`without()` 副本
- **ToolResult** — 统一工具返回值，空文本防护

### 上下文引擎
- **CompactService** — 三层压缩：Micro（截断 >2000→500）/ Auto（LLM 摘要 + 保留 10 轮）/ Manual（`/compact`）
- **ContextBuilder** — System Prompt → Memory → History → Tools 四槽拼装

### MCP 协议
- **MCPClient** — 手写 JSON-RPC 2.0 over stdio，零 MCP SDK 依赖。完整 initialize → tools/list → tools/call 流程
- **MCPToolAdapter** — MCP `inputSchema` → LangChain4j `JsonObjectSchema` 自动转换

### 子 Agent
- **SubagentRunner** — 独立上下文执行，防递归（去 task 工具），最大轮次限制，天然线程安全

### 记忆系统
- **MemoryManager** — `~/.agent/memory/` 文件型记忆，MEMORY.md 索引 + 四种类型（user/feedback/project/reference），二层加载模式

### 配置
- **AppConfig** — SnakeYAML 解析，`${ENV_VAR}` 环境变量插值，文件系统 → classpath 双路径加载
- **config.yaml.example** — 配置模板，安全的默认值

### CLI
- **Main** — Picocli `-p` 单次 / `-i` 交互模式，`/compact` `/memory` `/quit` 内置命令

### 工程
- Maven + shade 插件 Fat JAR 打包
- `.gitignore` 排除 target/ config.yaml .idea/
- README.md 含完整项目说明、核心设计决策、与 learn-claude-code 逐项对比
- `docs/HARNESS_DESIGN.md` 含 8 个组件的设计思路 + 面试话术 + 5 个踩坑记录
- MIT License
