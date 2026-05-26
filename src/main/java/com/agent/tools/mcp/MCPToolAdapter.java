package com.agent.tools.mcp;

import com.agent.tools.BaseTool;
import com.agent.tools.FileTool;
import com.agent.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP 工具适配器 — 把外部的 MCP Server 工具包装成 BaseTool。
 *
 * <h3>为什么需要这个类</h3>
 * MCP Server 通过 tools/list 返回的 inputSchema 是原始 JSON Schema，
 * LangChain4j 不认识这个格式。需要把它转换成 LangChain4j 的 JsonObjectSchema。
 *
 * <h3>转换细节（toToolSpecification）</h3>
 * 遍历 inputSchema.properties，根据每个属性的 type 字段：
 * - "string"  → addStringProperty
 * - "integer"/"number" → addIntegerProperty
 * - "boolean" → addBooleanProperty
 * - "array"   → JsonArraySchema
 * - required 字段转成 required List
 *
 * <h3>执行流程</h3>
 * LLM 调用 MCP 工具 → ToolDispatcher 路由到这里 → execute() →
 * client.callTool(name, args) 通过 JSON-RPC 发到 MCP Server → 返回结果
 */
public class MCPToolAdapter extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(MCPToolAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** MCP 客户端（和 MCP Server 的通信管道） */
    private final MCPClient client;
    /** 工具名（来自 MCP Server） */
    private final String toolName;
    /** 工具描述（来自 MCP Server） */
    private final String toolDesc;
    /** 工具的 JSON Schema（来自 MCP Server 的 tools/list 响应） */
    private final JsonNode inputSchema;

    /**
     * @param client      MCP 客户端（已连接）
     * @param toolName    工具名
     * @param toolDesc    工具描述
     * @param inputSchema 原始 JSON Schema（MCP Server 提供的完整参数定义）
     */
    public MCPToolAdapter(MCPClient client, String toolName, String toolDesc, JsonNode inputSchema) {
        this.client = client;
        this.toolName = toolName;
        this.toolDesc = toolDesc;
        this.inputSchema = inputSchema;
    }

    @Override public String name() { return toolName; }
    @Override public String description() { return "[MCP] " + toolDesc; }

    /**
     * 调用 MCP Server 的工具。
     *
     * @param arguments JSON 参数字符串，会被转成 Map<String,Object> 发给 MCP Server
     * @return MCP Server 返回的结果文本
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            Map<String, Object> args = mapper.readValue(arguments, Map.class);
            String result = client.callTool(toolName, args);
            return ToolResult.success(FileTool.truncate(result));  // 输出截断保护
        } catch (Exception e) {
            log.error("MCP tool {} failed", toolName, e);
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return toToolSpecification();
    }

    /**
     * 把 MCP 的 inputSchema（原始 JSON）转成 LangChain4j JsonObjectSchema。
     *
     * <h3>转换规则</h3>
     * 遍历 properties 里的每个字段，根据 type 字段决定 LangChain4j 的属性类型。
     * required 数组里的字段名直接加入 required List。
     *
     * @return LangChain4j 格式的 ToolSpecification
     */
    public ToolSpecification toToolSpecification() {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        if (inputSchema != null) {
            // 遍历 properties
            JsonNode props = inputSchema.path("properties");
            if (props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = props.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String pname = entry.getKey();
                    JsonNode pnode = entry.getValue();
                    String ptype = pnode.path("type").asText("string");
                    String pdesc = pnode.path("description").asText("");

                    // 根据类型创建对应的属性
                    switch (ptype) {
                        case "integer", "number" -> builder.addIntegerProperty(pname, pdesc);
                        case "boolean" -> builder.addBooleanProperty(pname, pdesc);
                        case "array" -> builder.addProperty(pname, JsonArraySchema.builder().build());
                        default -> builder.addStringProperty(pname, pdesc);  // 默认 string
                    }
                }
            }

            // 读取 required 字段
            JsonNode req = inputSchema.path("required");
            if (req.isArray()) req.forEach(v -> required.add(v.asText()));
        }

        // 兜底：如果 MCP Server 没给 Schema，给一个通用的 arguments 参数
        if (required.isEmpty() && inputSchema == null) {
            builder.addStringProperty("arguments", "Tool arguments as JSON");
            required.add("arguments");
        }

        return ToolSpecification.builder()
            .name(toolName)
            .description(toolDesc)
            .parameters(builder.required(required).build())
            .build();
    }
}
