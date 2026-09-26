package com.enterprise.assistant.agent.tools;

import java.util.Map;

/** 工具名 → 页面显示的中文名（contracts/agent-tools.md「工具清单」）。 */
public final class ToolLabels {

    private static final Map<String, String> LABELS = Map.of(
            "findProjects", "查询项目",
            "queryTasks", "查询任务",
            "searchKnowledge", "检索知识库",
            "findMembers", "查询成员",
            "proposeCreateTask", "准备创建任务",
            "proposeUpdateTaskStatus", "准备修改任务状态");

    private ToolLabels() {
    }

    public static String labelOf(String toolName) {
        return LABELS.getOrDefault(toolName, toolName);
    }
}
