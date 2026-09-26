package com.enterprise.assistant.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 启动完成后自动导入 classpath:knowledge/*.md（FR-006）。导入失败只记录错误，应用照常运行，
 * 健康检查显示知识库不可用（spec 边界情况）。
 */
@Component
public class StartupKnowledgeImporter {

    private static final Logger log = LoggerFactory.getLogger(StartupKnowledgeImporter.class);

    private final KnowledgeIngestionService ingestion;
    private final KnowledgeHealthIndicator health;

    public StartupKnowledgeImporter(KnowledgeIngestionService ingestion, KnowledgeHealthIndicator health) {
        this.ingestion = ingestion;
        this.health = health;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importBuiltInDocuments() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
            int imported = 0;
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                String name = filename.substring(0, filename.length() - ".md".length());
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                boolean skipped = ingestion.importDocument(name, content, KbDocumentSource.BUILT_IN, null).skipped();
                if (skipped) {
                    log.info("知识库内容未变化，跳过导入：{}", name);
                }
                imported++;
            }
            log.info("知识库导入完成，共 {} 份内置文档", imported);
            health.markUp(imported);
        } catch (IOException | RuntimeException e) {
            log.error("知识库导入失败，知识库暂不可用：{}", e.getMessage(), e);
            health.markDown("内置文档导入失败：" + e.getMessage());
        }
    }
}
