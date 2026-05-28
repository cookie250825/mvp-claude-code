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
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * 程序入口 — Picocli CLI + 启动时装配所有组件。
 *
 * <h3>启动模式</h3>
 * -p "你的问题"   → 单次模式：一问一答，工具自动批准，不弹确认框
 * -i              → 交互模式：持续对话，工具执行前弹 y/n/a 确认
 * 不带参数        → 默认交互模式
 *
 * <h3>启动时的装配顺序（10 步，顺序重要）</h3>
 * 1. 加载配置（config.yaml → AppConfig）
 * 2. 初始化文件沙箱（FileTool.initWorkspace）
 * 3. 初始化记忆系统（MemoryManager）
 * 4. 注册内置工具（FileTool + BashTool + SearchTool）
 * 5. 连接 MCP Server 并注册外部工具
 * 6. 初始化 AI 服务（AIService 双模）
 * 7. 注册高级工具（BackgroundRun/CheckBackground/TodoWrite/Task — 依赖 AIService）
 * 8. 创建工具确认组件
 * 9. 组装核心组件（CompactService + ContextBuilder + AgentLoop）
 * 10. 运行
 */
@Command(name = "agent", mixinStandardHelpOptions = true, version = "1.0",
         description = "MVP Claude Code - Java AI Agent")
public class Main implements Callable<Integer> {

    @Option(names = {"-i", "--interactive"}, description = "交互模式")
    boolean interactive;

    @Option(names = {"-p", "--prompt"}, description = "单次提问")
    String prompt;

    @Option(names = {"-c", "--config"}, description = "配置文件路径", defaultValue = "config.yaml")
    String configPath;

    /**
     * main → Picocli 解析命令行参数 → call() 执行
     */
    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    /**
     * 启动 Agent — 装配全部组件，然后进入交互循环或单次执行。
     *
     * @return 0 = 正常退出
     */
    @Override
    public Integer call() throws Exception {
        // ---- 步骤 1: 加载配置 ----
        AppConfig config = new AppConfig(configPath);

        // ---- 步骤 2: 初始化文件沙箱 ----
        // 之后所有 FileTool 操作都被限制在这个目录内
        FileTool.initWorkspace(config.getSecurityWorkspace());

        // ---- 步骤 3: 初始化记忆系统 ----
        // ~/.agent/memory/ 目录，按项目隔离：global/ + projects/<id>/
        String projectId = java.nio.file.Path.of(config.getWorkspace()).getFileName().toString();
        MemoryManager memory = new MemoryManager(config.getMemoryDir(), projectId);

        // ---- 步骤 4: 注册内置工具 ----
        // 基础三件套：读文件、跑命令、搜内容
        ToolRegistry registry = new ToolRegistry();
        ToolDispatcher dispatcher = new ToolDispatcher();

        BaseTool[] builtins = { new FileTool(), new BashTool(), new SearchTool() };
        for (BaseTool tool : builtins) {
            registry.register(tool);
            dispatcher.register(tool);
        }

        // ---- 步骤 5: 连接 MCP Server ----
        // 遍历 config.yaml 中配置的 MCP Server，逐一连接并注册外部工具
        for (AppConfig.McpServerConfig server : config.getMcpServers()) {
            try {
                MCPClient mcpClient = new MCPClient();
                String cmd = server.command;
                String[] argsArr = server.args != null ? server.args.toArray(new String[0]) : new String[0];
                mcpClient.connect(cmd, argsArr);  // 启动子进程 + 握手

                // 拉取 MCP Server 的工具列表
                List<MCPClient.MCPToolDef> mcpTools = mcpClient.listTools();
                for (MCPClient.MCPToolDef def : mcpTools) {
                    // 每个 MCP 工具包装成 MCPToolAdapter，统一注册
                    MCPToolAdapter adapter = new MCPToolAdapter(mcpClient, def.name, def.description, def.inputSchema);
                    registry.registerMCP(adapter);
                    dispatcher.register(adapter);
                }
            } catch (Exception e) {
                // 连接失败不致命——内置工具还能用
                System.err.println("MCP server connect failed: " + e.getMessage());
            }
        }

        // ---- 步骤 6: 初始化 AI 服务 ----
        // 双模：同步 model（摘要）+ 流式 streamingModel（主对话）
        AIService ai = new AIService(config);

        // ---- 步骤 7: 注册高级工具（依赖 AIService 或其他组件） ----
        // 后台任务
        BackgroundManager bgManager = new BackgroundManager();
        BackgroundRunTool bgRun = new BackgroundRunTool(bgManager);
        CheckBackgroundTool bgCheck = new CheckBackgroundTool(bgManager);
        registry.register(bgRun); dispatcher.register(bgRun);
        registry.register(bgCheck); dispatcher.register(bgCheck);

        // Todo 管理
        TodoManager todoManager = new TodoManager();
        TodoWriteTool todoWrite = new TodoWriteTool(todoManager);
        registry.register(todoWrite);
        dispatcher.register(todoWrite);

        // Worktree + Subagent 管理（异步并行子 Agent）
        WorktreeManager worktreeManager = new WorktreeManager();
        SubagentManager subagentManager = new SubagentManager(worktreeManager);

        // 子任务委托（异步模式：task() 立即返回，子 Agent 后台执行）
        TaskTool taskTool = new TaskTool(ai, dispatcher, registry, config, subagentManager, memory);
        registry.register(taskTool);
        dispatcher.register(taskTool);

        // ---- 步骤 8: 工具确认组件 ----
        // prompt != null（-p 模式）→ 自动批；prompt == null（-i 或无参数）→ 弹确认
        ToolExecutionConfirmation confirmation = new ToolExecutionConfirmation(prompt == null);

        // ---- 步骤 9: 组装核心组件 ----
        CompactService compact = new CompactService(ai, config.getCompactThreshold());
        ContextBuilder ctxBuilder = new ContextBuilder(registry, memory, config);
        AgentLoop loop = new AgentLoop(ai, dispatcher, compact, ctxBuilder, todoManager, bgManager,
            subagentManager, confirmation,
            config.getMaxRounds(), config.getMaxContextTokens(), config.getContextDangerRatio());
        loop.init();

        // ---- 步骤 10: 运行 ----
        if (prompt != null) {
            // 单次模式：流式输出已在 AgentLoop 内打印，只需加末尾换行
            loop.process(prompt);
            System.out.println();
        } else {
            // 交互模式：持续对话
            runInteractive(loop);
        }

        loop.shutdown();
        return 0;
    }

    /**
     * 交互模式主循环 — 读终端输入 → 发给 AgentLoop → 打印结果 → 循环。
     *
     * 三个内置命令：
     * /quit    — 退出
     * /compact — 手动触发上下文压缩（用户觉得 AI 忘事了就敲）
     * /memory  — 查看记忆索引
     */
    private void runInteractive(AgentLoop loop) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("MVP Claude Code 已就绪。输入 /quit 退出，/compact 压缩上下文，/memory 查看记忆。");

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
                        loop.process(line);  // 流式输出已在 AgentLoop 内实时打印，不重复
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }
        }
    }
}
