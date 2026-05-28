package com.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 核心循环 — 整个项目的"心脏"。
 *
 * <h3>一句话概括</h3>
 * 拿到用户输入 → 进入循环 → 流式调用 LLM → 如果 LLM 说要调工具就确认后并行执行 →
 * 工具结果写回对话历史 → 继续下一轮，直到 LLM 返回纯文本 或 触发 token/轮次上限。
 *
 * <h3>为什么是手写循环</h3>
 * LangChain4j 有 AiServices 可以自动管理工具调用循环。但它是黑盒——
 * 你不知道工具什么时候被调、异常怎么处理、确认怎么插入。
 * 手写循环让你在每一步都能插自己的逻辑。
 *
 * <h3>每轮迭代的七个步骤（顺序不能变）</h3>
 * 1. background drain  — 收后台任务完成通知
 * 2. microCompact       — 零成本裁剪旧工具输出
 * 3. autoCompact?       — token 超了？LLM 摘要
 * 4. streamingChat      — 流式调 LLM，失败不崩溃
 * 5. 工具调用？          — 先确认再并行执行，结果写回 history
 * 6. Todo nag?          — 3 轮没更新 Todo 就提醒
 * 7. Token 预算检查     — 水位超 90%？先压缩，压不下来熔断
 *
 * <h3>Token 预算制（对标 Claude Code）</h3>
 * 不限轮次——限上下文水位。maxRounds=0 时完全靠 token 预算：
 * 1. 用量 < 90% → 继续
 * 2. 用量 90%-95% → 触发 autoCompact，压缩后继续
 * 3. 用量 > 95% 且连续压缩 3 次失败 → 熔断，提示用户拆任务
 * maxRounds>0 时轮次上限作为辅助保险丝。
 */
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** 连续压缩失败次数上限（对标 Claude Code MAX_CONSECUTIVE_FAILURES=3） */
    private static final int MAX_CONSECUTIVE_COMPACT_FAILS = 3;

    private final AIService ai;
    private final ToolDispatcher tools;
    private final CompactService compact;
    private final ContextBuilder ctx;
    private final com.agent.tools.TodoManager todoManager;
    private final BackgroundManager bgManager;
    private final SubagentManager subagentManager;
    private final ToolExecutionConfirmation confirmation;

    /** 0 = 不限轮次（靠 token 预算），>0 = 轮次上限 */
    private final int maxRounds;
    /** 上下文窗口大小（模型限制，如 128K） */
    private final int maxContextTokens;
    /** 水位危险比例（0.9 = 用量达 90% 触发压缩/熔断） */
    private final double dangerRatio;

    private List<ChatMessage> history = new ArrayList<>();
    /** 连续压缩失败计数器（用于熔断判断） */
    private int consecutiveCompactFails = 0;
    /** 已发过熔断警告？下一轮再超就硬终止 */
    private boolean meltdownWarningIssued = false;

    /**
     * @param ai               AI 服务
     * @param tools            工具分发器
     * @param compact          三层上下文压缩
     * @param ctx              请求拼装器
     * @param todoManager      Todo 状态管理
     * @param bgManager        后台任务管理器
     * @param confirmation     工具确认组件
     * @param maxRounds        最大轮次（0=不限），默认 0
     * @param maxContextTokens 上下文窗口大小，默认 128000
     * @param dangerRatio      危险水位比例，默认 0.9
     */
    public AgentLoop(AIService ai, ToolDispatcher tools, CompactService compact,
                     ContextBuilder ctx, com.agent.tools.TodoManager todoManager,
                     BackgroundManager bgManager, SubagentManager subagentManager,
                     ToolExecutionConfirmation confirmation,
                     int maxRounds, int maxContextTokens, double dangerRatio) {
        this.ai = ai;
        this.tools = tools;
        this.compact = compact;
        this.ctx = ctx;
        this.todoManager = todoManager;
        this.bgManager = bgManager;
        this.subagentManager = subagentManager;
        this.confirmation = confirmation;
        this.maxRounds = maxRounds;
        this.maxContextTokens = maxContextTokens;
        this.dangerRatio = dangerRatio;
    }

    /**
     * 初始化 — 目前只打日志，预留扩展点（如预热模型、预加载记忆）
     */
    public void init() {
        log.info("AgentLoop initialized (streaming mode)");
    }

    /**
     * 处理一条用户输入 — 整个 Agent 的唯一入口。
     *
     * <h3>执行流程</h3>
     * 1. 用户消息追加到 history
     * 2. 进入最多 30 轮的 while 循环
     * 3. 每轮：排空后台通知 → microCompact → autoCompact? → 流式调 LLM → 工具确认执行
     * 4. LLM 返回纯文本 → 返回给用户
     * 5. 达到 30 轮 → 返回超限提示
     *
     * @param input 用户在终端敲的那行字
     * @return LLM 的最终回复文本
     */
    public String process(String input) {
        // 第一步：用户消息进历史
        history.add(UserMessage.from(input));
        int roundsWithoutTodo = 0;
        consecutiveCompactFails = 0;
        meltdownWarningIssued = false;

        for (int round = 1; ; round++) {
            // ---- 步骤 1: 排空后台任务通知 ----
            if (bgManager != null) {
                java.util.List<java.util.Map<String, Object>> notifs = bgManager.drain();
                if (!notifs.isEmpty()) {
                    StringBuilder txt = new StringBuilder("<background-results>\n");
                    for (var n : notifs) {
                        txt.append("[").append(n.get("task_id")).append("] ")
                           .append(n.get("status")).append(": ").append(n.get("result")).append("\n");
                    }
                    txt.append("</background-results>");
                    history.add(UserMessage.from(txt.toString()));
                }
            }

            // ---- 步骤 1b: 排空子 Agent 结果 ----
            if (subagentManager != null) {
                java.util.List<java.util.Map<String, Object>> subNotifs = subagentManager.drain();
                if (!subNotifs.isEmpty()) {
                    StringBuilder txt = new StringBuilder("<subagent-results>\n");
                    for (var n : subNotifs) {
                        txt.append("子 Agent [").append(n.get("task_id")).append("]")
                           .append(" (").append(n.get("type")).append(")")
                           .append(": ").append(n.get("status")).append("\n")
                           .append(n.get("result")).append("\n");
                    }
                    txt.append("</subagent-results>");
                    history.add(UserMessage.from(txt.toString()));
                }
                // 提示父 Agent 有子 Agent 在执行中
                if (subagentManager.hasRunning()) {
                    history.add(UserMessage.from("<reminder>有 " + subagentManager.runningCount()
                        + " 个子 Agent 正在后台执行，请继续当前工作或等待结果。</reminder>"));
                }
            }

            // ---- 步骤 2: microCompact — 零成本裁剪旧工具输出 ----
            // 不删任何消息，只把太长的工具输出截短（>2000 → 500 字符）
            history = compact.microCompact(history);

            // ---- 步骤 3: autoCompact — token 超阈值时 LLM 摘要 ----
            // 大多数轮次不会触发（阈值通常设 50000 token），只在长对话时介入
            if (compact.shouldCompact(history)) {
                history = compact.compact(history);
            }

            // ---- 步骤 4: LLM 调用 — 流式输出 ----
            // CompletableFuture 桥接：流式 API 是异步的，但我们用 future.get() 阻塞等它完
            ChatRequest request = ctx.build(history);
            AiMessage aiMsg;
            try {
                CompletableFuture<AiMessage> future = ai.streamingChat(
                    request.messages(),
                    request.toolSpecifications(),
                    token -> System.out.print(token)  // 每个 token 实时打印
                );
                aiMsg = future.get();  // 阻塞等待流式完成
            } catch (Exception e) {
                // 步骤 4b: 错误注入 — API 报错不崩溃，包装成消息让 LLM 下轮看到
                log.warn("LLM call failed, injecting error: {}", e.getMessage());
                history.add(UserMessage.from("<error>LLM call failed: " + e.getMessage()
                    + ". Please retry or change approach.</error>"));
                continue;  // 跳过本轮，进入下一轮
            }

            // ---- 步骤 5: 判断响应类型 ----
            // 纯文本 = LLM 觉得任务完成了，直接返回
            if (!aiMsg.hasToolExecutionRequests()) {
                history.add(aiMsg);
                return aiMsg.text();
            }

            // 有工具调用 = LLM 需要干活
            history.add(aiMsg);
            System.out.println();  // 流式输出结束后换行

            // ---- 步骤 5a: 工具确认（串行 — 用户需逐个看） ----
            boolean usedTodo = false;
            try {
            List<ToolExecutionRequest> approvedReqs = new ArrayList<>();
            for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                if (!confirmation.ask(req)) {
                    history.add(ToolExecutionResultMessage.from(req, "User denied tool execution"));
                    System.out.println("   ⏭️  已跳过");
                    continue;
                }
                approvedReqs.add(req);
            }

            // ---- 步骤 5b: 工具执行（并行 — 互不依赖的工具同时跑） ----
            if (!approvedReqs.isEmpty()) {
                List<ToolExecutionRequest> reqsInOrder = new ArrayList<>();
                List<CompletableFuture<com.agent.tools.ToolResult>> futures = new ArrayList<>();
                for (ToolExecutionRequest req : approvedReqs) {
                    reqsInOrder.add(req);
                    futures.add(CompletableFuture.supplyAsync(() ->
                        tools.execute(req)
                    ));
                }

                for (int i = 0; i < futures.size(); i++) {
                    ToolExecutionRequest req = reqsInOrder.get(i);
                    try {
                        com.agent.tools.ToolResult result = futures.get(i).get();
                        String content = result.getContent();
                        if (content == null || content.isBlank()) {
                            content = result.isSuccess() ? "[empty]" : "[error]";
                        }
                        history.add(ToolExecutionResultMessage.from(req, content));
                        if ("TodoWrite".equals(req.name())) usedTodo = true;
                    } catch (Exception e) {
                        log.warn("Tool {} crashed: {}", req.name(), e.getMessage());
                        history.add(ToolExecutionResultMessage.from(req,
                            "[tool crash] " + e.getMessage()));
                    }
                }
            }
            } catch (Throwable t) {
                // 最后防线：确认阶段或工具执行阶段出了 Error 级异常（OOM 等）
                // 不能救的也写进 history，让 LLM 知道出事了，然后继续（而不是崩进程）
                log.error("Catastrophic failure in tool phase", t);
                history.add(UserMessage.from("<error>工具执行阶段发生严重错误: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "。请换一种方法重试或直接返回当前结果。</error>"));
            }

            // ---- 步骤 6: Todo nag — 3 轮没更新就提醒 ----
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (todoManager.hasOpenItems() && roundsWithoutTodo >= 3) {
                history.add(UserMessage.from("<reminder>有未完成的待办项，请更新 TodoWrite。</reminder>"));
            }

            // ---- 步骤 7: Token 预算检查（对标 Claude Code） ----
            // 1. 轮次上限保险丝（如果配置了 maxRounds>0）
            if (maxRounds > 0 && round >= maxRounds) {
                // 不 return，注入警告让 LLM 自己收尾
                history.add(UserMessage.from("<warning>已达到最大轮次 " + maxRounds
                    + "。请立刻用 task(agent_type='general') 或直接返回最终结果。</warning>"));
                // 给 LLM 最后一轮机会
            }

            // 2. Token 水位数检查（真正的限制手段）
            int currentTokens = compact.estimateTokens(history);
            double usedRatio = (double) currentTokens / maxContextTokens;

            if (usedRatio < dangerRatio) {
                consecutiveCompactFails = 0;
                meltdownWarningIssued = false;  // 水位恢复正常，重置
                continue;
            }

            // 上一轮已经发过熔断警告，这轮 LLM 还没收手 → 硬终止
            if (meltdownWarningIssued) {
                return "[上下文容量不足，任务强制终止。请缩小任务范围后重试。]";
            }

            log.info("Token danger zone: {} / {} ({:.0f}%), triggering compact",
                currentTokens, maxContextTokens, usedRatio * 100);

            int tokensBefore = currentTokens;
            history = compact.compact(history);
            int tokensAfter = compact.estimateTokens(history);

            if (tokensAfter >= tokensBefore * 0.8) {
                consecutiveCompactFails++;
                log.warn("Compact ineffective: {} → {} tokens, fails={}",
                    tokensBefore, tokensAfter, consecutiveCompactFails);
                if (consecutiveCompactFails >= MAX_CONSECUTIVE_COMPACT_FAILS) {
                    // 熔断 —— 但不直接 return，注入警告让 LLM 自己拆任务
                    history.add(UserMessage.from(
                        "<warning>上下文接近容量上限（" + currentTokens + "/" + maxContextTokens
                        + " token），连续压缩 " + consecutiveCompactFails + " 次无效。"
                        + "你必须立刻采取行动：\n"
                        + "1. 如果当前任务还可以拆分：用 task(agent_type='plan') 先出拆分方案，"
                        + "再用 task(agent_type='general') 逐个执行子任务\n"
                        + "2. 如果任务已经基本完成：直接返回最终结果\n"
                        + "3. 不要继续调用工具做细节工作\n"
                        + "这是你最后一次机会——下一轮再超将强制终止。</warning>"));
                    meltdownWarningIssued = true;
                    continue;  // 给 LLM 一轮自主决策
                }
            } else {
                consecutiveCompactFails = 0;
            }
        }

        // 不会走到这里（for(;;) 只有 return 或 continue）
    }

    /**
     * 手动压缩上下文 — 用户在终端敲 /compact 时触发。
     * 通常用户觉得 AI 开始"忘事"了才手动触发。
     *
     * @return 压缩后的对话历史
     */
    public List<ChatMessage> manualCompact() {
        history = compact.compact(history);
        log.info("Manual compact done, size: {}", history.size());
        return history;
    }

    /**
     * 展示当前记忆索引 — 用户在终端敲 /memory 时触发。
     *
     * @return MEMORY.md 的文本内容
     */
    public String showMemory() {
        return ctx.getMemoryIndex();
    }

    /**
     * 关闭 Agent — 释放资源。
     */
    public void shutdown() {
        log.info("AgentLoop shutdown");
    }

    /**
     * 返回当前对话历史的副本（不修改原 List）。
     *
     * @return 完整的对话消息列表
     */
    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }
}
