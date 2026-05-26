package com.agent.core;

import com.agent.config.AppConfig;
import com.agent.memory.MemoryManager;
import com.agent.tools.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatRequest 拼装器 — Prompt Caching 架构。
 *
 * 核心设计：将每次 LLM 调用拆分为「可缓存前缀」+「动态历史」。
 * 前缀（System Prompt + Memory + 工具指南）在构造时计算一次，
 * 后续每次 build() 复用同一个 List 引用。
 *
 * DeepSeek/Claude 等平台会自动识别重复前缀并缓存，节省 30-50% token 成本。
 * 原理：API 网关比较相邻请求的公共前缀 —— 前缀越稳定，缓存命中率越高。
 */
public class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final AppConfig config;

    /** 可缓存前缀 —— 构造时计算一次，每次 build() 复用 */
    private final List<ChatMessage> cachedPrefix;

    public ContextBuilder(ToolRegistry toolRegistry, MemoryManager memoryManager, AppConfig config) {
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.config = config;
        this.cachedPrefix = buildPrefix();
    }

    /** 构造可缓存前缀（仅执行一次，内容跨请求不变） */
    private List<ChatMessage> buildPrefix() {
        List<ChatMessage> prefix = new ArrayList<>();

        // 槽位 1: System Prompt（永不改变 = 完美缓存命中）
        prefix.add(SystemMessage.from(
            "你是 MVP Claude Code，一个 Java AI 编程助手。\n\n" +
            "核心原则:\n" +
            "- 主动使用工具完成任务，不要猜测，不要询问是否继续\n" +
            "- 复杂任务先用 todo_write 拆解为步骤清单，逐步执行并即时更新状态\n" +
            "- 文件操作前先 read 了解现有代码结构，避免盲目修改\n" +
            "- 遇到错误时检查工具返回信息，调整方法重试，不要放弃\n" +
            "- 完成后简要总结做了什么\n\n" +
            "工具指南:\n" +
            "- file(read/write/edit/list): 文件读写，优先使用\n" +
            "- bash(command): 执行 Shell 命令，包括编译、运行、git 操作\n" +
            "- search(pattern, ext): 搜索文件内容，支持正则和文件类型过滤\n" +
            "- task(prompt, maxRounds): 委托子 Agent 独立完成大规模探索\n" +
            "- TodoWrite(items): 任务拆解与进度追踪，每完成一项立即标记 completed\n\n" +
            "工作目录: " + config.getWorkspace()
        ));

        // 槽位 2: Memory 索引（跨会话恒定 = 缓存命中）
        if (memoryManager != null) {
            try {
                String memoryIndex = memoryManager.getIndex();
                if (memoryIndex != null && !memoryIndex.isEmpty()) {
                    prefix.add(SystemMessage.from("记忆索引:\n" + memoryIndex));
                }
            } catch (Exception e) {
                log.warn("Failed to load memory index", e);
            }
        }

        // 槽位 3: 工具可用性声明（同一会话不变 = 缓存命中）
        prefix.add(SystemMessage.from(
            "可用工具: " + toolRegistry.describeNames()
        ));

        return prefix;
    }

    /**
     * 组装完整 ChatRequest。
     * @param history 对话历史（每次调用不同，不可缓存）
     */
    public ChatRequest build(List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>(cachedPrefix);  // 复用缓存前缀
        messages.addAll(history);  // 动态拼接历史

        return ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolRegistry.getAllSpecs())
            .build();
    }

    public String getMemoryIndex() {
        if (memoryManager == null) return "无记忆";
        try {
            return memoryManager.getIndex();
        } catch (Exception e) {
            return "记忆加载失败: " + e.getMessage();
        }
    }
}
