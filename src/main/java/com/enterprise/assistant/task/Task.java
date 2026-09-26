package com.enterprise.assistant.task;

import java.time.Instant;
import java.time.LocalDate;

import com.enterprise.assistant.member.Member;
import com.enterprise.assistant.project.Project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignee_id", nullable = false)
    private Member assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Task() {
    }

    static Task create(String code, Project project, String title, Member assignee, TaskPriority priority,
                       LocalDate dueDate, Instant now) {
        Task task = new Task();
        task.code = code;
        task.project = project;
        task.title = title;
        task.assignee = assignee;
        task.priority = priority;
        task.status = TaskStatus.TODO;
        task.dueDate = dueDate;
        task.createdAt = now;
        task.updatedAt = now;
        return task;
    }

    /** 变更状态；变为已完成时记录完成日期。调用方负责先校验流转规则与权限。 */
    void changeStatus(TaskStatus target, LocalDate today, Instant now) {
        this.status = target;
        this.completedDate = target == TaskStatus.DONE ? today : null;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Project getProject() {
        return project;
    }

    public String getTitle() {
        return title;
    }

    public Member getAssignee() {
        return assignee;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getCompletedDate() {
        return completedDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
