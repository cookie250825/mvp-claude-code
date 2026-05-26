package com.agent.memory;

/**
 * 记忆项 — 一条记忆的元数据。
 *
 * <h3>四种记忆类型</h3>
 * USER   — 用户偏好（如"用中文回答"、"喜欢简洁回复"）
 * FEEDBACK — 行为反馈（如"不要问是否继续"、"工具确认用 y/n"）
 * PROJECT  — 项目上下文（如"当前在开发 Agent 项目"、"目标是找实习"）
 * REFERENCE — 外部参考（如"CSDN 写作规范在某个目录"、"用 opencli 发文章"）
 *
 * <h3>存在形式</h3>
 * 每个 MemoryItem 对应磁盘上一个 Markdown 文件。
 * 文件内容由 Agent 写（通过 FileTool），元数据在 MEMORY.md 索引中维护。
 */
public class MemoryItem {

    /** 四种记忆类型 */
    public enum MemoryType { USER, FEEDBACK, PROJECT, REFERENCE }

    private final MemoryType type;        // 哪种类型
    private final String name;            // 记忆名称（文件名的一部分）
    private final String description;     // 一行描述
    private final String fileName;        // 对应的 .md 文件名

    public MemoryItem(MemoryType type, String name, String description, String fileName) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.fileName = fileName;
    }

    public MemoryType getType() { return type; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getFileName() { return fileName; }

    /**
     * 格式化为 MEMORY.md 中的一行。
     * 如：- [CSDN 写作规范](reference_csdn_writer.md) — 排版格式和发布流程
     */
    @Override
    public String toString() {
        return String.format("- [%s](%s) — %s", name, fileName, description);
    }
}
