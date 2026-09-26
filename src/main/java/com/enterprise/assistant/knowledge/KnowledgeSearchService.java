package com.enterprise.assistant.knowledge;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/** 知识库检索（research R5）：最多 topK 条、相似度不低于阈值的片段。 */
@Service
public class KnowledgeSearchService {

    public record Chunk(String id, String documentName, String section, String content, double score) {
    }

    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    public KnowledgeSearchService(VectorStore vectorStore, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * @throws KnowledgeUnavailableException 向量服务或数据库不可用
     */
    public List<Chunk> search(String query) {
        List<Document> documents;
        try {
            documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(properties.topK())
                    .similarityThreshold(properties.similarityThreshold())
                    .build());
        } catch (RuntimeException e) {
            throw new KnowledgeUnavailableException("知识库暂不可用", e);
        }
        return documents.stream()
                .map(d -> new Chunk(d.getId(), String.valueOf(d.getMetadata().get("document_name")),
                        String.valueOf(d.getMetadata().getOrDefault("section", "")), d.getText(),
                        d.getScore() == null ? 0 : d.getScore()))
                .toList();
    }
}
