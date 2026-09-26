package com.enterprise.assistant.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;

import com.enterprise.assistant.conversation.Citation;
import com.enterprise.assistant.conversation.Step;
import com.enterprise.assistant.security.CurrentMember;

/**
 * 一次对话请求的上下文：当前成员、会话、事件出口，以及本次请求收集的步骤、引用与待确认操作。
 * 通过 Spring AI 的 ToolContext 传给工具，模型无法伪造当前用户（research R9）。
 */
public final class AgentRequestContext {

    public static final String KEY = "agentRequestContext";

    private final CurrentMember member;
    private final long conversationId;
    private final AgentEventSink sink;
    private final List<Step> steps = new ArrayList<>();
    private final List<Citation> citations = new ArrayList<>();
    private final Map<String, Integer> citationKeys = new HashMap<>();
    private final List<Long> pendingActionIds = new ArrayList<>();
    private final List<AgentEvent> deferred = new ArrayList<>();
    private boolean stepInProgress;

    public AgentRequestContext(CurrentMember member, long conversationId, AgentEventSink sink) {
        this.member = member;
        this.conversationId = conversationId;
        this.sink = sink;
    }

    public static AgentRequestContext from(ToolContext toolContext) {
        Object context = toolContext == null ? null : toolContext.getContext().get(KEY);
        if (!(context instanceof AgentRequestContext requestContext)) {
            throw new IllegalStateException("工具调用缺少请求上下文");
        }
        return requestContext;
    }

    Map<String, Object> asToolContext() {
        return Map.of(KEY, this);
    }

    public CurrentMember member() {
        return member;
    }

    public long conversationId() {
        return conversationId;
    }

    public void send(AgentEvent event) {
        sink.send(event);
    }

    /**
     * 工具执行中产生的事件（例如 pending_action）排在该步骤的 step_finished 之后推送
     * （contracts/chat-stream-events.md）；不在步骤中时立即推送。
     */
    public synchronized void sendAfterCurrentStep(AgentEvent event) {
        if (stepInProgress) {
            deferred.add(event);
        } else {
            sink.send(event);
        }
    }

    synchronized void beginStep() {
        stepInProgress = true;
    }

    synchronized void endStep() {
        stepInProgress = false;
        deferred.forEach(sink::send);
        deferred.clear();
    }

    /**
     * 登记一条引用，返回其编号（本次请求内从 1 递增），模型在正文中以 [编号] 引用。
     * 同一个知识片段（相同 key）重复登记时返回原编号。
     */
    public synchronized int registerCitation(String key, String documentName, String section, String excerpt) {
        Integer existing = citationKeys.get(key);
        if (existing != null) {
            return existing;
        }
        int index = citations.size() + 1;
        citations.add(new Citation(index, documentName, section, excerpt));
        citationKeys.put(key, index);
        return index;
    }

    public synchronized void registerPendingAction(long pendingActionId) {
        pendingActionIds.add(pendingActionId);
    }

    synchronized void addStep(Step step) {
        steps.add(step);
    }

    public synchronized List<Step> steps() {
        return List.copyOf(steps);
    }

    public synchronized List<Citation> citations() {
        return List.copyOf(citations);
    }

    public synchronized List<Long> pendingActionIds() {
        return List.copyOf(pendingActionIds);
    }
}
