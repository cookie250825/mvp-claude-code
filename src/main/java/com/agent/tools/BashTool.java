package com.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BashTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 50000;
    private static final List<String> DANGEROUS = List.of(
        "rm -rf /", "sudo", "shutdown", "reboot", "mkfs.",
        "dd if=", ":(){ :|:& };:", "chmod 777 /", "> /dev/sda"
    );

    @Override public String name() { return "bash"; }
    @Override public String description() { return "执行 shell 命令，返回 stdout+stderr。超时 30 秒自动终止。"; }

    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String command = args.path("command").asText();
            int timeout = args.path("timeout").asInt(30);
            if (command.isEmpty()) return ToolResult.error("command is required");
            for (String pattern : DANGEROUS) {
                if (command.contains(pattern)) {
                    return ToolResult.error("危险命令已拦截: 包含 '" + pattern + "'");
                }
            }

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) { process.destroyForcibly(); return ToolResult.error("命令超时 (" + timeout + "s)"); }
            String result = output.toString().trim();
            return ToolResult.success(FileTool.truncate(result));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
            .name(name()).description(description())
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("command", "要执行的 shell 命令")
                .addIntegerProperty("timeout", "超时秒数，默认 30")
                .required(List.of("command"))
                .build())
            .build();
    }
}
