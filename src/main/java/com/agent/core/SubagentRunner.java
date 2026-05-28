package com.agent.core;

import com.agent.config.AppConfig;
import com.agent.memory.MemoryManager;
import com.agent.tools.ToolRegistry;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 运行器 — 独立上下文 + 四种专用类型 + 双层安全保障。
 *
 * <h3>四种 SubAgent 类型（对标 Claude Code 泄露架构）</h3>
 *
 * EXPLORE（探索代理）：
 *   能做什么：读文件、搜索代码、列目录、check_background
 *   不能做什么：写文件、执行 bash、创建子 Agent、改 Todo
 *   工具集：file, search, check_background
 *   安全层：物理移除 bash/write/task/TodoWrite + Prompt 层 NEVER 指令
 *
 * PLAN（规划代理）：
 *   能做什么：读文件、搜索代码（纯信息收集）
 *   不能做什么：任何修改操作、任何命令执行
 *   工具集：file, search（白名单模式，最严格）
 *   安全层：白名单只含 2 个读工具 + Prompt 层 FORBIDDEN 指令
 *
 * VERIFICATION（验证代理）：
 *   能做什么：读文件、搜索代码、跑只读诊断命令（如 java --version、mvn validate）
 *   不能做什么：写文件、生产性 bash、创建子 Agent
 *   工具集：file, search, bash, check_background
 *   安全层：物理移除 write/task/TodoWrite + Prompt 层 NEVER modify 指令
 *
 * GENERAL（通用代理）：
 *   能做什么：所有工具（读写文件、执行命令、管理 Todo、后台任务）
 *   不能做什么：创建子 Agent（防递归）
 *   工具集：除 task 外全部
 *   安全层：只物理移除 task + Prompt 层防递归提醒
 *
 * <h3>双层安全保障（对标 Claude Code）</h3>
 * 第一道（物理隔离）：工具根本就不在 Dispatch Map 里，想调也调不了。ToolDispatcher 返回 "Unknown tool"。
 * 第二道（Prompt 约束）：NEVER / FORBIDDEN 否定指令，让 LLM 压根不想调。
 * Prompt 失效 ≠ 安全失效。第一道防线不依赖 LLM 听话。
 */
public class SubagentRunner {
    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    /** SubAgent 类型 — 决定工具集和 System Prompt */
    public enum SubagentType {
        EXPLORE,
        PLAN,
        VERIFICATION,
        GENERAL
    }

    /**
     * 同步运行一个子 Agent，返回结果摘要。
     *
     * @param ai         AI 服务（共享，无状态）
     * @param dispatcher 父 Agent 的工具分发器（会被按类型裁剪）
     * @param registry   工具注册表（会被按类型裁剪）
     * @param config     应用配置
     * @param prompt     子任务描述
     * @param maxRounds  最大工具调用轮次
     * @param type       子 Agent 类型（决定工具集和安全边界）
     * @return 子 Agent 的最终结果文本
     */
    public static String run(AIService ai, ToolDispatcher dispatcher,
                              ToolRegistry registry, AppConfig config,
                              String prompt, int maxRounds, SubagentType type,
                              MemoryManager memoryManager) {
        log.info("Subagent[{}] start, maxRounds={}, prompt={}", type, maxRounds,
                 prompt.substring(0, Math.min(80, prompt.length())));

        // ---- 步骤 1: 按类型裁剪工具集（第一道防线：物理隔离） ----
        ToolDispatcher subDispatcher;
        ToolRegistry subRegistry;

        switch (type) {
            case EXPLORE -> {
                // 黑名单：去掉写工具 + task + TodoWrite
                subDispatcher = dispatcher.without("task", "TodoWrite", "bash",
                    "background_run", "check_background");
                subRegistry = registry.without("task", "TodoWrite", "bash",
                    "background_run", "check_background");
            }
            case PLAN -> {
                // 白名单：仅保留只读工具（最严格）
                subDispatcher = dispatcher.only("file", "search");
                subRegistry = registry.only("file", "search");
            }
            case VERIFICATION -> {
                // 黑名单：去掉写工具 + task + TodoWrite，保留 bash 做只读检查
                subDispatcher = dispatcher.without("task", "TodoWrite",
                    "background_run", "check_background");
                subRegistry = registry.without("task", "TodoWrite",
                    "background_run", "check_background");
            }
            default -> {
                // GENERAL：只去掉 task（防递归），其他全给
                subDispatcher = dispatcher.without("task");
                subRegistry = registry.without("task");
            }
        }

        // ---- 步骤 2: 构建类型专属 System Prompt（第二道防线：否定指令） ----
        String systemPrompt = switch (type) {
            case EXPLORE -> """
                你是 Explore Agent，一个代码探索专家。
                你的职责：探索代码库、搜索模式、收集信息。只读不写。
                NEVER 写文件或修改任何代码。
                NEVER 执行 Shell 命令。
                NEVER 创建子 Agent。
                如果你发现了关键信息，整理成结构化报告返回。
                """;

            case PLAN -> """
                你是 Plan Agent，一个技术方案设计师。
                你的职责：阅读代码、分析架构、输出设计方案。
                FORBIDDEN: 任何执行操作——不写文件，不跑命令，不修改任何东西。
                FORBIDDEN: 创建子 Agent。
                你的唯一输出是一份结构化的技术方案，不是代码。
                """;

            case VERIFICATION -> """
                你是 Verification Agent，一个代码验证专家。
                你的职责：审查代码质量、验证功能正确性、发现潜在问题。
                你可以跑只读诊断命令（如 java --version、mvn validate --no-build），
                但 NEVER 修改代码或改变项目状态。
                NEVER 创建子 Agent。
                输出格式：问题列表 + 严重程度 + 修复建议。
                """;

            case GENERAL -> """
                你是通用子 Agent，专注于完成单个任务。
                你有完整的工具访问权限，但不能创建新的子 Agent。
                完成后直接返回结果，不要询问更多信息。
                """;
        };

        // ---- 步骤 3: 创建独立上下文 ----
        List<ChatMessage> subHistory = new ArrayList<>();
        subHistory.add(SystemMessage.from(systemPrompt));
        subHistory.add(UserMessage.from(prompt));

        ContextBuilder subCtx = new ContextBuilder(subRegistry, memoryManager, config);
        CompactService subCompact = new CompactService(ai, config.getCompactThreshold());
        // 子 Agent 工具自动批准（父 Agent 已授权）
        ToolExecutionConfirmation subConfirm = new ToolExecutionConfirmation(false);

        // ---- 步骤 4: 子循环（与 AgentLoop 完全对齐） ----
        String lastPartialResult = null;  // 保留最后一次纯文本输出，超限时返回部分结果
        int consecutiveFailures = 0;      // 连续 LLM 失败计数，超过阈值提前退出
        final int MAX_CONSECUTIVE_FAILURES = 3;

        for (int round = 1; round <= maxRounds; round++) {
            // microCompact
            subHistory = subCompact.microCompact(subHistory);

            // autoCompact
            if (subCompact.shouldCompact(subHistory)) {
                subHistory = subCompact.compact(subHistory);
            }

            // LLM 调用 + 错误注入（带退避和连续失败限制）
            ChatResponse resp;
            AiMessage aiMsg;
            try {
                resp = ai.chat(subCtx.build(subHistory));
                aiMsg = resp.aiMessage();
                consecutiveFailures = 0;  // 成功则重置计数器
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("Subagent[{}] LLM call failed ({}/{}): {}",
                    type, consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.error("Subagent[{}] 连续失败 {} 次，提前退出", type, consecutiveFailures);
                    String partial = lastPartialResult != null
                        ? "[部分结果]\n" + lastPartialResult + "\n[因 LLM 连续失败中止]"
                        : "[Subagent(" + type + ") LLM 连续失败 " + consecutiveFailures + " 次，任务中止]";
                    return partial;
                }
                // 指数退避：1s, 2s, 4s
                try { Thread.sleep(1000L * (1 << (consecutiveFailures - 1))); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                subHistory.add(UserMessage.from("<error>LLM call failed: " + e.getMessage()
                    + ". Please retry or change approach.</error>"));
                continue;
            }

            // 纯文本 = 子 Agent 完成
            if (!aiMsg.hasToolExecutionRequests()) {
                String result = aiMsg.text();
                log.info("Subagent[{}] done after {} rounds", type, round);
                return result;
            }

            // 记录最后一次 AI 消息文本（作为部分结果备份）
            if (aiMsg.text() != null && !aiMsg.text().isBlank()) {
                lastPartialResult = aiMsg.text();
            }

            // 工具调用自动执行
            subHistory.add(aiMsg);
            for (var req : aiMsg.toolExecutionRequests()) {
                var result = subDispatcher.execute(req);
                String content = result.getContent();
                if (content == null || content.isBlank()) content = "[empty]";
                subHistory.add(ToolExecutionResultMessage.from(req, content));
            }
        }

        log.warn("Subagent[{}] reached max rounds: {}", type, maxRounds);
        // 超限时返回部分结果（而非丢弃所有中间内容）
        if (lastPartialResult != null) {
            return "[部分结果（已达最大轮次 " + maxRounds + "，任务可能未完成）]\n" + lastPartialResult;
        }
        return "[Subagent(" + type + ") 达到最大轮次 " + maxRounds + "，任务未完成]";
    }
}
