package com.enterprise.assistant.agent;

import static com.enterprise.assistant.support.SseTestSupport.awaitEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.enterprise.assistant.common.RequestIdFilter;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** 宪法 IV：模型与工具调用的日志字段齐全，同一请求共用一个 requestId；宪法 I：日志中没有密钥与密码。 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AgentLoggingTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void attach() {
        chatModel.reset();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
    }

    @Test
    void modelAndToolCallsAreLoggedWithOneRequestIdAndNoSecrets() throws Exception {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        long id = conversations.create(wang).getId();
        chatModel.thenCallTool("queryTasks", "{\"projectCode\":\"P-001\",\"overdueOnly\":true}")
                .thenAnswer("项目A 有 3 个延期任务。");

        var result = mvc.perform(post("/api/conversations/{id}/messages", id)
                        .with(user(wang)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"项目A有哪些延期任务？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        awaitEvents(result, Duration.ofSeconds(10));
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);

        List<ILoggingEvent> modelCalls = messagesStartingWith("model call");
        List<ILoggingEvent> toolCalls = messagesStartingWith("tool call tool=");
        assertThat(modelCalls).hasSize(2).allSatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("model=").contains("durationMs=").contains("promptTokens=").contains("completionTokens="));
        assertThat(toolCalls).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("tool=queryTasks").contains("durationMs=").contains("success=true"));

        Set<String> requestIds = Stream.concat(modelCalls.stream(), toolCalls.stream())
                .map(e -> e.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY))
                .collect(Collectors.toSet());
        assertThat(requestIds).containsExactly(requestId);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("test-key") || m.contains("demo123") || m.contains("$2a$"));
    }

    List<ILoggingEvent> messagesStartingWith(String prefix) {
        return appender.list.stream().filter(e -> e.getFormattedMessage().startsWith(prefix)).toList();
    }
}
