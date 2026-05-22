package com.agent.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;

/** Claude Code 风格的 TodoWrite 工具 */
public class TodoWriteTool extends BaseTool {
    private final TodoManager todoManager;

    public TodoWriteTool(TodoManager todoManager) {
        this.todoManager = todoManager;
    }

    @Override public String name() { return "TodoWrite"; }
    @Override public String description() { return "更新任务跟踪列表。每完成一步都更新状态。"; }

    @Override
    public ToolResult execute(String arguments) {
        return ToolResult.success(todoManager.update(arguments));
    }

    @Override
    public ToolSpecification getSpec() {
        JsonObjectSchema itemSchema = JsonObjectSchema.builder()
            .addStringProperty("content", "任务描述")
            .addEnumProperty("status", List.of("pending", "in_progress", "completed"), "状态")
            .addStringProperty("activeForm", "进行中的动词形式")
            .required(List.of("content", "status"))
            .build();
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addProperty("items", JsonArraySchema.builder().items(itemSchema).build())
                .required(List.of("items"))
                .build())
            .build();
    }
}
