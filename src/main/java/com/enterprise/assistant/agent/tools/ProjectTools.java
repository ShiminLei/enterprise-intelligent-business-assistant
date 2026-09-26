package com.enterprise.assistant.agent.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.enterprise.assistant.project.ProjectNotFoundException;
import com.enterprise.assistant.project.ProjectService;
import com.enterprise.assistant.project.ProjectService.ProjectSummary;

/** 查询项目工具（FR-011），契约见 contracts/agent-tools.md「findProjects」。 */
@Component
public class ProjectTools implements AgentTools {

    private final ProjectService projects;

    public ProjectTools(ProjectService projects) {
        this.projects = projects;
    }

    @Tool(name = "findProjects",
            description = "按名称关键字或编号查询项目；不传参数时返回全部项目。用于确认项目是否存在、获取项目编号、负责人和计划时间。")
    public String findProjects(
            @ToolParam(required = false, description = "项目名称关键字或编号，例如「项目A」「P-001」") String keyword) {
        try {
            List<ProjectSummary> found = projects.search(keyword);
            String summary = found.size() == 1
                    ? "找到项目：" + found.get(0).name()
                    : "找到 " + found.size() + " 个项目";
            return ToolResult.ok(summary, found);
        } catch (ProjectNotFoundException e) {
            return ToolResult.error(e.getMessage(), e.candidates());
        }
    }
}
