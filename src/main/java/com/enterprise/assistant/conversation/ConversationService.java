package com.enterprise.assistant.conversation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.security.CurrentMember;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, MessageRepository messages, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.clock = clock;
    }

    @Transactional
    public Conversation create(CurrentMember owner) {
        return conversations.save(new Conversation(owner.id(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<Conversation> listOwn(CurrentMember owner) {
        return conversations.findByOwnerIdOrderByUpdatedAtDescIdDesc(owner.id());
    }

    /** 他人的会话按「不存在」处理，不暴露其存在（contracts/openapi.yaml 404）。 */
    @Transactional(readOnly = true)
    public Conversation getOwn(CurrentMember owner, long conversationId) {
        return conversations.findByIdAndOwnerId(conversationId, owner.id())
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
    }

    @Transactional(readOnly = true)
    public List<Message> listMessages(CurrentMember owner, long conversationId) {
        getOwn(owner, conversationId);
        return messages.findByConversationIdOrderById(conversationId);
    }

    @Transactional
    public Message saveUserMessage(long conversationId, String content) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> BusinessException.notFound("会话不存在"));
        conversation.touch(content, clock.instant());
        return messages.save(new Message(conversationId, MessageRole.USER, content, null, null,
                MessageStatus.COMPLETED, clock.instant()));
    }

    @Transactional
    public Message saveAssistantMessage(long conversationId, String content, List<Step> steps,
                                        List<Citation> citations, MessageStatus status) {
        conversations.findById(conversationId).ifPresent(c -> c.touch(null, clock.instant()));
        return messages.save(new Message(conversationId, MessageRole.ASSISTANT, content, List.copyOf(steps),
                List.copyOf(citations), status, clock.instant()));
    }

    /** 最近 size 条用户消息与助手回答（不含失败消息），按时间正序，作为模型的对话历史（research R8）。 */
    @Transactional(readOnly = true)
    public List<Message> recentHistory(long conversationId, int size) {
        List<Message> latest = new ArrayList<>(messages.findByConversationIdAndStatusNotOrderByIdDesc(
                conversationId, MessageStatus.FAILED, Limit.of(size)));
        Collections.reverse(latest);
        return latest;
    }
}
