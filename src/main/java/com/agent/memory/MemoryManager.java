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

public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private final Path dir;
    private static final String INDEX_FILE = "MEMORY.md";

    public enum MemoryType {
        USER, FEEDBACK, PROJECT, REFERENCE, TASK
    }

    public MemoryManager(String memoryDir) {
        this.dir = Paths.get(memoryDir.replace("~", System.getProperty("user.home")));
        ensureDir();
        ensureIndex();
    }

    private void ensureDir() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create memory dir: {}", dir, e);
        }
    }

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

    public String getIndex() {
        try {
            return Files.readString(dir.resolve(INDEX_FILE));
        } catch (IOException e) {
            log.warn("Failed to read memory index", e);
            return "（暂无记忆）";
        }
    }

    public String load(String fileName) {
        try {
            return Files.readString(dir.resolve(fileName));
        } catch (IOException e) {
            log.warn("Failed to load memory file: {}", fileName, e);
            return null;
        }
    }

    public void save(MemoryType type, String name, String desc, String content) {
        String fileName = type.name().toLowerCase() + "_" + name + ".md";
        Path filePath = dir.resolve(fileName);
        try {
            Files.writeString(filePath, content);
            appendIndex(name, desc, fileName);
            log.info("Saved memory: {}", fileName);
        } catch (IOException e) {
            log.error("Failed to save memory: {}", fileName, e);
        }
    }

    private void appendIndex(String name, String desc, String fileName) {
        try {
            Path indexPath = dir.resolve(INDEX_FILE);
            String entry = String.format("- [%s](%s) — %s\n", name, fileName, desc);
            // 避免重复追加
            String existing = Files.readString(indexPath);
            if (!existing.contains(fileName)) {
                Files.writeString(indexPath, entry, StandardOpenOption.APPEND);
            } else {
                // 更新已有条目
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

    public void delete(String fileName) {
        try {
            Files.deleteIfExists(dir.resolve(fileName));
            // 从索引中移除
            Path indexPath = dir.resolve(INDEX_FILE);
            String existing = Files.readString(indexPath);
            String updated = existing.replaceAll("- \\[.*?\\]\\(" + fileName + "\\).*\n", "");
            Files.writeString(indexPath, updated);
        } catch (IOException e) {
            log.error("Failed to delete memory: {}", fileName, e);
        }
    }
}
