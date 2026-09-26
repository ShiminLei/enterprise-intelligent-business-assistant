package com.enterprise.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.sun.net.httpserver.HttpServer;

/**
 * 百炼接口不响应时，模型调用必须在读取超时后失败，而不是无限期挂起
 * （评估中发现的问题：一次模型调用卡住了一个小时）。
 */
class ModelHttpTimeoutIT extends AbstractIntegrationTest {

    /** 接受连接但永不返回响应的假模型服务。 */
    static final HttpServer SILENT_SERVER;

    static {
        try {
            SILENT_SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SILENT_SERVER.createContext("/", exchange -> {
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        SILENT_SERVER.setExecutor(Executors.newCachedThreadPool());
        SILENT_SERVER.start();
    }

    @DynamicPropertySource
    static void pointModelAtSilentServer(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://localhost:" + SILENT_SERVER.getAddress().getPort());
        registry.add("spring.http.client.read-timeout", () -> "1s");
    }

    @AfterAll
    static void stop() {
        SILENT_SERVER.stop(0);
    }

    @Autowired
    OpenAiChatModel realChatModel;

    @Test
    void applicationConfiguresHttpTimeouts() throws IOException {
        // 本测试类把读取超时改成了 1 秒，这里直接检查 application.yml 中的默认值
        String yaml = new String(new org.springframework.core.io.ClassPathResource("application.yml")
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(yaml).contains("connect-timeout: 10s").contains("read-timeout: 60s");
    }

    @Test
    void unresponsiveModelFailsAfterReadTimeout() {
        Instant start = Instant.now();

        assertThatThrownBy(() -> realChatModel.call(new Prompt("你好"))).isInstanceOf(RuntimeException.class);

        // 读取超时 1 秒 × 最多 2 次尝试 + 1 秒重试间隔，远小于 30 秒
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(30));
    }
}
