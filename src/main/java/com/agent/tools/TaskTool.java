package com.agent.tools;

import com.agent.config.AppConfig;
import com.agent.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 子任务工具 — 独立上下文子 Agent，防递归 */
public class TaskTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AIService ai;
    private final ToolDispatcher dispatcher;
    private final ToolRegistry registry;
    private final AppConfig config;

    public TaskTool(AIService ai, ToolDispatcher dispatcher, ToolRegistry registry, AppConfig config) {
        this.ai = ai;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.config = config;
    }

    @Override public String name() { return "task"; }
    @Override public String description() { return "启动独立子 Agent 执行复杂子任务。子 Agent 有独立上下文，完成后返回结果摘要。"; }

    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String prompt = args.path("prompt").asText();
            int maxRounds = args.path("maxRounds").asInt(10);
            if (prompt.isEmpty()) return ToolResult.error("prompt is required");
            log.info("TaskTool spawning subagent");
            String result = SubagentRunner.run(ai, dispatcher, registry, config, prompt, maxRounds);
            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("TaskTool failed", e);
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("prompt", "子任务描述")
                .addIntegerProperty("maxRounds", "最大工具调用轮次，默认 10")
                .required(List.of("prompt"))
                .build())
            .build();
    }
}
