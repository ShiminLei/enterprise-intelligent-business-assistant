package com.enterprise.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class OverduePolicyTest {

    /** 固定「今天」为 2026-09-26（Asia/Shanghai）。 */
    final OverduePolicy policy = new OverduePolicy(
            Clock.fixed(Instant.parse("2026-09-26T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    final LocalDate today = LocalDate.of(2026, 9, 26);

    @Test
    void unfinishedTaskPastDueDateIsOverdue() {
        assertThat(policy.isOverdue(TaskStatus.TODO, today.minusDays(1))).isTrue();
        assertThat(policy.isOverdue(TaskStatus.IN_PROGRESS, today.minusDays(9))).isTrue();
        assertThat(policy.overdueDays(TaskStatus.IN_PROGRESS, today.minusDays(9))).isEqualTo(9);
    }

    @Test
    void dueTodayIsNotOverdue() {
        assertThat(policy.isOverdue(TaskStatus.TODO, today)).isFalse();
        assertThat(policy.overdueDays(TaskStatus.TODO, today)).isNull();
    }

    @Test
    void finishedTasksAreNeverOverdue() {
        assertThat(policy.isOverdue(TaskStatus.DONE, today.minusDays(30))).isFalse();
        assertThat(policy.isOverdue(TaskStatus.CANCELLED, today.minusDays(30))).isFalse();
        assertThat(policy.overdueDays(TaskStatus.DONE, today.minusDays(30))).isNull();
    }

    @Test
    void todayComesFromTheInjectedClock() {
        assertThat(policy.today()).isEqualTo(today);
    }
}
