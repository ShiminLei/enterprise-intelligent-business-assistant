package com.enterprise.assistant.conversation;

public enum MessageStatus {
    COMPLETED,
    FAILED,
    /** 工具调用达到单次请求上限（FR-019） */
    STEP_LIMIT_REACHED
}
