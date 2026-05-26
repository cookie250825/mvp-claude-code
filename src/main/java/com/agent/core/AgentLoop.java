package com.agent.core;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int MAX_ROUNDS = 30;

    private final AIService ai;
    private final ToolDispatcher tools;
    private final CompactService compact;
    private final ContextBuilder ctx;
    private final com.agent.tools.TodoManager todoManager;
    private final BackgroundManager bgManager;
    private List<ChatMessage> history = new ArrayList<>();

    public AgentLoop(AIService ai, ToolDispatcher tools, CompactService compact,
                     ContextBuilder ctx, com.agent.tools.TodoManager todoManager,
                     BackgroundManager bgManager) {
        this.ai = ai;
        this.tools = tools;
        this.compact = compact;
        this.ctx = ctx;
        this.todoManager = todoManager;
        this.bgManager = bgManager;
    }

    public void init() {
        log.info("AgentLoop initialized");
    }

    public String process(String input) {
        history.add(UserMessage.from(input));
        int roundsWithoutTodo = 0;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            // S08: 排空后台任务完成通知，注入上下文
            if (bgManager != null) {
                java.util.List<java.util.Map<String, Object>> notifs = bgManager.drain();//消息队列注入
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

            // F3: micro-compact — 静默裁剪旧工具输出，零 API 成本
            history = compact.microCompact(history);

            // auto-compact — token 超阈值时 LLM 摘要
            if (compact.shouldCompact(history)) {
                history = compact.compact(history);
            }

            // LLM 调用
            ChatResponse resp;
            AiMessage aiMsg;
            try {
                resp = ai.chat(ctx.build(history));
                aiMsg = resp.aiMessage();
            } catch (Exception e) {
                // F4/B1: 错误注入 — 不崩溃，把异常包装成消息让 LLM 自愈
                log.warn("LLM call failed, injecting error: {}", e.getMessage());
                history.add(UserMessage.from("<error>LLM call failed: " + e.getMessage()
                    + ". Please retry or change approach.</error>"));
                continue;
            }

            if (!aiMsg.hasToolExecutionRequests()) {
                history.add(aiMsg);
                return aiMsg.text();
            }

            history.add(aiMsg);
            boolean usedTodo = false;
            for (var req : aiMsg.toolExecutionRequests()) {
                var result = tools.execute(req);
                String content = result.getContent();
                if (content == null || content.isBlank()) {
                    content = result.isSuccess() ? "[empty]" : "[error]";
                }
                history.add(ToolExecutionResultMessage.from(req, content));
                if ("TodoWrite".equals(req.name())) usedTodo = true;
            }
            // F5: nag — 3 轮没更新 Todo 且有待办项时注入提醒
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (todoManager.hasOpenItems() && roundsWithoutTodo >= 3) {
                history.add(UserMessage.from("<reminder>有未完成的待办项，请更新 TodoWrite。</reminder>"));
            }
        }

        // Q1: 最大轮次保护
        return "[达到最大轮次 " + MAX_ROUNDS + "，任务未完成。请考虑缩小任务范围或使用 /compact 释放上下文。]";
    }

    public List<ChatMessage> manualCompact() {
        history = compact.compact(history);
        log.info("Manual compact done, size: {}", history.size());
        return history;
    }

    public String showMemory() {
        return ctx.getMemoryIndex();
    }

    public void shutdown() {
        log.info("AgentLoop shutdown");
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }
}
