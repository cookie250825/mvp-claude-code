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

/**
 * 应用配置管理 — 加载 config.yaml 并提供类型安全的配置读取。
 *
 * <h3>配置加载优先级</h3>
 * 1. 命令行传的 configPath（如 java -jar app.jar -c /path/to/config.yaml）
 * 2. 当前目录的 config.yaml
 * 3. classpath 中的 config.yaml（jar 内默认配置）
 *
 * <h3>环境变量插值</h3>
 * 配置值中的 ${ENV_VAR} 会自动替换为环境变量的值。
 * 如 apiKey: ${DEEPSEEK_API_KEY} → 运行时取 System.getenv("DEEPSEEK_API_KEY")
 *
 * <h3>设计：不用 Spring @ConfigurationProperties</h3>
 * 为了零 Spring 依赖，手动用 SnakeYAML 解析 + get() 链式路径取值。
 */
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /** SnakeYAML 解析后的原始 Map。所有值从这里通过路径取值。 */
    private final Map<String, Object> raw;

    /**
     * @param configPath 配置文件路径（可为 null，fallback 到 classpath）
     */
    @SuppressWarnings("unchecked")
    public AppConfig(String configPath) {
        Map<String, Object> result = loadFromFile(configPath);
        this.raw = result != null ? result : Map.of();
    }

    /**
     * 从文件加载 YAML 配置。
     * 优先文件系统 → 其次 classpath → 最后返回 null。
     */
    private Map<String, Object> loadFromFile(String configPath) {
        try {
            Path p = Paths.get(configPath != null ? configPath : "config.yaml");
            byte[] bytes;
            if (Files.exists(p)) {
                bytes = Files.readAllBytes(p);           // 文件系统
            } else {
                InputStream is = getClass().getClassLoader().getResourceAsStream("config.yaml");
                if (is == null) return null;             // classpath
                bytes = is.readAllBytes();
            }
            Yaml yaml = new Yaml();
            return yaml.load(new String(bytes));
        } catch (Exception e) {
            log.warn("Failed to load config: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按路径从嵌套 Map 中取值。
     * 如 get("model", "name") 取 raw.model.name。
     */
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

    /**
     * 替换 ${ENV_VAR} 为实际环境变量值。
     * 如 "${DEEPSEEK_API_KEY}" → System.getenv("DEEPSEEK_API_KEY")
     */
    private String envOr(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String envKey = value.substring(2, value.length() - 1);
            String envVal = System.getenv(envKey);
            return envVal != null ? envVal : "";
        }
        return value;
    }

    // ---- Model 配置 ----
    public String getProvider()     { return str("model", "provider", "deepseek"); }
    public String getModelName()    { return str("model", "name", "deepseek-chat"); }
    public String getBaseUrl()      { return str("model", "baseUrl", "https://api.deepseek.com/v1"); }
    public String getApiKey()       { return envOr(str("model", "apiKey", "")); }  // 敏感值：环境变量插值
    public double getTemperature()  { return dbl("model", "temperature", 0.7); }
    public int getMaxTokens()       { return num("model", "maxTokens", 4096); }

    // ---- Context 配置 ----
    /** token 超过这个阈值触发 auto compact */
    public int getCompactThreshold() { return num("context", "compactThreshold", 50000); }
    /** 最大轮次：0 = 不限制（靠 token 预算），>0 = 轮次上限。默认 0（token 预算制） */
    public int getMaxRounds()        { return num("context", "maxRounds", 0); }
    /** 上下文窗口大小（模型限制，DeepSeek 默认 128K） */
    public int getMaxContextTokens() { return num("context", "maxContextTokens", 128000); }
    /** 上下文水位危险比例：用量达到此比例时先压缩，压不下来再熔断 */
    public double getContextDangerRatio() { return dbl("context", "contextDangerRatio", 0.9); }

    // ---- Memory 配置 ----
    /** 记忆文件存储目录（~ 展开为 user.home） */
    public String getMemoryDir() {
        return str("memory", "dir", System.getProperty("user.home") + "/.agent/memory")
            .replace("~", System.getProperty("user.home"));
    }

    // ---- MCP Servers 配置 ----
    /**
     * 解析 tools.mcp.servers 列表。
     * 只在 tools.mcp.enabled = true 时才返回非空列表。
     */
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
    /** 当前工作目录（项目根目录） */
    public String getWorkspace()   { return System.getProperty("user.dir"); }

    // ---- Security ----
    /** 安全沙箱边界：留空=当前目录；填绝对路径=限制文件操作范围 */
    public String getSecurityWorkspace() {
        String path = str("security", "workspace", "");
        return path.isEmpty() ? System.getProperty("user.dir") : path;
    }

    // ---- 类型安全取值辅助方法 ----

    /** 取 String 配置值（带默认值） */
    @SuppressWarnings("unchecked")
    private String str(String section, String key, String def) {
        Map<String, Object> sec = get(section);
        if (sec == null) return def;
        Object val = sec.get(key);
        return val != null ? val.toString() : def;
    }

    /** 取 int 配置值（带默认值） */
    @SuppressWarnings("unchecked")
    private int num(String section, String key, int def) {
        Map<String, Object> sec = get(section);
        if (sec == null) return def;
        Object val = sec.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return def;
    }

    /** 取 double 配置值（带默认值） */
    @SuppressWarnings("unchecked")
    private double dbl(String section, String key, double def) {
        Map<String, Object> sec = get(section);
        if (sec == null) return def;
        Object val = sec.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return def;
    }

    /** MCP Server 配置项 */
    public static class McpServerConfig {
        public String name;
        public String command;
        public List<String> args = List.of();
    }
}
