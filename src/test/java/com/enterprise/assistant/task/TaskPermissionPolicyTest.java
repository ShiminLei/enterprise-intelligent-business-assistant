package com.enterprise.assistant.task;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;
import com.enterprise.assistant.member.Member;
import com.enterprise.assistant.member.MemberRole;
import com.enterprise.assistant.project.Project;
import com.enterprise.assistant.security.CurrentMember;

/** FR-014a：按角色校验写操作权限。 */
class TaskPermissionPolicyTest {

    final TaskPermissionPolicy policy = new TaskPermissionPolicy();

    static final CurrentMember WANG = new CurrentMember(1, "wangjl", "x", "王经理", MemberRole.PROJECT_MANAGER);
    static final CurrentMember LI = new CurrentMember(2, "lijl", "x", "李经理", MemberRole.PROJECT_MANAGER);
    static final CurrentMember ZHANG = new CurrentMember(3, "zhangsan", "x", "张三", MemberRole.TEAM_MEMBER);
    static final CurrentMember LISI = new CurrentMember(4, "lisi", "x", "李四", MemberRole.TEAM_MEMBER);

    static Member member(long id, String name) {
        Member m = mock(Member.class);
        when(m.getId()).thenReturn(id);
        when(m.getName()).thenReturn(name);
        return m;
    }

    /** 项目A：负责人王经理。 */
    static Project projectA() {
        Member wang = member(1, "王经理");
        Project p = mock(Project.class);
        when(p.getName()).thenReturn("项目A（客户门户升级）");
        when(p.getManager()).thenReturn(wang);
        return p;
    }

    /** 项目A 中分配给张三的任务。 */
    static Task taskOfZhang() {
        Project a = projectA();
        Member zhang = member(3, "张三");
        Task t = mock(Task.class);
        when(t.getCode()).thenReturn("T-103");
        when(t.getProject()).thenReturn(a);
        when(t.getAssignee()).thenReturn(zhang);
        return t;
    }

    static void assertForbidden(Runnable action, String messagePart) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void managerCanCreateTasksInOwnProject() {
        assertThatCode(() -> policy.checkCreateTask(WANG, projectA())).doesNotThrowAnyException();
    }

    @Test
    void managerCannotCreateTasksInOthersProject() {
        assertForbidden(() -> policy.checkCreateTask(LI, projectA()), "王经理");
    }

    @Test
    void teamMemberCannotCreateTasks() {
        assertForbidden(() -> policy.checkCreateTask(ZHANG, projectA()), "团队成员不能创建任务");
    }

    @Test
    void managerCanUpdateAnyTaskInOwnProject() {
        assertThatCode(() -> policy.checkUpdateStatus(WANG, taskOfZhang())).doesNotThrowAnyException();
    }

    @Test
    void managerCannotUpdateTasksInOthersProject() {
        assertForbidden(() -> policy.checkUpdateStatus(LI, taskOfZhang()), "王经理");
    }

    @Test
    void teamMemberCanUpdateOwnTask() {
        assertThatCode(() -> policy.checkUpdateStatus(ZHANG, taskOfZhang())).doesNotThrowAnyException();
    }

    @Test
    void teamMemberCannotUpdateOthersTask() {
        assertForbidden(() -> policy.checkUpdateStatus(LISI, taskOfZhang()), "只能修改分配给自己的任务");
    }
}
