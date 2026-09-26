package com.enterprise.assistant.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param topK                检索返回的最大片段数
 * @param similarityThreshold 相似度阈值（0–1），低于它的片段不返回
 */
@ConfigurationProperties("app.knowledge")
public record KnowledgeProperties(int topK, double similarityThreshold) {
}
