package com.enterprise.assistant.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class KnowledgeToolsTest extends AbstractIntegrationTest {

    @Autowired
    KnowledgeTools tools;

    @Autowired
    MemberUserDetailsService users;

    @Autowired
    JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void reset() {
        embeddingModel.reset();
    }

    AgentRequestContext newContext() {
        return new AgentRequestContext(users.loadUserByUsername("wangjl"), 0, e -> { });
    }

    ToolContext toolContext(AgentRequestContext context) {
        return new ToolContext(Map.of(AgentRequestContext.KEY, context));
    }

    @Test
    void returnsAtMostFiveRelevantChunksAndRegistersCitations() throws Exception {
        AgentRequestContext context = newContext();
        JsonNode result = json.readTree(tools.searchKnowledge("任务延期超过几天算高风险", toolContext(context)));

        assertThat(result.get("ok").asBoolean()).isTrue();
        JsonNode data = result.get("data");
        assertThat(data.size()).isBetween(1, 5);
        assertThat(data.get(0).get("ref").asInt()).isEqualTo(1);
        assertThat(data.get(0).get("documentName").asText()).isNotBlank();
        assertThat(data.get(0).has("section")).isTrue();
        assertThat(context.citations()).hasSize(data.size());
        assertThat(context.citations().get(0).documentName()).isEqualTo(data.get(0).get("documentName").asText());
    }

    @Test
    void refsContinueAcrossCallsWithinOneRequest() throws Exception {
        // 用两份内置文档中的真实片段作为检索语句，保证都有命中
        String riskChunk = jdbc.queryForObject("SELECT content FROM vector_store WHERE metadata->>'document_name' = '项目风险管理规定' "
                + "AND metadata->>'section' = '二、延期风险等级'", String.class);
        String ruleChunk = jdbc.queryForObject("SELECT content FROM vector_store WHERE metadata->>'document_name' = '项目管理制度' "
                + "AND metadata->>'section' = '二、任务状态流转规则'", String.class);
        AgentRequestContext context = newContext();

        JsonNode first = json.readTree(tools.searchKnowledge(riskChunk, toolContext(context)));
        JsonNode second = json.readTree(tools.searchKnowledge(ruleChunk, toolContext(context)));
        JsonNode repeated = json.readTree(tools.searchKnowledge(riskChunk, toolContext(context)));

        Map<String, Integer> refByChunk = new HashMap<>();
        for (JsonNode response : List.of(first, second, repeated)) {
            assertThat(response.get("data")).isNotEmpty();
            for (JsonNode item : response.get("data")) {
                Integer previous = refByChunk.putIfAbsent(item.get("content").asText(), item.get("ref").asInt());
                if (previous != null) {
                    assertThat(item.get("ref").asInt()).as("同一片段编号不变").isEqualTo(previous);
                }
            }
        }
        // 编号从 1 连续递增，与登记的引用一一对应
        assertThat(context.citations()).hasSize(refByChunk.size());
        assertThat(context.citations()).extracting(c -> c.index())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, refByChunk.size()).boxed().toList());
        assertThat(refByChunk.values()).containsExactlyInAnyOrderElementsOf(
                context.citations().stream().map(c -> c.index()).toList());
    }

    @Test
    void unrelatedQueryReturnsEmptyList() throws Exception {
        JsonNode result = json.readTree(tools.searchKnowledge("zzzz qqqq xxxx", toolContext(newContext())));
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("data")).isEmpty();
    }

    @Test
    void vectorServiceFailureMeansKnowledgeUnavailable() throws Exception {
        embeddingModel.setFailing(true);
        JsonNode result = json.readTree(tools.searchKnowledge("高风险", toolContext(newContext())));
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("知识库暂不可用");
    }
}
