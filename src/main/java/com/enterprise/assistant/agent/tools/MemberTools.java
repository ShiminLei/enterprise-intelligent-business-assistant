package com.enterprise.assistant.agent.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.enterprise.assistant.member.MemberService;
import com.enterprise.assistant.member.MemberService.MemberView;

/** 查询成员工具，契约见 contracts/agent-tools.md「findMembers」。 */
@Component
public class MemberTools implements AgentTools {

    private final MemberService members;

    public MemberTools(MemberService members) {
        this.members = members;
    }

    @Tool(name = "findMembers", description = "按姓名查询成员，用于创建任务前确认负责人。")
    public String findMembers(@ToolParam(description = "姓名或部分姓名") String name) {
        List<MemberView> found = members.findByName(name);
        if (found.isEmpty()) {
            return ToolResult.error("未找到姓名包含「" + name + "」的成员");
        }
        return ToolResult.ok("找到 " + found.size() + " 个成员", found);
    }
}
