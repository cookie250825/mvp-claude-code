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

public class MCPClient {
    private static final Logger log = LoggerFactory.getLogger(MCPClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger idGen = new AtomicInteger(1);

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    public static class MCPToolDef {
        public final String name;
        public final String description;
        public final JsonNode inputSchema;

        public MCPToolDef(String name, String description, JsonNode inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }
    }

    public void connect(String command, String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = command;
        System.arraycopy(args, 0, cmd, 1, args.length);
        process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        initialize();
    }

    private void initialize() throws IOException {
        int id = idGen.getAndIncrement();
        String req = mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0", "id", id, "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mini-claude", "version", "1.0")
            )
        ));
        send(req);
        readResponse(); // discard
        send(mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()
        )));
    }

    public List<MCPToolDef> listTools() throws IOException {
        int id = idGen.getAndIncrement();
        send(mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0", "id", id, "method", "tools/list", "params", Map.of()
        )));
        JsonNode resp = mapper.readTree(readResponse());
        List<MCPToolDef> tools = new ArrayList<>();
        JsonNode arr = resp.path("result").path("tools");
        for (JsonNode t : arr) {
            tools.add(new MCPToolDef(
                t.path("name").asText(),
                t.path("description").asText(""),
                t.path("inputSchema")
            ));
        }
        return tools;
    }

    public String callTool(String name, Map<String, Object> args) throws IOException {
        int id = idGen.getAndIncrement();
        send(mapper.writeValueAsString(Map.of(
            "jsonrpc", "2.0", "id", id, "method", "tools/call",
            "params", Map.of("name", name, "arguments", args)
        )));
        JsonNode resp = mapper.readTree(readResponse());
        JsonNode content = resp.path("result").path("content");
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText();
        }
        return resp.toString();
    }

    private void send(String msg) throws IOException {
        writer.write(msg);
        writer.newLine();
        writer.flush();
    }

    private String readResponse() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) return line;
        }
        return "{}";
    }

    public void disconnect() {
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        if (process != null) process.destroyForcibly();
    }
}
