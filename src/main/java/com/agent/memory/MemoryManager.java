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
 * 记忆管理器 — 文件型记忆系统，按项目隔离。
 *
 * <h3>存储结构</h3>
 * <pre>
 * ~/.agent/memory/
 *   global/MEMORY.md        — USER + REFERENCE + FEEDBACK（全局，跟人不跟项目）
 *   projects/mvp/MEMORY.md  — PROJECT（项目级，换项目看不到）
 *   projects/thoughtcoding/MEMORY.md
 * </pre>
 *
 * <h3>类型路由</h3>
 * USER / REFERENCE / FEEDBACK → global/（人的属性，跨项目共享）
 * PROJECT                     → projects/{id}/（项目专属）
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final String INDEX_FILE = "MEMORY.md";
    private static final Pattern INDEX_LINE = Pattern.compile("- \\[(.+?)\\]\\((.+?)\\) — (.+)");

    private final Path globalDir;
    private final Path projectDir;
    private final boolean hasProject;

    /**
     * @param memoryDir 记忆根目录（~ 展开为 user.home）
     * @param projectId 项目标识（workspace 目录名），null 表示仅全局记忆
     */
    public MemoryManager(String memoryDir, String projectId) {
        Path root = Paths.get(memoryDir.replace("~", System.getProperty("user.home")));
        this.globalDir = root.resolve("global");
        this.hasProject = projectId != null && !projectId.isBlank();
        this.projectDir = hasProject ? root.resolve("projects").resolve(projectId) : null;
        ensureDirs();
    }

    /** 向下兼容：不传 projectId 时只使用全局记忆 */
    public MemoryManager(String memoryDir) {
        this(memoryDir, null);
    }

    private void ensureDirs() {
        try { Files.createDirectories(globalDir); ensureIndex(globalDir); }
        catch (IOException e) { log.error("Failed to create global memory dir", e); }
        if (hasProject) {
            try { Files.createDirectories(projectDir); ensureIndex(projectDir); }
            catch (IOException e) { log.error("Failed to create project memory dir", e); }
        }
    }

    private void ensureIndex(Path dir) {
        Path indexPath = dir.resolve(INDEX_FILE);
        if (!Files.exists(indexPath)) {
            try { Files.writeString(indexPath, "# Memory Index\n\n记忆文件索引，由 Agent 自动维护。\n\n## 文件列表\n\n"); }
            catch (IOException e) { log.error("Failed to create index in {}", dir, e); }
        }
    }

    // ── 读取 ──

    /** 合并全局 + 项目记忆索引，注入 System Prompt */
    public String getIndex() {
        List<MemoryItem> items = new ArrayList<>();
        items.addAll(parseIndex(globalDir));
        if (hasProject) items.addAll(parseIndex(projectDir));

        if (items.isEmpty()) return "（暂无记忆）";
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : items) {
            sb.append(item.toString()).append("\n");
        }
        return sb.toString();
    }

    private List<MemoryItem> parseIndex(Path dir) {
        List<MemoryItem> items = new ArrayList<>();
        try {
            Path indexPath = dir.resolve(INDEX_FILE);
            if (!Files.exists(indexPath)) return items;
            String content = Files.readString(indexPath);
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
        } catch (IOException e) { log.warn("Failed to parse index in {}", dir, e); }
        return items;
    }

    private MemoryItem.MemoryType inferType(String fileName) {
        if (fileName.startsWith("user_"))      return MemoryItem.MemoryType.USER;
        if (fileName.startsWith("feedback_"))  return MemoryItem.MemoryType.FEEDBACK;
        if (fileName.startsWith("project_"))   return MemoryItem.MemoryType.PROJECT;
        if (fileName.startsWith("reference_")) return MemoryItem.MemoryType.REFERENCE;
        return MemoryItem.MemoryType.PROJECT;
    }

    // ── 写入 ──

    /** 加载记忆文件内容（先查全局，再查项目） */
    public String load(String fileName) {
        Path f = globalDir.resolve(fileName);
        if (Files.exists(f)) return readFile(f);
        if (hasProject) {
            f = projectDir.resolve(fileName);
            if (Files.exists(f)) return readFile(f);
        }
        return null;
    }

    /** 保存一条记忆，按类型路由到全局或项目目录 */
    public void save(MemoryItem.MemoryType type, String name, String desc, String content) {
        Path targetDir = isGlobalType(type) ? globalDir : projectDir;
        if (targetDir == null) {
            log.warn("Cannot save project memory without projectId");
            return;
        }
        String fileName = type.name().toLowerCase() + "_" + name + ".md";
        try {
            Files.writeString(targetDir.resolve(fileName), content);
            MemoryItem item = new MemoryItem(type, name, desc, fileName);
            List<MemoryItem> items = parseIndex(targetDir);
            items.removeIf(i -> i.getFileName().equals(fileName));
            items.add(item);
            writeIndex(targetDir, items);
            log.info("Saved memory: {} -> {}", fileName, targetDir);
        } catch (IOException e) { log.error("Failed to save memory: {}", fileName, e); }
    }

    /** 删除记忆（尝试两个目录） */
    public void delete(String fileName) {
        deleteFrom(globalDir, fileName);
        if (hasProject) deleteFrom(projectDir, fileName);
    }

    /** 列出指定类型的记忆文件 */
    public List<String> listFiles(MemoryItem.MemoryType type) {
        List<String> result = new ArrayList<>();
        Path targetDir = isGlobalType(type) ? globalDir : projectDir;
        if (targetDir == null) return result;
        String prefix = type.name().toLowerCase() + "_";
        try {
            Files.list(targetDir)
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .forEach(p -> result.add(p.getFileName().toString()));
        } catch (IOException e) { log.error("Failed to list files in {}", targetDir, e); }
        return result;
    }

    // ── 内部 ──

    private boolean isGlobalType(MemoryItem.MemoryType type) {
        return switch (type) {
            case USER, REFERENCE, FEEDBACK -> true;
            case PROJECT -> false;
        };
    }

    private void writeIndex(Path dir, List<MemoryItem> items) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Memory Index\n\n记忆文件索引，由 Agent 自动维护。\n\n## 文件列表\n\n");
            for (MemoryItem item : items) sb.append(item.toString()).append("\n");
            Files.writeString(dir.resolve(INDEX_FILE), sb.toString());
        } catch (IOException e) { log.error("Failed to write index in {}", dir, e); }
    }

    private void deleteFrom(Path dir, String fileName) {
        try {
            Files.deleteIfExists(dir.resolve(fileName));
            List<MemoryItem> items = parseIndex(dir);
            items.removeIf(i -> i.getFileName().equals(fileName));
            writeIndex(dir, items);
        } catch (IOException e) { log.error("Failed to delete {} from {}", fileName, dir); }
    }

    private String readFile(Path path) {
        try { return Files.readString(path); }
        catch (IOException e) { log.warn("Failed to read {}", path); return null; }
    }
}
