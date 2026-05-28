package com.agent.core;

import com.agent.config.AppConfig;
import com.agent.memory.MemoryManager;
import com.agent.tools.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ChatRequest 拼装器 — 每次 LLM 调用前，把零散的零件组装成一份完整的请求。
 *
 * <h3>为什么要这个类</h3>
 * LLM 每次调用不只传"对话历史"，还要传 System Prompt（角色设定）、
 * Memory 索引（跨会话记忆）、工具列表（ToolSpecification）。
 * 如果每次都在 AgentLoop 里拼，代码又长又容易漏。抽出来单独管。
 *
 * <h3>Prompt Caching 原理</h3>
 * 请求 = 可缓存前缀 + 对话历史。前缀（System Prompt + Memory + 工具声明）
 * 在构造时算一次，以后每次 build() 复用同一个 List 引用。
 * LLM API 网关（DeepSeek/Claude）发现相邻请求的前缀一样，就命中缓存，
 * 白省 30-50% token。不需要多写一行 cache_control 参数。
 *
 * <h3>四个槽位（从上到下顺序固定）</h3>
 * 1. System Prompt — 角色 + 行为规范，永不改变
 * 2. Memory 索引   — 跨会话记忆，偶尔变（新增记忆时）
 * 3. 工具声明       — 告诉 LLM 有哪些工具可用
 * 4. 对话历史       — 每次请求不一样，拼接在最后
 */
public class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final AppConfig config;

    /**
     * 可缓存前缀 — 构造时计算一次（内容跨请求不变），每次 build() 复用。
     * 关键：是同一个 List 对象引用，不是内容相同的新 List。
     * API 网关比较的是字节序列，同一引用 = 同一字节。
     */
    private final List<ChatMessage> cachedPrefix;

    /**
     * @param toolRegistry  工具注册表，提供 ToolSpecification 列表 + 工具名
     * @param memoryManager 记忆管理器，提供 MEMORY.md 索引（可为 null）
     * @param config        应用配置
     */
    public ContextBuilder(ToolRegistry toolRegistry, MemoryManager memoryManager, AppConfig config) {
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.config = config;
        this.cachedPrefix = buildPrefix();  // 构造时一次性建好，后面不复建
    }

    /**
     * 构建可缓存前缀 — 只在构造函数里调一次。
     *
     * @return 仅 System Prompt（工具名和 Memory 在 build() 里动态拼）
     */
    private List<ChatMessage> buildPrefix() {
        List<ChatMessage> prefix = new ArrayList<>();

        // 槽位 0: CLAUDE.md（项目根目录，可选，注入到 System Prompt 之前）
        String claudeMd = loadClaudeMd();
        if (!claudeMd.isEmpty()) {
            prefix.add(SystemMessage.from(claudeMd));
        }

        // 槽位 1: System Prompt
        // 内容完全固定，是缓存命中率最高的部分
        prefix.add(SystemMessage.from(
            "你是 MVP Claude Code，一个 Java AI 编程助手。\n\n" +
            "核心原则:\n" +
            "- 主动使用工具完成任务，不要猜测，不要询问是否继续\n" +
            "- 复杂任务先用 TodoWrite 拆解为步骤清单，逐步执行并即时更新状态\n" +
            "- 互不依赖的工具调用尽量在一次响应中并行返回，不要串行等待\n" +
            "- 文件操作前先 read 了解现有代码结构，避免盲目修改\n" +
            "- 遇到错误时检查工具返回信息，调整方法重试，不要放弃\n" +
            "- 如果工具返回 [tool crash]，说明工具本身出 bug 了，不要重试同一个调用，换一条路走\n" +
            "- 完成后简要总结做了什么\n\n" +
            "TodoWrite 使用规范（必须遵守）:\n" +
            "- 最多 20 条 Todo 项，同时仅 1 条标记为 in_progress\n" +
            "- 完成一条后立即单独标记为 completed，禁止批量标记多条完成\n" +
            "- 每完成一步都要立刻调 TodoWrite 更新状态\n\n" +
            "工具指南:\n" +
            "- file(read/write/list/exists): 文件读写，优先使用\n" +
            "- bash(command): 执行 Shell 命令，包括编译、运行、git 操作\n" +
            "- search(pattern, ext): 搜索文件内容，支持正则和文件类型过滤\n" +
            "- task(prompt, agent_type, maxRounds): 启动后台子 Agent，立即返回启动确认，子 Agent 在后台异步执行。\n" +
            "  可同时启动多个子 Agent 并行工作。完成后结果自动以 <subagent-results> 注入对话。\n" +
            "  agent_type: explore(只读探索) / plan(方案设计) / verification(验证审查) / general(通用,默认)\n" +
            "  注意: general 子 Agent 跑在独立 git worktree 中，修改不会影响主工作区。\n" +
            "- TodoWrite(items): 任务拆解与进度追踪。严格遵守上述 TodoWrite 使用规范\n\n" +
            "工作目录: " + config.getWorkspace()
        ));

        return prefix;
    }

    /** 加载项目根目录的 CLAUDE.md（如果存在），注入到 System Prompt 最前面 */
    private String loadClaudeMd() {
        try {
            Path path = Path.of(config.getWorkspace(), "CLAUDE.md");
            if (Files.exists(path)) {
                String content = Files.readString(path);
                log.info("Loaded CLAUDE.md ({} chars)", content.length());
                return content;
            }
        } catch (Exception e) {
            log.warn("Failed to load CLAUDE.md: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 组装完整 ChatRequest — AgentLoop 每轮调一次。
     *
     * <h3>为什么 Tool 列表和 Memory 不放在缓存前缀里</h3>
     * 两者都是会话中途会变的东西——
     * MCP Server 中途增删工具、Agent 保存新记忆时，
     * 放在动态部分每次重新读，保证 LLM 看到最新状态。
     * Claude Code 也是把工具名列表和 Memory 放在动态 Append Prompt 层（cache_boundary 以下）。
     *
     * @param history 当前完整对话历史
     * @return 可以直接发给 API 的 ChatRequest
     */
    public ChatRequest build(List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>(cachedPrefix);  // 复用缓存前缀

        // 动态工具列表：每次从 ToolRegistry 拿最新的（MCP 中途增删工具时自动反映）
        messages.add(SystemMessage.from("可用工具: " + toolRegistry.describeNames()));

        // 动态记忆：每次构建时重新读取（Agent 可能在会话中保存了新记忆）
        if (memoryManager != null) {
            try {
                String memoryIndex = memoryManager.getIndex();
                if (memoryIndex != null && !memoryIndex.isEmpty()) {
                    messages.add(SystemMessage.from("当前记忆:\n" + memoryIndex));
                }
            } catch (Exception e) {
                log.warn("Failed to load memory index", e);
            }
        }

        messages.addAll(history);  // 动态拼接对话历史

        return ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolRegistry.getAllSpecs())
            .build();
    }

    /**
     * 返回当前记忆索引的文本 — 用于 /memory 命令展示给用户看。
     *
     * @return MEMORY.md 的完整内容，或"无记忆"
     */
    public String getMemoryIndex() {
        if (memoryManager == null) return "无记忆";
        try {
            return memoryManager.getIndex();
        } catch (Exception e) {
            return "记忆加载失败: " + e.getMessage();
        }
    }
}
