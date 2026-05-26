package com.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Scanner;

/**
 * 工具执行确认组件 — 在 AI 动手之前，先问用户同不同意。
 *
 * <h3>为什么需要这个</h3>
 * LLM 可能会执行危险操作（删文件、改配置、发请求）。
 * 在工具真正执行前弹一个 y/n 确认，用户有机会拦住。
 *
 * <h3>三种选择</h3>
 * y（yes）    — 执行这一次，下次还要问
 * n（no）     — 跳过这一次，LLM 会看到 "User denied" 并调整策略
 * a（all）    — 后续全部批准，不再问了（适合你信任 AI 的场景）
 *
 * <h3>交互模式 vs 单次模式</h3>
 * 交互模式（-i）：人在终端前，弹确认
 * 单次模式（-p）：自动批，因为人不在（可能只等最后结果）
 * 子 Agent：自动批，因为父 Agent 已经授权
 */
public class ToolExecutionConfirmation {

    /** 是否在交互模式（人在终端前） */
    private final boolean interactive;

    /** 终端输入读取器（交互模式才有） */
    private final Scanner scanner;

    /** 用户选过 a（全部批准）后，后续自动跳过询问 */
    private boolean autoApproveAll;

    /**
     * @param interactive true = 交互模式，要弹确认；false = 自动批
     */
    public ToolExecutionConfirmation(boolean interactive) {
        this.interactive = interactive;
        this.scanner = interactive ? new Scanner(System.in) : null;
    }

    /**
     * 询问用户是否批准此工具调用。
     *
     * <h3>显示内容</h3>
     * 工具名 + 参数摘要（超过 200 字符会截断，避免刷屏）
     *
     * <h3>判断逻辑</h3>
     * 1. 非交互模式 → 直接批
     * 2. 之前选过 a → 直接批
     * 3. 用户输入 y → 批
     * 4. 用户输入 a → 批 + 后续全批
     * 5. 其他（n 或随便敲）→ 拒
     *
     * @param req LLM 返回的工具调用请求
     * @return true = 批准执行，false = 用户拒绝
     */
    public boolean ask(ToolExecutionRequest req) {
        // 非交互模式 或 之前选了 a → 自动放行
        if (!interactive || autoApproveAll) return true;

        // 缩短参数显示（太长的 JSON 看着累）
        String toolName = req.name();
        String args = req.arguments();
        if (args != null && args.length() > 200) {
            args = args.substring(0, 200) + "...";
        }

        // 弹确认框
        System.out.println();
        System.out.println("⚡ 工具调用: " + toolName);
        System.out.println("   参数: " + (args != null ? args : "(无参数)"));
        System.out.print("   执行? [y=是 / n=否 / a=全部批准] ");

        String line = scanner.nextLine().trim().toLowerCase();
        switch (line) {
            case "y", "yes", "" -> { return true; }
            case "a", "all" ->  { autoApproveAll = true; return true; }
            default ->          { return false; }
        }
    }

    /**
     * 当前是否处于全自动批准状态。
     *
     * @return true = 已全自动（非交互模式或用户选了 a）
     */
    public boolean isAutoApprove() {
        return autoApproveAll || !interactive;
    }
}
