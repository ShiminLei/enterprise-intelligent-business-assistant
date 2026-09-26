package com.enterprise.assistant.action;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.assistant.security.CurrentMember;

/** 确认 / 取消待确认操作（FR-021），执行不经过大模型。契约见 contracts/openapi.yaml。 */
@RestController
@RequestMapping("/api/pending-actions")
public class PendingActionController {

    public record PendingActionView(long id, PendingActionType type, String summary, PendingActionStatus status,
                                    String result) {
        public static PendingActionView of(PendingAction a) {
            return new PendingActionView(a.getId(), a.getType(), a.getSummary(), a.getStatus(), a.getResult());
        }
    }

    private final PendingActionService actions;

    public PendingActionController(PendingActionService actions) {
        this.actions = actions;
    }

    @PostMapping("/{actionId}/confirm")
    PendingActionView confirm(@AuthenticationPrincipal CurrentMember member, @PathVariable long actionId) {
        return PendingActionView.of(actions.confirm(member, actionId));
    }

    @PostMapping("/{actionId}/cancel")
    PendingActionView cancel(@AuthenticationPrincipal CurrentMember member, @PathVariable long actionId) {
        return PendingActionView.of(actions.cancel(member, actionId));
    }
}
