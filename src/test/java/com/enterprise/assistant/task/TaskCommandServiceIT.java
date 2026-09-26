package com.enterprise.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;
import com.enterprise.assistant.member.MemberRepository;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.enterprise.assistant.task.TaskCommandService.CreateTask;

@Transactional
class TaskCommandServiceIT extends AbstractIntegrationTest {

    @Autowired
    TaskCommandService commands;

    @Autowired
    TaskRepository tasks;

    @Autowired
    MemberRepository members;

    @Autowired
    MemberUserDetailsService users;

    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    CurrentMember as(String username) {
        return users.loadUserByUsername(username);
    }

    long idOf(String username) {
        return members.findByUsername(username).orElseThrow().getId();
    }

    CreateTask create(String title, LocalDate due) {
        return new CreateTask("P-001", title, idOf("zhangsan"), due, TaskPriority.HIGH);
    }

    static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(code);
    }

    @Test
    void createsTodoTaskWithNextSequentialCode() {
        Task created = commands.createTask(as("wangjl"), create("  跟进支付接口联调  ", today.plusDays(3)));

        assertThat(created.getCode()).isEqualTo("T-131");
        assertThat(created.getTitle()).isEqualTo("跟进支付接口联调");
        assertThat(created.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(created.getAssignee().getName()).isEqualTo("张三");
        assertThat(created.getProject().getCode()).isEqualTo("P-001");
        assertThat(tasks.findByCodeIgnoreCase("T-131")).isPresent();
    }

    @Test
    void validatesTitleAndDueDate() {
        assertCode(() -> commands.createTask(as("wangjl"), create("   ", today)), ErrorCode.BAD_REQUEST);
        assertCode(() -> commands.createTask(as("wangjl"), create("长".repeat(201), today)), ErrorCode.BAD_REQUEST);
        assertCode(() -> commands.createTask(as("wangjl"), create("昨天到期", today.minusDays(1))), ErrorCode.BAD_REQUEST);
        assertThat(tasks.count()).isEqualTo(30);
    }

    @Test
    void permissionIsCheckedBeforeWriting() {
        assertCode(() -> commands.createTask(as("zhangsan"), create("成员创建", today)), ErrorCode.FORBIDDEN);
        assertCode(() -> commands.createTask(as("lijl"), create("他人项目", today)), ErrorCode.FORBIDDEN);
        assertThat(tasks.count()).isEqualTo(30);
    }

    @Test
    void completingATaskRecordsCompletedDate() {
        Task done = commands.updateStatus(as("wangjl"), "T-104", TaskStatus.DONE);

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getCompletedDate()).isEqualTo(today);
    }

    @Test
    void illegalTransitionLeavesTaskUnchanged() {
        assertThatThrownBy(() -> commands.updateStatus(as("wangjl"), "T-102", TaskStatus.DONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("需先变为「进行中」");
        assertThat(tasks.findByCodeIgnoreCase("T-102").orElseThrow().getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void teamMemberCanOnlyUpdateOwnTasks() {
        assertThat(commands.updateStatus(as("lisi"), "T-102", TaskStatus.IN_PROGRESS).getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
        assertCode(() -> commands.updateStatus(as("lisi"), "T-103", TaskStatus.DONE), ErrorCode.FORBIDDEN);
    }

    @Test
    void unknownTaskIsNotFound() {
        assertCode(() -> commands.updateStatus(as("wangjl"), "T-999", TaskStatus.DONE), ErrorCode.NOT_FOUND);
    }
}
