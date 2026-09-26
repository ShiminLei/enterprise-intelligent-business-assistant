package com.enterprise.assistant.api;

import static com.enterprise.assistant.support.SseTestSupport.awaitEvents;
import static com.enterprise.assistant.support.SseTestSupport.names;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.enterprise.assistant.support.SseTestSupport.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// SSE 由后台线程写入响应，关闭 MockMvc 的请求打印，避免它与后台线程同时读写响应头
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ConversationApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MemberUserDetailsService users;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void resetModel() {
        chatModel.reset();
    }

    RequestPostProcessor as(String username) {
        return user(users.loadUserByUsername(username));
    }

    long createConversation(String username) throws Exception {
        String body = mvc.perform(post("/api/conversations").with(as(username)).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    MvcResult send(String username, long conversationId, String text) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .with(as(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("content", text))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void createAndListOwnConversations() throws Exception {
        long mine = createConversation("wangjl");
        long theirs = createConversation("zhangsan");

        String list = mvc.perform(get("/api/conversations").with(as("wangjl")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = json.readTree(list).findValues("id").stream().map(JsonNode::asLong).toList();
        assertThat(ids).contains(mine).doesNotContain(theirs);
    }

    @Test
    void otherMembersConversationIsNotFound() throws Exception {
        long theirs = createConversation("zhangsan");
        mvc.perform(get("/api/conversations/{id}/messages", theirs).with(as("wangjl")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBlankOrTooLongContent() throws Exception {
        long id = createConversation("wangjl");
        for (String bad : List.of("   ", "长".repeat(2001))) {
            mvc.perform(post("/api/conversations/{id}/messages", id).with(as("wangjl")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of("content", bad))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void sendingMessageStreamsEventsInOrderAndPersistsHistory() throws Exception {
        long id = createConversation("wangjl");
        chatModel.thenAnswer("你好，王经理。");

        MvcResult result = send("wangjl", id, "你好");
        List<Event> events = awaitEvents(result, Duration.ofSeconds(10));
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        assertThat(names(events)).containsExactly("message_accepted", "answer", "done");
        assertThat(events.get(1).data().get("content").asText()).isEqualTo("你好，王经理。");
        mvc.perform(get("/api/conversations/{id}/messages", id).with(as("wangjl")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("你好"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"));
    }

    @Test
    void concurrentRequestInSameConversationIsRejected() throws Exception {
        long id = createConversation("wangjl");
        chatModel.thenAnswerAfter(Duration.ofMillis(800), "慢回答");

        MvcResult first = send("wangjl", id, "第一条");
        mvc.perform(post("/api/conversations/{id}/messages", id).with(as("wangjl")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"第二条\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        awaitEvents(first, Duration.ofSeconds(10));
    }
}
