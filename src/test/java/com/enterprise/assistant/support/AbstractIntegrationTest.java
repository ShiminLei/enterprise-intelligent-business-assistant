package com.enterprise.assistant.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：真实的 PostgreSQL + pgvector（Testcontainers），模型替身代替百炼。
 * 容器为静态单例，所有测试类共享同一个 Spring 上下文与数据库。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestAiConfig.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @Autowired
    protected ScriptedChatModel chatModel;

    @Autowired
    protected HashEmbeddingModel embeddingModel;
}
