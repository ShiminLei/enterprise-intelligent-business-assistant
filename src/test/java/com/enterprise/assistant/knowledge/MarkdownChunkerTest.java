package com.enterprise.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class MarkdownChunkerTest {

    final MarkdownChunker chunker = new MarkdownChunker();

    static final String DOC = """
            # 项目风险管理规定

            本规定适用于公司全部项目。

            ## 一、总则

            ## 二、延期风险等级

            延期 1–3 天为低风险。

            ### 2.1 高风险

            延期超过 7 天为高风险。

            ## 三、上报流程

            高风险须在 1 个工作日内上报。
            """;

    @Test
    void splitsByHeadingsAndRecordsSectionMetadata() {
        List<Document> chunks = chunker.chunk(42L, "项目风险管理规定", DOC);

        assertThat(chunks).extracting(d -> d.getMetadata().get("section")).containsExactly(
                "项目风险管理规定", "二、延期风险等级", "二、延期风险等级 / 2.1 高风险", "三、上报流程");
        assertThat(chunks).allSatisfy(d -> {
            assertThat(d.getMetadata()).containsEntry("document_id", 42L)
                    .containsEntry("document_name", "项目风险管理规定")
                    .containsKey("chunk_index");
        });
        assertThat(chunks).extracting(d -> d.getMetadata().get("chunk_index")).containsExactly(0, 1, 2, 3);
        assertThat(chunks.get(2).getText()).contains("2.1 高风险").contains("延期超过 7 天为高风险");
    }

    @Test
    void skipsSectionsWithoutBody() {
        assertThat(chunker.chunk(1L, "d", DOC))
                .noneMatch(d -> "一、总则".equals(d.getMetadata().get("section")));
    }

    @Test
    void longSectionIsSplitWithOverlap() {
        StringBuilder body = new StringBuilder("## 长章节\n\n");
        for (int i = 1; i <= 120; i++) {
            body.append("第").append(i).append("条：项目经理应当按周检查任务进度并记录风险。");
        }
        List<Document> chunks = chunker.chunk(1L, "长文档", body.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(d ->
                assertThat(MarkdownChunker.estimateTokens(d.getText())).isLessThanOrEqualTo(MarkdownChunker.MAX_TOKENS + 60));
        // 相邻片段有重叠：后一个片段的开头出现在前一个片段中
        String first = chunks.get(0).getText();
        String secondStart = chunks.get(1).getText().replaceFirst("^长章节\\n", "").substring(0, 10);
        assertThat(first).contains(secondStart);
    }

    @Test
    void emptyDocumentProducesNoChunks() {
        assertThat(chunker.chunk(1L, "空", "   \n\n")).isEmpty();
    }
}
