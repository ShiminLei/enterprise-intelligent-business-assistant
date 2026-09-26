package com.enterprise.assistant.knowledge;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "kb_document")
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KbDocumentSource source;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected KbDocument() {
    }

    KbDocument(String name) {
        this.name = name;
    }

    void update(KbDocumentSource source, String contentHash, int chunkCount, Long uploadedBy, Instant importedAt) {
        this.source = source;
        this.contentHash = contentHash;
        this.chunkCount = chunkCount;
        this.uploadedBy = uploadedBy;
        this.importedAt = importedAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public KbDocumentSource getSource() {
        return source;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
