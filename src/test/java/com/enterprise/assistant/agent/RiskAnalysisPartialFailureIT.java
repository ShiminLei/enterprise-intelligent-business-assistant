package com.enterprise.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;

import com.enterprise.assistant.agent.AgentEvent.StepFinished;
import com.enterprise.assistant.agent.AgentEvent.StepStarted;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.conversation.MessageRole;
import com.enterprise.assistant.conversation.MessageStatus;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

/** 某一步失败时，页面看到失败步骤，模型收到失败原因，回答照常保存（验收场景 US3-4）。 */
class RiskAnalysisPartialFailureIT extends AbstractIntegrationTest {

    @Autowired
    AgentService agent;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    @AfterEach
    void reset() {
        chatModel.reset();
        embeddingModel.reset();
    }

    @Test
    void knowledgeBaseFailureIsVisibleAndReportedToModel() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        long id = conversations.create(wang).getId();
        chatModel.reset();
        chatModel.thenCallTool("queryTasks", "{\"projectCode\":\"P-001\",\"overdueOnly\":true}")
                .thenCallTool("searchKnowledge", "{\"query\":\"延期风险等级\"}")
                .thenAnswer("已查到 3 个延期任务，但知识库暂不可用，无法给出基于制度的风险等级判定。");
        embeddingModel.setFailing(true);

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        agent.handle(wang, id, "项目A的延期任务风险如何？", List.of(), events::add);

        List<AgentEvent> steps = events.stream().filter(e -> e instanceof StepStarted || e instanceof StepFinished).toList();
        StepFinished knowledgeStep = (StepFinished) steps.get(3);
        assertThat(((StepStarted) steps.get(2)).tool()).isEqualTo("searchKnowledge");
        assertThat(knowledgeStep.success()).isFalse();
        assertThat(knowledgeStep.resultSummary()).isEqualTo("知识库暂不可用");

        String toolResponses = chatModel.lastPrompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.TOOL).map(Object::toString)
                .reduce("", String::concat);
        assertThat(toolResponses).contains("知识库暂不可用");

        assertThat(conversations.listMessages(wang, id))
                .filteredOn(m -> m.getRole() == MessageRole.ASSISTANT)
                .singleElement()
                .satisfies(m -> assertThat(m.getStatus()).isEqualTo(MessageStatus.COMPLETED));
    }
}
