package com.agent.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 协议客户端 — 手写 JSON-RPC 2.0 over stdio。
 *
 * <h3>MCP 是什么</h3>
 * Model Context Protocol — Anthropic 发布的 Agent 与外部工具交互的开放协议。
 * MCP Server 是一个独立进程，通过 stdio 提供工具列表和工具执行能力。
 * 本类实现 MCP Client，和 MCP Server 通过 JSON-RPC 通信。
 *
 * <h3>通信方式</h3>
 * 通过 ProcessBuilder 起一个子进程（如 npx @modelcontextprotocol/server-filesystem），
 * 往 stdin 写 JSON-RPC 请求，从 stdout 读 JSON-RPC 响应。
 *
 * <h3>握手流程（3 步）</h3>
 * 1. 发 initialize       → 声明客户端能力和协议版本
 * 2. 收 response          → 获取服务端能力和版本
 * 3. 发 initialized 通知  → 握手完成，进入工作状态
 *
 * <h3>为什么手写而不用 MCP SDK</h3>
 * 面试能讲 10 分钟——从 JSON-RPC 握手讲到子进程管理。
 * 手写的过程就是理解协议的过程。
 *
 * <h3>已知限制</h3>
 * readResponse() 只读一行。如果 MCP Server 发多行 JSON，会损坏。
 * 大部分 MCP Server 是单行 JSON，所以实际影响不大。
 */
public class MCPClient {
    private static final Logger log = LoggerFactory.getLogger(MCPClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** JSON-RPC 请求 ID 生成器（线程安全） */
    private final AtomicInteger idGen = new AtomicInteger(1);

    /** MCP Server 子进程 */
    private Process process;
    /** MCP Server 的 stdout 读取器 */
    private BufferedReader reader;
    /** MCP Server 的 stdin 写入器 */
    private BufferedWriter writer;

    /**
     * MCP 工具定义 — 从 Server 的 tools/list 响应中解析出来。
     */
    public static class MCPToolDef {
        public final String name;
        public final String description;
        public final JsonNode inputSchema;   // JSON Schema，描述工具的参数

        public MCPToolDef(String name, String description, JsonNode inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    /**
     * 连接 MCP Server — 起子进程并完成握手。
     *
     * @param command 可执行文件路径（如 "npx" 或 "D:\\Node.js\\npx.cmd"）
     * @param args    命令行参数（如 ["-y", "@modelcontextprotocol/server-filesystem", "."]）
     * @throws IOException 起进程失败或握手失败
     */
    public void connect(String command, String... args) throws IOException {
        // 拼接命令：["npx", "-y", "@modelcontextprotocol/server-filesystem", "."]
        String[] cmd = new String[args.length + 1];
        cmd[0] = command;
        System.arraycopy(args, 0, cmd, 1, args.length);

        // 起子进程
        process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        // 握手
        initialize();
    }

    /**
     * MCP 握手 — 先 initialize，再发 initialized 通知。
     */
    private void initialize() throws IOException {
        int id = idGen.getAndIncrement();

        // 步骤 1: 发 initialize 请求
        String req = mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mini-claude", "version", "1.0")
            )
        ));
        send(req);
        readResponse();  // 步骤 2: 收响应（内容丢弃，主要确认握手成功）

        // 步骤 3: 发 initialized 通知（JSON-RPC 通知没有 id）
        send(mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "method", "notifications/initialized",
            "params", Map.of()
        )));
    }

    /**
     * 列出 MCP Server 提供的所有工具。
     *
     * @return 工具定义列表（name + description + inputSchema）
     * @throws IOException 通信失败
     */
    public List<MCPToolDef> listTools() throws IOException {
        int id = idGen.getAndIncrement();

        // 发 tools/list 请求
        send(mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/list",
            "params", Map.of()
        )));

        // 解析响应
        JsonNode resp = mapper.readTree(readResponse());
        List<MCPToolDef> tools = new ArrayList<>();
        JsonNode arr = resp.path("result").path("tools");

        for (JsonNode t : arr) {
            tools.add(new MCPToolDef(
                t.path("name").asText(),
                t.path("description").asText(""),
                t.path("inputSchema")     // 完整的 JSON Schema
            ));
        }
        return tools;
    }

    /**
     * 调用 MCP Server 的某个工具。
     *
     * @param name 工具名
     * @param args 参数字典
     * @return 工具执行结果文本
     * @throws IOException 通信失败
     */
    public String callTool(String name, Map<String, Object> args) throws IOException {
        int id = idGen.getAndIncrement();

        send(mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of("name", name, "arguments", args)
        )));

        JsonNode resp = mapper.readTree(readResponse());
        JsonNode content = resp.path("result").path("content");

        // MCP 响应格式：result.content[0].text
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText();
        }
        return resp.toString();
    }

    /** 往 stdin 写一行 JSON */
    private void send(String msg) throws IOException {
        writer.write(msg);
        writer.newLine();
        writer.flush();
    }

    /**
     * 从 stdout 读一行 JSON 响应。
     * 已知限制：如果 Server 发多行 JSON（含换行），只读第一行。
     */
    private String readResponse() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) return line;
        }
        return "{}";
    }

}
