package com.enterprise.assistant.task;

public enum TaskPriority {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低");

    private final String label;

    TaskPriority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
