package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Todo 状态管理器 — 维护当前任务列表的内存状态。
 *
 * <h3>职责</h3>
 * 1. update(arguments) — 全量替换 Todo 列表（TodoWriteTool 调用）
 * 2. hasOpenItems()    — 有没有未完成的待办项（AgentLoop 每轮检查）
 * 3. format()          — 格式化成可读文本（展示给 LLM 看）
 *
 * <h3>线程安全</h3>
 * 所有读写方法都用 synchronized 保护。AgentLoop 和 ToolDispatcher
 * 可能在同线程执行，但加锁是防御性的——将来如果引入多线程不会有问题。
 */
public class TodoManager {
    private static final Logger log = LoggerFactory.getLogger(TodoManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Todo 列表（每一项是 content + status + activeForm 的 Map） */
    private final List<Map<String, Object>> items = new ArrayList<>();

    /**
     * 全量更新 Todo 列表。
     * 每次调用完全替换旧列表——不是增量追加。
     *
     * @param arguments JSON 参数：{"items":[{"content":"...","status":"pending"},...]}
     * @return 格式化后的 Todo 列表文本
     */
    public synchronized String update(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            JsonNode itemsNode = args.path("items");
            if (!itemsNode.isArray()) return ToolResult.error("items array required").getContent();

            items.clear();  // 全量替换，不是追加
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

    /**
     * 检查是否有未完成的待办项。
     * "未完成" = status 是 pending 或 in_progress（不是 completed）。
     *
     * @return true = 还有没做完的任务
     */
    public synchronized boolean hasOpenItems() {
        return items.stream().anyMatch(i ->
            "pending".equals(i.get("status")) || "in_progress".equals(i.get("status")));
    }

    /**
     * 格式化 Todo 列表为可读文本。
     * 格式：[x] 已完成  [>] 进行中  [ ] 待办
     *
     * @return 格式化的 Todo 文本
     */
    public synchronized String format() {
        if (items.isEmpty()) return "Todo list empty.";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);

            // 图标映射：completed → [x]，in_progress → [>]，pending → [ ]
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
