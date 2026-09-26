package com.enterprise.assistant.action;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingActionRepository extends JpaRepository<PendingAction, Long> {

    List<PendingAction> findByConversationIdAndStatus(Long conversationId, PendingActionStatus status);

    List<PendingAction> findByConversationIdOrderById(Long conversationId);

    List<PendingAction> findByMessageIdInOrderById(Collection<Long> messageIds);
}
