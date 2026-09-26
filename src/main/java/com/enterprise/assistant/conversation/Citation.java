package com.enterprise.assistant.conversation;

/** 回答引用的知识片段（FR-008）；index 对应正文中的 [n]。 */
public record Citation(int index, String documentName, String section, String excerpt) {
}
