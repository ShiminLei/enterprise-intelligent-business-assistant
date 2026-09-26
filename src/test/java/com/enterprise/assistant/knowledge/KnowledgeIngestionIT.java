package com.enterprise.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.enterprise.assistant.support.AbstractIntegrationTest;

class KnowledgeIngestionIT extends AbstractIntegrationTest {

    static final String NAME = "测试制度-导入";

    @Autowired
    KnowledgeIngestionService ingestion;

    @Autowired
    KbDocumentRepository documents;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        embeddingModel.reset();
        documents.findByName(NAME).ifPresent(d -> {
            jdbc.update("DELETE FROM vector_store WHERE metadata->>'document_id' = ?", String.valueOf(d.getId()));
            documents.delete(d);
        });
    }

    int chunkRows(long documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class, String.valueOf(documentId));
    }

    String content(String marker, int sections) {
        StringBuilder sb = new StringBuilder("# " + NAME + "\n\n");
        for (int i = 1; i <= sections; i++) {
            sb.append("## 第").append(i).append("章\n\n").append(marker).append("规定第").append(i).append("条。\n\n");
        }
        return sb.toString();
    }

    @Test
    void firstImportStoresDocumentAndChunksInBatchesOfAtMostTen() {
        KbDocument doc = ingestion.importDocument(NAME, content("甲", 12), KbDocumentSource.BUILT_IN, null).document();

        assertThat(doc.getChunkCount()).isEqualTo(12);
        assertThat(chunkRows(doc.getId())).isEqualTo(12);
        assertThat(doc.getContentHash()).hasSize(64);
        assertThat(embeddingModel.batchSizes()).isNotEmpty().allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(10));
    }

    @Test
    void reimportingSameContentIsSkipped() {
        ingestion.importDocument(NAME, content("甲", 3), KbDocumentSource.BUILT_IN, null);
        embeddingModel.reset();

        KnowledgeIngestionService.ImportResult again = ingestion.importDocument(NAME, content("甲", 3),
                KbDocumentSource.BUILT_IN, null);

        assertThat(again.skipped()).isTrue();
        assertThat(embeddingModel.batchSizes()).isEmpty();
        assertThat(chunkRows(again.document().getId())).isEqualTo(3);
    }

    @Test
    void changedContentReplacesOldChunks() {
        long id = ingestion.importDocument(NAME, content("甲", 3), KbDocumentSource.BUILT_IN, null).document().getId();

        KbDocument updated = ingestion.importDocument(NAME, content("乙", 5), KbDocumentSource.BUILT_IN, null).document();

        assertThat(updated.getId()).isEqualTo(id);
        assertThat(chunkRows(id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ? AND content LIKE '%甲%'",
                Integer.class, String.valueOf(id))).isZero();
    }

    @Test
    void uploadWithSameNameReplacesBuiltInDocument() {
        ingestion.importDocument(NAME, content("甲", 3), KbDocumentSource.BUILT_IN, null);

        KbDocument uploaded = ingestion.importDocument(NAME, content("乙", 2), KbDocumentSource.UPLOADED, 1L).document();

        assertThat(uploaded.getSource()).isEqualTo(KbDocumentSource.UPLOADED);
        assertThat(uploaded.getUploadedBy()).isEqualTo(1L);
        assertThat(chunkRows(uploaded.getId())).isEqualTo(2);
    }

    @Test
    void embeddingFailureLeavesExistingKnowledgeUntouched() {
        KbDocument original = ingestion.importDocument(NAME, content("甲", 3), KbDocumentSource.BUILT_IN, null).document();
        embeddingModel.setFailing(true);

        assertThatThrownBy(() -> ingestion.importDocument(NAME, content("乙", 5), KbDocumentSource.UPLOADED, 1L))
                .isInstanceOf(KnowledgeUnavailableException.class);

        KbDocument after = documents.findByName(NAME).orElseThrow();
        assertThat(after.getContentHash()).isEqualTo(original.getContentHash());
        assertThat(after.getSource()).isEqualTo(KbDocumentSource.BUILT_IN);
        assertThat(chunkRows(after.getId())).isEqualTo(3);
    }

    @Test
    void builtInDocumentsAreImportedAtStartup() {
        assertThat(documents.findByName("项目风险管理规定")).isPresent();
        assertThat(documents.findByName("项目管理制度")).isPresent();
    }
}
