package com.agent.tools;

import com.agent.core.BackgroundManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 后台任务启动工具 — LLM 通过它来启动异步长时间命令。
 *
 * <h3>什么情况下 LLM 调用它</h3>
 * 需要执行耗时操作（编译大项目、安装依赖）时，LLM 应该用 background_run
 * 而不是 bash。这个工具立刻返回任务 ID，不阻塞主循环。
 *
 * <h3>配套工具</h3>
 * check_background — 查询后台任务状态
 */
public class BackgroundRunTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(BackgroundRunTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** BackgroundManager 实例（与 AgentLoop 共享同一个） */
    private final BackgroundManager bg;

    public BackgroundRunTool(BackgroundManager bg) { this.bg = bg; }

    @Override public String name() { return "background_run"; }
    @Override public String description() { return "在后台异步执行长时间命令（编译、安装等），立即返回任务ID。用 check_background 查询结果。"; }

    /**
     * 启动后台任务。
     *
     * @param arguments JSON 参数：command(必填) / timeout(默认120s)
     * @return "后台任务 xxx 已启动: ..."
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String command = args.path("command").asText();
            int timeout = args.path("timeout").asInt(120);  // 默认 2 分钟

            if (command.isEmpty()) return ToolResult.error("command is required");
            return ToolResult.success(bg.run(command, timeout));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("command", "要执行的 shell 命令")
                .addIntegerProperty("timeout", "超时秒数，默认 120")
                .required(List.of("command"))
                .build())
            .build();
    }
}
