package com.enterprise.assistant.security;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.enterprise.assistant.member.MemberRepository;

@Service
public class MemberUserDetailsService implements UserDetailsService {

    private final MemberRepository members;

    public MemberUserDetailsService(MemberRepository members) {
        this.members = members;
    }

    @Override
    public CurrentMember loadUserByUsername(String username) {
        return members.findByUsername(username)
                .map(CurrentMember::of)
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
