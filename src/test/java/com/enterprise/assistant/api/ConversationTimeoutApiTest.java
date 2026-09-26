package com.enterprise.assistant.api;

import static com.enterprise.assistant.support.SseTestSupport.awaitEvents;
import static com.enterprise.assistant.support.SseTestSupport.names;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.conversation.MessageRole;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;
import com.enterprise.assistant.support.SseTestSupport.Event;

/** 模型响应超过 app.agent.timeout 时推送 TIMEOUT 错误，用户消息已保存（spec 边界情况、G3）。 */
// SSE 由后台线程写入响应，关闭 MockMvc 的请求打印，避免它与后台线程同时读写响应头
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = "app.agent.timeout=1s")
class ConversationTimeoutApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MemberUserDetailsService users;

    @Autowired
    ConversationService conversations;

    @Test
    void slowModelProducesTimeoutError() throws Exception {
        chatModel.reset();
        chatModel.thenAnswerAfter(Duration.ofSeconds(3), "太晚了");
        CurrentMember wang = users.loadUserByUsername("wangjl");
        long id = conversations.create(wang).getId();

        MvcResult result = mvc.perform(post("/api/conversations/{id}/messages", id)
                        .with(user(wang)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"项目A进展如何？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        List<Event> events = awaitEvents(result, Duration.ofSeconds(10));

        assertThat(names(events)).containsExactly("message_accepted", "error", "done");
        assertThat(events.get(1).data().get("code").asText()).isEqualTo("TIMEOUT");
        assertThat(conversations.listMessages(wang, id))
                .anyMatch(m -> m.getRole() == MessageRole.USER && m.getContent().equals("项目A进展如何？"));
        Thread.sleep(2500); // 等后台线程结束，避免影响其他测试
    }
}
