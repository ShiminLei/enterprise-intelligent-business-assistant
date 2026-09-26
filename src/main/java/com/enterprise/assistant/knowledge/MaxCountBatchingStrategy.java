package com.enterprise.assistant.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

/**
 * 按条数分批的向量化策略：阿里云百炼 text-embedding-v4 单次请求最多 10 条文本（research R2）。
 * 每个片段不超过约 500 Token，远低于单条 8,192 Token 的限制。
 */
public class MaxCountBatchingStrategy implements BatchingStrategy {

    private final int maxCount;

    public MaxCountBatchingStrategy(int maxCount) {
        this.maxCount = maxCount;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += maxCount) {
            batches.add(List.copyOf(documents.subList(i, Math.min(documents.size(), i + maxCount))));
        }
        return batches;
    }
}
