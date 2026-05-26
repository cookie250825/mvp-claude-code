package com.agent.core;

import com.agent.config.AppConfig;
import com.agent.tools.ToolRegistry;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Subagent 运行器 — 独立上下文，防止递归，线程安全。
 * 每个 subagent 拥有独立的 messages[] 和工具集，与父 agent 不共享可变状态。
 *
 * 与 AgentLoop 架构对齐：microCompact + autoCompact + 错误注入。
 * 工具确认在此层自动批准（父 Agent 已授权，子 Agent 不再二次确认）。
 */
public class SubagentRunner {
    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /**
     * 同步运行一个子 Agent，返回摘要结果。
     */
    public static String run(AIService ai, ToolDispatcher dispatcher,
                              ToolRegistry registry, AppConfig config,
                              String prompt, int maxRounds) {
        log.info("Subagent start, maxRounds={}, prompt={}", maxRounds,
                 prompt.substring(0, Math.min(80, prompt.length())));

        // 独立上下文，不污染主 Agent 的 history
        List<ChatMessage> subHistory = new ArrayList<>();
        subHistory.add(SystemMessage.from("你是子 Agent，专注于完成单个任务。完成后直接返回结果，不要询问更多信息。"));
        subHistory.add(UserMessage.from(prompt));

        // 去掉 task 工具，防止子 Agent 再创建孙 Agent（避免无限递归）
        ToolDispatcher subDispatcher = dispatcher.withoutTaskTool();
        ToolRegistry subRegistry = registry.without("task");
        ContextBuilder subCtx = new ContextBuilder(subRegistry, null, config);
        CompactService subCompact = new CompactService(ai, config.getCompactThreshold());
        // 子 Agent 工具自动批准 — 父 Agent 已授权
        ToolExecutionConfirmation subConfirm = new ToolExecutionConfirmation(false);

        for (int round = 1; round <= maxRounds; round++) {
            // microCompact — 静默裁剪旧工具输出（与 AgentLoop 对齐）
            subHistory = subCompact.microCompact(subHistory);

            // autoCompact — token 超阈值时 LLM 摘要
            if (subCompact.shouldCompact(subHistory)) {
                subHistory = subCompact.compact(subHistory);
            }

            // LLM 调用 + 错误注入（与 AgentLoop 对齐）
            ChatResponse resp;
            AiMessage aiMsg;
            try {
                resp = ai.chat(subCtx.build(subHistory));
                aiMsg = resp.aiMessage();
            } catch (Exception e) {
                log.warn("Subagent LLM call failed, injecting error: {}", e.getMessage());
                subHistory.add(UserMessage.from("<error>LLM call failed: " + e.getMessage()
                    + ". Please retry or change approach.</error>"));
                continue;
            }

            if (!aiMsg.hasToolExecutionRequests()) {
                String result = aiMsg.text();
                log.info("Subagent done after {} rounds", round);
                return result;
            }

            subHistory.add(aiMsg);
            for (var req : aiMsg.toolExecutionRequests()) {
                // 子 Agent 工具调用自动批准（不再二次确认）
                var result = subDispatcher.execute(req);
                String content = result.getContent();
                if (content == null || content.isBlank()) content = "[empty]";
                subHistory.add(ToolExecutionResultMessage.from(req, content));
            }
        }

        log.warn("Subagent reached max rounds: {}", maxRounds);
        return "[Subagent 达到最大轮次 " + maxRounds + "，任务未完成]";
    }
}
