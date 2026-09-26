package com.enterprise.assistant.project;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.task.OverduePolicy;
import com.enterprise.assistant.task.Task;
import com.enterprise.assistant.task.TaskRepository;

@Service
public class ProjectService {

    public record ProjectSummary(String code, String name, String manager, LocalDate plannedStartDate,
                                 LocalDate plannedEndDate, ProjectStatus status, String statusLabel,
                                 int taskCount, int overdueTaskCount) {
    }

    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final OverduePolicy overduePolicy;

    public ProjectService(ProjectRepository projects, TaskRepository tasks, OverduePolicy overduePolicy) {
        this.projects = projects;
        this.tasks = tasks;
        this.overduePolicy = overduePolicy;
    }

    /**
     * 按名称关键字或编号查询项目（忽略空格与大小写）；关键字为空时返回全部项目。
     *
     * @throws ProjectNotFoundException 没有匹配项时，附带全部项目名称作为候选
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public List<ProjectSummary> search(String keyword) {
        List<Project> all = projects.findAllByOrderByCode();
        List<Project> matched = keyword == null || keyword.isBlank() ? all : all.stream()
                .filter(p -> normalize(p.getCode()).equals(normalize(keyword))
                        || normalize(p.getName()).contains(normalize(keyword)))
                .toList();
        if (matched.isEmpty()) {
            throw new ProjectNotFoundException("未找到匹配的项目", all.stream().map(Project::getName).toList());
        }
        return matched.stream().map(this::summarize).toList();
    }

    /** 按编号精确查找项目。 */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public Project getByCode(String code) {
        return projects.findByCodeIgnoreCase(code == null ? "" : code.strip())
                .orElseThrow(() -> new ProjectNotFoundException("未找到项目 " + code,
                        projects.findAllByOrderByCode().stream().map(p -> p.getCode() + " " + p.getName()).toList()));
    }

    private ProjectSummary summarize(Project p) {
        List<Task> projectTasks = tasks.findByProjectId(p.getId());
        int overdue = (int) projectTasks.stream()
                .filter(t -> overduePolicy.isOverdue(t.getStatus(), t.getDueDate()))
                .count();
        return new ProjectSummary(p.getCode(), p.getName(), p.getManager().getName(), p.getPlannedStartDate(),
                p.getPlannedEndDate(), p.getStatus(), p.getStatus().label(), projectTasks.size(), overdue);
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
