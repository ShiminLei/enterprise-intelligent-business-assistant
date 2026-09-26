package com.enterprise.assistant.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.enterprise.assistant.member.Member;
import com.enterprise.assistant.member.MemberRole;

/**
 * 登录主体。Service 层的权限校验显式接收它作为参数，不从线程上下文隐式获取（research R9）。
 */
public final class CurrentMember implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final String passwordHash;
    private final String name;
    private final MemberRole role;

    public CurrentMember(long id, String username, String passwordHash, String name, MemberRole role) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
    }

    public static CurrentMember of(Member member) {
        return new CurrentMember(member.getId(), member.getUsername(), member.getPasswordHash(),
                member.getName(), member.getRole());
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public MemberRole role() {
        return role;
    }

    public boolean isProjectManager() {
        return role == MemberRole.PROJECT_MANAGER;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String toString() {
        return "CurrentMember[" + id + ", " + username + ", " + role + "]";
    }
}
