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
    @Override public String description() {
        return "启动专用子 Agent 执行复杂子任务。四种子 Agent：explore(只读探索) / plan(方案设计) / verification(验证审查) / general(通用执行)。";
    }

    /**
     * 启动子 Agent — 按类型创建不同安全边界的子 Agent。
     *
     * <h3>四种类型</h3>
     * explore      — 只读探索，只能 file(read/list/exists) + search，禁止写文件和执行命令
     * plan         — 方案设计，只能 file + search，禁止所有执行操作，只输出方案文本
     * verification — 验证审查，可读文件+诊断命令，禁止修改代码
     * general      — 通用执行，除 task 外全部工具可用（默认类型）
     *
     * @param arguments JSON 参数：prompt(必填) / agent_type(默认general) / maxRounds(默认10)
     * @return 子 Agent 的最终结果
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String prompt = args.path("prompt").asText();
            String typeStr = args.path("agent_type").asText("general");
            int maxRounds = args.path("maxRounds").asInt(10);

            if (prompt.isEmpty()) return ToolResult.error("prompt is required");

            SubagentRunner.SubagentType type = switch (typeStr.toLowerCase()) {
                case "explore" -> SubagentRunner.SubagentType.EXPLORE;
                case "plan" -> SubagentRunner.SubagentType.PLAN;
                case "verification" -> SubagentRunner.SubagentType.VERIFICATION;
                default -> SubagentRunner.SubagentType.GENERAL;
            };

            log.info("TaskTool spawning subagent type={}", type);
            String result = SubagentRunner.run(ai, dispatcher, registry, config, prompt, maxRounds, type);
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
                .addEnumProperty("agent_type", List.of("explore", "plan", "verification", "general"),
                    "子 Agent 类型。explore=只读探索, plan=方案设计, verification=验证审查, general=通用执行")
                .addIntegerProperty("maxRounds", "最大工具调用轮次，默认 10")
                .required(List.of("prompt"))
                .build())
            .build();
    }
}
