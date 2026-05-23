package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

public class FileTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(FileTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 50000;
    private static final Path WORKSPACE = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    @Override public String name() { return "file"; }
    @Override public String description() { return "读写文件。操作范围限制在工作目录内。"; }

    /** 安全检查：限制路径在工作目录内 */
    private Path safePath(String raw) {
        Path resolved = WORKSPACE.resolve(resolvePath(raw)).toAbsolutePath().normalize();
        if (!resolved.startsWith(WORKSPACE)) {
            throw new SecurityException("路径越界: " + raw + " (工作目录: " + WORKSPACE + ")");
        }
        return resolved;
    }

    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String action = args.path("action").asText("read");
            String path = resolvePath(args.path("path").asText());
            if (path.isEmpty()) return ToolResult.error("path is required");

            if ("read".equals(action)) {
                if (!Files.exists(safePath(path))) return ToolResult.error("File not found: " + path);
                String content = Files.readString(safePath(path));
                return ToolResult.success(truncate(content));
            } else if ("write".equals(action)) {
                String content = args.path("content").asText();
                Path p = safePath(path);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return ToolResult.success("Written: " + p);
            } else if ("list".equals(action)) {
                StringBuilder sb = new StringBuilder();
                try (var s = Files.list(safePath(path))) {
                    s.forEach(f -> sb.append(f.getFileName()).append("\n"));
                }
                return ToolResult.success(truncate(sb.toString()));
            } else if ("exists".equals(action)) {
                return ToolResult.success(String.valueOf(Files.exists(safePath(path))));
            } else {
                return ToolResult.error("Unknown action: " + action + ". Use read/write/list/exists");
            }
        } catch (IOException e) {
            return ToolResult.error("IO error: " + e.getMessage());
        } catch (SecurityException e) {
            return ToolResult.error("安全限制: " + e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addEnumProperty("action", List.of("read", "write", "list", "exists"), "操作类型")
                .addStringProperty("path", "文件或目录路径")
                .addStringProperty("content", "写入内容（write 时必填）")
                .required(List.of("action", "path"))
                .build())
            .build();
    }

    private String resolvePath(String path) {
        if (path.startsWith("~/")) return System.getProperty("user.home") + path.substring(1);
        return path;
    }

    /** 输出截断保护 */
    public static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n\n[截断: 输出超过 " + MAX_OUTPUT_CHARS + " 字符]";
    }
}
