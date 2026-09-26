package com.enterprise.assistant.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxToolCalls 单次请求最多工具调用次数（FR-019）
 * @param timeout      单次请求超时
 * @param historySize  传给模型的历史消息条数（research R8）
 */
@ConfigurationProperties("app.agent")
public record AgentProperties(int maxToolCalls, Duration timeout, int historySize) {
}
