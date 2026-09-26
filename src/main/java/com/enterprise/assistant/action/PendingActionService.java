package com.enterprise.assistant.action;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.agent.AssistantMessageListener;
import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.task.Task;
import com.enterprise.assistant.task.TaskCommandService;
import com.enterprise.assistant.task.TaskCommandService.CreateTask;
import com.enterprise.assistant.task.TaskCommandService.PreparedCreate;
import com.enterprise.assistant.task.TaskCommandService.PreparedUpdate;
import com.enterprise.assistant.task.TaskPriority;
import com.enterprise.assistant.task.TaskStatus;

/**
 * 待确认操作（research R4）：写工具只「提议」，生成 PENDING 记录；用户在页面确认后由这里直接执行，
 * 不经过大模型。执行前重新校验权限与状态流转；只有发起人本人可以确认或取消。
 */
@Service
public class PendingActionService implements AssistantMessageListener {

    private static final Logger log = LoggerFactory.getLogger(PendingActionService.class);

    private final PendingActionRepository actions;
    private final TaskCommandService commands;
    private final Clock clock;

    public PendingActionService(PendingActionRepository actions, TaskCommandService commands, Clock clock) {
        this.actions = actions;
        this.commands = commands;
        this.clock = clock;
    }

    @Transactional
    public PendingAction proposeCreateTask(CurrentMember actor, long conversationId, String projectCode, String title,
                                           String assigneeName, String dueDate, String priority) {
        PreparedCreate prepared = commands.prepareCreate(actor, projectCode, title, assigneeName, dueDate, priority);
        CreateTask c = prepared.command();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectCode", c.projectCode());
        payload.put("title", c.title());
        payload.put("assigneeId", c.assigneeId());
        payload.put("assigneeName", prepared.assignee().getName());
        payload.put("dueDate", c.dueDate().toString());
        payload.put("priority", c.priority().name());
        String summary = "在「" + prepared.project().getName() + "」中创建任务「" + c.title() + "」，负责人"
                + prepared.assignee().getName() + "，截止 " + c.dueDate() + "，优先级" + c.priority().label();
        return actions.save(new PendingAction(conversationId, actor.id(), PendingActionType.CREATE_TASK, payload,
                summary, clock.instant()));
    }

    @Transactional
    public PendingAction proposeUpdateStatus(CurrentMember actor, long conversationId, String taskCode,
                                             String targetStatus) {
        PreparedUpdate prepared = commands.prepareUpdate(actor, taskCode, targetStatus);
        Task task = prepared.task();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskCode", task.getCode());
        payload.put("fromStatus", prepared.from().name());
        payload.put("targetStatus", prepared.to().name());
        String summary = "将任务 " + task.getCode() + "「" + task.getTitle() + "」的状态从「" + prepared.from().label()
                + "」改为「" + prepared.to().label() + "」";
        return actions.save(new PendingAction(conversationId, actor.id(), PendingActionType.UPDATE_TASK_STATUS, payload,
                summary, clock.instant()));
    }

    /** 确认并执行；执行时校验失败则记为 FAILED 并返回，不抛异常。 */
    @Transactional
    public PendingAction confirm(CurrentMember actor, long actionId) {
        PendingAction action = requirePendingOwnedBy(actor, actionId);
        try {
            String result = execute(actor, action);
            action.resolve(PendingActionStatus.EXECUTED, result, actor.id(), clock.instant());
            log.info("pending action executed id={} type={} by={}", action.getId(), action.getType(), actor.getUsername());
        } catch (BusinessException e) {
            action.resolve(PendingActionStatus.FAILED, e.getMessage(), actor.id(), clock.instant());
            log.info("pending action failed id={} reason={}", action.getId(), e.getMessage());
        }
        return action;
    }

    @Transactional
    public PendingAction cancel(CurrentMember actor, long actionId) {
        PendingAction action = requirePendingOwnedBy(actor, actionId);
        action.resolve(PendingActionStatus.CANCELLED, "已取消", actor.id(), clock.instant());
        return action;
    }

    /** 同一会话出现新的用户消息时，作废其中所有待确认操作，返回被作废的 ID。 */
    @Transactional
    public List<Long> expireForConversation(long conversationId) {
        List<PendingAction> pending = actions.findByConversationIdAndStatus(conversationId, PendingActionStatus.PENDING);
        pending.forEach(a -> a.resolve(PendingActionStatus.EXPIRED, "已作废", null, clock.instant()));
        return pending.stream().map(PendingAction::getId).toList();
    }

    @Transactional
    public void linkToMessage(Collection<Long> actionIds, long messageId) {
        actions.findAllById(actionIds).forEach(a -> a.linkToMessage(messageId));
    }

    @Override
    @Transactional
    public void onAssistantMessageSaved(long assistantMessageId, AgentRequestContext context) {
        if (!context.pendingActionIds().isEmpty()) {
            linkToMessage(context.pendingActionIds(), assistantMessageId);
        }
    }

    @Transactional(readOnly = true)
    public PendingAction get(long actionId) {
        return actions.findById(actionId).orElseThrow(() -> BusinessException.notFound("待确认操作不存在"));
    }

    @Transactional(readOnly = true)
    public List<PendingAction> findByMessageIds(Collection<Long> messageIds) {
        return messageIds.isEmpty() ? List.of() : actions.findByMessageIdInOrderById(messageIds);
    }

    private PendingAction requirePendingOwnedBy(CurrentMember actor, long actionId) {
        PendingAction action = actions.findById(actionId)
                .orElseThrow(() -> BusinessException.notFound("待确认操作不存在"));
        if (!action.getRequestedBy().equals(actor.id())) {
            throw BusinessException.forbidden("只有发起人可以确认或取消该操作");
        }
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw BusinessException.conflict("该操作" + action.getStatus().label() + "，不能再次处理");
        }
        return action;
    }

    private String execute(CurrentMember actor, PendingAction action) {
        Map<String, Object> p = action.getPayload();
        return switch (action.getType()) {
            case CREATE_TASK -> {
                Task created = commands.createTask(actor, new CreateTask(
                        (String) p.get("projectCode"), (String) p.get("title"),
                        ((Number) p.get("assigneeId")).longValue(),
                        LocalDate.parse((String) p.get("dueDate")),
                        TaskPriority.valueOf((String) p.get("priority"))));
                yield "已创建任务 " + created.getCode();
            }
            case UPDATE_TASK_STATUS -> {
                TaskStatus target = TaskStatus.valueOf((String) p.get("targetStatus"));
                Task task = commands.updateStatus(actor, (String) p.get("taskCode"), target);
                yield "已将任务 " + task.getCode() + " 的状态改为「" + target.label() + "」";
            }
        };
    }
}
