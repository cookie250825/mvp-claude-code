package com.agent.tools;

import com.agent.tools.mcp.MCPToolAdapter;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.*;

public class ToolRegistry {
    private final List<ToolSpecification> specs = new ArrayList<>();
    private final Map<String, BaseTool> tools = new HashMap<>();

    /** 注册内置工具，使用工具自带的精确 JSON Schema */
    public void register(BaseTool tool) {
        tools.put(tool.name(), tool);
        specs.add(tool.getSpec());
    }

    /** 注册 MCP 工具适配器 */
    public void registerMCP(MCPToolAdapter adapter) {
        tools.put(adapter.name(), adapter);
        specs.add(adapter.toToolSpecification());
    }

    public List<ToolSpecification> getAllSpecs() {
        return new ArrayList<>(specs);
    }

    /** 返回不包含指定工具的副本（用于子 Agent 防递归） */
    public ToolRegistry without(String name) {
        ToolRegistry copy = new ToolRegistry();
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            if (!entry.getKey().equals(name)) {
                copy.tools.put(entry.getKey(), entry.getValue());
            }
        }
        for (ToolSpecification spec : specs) {
            if (!spec.name().equals(name)) {
                copy.specs.add(spec);
            }
        }
        return copy;
    }

    public Map<String, BaseTool> getAllTools() {
        return new HashMap<>(tools);
    }

    /** 返回工具名称列表，用于 System Prompt 中的工具声明 */
    public String describeNames() {
        StringBuilder sb = new StringBuilder();
        for (String name : tools.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

}
