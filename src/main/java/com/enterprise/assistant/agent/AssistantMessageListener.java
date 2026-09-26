package com.enterprise.assistant.agent;

/** 助手消息保存后的回调，例如把本次请求生成的待确认操作关联到该消息。 */
public interface AssistantMessageListener {

    void onAssistantMessageSaved(long assistantMessageId, AgentRequestContext context);
}
