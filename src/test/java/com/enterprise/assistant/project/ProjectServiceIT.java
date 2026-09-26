package com.enterprise.assistant.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.enterprise.assistant.support.AbstractIntegrationTest;

class ProjectServiceIT extends AbstractIntegrationTest {

    @Autowired
    ProjectService projects;

    @Test
    void findsByNameOrCode() {
        for (String keyword : List.of("项目A", "项目 A", "项目a", "P-001", "p-001", "客户门户")) {
            assertThat(projects.search(keyword)).as(keyword)
                    .extracting(ProjectService.ProjectSummary::code).containsExactly("P-001");
        }
    }

    @Test
    void blankKeywordReturnsAllProjects() {
        assertThat(projects.search(null)).extracting(ProjectService.ProjectSummary::code)
                .containsExactly("P-001", "P-002", "P-003");
    }

    @Test
    void ambiguousKeywordReturnsAllCandidates() {
        assertThat(projects.search("项目")).hasSize(3);
    }

    @Test
    void noMatchThrowsWithAllProjectNamesAsCandidates() {
        assertThatThrownBy(() -> projects.search("项目Z"))
                .isInstanceOf(ProjectNotFoundException.class)
                .hasMessage("未找到匹配的项目")
                .satisfies(e -> assertThat(((ProjectNotFoundException) e).candidates())
                        .containsExactly("项目A（客户门户升级）", "项目B（数据中台建设）", "项目C（移动办公 App）"));
    }

    @Test
    void summaryIncludesManagerAndTaskCounts() {
        ProjectService.ProjectSummary a = projects.search("P-001").get(0);
        assertThat(a.manager()).isEqualTo("王经理");
        assertThat(a.taskCount()).isEqualTo(10);
        assertThat(a.overdueTaskCount()).isEqualTo(3);
        assertThat(a.plannedEndDate()).isAfter(a.plannedStartDate());
        assertThat(projects.search("P-003").get(0).overdueTaskCount()).isZero();
    }
}
