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

/**
 * 子任务委托工具 — LLM 通过它来启动子 Agent 完成独立任务。
 *
 * <h3>什么场景用</h3>
 * 大规模探索任务（如"在项目里找所有 SQL 注入风险"），
 * LLM 调 task(prompt="...", maxRounds=10) 开一个子 Agent 专门干。
 * 子 Agent 有独立上下文，完成任务后返回摘要给父 Agent。
 *
 * <h3>防递归</h3>
 * 子 Agent 的工具集不含 task 本身——SubagentRunner.withoutTaskTool()。
 * 子 Agent 不能创建孙 Agent。
 */
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

    /**
     * 启动子 Agent — 同步等待完成后返回结果。
     *
     * @param arguments JSON 参数：prompt(必填) / maxRounds(默认10)
     * @return 子 Agent 的最终回复
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String prompt = args.path("prompt").asText();
            int maxRounds = args.path("maxRounds").asInt(10);  // 默认 10 轮

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
