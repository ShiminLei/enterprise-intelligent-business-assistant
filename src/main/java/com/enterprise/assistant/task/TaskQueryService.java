package com.enterprise.assistant.task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.member.Member;
import com.enterprise.assistant.member.MemberService;
import com.enterprise.assistant.project.Project;
import com.enterprise.assistant.project.ProjectService;
import com.enterprise.assistant.security.CurrentMember;

import jakarta.persistence.criteria.Predicate;

@Service
public class TaskQueryService {

    /**
     * @param projectCode 项目编号，可为空
     * @param statuses    状态列表，可为空
     * @param assignee    负责人姓名或「me」，可为空
     * @param overdueOnly 只返回延期任务
     */
    public record TaskQuery(String projectCode, List<TaskStatus> statuses, String assignee, boolean overdueOnly) {
    }

    public record TaskView(String code, String title, String projectCode, String projectName, String assignee,
                           TaskPriority priority, String priorityLabel, TaskStatus status, String statusLabel,
                           LocalDate dueDate, Long overdueDays) {
    }

    private final TaskRepository tasks;
    private final ProjectService projects;
    private final MemberService members;
    private final OverduePolicy overduePolicy;

    public TaskQueryService(TaskRepository tasks, ProjectService projects, MemberService members,
                            OverduePolicy overduePolicy) {
        this.tasks = tasks;
        this.projects = projects;
        this.members = members;
        this.overduePolicy = overduePolicy;
    }

    /** 按条件组合查询任务，结果按计划截止日期升序（FR-012、FR-016）。 */
    @Transactional(readOnly = true)
    public List<TaskView> query(CurrentMember current, TaskQuery query) {
        Project project = isBlank(query.projectCode()) ? null : projects.getByCode(query.projectCode());
        Member assignee = isBlank(query.assignee()) ? null : members.resolve(current, query.assignee());
        LocalDate today = overduePolicy.today();

        Specification<Task> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (project != null) {
                predicates.add(cb.equal(root.get("project").get("id"), project.getId()));
            }
            if (query.statuses() != null && !query.statuses().isEmpty()) {
                predicates.add(root.get("status").in(query.statuses()));
            }
            if (assignee != null) {
                predicates.add(cb.equal(root.get("assignee").get("id"), assignee.getId()));
            }
            if (query.overdueOnly()) {
                predicates.add(root.get("status").in(TaskStatus.UNFINISHED));
                predicates.add(cb.lessThan(root.get("dueDate"), today));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        return tasks.findAll(spec).stream()
                .sorted(Comparator.comparing(Task::getDueDate).thenComparing(Task::getCode))
                .map(this::view)
                .toList();
    }

    TaskView view(Task t) {
        return new TaskView(t.getCode(), t.getTitle(), t.getProject().getCode(), t.getProject().getName(),
                t.getAssignee().getName(), t.getPriority(), t.getPriority().label(), t.getStatus(),
                t.getStatus().label(), t.getDueDate(), overduePolicy.overdueDays(t.getStatus(), t.getDueDate()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
