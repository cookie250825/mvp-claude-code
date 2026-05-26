package com.agent.core;

import com.agent.config.AppConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class AIService {
    private final ChatLanguageModel model;
    private final StreamingChatLanguageModel streamingModel;

    public AIService(AppConfig config) {
        this.model = OpenAiChatModel.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .maxTokens(config.getMaxTokens())
            .build();

        this.streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .maxTokens(config.getMaxTokens())
            .build();
    }

    /** 同步对话 — 用于摘要压缩、简单字符串问答 */
    public ChatResponse chat(ChatRequest request) {
        return model.chat(request);
    }

    public String chat(String prompt) {
        ChatRequest req = ChatRequest.builder()
            .messages(UserMessage.from(prompt))
            .build();
        return model.chat(req).aiMessage().text();
    }

    /**
     * 流式对话 — 用于主 AgentLoop。
     * 每收到一个 token 回调 onToken，完成后 CompletableFuture 返回 AiMessage（含工具调用信息）。
     */
    public CompletableFuture<AiMessage> streamingChat(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecs,
            Consumer<String> onToken) {

        CompletableFuture<AiMessage> future = new CompletableFuture<>();

        streamingModel.generate(messages, toolSpecs, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                if (onToken != null) onToken.accept(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                future.complete(response.content());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        return future;
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
        return model.chat(req).aiMessage().text();
    }
}
