package com.agent;

import com.agent.config.AppConfig;
import com.agent.core.*;
import com.agent.memory.MemoryManager;
import com.agent.tools.*;
import com.agent.tools.mcp.MCPClient;
import com.agent.tools.mcp.MCPToolAdapter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(name = "agent", mixinStandardHelpOptions = true, version = "1.0",
         description = "MVP Claude Code - Java AI Agent")
public class Main implements Callable<Integer> {

    @Option(names = {"-i", "--interactive"}, description = "交互模式")
    boolean interactive;

    @Option(names = {"-p", "--prompt"}, description = "单次提问")
    String prompt;

    @Option(names = {"-c", "--config"}, description = "配置文件路径", defaultValue = "config.yaml")
    String configPath;

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    @Override
    public Integer call() throws Exception {
        // 1. 加载配置
        AppConfig config = new AppConfig(configPath);

        // 2. 初始化安全工作目录
        FileTool.initWorkspace(config.getSecurityWorkspace());

        // 3. 初始化记忆系统
        MemoryManager memory = new MemoryManager(config.getMemoryDir());

        // 4. 初始化工具注册表
        ToolRegistry registry = new ToolRegistry();
        ToolDispatcher dispatcher = new ToolDispatcher();

        // 注册内置工具
        BaseTool[] builtins = { new FileTool(), new BashTool(), new SearchTool() };
        for (BaseTool tool : builtins) {
            registry.register(tool);
            dispatcher.register(tool);
        }

        // 5. 连接 MCP servers
        for (AppConfig.McpServerConfig server : config.getMcpServers()) {
            try {
                MCPClient mcpClient = new MCPClient();
                String cmd = server.command;
                String[] argsArr = server.args != null ? server.args.toArray(new String[0]) : new String[0];
                mcpClient.connect(cmd, argsArr);

                List<MCPClient.MCPToolDef> mcpTools = mcpClient.listTools();
                for (MCPClient.MCPToolDef def : mcpTools) {
                    MCPToolAdapter adapter = new MCPToolAdapter(mcpClient, def.name, def.description, def.inputSchema);
                    registry.registerMCP(adapter);
                    dispatcher.register(adapter);
                }
            } catch (Exception e) {
                System.err.println("MCP server connect failed: " + e.getMessage());
            }
        }

        // 6. 初始化 AI 服务
        AIService ai = new AIService(config);

        // 6.5. 注册 TaskTool + TodoWrite（依赖 AIService / TodoManager）
        TodoManager todoManager = new TodoManager();
        TodoWriteTool todoWrite = new TodoWriteTool(todoManager);
        registry.register(todoWrite);
        dispatcher.register(todoWrite);

        TaskTool taskTool = new TaskTool(ai, dispatcher, registry, config);
        registry.register(taskTool);
        dispatcher.register(taskTool);

        // 7. 初始化核心组件
        CompactService compact = new CompactService(ai, config.getCompactThreshold());
        ContextBuilder ctxBuilder = new ContextBuilder(registry, memory, config);
        AgentLoop loop = new AgentLoop(ai, dispatcher, compact, ctxBuilder, todoManager);
        loop.init();

        // 8. 运行
        if (prompt != null) {
            System.out.println(loop.process(prompt));
        } else {
            runInteractive(loop);
        }

        loop.shutdown();
        return 0;
    }

    private void runInteractive(AgentLoop loop) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Mini Claude Code 已就绪。输入 /quit 退出，/compact 压缩上下文，/memory 查看记忆。");

        while (true) {
            System.out.print("\n> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            switch (line) {
                case "/quit", "/exit" -> { loop.shutdown(); return; }
                case "/compact" -> { loop.manualCompact(); System.out.println("[已压缩上下文]"); }
                case "/memory" -> System.out.println(loop.showMemory());
                default -> {
                    try {
                        String response = loop.process(line);
                        System.out.println("\n" + response);
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }
        }
    }
}
