package com.enterprise.assistant.common;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * .env 模板中的 {@code DASHSCOPE_API_KEY=} 在用户粘贴 Key 之前是空值；Spring AI 不接受空的 API Key，
 * 会导致应用无法启动。这里把空值视为「未配置」，应用照常启动，健康检查中知识库显示不可用。
 */
public class BlankApiKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String KEY = "DASHSCOPE_API_KEY";
    static final String NOT_CONFIGURED = "not-configured";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String value = environment.getProperty(KEY);
        if (value != null && value.isBlank()) {
            environment.getPropertySources().addFirst(new MapPropertySource("blankApiKey", Map.of(KEY, NOT_CONFIGURED)));
        }
    }

    /** 在读取 application.yml 与 .env 之后执行。 */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
