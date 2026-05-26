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
 * 后台任务查询工具 — LLM 用它来查异步任务有没有跑完。
 *
 * <h3>两种用法</h3>
 * 1. 传 task_id → 查单个任务状态
 * 2. 不传参数 → 列出所有后台任务
 */
public class CheckBackgroundTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(CheckBackgroundTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BackgroundManager bg;

    public CheckBackgroundTool(BackgroundManager bg) { this.bg = bg; }

    @Override public String name() { return "check_background"; }
    @Override public String description() { return "查询后台任务状态。不传参数列出全部，传 task_id 查单个。"; }

    /**
     * 查询后台任务。
     *
     * @param arguments JSON 参数：task_id（可选，不传则列出全部）
     * @return 任务状态文本
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String taskId = args.has("task_id") ? args.path("task_id").asText() : null;
            return ToolResult.success(bg.check(taskId));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("task_id", "要查询的任务ID，不传则列出全部")
                .required(List.of())
                .build())
            .build();
    }
}
