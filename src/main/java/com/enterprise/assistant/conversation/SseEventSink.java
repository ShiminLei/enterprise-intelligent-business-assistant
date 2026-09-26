package com.enterprise.assistant.conversation;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.enterprise.assistant.agent.AgentEvent;
import com.enterprise.assistant.agent.AgentEventSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 把 Agent 事件写成 SSE；流关闭后（完成、超时或客户端断开）静默丢弃后续事件。 */
final class SseEventSink implements AgentEventSink {

    private static final Logger log = LoggerFactory.getLogger(SseEventSink.class);

    private final SseEmitter emitter;
    private final ObjectMapper json;
    private boolean closed;

    SseEventSink(SseEmitter emitter, ObjectMapper json) {
        this.emitter = emitter;
        this.json = json;
        emitter.onCompletion(this::markClosed);
        emitter.onError(e -> markClosed());
    }

    @Override
    public synchronized void send(AgentEvent event) {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(json.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE client gone, dropping event {}", event.name());
            closed = true;
        }
    }

    synchronized void complete() {
        if (!closed) {
            closed = true;
            emitter.complete();
        }
    }

    private synchronized void markClosed() {
        closed = true;
    }
}
