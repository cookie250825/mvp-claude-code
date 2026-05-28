package com.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆管理器 — 文件型记忆系统的核心。
 *
 * <h3>存在形式</h3>
 * ~/.agent/memory/ 下 MEMORY.md 为索引，每条记忆对应一个 .md 文件。
 * 索引行格式：- [名称](文件名.md) — 一行描述
 *
 * <h3>二层加载</h3>
 * 第一层：MEMORY.md 索引全量注入 System Prompt（轻量，通常几百字）
 * 第二层：Agent 用 FileTool 读取具体 .md 文件（按需）
 *
 * <h3>数据模型</h3>
 * MemoryItem 是核心 DTO — 解析 MEMORY.md → List<MemoryItem>
 * → toString() 写回索引。name/type/description/fileName 四个字段完整覆盖。
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final String INDEX_FILE = "MEMORY.md";
    /** 索引行正则：- [name](file.md) — description */
    private static final Pattern INDEX_LINE = Pattern.compile("- \\[(.+?)\\]\\((.+?)\\) — (.+)");

    private final Path dir;

    public MemoryManager(String memoryDir) {
        this.dir = Paths.get(memoryDir.replace("~", System.getProperty("user.home")));
        ensureDir();
        ensureIndex();
    }

    private void ensureDir() {
        try { Files.createDirectories(dir); } catch (IOException e) { log.error("Failed to create memory dir", e); }
    }

    private void ensureIndex() {
        Path indexPath = dir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            try {
                Files.writeString(indexPath,
                    "# Memory Index\n\n记忆文件索引，由 Agent 自动维护。\n\n## 文件列表\n\n");
            } catch (IOException e) { log.error("Failed to create memory index", e); }
        }
    }

    // ── 索引操作 ──

    /** 读取 MEMORY.md 索引文本（注入 System Prompt 用） */
    public String getIndex() {
        List<MemoryItem> items = parseIndex();
        if (items.isEmpty()) return "（暂无记忆）";
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : items) {
            sb.append(item.toString()).append("\n");
        }
        return sb.toString();
    }

    /** 解析 MEMORY.md → MemoryItem 列表 */
    private List<MemoryItem> parseIndex() {
        List<MemoryItem> items = new ArrayList<>();
        try {
            String content = Files.readString(dir.resolve(INDEX_FILE));
            for (String line : content.split("\n")) {
                Matcher m = INDEX_LINE.matcher(line);
                if (m.find()) {
                    String name = m.group(1);
                    String fileName = m.group(2);
                    String desc = m.group(3).trim();
                    MemoryItem.MemoryType type = inferType(fileName);
                    items.add(new MemoryItem(type, name, desc, fileName));
                }
            }
        } catch (IOException e) { log.warn("Failed to parse memory index", e); }
        return items;
    }

    /** 从文件名前缀推断记忆类型 */
    private MemoryItem.MemoryType inferType(String fileName) {
        if (fileName.startsWith("user_"))      return MemoryItem.MemoryType.USER;
        if (fileName.startsWith("feedback_"))  return MemoryItem.MemoryType.FEEDBACK;
        if (fileName.startsWith("project_"))   return MemoryItem.MemoryType.PROJECT;
        if (fileName.startsWith("reference_")) return MemoryItem.MemoryType.REFERENCE;
        return MemoryItem.MemoryType.PROJECT;
    }

    /** 序列化 MemoryItem 列表 → 写入 MEMORY.md */
    private void writeIndex(List<MemoryItem> items) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Memory Index\n\n记忆文件索引，由 Agent 自动维护。\n\n## 文件列表\n\n");
            for (MemoryItem item : items) {
                sb.append(item.toString()).append("\n");
            }
            Files.writeString(dir.resolve(INDEX_FILE), sb.toString());
        } catch (IOException e) { log.error("Failed to write memory index", e); }
    }

    // ── CRUD ──

    /** 加载记忆文件内容 */
    public String load(String fileName) {
        try { return Files.readString(dir.resolve(fileName)); }
        catch (IOException e) { log.warn("Failed to load memory file: {}", fileName); return null; }
    }

    /** 保存一条记忆：写 .md 文件 + 更新索引 */
    public void save(MemoryItem.MemoryType type, String name, String desc, String content) {
        String fileName = type.name().toLowerCase() + "_" + name + ".md";
        try {
            Files.writeString(dir.resolve(fileName), content);
            MemoryItem item = new MemoryItem(type, name, desc, fileName);
            List<MemoryItem> items = parseIndex();
            items.removeIf(i -> i.getFileName().equals(fileName));
            items.add(item);
            writeIndex(items);
            log.info("Saved memory: {}", fileName);
        } catch (IOException e) { log.error("Failed to save memory: {}", fileName, e); }
    }

    /** 删除一条记忆：删文件 + 从索引移除 */
    public void delete(String fileName) {
        try {
            Files.deleteIfExists(dir.resolve(fileName));
            List<MemoryItem> items = parseIndex();
            items.removeIf(i -> i.getFileName().equals(fileName));
            writeIndex(items);
            log.info("Deleted memory: {}", fileName);
        } catch (IOException e) { log.error("Failed to delete memory: {}", fileName, e); }
    }

    /** 列出指定类型的记忆文件名 */
    public List<String> listFiles(MemoryItem.MemoryType type) {
        List<String> result = new ArrayList<>();
        try {
            String prefix = type.name().toLowerCase() + "_";
            Files.list(dir)
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .forEach(p -> result.add(p.getFileName().toString()));
        } catch (IOException e) { log.error("Failed to list memory files", e); }
        return result;
    }
}
