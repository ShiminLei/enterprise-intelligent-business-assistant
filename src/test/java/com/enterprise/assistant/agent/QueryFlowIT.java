package com.enterprise.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;

import com.enterprise.assistant.agent.AgentEvent.StepFinished;
import com.enterprise.assistant.agent.AgentEvent.StepStarted;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

/** User Story 1 端到端：模型依次调用查询工具，页面收到步骤，第二轮带上第一轮历史。 */
class QueryFlowIT extends AbstractIntegrationTest {

    @Autowired
    AgentService agent;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    @BeforeEach
    void reset() {
        chatModel.reset();
    }

    @Test
    void overdueQueryThenFollowUp() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        long id = conversations.create(wang).getId();
        chatModel.thenCallTool("findProjects", "{\"keyword\":\"项目A\"}")
                .thenCallTool("queryTasks", "{\"projectCode\":\"P-001\",\"overdueOnly\":true}")
                .thenAnswer("项目A 有 3 个延期任务：T-103、T-104、T-105。")
                .thenAnswer("其中优先级最高的是 T-103 支付接口联调。");

        List<AgentEvent> first = new CopyOnWriteArrayList<>();
        agent.handle(wang, id, "项目A有哪些延期任务？", List.of(), first::add);

        assertThat(first).filteredOn(e -> e instanceof StepStarted)
                .extracting(e -> ((StepStarted) e).label()).containsExactly("查询项目", "查询任务");
        assertThat(first).filteredOn(e -> e instanceof StepFinished)
                .extracting(e -> ((StepFinished) e).resultSummary())
                .last().isEqualTo("找到 3 个延期任务");

        List<AgentEvent> second = new CopyOnWriteArrayList<>();
        agent.handle(wang, id, "其中优先级最高的是哪个？", List.of(), second::add);

        List<String> history = chatModel.lastPrompt().getInstructions().stream().map(Message::getText).toList();
        assertThat(history).contains("项目A有哪些延期任务？", "项目A 有 3 个延期任务：T-103、T-104、T-105。",
                "其中优先级最高的是哪个？");
    }
}
