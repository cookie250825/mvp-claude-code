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
 * 子 Agent 运行器 — 独立上下文执行子任务。
 *
 * <h3>什么时候用</h3>
 * 主 Agent 遇到大规模探索任务（如"在项目中找到所有 SQL 注入风险"），
 * 调用 TaskTool → TaskTool 调用 SubagentRunner.run()。
 * 子 Agent 独立完成搜索后把结果摘要还给父 Agent。
 *
 * <h3>核心设计：上下文隔离，不是线程隔离</h3>
 * 父子 Agent 共享线程（父等子返回才能继续），
 * 但各自拥有独立的消息列表（subHistory = 全新 ArrayList），
 * 不共享任何可变状态 → 天然线程安全。
 *
 * <h3>防递归：在定义层切断</h3>
 * 子 Agent 的工具列表 = 父的工具列表 - task 工具。
 * LLM 不知道 task 工具的存在 → 永远不会有"子 Agent 再创建孙子 Agent"。
 *
 * <h3>与 AgentLoop 的对齐</h3>
 * 补全了 microCompact + 错误注入 + autoCompact，
 * 与主循环共享同一架构。差异只在：子 Agent 不管理 Todo、不弹确认框。
 */
public class SubagentRunner {
    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /**
     * 同步运行一个子 Agent，返回结果摘要。
     *
     * <h3>执行流程</h3>
     * 1. 创建独立的 subHistory（不污染父 Agent）
     * 2. 去掉 task 工具（防递归）
     * 3. 进入子循环：microCompact → autoCompact? → LLM 调用 → 工具执行 → 循环
     * 4. LLM 返回纯文本 → 结束，返回结果
     *
     * @param ai         AI 服务（与父 Agent 共享，无状态）
     * @param dispatcher 父 Agent 的工具分发器（会被去掉 task 后创建副本）
     * @param registry   工具注册表（用于创建子 ContextBuilder）
     * @param config     应用配置
     * @param prompt     子任务描述（父 Agent 写的，如"找到所有 Thread 未关闭的地方"）
     * @param maxRounds  最大工具调用轮次（子 Agent 默认 10 轮）
     * @return 子 Agent 的最终结果文本
     */
    public static String run(AIService ai, ToolDispatcher dispatcher,
                              ToolRegistry registry, AppConfig config,
                              String prompt, int maxRounds) {
        log.info("Subagent start, maxRounds={}, prompt={}", maxRounds,
                 prompt.substring(0, Math.min(80, prompt.length())));

        // ---- 步骤 1: 创建独立的子上下文 ----
        // subHistory 是全新 ArrayList，和父 Agent 的 history 没有任何关系
        List<ChatMessage> subHistory = new ArrayList<>();
        subHistory.add(SystemMessage.from("你是子 Agent，专注于完成单个任务。完成后直接返回结果，不要询问更多信息。"));
        subHistory.add(UserMessage.from(prompt));

        // ---- 步骤 2: 去掉 task 工具（防递归） ----
        // 子 Agent 的 ToolSpec 列表里没有 "task" → LLM 不知道可以再开子 Agent
        ToolDispatcher subDispatcher = dispatcher.withoutTaskTool();
        ToolRegistry subRegistry = registry.without("task");
        ContextBuilder subCtx = new ContextBuilder(subRegistry, null, config);
        CompactService subCompact = new CompactService(ai, config.getCompactThreshold());
        // 子 Agent 工具自动批准（父 Agent 已授权，不二次确认）
        ToolExecutionConfirmation subConfirm = new ToolExecutionConfirmation(false);

        for (int round = 1; round <= maxRounds; round++) {
            // ---- microCompact（与 AgentLoop 对齐） ----
            subHistory = subCompact.microCompact(subHistory);

            // ---- autoCompact（与 AgentLoop 对齐） ----
            if (subCompact.shouldCompact(subHistory)) {
                subHistory = subCompact.compact(subHistory);
            }

            // ---- LLM 调用 + 错误注入（与 AgentLoop 对齐） ----
            ChatResponse resp;
            AiMessage aiMsg;
            try {
                resp = ai.chat(subCtx.build(subHistory));
                aiMsg = resp.aiMessage();
            } catch (Exception e) {
                // 异常不崩溃，包装成消息注入 —— 和 AgentLoop 一样
                log.warn("Subagent LLM call failed, injecting error: {}", e.getMessage());
                subHistory.add(UserMessage.from("<error>LLM call failed: " + e.getMessage()
                    + ". Please retry or change approach.</error>"));
                continue;
            }

            // 纯文本 = 子 Agent 任务完成
            if (!aiMsg.hasToolExecutionRequests()) {
                String result = aiMsg.text();
                log.info("Subagent done after {} rounds", round);
                return result;
            }

            // 工具调用自动执行（子 Agent 不弹确认）
            subHistory.add(aiMsg);
            for (var req : aiMsg.toolExecutionRequests()) {
                var result = subDispatcher.execute(req);
                String content = result.getContent();
                if (content == null || content.isBlank()) content = "[empty]";
                subHistory.add(ToolExecutionResultMessage.from(req, content));
            }
        }

        // 子 Agent 也受轮次上限保护
        log.warn("Subagent reached max rounds: {}", maxRounds);
        return "[Subagent 达到最大轮次 " + maxRounds + "，任务未完成]";
    }
}
