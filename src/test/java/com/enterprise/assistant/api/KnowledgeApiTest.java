package com.enterprise.assistant.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.enterprise.assistant.knowledge.KbDocumentRepository;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

@AutoConfigureMockMvc
class KnowledgeApiTest extends AbstractIntegrationTest {

    static final String NAME = "测试上传制度";

    @Autowired
    MockMvc mvc;

    @Autowired
    MemberUserDetailsService users;

    @Autowired
    KbDocumentRepository documents;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        embeddingModel.reset();
        documents.findByName(NAME).ifPresent(d -> {
            jdbc.update("DELETE FROM vector_store WHERE metadata->>'document_id' = ?", String.valueOf(d.getId()));
            documents.delete(d);
        });
    }

    RequestPostProcessor as(String username) {
        return user(users.loadUserByUsername(username));
    }

    MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "text/markdown", content);
    }

    MockMultipartFile markdown(String filename, String content) {
        return file(filename, content.getBytes(StandardCharsets.UTF_8));
    }

    void upload(String username, MockMultipartFile file, ResultMatcher expected) throws Exception {
        mvc.perform(multipart("/api/knowledge/documents").file(file).with(as(username)).with(csrf()))
                .andExpect(expected);
    }

    @Test
    void projectManagerCanUpload() throws Exception {
        mvc.perform(multipart("/api/knowledge/documents").file(markdown(NAME + ".md", "# 差旅\n\n## 一、标准\n\n住宿每晚不超过 500 元。"))
                        .with(as("wangjl")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(NAME))
                .andExpect(jsonPath("$.source").value("UPLOADED"))
                .andExpect(jsonPath("$.chunkCount").value(1));
    }

    @Test
    void teamMemberCannotUpload() throws Exception {
        upload("zhangsan", markdown(NAME + ".md", "# 标题\n\n内容"), status().isForbidden());
    }

    @Test
    void uploadRequiresLogin() throws Exception {
        mvc.perform(multipart("/api/knowledge/documents").file(markdown(NAME + ".md", "内容")).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidFiles() throws Exception {
        upload("wangjl", markdown(NAME + ".txt", "内容"), status().isBadRequest());
        upload("wangjl", markdown(NAME + ".md", "   "), status().isBadRequest());
        upload("wangjl", file(NAME + ".md", new byte[] {(byte) 0xC3, (byte) 0x28, 'a'}), status().isBadRequest());
        upload("wangjl", markdown(NAME + ".md", "字".repeat(400_000)), status().isBadRequest());
    }

    @Test
    void embeddingServiceDownReturns503() throws Exception {
        embeddingModel.setFailing(true);
        mvc.perform(multipart("/api/knowledge/documents").file(markdown(NAME + ".md", "# 标题\n\n## 一\n\n内容"))
                        .with(as("wangjl")).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void listsDocuments() throws Exception {
        mvc.perform(get("/api/knowledge/documents").with(as("zhangsan")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '项目风险管理规定')].source").value("BUILT_IN"));
    }
}
