package com.enterprise.assistant.knowledge;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfig {

    /** 替换 Spring AI 默认的按 Token 分批策略，PgVectorStore 自动使用它。 */
    @Bean
    BatchingStrategy embeddingBatchingStrategy() {
        return new MaxCountBatchingStrategy(10);
    }
}
