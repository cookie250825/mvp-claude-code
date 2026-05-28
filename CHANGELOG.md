# Changelog

## v1.6.3 — MemoryManager 项目级隔离 (2026-05-28)

### 新增
- **记忆项目隔离** — 不同项目不再共享记忆。USER/REFERENCE/FEEDBACK 存 `global/`（全局），PROJECT 存 `projects/<项目名>/`。换项目自动切换
- **MemoryManager(projectId)** — 新增双参数构造器，Main.java 用 workspace 目录名作为项目标识

### 存储结构
```
~/.agent/memory/
  global/MEMORY.md        ← USER + REFERENCE + FEEDBACK（全局，跟人不跟项目）
  projects/MVP/MEMORY.md  ← PROJECT（项目专属）
  projects/ThoughtCoding/MEMORY.md
```

### 修改文件
- `memory/MemoryManager.java` — 重写为双目录架构，save/getIndex/delete 按类型路由
- `Main.java` — 传入 workspace 目录名作为 projectId

---

## v1.6.2 — 死代码清理 + CLAUDE.md 支持 (2026-05-28)

### 新增
- **CLAUDE.md 全局注入** — ContextBuilder 启动时读取项目根目录 `CLAUDE.md`，全量注入 System Prompt 最前面。如果文件不存在则静默跳过。对标 Claude Code 的产品行为，用于存放项目架构、编码规范、构建命令等全局指令

### 修复
- **BashTool 只读白名单生效** — `READ_ONLY_WHITELIST` 之前定义但 execute() 从未检查，`readOnly=true` 路径为死代码。现 `execute()` 中新增白名单校验逻辑，VERIFICATION Agent 的 bash 真正受限
- **删除 AIService.summarize()** — 与 CompactService 私有 summarize() 功能重复，无人调用
- **删除 AppConfig.getProvider()/getProjectName()** — 两个 getter 无人调用
- **删除 ToolResult.toString()** — 未被使用，所有消费方均通过 getContent() 取值
- **删除 ToolExecutionConfirmation.isAutoApprove()** — 未被外部调用
- **删除 MCPClient.disconnect()** — 未被调用，资源清理由 JVM 退出完成
- **BackgroundManager 可见性修正** — Task 内部类/tasks/notifications 从 public 降为 private
- **Main.java** — 移除未使用的 import java.util.Map

---

## v1.6.1 — MemoryManager 重构：MemoryItem 驱动 (2026-05-28)

### 重构
- **MemoryManager 真正使用 MemoryItem** — 之前 MemoryItem 定义了 name/type/description/fileName 四个字段和 toString()，但 MemoryManager 全程用原始字符串拼凑，MemoryItem 从未实例化。现在改为解析式架构：
  - `parseIndex()` — 正则解析 MEMORY.md 每行 `- [name](file.md) — desc` → `List<MemoryItem>`
  - `writeIndex(List<MemoryItem>)` — 每个 `item.toString()` 序列化回写
  - `save()` — `new MemoryItem()` → parse → removeIf 匹配 fileName → add → write
  - `delete()` — parse → removeIf 匹配 fileName → write
  - `getFileName()` 终于在 `removeIf(i.getFileName().equals(...))` 中被使用
- **消除重复枚举** — `MemoryManager.MemoryType` 删除，统一使用 `MemoryItem.MemoryType`
- **删除 `appendIndex()`** — 被 parse→modify→write 模式替代，按文件名匹配更新取代按正则替换

### 修改文件
- `memory/MemoryManager.java` — 重写为 MemoryItem 驱动的解析式架构

---

## v1.6.0 — SubAgent 异步并行 + Worktree 文件隔离 (2026-05-28)

### 新增
- **SubagentManager** — 异步子 Agent 管理器。4 线程固定池，`submit()` 提交后立即返回任务 ID，子 Agent 在后台执行。AgentLoop 每轮 `drain()` 已完成结果自动注入 `<subagent-results>`。父 Agent 不再阻塞等待子 Agent
- **WorktreeManager** — Git worktree 管理器。`create()` 调用 `git worktree add --detach` 创建临时项目副本，`getDiff()` 获取修改内容，`remove()` 清理。每个 GENERAL 子 Agent 获得独立文件系统副本
- **GENERAL Worktree 隔离** — 多个 GENERAL 子 Agent 可同时并行执行，各自操作独立 worktree，改同一个文件也不互相覆盖。完成后 git diff 附加到结果中，父 Agent 决定是否合入
- **只读子 Agent 无限制并行** — Explore/Plan/Verification 纯读操作，不存在冲突，可任意数量并行
- **SubAgent 记忆注入** — 子 Agent 通过 MemoryManager 读取 MEMORY.md 获取项目上下文（只读不写）。SubagentRunner → SubagentManager → TaskTool 全链路传递 MemoryManager

### 增强
- **FileTool ThreadLocal workspace** — 新增 `setWorkspaceOverride()`/`clearWorkspaceOverride()`，支持线程级 workspace 覆写。子 Agent 在 worktree 中执行时文件操作自动指向正确目录
- **AppConfig.withWorkspace()** — 创建 workspace 路径不同的配置副本，子 Agent 在 worktree 中获取正确的项目路径
- **System Prompt 更新** — 说明 task 工具异步行为 + GENERAL worktree 隔离 + 子 Agent 记忆可读

### 架构
- **并行策略** — Explore/Plan/Verification 无限制并行（纯读）。GENERAL 并行 + worktree 隔离（可写不冲突）
- **线程安全** — SubagentRunner 全局部变量、AIService 无状态、ToolDispatcher/Registry 每次 clone、FileTool ThreadLocal、ConcurrentHashMap/Queue。无需任何锁

### 新增文件
- `core/SubagentManager.java` — 异步子 Agent 管理器
- `core/WorktreeManager.java` — Git worktree 管理器

### 修改文件
- `core/SubagentRunner.java` — run() 新增 MemoryManager 参数，ContextBuilder 不再传 null
- `core/AgentLoop.java` — 新增 SubagentManager 字段 + drain 逻辑 + 运行中提醒
- `core/ContextBuilder.java` — System Prompt 更新异步 task 说明
- `tools/TaskTool.java` — 改用 SubagentManager.submit() 异步提交，新增 MemoryManager
- `tools/FileTool.java` — 新增 ThreadLocal workspace 覆写
- `config/AppConfig.java` — 新增 withWorkspace() 方法
- `Main.java` — 装配 WorktreeManager + SubagentManager，传 MemoryManager 给 TaskTool

---

## v1.5.2 — SubAgent 安全加固 + 容错增强 (2026-05-28)

### 安全修复
- **BashTool 只读模式** — 新增 `BashTool(boolean readOnly)` 构造函数和 `READ_ONLY_WHITELIST` 白名单（java/mvn/git status/ls/cat/grep 等诊断类命令）。只读模式拒绝白名单外的所有命令，VERIFICATION Agent 专用
- **VERIFICATION bash 实质限制** — BashTool 现在可以以只读模式实例化，从物理层面拦截破坏性命令（之前仅靠 Prompt 层 NEVER 指令，实为无效防线）

### 容错增强
- **连续 LLM 失败熔断** — 新增 `consecutiveFailures` 计数器，连续失败 ≥ 3 次提前退出，不再无限注入错误消息撑爆 history
- **指数退避** — LLM 失败后退避 1s → 2s → 4s，减少无效重试压力
- **部分结果保留** — 新增 `lastPartialResult` 变量，达到 maxRounds 上限时返回最后一次有效文本输出 + 未完成标记，而非丢弃所有中间内容
- **AI 消息文本捕获** — 工具调用前记录 `aiMsg.text()` 作为备份，保证即使任务未完成也能返回有用内容

---

## v1.5.1 — Prompt Caching 对齐 Claude Code (2026-05-28)

### 重构
- **缓存前缀精简** — ContextBuilder 对齐 Claude Code `<|cache_boundary|>` 分隔。缓存前缀只放 System Prompt（永不改变），工具名列表和 Memory 索引移到动态层每次实时获取
- **MCP 工具热感知** — 工具名列表从 `buildPrefix()` 移到 `build()`，`ToolRegistry.describeNames()` 每次重新调用——MCP Server 中途增删工具立刻反映到 LLM

### 文档
- **README** — ContextBuilder 模块说明新增 prompt 结构图 + 三点架构对齐说明

---

## v1.5.0 — Token 预算制 + 熔断容错 (2026-05-26)

### 变更
- **Token 预算制** — 用上下文水位替代硬编码 30 轮上限。对标 Claude Code AUTOCOMPACT_BUFFER_TOKENS 机制。maxRounds=0 时完全靠 token 预算
- **三级熔断 + 自主卸载**：水位 <90% 继续；90-95% 压缩；连续 3 次压不下来 → 注入 `<warning>` 给 LLM 一轮自主决策机会（调 task 卸载 / 返回结果），无视则硬终止
- **新增配置项** — `context.maxRounds`(0=不限)、`context.maxContextTokens`(128000)、`context.contextDangerRatio`(0.9)

### 防御
- **工具崩溃保护** — AgentLoop 外层 `catch(Throwable)` 兜底 OOM 等 Error 级异常，写 `[tool crash]` 进 history 让 LLM 换路，进程不崩
- **Compact 摘要失败降级** — LLM 摘要 API 失败时不扔旧消息（之前会全部丢失），改为裁剪前一半保留后一半；连续 3 次失败放弃 compact 等熔断接手。对标 Claude Code MAX_CONSECUTIVE_FAILURES=3
- **重复输出修复** — Main.java 交互/单次模式不再重复打印 AI 响应（流式已实时输出）

### 改进
- **System Prompt** — 加工具崩溃处理指导："如果 [tool crash] 说明工具出 bug，不要重试同一个调用，换路"

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
