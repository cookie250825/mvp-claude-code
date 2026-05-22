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

/** MCP 工具适配为 BaseTool，使用 MCP Server 提供的 inputSchema */
public class MCPToolAdapter extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(MCPToolAdapter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final MCPClient client;
    private final String toolName;
    private final String toolDesc;
    private final JsonNode inputSchema;

    public MCPToolAdapter(MCPClient client, String toolName, String toolDesc, JsonNode inputSchema) {
        this.client = client;
        this.toolName = toolName;
        this.toolDesc = toolDesc;
        this.inputSchema = inputSchema;
    }

    @Override public String name() { return toolName; }
    @Override public String description() { return "[MCP] " + toolDesc; }

    @Override
    public ToolResult execute(String arguments) {
        try {
            Map<String, Object> args = mapper.readValue(arguments, Map.class);
            String result = client.callTool(toolName, args);
            return ToolResult.success(FileTool.truncate(result));
        } catch (Exception e) {
            log.error("MCP tool {} failed", toolName, e);
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return toToolSpecification();
    }

    public ToolSpecification toToolSpecification() {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        if (inputSchema != null) {
            JsonNode props = inputSchema.path("properties");
            if (props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = props.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String pname = entry.getKey();
                    JsonNode pnode = entry.getValue();
                    String ptype = pnode.path("type").asText("string");
                    String pdesc = pnode.path("description").asText("");
                    switch (ptype) {
                        case "integer", "number" -> builder.addIntegerProperty(pname, pdesc);
                        case "boolean" -> builder.addBooleanProperty(pname, pdesc);
                        case "array" -> builder.addProperty(pname, JsonArraySchema.builder().build());
                        default -> builder.addStringProperty(pname, pdesc);
                    }
                }
            }
            JsonNode req = inputSchema.path("required");
            if (req.isArray()) req.forEach(v -> required.add(v.asText()));
        }

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
