package com.enterprise.assistant.conversation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation")
public class Conversation {

    static final int TITLE_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(length = 100)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    Conversation(Long ownerId, Instant now) {
        this.ownerId = ownerId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 首条用户消息的前 30 个字作为标题；之后的消息不再改变标题。 */
    void touch(String userMessage, Instant now) {
        if (title == null && userMessage != null) {
            String trimmed = userMessage.strip();
            title = trimmed.length() <= TITLE_LENGTH ? trimmed : trimmed.substring(0, TITLE_LENGTH);
        }
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
