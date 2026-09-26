package com.enterprise.assistant.member;

public enum MemberRole {
    PROJECT_MANAGER("项目经理"),
    TEAM_MEMBER("团队成员");

    private final String label;

    MemberRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
