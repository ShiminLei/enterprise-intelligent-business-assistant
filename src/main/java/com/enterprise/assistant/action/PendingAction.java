package com.enterprise.assistant.action;

import java.time.Instant;
import java.util.Map;

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

/** 待确认的写操作（FR-021），同时作为写操作的审计记录（宪法 I）。 */
@Entity
@Table(name = "pending_action")
public class PendingAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PendingActionType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(nullable = false, length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PendingActionStatus status;

    @Column(length = 500)
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    protected PendingAction() {
    }

    PendingAction(Long conversationId, Long requestedBy, PendingActionType type, Map<String, Object> payload,
                  String summary, Instant now) {
        this.conversationId = conversationId;
        this.requestedBy = requestedBy;
        this.type = type;
        this.payload = payload;
        this.summary = summary;
        this.status = PendingActionStatus.PENDING;
        this.createdAt = now;
    }

    void resolve(PendingActionStatus status, String result, Long resolvedBy, Instant now) {
        this.status = status;
        this.result = result;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = now;
    }

    void linkToMessage(Long messageId) {
        this.messageId = messageId;
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public PendingActionType getType() {
        return type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public String getSummary() {
        return summary;
    }

    public PendingActionStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }
}
