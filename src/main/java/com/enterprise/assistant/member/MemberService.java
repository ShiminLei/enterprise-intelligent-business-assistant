package com.enterprise.assistant.member;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;
import com.enterprise.assistant.security.CurrentMember;

@Service
public class MemberService {

    public record MemberView(String name, String username, MemberRole role, String roleLabel) {
        static MemberView of(Member m) {
            return new MemberView(m.getName(), m.getUsername(), m.getRole(), m.getRole().label());
        }
    }

    /** 按姓名匹配到多个成员；附带候选姓名让用户确认（data-model.md「member」规则）。 */
    public static class AmbiguousMemberException extends BusinessException {
        private final List<String> candidates;

        public AmbiguousMemberException(String name, List<String> candidates) {
            super(ErrorCode.BAD_REQUEST, "姓名「" + name + "」匹配到多个成员，请确认是哪一位");
            this.candidates = List.copyOf(candidates);
        }

        public List<String> candidates() {
            return candidates;
        }
    }

    private static final Set<String> ME = Set.of("me", "ME", "我", "我自己");

    private final MemberRepository members;

    public MemberService(MemberRepository members) {
        this.members = members;
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public List<MemberView> findByName(String name) {
        return members.findByNameContainingOrderById(name == null ? "" : name.strip()).stream()
                .map(MemberView::of).toList();
    }

    /**
     * 把「me」解析为当前成员，把姓名解析为唯一成员：姓名完全一致优先，其次唯一的部分匹配。
     *
     * @throws AmbiguousMemberException 匹配到多个成员
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public Member resolve(CurrentMember current, String nameOrMe) {
        String name = nameOrMe == null ? "" : nameOrMe.strip();
        if (ME.contains(name)) {
            return members.findById(current.id()).orElseThrow();
        }
        if (name.isEmpty()) {
            throw BusinessException.badRequest("请提供成员姓名");
        }
        List<Member> matched = members.findByNameContainingOrderById(name);
        List<Member> exact = matched.stream().filter(m -> m.getName().equals(name)).toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (matched.size() == 1) {
            return matched.get(0);
        }
        if (matched.isEmpty()) {
            throw BusinessException.notFound("未找到成员「" + name + "」");
        }
        throw new AmbiguousMemberException(name, matched.stream().map(Member::getName).toList());
    }
}
