package com.agent.tools;

/**
 * 工具执行结果 — 所有工具 execute() 返回的统一结构。
 *
 * <h3>为什么要有这个类</h3>
 * 不同工具返回不同格式（有的返回文本、有的抛异常、有的返回 null），
 * 如果不统一，ToolDispatcher 不知道某个结果是成功还是失败。
 * ToolResult 用 success 标记 + content 承载内容，所有工具遵守同一份契约。
 */
public class ToolResult {

    /** 是否执行成功 */
    private final boolean success;
    /** 结果内容（成功的输出 或 错误信息） */
    private final String content;

    private ToolResult(boolean success, String content) {
        this.success = success;
        this.content = content;
    }

    /** 构造成功结果 */
    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    /** 构造失败结果（自动加 "ERROR: " 前缀） */
    public static ToolResult error(String message) {
        return new ToolResult(false, "ERROR: " + message);
    }

    public boolean isSuccess() { return success; }
    public String getContent() { return content; }

    @Override
    public String toString() { return content; }
}
