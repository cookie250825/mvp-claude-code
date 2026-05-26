package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/**
 * Todo 写工具 — 仿 Claude Code 的任务追踪系统。
 *
 * <h3>LLM 怎么用它</h3>
 * 接到复杂任务（如"帮我建一个 Spring Boot 项目骨架"）后，
 * LLM 先调 TodoWrite 拆成步骤清单，每完成一步即时更新状态。
 *
 * <h3>AgentLoop 怎么配合</h3>
 * 连续 3 轮没用 TodoWrite → 检查 TodoManager 还有待办项 →
 * 注入 <reminder> 提醒 LLM 更新 Todo。
 *
 * <h3>JSON Schema 设计</h3>
 * items 数组里每项有 content（描述）、status（pending/in_progress/completed）、
 * activeForm（可选，进行中的动词形式）。
 * 嵌套 Schema 让 LLM 一次填对格式，不用来回纠正。
 */
public class TodoWriteTool extends BaseTool {
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 内存中的 Todo 状态管理器（不持久化） */
    private final TodoManager todoManager;

    public TodoWriteTool(TodoManager todoManager) {
        this.todoManager = todoManager;
    }

    @Override public String name() { return "TodoWrite"; }
    @Override public String description() { return "更新任务跟踪列表。每完成一步都更新状态。"; }

    /**
     * 更新 Todo 列表。
     * 每次调用会**全量替换**当前 Todo 列表（不是增量追加）。
     *
     * @param arguments JSON 参数：items 数组
     * @return 格式化后的 Todo 列表
     */
    @Override
    public ToolResult execute(String arguments) {
        return ToolResult.success(todoManager.update(arguments));
    }

    @Override
    public ToolSpecification getSpec() {
        // 嵌套 JSON Schema：items 数组里每项有自己的属性
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
