package com.agent.core;

import com.agent.config.AppConfig;
import com.agent.memory.MemoryManager;
import com.agent.tools.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final AppConfig config;

    public ContextBuilder(ToolRegistry toolRegistry, MemoryManager memoryManager, AppConfig config) {
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.config = config;
    }

    public ChatRequest build(List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(SystemMessage.from(
            "你是一个 Java 开发助手。\n" +
            "工作目录: " + config.getWorkspace() + "\n" +
            "你可以使用提供的工具来读取文件、执行命令、搜索内容。"
        ));

        if (memoryManager != null) {
            try {
                String memoryIndex = memoryManager.getIndex();
                if (memoryIndex != null && !memoryIndex.isEmpty()) {
                    messages.add(SystemMessage.from("可用记忆:\n" + memoryIndex));
                }
            } catch (Exception e) {
                log.warn("Failed to load memory index", e);
            }
        }

        messages.addAll(history);

        return ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolRegistry.getAllSpecs())
            .build();
    }

    public String getMemoryIndex() {
        if (memoryManager == null) return "无记忆";
        try {
            return memoryManager.getIndex();
        } catch (Exception e) {
            return "记忆加载失败: " + e.getMessage();
        }
    }
}
