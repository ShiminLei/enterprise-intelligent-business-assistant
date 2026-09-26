package com.enterprise.assistant.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.action.PendingActionService;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.conversation.MessageStatus;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

@AutoConfigureMockMvc
@Transactional
class PendingActionApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PendingActionService actions;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    CurrentMember wang;
    long conversationId;
    long actionId;

    @BeforeEach
    void setUp() {
        wang = users.loadUserByUsername("wangjl");
        conversationId = conversations.create(wang).getId();
        String due = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(3).toString();
        actionId = actions.proposeCreateTask(wang, conversationId, "P-001", "跟进支付接口联调", "张三", due, "HIGH").getId();
    }

    RequestPostProcessor as(String username) {
        return user(users.loadUserByUsername(username));
    }

    @Test
    void confirmExecutesOnceThenConflicts() throws Exception {
        mvc.perform(post("/api/pending-actions/{id}/confirm", actionId).with(as("wangjl")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.result").value("已创建任务 T-131"));
        mvc.perform(post("/api/pending-actions/{id}/confirm", actionId).with(as("wangjl")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void cancel() throws Exception {
        mvc.perform(post("/api/pending-actions/{id}/cancel", actionId).with(as("wangjl")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void otherMemberIsForbidden() throws Exception {
        mvc.perform(post("/api/pending-actions/{id}/confirm", actionId).with(as("lijl")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/pending-actions/{id}/cancel", actionId).with(as("zhangsan")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownActionIsNotFound() throws Exception {
        mvc.perform(post("/api/pending-actions/{id}/confirm", 999_999).with(as("wangjl")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(post("/api/pending-actions/{id}/confirm", actionId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void historyIncludesPendingActionsWithLatestStatus() throws Exception {
        long messageId = conversations.saveAssistantMessage(conversationId, "请确认", List.of(), List.of(),
                MessageStatus.COMPLETED).getId();
        actions.linkToMessage(List.of(actionId), messageId);
        actions.cancel(wang, actionId);

        mvc.perform(get("/api/conversations/{id}/messages", conversationId).with(as("wangjl")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pendingActions[0].id").value(actionId))
                .andExpect(jsonPath("$[0].pendingActions[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].pendingActions[0].summary").isNotEmpty());
    }
}
