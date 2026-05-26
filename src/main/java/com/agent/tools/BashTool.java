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

/**
 * Shell 命令执行工具 — LLM 通过它来跑 bash 命令。
 *
 * <h3>支持的场景</h3>
 * - 编译运行（javac、java、python3、gcc）
 * - 版本控制（git status、git diff、git log）
 * - 构建工具（mvn compile、gradle build）
 * - 文件查找（find、grep、ls）
 *
 * <h3>安全机制</h3>
 * 1. 危险命令黑名单：rm -rf /、sudo、shutdown、reboot、mkfs.、dd if=、fork bomb、chmod 777 /、> /dev/sda
 *    在命令执行前检查，命中了直接拒绝
 * 2. 30 秒超时：命令运行超过 30 秒自动 kill，防死循环
 * 3. 输出截断：最多 50K 字符，防大输出撑爆上下文
 */
public class BashTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_OUTPUT_CHARS = 50000;

    /** 危险命令黑名单（包含任意一个就拒绝执行） */
    private static final List<String> DANGEROUS = List.of(
        "rm -rf /", "sudo", "shutdown", "reboot", "mkfs.",
        "dd if=", ":(){ :|:& };:", "chmod 777 /", "> /dev/sda"
    );

    @Override public String name() { return "bash"; }
    @Override public String description() { return "执行 shell 命令，返回 stdout+stderr。超时 30 秒自动终止。"; }

    /**
     * 执行 Shell 命令。
     *
     * <h3>执行流程</h3>
     * 1. 解 JSON 参数 → command + timeout（默认 30s）
     * 2. 危险命令检查 → 命中黑名单直接拒
     * 3. ProcessBuilder 起 bash -c 子进程
     * 4. 逐行读 stdout/stderr（已合并）
     * 5. 等 timeout 秒，超时杀进程
     * 6. 输出截断到 50K 字符
     *
     * @param arguments JSON 参数，如 {"command":"javac Hello.java", "timeout":60}
     * @return 命令执行结果
     */
    @Override
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = mapper.readTree(arguments);
            String command = args.path("command").asText();
            int timeout = args.path("timeout").asInt(30);  // 默认 30 秒

            if (command.isEmpty()) return ToolResult.error("command is required");

            // 危险命令检查
            for (String pattern : DANGEROUS) {
                if (command.contains(pattern)) {
                    return ToolResult.error("危险命令已拦截: 包含 '" + pattern + "'");
                }
            }

            // 起子进程
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);  // stderr 合并到 stdout
            Process process = pb.start();

            // 逐行读输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }

            // 等进程结束，带超时
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();  // 超时杀进程
                return ToolResult.error("命令超时 (" + timeout + "s)");
            }

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
