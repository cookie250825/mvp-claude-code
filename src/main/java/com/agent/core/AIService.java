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

/**
 * AI 服务封装 — 对 DeepSeek API 的统一入口。
 *
 * <h3>双模设计（为什么有两个 Model）</h3>
 * 1. model（同步 ChatLanguageModel）
 *    用于 CompactService 摘要压缩、简单字符串问答。
 *    这些场景不面向用户，不需要流式输出，同步调完拿结果就行。
 *
 * 2. streamingModel（异步 StreamingChatLanguageModel）
 *    用于 AgentLoop 主对话。
 *    必须流式——用户坐在终端前等，token 一个一个蹦出来是基本体验。
 *
 * <h3>CompletableFuture 桥接</h3>
 * 流式 API 是异步的（调完就返回，token 通过回调推过来），
 * 但 while(true) 循环需要等 AI 返回完整响应才能继续。
 * 用 CompletableFuture 做桥梁：回调里标记完成，循环里 future.get() 阻塞等。
 */
public class AIService {

    /** 同步模型 — 用于不需要流式输出的场景（摘要压缩、简单问答） */
    private final ChatLanguageModel model;

    /** 流式模型 — 用于主对话循环，token 实时打印 */
    private final StreamingChatLanguageModel streamingModel;

    /**
     * @param config 应用配置（baseUrl、apiKey、modelName、temperature、maxTokens）
     */
    public AIService(AppConfig config) {
        // 同步模型：直接用 OpenAiChatModel（兼容 DeepSeek）
        this.model = OpenAiChatModel.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .maxTokens(config.getMaxTokens())
            .build();

        // 流式模型：用 OpenAiStreamingChatModel
        this.streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .modelName(config.getModelName())
            .temperature(config.getTemperature())
            .maxTokens(config.getMaxTokens())
            .build();
    }

    /**
     * 同步对话 — 发送完整 ChatRequest，阻塞等待完整响应。
     * 用于 CompactService 摘要、子 Agent（子 Agent 不需要流式面向用户）。
     *
     * @param request 包含 messages + toolSpecifications 的完整请求
     * @return LLM 的完整响应（含工具调用信息）
     */
    public ChatResponse chat(ChatRequest request) {
        return model.chat(request);
    }

    /**
     * 同步对话（简化版）— 只传一段文字，拿回文字回复。
     * 用于 CompactService.summarize() 和不需要工具的场景。
     *
     * @param prompt 要发给 LLM 的文本
     * @return LLM 的文本回复
     */
    public String chat(String prompt) {
        ChatRequest req = ChatRequest.builder()
            .messages(UserMessage.from(prompt))
            .build();
        return model.chat(req).aiMessage().text();
    }

    /**
     * 流式对话 — 用于 AgentLoop 主循环。
     *
     * <h3>工作方式</h3>
     * 1. 调用 streamingModel.generate()，立即返回（异步）
     * 2. 每收到一个 token → 回调 onToken（实时打印到终端）
     * 3. 流结束 → onComplete 触发 → CompletableFuture 标记完成
     * 4. 调用方 future.get() 阻塞拿到最终 AiMessage（含工具调用）
     *
     * <h3>为什么不直接用 AiServices</h3>
     * AiServices 的流式代理是黑盒——你不知道 onNext/onComplete/onError 里发生了什么。
     * 手写 StreamingResponseHandler 让你在三个回调里精确控制行为。
     *
     * @param messages  完整消息列表（System Prompt + Memory + History）
     * @param toolSpecs 工具 JSON Schema 列表（告诉 LLM 有哪些武器）
     * @param onToken   每收到一个 token 的回调（null 表示不打印）
     * @return CompletableFuture，get() 阻塞直到流式完成，返回含工具调用的 AiMessage
     */
    public CompletableFuture<AiMessage> streamingChat(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecs,
            Consumer<String> onToken) {

        CompletableFuture<AiMessage> future = new CompletableFuture<>();

        streamingModel.generate(messages, toolSpecs, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                // LLM 每吐出一个 token，立即回调这里
                if (onToken != null) onToken.accept(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                // 流式输出全部完成，response.content() 是最终 AiMessage
                // AiMessage 里有 text()（纯文本）和 hasToolExecutionRequests()（有没有工具调用）
                future.complete(response.content());
            }

            @Override
            public void onError(Throwable error) {
                // API 调用失败 → CompletableFuture 进入异常状态
                // AgentLoop 的 try-catch 会捕获并注入 <error> 消息
                future.completeExceptionally(error);
            }
        });

        return future;
    }
}
