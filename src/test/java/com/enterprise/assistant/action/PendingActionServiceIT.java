package com.enterprise.assistant.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.enterprise.assistant.task.TaskCommandService;
import com.enterprise.assistant.task.TaskRepository;
import com.enterprise.assistant.task.TaskStatus;

@Transactional
class PendingActionServiceIT extends AbstractIntegrationTest {

    @Autowired
    PendingActionService actions;

    @Autowired
    ConversationService conversations;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskCommandService commands;

    @Autowired
    MemberUserDetailsService users;

    CurrentMember wang;
    long conversationId;
    final String friday = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(4).toString();

    @BeforeEach
    void setUp() {
        wang = users.loadUserByUsername("wangjl");
        conversationId = conversations.create(wang).getId();
    }

    PendingAction proposeCreate() {
        return actions.proposeCreateTask(wang, conversationId, "P-001", "跟进支付接口联调", "张三", friday, "HIGH");
    }

    static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(code);
    }

    @Test
    void proposalDoesNotChangeData() {
        PendingAction action = proposeCreate();

        assertThat(action.getStatus()).isEqualTo(PendingActionStatus.PENDING);
        assertThat(action.getType()).isEqualTo(PendingActionType.CREATE_TASK);
        assertThat(action.getSummary()).isEqualTo(
                "在「项目A（客户门户升级）」中创建任务「跟进支付接口联调」，负责人张三，截止 " + friday + "，优先级高");
        assertThat(tasks.count()).isEqualTo(30);
    }

    @Test
    void confirmExecutesAndRecordsAudit() {
        PendingAction action = actions.confirm(wang, proposeCreate().getId());

        assertThat(action.getStatus()).isEqualTo(PendingActionStatus.EXECUTED);
        assertThat(action.getResult()).isEqualTo("已创建任务 T-131");
        assertThat(action.getResolvedAt()).isNotNull();
        assertThat(action.getResolvedBy()).isEqualTo(wang.id());
        assertThat(tasks.findByCodeIgnoreCase("T-131")).isPresent();
    }

    @Test
    void onlyTheRequesterCanConfirmOrCancel() {
        long id = proposeCreate().getId();
        CurrentMember li = users.loadUserByUsername("lijl");

        assertCode(() -> actions.confirm(li, id), ErrorCode.FORBIDDEN);
        assertCode(() -> actions.cancel(li, id), ErrorCode.FORBIDDEN);
        assertThat(tasks.count()).isEqualTo(30);
    }

    @Test
    void cancelLeavesDataUnchanged() {
        PendingAction cancelled = actions.cancel(wang, proposeCreate().getId());

        assertThat(cancelled.getStatus()).isEqualTo(PendingActionStatus.CANCELLED);
        assertThat(tasks.count()).isEqualTo(30);
    }

    @Test
    void resolvedActionCannotBeConfirmedAgain() {
        long id = proposeCreate().getId();
        actions.confirm(wang, id);

        assertCode(() -> actions.confirm(wang, id), ErrorCode.CONFLICT);
        assertThat(tasks.count()).isEqualTo(31);
    }

    @Test
    void confirmRevalidatesAgainstCurrentData() {
        PendingAction proposal = actions.proposeUpdateStatus(wang, conversationId, "T-102", "IN_PROGRESS");
        // 确认前，任务已被他人取消
        commands.updateStatus(wang, "T-102", TaskStatus.CANCELLED);

        PendingAction result = actions.confirm(wang, proposal.getId());

        assertThat(result.getStatus()).isEqualTo(PendingActionStatus.FAILED);
        assertThat(result.getResult()).contains("终态");
        assertThat(tasks.findByCodeIgnoreCase("T-102").orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void newMessageExpiresPendingActionsOfThatConversation() {
        long first = proposeCreate().getId();
        long second = actions.proposeUpdateStatus(wang, conversationId, "T-102", "IN_PROGRESS").getId();
        long cancelled = proposeCreate().getId();
        actions.cancel(wang, cancelled);

        List<Long> expired = actions.expireForConversation(conversationId);

        assertThat(expired).containsExactlyInAnyOrder(first, second);
        assertThat(actions.get(first).getStatus()).isEqualTo(PendingActionStatus.EXPIRED);
        assertThat(actions.get(cancelled).getStatus()).isEqualTo(PendingActionStatus.CANCELLED);
        assertCode(() -> actions.confirm(wang, first), ErrorCode.CONFLICT);
    }

    @Test
    void unknownActionIsNotFound() {
        assertCode(() -> actions.confirm(wang, 999_999L), ErrorCode.NOT_FOUND);
    }
}
