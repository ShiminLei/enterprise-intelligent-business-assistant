package com.enterprise.assistant.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class BlankApiKeyEnvironmentPostProcessorTest {

    final BlankApiKeyEnvironmentPostProcessor processor = new BlankApiKeyEnvironmentPostProcessor();

    @Test
    void blankKeyIsTreatedAsNotConfigured() {
        MockEnvironment env = new MockEnvironment().withProperty("DASHSCOPE_API_KEY", "  ");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("DASHSCOPE_API_KEY")).isEqualTo("not-configured");
    }

    @Test
    void realKeyIsKept() {
        MockEnvironment env = new MockEnvironment().withProperty("DASHSCOPE_API_KEY", "sk-abc");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("DASHSCOPE_API_KEY")).isEqualTo("sk-abc");
    }

    @Test
    void missingKeyIsLeftToTheDefault() {
        MockEnvironment env = new MockEnvironment();
        processor.postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("DASHSCOPE_API_KEY")).isNull();
    }
}
