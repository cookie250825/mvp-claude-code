package com.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final Map<String, Object> raw;

    @SuppressWarnings("unchecked")
    public AppConfig(String configPath) {
        Map<String, Object> result = loadFromFile(configPath);
        this.raw = result != null ? result : Map.of();
    }

    private Map<String, Object> loadFromFile(String configPath) {
        try {
            Path p = Paths.get(configPath != null ? configPath : "config.yaml");
            byte[] bytes;
            if (Files.exists(p)) {
                bytes = Files.readAllBytes(p);
            } else {
                InputStream is = getClass().getClassLoader().getResourceAsStream("config.yaml");
                if (is == null) return null;
                bytes = is.readAllBytes();
            }
            Yaml yaml = new Yaml();
            return yaml.load(new String(bytes));
        } catch (Exception e) {
            log.warn("Failed to load config: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String... path) {
        Object current = raw;
        for (String key : path) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
        }
        return (T) current;
    }

    private String envOr(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String envKey = value.substring(2, value.length() - 1);
            String envVal = System.getenv(envKey);
            return envVal != null ? envVal : "";
        }
        return value;
    }

    // ---- Model ----
    public String getProvider()     { return str("model", "provider", "deepseek"); }
    public String getModelName()    { return str("model", "name", "deepseek-chat"); }
    public String getBaseUrl()      { return str("model", "baseUrl", "https://api.deepseek.com/v1"); }
    public String getApiKey()       { return envOr(str("model", "apiKey", "")); }
    public double getTemperature()  { return dbl("model", "temperature", 0.7); }
    public int getMaxTokens()       { return num("model", "maxTokens", 4096); }

    // ---- Context ----
    public int getCompactThreshold() { return num("context", "compactThreshold", 50000); }

    // ---- Memory ----
    public String getMemoryDir() { return str("memory", "dir", System.getProperty("user.home") + "/.agent/memory")
        .replace("~", System.getProperty("user.home")); }

    // ---- MCP Servers ----
    @SuppressWarnings("unchecked")
    public List<McpServerConfig> getMcpServers() {
        List<McpServerConfig> result = new ArrayList<>();
        Map<String, Object> tools = get("tools");
        if (tools == null) return result;
        Map<String, Object> mcpSec = (Map<String, Object>) tools.get("mcp");
        if (mcpSec == null) return result;
        Object enabled = mcpSec.get("enabled");
        if (!(enabled instanceof Boolean && (Boolean) enabled)) return result;
        List<Map<String, Object>> servers = (List<Map<String, Object>>) mcpSec.get("servers");
        if (servers == null) return result;
        for (Map<String, Object> s : servers) {
            McpServerConfig cfg = new McpServerConfig();
            cfg.name = (String) s.get("name");
            cfg.command = (String) s.get("command");
            List<String> args = (List<String>) s.get("args");
            cfg.args = args != null ? args : List.of();
            result.add(cfg);
        }
        return result;
    }

    public String getProjectName() { return "mini-claude"; }
    public String getWorkspace()   { return System.getProperty("user.dir"); }

    // ---- Helpers ----
    @SuppressWarnings("unchecked")
    private String str(String section, String key, String def) {
        Map<String, Object> sec = get(section);
        if (sec == null) return def;
        Object val = sec.get(key);
        return val != null ? val.toString() : def;
    }

    @SuppressWarnings("unchecked")
    private int num(String section, String key, int def) {
        Map<String, Object> sec = get(section);
        if (sec == null) return def;
        Object val = sec.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return def;
    }

    @SuppressWarnings("unchecked")
    private double dbl(String section, String key, double def) {
        Map<String, Object> sec = get(section);
        if (sec == null) return def;
        Object val = sec.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return def;
    }

    public static class McpServerConfig {
        public String name;
        public String command;
        public List<String> args = List.of();
    }
}
