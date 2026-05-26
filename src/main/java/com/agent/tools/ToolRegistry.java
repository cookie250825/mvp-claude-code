package com.agent.tools;

import com.agent.tools.mcp.MCPToolAdapter;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.*;

/**
 * 工具注册表 — 管理所有工具的 JSON Schema 和实例。
 *
 * <h3>两个职责</h3>
 * 1. specs 列表 — 存所有工具的 JSON Schema，拼进 ChatRequest 发给 LLM
 * 2. tools Map   — 存所有工具的实例，执行时按名称查找
 *
 * <h3>两类注册</h3>
 * register(BaseTool)       — 注册内置工具（FileTool、BashTool 等）
 * registerMCP(MCPToolAdapter) — 注册 MCP 外部工具（运行时从外部服务器下发）
 *
 * <h3>without() 方法</h3>
 * 返回一个新 ToolRegistry 副本，不含指定名称的工具。
 * 用于子 Agent：registry.without("task") 创建不含 task 的工具注册表。
 */
public class ToolRegistry {

    /** 所有工具的 JSON Schema 列表（发给 LLM） */
    private final List<ToolSpecification> specs = new ArrayList<>();

    /** 工具名 → 工具实例（执行时查表） */
    private final Map<String, BaseTool> tools = new HashMap<>();

    /**
     * 注册内置工具。
     *
     * @param tool 实现了 BaseTool 的内置工具实例
     */
    public void register(BaseTool tool) {
        tools.put(tool.name(), tool);
        specs.add(tool.getSpec());
    }

    /**
     * 注册 MCP 外部工具。
     * 和内置工具用同样的数据结构，只是 JSON Schema 来自外部 MCP Server。
     *
     * @param adapter MCP 工具适配器
     */
    public void registerMCP(MCPToolAdapter adapter) {
        tools.put(adapter.name(), adapter);
        specs.add(adapter.toToolSpecification());
    }

    /**
     * 返回所有工具的 JSON Schema 列表。
     * 这个列表会被拼进 ChatRequest.toolSpecifications()，告诉 LLM 有哪些武器。
     *
     * @return 新副本（防外部修改）
     */
    public List<ToolSpecification> getAllSpecs() {
        return new ArrayList<>(specs);
    }

    /**
     * 创建不含指定工具的副本 — 用于子 Agent 防递归。
     * 子 Agent 调用 registry.without("task") 获得一个没有 task 工具的新注册表。
     *
     * @param name 要排除的工具名
     * @return 不含该工具的新 ToolRegistry
     */
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

    /**
     * 返回所有工具实例的只读 Map。
     *
     * @return 不可修改的工具 Map
     */
    public Map<String, BaseTool> getAllTools() {
        return new HashMap<>(tools);
    }

    /**
     * 返回工具名称列表 — 用于 System Prompt 中的工具声明行。
     * 如：file, bash, search, task, TodoWrite, background_run, check_background
     *
     * @return 逗号分隔的工具名
     */
    public String describeNames() {
        StringBuilder sb = new StringBuilder();
        for (String name : tools.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }
}
