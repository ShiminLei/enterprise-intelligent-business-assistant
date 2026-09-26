package com.enterprise.assistant.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.action.PendingActionRepository;
import com.enterprise.assistant.agent.AgentEvent;
import com.enterprise.assistant.agent.AgentEvent.PendingActionProposed;
import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Transactional
class TaskWriteToolsTest extends AbstractIntegrationTest {

    @Autowired
    TaskWriteTools tools;

    @Autowired
    PendingActionRepository pendingActions;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    final ObjectMapper json = new ObjectMapper();
    final String nextWeek = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7).toString();
    final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    AgentRequestContext context;

    @BeforeEach
    void setUp() {
        as("wangjl");
    }

    ToolContext as(String username) {
        CurrentMember member = users.loadUserByUsername(username);
        context = new AgentRequestContext(member, conversations.create(member).getId(), events::add);
        return new ToolContext(Map.of(AgentRequestContext.KEY, context));
    }

    ToolContext toolContext() {
        return new ToolContext(Map.of(AgentRequestContext.KEY, context));
    }

    JsonNode create(String project, String title, String assignee, String due, String priority) throws Exception {
        return json.readTree(tools.proposeCreateTask(project, title, assignee, due, priority, toolContext()));
    }

    @Test
    void successfulProposalCreatesPendingActionAndEvent() throws Exception {
        JsonNode result = create("P-001", "跟进支付接口联调", "张三", nextWeek, "HIGH");

        assertThat(result.get("ok").asBoolean()).isTrue();
        long id = result.get("data").get("pendingActionId").asLong();
        assertThat(result.get("data").get("summary").asText()).contains("跟进支付接口联调").contains("张三");
        assertThat(pendingActions.findById(id)).isPresent();
        assertThat(context.pendingActionIds()).containsExactly(id);
        assertThat(events).singleElement().isInstanceOfSatisfying(PendingActionProposed.class, e -> {
            assertThat(e.id()).isEqualTo(id);
            assertThat(e.status()).isEqualTo("PENDING");
            assertThat(e.type()).isEqualTo("CREATE_TASK");
        });
    }

    @Test
    void invalidParametersAreReportedFirst() throws Exception {
        JsonNode result = create("P-999", "标题", "张三", nextWeek, "URGENT");
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("error").asText()).contains("优先级");

        result = create("P-001", "标题", "张三", "下周五", "HIGH");
        assertThat(result.get("error").asText()).contains("日期");
        assertThat(pendingActions.count()).isZero();
    }

    @Test
    void validationOrderProjectThenPermissionThenAssigneeThenDate() throws Exception {
        JsonNode noProject = create("P-999", "标题", "不存在的人", "2000-01-01", "HIGH");
        assertThat(noProject.get("error").asText()).contains("P-999");
        assertThat(noProject.get("candidates")).isNotEmpty();

        JsonNode notManager = create("P-002", "标题", "不存在的人", "2000-01-01", "HIGH");
        assertThat(notManager.get("error").asText()).contains("李经理");

        JsonNode ambiguous = create("P-001", "标题", "李", "2000-01-01", "HIGH");
        assertThat(ambiguous.get("candidates")).extracting(JsonNode::asText).containsExactly("李经理", "李四");

        JsonNode pastDate = create("P-001", "标题", "张三", "2000-01-01", "HIGH");
        assertThat(pastDate.get("error").asText()).contains("截止日期");

        assertThat(pendingActions.count()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void teamMemberCannotProposeCreation() throws Exception {
        as("zhangsan");
        JsonNode result = create("P-001", "标题", "张三", nextWeek, "HIGH");
        assertThat(result.get("error").asText()).contains("团队成员不能创建任务");
        assertThat(pendingActions.count()).isZero();
    }

    @Test
    void statusProposalValidatesPermissionAndTransition() throws Exception {
        JsonNode illegal = json.readTree(tools.proposeUpdateTaskStatus("T-102", "DONE", toolContext()));
        assertThat(illegal.get("error").asText()).isEqualTo("任务 T-102 当前为「待开始」，不能直接变为「已完成」，需先变为「进行中」");

        as("lisi");
        JsonNode othersTask = json.readTree(tools.proposeUpdateTaskStatus("T-103", "DONE", toolContext()));
        assertThat(othersTask.get("error").asText()).contains("只能修改分配给自己的任务");

        JsonNode unknown = json.readTree(tools.proposeUpdateTaskStatus("T-999", "DONE", toolContext()));
        assertThat(unknown.get("ok").asBoolean()).isFalse();

        JsonNode ok = json.readTree(tools.proposeUpdateTaskStatus("T-102", "IN_PROGRESS", toolContext()));
        assertThat(ok.get("ok").asBoolean()).isTrue();
        assertThat(ok.get("data").get("summary").asText()).contains("「待开始」改为「进行中」");
        assertThat(pendingActions.count()).isEqualTo(1);
    }
}
