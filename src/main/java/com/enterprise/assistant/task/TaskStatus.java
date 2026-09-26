package com.enterprise.assistant.task;

import java.util.Map;
import java.util.Set;

/** 任务状态与流转规则（FR-016a）。「未完成」= 待开始 + 进行中。 */
public enum TaskStatus {
    TODO("待开始"),
    IN_PROGRESS("进行中"),
    DONE("已完成"),
    CANCELLED("已取消");

    public static final Set<TaskStatus> UNFINISHED = Set.of(TODO, IN_PROGRESS);

    /** 允许的状态变化；未列出的一律拒绝。已完成、已取消为终态。 */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            TODO, Set.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, Set.of(DONE, TODO, CANCELLED),
            DONE, Set.of(),
            CANCELLED, Set.of());

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isUnfinished() {
        return UNFINISHED.contains(this);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean canTransitionTo(TaskStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** 变化合法时返回 null，否则返回面向用户的中文原因。 */
    public String transitionError(TaskStatus target) {
        if (canTransitionTo(target)) {
            return null;
        }
        if (isTerminal()) {
            return "当前为「" + label + "」，已是终态，不能再变为「" + target.label + "」";
        }
        if (this == target) {
            return "当前已经是「" + label + "」，不能再变为「" + target.label + "」";
        }
        if (this == TODO && target == DONE) {
            return "当前为「待开始」，不能直接变为「已完成」，需先变为「进行中」";
        }
        return "当前为「" + label + "」，不能变为「" + target.label + "」";
    }
}
