package com.enterprise.assistant.action;

/**
 * 待确认操作的状态（data-model.md「待确认操作状态机」）：
 * PENDING → EXECUTED / FAILED（确认后执行成功或失败）/ CANCELLED（用户取消）/ EXPIRED（同会话出现新消息）。
 */
public enum PendingActionStatus {
    PENDING("待确认"),
    EXECUTED("已执行"),
    FAILED("执行失败"),
    CANCELLED("已取消"),
    EXPIRED("已作废");

    private final String label;

    PendingActionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
