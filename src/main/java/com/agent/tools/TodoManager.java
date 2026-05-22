package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** 轻量级 Todo 管理器，Claude Code 风格 */
public class TodoManager {
    private static final Logger log = LoggerFactory.getLogger(TodoManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final List<Map<String, Object>> items = new ArrayList<>();

    public synchronized String update(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            JsonNode itemsNode = args.path("items");
            if (!itemsNode.isArray()) return ToolResult.error("items array required").getContent();

            items.clear();
            for (JsonNode item : itemsNode) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("content", item.path("content").asText(""));
                entry.put("status", item.path("status").asText("pending"));
                entry.put("activeForm", item.path("activeForm").asText(null));
                items.add(entry);
            }
            return ToolResult.success(format()).getContent();
        } catch (Exception e) {
            return ToolResult.error(e.getMessage()).getContent();
        }
    }

    public synchronized boolean hasOpenItems() {
        return items.stream().anyMatch(i ->
            "pending".equals(i.get("status")) || "in_progress".equals(i.get("status")));
    }

    public synchronized String format() {
        if (items.isEmpty()) return "Todo list empty.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String marker = switch ((String) item.get("status")) {
                case "completed" -> "[x]";
                case "in_progress" -> "[>]";
                default -> "[ ]";
            };
            sb.append(marker).append(" ").append(item.get("content"));
            String active = (String) item.get("activeForm");
            if (active != null) sb.append(" (").append(active).append(")");
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
