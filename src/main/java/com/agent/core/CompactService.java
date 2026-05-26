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

/**
 * 上下文压缩服务 — 解决 LLM 对话太长的问题。
 *
 * <h3>为什么需要压缩</h3>
 * LLM 有上下文窗口限制（比如 DeepSeek 是 128K token）。
 * 对话长了——读几十个文件、跑几十条命令——消息列表会超过窗口。
 * 必须裁剪旧消息，否则 API 直接报错。
 *
 * <h3>三层递进策略（从便宜到贵）</h3>
 * 不是一层压缩，是三种策略按需触发：
 *
 * 1. Micro（静默裁剪）— 每轮执行，零 API 成本
 *    工具返回了 5000 字的文件内容，但 LLM 不需要全部记住。
 *    只保留前 500 字 + "...已截断" 标记。
 *    解决 80% 的膨胀问题。
 *
 * 2. Auto（LLM 摘要）— token 超阈值时触发，一次 LLM 调用
 *    旧的对话消息太多，调 LLM 做个语义摘要，
 *    保留最近 10 条（保持上下文连贯），旧的换成一句话概括。
 *
 * 3. Manual（用户触发）— 用户敲 /compact 时触发
 *    不做保护，全量压缩。用户主动触发代表他觉得 AI "忘事"了。
 *
 * <h3>为什么 Micro 放在 Auto 前面</h3>
 * Micro 零成本（纯字符串操作），每轮都跑。
 * 如果 Micro 已经把上下文压到阈值以下，Auto 根本不会触发。
 * 只在 Micro 扛不住时才请 LLM 出马——省钱。
 */
public class CompactService {
    private static final Logger log = LoggerFactory.getLogger(CompactService.class);

    /** Auto compact 保留最近多少条消息不压缩 */
    private static final int KEEP_RECENT = 10;

    /** 工具输出超过这个长度才裁剪（太短的不值得） */
    private static final int MICRO_TRIM_THRESHOLD = 2000;

    /** 裁剪后保留的字符数 */
    private static final int MICRO_KEEP_LENGTH = 500;

    private final AIService ai;
    private final int threshold;  // token 阈值，超过就触发 auto compact

    /**
     * @param ai        AI 服务（用于 Auto/Manual 层的 LLM 摘要）
     * @param threshold token 阈值，超过这个数触发 auto compact
     */
    public CompactService(AIService ai, int threshold) {
        this.ai = ai;
        this.threshold = threshold;
    }

    /**
     * Micro compact — 静默裁剪旧的工具输出。
     *
     * <h3>做了什么</h3>
     * 遍历所有消息，找到 ToolExecutionResultMessage，
     * 如果内容超过 2000 字符，截断到前 500 字符 + "已截断" 标记。
     *
     * <h3>为什么只裁剪工具输出</h3>
     * 对话文本（用户消息、AI 回复）通常很短。
     * 真正撑爆上下文的是工具返回的大文件、长命令输出。
     *
     * <h3>为什么是 500 而不是全删</h3>
     * 全删 LLM 不知道发生了什么。留 500 字让 LLM 知道"我读了文件、文件大概长这样"。
     *
     * @param history 当前对话历史
     * @return 裁剪后的新 List（不修改原 List）
     */
    public List<ChatMessage> microCompact(List<ChatMessage> history) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : history) {
            if (msg instanceof ToolExecutionResultMessage trm && trm.text().length() > MICRO_TRIM_THRESHOLD) {
                // 太长 → 截断
                String trimmed = trm.text().substring(0, MICRO_KEEP_LENGTH)
                    + "\n[... 已截断，原始输出 " + trm.text().length() + " 字符 ...]";
                result.add(new ToolExecutionResultMessage(trm.id(), null, trimmed));
            } else {
                // 不长 → 原样保留
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 判断是否需要 auto compact。
     *
     * <h3>估算方式</h3>
     * 简单粗暴：所有消息转成字符串，字符数除以 4 ≈ token 数。
     * 不准但够用——我们只需要知道"是不是差不多该压了"。
     *
     * @param history 当前对话历史
     * @return true = token 超阈值，需要压缩
     */
    public boolean shouldCompact(List<ChatMessage> history) {
        return estimateTokens(history) > threshold;
    }

    /**
     * Auto compact — LLM 语义摘要。
     *
     * <h3>做了什么</h3>
     * 1. 先把压缩前的对话转录到磁盘（出事能回溯）
     * 2. 把消息分成两部分：旧消息（需要摘要）+ 最近 10 条（保留不碰）
     * 3. 调 LLM 把旧消息压缩成一段话
     * 4. 返回：[摘要消息] + [最近 10 条原始消息]
     *
     * <h3>为什么保留最近 10 条</h3>
     * 全量替换（只剩摘要）LLM 会丢失最近的上下文线索。
     * 保留 10 条让 LLM 对"刚刚在聊什么"有完整的记忆。
     *
     * @param history 当前对话历史
     * @return 压缩后的新 List
     */
    public List<ChatMessage> compact(List<ChatMessage> history) {
        log.info("Compacting history, size: {}", history.size());

        // 压缩前先保存一份到磁盘（转录）
        saveTranscript(history);

        // 切分：旧消息（需要摘要）vs 最近 10 条（保留不碰）
        int keepFrom = Math.max(0, history.size() - KEEP_RECENT);
        List<ChatMessage> recent = new ArrayList<>(history.subList(keepFrom, history.size()));
        List<ChatMessage> old = new ArrayList<>(history.subList(0, keepFrom));

        // 调 LLM 把旧消息压缩
        String summary = summarize(old);

        // 拼装：[摘要] + [最近 10 条]
        List<ChatMessage> result = new ArrayList<>();
        result.add(SystemMessage.from("[历史摘要] " + summary));
        result.addAll(recent);

        log.info("Compact done, new size: {}", result.size());
        return result;
    }

    /**
     * 调 LLM 把消息列表压缩成一段话。
     *
     * @param messages 需要被压缩的消息
     * @return 摘要文本（如果 LLM 调用失败，返回降级描述）
     */
    private String summarize(List<ChatMessage> messages) {
        if (messages.isEmpty()) return "无历史对话";

        // 构建摘要 prompt：把所有消息拼成一段文本，让 LLM 总结
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
            // 降级：LLM 摘要失败，至少告诉系统"有多少条消息"
            log.warn("Summarize failed", e);
            return "摘要生成失败，共 " + messages.size() + " 条消息";
        }
    }

    /**
     * 粗略估算 token 数。
     * 规则：字符数 / 4（英文约 4 字符 = 1 token，中文略多但够用）。
     * 不需要精确，只需要知道"是不是差不多该压了"。
     *
     * @param history 对话历史
     * @return 估算的 token 数
     */
    /**
     * 估算 token 数 — 公开给 AgentLoop 做 token 预算检查。
     * 规则：字符数 / 4。不准但够用。
     */
    public int estimateTokens(List<ChatMessage> history) {
        return history.stream().mapToInt(m -> m.toString().length() / 4).sum();
    }

    /**
     * 压缩前把对话转录到磁盘。
     * 路径：~/.agent/transcripts/transcript_时间戳.txt
     * 作用：万一压缩坏了，能从原稿恢复。
     *
     * @param history 压缩前的完整对话历史
     */
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
