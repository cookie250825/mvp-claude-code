package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * 文件操作工具 — LLM 通过它来读写本地文件。
 *
 * <h3>支持的操作</h3>
 * - read(路径)       — 读文件内容（最多 50K 字符，超过截断）
 * - write(路径, 内容) — 写文件（覆盖式，目录不存在自动创建）
 * - list(路径)        — 列目录内容
 * - exists(路径)      — 检查文件/目录是否存在
 *
 * <h3>安全机制：路径沙箱</h3>
 * 所有路径操作被限制在工作目录内。LLM 传 ../../etc/passwd 也绕不出去——
 * safePath() 会抛出 SecurityException。
 * 在 Main.java 启动时调用 FileTool.initWorkspace() 设置沙箱边界。
 *
 * <h3>~ 路径展开</h3>
 * Windows 下 Java 不自动展开 ~。resolvePath() 手动检测 ~/ 前缀，
 * 替换为 System.getProperty("user.home")。
 */
public class FileTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(FileTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 50000;  // 读文件/列目录的最大输出字符数
    private static Path WORKSPACE;  // 沙箱边界（启动时由 Main.initWorkspace 设置）

    /**
     * 设置工作目录边界 — 启动时由 Main.java 调用一次。
     * 之后所有文件操作都被限制在这个目录内。
     *
     * @param workspacePath 工作目录绝对路径
     */
    public static void initWorkspace(String workspacePath) {
        WORKSPACE = Path.of(workspacePath).toAbsolutePath().normalize();
    }

    @Override public String name() { return "file"; }
    @Override public String description() { return "读写文件。操作范围限制在工作目录内。"; }

    /**
     * 执行文件操作 — LLM 调 file 工具时走到这里。
     *
     * @param arguments JSON 参数，如 {"action":"read", "path":"pom.xml"}
     * @return 操作结果
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String action = args.path("action").asText("read");  // 默认读
            String path = resolvePath(args.path("path").asText());

            if (path.isEmpty()) return ToolResult.error("path is required");

            if ("read".equals(action)) {
                if (!Files.exists(safePath(path))) return ToolResult.error("File not found: " + path);
                String content = Files.readString(safePath(path));
                return ToolResult.success(truncate(content));
            } else if ("write".equals(action)) {
                String content = args.path("content").asText();
                Path p = safePath(path);
                if (p.getParent() != null) Files.createDirectories(p.getParent());  // 自动建父目录
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

    /**
     * 安全检查：把相对路径拼到工作目录下，然后检查有没有越界。
     * 如果 LLM 传了 ../../etc/passwd，resolved 会在 WORKSPACE 之外 → 抛异常。
     *
     * @param raw LLM 传的原始路径
     * @return 安全的绝对路径
     * @throws SecurityException 如果路径越界
     */
    private Path safePath(String raw) {
        Path resolved = WORKSPACE.resolve(resolvePath(raw)).toAbsolutePath().normalize();
        if (!resolved.startsWith(WORKSPACE)) {
            throw new SecurityException("路径越界: " + raw + " (工作目录: " + WORKSPACE + ")");
        }
        return resolved;
    }

    /**
     * 展开 ~ 路径 — Windows 下 Java 不会自动展开。
     * 如 ~/test.txt → C:/Users/李想/test.txt
     *
     * @param path 原始路径
     * @return 展开后的路径
     */
    private String resolvePath(String path) {
        if (path.startsWith("~/")) return System.getProperty("user.home") + path.substring(1);
        return path;
    }

    /**
     * 输出截断保护 — 50K 字符以上的内容截断并标记。
     * 防止一个大文件（如几 MB 的日志）撑爆 LLM 上下文。
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    public static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n\n[截断: 输出超过 " + MAX_OUTPUT_CHARS + " 字符]";
    }
}
