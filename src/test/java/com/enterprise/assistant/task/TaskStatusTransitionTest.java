package com.enterprise.assistant.task;

import static com.enterprise.assistant.task.TaskStatus.CANCELLED;
import static com.enterprise.assistant.task.TaskStatus.DONE;
import static com.enterprise.assistant.task.TaskStatus.IN_PROGRESS;
import static com.enterprise.assistant.task.TaskStatus.TODO;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** FR-016a：只允许 5 种状态变化，已完成、已取消为终态。 */
class TaskStatusTransitionTest {

    static final Set<String> ALLOWED = Set.of(
            "TODO->IN_PROGRESS", "TODO->CANCELLED",
            "IN_PROGRESS->DONE", "IN_PROGRESS->TODO", "IN_PROGRESS->CANCELLED");

    @Test
    void onlyTheFiveDocumentedTransitionsAreAllowed() {
        for (TaskStatus from : TaskStatus.values()) {
            for (TaskStatus to : TaskStatus.values()) {
                boolean expected = ALLOWED.contains(from + "->" + to);
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(expected);
                if (expected) {
                    assertThat(from.transitionError(to)).isNull();
                } else {
                    assertThat(from.transitionError(to)).as(from + " -> " + to)
                            .contains(from.label()).contains(to.label());
                }
            }
        }
    }

    @Test
    void todoToDoneExplainsTheRequiredIntermediateStep() {
        assertThat(TODO.transitionError(DONE))
                .isEqualTo("当前为「待开始」，不能直接变为「已完成」，需先变为「进行中」");
    }

    @Test
    void terminalStatesExplainTheyCannotChange() {
        assertThat(DONE.transitionError(IN_PROGRESS)).contains("终态");
        assertThat(CANCELLED.transitionError(TODO)).contains("终态");
    }
}
