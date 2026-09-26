package com.enterprise.assistant.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

@AutoConfigureMockMvc
class AuthApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MemberUserDetailsService users;

    @Test
    void loginWithDemoPasswordRedirectsHome() throws Exception {
        mvc.perform(formLogin("/login").user("wangjl").password("demo123"))
                .andExpect(authenticated().withUsername("wangjl"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void wrongPasswordRedirectsToGenericError() throws Exception {
        mvc.perform(formLogin("/login").user("wangjl").password("wrong"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login.html?error"));
        mvc.perform(formLogin("/login").user("nobody").password("demo123"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void apiWithoutLoginReturns401Json() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void pageWithoutLoginRedirectsToLoginPage() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login.html"));
    }

    @Test
    void meReturnsCurrentMember() throws Exception {
        mvc.perform(get("/api/me").with(user(users.loadUserByUsername("zhangsan"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("zhangsan"))
                .andExpect(jsonPath("$.name").value("张三"))
                .andExpect(jsonPath("$.role").value("TEAM_MEMBER"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MvcResult login = mvc.perform(formLogin("/login").user("lijl").password("demo123")).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/conversations").with(user(users.loadUserByUsername("wangjl"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthDoesNotRequireLogin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
