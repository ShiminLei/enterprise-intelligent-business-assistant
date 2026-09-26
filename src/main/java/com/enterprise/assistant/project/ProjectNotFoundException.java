package com.enterprise.assistant.project;

import java.util.List;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;

/** 找不到匹配的项目；附带全部项目名称供用户选择（验收场景 US1-3）。 */
public class ProjectNotFoundException extends BusinessException {

    private final List<String> candidates;

    public ProjectNotFoundException(String message, List<String> candidates) {
        super(ErrorCode.NOT_FOUND, message);
        this.candidates = List.copyOf(candidates);
    }

    public List<String> candidates() {
        return candidates;
    }
}
