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

public class SearchTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 50;

    @Override public String name() { return "search"; }
    @Override public String description() { return "在目录中搜索文本内容，返回匹配行和行号。支持文件扩展名过滤。"; }

    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String pattern = args.path("pattern").asText();
            String dir = args.path("dir").asText(".");
            String ext = args.path("ext").asText("");  // 如 ".java"
            if (pattern.isEmpty()) return ToolResult.error("pattern is required");

            List<String> results = new ArrayList<>();
            PathMatcher matcher = ext.isEmpty() ? null
                : FileSystems.getDefault().getPathMatcher("glob:**" + ext);
            Path base = Path.of(dir);

            try (Stream<Path> paths = Files.walk(base)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> matcher == null || matcher.matches(p))
                     .forEach(p -> {
                         if (results.size() >= MAX_RESULTS) return;
                         try {
                             List<String> lines = Files.readAllLines(p);
                             for (int i = 0; i < lines.size(); i++) {
                                 if (lines.get(i).contains(pattern)) {
                                     results.add(p + ":" + (i+1) + ": " + lines.get(i).trim());
                                     if (results.size() >= MAX_RESULTS) break;
                                 }
                             }
                         } catch (IOException ignored) {}
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
