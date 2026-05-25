# Changelog

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
- **MemoryManager** — `~/.agent/memory/` 文件型记忆，MEMORY.md 索引 + 五种类型（user/feedback/project/reference/task），二层加载模式

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
