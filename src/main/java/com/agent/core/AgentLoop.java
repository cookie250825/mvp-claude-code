package com.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.AbstractMap.SimpleEntry;

/**
 * Agent 核心循环 — 整个项目的"心脏"。
 *
 * <h3>一句话概括</h3>
 * 拿到用户输入 → 进入 while 循环 → 流式调用 LLM → 如果 LLM 说要调工具就确认后并行执行 →
 * 工具结果写回对话历史 → 继续下一轮循环，直到 LLM 返回纯文本。
 *
 * <h3>为什么是 while(true) 手写循环</h3>
 * LangChain4j 有 AiServices 可以自动管理工具调用循环。但它是黑盒——
 * 你不知道工具什么时候被调、异常怎么处理、确认怎么插入。
 * 手写循环让你在每一步都能插自己的逻辑：压缩上下文、注入错误、用户确认、Todo 提醒。
 *
 * <h3>每轮迭代的六个步骤（顺序不能变）</h3>
 * 1. background drain  — 收后台任务完成通知
 * 2. microCompact       — 零成本裁剪旧工具输出
 * 3. autoCompact?       — token 超了？LLM 摘要
 * 4. streamingChat      — 流式调 LLM，失败不崩溃
 * 5. 工具调用？          — 先确认再并行执行，结果写回 history
 * 6. Todo nag?          — 3 轮没更新 Todo 就提醒
 *
 * <h3>30 轮上限</h3>
 * 不是功能需求，是保险丝。LLM 有时会陷入工具调用死循环（反复读同一个不存在的文件）。
 * 没上限就是真死循环，30 轮足够完成绝大多数任务。
 *
 * <h3>并行工具执行</h3>
 * LLM 一次返回的多个工具调用如果互不依赖（如同时读 3 个文件），
 * 确认后并行执行——确认串行（用户逐个看），执行并行。
 */
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int MAX_ROUNDS = 30;  // 最大工具调用轮次，防死循环

    private final AIService ai;
    private final ToolDispatcher tools;
    private final CompactService compact;
    private final ContextBuilder ctx;
    private final com.agent.tools.TodoManager todoManager;
    private final BackgroundManager bgManager;
    private final ToolExecutionConfirmation confirmation;
    private List<ChatMessage> history = new ArrayList<>();

    /**
     * @param ai           AI 服务（双模：同步用于摘要，流式用于主对话）
     * @param tools        工具分发器，存着所有工具的 Map
     * @param compact      三层上下文压缩
     * @param ctx          请求拼装器（含 Prompt Caching）
     * @param todoManager  Todo 状态管理
     * @param bgManager    后台异步任务管理器
     * @param confirmation 工具执行确认（交互模式要用户点头，单次模式自动批）
     */
    public AgentLoop(AIService ai, ToolDispatcher tools, CompactService compact,
                     ContextBuilder ctx, com.agent.tools.TodoManager todoManager,
                     BackgroundManager bgManager, ToolExecutionConfirmation confirmation) {
        this.ai = ai;
        this.tools = tools;
        this.compact = compact;
        this.ctx = ctx;
        this.todoManager = todoManager;
        this.bgManager = bgManager;
        this.confirmation = confirmation;
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
        int roundsWithoutTodo = 0;  // 计数器：连续几轮没更新 Todo 了

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            // ---- 步骤 1: 排空后台任务通知 ----
            // 如果有后台任务刚完成，把结果注入上下文，LLM 下轮能看到
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
            boolean usedTodo = false;
            if (!approvedReqs.isEmpty()) {
                // 提交所有获批工具到线程池并行执行
                List<CompletableFuture<SimpleEntry<ToolExecutionRequest, com.agent.tools.ToolResult>>> futures =
                    new ArrayList<>();
                for (ToolExecutionRequest req : approvedReqs) {
                    futures.add(CompletableFuture.supplyAsync(() ->
                        new SimpleEntry<>(req, tools.execute(req))
                    ));
                }

                // 收集结果，保持 LLM 原始顺序写回 history
                for (var future : futures) {
                    try {
                        var entry = future.get();  // 阻塞等这个工具执行完
                        ToolExecutionRequest req = entry.getKey();
                        com.agent.tools.ToolResult result = entry.getValue();

                        String content = result.getContent();
                        if (content == null || content.isBlank()) {
                            content = result.isSuccess() ? "[empty]" : "[error]";
                        }
                        history.add(ToolExecutionResultMessage.from(req, content));

                        if ("TodoWrite".equals(req.name())) usedTodo = true;
                    } catch (Exception e) {
                        log.warn("Parallel tool execution failed", e);
                    }
                }
            }

            // ---- 步骤 6: Todo nag — 3 轮没更新就提醒 ----
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (todoManager.hasOpenItems() && roundsWithoutTodo >= 3) {
                history.add(UserMessage.from("<reminder>有未完成的待办项，请更新 TodoWrite。</reminder>"));
            }
        }

        // 30 轮用完了还没完 → 熔断
        return "[达到最大轮次 " + MAX_ROUNDS + "，任务未完成。请考虑缩小任务范围或使用 /compact 释放上下文。]";
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
