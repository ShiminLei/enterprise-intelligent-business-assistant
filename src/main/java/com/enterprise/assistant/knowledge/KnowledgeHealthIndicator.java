package com.enterprise.assistant.knowledge;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 知识库可用状态，健康检查中的组件名为 knowledgeBase（research R7）。 */
@Component("knowledgeBaseHealthIndicator")
public class KnowledgeHealthIndicator implements HealthIndicator {

    private volatile Health health = Health.unknown().withDetail("reason", "尚未导入").build();
    private volatile boolean available;

    public void markUp(int documentCount) {
        available = true;
        health = Health.up().withDetail("documents", documentCount).build();
    }

    public void markDown(String reason) {
        available = false;
        health = Health.down().withDetail("reason", reason).build();
    }

    public boolean isAvailable() {
        return available;
    }

    @Override
    public Health health() {
        return health;
    }
}
