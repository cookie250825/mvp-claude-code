package com.agent.core;

import com.agent.tools.BaseTool;
import com.agent.tools.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具分发器 — LLM 说"我要用哪个工具"，这里查表找到它并执行。
 *
 * <h3>为什么是 Map 查表而不是 @Tool 注解反射</h3>
 * 1. 简单：一个 HashMap，任何 Java 程序员看一眼就懂
 * 2. 支持运行时动态注册：MCP 工具是运行时从外部服务器下发的，
 *    不可能提前在代码里写 @Tool 注解。Dispatch Map 可以随时 register()/unregister()
 * 3. 子 Agent 隔离：withoutTaskTool() 一行代码创建不含 task 的 Map 副本，
 *    注解反射做不到这种运行时裁剪
 *
 * <h3>Dispatch Map 模式</h3>
 * map = {"file" → FileTool, "bash" → BashTool, "task" → TaskTool, ...}
 * LLM 返回 ToolExecutionRequest(name="bash", arguments="{...}")
 * → map.get("bash") → bashTool.execute("{...}")
 */
public class ToolDispatcher {

    /** 工具名 → 工具实例 的映射表 */
    private final Map<String, BaseTool> map = new HashMap<>();

    /**
     * 注册一个工具到 Dispatch Map。
     * 工具名（BaseTool.name()）作为 key，工具实例作为 value。
     *
     * @param tool 要注册的工具实例
     */
    public void register(BaseTool tool) {
        map.put(tool.name(), tool);
    }

    /**
     * 注销一个工具 — 从 Map 中移除。
     * 主要用于 MCP 连接断开时清理外部工具。
     *
     * @param name 要移除的工具名
     */
    public void unregister(String name) {
        map.remove(name);
    }

    /**
     * 创建不含 task 工具的副本 — 用于子 Agent 防递归。
     *
     * <h3>为什么这样做能防递归</h3>
     * 子 Agent 的工具列表里没有 "task"，
     * LLM 不知道有这个工具 = 永远不会产生"再开一个子 Agent"的想法。
     * 在能力层截断，不是执行层拦截。
     *
     * @return 新的 ToolDispatcher，所有工具都一样，唯独没有 task
     */
    public ToolDispatcher withoutTaskTool() {
        ToolDispatcher sub = new ToolDispatcher();
        map.forEach((k, v) -> {
            if (!k.equals("task")) sub.register(v);
        });
        return sub;
    }

    /**
     * 执行 LLM 请求的工具调用。
     *
     * <h3>流程</h3>
     * 1. 从 Map 按名称找工具
     * 2. 没找到 → 返回 error
     * 3. 找到了 → 调 tool.execute(arguments)
     * 4. execute 抛异常 → 包装成 error 返回（不让异常炸掉整个循环）
     *
     * @param req LLM 返回的工具调用请求（含工具名 + JSON 参数）
     * @return 工具执行结果（成功或失败）
     */
    public ToolResult execute(ToolExecutionRequest req) {
        BaseTool tool = map.get(req.name());
        if (tool == null) return ToolResult.error("Unknown tool: " + req.name());
        try {
            return tool.execute(req.arguments());
        } catch (Exception e) {
            return ToolResult.error("Tool error: " + e.getMessage());
        }
    }

    /**
     * 返回所有已注册工具的只读 Map。
     *
     * @return 不可修改的工具 Map
     */
    public Map<String, BaseTool> getAll() {
        return Map.copyOf(map);
    }
}
