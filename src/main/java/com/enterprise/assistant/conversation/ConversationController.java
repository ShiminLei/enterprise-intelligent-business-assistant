package com.enterprise.assistant.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.enterprise.assistant.action.PendingAction;
import com.enterprise.assistant.action.PendingActionController.PendingActionView;
import com.enterprise.assistant.action.PendingActionService;
import com.enterprise.assistant.agent.AgentEvent;
import com.enterprise.assistant.agent.AgentProperties;
import com.enterprise.assistant.agent.AgentService;
import com.enterprise.assistant.common.AsyncConfig;
import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.security.CurrentMember;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 会话与对话接口，契约见 contracts/openapi.yaml 与 contracts/chat-stream-events.md。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    public record ConversationSummary(long id, String title, Instant createdAt, Instant updatedAt) {
        static ConversationSummary of(Conversation c) {
            return new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record MessageView(long id, MessageRole role, String content, MessageStatus status, List<Step> steps,
                              List<Citation> citations, List<PendingActionView> pendingActions, Instant createdAt) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 2000) String content) {
    }

    private final ConversationService conversations;
    private final PendingActionService pendingActions;
    private final AgentService agent;
    private final AgentProperties properties;
    private final Executor executor;
    private final ObjectMapper json;
    /** 同一会话同一时间只允许一个处理中的请求。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ConversationController(ConversationService conversations, PendingActionService pendingActions,
                                  AgentService agent, AgentProperties properties,
                                  @Qualifier(AsyncConfig.AGENT_EXECUTOR) Executor executor, ObjectMapper json) {
        this.conversations = conversations;
        this.pendingActions = pendingActions;
        this.agent = agent;
        this.properties = properties;
        this.executor = executor;
        this.json = json;
    }

    @GetMapping
    List<ConversationSummary> list(@AuthenticationPrincipal CurrentMember member) {
        return conversations.listOwn(member).stream().map(ConversationSummary::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConversationSummary create(@AuthenticationPrincipal CurrentMember member) {
        return ConversationSummary.of(conversations.create(member));
    }

    @GetMapping("/{conversationId}/messages")
    List<MessageView> messages(@AuthenticationPrincipal CurrentMember member, @PathVariable long conversationId) {
        List<Message> messages = conversations.listMessages(member, conversationId);
        Map<Long, List<PendingActionView>> actionsByMessage = pendingActions
                .findByMessageIds(messages.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.groupingBy(PendingAction::getMessageId,
                        Collectors.mapping(PendingActionView::of, Collectors.toList())));
        return messages.stream()
                .map(m -> new MessageView(m.getId(), m.getRole(), m.getContent(), m.getStatus(), m.getSteps(),
                        m.getCitations(), actionsByMessage.getOrDefault(m.getId(), List.of()), m.getCreatedAt()))
                .toList();
    }

    @PostMapping(path = "/{conversationId}/messages")
    SseEmitter send(@AuthenticationPrincipal CurrentMember member, @PathVariable long conversationId,
                    @Valid @RequestBody SendMessageRequest request) {
        conversations.getOwn(member, conversationId);
        if (!inFlight.add(conversationId)) {
            throw BusinessException.conflict("该会话正在处理上一条消息，请稍候");
        }

        List<Long> expiredActionIds;
        try {
            // 用户发出新消息时，该会话中尚未确认的操作全部作废，避免被误执行（spec 边界情况）
            expiredActionIds = pendingActions.expireForConversation(conversationId);
        } catch (RuntimeException e) {
            inFlight.remove(conversationId);
            throw e;
        }
        SseEmitter emitter = new SseEmitter(properties.timeout().plusSeconds(10).toMillis());
        SseEventSink sink = new SseEventSink(emitter, json);
        CompletableFuture
                .runAsync(() -> agent.handle(member, conversationId, request.content().strip(), expiredActionIds, sink),
                        executor)
                .orTimeout(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, failure) -> {
                    inFlight.remove(conversationId);
                    if (failure != null) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        if (cause instanceof TimeoutException) {
                            log.warn("agent request timed out conversationId={}", conversationId);
                            sink.send(new AgentEvent.Error("TIMEOUT", "处理超时，请稍后重试或把问题拆小"));
                        } else {
                            log.error("agent request crashed conversationId={}", conversationId, cause);
                            sink.send(new AgentEvent.Error("INTERNAL_ERROR", "系统内部错误，请稍后重试"));
                        }
                        sink.send(new AgentEvent.Done());
                    }
                    sink.complete();
                });
        return emitter;
    }
}
