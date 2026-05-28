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

**四种类型（user/feedback/project/reference）：** 不是随便分的。每种类型有不同的保存时机和生命周期。user 是偏好（稳定），project 是上下文（随项目变），feedback 是纠偏（及时更新）。分类是为了让 MEMORY.md 索引可读，不是给 Agent 加复杂度。

**采访话术：** "记忆系统用了 Markdown 文件 + 索引模式。MEMORY.md 做索引全量注入，四种类型分类管理。不需要数据库，人可以直接打开看 AI 记住了什么。和 Claude Code 的记忆系统是同一种设计哲学。"

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

## 7. SubagentRunner — 独立上下文 + 防递归 + 记忆注入

**设计决策：** 不是线程隔离，是**上下文隔离**。

```
父 Agent: history[...], ToolDispatcher{tool1,tool2,task}
子 Agent: subHistory[...], ToolDispatcher{tool1,tool2}  ← 没有 task
                                 + MemoryManager  ← 可读 MEMORY.md
```

**异步并行：** v1.6 起子 Agent 不再同步阻塞。SubagentManager 线程池管理，task() 调用后立即返回"已启动"，子 Agent 后台执行。完成后结果通过 drain() 自动注入父 Agent 对话。

**GENERAL Worktree 隔离：** GENERAL 类型通过 WorktreeManager 创建独立 git worktree（`git worktree add --detach`），文件系统级别物理隔离。多个 GENERAL 可同时并行，改同一个文件也不互相覆盖。FileTool ThreadLocal workspace 覆写确保文件操作指向正确目录。

**记忆注入（只读）：** 子 Agent 通过 MemoryManager 读取 MEMORY.md 获取项目上下文。只读不写——子 Agent 是一次性工人，记忆应由父 Agent 管理。

**防递归：** `withoutTaskTool()` + `registry.without("task")` —— 子 Agent 的工具列表里没有 task，LLM 不知道有 task 工具就不会调用。这是**在工具定义层面切断**，不是在执行层面拦截。

**线程安全：** SubagentRunner 全局部变量、AIService 无状态、ToolDispatcher/Registry 每次 clone、FileTool ThreadLocal——无需任何锁。

**采访话术：** "子 Agent 的核心是上下文隔离——独立的 messages[] 和独立的工具集。防递归不是在执行层拦截，而是在工具定义层切断。v1.6 升级为异步并行 + worktree 文件隔离——只读类型无限制并行，GENERAL 每个跑在独立 git worktree 中。这种设计天然线程安全，因为父子之间不共享任何可变状态。"

---

## 8. CLI 设计 — Picocli 但不复杂

**设计决策：** `-p` 单次模式 + `-i` 交互模式，够了。

**为什么不做更多？** CLI 发请求的事情交给 LLM（调用 bash/read/write 工具），不要自己做。`-p` 就够了——单次提问，Agent 自己做所有事情。`-i` 是给人调试用的。

`/compact`、`/memory`、`/quit` 三个内置命令是 Harness 层面的操作，不是 Agent 任务。LLM 无法触发 compact（它在循环里），所以需要用户手动入口。

---

## 8. 流式输出 — 用户体验的底线

**设计决策：** 同步 API 用于摘要，流式 API 用于主对话。

```
AIService
├── model (同步)     → chat(String prompt)      → 摘要压缩、简单问答
└── streamingModel   → streamingChat(messages)   → AgentLoop 主循环
```

**为什么需要两个 Model？** 摘要压缩（CompactService）不需要流式——它就是一瞬间的事，用户不坐在屏幕前等。但主对话必须流式——用户输入"帮我读 pom.xml"后看到 token 一个一个蹦出来，这是 AI 交互的基本体验。同步等待 30 秒，用户会以为程序卡死了。

**实现细节：** 使用 `CompletableFuture<AiMessage>` 桥接异步流式调用和同步 while(true) 循环。`streamingModel.generate()` 是异步的（立即返回），但循环需要等待 AI 响应才能继续。`future.get()` 阻塞直到流式完成，然后在 onComplete 里拿到含工具调用的 `AiMessage`。

**和 LangChain4j AiServices 的区别：** `AiServices` 也支持流式，但它是黑盒——token 怎么来的、何时结束、中间是否有工具调用，全在框架内部。手写 `StreamingResponseHandler` 让我们控制每一步：onNext 里实时打印，onComplete 里拿工具调用，onError 里注入上下文。

---

## 9. ToolExecutionConfirmation — 用户安全防线

**设计决策：** 交互模式需确认，单次模式自动批准。

```
for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
    if (!confirmation.ask(req)) {         ← 用户选择 n
        history.add("User denied");       ← 告诉 LLM 被拒绝了
        continue;
    }
    result = tools.execute(req);          ← 用户选择 y/a
    history.add(ToolExecutionResultMessage.from(req, result));
}
```

**三种选择：** y（执行这一次）、n（跳过这一次）、a（后续全部批准，不再询问）。

**为什么用户拒绝也要写入 history？** 如果静默跳过，LLM 不知道工具被拒绝了，下一轮会再试同一个工具调用。把 "User denied tool execution" 写进 history，LLM 看到后会调整策略。

**为什么不算 Harness 越界？** Harness 不决定"是否应该执行"，它只提供"用户有否决权"这个能力。决定权在用户手里。这是安全机制，不是执行逻辑。

---

## 10. Prompt Caching — 结构即缓存

**设计决策：** 把每次 LLM 请求拆成「可缓存前缀」+「动态历史」。

```
ChatRequest = [System Prompt] + [Memory 索引] + [工具声明] + [对话历史]
              ←—— 构造时计算一次，——→  ←—— 每次 build() 重新拼接 ——→
              ←—— 完全不变 = 缓存命中 —→
```

**原理：** 所有 LLM API（DeepSeek/Claude/GPT）都会比较相邻请求的公共前缀。前缀越稳定、越长，缓存命中率越高。我把 System Prompt、Memory 索引、工具可用性声明固定在 `cachedPrefix` 里，同一个 `ContextBuilder` 实例的每次 `build()` 都复用相同的前缀 List。

**三个缓存槽位：**
| 槽位 | 内容 | 变化频率 | 缓存价值 |
|------|------|---------|---------|
| 1 | System Prompt | 永不改变 | 高（最长、最稳定） |
| 2 | Memory 索引 | 跨会话恒定 | 中 |
| 3 | 工具可用性声明 | 同会话不变 | 中 |

**为什么不是显式 cache_control 断点？** 当前项目对接 DeepSeek API，DeepSeek 自动识别重复前缀并在服务端缓存，不需要显式标记。如果后续切换到 Claude API，再加 `cache_control: {"type": "ephemeral"}` 断点即可——架构已经预留了分离结构，加标记只是一行代码的事。

**面试话术：** "上下文拼装不是简单拼接。我把请求拆成可缓存前缀和动态历史两部分——前缀在构造时固定，每次请求复用同一个对象引用。DeepSeek/Claude 等服务端会自动识别这种重复前缀并缓存，节省 30-50% 的 token 成本。结构设计本身就自带缓存优化。"

---

## 11. SubagentRunner 对齐 — 架构一致性

**设计决策：** SubagentRunner 补齐 microCompact + 错误注入 + 记忆注入，通过 SubagentManager 实现异步并行 + worktree 文件隔离。

**对齐矩阵：**

| 功能 | AgentLoop | SubagentRunner | 说明 |
|------|-----------|---------------|------|
| microCompact | ✅ | ✅ | 每轮裁剪 >2000 字工具输出 |
| autoCompact | ✅ | ✅ | token 超阈值 LLM 摘要 |
| 错误注入 | ✅ | ✅ | LLM 异常 → `<error>` 消息继续 |
| Memory 读取 | ✅ | ✅ v1.6 | MEMORY.md 注入子 Agent 上下文 |
| Tool 确认 | ✅ 交互式 | ✅ 自动批准 | 子 Agent 不二次确认 |
| Todo nag | ✅ | ❌ 不需要 | 子 Agent 不管理 Todo |
| 后台任务 | ✅ | ❌ 不需要 | 子 Agent 通过 SubagentManager 异步执行 |
| Worktree 隔离 | ❌ 不需要 | ✅ v1.6 | GENERAL 类型独有 |

**为什么子 Agent 的 Tool 确认是「自动批准」？** 父 Agent 已经决定委托任务给子 Agent，如果子 Agent 的每次工具调用还要再确认一次，用户体验就变成了"俄罗斯套娃式的确认弹窗"。子 Agent 用 `new ToolExecutionConfirmation(false)` —— 非交互模式，所有工具自动批准。

**面试话术：** "子 Agent 和主 Agent 共享同一套循环架构——微裁剪、自动压缩、错误自愈全有。v1.6 升级为异步并行——只读类型无限制并行，GENERAL 每个跑在独立 git worktree 中。FileTool ThreadLocal workspace 确保文件操作正确隔离。记忆只读注入让子 Agent 理解项目上下文。"

---

## 总结：所有 Harness 组件的共性和边界

| 组件 | 属于 Harness 的什么 | 不属于 Harness 的什么 |
|------|-------------------|---------------------|
| AgentLoop | 定义循环节奏 | 不定义具体执行步骤 |
| AIService | 管理 API 通信方式（同步/流式） | 不决定 prompt 内容 |
| ToolDispatcher | 管理工具注册表 | 不管理工具实现 |
| ToolExecutionConfirmation | 提供用户否决权 | 不决定是否应该执行 |
| ContextBuilder | 拼装请求结构 + 缓存优化 | 不决定对话内容 |
| CompactService | 管理上下文大小 | 不代替 LLM 决策 |
| MemoryManager | 持久化跨会话信息 | 不解释记忆语义 |
| MCPClient | 连接外部工具生态 | 不定义工具行为 |
| SubagentRunner | 隔离子任务上下文 | 不调度多 Agent |
| SubagentManager | 异步调度 + 结果回传 | 不干预子 Agent 决策 |
| WorktreeManager | 文件系统隔离 | 不管理 Git 分支策略 |
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
