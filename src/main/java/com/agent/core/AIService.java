package com.agent.core;

import com.agent.config.AppConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

public class AIService {
    private final ChatLanguageModel model;

    public AIService(AppConfig config) {
        this.model = OpenAiChatModel.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .maxTokens(config.getMaxTokens())
            .build();
    }

    public ChatResponse chat(ChatRequest request) {
        return model.chat(request);
    }

    /** 简单对话：输入字符串，返回AI回复文本 */
    public String chat(String prompt) {
        ChatRequest req = ChatRequest.builder()
            .messages(UserMessage.from(prompt))
            .build();
        ChatResponse resp = model.chat(req);
        return resp.aiMessage().text();
    }

    /** 摘要生成：给定消息列表，返回摘要文本 */
    public String summarize(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder("请将以下对话历史压缩为简洁摘要，保留关键信息：\n\n");
        for (ChatMessage msg : messages) {
            sb.append(msg.type()).append(": ").append(msg.toString()).append("\n");
        }
        ChatRequest req = ChatRequest.builder()
            .messages(UserMessage.from(sb.toString()))
            .build();
        ChatResponse resp = model.chat(req);
        return resp.aiMessage().text();
    }
}
