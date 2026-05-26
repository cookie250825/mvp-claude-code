package com.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Scanner;

/**
 * 工具执行确认组件 — 用户安全防线。
 * 交互模式下 AI 调用工具前必须用户批准；
 * 单次模式（-p）自动批准所有工具。
 */
public class ToolExecutionConfirmation {
    private final boolean interactive;
    private final Scanner scanner;
    private boolean autoApproveAll;

    public ToolExecutionConfirmation(boolean interactive) {
        this.interactive = interactive;
        this.scanner = interactive ? new Scanner(System.in) : null;
    }

    /**
     * 询问用户是否批准此工具调用。
     * @return true=批准执行，false=拒绝
     */
    public boolean ask(ToolExecutionRequest req) {
        if (!interactive || autoApproveAll) return true;

        String toolName = req.name();
        String args = req.arguments();
        if (args != null && args.length() > 200) {
            args = args.substring(0, 200) + "...";
        }

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

    public boolean isAutoApprove() {
        return autoApproveAll || !interactive;
    }
}
