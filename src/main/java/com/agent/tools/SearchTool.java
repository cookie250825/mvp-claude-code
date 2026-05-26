package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 内容搜索工具 — LLM 通过它在项目里搜索代码/文本。
 *
 * <h3>用法</h3>
 * search(pattern="ThreadPoolExecutor", dir=".", ext=".java")
 * → 遍历 dir 目录下所有 .java 文件，找到含 ThreadPoolExecutor 的行，
 *    返回 "文件名:行号: 代码行" 格式的结果。
 *
 * <h3>输出限制</h3>
 * 最多返回 50 条结果，超过直接截断。
 * 输出用 FileTool.truncate() 限制 50K 字符。
 */
public class SearchTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 50;  // 最多返回 50 条匹配

    @Override public String name() { return "search"; }
    @Override public String description() { return "在目录中搜索文本内容，返回匹配行和行号。支持文件扩展名过滤。"; }

    /**
     * 搜索文本内容。
     *
     * @param arguments JSON 参数：pattern(必填) / dir(默认.)/ ext(可选，如.java)
     * @return 搜索结果（文件名:行号: 行内容，每行一条）
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String pattern = args.path("pattern").asText();
            String dir = args.path("dir").asText(".");
            String ext = args.path("ext").asText("");  // 如 ".java"

            if (pattern.isEmpty()) return ToolResult.error("pattern is required");

            List<String> results = new ArrayList<>();

            // 扩展名过滤：ext 非空时创建 PathMatcher
            PathMatcher matcher = ext.isEmpty() ? null
                : FileSystems.getDefault().getPathMatcher("glob:**" + ext);

            Path base = Path.of(dir);

            // 递归遍历目录
            try (Stream<Path> paths = Files.walk(base)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> matcher == null || matcher.matches(p))
                     .forEach(p -> {
                         if (results.size() >= MAX_RESULTS) return;  // 够了就停
                         try {
                             List<String> lines = Files.readAllLines(p);
                             for (int i = 0; i < lines.size(); i++) {
                                 if (lines.get(i).contains(pattern)) {
                                     results.add(p + ":" + (i + 1) + ": " + lines.get(i).trim());
                                     if (results.size() >= MAX_RESULTS) break;
                                 }
                             }
                         } catch (IOException ignored) {
                             // 读不动的文件跳过（权限、编码等），不求全
                         }
                     });
            }

            String output = results.isEmpty() ? "No matches found" : String.join("\n", results);
            return ToolResult.success(FileTool.truncate(output));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("pattern", "搜索关键词")
                .addStringProperty("dir", "搜索目录，默认当前目录")
                .addStringProperty("ext", "文件扩展名过滤，如 .java")
                .required(List.of("pattern"))
                .build())
            .build();
    }
}
