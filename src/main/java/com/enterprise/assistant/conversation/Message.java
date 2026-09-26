package com.enterprise.assistant.conversation;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Step> steps;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Citation> citations;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {
    }

    Message(Long conversationId, MessageRole role, String content, List<Step> steps, List<Citation> citations,
            MessageStatus status, Instant createdAt) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.steps = steps;
        this.citations = citations;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<Step> getSteps() {
        return steps == null ? List.of() : steps;
    }

    public List<Citation> getCitations() {
        return citations == null ? List.of() : citations;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
