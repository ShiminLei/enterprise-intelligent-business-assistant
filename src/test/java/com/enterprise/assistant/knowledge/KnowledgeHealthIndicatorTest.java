package com.enterprise.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.enterprise.assistant.support.AbstractIntegrationTest;

@AutoConfigureMockMvc
class KnowledgeHealthIndicatorTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void upAfterSuccessfulStartupImport() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.knowledgeBase.status").value("UP"));
    }

    @Test
    void downWithReasonWhenImportFails() {
        KnowledgeHealthIndicator indicator = new KnowledgeHealthIndicator();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);

        indicator.markDown("向量服务不可用");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("reason", "向量服务不可用");
        assertThat(indicator.isAvailable()).isFalse();

        indicator.markUp(2);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.isAvailable()).isTrue();
    }
}
