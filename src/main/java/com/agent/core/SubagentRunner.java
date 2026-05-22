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
 * Subagent 运行器 — 独立上下文，防止递归，线程安全
 * 每个 subagent 拥有独立的 messages[] 和工具集，与父 agent 不共享可变状态
 */
public class SubagentRunner {
    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /**
     * 同步运行一个子 Agent，返回摘要结果
     *
     * @param ai          AI 服务（共享，无状态）
     * @param dispatcher  工具分发器（会被去掉 task 工具）
     * @param registry    工具注册表（用于构建上下文）
     * @param config      配置
     * @param prompt      子任务描述
     * @param maxRounds   最大工具调用轮次
     * @return 子 Agent 的最终结果文本
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

        for (int round = 1; round <= maxRounds; round++) {
            if (subCompact.shouldCompact(subHistory)) {
                subHistory = subCompact.compact(subHistory);
            }

            ChatResponse resp = ai.chat(subCtx.build(subHistory));
            AiMessage aiMsg = resp.aiMessage();

            if (!aiMsg.hasToolExecutionRequests()) {
                // 子 Agent 完成，返回结果
                String result = aiMsg.text();
                log.info("Subagent done after {} rounds", round);
                return result;
            }

            subHistory.add(aiMsg);
            for (var req : aiMsg.toolExecutionRequests()) {
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
