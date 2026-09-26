package com.enterprise.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.enterprise.assistant.agent.AgentEvent.Answer;
import com.enterprise.assistant.agent.AgentEvent.StepStarted;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.conversation.Message;
import com.enterprise.assistant.conversation.MessageRole;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 核心演示用例：查询项目 → 查询任务 → 检索知识库 → 综合分析（User Story 3）。 */
class RiskAnalysisFlowIT extends AbstractIntegrationTest {

    @Autowired
    AgentService agent;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    @Autowired
    JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        chatModel.reset();
        embeddingModel.reset();
    }

    @Test
    void riskAnalysisRunsThreeStepsAndCitesTheRiskPolicy() throws Exception {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        long id = conversations.create(wang).getId();
        String riskChunk = jdbc.queryForObject("SELECT content FROM vector_store WHERE metadata->>'document_name' = "
                + "'项目风险管理规定' AND metadata->>'section' = '二、延期风险等级'", String.class);

        chatModel.thenCallTool("findProjects", "{\"keyword\":\"项目A\"}")
                .thenCallTool("queryTasks", "{\"projectCode\":\"P-001\",\"overdueOnly\":true}")
                .thenCallTool("searchKnowledge", json.writeValueAsString(Map.of("query", riskChunk)))
                .thenAnswer("## 项目A 风险分析\n\n| 任务 | 延期天数 | 风险等级 |\n| --- | --- | --- |\n"
                        + "| T-103 | 9 | 高 |\n\n依据《项目风险管理规定》，延期超过 7 天为高风险[1]。");

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        agent.handle(wang, id, "查询项目A目前有哪些延期任务，并结合公司的风险管理规定生成一份风险分析。", List.of(), events::add);

        assertThat(events).filteredOn(e -> e instanceof StepStarted)
                .extracting(e -> ((StepStarted) e).label())
                .containsExactly("查询项目", "查询任务", "检索知识库");

        Answer answer = (Answer) events.stream().filter(e -> e instanceof Answer).findFirst().orElseThrow();
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).allSatisfy(c -> assertThat(answer.content()).contains("[" + c.index() + "]"));
        assertThat(answer.citations().get(0).index()).isEqualTo(1);
        assertThat(answer.citations().get(0).documentName()).isEqualTo("项目风险管理规定");
        assertThat(answer.citations().get(0).section()).isEqualTo("二、延期风险等级");

        Message saved = conversations.listMessages(wang, id).stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT).findFirst().orElseThrow();
        assertThat(saved.getSteps()).extracting(s -> s.tool())
                .containsExactly("findProjects", "queryTasks", "searchKnowledge");
        assertThat(saved.getCitations()).isEqualTo(answer.citations());
    }

    @Test
    void citationsAreKeptWhenAnswerDoesNotReferenceThem() throws Exception {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        long id = conversations.create(wang).getId();
        String riskChunk = jdbc.queryForObject("SELECT content FROM vector_store WHERE metadata->>'document_name' = "
                + "'项目风险管理规定' AND metadata->>'section' = '二、延期风险等级'", String.class);
        chatModel.thenCallTool("searchKnowledge", json.writeValueAsString(Map.of("query", riskChunk)))
                .thenAnswer("延期超过 7 天为高风险。");

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        agent.handle(wang, id, "延期多久算高风险？", List.of(), events::add);

        Answer answer = (Answer) events.stream().filter(e -> e instanceof Answer).findFirst().orElseThrow();
        assertThat(answer.citations()).isNotEmpty();
    }
}
