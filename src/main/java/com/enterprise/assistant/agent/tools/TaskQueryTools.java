package com.enterprise.assistant.agent.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.member.MemberService.AmbiguousMemberException;
import com.enterprise.assistant.project.ProjectNotFoundException;
import com.enterprise.assistant.task.TaskQueryService;
import com.enterprise.assistant.task.TaskQueryService.TaskQuery;
import com.enterprise.assistant.task.TaskQueryService.TaskView;
import com.enterprise.assistant.task.TaskStatus;

/** 查询任务工具（FR-012、FR-016），契约见 contracts/agent-tools.md「queryTasks」。 */
@Component
public class TaskQueryTools implements AgentTools {

    private final TaskQueryService tasks;

    public TaskQueryTools(TaskQueryService tasks) {
        this.tasks = tasks;
    }

    @Tool(name = "queryTasks",
            description = "按条件查询任务。「我的任务」请把 assignee 设为 me。「延期任务」请设 overdueOnly=true。")
    public String queryTasks(
            @ToolParam(required = false, description = "项目编号，例如 P-001") String projectCode,
            @ToolParam(required = false, description = "状态列表，可选值：TODO(待开始)、IN_PROGRESS(进行中)、DONE(已完成)、CANCELLED(已取消)")
            List<String> status,
            @ToolParam(required = false, description = "负责人姓名，或 me 表示当前用户") String assignee,
            @ToolParam(required = false, description = "只返回延期任务，默认 false") Boolean overdueOnly,
            ToolContext toolContext) {
        List<TaskStatus> statuses = new ArrayList<>();
        for (String s : status == null ? List.<String>of() : status) {
            try {
                statuses.add(TaskStatus.valueOf(s.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("无效的状态值 " + s + "，可选：" + Arrays.stream(TaskStatus.values())
                        .map(v -> v.name() + "(" + v.label() + ")").collect(Collectors.joining("、")));
            }
        }
        boolean overdue = Boolean.TRUE.equals(overdueOnly);
        try {
            List<TaskView> found = tasks.query(AgentRequestContext.from(toolContext).member(),
                    new TaskQuery(projectCode, statuses, assignee, overdue));
            String summary = "找到 " + found.size() + " 个" + (overdue ? "延期任务" : "任务");
            return ToolResult.okList(summary, found);
        } catch (AmbiguousMemberException e) {
            return ToolResult.error(e.getMessage(), e.candidates());
        } catch (ProjectNotFoundException e) {
            return ToolResult.error(e.getMessage(), e.candidates());
        } catch (BusinessException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
