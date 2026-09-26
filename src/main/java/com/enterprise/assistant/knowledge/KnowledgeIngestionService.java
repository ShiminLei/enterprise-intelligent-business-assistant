package com.enterprise.assistant.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;

/**
 * 知识文档导入（research R7）：内置文档内容哈希不变则跳过；否则在一个事务中删除旧片段、写入新片段。
 * 向量化失败时整个事务回滚，已有知识库保持不变。
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    public record ImportResult(KbDocument document, boolean skipped) {
    }

    private final KbDocumentRepository documents;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final MarkdownChunker chunker;
    private final Clock clock;

    public KnowledgeIngestionService(KbDocumentRepository documents, VectorStore vectorStore, JdbcTemplate jdbc,
                                     MarkdownChunker chunker, Clock clock) {
        this.documents = documents;
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.chunker = chunker;
        this.clock = clock;
    }

    @Transactional
    public ImportResult importDocument(String name, String content, KbDocumentSource source, Long uploadedBy) {
        if (content == null || content.isBlank()) {
            throw BusinessException.badRequest("文档内容为空");
        }
        String hash = sha256(content);
        KbDocument document = documents.findByName(name).orElse(null);
        if (document != null && source == KbDocumentSource.BUILT_IN
                && document.getSource() == KbDocumentSource.BUILT_IN && hash.equals(document.getContentHash())) {
            log.info("knowledge document unchanged, skipped name={}", name);
            return new ImportResult(document, true);
        }
        if (document == null) {
            document = new KbDocument(name);
        } else {
            jdbc.update("DELETE FROM vector_store WHERE metadata->>'document_id' = ?", String.valueOf(document.getId()));
        }
        document.update(source, hash, 0, uploadedBy, clock.instant());
        document = documents.saveAndFlush(document);

        List<Document> chunks = chunker.chunk(document.getId(), name, content);
        if (chunks.isEmpty()) {
            throw BusinessException.badRequest("文档没有可导入的正文内容");
        }
        try {
            vectorStore.add(chunks);
        } catch (RuntimeException e) {
            throw new KnowledgeUnavailableException("向量化服务暂不可用，已有知识库不受影响", e);
        }
        document.update(source, hash, chunks.size(), uploadedBy, clock.instant());
        log.info("knowledge document imported name={} source={} chunks={}", name, source, chunks.size());
        return new ImportResult(document, false);
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
