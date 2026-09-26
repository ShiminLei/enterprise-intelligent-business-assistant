package com.enterprise.assistant.conversation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId);

    Optional<Conversation> findByIdAndOwnerId(Long id, Long ownerId);
}
