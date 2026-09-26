package com.enterprise.assistant.task;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

/**
 * 延期判定（FR-016）：状态为待开始或进行中，且今天已超过计划截止日期。
 * 「今天」取自注入的 Clock（research R14）。
 */
@Component
public class OverduePolicy {

    private final Clock clock;

    public OverduePolicy(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public boolean isOverdue(TaskStatus status, LocalDate dueDate) {
        return status.isUnfinished() && dueDate.isBefore(today());
    }

    /** 延期天数；未延期返回 null。 */
    public Long overdueDays(TaskStatus status, LocalDate dueDate) {
        return isOverdue(status, dueDate) ? ChronoUnit.DAYS.between(dueDate, today()) : null;
    }
}
