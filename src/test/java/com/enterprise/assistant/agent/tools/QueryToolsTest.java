package com.enterprise.assistant.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;

import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 查询工具的返回格式与 contracts/agent-tools.md 一致。 */
class QueryToolsTest extends AbstractIntegrationTest {

    @Autowired
    ProjectTools projectTools;

    @Autowired
    TaskQueryTools taskTools;

    @Autowired
    MemberTools memberTools;

    @Autowired
    MemberUserDetailsService users;

    final ObjectMapper json = new ObjectMapper();

    ToolContext contextOf(String username) {
        return new ToolContext(Map.of(AgentRequestContext.KEY,
                new AgentRequestContext(users.loadUserByUsername(username), 0, e -> { })));
    }

    @Test
    void findProjectsReturnsSummaries() throws Exception {
        JsonNode result = json.readTree(projectTools.findProjects("项目A"));
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("summary").asText()).contains("项目A");
        JsonNode project = result.get("data").get(0);
        for (String field : List.of("code", "name", "manager", "plannedStartDate", "plannedEndDate", "status",
                "taskCount", "overdueTaskCount")) {
            assertThat(project.has(field)).as(field).isTrue();
        }
        assertThat(project.get("plannedStartDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void findProjectsWithoutMatchListsCandidates() throws Exception {
        JsonNode result = json.readTree(projectTools.findProjects("项目Z"));
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("未找到匹配的项目");
        assertThat(result.get("candidates")).hasSize(3);
    }

    @Test
    void queryTasksReturnsOverdueTasksWithDays() throws Exception {
        JsonNode result = json.readTree(taskTools.queryTasks("P-001", null, null, true, contextOf("wangjl")));
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("summary").asText()).isEqualTo("找到 3 个延期任务");
        JsonNode data = result.get("data");
        assertThat(data.get("total").asInt()).isEqualTo(3);
        JsonNode first = data.get("items").get(0);
        for (String field : List.of("code", "title", "projectCode", "assignee", "priority", "status", "dueDate", "overdueDays")) {
            assertThat(first.has(field)).as(field).isTrue();
        }
    }

    @Test
    void queryTasksForMe() throws Exception {
        JsonNode result = json.readTree(taskTools.queryTasks(null, List.of("TODO", "IN_PROGRESS"), "me", null, contextOf("lisi")));
        assertThat(result.get("data").get("items")).allSatisfy(t -> assertThat(t.get("assignee").asText()).isEqualTo("李四"));
    }

    @Test
    void invalidStatusIsReportedInChinese() throws Exception {
        JsonNode result = json.readTree(taskTools.queryTasks(null, List.of("FINISHED"), null, null, contextOf("wangjl")));
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("error").asText()).contains("FINISHED").contains("TODO");
    }

    @Test
    void findMembersByName() throws Exception {
        JsonNode result = json.readTree(memberTools.findMembers("张"));
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("data").get(0).get("name").asText()).isEqualTo("张三");
        assertThat(result.get("data").get(0).get("username").asText()).isEqualTo("zhangsan");
    }

    @Test
    void longListsAreTruncatedToFiftyWithTotal() throws Exception {
        List<Integer> items = IntStream.range(0, 60).boxed().toList();
        JsonNode result = json.readTree(ToolResult.okList("找到 60 个任务", items));
        assertThat(result.get("data").get("total").asInt()).isEqualTo(60);
        assertThat(result.get("data").get("items")).hasSize(50);
        assertThat(result.get("data").get("note").asText()).contains("缩小范围");
    }
}
