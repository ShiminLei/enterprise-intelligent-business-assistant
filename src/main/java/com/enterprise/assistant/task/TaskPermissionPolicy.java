package com.enterprise.assistant.task;

import org.springframework.stereotype.Component;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.project.Project;
import com.enterprise.assistant.security.CurrentMember;

/**
 * 写操作权限（FR-014a）：项目经理只能在自己负责的项目中创建任务、修改任务状态；
 * 团队成员不能创建任务，只能修改分配给自己的任务状态。所有人都可以查询。
 */
@Component
public class TaskPermissionPolicy {

    public void checkCreateTask(CurrentMember actor, Project project) {
        if (!actor.isProjectManager()) {
            throw BusinessException.forbidden("团队成员不能创建任务，请联系项目负责人");
        }
        if (project.getManager().getId() != actor.id()) {
            throw BusinessException.forbidden("只有" + project.getName() + "的负责人" + project.getManager().getName()
                    + "可以在该项目中创建任务");
        }
    }

    public void checkUpdateStatus(CurrentMember actor, Task task) {
        if (actor.isProjectManager()) {
            if (task.getProject().getManager().getId() != actor.id()) {
                throw BusinessException.forbidden("只有" + task.getProject().getName() + "的负责人"
                        + task.getProject().getManager().getName() + "可以修改该项目任务的状态");
            }
            return;
        }
        if (task.getAssignee().getId() != actor.id()) {
            throw BusinessException.forbidden("团队成员只能修改分配给自己的任务状态，任务 " + task.getCode() + " 的负责人是"
                    + task.getAssignee().getName());
        }
    }
}
