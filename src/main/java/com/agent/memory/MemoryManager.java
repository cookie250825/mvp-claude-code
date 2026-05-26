package com.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆管理器 — 文件型记忆系统的核心。
 *
 * <h3>记忆存在哪里</h3>
 * ~/.agent/memory/ 目录下。MEMORY.md 是索引，每种类型一个 .md 文件存具体内容。
 *
 * <h3>为什么用文件而不是数据库</h3>
 * 1. 人可读 — 用户直接打开 ~/.agent/memory/ 看 AI 记住了什么
 * 2. Git 友好 — 可以把记忆文件纳入版本管理
 * 3. 零依赖 — 不需要 JDBC、Redis、任何外部服务
 *
 * <h3>二层加载模式</h3>
 * 第一层：MEMORY.md 索引全量注入 System Prompt（轻量，通常几百字）
 * 第二层：Agent 需要查看具体记忆时，用 FileTool 读取对应 .md 文件（按需）
 *
 * <h3>四种类型</h3>
 * USER / FEEDBACK / PROJECT / REFERENCE — 每种有不同的保存时机和生命周期。
 * 名称会作为文件名前缀，如 user_语言偏好.md。
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private final Path dir;
    private static final String INDEX_FILE = "MEMORY.md";

    public enum MemoryType {
        USER, FEEDBACK, PROJECT, REFERENCE
    }

    /**
     * @param memoryDir 记忆存储目录路径（~ 会被展开为 user.home）
     */
    public MemoryManager(String memoryDir) {
        this.dir = Paths.get(memoryDir.replace("~", System.getProperty("user.home")));
        ensureDir();     // 确保目录存在
        ensureIndex();   // 确保索引文件存在
    }

    /** 创建记忆目录（不存在时才建） */
    private void ensureDir() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create memory dir: {}", dir, e);
        }
    }

    /** 创建 MEMORY.md 索引文件（不存在时才建） */
    private void ensureIndex() {
        Path indexPath = dir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            try {
                Files.writeString(indexPath,
                    "# Memory Index\n\n记忆文件索引，由 Agent 自动维护。\n\n## 文件列表\n\n");
            } catch (IOException e) {
                log.error("Failed to create memory index", e);
            }
        }
    }

    /**
     * 读取完整 MEMORY.md 索引 — AgentLoop 启动时调用，注入 System Prompt。
     *
     * @return MEMORY.md 的完整内容
     */
    public String getIndex() {
        try {
            return Files.readString(dir.resolve(INDEX_FILE));
        } catch (IOException e) {
            log.warn("Failed to read memory index", e);
            return "（暂无记忆）";
        }
    }

    /**
     * 加载具体记忆文件的内容。
     *
     * @param fileName 要加载的文件名
     * @return 文件内容，失败返回 null
     */
    public String load(String fileName) {
        try {
            return Files.readString(dir.resolve(fileName));
        } catch (IOException e) {
            log.warn("Failed to load memory file: {}", fileName, e);
            return null;
        }
    }

    /**
     * 保存一条记忆 — 写入 .md 文件 + 更新 MEMORY.md 索引。
     *
     * @param type    记忆类型
     * @param name    记忆名称
     * @param desc    一行描述
     * @param content 完整记忆内容（写入 .md 文件）
     */
    public void save(MemoryType type, String name, String desc, String content) {
        String fileName = type.name().toLowerCase() + "_" + name + ".md";
        Path filePath = dir.resolve(fileName);
        try {
            Files.writeString(filePath, content);
            appendIndex(name, desc, fileName);  // 同时更新索引
            log.info("Saved memory: {}", fileName);
        } catch (IOException e) {
            log.error("Failed to save memory: {}", fileName, e);
        }
    }

    /**
     * 往 MEMORY.md 追加/更新一条索引记录。
     * 如果该文件名已存在，则更新旧条目；否则追加新条目。
     */
    private void appendIndex(String name, String desc, String fileName) {
        try {
            Path indexPath = dir.resolve(INDEX_FILE);
            String entry = String.format("- [%s](%s) — %s\n", name, fileName, desc);
            String existing = Files.readString(indexPath);

            if (!existing.contains(fileName)) {
                // 新记忆 → 追加
                Files.writeString(indexPath, entry, StandardOpenOption.APPEND);
            } else {
                // 已存在 → 替换旧条目
                String updated = existing.replaceAll(
                    "- \\[.*?\\]\\(" + fileName + "\\).*\\n",
                    entry
                );
                Files.writeString(indexPath, updated);
            }
        } catch (IOException e) {
            log.error("Failed to update memory index", e);
        }
    }

    /**
     * 列出指定类型的所有记忆文件。
     *
     * @param type 记忆类型
     * @return 文件名列表（不含路径）
     */
    public List<String> listFiles(MemoryType type) {
        List<String> result = new ArrayList<>();
        try {
            String prefix = type.name().toLowerCase() + "_";
            Files.list(dir)
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .forEach(p -> result.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.error("Failed to list memory files", e);
        }
        return result;
    }

    /**
     * 删除一条记忆 — 删文件 + 从索引中移除条目。
     *
     * @param fileName 要删除的记忆文件名
     */
    public void delete(String fileName) {
        try {
            Files.deleteIfExists(dir.resolve(fileName));
            // 从索引中移除该条目
            Path indexPath = dir.resolve(INDEX_FILE);
            String existing = Files.readString(indexPath);
            String updated = existing.replaceAll("- \\[.*?\\]\\(" + fileName + "\\).*\n", "");
            Files.writeString(indexPath, updated);
        } catch (IOException e) {
            log.error("Failed to delete memory: {}", fileName, e);
        }
    }
}
