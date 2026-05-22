package com.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class CompactService {
    private static final Logger log = LoggerFactory.getLogger(CompactService.class);
    private static final int KEEP_RECENT = 10;

    private final AIService ai;
    private final int threshold;

    public CompactService(AIService ai, int threshold) {
        this.ai = ai;
        this.threshold = threshold;
    }

    /**
     * F3: micro-compact — 静默裁剪旧工具输出。
     * 超过 2000 字符的 ToolExecutionResultMessage 截断到 500 字符 + 标记。
     * 非 LLM 摘要，纯规则裁剪，零 API 成本。
     */
    public List<ChatMessage> microCompact(List<ChatMessage> history) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : history) {
            if (msg instanceof ToolExecutionResultMessage trm && trm.text().length() > 2000) {
                String trimmed = trm.text().substring(0, 500)
                    + "\n[... 已截断，原始输出 " + trm.text().length() + " 字符 ...]";
                result.add(new ToolExecutionResultMessage(trm.id(), null, trimmed));
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    public boolean shouldCompact(List<ChatMessage> history) {
        return estimateTokens(history) > threshold;
    }

    public List<ChatMessage> compact(List<ChatMessage> history) {
        log.info("Compacting history, size: {}", history.size());
        saveTranscript(history);

        int keepFrom = Math.max(0, history.size() - KEEP_RECENT);
        List<ChatMessage> recent = new ArrayList<>(history.subList(keepFrom, history.size()));
        List<ChatMessage> old = new ArrayList<>(history.subList(0, keepFrom));

        String summary = summarize(old);

        List<ChatMessage> result = new ArrayList<>();
        result.add(SystemMessage.from("[历史摘要] " + summary));
        result.addAll(recent);

        log.info("Compact done, new size: {}", result.size());
        return result;
    }

    private String summarize(List<ChatMessage> messages) {
        if (messages.isEmpty()) return "无历史对话";
        StringBuilder sb = new StringBuilder("请用一段话总结以下对话的核心内容：\n\n");
        for (ChatMessage msg : messages) {
            sb.append(msg.type()).append(": ");
            if (msg instanceof UserMessage um) {
                sb.append(um.singleText());
            } else {
                sb.append(msg.toString());
            }
            sb.append("\n");
        }
        try {
            return ai.chat(sb.toString());
        } catch (Exception e) {
            log.warn("Summarize failed", e);
            return "摘要生成失败，共 " + messages.size() + " 条消息";
        }
    }

    private int estimateTokens(List<ChatMessage> history) {
        return history.stream().mapToInt(m -> m.toString().length() / 4).sum();
    }

    private void saveTranscript(List<ChatMessage> history) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".agent", "transcripts");
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = dir.resolve("transcript_" + ts + ".txt");
            StringBuilder sb = new StringBuilder();
            for (ChatMessage msg : history) {
                sb.append("[").append(msg.type()).append("]\n");
                if (msg instanceof UserMessage um) sb.append(um.singleText());
                else sb.append(msg.toString());
                sb.append("\n\n");
            }
            Files.writeString(file, sb.toString());
            log.info("Transcript saved to {}", file);
        } catch (IOException e) {
            log.warn("Failed to save transcript", e);
        }
    }
}
