package com.enterprise.assistant.project;

public enum ProjectStatus {
    PLANNING("计划中"),
    IN_PROGRESS("进行中"),
    COMPLETED("已完成");

    private final String label;

    ProjectStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
