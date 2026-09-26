package com.enterprise.assistant.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 确定性的向量模型替身：按字符出现次数生成 1024 维归一化向量（字符越相近，余弦相似度越高），
 * 并记录每次调用的输入条数，用于验证分批不超过 10 条。
 */
public class HashEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;

    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    public void reset() {
        batchSizes.clear();
        failing.set(false);
    }

    /** 让后续调用抛出异常，模拟向量服务不可用。 */
    public void setFailing(boolean failing) {
        this.failing.set(failing);
    }

    public List<Integer> batchSizes() {
        return List.copyOf(batchSizes);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (failing.get()) {
            throw new IllegalStateException("向量服务不可用（测试模拟）");
        }
        batchSizes.add(request.getInstructions().size());
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(vectorOf(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorOf(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    static float[] vectorOf(String text) {
        float[] v = new float[DIMENSIONS];
        text.codePoints()
                .filter(cp -> !Character.isWhitespace(cp))
                .forEach(cp -> v[Math.floorMod(Integer.hashCode(cp) * 31 + 7, DIMENSIONS)] += 1f);
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        float n = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] /= n;
        }
        return v;
    }
}
