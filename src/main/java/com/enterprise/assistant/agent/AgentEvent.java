package com.enterprise.assistant.agent;

import java.util.List;

import com.enterprise.assistant.conversation.Citation;
import com.enterprise.assistant.conversation.MessageStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 对话事件流中的事件，字段与 contracts/chat-stream-events.md 一致；name() 为 SSE 的 event 名。
 */
public sealed interface AgentEvent {

    @JsonIgnore
    String name();

    record MessageAccepted(long userMessageId, List<Long> expiredActionIds) implements AgentEvent {
        public String name() {
            return "message_accepted";
        }
    }

    record StepStarted(int seq, String tool, String label) implements AgentEvent {
        public String name() {
            return "step_started";
        }
    }

    record StepFinished(int seq, boolean success, String resultSummary, long durationMs) implements AgentEvent {
        public String name() {
            return "step_finished";
        }
    }

    record PendingActionProposed(long id, String type, String summary, String status) implements AgentEvent {
        public String name() {
            return "pending_action";
        }
    }

    record Answer(long assistantMessageId, String content, MessageStatus status, List<Citation> citations)
            implements AgentEvent {
        public String name() {
            return "answer";
        }
    }

    record Error(String code, String message) implements AgentEvent {
        public String name() {
            return "error";
        }
    }

    record Done() implements AgentEvent {
        public String name() {
            return "done";
        }
    }
}
