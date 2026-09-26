package com.enterprise.assistant.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.assistant.member.MemberRole;

@RestController
@RequestMapping("/api/me")
public class MeController {

    public record CurrentUser(long id, String username, String name, MemberRole role, String roleLabel) {
    }

    @GetMapping
    CurrentUser me(@AuthenticationPrincipal CurrentMember member) {
        return new CurrentUser(member.id(), member.getUsername(), member.name(), member.role(), member.role().label());
    }
}
