# MVP CLAUDE CODE — Harness 组件设计思路

> **Harness Engineering 核心命题：** Model 是司机（决定做什么），Harness 是车辆（决定能做什么）。你造的是一辆让司机发挥最大能力的车。

---

## 1. AgentLoop — 永不改动的循环

**设计决策：** while(true) 是所有功能的唯一底盘。不定义"应该怎么干活"，只定义"怎么运转"。

```
用户输入 → 加进 history → while(true):
  microCompact  → 每轮无声裁剪旧工具输出
  shouldCompact → token 超了？LLM 摘要
  ai.chat()     → 失败？不崩溃，注入 <error> 继续
  没有工具调用？→ 返回，结束
  有工具调用？  → 逐个执行，结果写回 history，继续循环
```

**为什么每步都放进去？** 因为循环不变——你把新功能加在外面，循环不知道它的存在。只有放进 while(true) 里，它才成为 Agent 的"本能"。microCompact 不在循环里就永远不会触发；error 注入不在循环里就一次 API 抖动直接崩溃。

**30 轮上限：** 不是功能需求，是保险丝。LLM 有时候会陷入工具调用循环（反复读同一个不存在的文件），没有上限就是无限循环。面试能讲："和汽车一样，再好的引擎也要有转速限制器。"

---

## 2. ToolDispatcher — Dispatch Map，不是反射

**设计决策：** `Map<String, BaseTool>` 查表执行，而不是 `@Tool` 注解反射。

**为什么？** 三个原因：
1. **可控：** 一个 Map 查表，任何一个 Java 程序员看一眼就懂。不需要理解注解处理器、代理生成、反射调用链。
2. **运行时动态：** MCP 工具是运行时从外部服务器下发的，不可能提前写 `@Tool` 注解。只有 Dispatch Map 能运行时注册/注销。
3. **子 Agent 隔离：** `withoutTaskTool()` 一行代码创建不含 task 的 Map 副本。注解反射做不到这种运行时裁剪。

**面试话术：** "我没用注解反射，用了 Dispatch Map 模式。因为 MCP 工具是运行时下发的，必须动态注册。而且子 Agent 防递归靠 Map 副本实现——比静态注解灵活得多。"

---

## 3. ContextBuilder — 拼装不是堆积

**设计决策：** 每次 LLM 调用前重新构建完整 `ChatRequest`，而不是往 history 尾部追加。

**为什么？** history 只存对话消息。但 LLM 每次需要的不只是 history：
- System Prompt（角色设定）
- Memory 索引（跨会话记忆）
- 当前工具列表（ToolSpecification）
- 对话历史

这四样拼装顺序是固定的。ContextBuilder 的职责就是"每次给 LLM 一份完整的、结构化的请求"。它不管 history 里有什么，只管拼装。

**面试话术：** "上下文不是简单把消息堆一起。我用四个固定槽位——System Prompt → Memory → History → Tools——保证 LLM 每次都拿到相同结构的请求。这避免了 prompt 位置漂移导致的输出质量下降。"

---

## 4. CompactService — 三层压缩的递进逻辑

**设计决策：** 不是一种压缩策略，是三种，按"成本递增、损失递增"递进。

| 层 | 触发起始值 | 怎么做 | API 成本 | 信息损失 |
|----|-----------|--------|---------|---------|
| Micro | 每轮 | 旧工具输出 > 2000 字符 → 截到 500 + 标记 | 零 | 低（旧输出细节） |
| Auto | token > 阈值 | LLM 摘要旧消息 + 保留最近 10 轮 | 一次 LLM 调用 | 中（语义保留） |
| Manual | 用户敲 `/compact` | 全量压缩 | 一次 LLM 调用 | 高 |

**为什么是三层而不是一层？** 一层 LLM 摘要的问题是成本太高（每轮都调 LLM 做摘要）。Micro 层零成本解决了 80% 的上下文膨胀问题——大文件输出、长命令结果，静默裁剪即可。Auto 层才是真正需要 LLM 语义理解的时候。Manual 层给用户一个"我不信任自动压缩，让我来"的后门。

**采访话术：** "很多人做上下文只做一层——要么滑窗丢消息，要么 LLM 摘要。我设计了三层递进：Micro 无声裁剪（零成本，解决 80% 问题）、Auto LLM 摘要（语义保留）、Manual 用户触发（最后防线）。每层成本递增，按需触发。"

---

## 5. MemoryManager — 文件型记忆，零依赖

**设计决策：** Markdown 文件系统，不用数据库。

**为什么？**
1. **人可读：** 用户可以直接打开 `~/.agent/memory/` 看 AI 记住了什么，删改也方便
2. **Git 友好：** Markdown 文件天然支持版本控制
3. **零依赖：** 不需要 JDBC、不需要 Redis、不需要任何外部服务
4. **索引模式：** MEMORY.md 做索引（全量注入 System Prompt），具体文件按需加载（Agent 用 FileTool 读取）。两层模式——索引轻量，内容按需

**五种类型（user/feedback/project/reference/task）：** 不是随便分的。每种类型有不同的保存时机和生命周期。user 是偏好（稳定），project 是上下文（随项目变），feedback 是纠偏（及时更新）。分类是为了让 MEMORY.md 索引可读，不是给 Agent 加复杂度。

**采访话术：** "记忆系统用了 Markdown 文件 + 索引模式。MEMORY.md 做索引全量注入，五种类型分类管理。不需要数据库，人可以直接打开看 AI 记住了什么。和 Claude Code 的记忆系统是同一种设计哲学。"

---

## 6. MCPClient — 手写 JSON-RPC over stdio

**设计决策：** 不引入任何 MCP SDK，纯 Java 标准库。

**为什么？**
- **面试能深讲 10 分钟：** 讲 JSON-RPC 握手（initialize → initialized）、tools/list 解析、tools/call 参数适配、子进程生命周期管理
- **零依赖：** `ProcessBuilder` + `BufferedReader/Writer` + `Jackson`，全是 JDK 自带或已有依赖
- **协议理解：** 手写的过程就是理解 MCP 协议的过程——JSON-RPC 消息格式、通知 vs 请求的区别、子进程 stdio 通信的坑

**遇到过的坑：** Windows 下 ProcessBuilder 找不到 `npx`（Bash PATH和 Windows PATH 不同），需要全路径 `D:\Node.js\npx.cmd`。readResponse() 每行读一次，如果 MCP server 发送多行 JSON 会损坏——这是已知限制。

**采访话术：** "MCP 客户端是我手写的，不依赖任何 MCP SDK。核心技术点是 JSON-RPC 协议的握手流程——先发 initialize 获取服务端能力，再发 initialized 通知确认，然后 tools/list 拉取工具列表。整个过程通过子进程 stdin/stdout 通信，完全在控。"

---

## 7. SubagentRunner — 独立上下文 + 防递归

**设计决策：** 不是线程隔离，是**上下文隔离**。

```
父 Agent: history[...], ToolDispatcher{tool1,tool2,task}
子 Agent: subHistory[...], ToolDispatcher{tool1,tool2}  ← 没有 task
```

**为什么不是线程？** 子 Agent 在父 Agent 的线程里同步执行，因为父 Agent 需要子 Agent 的结果才能继续。异步执行是另一种模式（后台任务），不应该和子 Agent 混淆。

**防递归：** `withoutTaskTool()` + `registry.without("task")` —— 子 Agent 的工具列表里没有 task，LLM 不知道有 task 工具就不会调用。这是**在工具定义层面切断**，不是在执行层面拦截。

**采访话术：** "子 Agent 的核心是上下文隔离——独立的 messages[] 和独立的工具集。防递归不是在执行层拦截，而是在工具定义层切断——子 Agent 的工具 spec 列表里根本没有 task。这种设计天然线程安全，因为父子之间不共享任何可变状态。"

---

## 8. CLI 设计 — Picocli 但不复杂

**设计决策：** `-p` 单次模式 + `-i` 交互模式，够了。

**为什么不做更多？** CLI 发请求的事情交给 LLM（调用 bash/read/write 工具），不要自己做。`-p` 就够了——单次提问，Agent 自己做所有事情。`-i` 是给人调试用的。

`/compact`、`/memory`、`/quit` 三个内置命令是 Harness 层面的操作，不是 Agent 任务。LLM 无法触发 compact（它在循环里），所以需要用户手动入口。

---

## 总结：所有 Harness 组件的共性和边界

| 组件 | 属于 Harness 的什么 | 不属于 Harness 的什么 |
|------|-------------------|---------------------|
| AgentLoop | 定义循环节奏 | 不定义具体执行步骤 |
| ToolDispatcher | 管理工具注册表 | 不管理工具实现 |
| ContextBuilder | 拼装请求结构 | 不决定对话内容 |
| CompactService | 管理上下文大小 | 不代替 LLM 决策 |
| MemoryManager | 持久化跨会话信息 | 不解释记忆语义 |
| MCPClient | 连接外部工具生态 | 不定义工具行为 |
| SubagentRunner | 隔离子任务上下文 | 不调度多 Agent |
| CLI | 人机交互入口 | 不做 Agent 能力 |

**核心原则：Harness 造车，Model 开车。车的质量决定了司机能开多远多快，但路线永远是司机自己决定的。**

---

## 开发过程遇到的问题与解决方案

### 1. LangChain4j 0.36.2 ToolParameters 构建器废弃陷阱

**问题：** `ToolParameters.builder().properties(...).required(...).build()` 编译通过，运行时 `parameters()` 返回 null，导致 LLM 调用报 `Cannot invoke Map.get because properties is null`。

**根因：** `ToolSpecification.Builder` 有两个 `parameters()` 重载——新版接受 `JsonObjectSchema`，旧版接受 `ToolParameters`。旧版的 `ToolParameters` 存在 `toolParameters` 字段中，但 `build()` 只读取新版的 `parameters`（JsonObjectSchema 字段），旧版字段被忽略。

**解决：** 改用 `dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder()`，使用 `addStringProperty()`、`addIntegerProperty()`、`addEnumProperty()` 等链式方法。

### 2. MCP 工具 Schema 丢弃

**问题：** MCP Server 通过 `tools/list` 返回的 `inputSchema`（完整 JSON Schema）在 `Main.java` 创建 `MCPToolAdapter` 时被丢弃——只传了 name 和 description，inputSchema 未传入。

**解决：** `MCPToolAdapter` 构造函数增加 `JsonNode inputSchema` 参数，`toToolSpecification()` 遍历 MCP inputSchema 的 properties，按类型构建对应的 JsonSchemaElement。

### 3. ToolExecutionResultMessage 不接受空白文本

**问题：** 工具返回空结果时（如 bash 命令无输出），`ToolExecutionResultMessage.from(req, "")` 抛出 `IllegalArgumentException: text cannot be null or blank`。

**解决：** 在 `AgentLoop.process()` 中添加防卫检查——content 为空或空白时替换为 `[empty]` 或 `[error]`。

### 4. Windows 下 `~` 路径不解析

**问题：** Java 的 `Path.of("~/.agent/memory/")` 在 Windows 上不展开 `~`，导致 FileTool 读记忆文件时 `File not found`。

**解决：** `FileTool.resolvePath()` 手动检测 `~/` 前缀，替换为 `System.getProperty("user.home")`。

### 5. 工具 Schema 类型推断导致 Map.of() 失败

**问题：** `Map.of("command", Map.of("type", "string", "description", "..."))` 返回 `Map<String, Map<String, String>>`，但 `ToolParameters.Builder.properties()` 期望 `Map<String, Map<String, Object>>`。Java 泛型不变性导致类型不匹配（编译未报错但运行时 builder 的字段为 null）。

**解决：** 用 `JsonObjectSchema.Builder` 的链式 API 替代 `Map.of()`，完全避免泛型类型推断问题。
