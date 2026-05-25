package com.agent.memory;

public class MemoryItem {
    public enum MemoryType { USER, FEEDBACK, PROJECT, REFERENCE }

    private final MemoryType type;
    private final String name;
    private final String description;
    private final String fileName;

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

    @Override
    public String toString() {
        return String.format("- [%s](%s) — %s", name, fileName, description);
    }
}
