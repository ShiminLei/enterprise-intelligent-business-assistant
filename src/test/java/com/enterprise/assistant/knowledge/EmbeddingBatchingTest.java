package com.enterprise.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** 百炼 text-embedding-v4 单次最多 10 条文本（research R2）。 */
class EmbeddingBatchingTest {

    @Test
    void batchesNeverExceedTenDocuments() {
        List<Document> docs = IntStream.range(0, 25).mapToObj(i -> new Document("片段" + i)).toList();

        List<List<Document>> batches = new MaxCountBatchingStrategy(10).batch(docs);

        assertThat(batches).extracting(List::size).containsExactly(10, 10, 5);
        assertThat(batches.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(docs);
    }

    @Test
    void emptyInputProducesNoBatches() {
        assertThat(new MaxCountBatchingStrategy(10).batch(List.of())).isEmpty();
    }
}
