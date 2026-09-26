package com.enterprise.assistant.agent;

/** 接收 Agent 处理过程中的事件（生产环境为 SSE，测试中为列表）。 */
@FunctionalInterface
public interface AgentEventSink {

    void send(AgentEvent event);
}
