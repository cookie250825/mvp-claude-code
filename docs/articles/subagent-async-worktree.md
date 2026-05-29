@[TOC]

---

## 引子

你让 AI 同时分析 SQL 注入和 XSS 漏洞。它启动了 2 个子 Agent——然后第二个**干等了 30 秒**。

因为代码里 `SubagentRunner.run()` 是同步阻塞的。

我花了一下午把子 Agent 从排队改成并行，又用 Git 的一个冷门功能解决了多 Agent 同时写文件的冲突。整个过程比我想象的顺利得多——因为架构一开始就把线程安全考虑进去了。

---

## 一、原来的子 Agent 为什么是"假并行"

先看原来的代码：

```java
// TaskTool.execute() — 第 77 行
String result = SubagentRunner.run(ai, dispatcher, registry, config, 
                                    prompt, maxRounds, type);
return ToolResult.success(result);
```

`SubagentRunner.run()` 内部是一个 `for` 循环，最多跑 10 轮 LLM 调用。**这个方法不返回，父 Agent 就没法往下走。**

即便父 LLM 一次返回了 3 个 `task()` 调用，AgentLoop 的处理逻辑也是：

```
for each tool call:
    tools.execute(req)     ← task() 在这里面卡住了
    第二个 task() 要等第一个跑完才能进循环
```

**本质：串行排队，不是并行。**

---

## 二、怎么改成真正并行

新建一个 `SubagentManager`，核心就一个固定线程池：

```java
private final ExecutorService pool = Executors.newFixedThreadPool(4);
```

`submit()` 方法把子 Agent 提交到线程池，**立刻返回任务 ID**：

```java
public String submit(...) {
    String id = UUID.randomUUID().toString().substring(0, 8);
    pool.submit(() -> {
        String result = SubagentRunner.run(...);  // 在线程池的线程里跑
        notifications.add(完成通知);               // 跑完塞进队列
    });
    return id;  // 立刻返回，不等待
}
```

`TaskTool.execute()` 从同步等结果变成：

```java
String id = subagentManager.submit(...);
return ToolResult.success("子 Agent [" + id + "] 已启动，完成后自动通知。");
```

父 Agent 的循环每轮调 `drain()` 扫一遍通知队列，有结果就注入对话历史：

```
父 LLM 调用 task() → "已启动 [a1]"
下一轮 drain() → "子 Agent [a1] 完成: 发现 3 处 SQL 注入..."
父 LLM 看到结果 → 决定下一步
```

**效果：**

> 父 Agent 不阻塞。同一轮 LLM 返回 3 个 task()，三个子 Agent 同时启动。

---

## 三、只读类型无限制并行，GENERAL 怎么办

问题来了：`EXPLORE` / `PLAN` / `VERIFICATION` 这三种只读的，10 个同时跑都没事。但 `GENERAL` 是会写文件的：

```
GENERAL-A: file(write, "UserService.java", "版本A")
GENERAL-B: file(write, "UserService.java", "版本B")
→ 后写的覆盖先写的，A 的修改静默丢失
```

这就是为什么要用 **Git worktree**。

---

## 四、Git Worktree：冷门但正好解决这个问题的功能

`git worktree` 能在一个仓库里创建**多个独立的工作目录**：

```
git worktree add --detach /tmp/agent-worktrees/wt-a1
```

这条命令干了什么：

- 在 `/tmp/agent-worktrees/wt-a1` 创建一个完整的项目副本
- `--detach` 表示不创建新分支，用 detached HEAD 模式
- 这个目录**完全独立**——有自己的文件、自己的 `.git` 元数据
- 创建速度很快（秒级），因为它共享底层 git 对象，不是 `cp -r`

每个需要写文件的 GENERAL 子 Agent，启动前先拿一个独立 worktree：

```
GENERAL-A: /tmp/agent-worktrees/wt-a1/UserService.java ── 写这里
GENERAL-B: /tmp/agent-worktrees/wt-b2/UserService.java ── 写这里
父 Agent:  ./UserService.java                            ── 原文件不动
```

**三个互不干扰。** 改同一个文件也不会互相覆盖。

子 Agent 完成后，通过 `git diff` 把修改拉回来：

```java
String diff = worktreeManager.getDiff(worktreePath);
// git -C /tmp/wt-a1 diff → 所有文件改动
```

父 LLM 在通知里看到完整的 git diff，**由它决定要不要把改动合入主分支**。子 Agent 只管"改"，父 Agent 负责"判"。

---

## 五、ThreadLocal：一句话让 FileTool 指向正确的 worktree

worktree 有了，但 `FileTool` 有个大问题——它的工作目录是**静态字段**：

```java
// FileTool.java
private static Path WORKSPACE;  // 启动时设置，之后不变
```

所有线程都共享这一个 `WORKSPACE`。子 Agent 在 worktree 里调 `file(write, "UserService.java", ...)`，怎么保证它写到 `/tmp/wt-a1/` 而不是主目录？

**加一行 ThreadLocal：**

```java
private static final ThreadLocal<Path> WORKSPACE_OVERRIDE = new ThreadLocal<>();

// 子 Agent 启动前
FileTool.setWorkspaceOverride(worktreePath);   // 当前线程 → worktree

// safePath() 里
Path workspace = WORKSPACE_OVERRIDE.get();
if (workspace == null) workspace = WORKSPACE;  // 没人设就用默认

// 子 Agent 结束后（finally 块）
FileTool.clearWorkspaceOverride();             // 当前线程恢复
```

**为什么 ThreadLocal 而不是传参？**

因为 `FileTool` 的调用链路是：`LLM → ToolDispatcher → FileTool.execute()`。中间没有任何地方让你"顺便传一个 workspace 进去"。ThreadLocal 是唯一不破坏现有架构的注入方式。

**线程安全保证：** ThreadLocal 的 set 只影响当前线程，线程 1 设了 `/tmp/wt-a1` 不影响线程 2 的 `/tmp/wt-b2`。比锁优雅得多。

---

## 六、线程安全全景

整个子 Agent 系统从头到尾不需要一把锁，原因：

| 资源 | 安全机制 |
|------|---------|
| `SubagentRunner` 内部状态 | 全局部变量，每次调用 new 一份 |
| `AIService`（API 调用） | 无状态 HTTP 客户端，天然线程安全 |
| `ToolDispatcher / ToolRegistry` | `without()` 返回新副本，不共享 |
| `FileTool` workspace | `ThreadLocal` 线程级隔离 |
| 通知队列 | `ConcurrentLinkedQueue` |
| 运行任务跟踪 | `ConcurrentHashMap + AtomicInteger` |

**唯一的共享是 DeepSeek API Key**——但两个 HTTP 请求之间不存在竞争，就像两台电脑用同一个 WiFi。

---

## 七、Worktree 失败怎么办

worktree 可能创建失败——不是 git 仓库、有未提交的修改、磁盘满了。GENERAL 子 Agent 拿不到 worktree 时，**不降级到主 workspace**：

```java
if (worktreePath == null) {
    // 直接报错，不跑子 Agent
    notifications.add(错误通知: "git worktree 创建失败，请检查...");
    return;
}
```

父 LLM 看到错误后有三种选择：
1. `bash("git status")` 检查状态，修好后重试
2. 改用 `task(type="explore")` 做只读分析
3. 自己动手改，不用子 Agent

**关键原则：写操作拿不到隔离环境就拒绝执行。宁可不干活，不能瞎干活。**

---

## 总结

1. **异步不阻塞** — 子 Agent 提交到线程池后立刻返回，父 Agent 继续循环
2. **只读无限制并行** — Explore/Plan/Verification 纯读不冲突
3. **GENERAL Worktree 隔离** — 每个写操作子 Agent 跑在独立 git worktree 里
4. **ThreadLocal 防串** — 一句话让 FileTool 指向正确目录，线程级隔离
5. **失败不降级** — worktree 拿不到就报错，绝不动主 workspace

整轮改造 4 个新文件 + 6 个修改文件，核心改动不到 300 行。

> 点赞收藏，面试能讲 10 分钟。

---

## 封面图生成提示词

极简数字画风，深蓝色背景，中央一个立方体被分割成三个独立的小立方体，每个小立方体用不同的霓虹色线条勾勒（青/紫/橙）。立方体之间用虚线箭头连接，表示异步并行。画面底部一行白色极细字体 "Subagent Parallel + Worktree Isolation"。无阴影、无渐变、纯矢量风格。
