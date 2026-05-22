package com.agent.core;

import com.agent.tools.BaseTool;
import com.agent.tools.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.HashMap;
import java.util.Map;

public class ToolDispatcher {
    private final Map<String, BaseTool> map = new HashMap<>();

    public void register(BaseTool tool) {
        map.put(tool.name(), tool);
    }

    public void unregister(String name) {
        map.remove(name);
    }

    public ToolDispatcher withoutTaskTool() {
        ToolDispatcher sub = new ToolDispatcher();
        map.forEach((k, v) -> {
            if (!k.equals("task")) sub.register(v);
        });
        return sub;
    }

    public ToolResult execute(ToolExecutionRequest req) {
        BaseTool tool = map.get(req.name());
        if (tool == null) return ToolResult.error("Unknown tool: " + req.name());
        try {
            return tool.execute(req.arguments());
        } catch (Exception e) {
            return ToolResult.error("Tool error: " + e.getMessage());
        }
    }

    public Map<String, BaseTool> getAll() {
        return Map.copyOf(map);
    }
}
