package com.enterprise.assistant.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 用模型替身替换真实的百炼模型，测试不需要 API Key（宪法 III）。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAiConfig {

    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }

    @Bean
    @Primary
    HashEmbeddingModel hashEmbeddingModel() {
        return new HashEmbeddingModel();
    }
}
