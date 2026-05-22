package com.agent.tools;

import dev.langchain4j.agent.tool.ToolSpecification;

/** 所有工具的基类。每个工具负责提供自己的精确 JSON Schema */
public abstract class BaseTool {
    public abstract String name();
    public abstract String description();
    public abstract ToolResult execute(String arguments);

    /** 返回工具的精确 JSON Schema，用于发送给 LLM */
    public abstract ToolSpecification getSpec();
}
