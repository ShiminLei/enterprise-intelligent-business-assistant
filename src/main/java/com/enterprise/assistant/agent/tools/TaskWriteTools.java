package com.enterprise.assistant.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.enterprise.assistant.action.PendingAction;
import com.enterprise.assistant.action.PendingActionService;
import com.enterprise.assistant.agent.AgentEvent.PendingActionProposed;
import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.member.MemberService.AmbiguousMemberException;
import com.enterprise.assistant.project.ProjectNotFoundException;

/**
 * 写操作工具（FR-013、FR-014、FR-021）：只生成待确认操作，不修改数据；执行由用户在页面确认后触发
 * （research R4）。契约见 contracts/agent-tools.md「proposeCreateTask」「proposeUpdateTaskStatus」。
 */
@Component
public class TaskWriteTools implements AgentTools {

    private final PendingActionService actions;

    public TaskWriteTools(PendingActionService actions) {
        this.actions = actions;
    }

    @Tool(name = "proposeCreateTask",
            description = "为用户准备一个「创建任务」操作，交由用户在页面上确认后执行。缺少任何必填信息时，不要调用本工具，先向用户询问。")
    public String proposeCreateTask(
            @ToolParam(description = "项目编号，例如 P-001") String projectCode,
            @ToolParam(description = "任务标题，1 ~ 200 字") String title,
            @ToolParam(description = "负责人姓名") String assigneeName,
            @ToolParam(description = "计划截止日期，格式 YYYY-MM-DD，不早于今天") String dueDate,
            @ToolParam(description = "优先级：HIGH(高)、MEDIUM(中)、LOW(低)") String priority,
            ToolContext toolContext) {
        AgentRequestContext context = AgentRequestContext.from(toolContext);
        try {
            return proposed(context, actions.proposeCreateTask(context.member(), context.conversationId(),
                    projectCode, title, assigneeName, dueDate, priority));
        } catch (AmbiguousMemberException e) {
            return ToolResult.error(e.getMessage(), e.candidates());
        } catch (ProjectNotFoundException e) {
            return ToolResult.error(e.getMessage(), e.candidates());
        } catch (BusinessException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @Tool(name = "proposeUpdateTaskStatus", description = "为用户准备一个「修改任务状态」操作，交由用户确认后执行。")
    public String proposeUpdateTaskStatus(
            @ToolParam(description = "任务编号，例如 T-102") String taskCode,
            @ToolParam(description = "目标状态：TODO(待开始)、IN_PROGRESS(进行中)、DONE(已完成)、CANCELLED(已取消)")
            String targetStatus,
            ToolContext toolContext) {
        AgentRequestContext context = AgentRequestContext.from(toolContext);
        try {
            return proposed(context, actions.proposeUpdateStatus(context.member(), context.conversationId(),
                    taskCode, targetStatus));
        } catch (BusinessException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static String proposed(AgentRequestContext context, PendingAction action) {
        context.registerPendingAction(action.getId());
        context.sendAfterCurrentStep(new PendingActionProposed(action.getId(), action.getType().name(),
                action.getSummary(), action.getStatus().name()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingActionId", action.getId());
        data.put("summary", action.getSummary());
        data.put("message", "已生成待确认操作，请提醒用户在页面上确认；在用户确认前操作不会执行");
        return ToolResult.ok("已生成待确认操作，等待用户确认", data);
    }
}
