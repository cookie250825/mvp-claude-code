package com.agent.tools;

public class ToolResult {
    private final boolean success;
    private final String content;

    private ToolResult(boolean success, String content) {
        this.success = success;
        this.content = content;
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, "ERROR: " + message);
    }

    public boolean isSuccess() { return success; }
    public String getContent() { return content; }

    @Override
    public String toString() { return content; }
}
