package com.enterprise.assistant.conversation;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderById(Long conversationId);

    List<Message> findByConversationIdAndStatusNotOrderByIdDesc(Long conversationId, MessageStatus excluded, Limit limit);
}
