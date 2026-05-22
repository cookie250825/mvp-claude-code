# 仿 Claude Code Agent 项目 — Java后端面试题与解答

> 面试官视角，基于 `com.claudeagent` 项目的真实代码设计，覆盖 Java 17、并发编程、设计模式、系统架构、MCP 协议等核心考点。

---

## 一、架构与设计思想（6 题）

### Q1：这个项目的核心 Agent 循环为什么"永不改变"？请描述其设计原理。

**考点**：理解 Agent 循环本质是对 LLM Function Calling 的编排，而非业务逻辑。

**解答**：

核心循环极其简单（约 40 行）：

```
用户输入 → callApi() → 检查 finish_reason → 执行 Tool → 喂入结果 → 继续循环
```

它永不改变的原因是：**LLM 被给定 tools 定义后，会自行决定"何时调用工具"和"何时输出最终回答"**。代码只负责两件事：
1. 调用 API 并检查 `finish_reason`：是 `tool_calls` 则执行工具、把结果喂回；是 `stop` 则退出循环输出文本
2. 在循环的固定扩展点（压缩、通知注入、收件箱检查）插入钩子

这个设计本质上是一个**解释器模式**：LLM 是"程序"，Agent 循环是"解释器"。只要 LLM 遵守 Function Calling 协议，循环代码不需要变动。

**追问**：为什么不用状态机来实现？
> 因为 LLM 已经充当了"智能状态机"——它根据完整对话历史做决策，远比人工枚举状态健壮。代码层面只需要处理 API 协议状态（finish_reason），业务状态完全交给 LLM。

---

### Q2：项目仅依赖 Jackson 和 dotenv-java 两个外部库，为什么能做到如此极简？这体现了什么设计原则？

**考点**：评估候选人对依赖管理的意识和对 Java 标准库的熟悉程度。

**解答**：

能极简的原因是将所有核心能力压在了 Java 标准库上：

| 需求 | 不使用外部库 | 使用的标准库 |
|------|------------|------------|
| HTTP 调用 LLM API | 不用 OkHttp / Apache HttpClient | `java.net.http.HttpClient`（Java 11+） |
| JSON 解析 | 必须用 Jackson（标准库无 JSON） | `jackson-databind` |
| 文件读写 | 不用 Commons IO | `java.nio.file.Files` |
| Shell 命令执行 | 不用 Apache Exec | `java.lang.ProcessBuilder` |
| 并发队列 | 不用 Guava / Disruptor | `java.util.concurrent` + `synchronized` |
| 环境变量加载 | 必须用 dotenv-java（标准库无 .env 支持） | `dotenv-java` |

体现的设计原则：
1. **YAGNI（You Ain't Gonna Need It）**：不引入框架直到确实需要
2. **依赖最小化**：每增加一个依赖就是增加供应链攻击面、版本冲突风险和打包体积
3. **对标准库的深度掌握**：Java 11+ 的 HttpClient 已经是生产级的，`Files` 和 `ProcessBuilder` 覆盖了所有文件/进程操作场景

---

### Q3：项目中 S01-S13 逐章构建，但核心循环不变。请从软件工程角度分析这种"渐进式架构"的优缺点。

**考点**：架构演进思维、开闭原则、可维护性。

**解答**：

**优点**：
- **开闭原则的极致体现**：核心循环对扩展开放（注入点），对修改关闭（循环本身不变）
- **低认知负担**：每章只引入一个新概念，读者可以逐步理解完整系统
- **可测试性强**：每一章都是独立可运行的里程碑，可以单独验证
- **强制良好抽象**：因为不能改循环，新增能力必须通过清晰的接口（静态字段注入、钩子方法）接入

**缺点**：
- **静态字段注入是反模式**：`ToolHandlers.taskMgr`、`ToolHandlers.bgMgr` 等静态字段模拟依赖注入，在真实项目中不利于单元测试和模块隔离
- **缺少真正的 DI 容器**：所有管理器在 Main.java 中手动创建和注入，模块间耦合通过静态字段传播
- **难以并行开发**：多人协作时会频繁冲突在 ToolHandlers 的 dispatch 方法上

---

### Q4：ToolHandlers 的 dispatch 方法用 Java 17 switch 表达式实现，和传统的 if-else 链或 Map 分发有什么优劣？

**考点**：Java 新特性掌握、性能意识。

**解答**：

```java
return switch (toolName) {
    case "bash"        -> runBash(...);
    case "read_file"   -> runRead(...);
    case "write_file"  -> runWrite(...);
    // ...
    default            -> dispatchMcp(toolName, input);
};
```

**对比分析**：

| 方案 | 性能 | 可读性 | 扩展性 |
|------|------|--------|--------|
| if-else 链 | O(n) 逐条比较 | 差，括号嵌套深 | 差 |
| switch 表达式（本题） | O(1) tableswitch/lookupswitch | 极佳，箭头语法简洁 | 中，需改源码 |
| Map<String, Handler> | O(1) 哈希 | 好 | 最佳，可动态注册 |

**项目选择 switch 是合理的**：工具数量固定（~15个），教学项目不需要运行时注册。但如果在真实项目中，MCP 客户端动态注册工具时，Map 方案会更好——`dispatchMcp` 的 default 分支实际上就是 Map 方案的兜底。

**追问**：Java 17 switch 表达式与 Java 8 switch 语句的区别？
- switch 表达式有返回值，可直接赋值
- 箭头语法（`->`）无需 break，避免 fall-through bug
- 编译器强制穷举（sealed class 场景）或要求 default

---

### Q5：项目中有 9 种可识别的设计模式，请任意挑选 3 种，结合项目实际代码说明其应用和动机。

**考点**：设计模式的实际应用能力，而非背诵定义。

**解答**：

**1. 模板方法模式 — AgentLoop.agentLoop()**

核心循环是模板，固定步骤为：
```
injectBackgroundNotifications()  → 钩子
injectInbox()                    → 钩子
ContextCompactor.maybeCompact()  → 钩子
reinjectIdentity()               → 钩子
callApi()                        → 固定
工具执行循环                      → 固定
```

所有子类（主 Agent、子 Agent、队友）共用同一个循环逻辑，通过 `isSubagent` 标志和静态方法 `runSubagent()` / `runTeammateTurn()` 区分行为。这避免了为每种 Agent 写重复的循环代码。

**2. 观察者模式 — BackgroundManager.drainNotifications()**

```
守护线程（生产者） → synchronized(lock) → notificationQueue.add()
主线程（消费者）   → drainNotifications() → 排空注入对话历史
```

这并非传统的 Observer 接口注册，而是**基于共享队列的生产者-消费者模型**。设计精妙之处：守护线程的后台命令结果不是"工具返回值"（因为是异步的），所以注入时使用 `role: user` 而非 `role: tool` 来兼容 OpenAI API 的三方角色限制。

**3. 策略模式 — AgentLoop.buildTools()**

```java
if (!isSubagent) {
    tools.add(taskTool);  // 只有主 Agent 能派发子智能体
}
```

主 Agent 和子 Agent 的工具集不同：子 Agent 不能再次委托（避免无限递归），因此 `task` 工具仅在 `isSubagent == false` 时添加。这是通过条件判断实现的策略模式，而非传统接口抽象。

---

### Q6：项目采用"文件邮箱"（MessageBus）而非共享内存或 RPC 实现 Agent 间通信。请分析这种设计的适用场景和局限性。

**考点**：系统设计取舍、分布式系统思维。

**解答**：

**设计**：每个 Agent 有一个 `.jsonl` 收件箱文件，send 追加一行 JSON，readInbox 读取全部后清空。

**适用场景**：
- 单机多 Agent，不需要服务发现和注册中心
- Agent 之间通信频率低（每轮循环才检查一次）
- 需要持久化保证（消息不丢，即使进程重启）
- 调试友好：可以直接 `cat bob.jsonl` 查看所有消息

**局限性**：
- 不支持实时推送：依赖轮询，延迟至少一个 Agent 循环周期
- 不支持广播/订阅模式：只能点对点
- 并发写入不安全：多个 Agent 同时 send 到同一个收件箱需要额外加锁（当前设计假设一对一通信）
- 文件 I/O 开销：每次 send 是一次文件写入 + flush，高频通信时性能差
- 没有消息确认（ACK）机制：readInbox 清空后如果 Agent 崩溃，消息丢失

**追问**：如果要升级为生产级方案，你会怎么改？
> 引入轻量级消息队列（如嵌入式 Chronicle Queue 或外部 Redis Stream），保留文件落盘能力的同时支持订阅模式和更低的延迟。

---

## 二、并发与线程安全（5 题）

### Q7：BackgroundManager 使用 `synchronized(lock)` + `ArrayList` 而非 `BlockingQueue`，项目文档明确说这是教学意图。请对比两种方案，并说明在什么场景下 synchronized + List 可能更合适？

**考点**：并发原语选择的工程判断力。

**解答**：

| 维度 | `synchronized` + `ArrayList` | `BlockingQueue`（如 LinkedBlockingQueue） |
|------|---------------------------|----------------------------------------|
| 语义 | 通用互斥，手动控制等待/通知 | 专为生产者-消费者设计，自带阻塞语义 |
| 阻塞行为 | 需要手动 `wait()/notify()` | `take()` 自动阻塞，`poll()` 可选超时 |
| 批量操作 | `drainNotifications()` 一次排空所有 | `drainTo()` 同样支持 |
| 代码量 | 更多（需手写锁管理） | 更少 |
| 教学价值 | **高**：暴露并发编程本质 | 低：屏蔽了实现细节 |

**synchronized + List 更合适的场景**：
1. **教学/面试**：展示对并发原语的理解深度
2. **批量消费语义**：消费者总是"一次性排空所有"，而非逐条消费——此时 BlockingQueue 的阻塞语义反而是冗余的
3. **需要非标准操作**：比如需要在同一把锁下同时操作多个数据结构（本项目只有一个队列，但真实场景可能还要更新计数器、状态等）
4. **零依赖要求**：BlockingQueue 本身就是标准库，不存在依赖问题，但如果还需要其他自定义约束，手写更灵活

**关键判断**：当消费者模式是 `drainAll` 而非 `takeOne` 时，`BlockingQueue` 的阻塞能力用不上，`synchronized` 反而更直观。

---

### Q8：守护线程（daemon=true）在这个项目中的生命周期是如何管理的？如果后台任务执行超时，会发生什么？

**考点**：守护线程语义、超时处理、资源清理。

**解答**：

**生命周期**：

```java
Thread bg = new Thread(() -> {
    Process proc = new ProcessBuilder(cmd).start();
    proc.waitFor(300, TimeUnit.SECONDS);  // 最多等 300 秒
    // 把结果写入 notificationQueue
});
bg.setDaemon(true);  // 关键：守护线程
bg.start();
```

守护线程的核心特性：**JVM 不会等待守护线程结束**。当主线程（Agent 循环）退出时，所有守护线程自动终止。

**超时行为**：
- `proc.waitFor(300, TimeUnit.SECONDS)` 返回 `false` 表示超时
- 超时后进程可能还在运行，但线程继续执行并将"超时"结果放入通知队列
- **问题**：超时后调用 `proc.destroyForcibly()` 了吗？从项目代码看，没有显式销毁，这是一个潜在的资源泄漏
- 好在守护线程特性意味着 JVM 退出时进程也会被清理

**潜在问题**：
1. 没有超时后的 `destroyForcibly()` 调用 → 僵尸进程风险
2. 线程没有 `interrupt()` 机制 → 无法从外部取消正在执行的后台任务
3. `notificationQueue` 的 `synchronized(lock)` 在守护线程写和主线程读时正确，但没有任何背压机制

---

### Q9：WorktreeManager 使用 `ThreadLocal<Path>` 存储当前工作目录。请解释 ThreadLocal 的原理、内存泄漏风险，以及为什么本项目用 ThreadLocal 是合理的。

**考点**：ThreadLocal 底层实现、内存管理、适用场景判断。

**解答**：

**原理**：
每个 Thread 对象内部有一个 `ThreadLocalMap`，Key 是 ThreadLocal 的弱引用，Value 是线程私有的值。`ThreadLocal.get()` 本质是 `Thread.currentThread().threadLocalMap.get(this)`。

**本项目使用 ThreadLocal 的合理性**：
- 每个 Agent（子智能体/队友）运行在独立线程
- 每个线程需要一个独立的工作目录（隔离文件操作）
- 不需要在线程间传递这个值
- **完全符合 ThreadLocal 的设计意图：线程级全局变量**

**内存泄漏风险**：
- ThreadLocalMap 的 Entry 的 Key（ThreadLocal 对象）是弱引用，但 Value 是强引用
- 如果线程是线程池中的线程（长期存活），ThreadLocal 对象被 GC 后 Key 变为 null，但 Value 永远不会被回收（因为 Entry 还在 ThreadLocalMap 中）
- **本项目风险低**：因为 Agent 线程不是池化的，完成任务后线程结束，整个 ThreadLocalMap 被 GC

**追问**：如何防止内存泄漏？
- 在 finally 块中 `threadLocal.remove()`
- 本项目应该在 Agent 循环结束时显式调用 `currentWorktree.remove()`

---

### Q10：项目中的 Agent 循环是单线程的，但可以通过 spawn 创建多线程队友。这种模型的并发度瓶颈在哪里？

**考点**：并发模型分析、瓶颈识别。

**解答**：

```
主 Agent（单线程）── spawn ──→ Alice 队友（独立线程）
                              Bob 队友（独立线程）
                              Charlie 队友（独立线程）
```

**瓶颈分析**：

1. **主 Agent 循环是串行的**：每轮必须 `callApi → 等待响应 → 执行工具 → 继续`。LLM API 延迟（通常 1-5 秒）是最大瓶颈

2. **队友之间无协作**：每个队友独立推理，Alice 和 Bob 不能实时通信——只能通过文件邮箱在各自的循环轮次中检查收件箱

3. **ToolHandlers 的静态字段是共享状态**：所有线程共用 `taskMgr`、`bgMgr` 等单例，虽然当前没有显式加锁，但这些管理器的内部状态（如 task JSON 文件的读写）在高并发时是竞争点

4. **文件系统是共享资源**：多个 Agent 同时写文件时，依赖 WorktreeManager 的隔离，但 WorktreeManager 本身的操作（`git worktree add`）是串行的

5. **API 限流**：所有 Agent 共享同一个 API Key，DeepSeek/OpenAI 都有 RPM（每分钟请求数）限制

**优化方向**：
- 主 Agent 的 bash 操作可以使用异步 I/O
- 引入队友间直接通信（共享内存队列）减少轮询延迟
- 对 TaskManager 的文件操作加读写锁

---

### Q11：如果需要让这个项目支持"一个用户同时开启多个独立对话"，你会如何改造线程模型？

**考点**：架构设计、会话隔离。

**解答**：

当前模型：一个 JVM 进程 = 一个 Agent 循环 = 一个对话。

改造方案：

**方案一：Session 级 AgentLoop 实例**
```
每个用户会话 → 新建 AgentLoop 实例 → 独立线程 → 独立 history 列表
```
最轻量，但所有实例共享 ToolHandlers 的静态管理器（可能导致状态串扰）。

**方案二：Session 级 ClassLoader 隔离**
```
每个会话 → 独立 ClassLoader → 独立的静态字段副本
```
过度工程化，不适合本项目。

**方案三（推荐）：实例化改造**
- 将 `ToolHandlers` 的静态字段改为实例字段
- 将 `BackgroundManager`、`TaskManager` 等单例改为会话级实例
- `MessageBus` 的收件箱路径用 sessionId 做命名空间（如 `inbox/session_123/alice.jsonl`）
- 使用 `ExecutorService`（固定线程池）管理所有会话的 Agent 线程
- 每个会话有独立的 `WorktreeManager`（独立的工作目录）

关键改动：
```java
// 之前
static TaskManager taskMgr;  // 全局共享

// 之后
class AgentSession {
    private TaskManager taskMgr;       // 会话级
    private BackgroundManager bgMgr;   // 会话级
    private AgentLoop loop;
    
    void start() { loop.agentLoop(); }
}
```

---

## 三、Java 17 特性应用（4 题）

### Q12：项目中大量使用 `java.net.http.HttpClient`（Java 11+）。请对比它和 Apache HttpClient / OkHttp，并说明本项目选择它的理由。

**考点**：Java 标准库演进、HTTP 客户端选型。

**解答**：

| 特性 | java.net.http.HttpClient | Apache HttpClient 5 | OkHttp 4 |
|------|-------------------------|---------------------|----------|
| JDK 依赖 | 内置，零额外依赖 | 需要 ~3 个 JAR | 需要 OkHttp + Okio |
| HTTP/2 支持 | 原生支持 | 支持 | 支持 |
| 异步支持 | `CompletableFuture` | `FutureCallback` | `Callback` |
| 连接池 | 内置 | 内置 | 内置 |
| 学习成本 | 低（标准库） | 高（配置复杂） | 中 |
| 社区生态 | 中 | 大 | 大 |

**本项目选择理由**：
1. **零额外依赖**：项目仅 2 个依赖的极简哲学
2. **功能完全够用**：只需要发 POST 请求到 `/v1/chat/completions`，不需要高级特性（代理、拦截器链、重试策略等）
3. **CompletableFuture 天然支持**：虽然项目用同步模式 `send()`，但如果将来需要流式响应（SSE），`sendAsync()` 配合 `CompletableFuture` 可以无缝升级

**追问**：如果要支持 streaming（SSE），HttpClient 怎么处理？
```java
HttpResponse<InputStream> resp = client.send(request,
    HttpResponse.BodyHandlers.ofInputStream());
// 逐行读取 resp.body()，解析 SSE 的 "data: " 行
```

---

### Q13：`Text Block`（Java 15+）、`Records`（Java 16+）和 `Pattern Matching for switch`（Java 17+）在这个项目中是否有应用空间？如果有，请举例。

**考点**：Java 新版本特性的实际判断力。

**解答**：

**1. Text Block 的应用空间 — 很大**

当前 System Prompt 是用字符串拼接的，引入 Text Block 可大幅提升可读性：

```java
// 当前做法（难以维护）
String prompt = "You are an AI agent. Available skills:\n" +
                "- wechat-writer\n" + ...

// Text Block 改写
String prompt = """
    You are an AI agent. Available skills:
    - wechat-writer: 公众号全流程写作
    - wechat-director: 公众号封面图/配图生成
    - wewrite: 通用 AI 写作框架
    
    Tools available: bash, read_file, write_file, ...
    """;
```

同样，`buildTools()` 中 Function Calling 的 JSON Schema 定义也可以用 Text Block 嵌入，避免大量 `"\"type\":\"object\""` 的转义地狱。

**2. Records 的应用空间 — 中等**

当前 `Skill` 类是一个简单的数据载体：
```java
// 当前
class Skill {
    String name;
    String description;
    String content;
}

// Record 改写
record Skill(String name, String description, String content) {}
```

另外 `TaskManager` 中的任务对象也适合用 Record。但要注意：Record 是不可变的，如果任务状态需要修改（如 pending → in_progress），则需要重建对象或用 `with` 模式。

**3. Pattern Matching for switch — 有限空间**

当前 `dispatch` 方法匹配的是字符串（tool name），不是类型，所以 Pattern Matching for switch 的直接应用场景有限。但如果将来 `dispatch` 改为匹配 ToolCall 的不同子类型，则可以：
```java
return switch (toolCall) {
    case BashCall c -> runBash(c);
    case ReadCall c -> runRead(c);
    case McpCall c -> dispatchMcp(c);
};
```

---

### Q14：项目使用 `ProcessBuilder` 执行 Shell 命令。在 Java 中，`ProcessBuilder` vs `Runtime.exec()` 有什么区别？120 秒超时是怎么实现的？

**考点**：进程管理 API 掌握程度。

**解答**：

| 特性 | ProcessBuilder | Runtime.exec() |
|------|---------------|----------------|
| API 风格 | Builder 模式，链式调用 | 重载方法，参数拼接 |
| 环境变量 | `environment()` 返回 Map，可修改 | 需要传入字符串数组 |
| 工作目录 | `directory(Path)` | 需要传入 File 参数 |
| 错误重定向 | `redirectErrorStream(true)` | 需手动处理 |
| 管道 | `startPipeline()` 支持 | 不支持 |

**120 秒超时实现**：

```java
Process proc = new ProcessBuilder(cmd).start();
boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
if (!finished) {
    proc.destroyForcibly();  // 强制终止
    return "Error: command timed out";
}
```

关键 API：`Process.waitFor(long, TimeUnit)` — Java 8 引入，比旧版 `waitFor()` 增加了超时语义。超时后必须调用 `destroyForcibly()` 清理子进程，否则会成为僵尸进程。

**追问**：`destroy()` 和 `destroyForcibly()` 的区别？
- `destroy()`：发送 SIGTERM（优雅终止），进程可以捕获并做清理
- `destroyForcibly()`：发送 SIGKILL（强制终止），进程无法捕获。如果 `destroy()` 后进程未退出，`destroyForcibly()` 是唯一切实的终止手段

---

### Q15：ContextCompactor 的 Token 估算使用 `history.toString().length() / 4`。这个方法为什么是"简化版"？生产环境中应该如何做？

**考点**：对 Token 化原理的理解。

**解答**：

**为什么是简化版**：
- Token 不是字符：英文中 1 token ≈ 4 字符（经验值），但中文 1 字符 ≈ 1-2 token
- 不同模型的 Tokenizer 不同：DeepSeek 用的是自己的 BPE Tokenizer，英文/代码/中文的 token 比差异很大
- JSON 结构开销：`history.toString()` 包含大量 JSON 字段名（`"role":"user"` 等），这些在模型眼中 token 数不同

**生产环境方案**：
1. **使用专门的 Token 计数库**：如 `tikToken`（OpenAI 开源）或 `HuggingFace tokenizers`
2. **实现对应模型的 Tokenizer**：DeepSeek 基于 Llama 的 BPE，可以用 `llama-tokenizer-js` 的 Java 移植
3. **调用 API 的 token 计数端点**：OpenAI 有专门的 `/tiktoken` 端点
4. **保守策略**：用 `length / 3` 而非 `/4` 来预留余量，宁可提前压缩也不触发 API 的超长截断

**本项目的启示**：Token 估算不精确不是 bug，只要剩余 Token 足够让模型完成任务即可。"提前压缩"比"精确计算"更重要。

---

## 四、系统设计与协议（5 题）

### Q16：项目中的上下文压缩（S06）方案是"保留 system prompt + 最后 4 条 + 删除中间"。这个策略有什么问题？你会如何改进？

**考点**：系统设计思维、对 LLM 工作原理的理解。

**解答**：

**当前方案的问题**：
1. **中间信息完全丢失**：如果用户在对话开始时说了一个关键约束（"只能用 Python 3.8 语法"），压缩后这个约束会丢失
2. **硬编码 4 条**：可能保留太少（丢失重要上下文），也可能保留太多（未充分压缩）
3. **没有摘要机制**：更好的做法是让 LLM 自己总结中间内容，然后替换而非丢弃
4. **压缩前只保存了 transcript**：如果想恢复被压缩的内容，需要从磁盘读取完整记录，但压缩后的对话已经不可逆

**改进方案**：

**方案一：结构化摘要（推荐）**
```
每 10 轮对话 → 让 LLM 输出结构化摘要：
{ "key_decisions": [...], "constraints": [...], "files_modified": [...], "open_issues": [...] }
→ 将摘要注入 system prompt → 清空中段对话
```

**方案二：滑动窗口 + 重要性评分**
- 不只是保留最后 N 条，而是根据工具调用类型保留关键操作：
  - `write_file` 的结果 → 高重要性，保留
  - `bash ls` 的结果 → 低重要性，可丢弃
  - `task` 子智能体结果 → 保留摘要

**方案三：分层记忆**
```
Working Memory：最近 5 轮对话（全量）
Short-term Memory：LLM 自动生成的任务摘要
Long-term Memory：文件系统持久化的完整 transcript
```

---

### Q17：TaskManager 中，任务完成时自动清理依赖（`clearDependency`）。如果任务数量达到 10,000 个，每次遍历所有任务 JSON 文件的 O(n) 操作会成为瓶颈吗？如何优化？

**考点**：性能优化、数据结构选择、数据库思维。

**解答**：

**当前实现**：
```java
void clearDependency(int completedId) {
    for (File taskFile : tasksDir.listFiles()) {  // O(n)
        JsonNode task = mapper.readTree(taskFile);
        ArrayNode blockedBy = task.get("blockedBy");
        // 遍历 blockedBy，移除 completedId
        // 重新写回文件
    }
}
```

每次完成任务 → 遍历所有 N 个任务 → 读取 + 解析 JSON + 回写。当 N=10,000 时，单次 `clearDependency` 约需 I/O 10,000 次，不可接受。

**优化方案**：

**方案一：反向索引（内存索引）**
```java
// 维护一个 Map：blockedTaskId → List<依赖它的任务ID>
Map<Integer, List<Integer>> blockedByIndex;

completeTask(id) {
    List<Integer> dependents = blockedByIndex.get(id);
    for (int depId : dependents) {
        // 只更新受影响的那些任务文件
        removeFromBlockedBy(depId, id);
    }
    blockedByIndex.remove(id);  // 清理索引
}
```
将复杂度从 O(n) 降到 O(依赖数)，通常是 O(1)。

**方案二：换存储引擎**
- 任务数上万时，JSON 文件不再合适。换 SQLite（单文件嵌入式数据库）：
```sql
CREATE TABLE tasks (id, subject, status, ...);
CREATE TABLE dependencies (task_id, blocked_by_task_id);
-- 清理依赖：DELETE FROM dependencies WHERE blocked_by_task_id = ?;
```

**方案三：延迟清理**
- 不主动清理 `blockedBy`，而是在 `task_start` 时检查依赖状态——被依赖的任务如果已完成，自动忽略

---

### Q18：MCP Client 与多个外部 MCP Server 通信时，如何防止工具名冲突？项目中的 `serverName__` 前缀方案的优缺点？

**考点**：命名空间设计、API 设计。

**解答**：

**项目方案**：
```java
// McpClient 注册时
toolName = serverName + "__" + originalName;
// 例如：filesystem__read_file, github__read_file
```

**优点**：
- 简单直观，一行代码解决
- 调试友好：一看名字就知道来自哪个 Server
- LLM 能自然理解前缀含义（不需要额外 schema）

**缺点**：
1. **分隔符风险**：如果 Server 名或工具名本身包含 `__`，会产生歧义。应该用更罕见的分隔符或 URL 风格的命名空间（如 `server://tool`）
2. **前缀污染**：如果 LLM 调用 `filesystem__read_file`，McpClient 需要先去掉前缀找到真正的工具名 → 有一定字符串处理开销
3. **前缀信息冗余**：每次 Function Calling 的 tool name 都带前缀，占用 Token

**替代方案**：
- **结构化命名空间**：`{"namespace": "filesystem", "tool": "read_file"}` — 用 JSON 对象的嵌套结构
- **URL 风格**：`filesystem/read_file` — 用层级结构
- **MCP 标准推荐**：保持原名，用 `serverName` 字段区分

---

### Q19：ProtocolTracker 用同一个 FSM 处理 Shutdown 和 Plan Review 两个协议。这两个协议的状态流转有何不同？为什么可以复用？

**考点**：状态机设计的抽象能力。

**解答**：

**两个协议的状态流转**：

Shutdown（Leader → Teammate）：
```
Leader 发起 shutdown_request → Teammate pending → Teammate 队友循环中检测到
→ Teammate 自主决定 → shutdown_response(approved/rejected) → Leader 收到通知
```

Plan Review（Teammate → Leader）：
```
Teammate 写操作前 → plan_submit → Leader pending → Leader 主循环收到通知
→ Leader 调用 shutdown_response(approved/rejected) → Teammate 收到通知
```

**相同点（为什么可以复用）**：
- 都是两方协议：发起方 + 响应方
- 状态空间完全一致：`pending → approved | rejected`
- 都通过 MessageBus 传递消息
- 都需要超时处理（pending 状态太久未响应）

**不同点**：
- 发起方不同：Shutdown 是 Leader 发起，Plan Review 是 Teammate 发起
- 语义不同：Shutdown 是降权（队友减少能力），Plan Review 是授权（队友获得临时写权限）

**抽象的价值**：ProtocolTracker 本质上是一个 **"请求-响应式两态协议引擎"**，任何符合 `发起请求 → 等待响应(approve/reject) → 通知结果` 的协议都可以复用。如果将来加入 PR Review、Merge Request 等协议，只需新增协议类型枚举，FSM 逻辑不用改。

---

### Q20：TaskManager 同时承担 Git Worktree 的分配和回收。这种耦合是否合理？如果将来要支持非 Git 的隔离方案（如 Docker），如何解耦？

**考点**：关注点分离、依赖倒置。

**解答**：

**当前耦合**：
```java
// TaskManager 中
case "in_progress" → WorktreeManager.allocate(taskId)
case "cancelled"   → WorktreeManager.release(taskId)
```

TaskManager 不仅管理任务状态，还知道"工作空间隔离"的存在，是两个不同的关注点耦合在一起。

**问题**：
- 如果将来换 Docker 隔离 → 需要改 TaskManager
- 如果有些任务不需要隔离 → TaskManager 没有相关判断逻辑
- 单元测试 TaskManager 时不得不 mock WorktreeManager

**解耦方案：依赖倒置**

```java
// 定义隔离策略接口
interface WorkspaceProvider {
    Path allocate(int taskId);
    void release(int taskId);
}

// Git Worktree 实现
class GitWorktreeProvider implements WorkspaceProvider { ... }

// Docker 实现
class DockerWorkspaceProvider implements WorkspaceProvider { ... }

// TaskManager 只依赖接口
class TaskManager {
    private WorkspaceProvider workspace;  // 构造注入
    
    void startTask(int id) {
        task.status = "in_progress";
        task.worktree = workspace.allocate(id);  // 无需知道实现细节
    }
}
```

通过策略模式 + 依赖注入，TaskManager 完全不知道隔离机制的具体实现，符合**开闭原则**和**依赖倒置原则**。

---

## 五、安全性（3 题）

### Q21：ToolHandlers 中 `safePath()` 方法的路径逃逸检测是如何工作的？存在绕过的可能吗？

**考点**：安全审计意识、路径遍历攻击。

**解答**：

**safePath() 的逻辑（推断）**：
```java
Path safePath(Path userPath) {
    Path resolved = currentWorktree.resolve(userPath);
    Path normalized = resolved.normalize();  // 消除 ../
    if (!normalized.startsWith(currentWorktree)) {
        throw new SecurityException("Path escape detected");
    }
    return normalized;
}
```

**攻击向量分析**：

| 攻击方式 | payload | 能否绕过 | 说明 |
|---------|---------|---------|------|
| 目录遍历 | `../../../etc/passwd` | 不能 | normalize() 消除 `..` |
| 符号链接 | `link → /etc/passwd` | **能** | startsWith 只检查路径前缀，不跟踪符号链接 |
| 大小写混淆 | `..\\..\\WINDOWS` (Windows) | 可能 | Windows 大小写不敏感，但 `startsWith` 可能区分大小写 |
| 路径分隔符混淆 | `..\..\etc/passwd` (Windows) | 不能 | resolve() 会规范化分隔符 |
| 绝对路径注入 | `/etc/passwd` | 不能 | resolve() 会把绝对路径拼到 worktree 下 |

**最大的漏洞**：**符号链接绕过**。如果 worktree 内有人创建了指向外部的符号链接，`startsWith` 检查完全无效。

**修复**：
```java
// 在 normalize() 后检查
Path realPath = normalized.toRealPath();  // 解析所有符号链接
if (!realPath.startsWith(currentWorktree.toRealPath())) {
    throw new SecurityException("Path escape via symlink");
}
```

---

### Q22：项目中 bash 命令有一个 `BLOCKED` 黑名单。黑名单 vs 白名单的安全哲学差异是什么？在这个 Agent 场景下哪种更合适？

**考点**：安全策略设计思维。

**解答**：

**黑名单**（当前方案）：
```
禁止：rm -rf /, sudo, shutdown, reboot, > /dev/
允许：除黑名单外的所有命令
```

**白名单**（替代方案）：
```
允许：ls, cat, grep, find, python, node, git, echo, mkdir, ...
禁止：除白名单外的所有命令
```

**Agent 场景分析**：

这个 Agent 的核心价值是**帮开发者执行任意合理的 Shell 命令**。如果用白名单：
- 无法预测所有合法命令（npm、pip、curl、docker、make...）
- 每遇到一个新工具就要更新白名单 → Agent 失去灵活性
- LLM 本身有一定判断力，大概率不会执行恶意命令

**结论**：黑名单在这个场景是**务实的选择**，因为：
1. 攻击面有限：Agent 是本地运行的 CLI 工具，不是对外服务
2. 灵活性 > 绝对安全：用户需要 Agent 执行任意合法命令
3. LLM 充当了第一道防线

**但需要补充**：
- 对写操作命令（`rm`, `mv`, `> overwrite`）在执行前请求用户确认
- 对网络操作命令（`curl`, `wget`）限制目标域名白名单（防止数据外泄）

---

### Q23：如果把这个 Agent 部署为 SaaS 多租户服务，当前的安全模型有哪些致命缺陷？

**考点**：从单机到云的安全思维跃迁。

**解答**：

当前安全模型是**单用户信任模型**（Agent 运行在用户自己的机器上），一旦多租户化：

| 缺陷 | 风险等级 | 说明 |
|------|---------|------|
| 无租户隔离 | 🔴 致命 | 所有租户共享同一个文件系统和进程空间 |
| bash 无沙箱 | 🔴 致命 | 用户可以执行任意系统命令，访问其他租户数据 |
| 文件邮箱无命名空间 | 🔴 致命 | `bob.jsonl` 文件名冲突 |
| API Key 共享 | 🔴 致命 | 所有租户消耗同一个 API Key |
| 无认证/授权 | 🔴 致命 | 任何人可以访问任何 Agent 会话 |
| 无资源配额 | 🟠 高危 | 一个租户可以耗尽所有 CPU/内存/磁盘 |
| 无审计日志 | 🟠 高危 | 无法追踪谁做了什么 |

**改造要点**：
1. **容器化**：每个租户会话运行在独立的 Docker 容器或 gVisor 沙箱中
2. **API Key 池**：每个租户绑定自己的 API Key，或使用 API 代理做计费和限流
3. **认证层**：添加 JWT/OAuth2 认证，每个请求验证租户身份
4. **资源限制**：cgroups 限制 CPU/内存，磁盘 quota 限制存储
5. **审计**：所有 Tool 调用记录到不可篡改的审计日志

---

## 六、综合应用题（2 题）

### Q24：如果让你用 15 分钟向面试官介绍这个项目，请组织一个 3 分钟的 oral presentation 框架。

**考点**：技术沟通能力、归纳总结。

**参考答案框架**：

> "这是一个全 Java 17 标准库实现的 AI Agent 框架，仅 2 个外部依赖：Jackson 做 JSON、dotenv 加载配置。
>
> 核心设计是一条永不改变的 Agent 循环——LLM 通过 Function Calling 自主决定何时调工具、何时输出答案，代码只负责循环执行 LLM 的决策。从 S01 到 S13 逐章叠加能力：子智能体委托、两层技能缓存、上下文压缩、任务依赖图、多智能体文件邮箱通信、FSM 协议追踪、Git Worktree 并发隔离，最后是 MCP 协议支持。
>
> 技术亮点有三：第一，极简依赖体现了对 Java 标准库 HttpClient、ProcessBuilder、NIO 的深度掌握；第二，9 种设计模式自然融入而非强行套用；第三，MCP 的实现严格遵循 JSON-RPC 2.0 over stdio 规范，与 Anthropic 官方标准兼容。
>
> 如果升级为生产级，我会优先改造三个点：静态字段改为依赖注入、bash 执行加入 seccomp 沙箱、任务存储从 JSON 文件迁移到 SQLite。"

---

### Q25：【场景题】用户说"帮我把 src 目录下所有的 `.java` 文件中的 `System.out.println` 替换为 `log.info`"。请描述这个任务在 Agent 系统中完整的执行过程，从用户输入到最终完成。

**考点**：对 Agent 系统的全链路理解。

**解答**：

**完整执行过程**：

```
第 1 轮
├── 用户输入："帮我把 src 目录下所有的 .java 文件中的 System.out.println 替换为 log.info"
├── callApi() → LLM 决策：需要先找到所有 .java 文件
├── tool_call: bash "find src -name '*.java' -type f"
└── 结果：50 个文件列表

第 2 轮
├── 历史包含：用户需求 + 50 个文件列表
├── callApi() → LLM 决策：文件太多，需要委托子智能体并行处理
├── tool_call: task "对 files_001~025 做 println → log.info 替换"
├── tool_call: task "对 files_026~050 做 println → log.info 替换"
└── 结果：两个子智能体完成，返回修改文件列表

第 3 轮
├── 历史包含：所有结果
├── callApi() → LLM 决策：验证替换是否完整
├── tool_call: bash "grep -r 'System.out.println' src/ | wc -l"
└── 结果：0 处残留

第 4 轮
├── callApi() → LLM 决策：任务完成，输出总结
└── finish_reason: "stop"
    输出："已完成。50 个文件中的 System.out.println 已全部替换为 log.info。修改的文件包括：..."
```

**关键洞察**：
- LLM 在第 2 轮自动选择了并行策略（两个 task 同时派发），这体现了 Function Calling 的智能——代码不需要写"什么时候并行"，LLM 会根据上下文判断
- 第 3 轮的验证步骤（grep 确认）也是 LLM 自主决策的，代码循环没有硬编码任何验证逻辑
- 整个过程中 AgentLoop.java 的代码没有改变一个字——完全由 LLM 驱动

---

## 附录：快速自检清单

面试前用这些问题快速自查：

- [ ] 能画出 AgentLoop 的循环流程图吗？
- [ ] 能说出 Function Calling 和普通 API 调用的区别吗？
- [ ] 能解释 ThreadLocal 的内存泄漏原理和本项目的安全性吗？
- [ ] 能对比 synchronized + List 和 BlockingQueue 的取舍吗？
- [ ] 能说出项目中至少 3 个设计模式及其实际应用吗？
- [ ] 能分析文件邮箱通信的设计取舍吗？
- [ ] 能解释 MCP 协议的 JSON-RPC 格式吗？
- [ ] 能指出 safePath() 的符号链接绕过漏洞吗？
- [ ] 能说明上下文压缩为什么要保留最后 N 条而非最早 N 条吗？
- [ ] 能从单机到 SaaS 多租户分析安全模型的差异吗？

---

> **面试建议**：不要背诵答案，要理解每个设计背后的 **"为什么"**。面试官最看重的是：你面对一个技术决策时，能说清楚取舍而不是盲从某种模式。