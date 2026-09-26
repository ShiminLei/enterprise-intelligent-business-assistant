package com.enterprise.assistant.task;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.member.Member;
import com.enterprise.assistant.member.MemberRepository;
import com.enterprise.assistant.member.MemberService;
import com.enterprise.assistant.project.Project;
import com.enterprise.assistant.project.ProjectService;
import com.enterprise.assistant.security.CurrentMember;

/**
 * 任务写操作（FR-013、FR-014）：所有校验在写入前完成，因此业务异常不回滚外层事务
 * （待确认操作在同一事务中把执行失败记为 FAILED）。
 */
@Service
public class TaskCommandService {

    static final int MAX_TITLE_LENGTH = 200;

    /** 已解析的创建任务命令。 */
    public record CreateTask(String projectCode, String title, long assigneeId, LocalDate dueDate,
                             TaskPriority priority) {
    }

    /** 创建任务前的完整校验结果，含展示用信息。 */
    public record PreparedCreate(CreateTask command, Project project, Member assignee) {
    }

    /** 修改状态前的完整校验结果。 */
    public record PreparedUpdate(Task task, TaskStatus from, TaskStatus to) {
    }

    private final TaskRepository tasks;
    private final ProjectService projects;
    private final MemberService memberService;
    private final MemberRepository members;
    private final TaskPermissionPolicy permissions;
    private final OverduePolicy overduePolicy;
    private final Clock clock;

    public TaskCommandService(TaskRepository tasks, ProjectService projects, MemberService memberService,
                              MemberRepository members, TaskPermissionPolicy permissions, OverduePolicy overduePolicy,
                              Clock clock) {
        this.tasks = tasks;
        this.projects = projects;
        this.memberService = memberService;
        this.members = members;
        this.permissions = permissions;
        this.overduePolicy = overduePolicy;
        this.clock = clock;
    }

    /**
     * 按「参数格式 → 项目存在 → 当前用户是该项目经理 → 负责人唯一存在 → 截止日期」的顺序校验创建请求
     * （contracts/agent-tools.md「proposeCreateTask」），不写入任何数据。
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public PreparedCreate prepareCreate(CurrentMember actor, String projectCode, String title, String assigneeName,
                                        String dueDate, String priority) {
        String normalizedTitle = validateTitle(title);
        LocalDate due = parseDate(dueDate);
        TaskPriority taskPriority = parsePriority(priority);
        if (assigneeName == null || assigneeName.isBlank()) {
            throw BusinessException.badRequest("请提供任务负责人");
        }
        Project project = projects.getByCode(projectCode);
        permissions.checkCreateTask(actor, project);
        Member assignee = memberService.resolve(actor, assigneeName);
        validateDueDate(due);
        return new PreparedCreate(new CreateTask(project.getCode(), normalizedTitle, assignee.getId(), due, taskPriority),
                project, assignee);
    }

    /** 按「任务存在 → 权限 → 状态流转」的顺序校验修改状态请求，不写入任何数据。 */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public PreparedUpdate prepareUpdate(CurrentMember actor, String taskCode, String targetStatus) {
        TaskStatus target = parseStatus(targetStatus);
        Task task = findTask(taskCode);
        permissions.checkUpdateStatus(actor, task);
        String error = task.getStatus().transitionError(target);
        if (error != null) {
            throw BusinessException.badRequest("任务 " + task.getCode() + " " + error);
        }
        return new PreparedUpdate(task, task.getStatus(), target);
    }

    /** 创建任务；执行前重新校验权限、标题和截止日期。 */
    @Transactional(noRollbackFor = BusinessException.class)
    public Task createTask(CurrentMember actor, CreateTask command) {
        String title = validateTitle(command.title());
        Project project = projects.getByCode(command.projectCode());
        permissions.checkCreateTask(actor, project);
        Member assignee = members.findById(command.assigneeId())
                .orElseThrow(() -> BusinessException.notFound("负责人不存在"));
        validateDueDate(command.dueDate());
        String code = "T-" + (tasks.maxTaskNumber() + 1);
        return tasks.save(Task.create(code, project, title, assignee, command.priority(), command.dueDate(),
                clock.instant()));
    }

    /** 修改任务状态；执行前重新校验权限和流转规则（数据可能已被他人修改）。 */
    @Transactional(noRollbackFor = BusinessException.class)
    public Task updateStatus(CurrentMember actor, String taskCode, TaskStatus target) {
        Task task = findTask(taskCode);
        permissions.checkUpdateStatus(actor, task);
        String error = task.getStatus().transitionError(target);
        if (error != null) {
            throw BusinessException.badRequest("任务 " + task.getCode() + " " + error);
        }
        task.changeStatus(target, overduePolicy.today(), clock.instant());
        return task;
    }

    private Task findTask(String taskCode) {
        return tasks.findByCodeIgnoreCase(taskCode == null ? "" : taskCode.strip())
                .orElseThrow(() -> BusinessException.notFound("未找到任务 " + taskCode));
    }

    private static String validateTitle(String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isEmpty()) {
            throw BusinessException.badRequest("任务标题不能为空");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw BusinessException.badRequest("任务标题不能超过 " + MAX_TITLE_LENGTH + " 个字");
        }
        return normalized;
    }

    private void validateDueDate(LocalDate due) {
        if (due.isBefore(overduePolicy.today())) {
            throw BusinessException.badRequest("截止日期 " + due + " 早于今天（" + overduePolicy.today() + "），请重新指定");
        }
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text == null ? "" : text.strip());
        } catch (DateTimeParseException e) {
            throw BusinessException.badRequest("截止日期格式不正确：" + text + "，请使用 YYYY-MM-DD 格式的日期");
        }
    }

    private static TaskPriority parsePriority(String text) {
        try {
            return TaskPriority.valueOf(text == null ? "" : text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("无效的优先级 " + text + "，可选：HIGH(高)、MEDIUM(中)、LOW(低)");
        }
    }

    private static TaskStatus parseStatus(String text) {
        try {
            return TaskStatus.valueOf(text == null ? "" : text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("无效的状态 " + text
                    + "，可选：TODO(待开始)、IN_PROGRESS(进行中)、DONE(已完成)、CANCELLED(已取消)");
        }
    }
}
