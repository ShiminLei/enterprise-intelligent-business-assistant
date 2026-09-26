package com.enterprise.assistant.conversation;

/** 助手处理一次请求时的一个执行步骤（FR-020、FR-024）。input 为工具参数 JSON。 */
public record Step(int seq, String tool, String label, String input, String resultSummary, boolean success,
                   long durationMs) {
}
