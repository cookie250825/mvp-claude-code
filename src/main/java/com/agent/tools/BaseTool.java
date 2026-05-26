package com.agent.tools;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 所有工具的抽象基类。
 *
 * <h3>怎么加一个新工具</h3>
 * 1. 写个类 extends BaseTool
 * 2. 实现 name()        → 工具名，LLM 通过这个名字调用
 * 3. 实现 description() → 告诉 LLM 这个工具是干什么的
 * 4. 实现 execute(String arguments) → LLM 调用时执行，参数是 JSON 字符串
 * 5. 实现 getSpec()     → 返回工具的 JSON Schema（参数名、类型、是否必填）
 * 6. 在 Main.java 里 new 出来，registry.register() + dispatcher.register()
 *
 * <h3>为什么不用 @Tool 注解</h3>
 * LangChain4j 支持 @Tool 注解 + AiServices 自动反射，但 MCP 工具是运行时
 * 从外部服务器下发的，不可能提前写注解。抽象类 + Dispatch Map 让所有工具
 *（内置 + MCP）共用同一套执行流程。
 */
public abstract class BaseTool {

    /** LLM 用来标识这个工具的名字，如 "file"、"bash"、"TodoWrite" */
    public abstract String name();

    /** 一句话描述，LLM 根据这段话来决定什么时候用这个工具 */
    public abstract String description();

    /**
     * 执行工具 — LLM 说"用这个工具，参数是这些"时调用。
     *
     * @param arguments JSON 格式的参数字符串，如 {"command":"ls -la"}
     * @return 工具执行结果（成功的内容 或 失败的错误信息）
     */
    public abstract ToolResult execute(String arguments);

    /**
     * 返回工具的精确 JSON Schema。
     * 告诉 LLM 这个工具有哪些参数、每个参数什么类型、哪个必填。
     * 精确的 Schema = LLM 一次就填对参数，不用来回纠正。
     *
     * @return ToolSpecification（LangChain4j 的标准工具定义）
     */
    public abstract ToolSpecification getSpec();
}
