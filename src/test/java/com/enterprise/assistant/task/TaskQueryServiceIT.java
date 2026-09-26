package com.enterprise.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.member.MemberService.AmbiguousMemberException;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.enterprise.assistant.task.TaskQueryService.TaskQuery;
import com.enterprise.assistant.task.TaskQueryService.TaskView;

class TaskQueryServiceIT extends AbstractIntegrationTest {

    @Autowired
    TaskQueryService tasks;

    @Autowired
    MemberUserDetailsService users;

    CurrentMember wang() {
        return users.loadUserByUsername("wangjl");
    }

    @Test
    void overdueTasksOfProjectAOrderedByDueDate() {
        List<TaskView> result = tasks.query(wang(), new TaskQuery("P-001", null, null, true));
        assertThat(result).extracting(TaskView::code).containsExactly("T-103", "T-104", "T-105");
        assertThat(result).extracting(TaskView::overdueDays).containsExactly(9L, 5L, 2L);
        assertThat(result.get(0).assignee()).isEqualTo("张三");
        assertThat(result.get(0).priority()).isEqualTo(TaskPriority.HIGH);
    }

    @Test
    void filtersByStatusList() {
        List<TaskView> result = tasks.query(wang(), new TaskQuery("P-001", List.of(TaskStatus.DONE, TaskStatus.CANCELLED), null, false));
        assertThat(result).extracting(TaskView::code).containsExactlyInAnyOrder("T-101", "T-106", "T-107");
        assertThat(result).allSatisfy(t -> assertThat(t.overdueDays()).isNull());
    }

    @Test
    void assigneeMeResolvesToCurrentMember() {
        CurrentMember zhang = users.loadUserByUsername("zhangsan");
        List<TaskView> mine = tasks.query(zhang, new TaskQuery(null, List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS), "me", false));
        assertThat(mine).isNotEmpty().allSatisfy(t -> assertThat(t.assignee()).isEqualTo("张三"));
        assertThat(mine).extracting(TaskView::projectCode).contains("P-001", "P-002", "P-003");
    }

    @Test
    void filtersByAssigneeName() {
        assertThat(tasks.query(wang(), new TaskQuery(null, null, "李四", false)))
                .isNotEmpty().allSatisfy(t -> assertThat(t.assignee()).isEqualTo("李四"));
    }

    @Test
    void ambiguousAssigneeNameReturnsCandidates() {
        assertThatThrownBy(() -> tasks.query(wang(), new TaskQuery(null, null, "李", false)))
                .isInstanceOf(AmbiguousMemberException.class)
                .satisfies(e -> assertThat(((AmbiguousMemberException) e).candidates()).containsExactly("李经理", "李四"));
    }

    @Test
    void unknownProjectCodeIsNotFound() {
        assertThatThrownBy(() -> tasks.query(wang(), new TaskQuery("P-999", null, null, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("P-999");
    }

    @Test
    void allOverdueTasksAcrossProjects() {
        assertThat(tasks.query(wang(), new TaskQuery(null, null, null, true)))
                .extracting(TaskView::code).containsExactly("T-103", "T-104", "T-112", "T-105");
    }
}
