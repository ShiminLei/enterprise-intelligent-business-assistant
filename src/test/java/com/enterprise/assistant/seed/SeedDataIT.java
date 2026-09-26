package com.enterprise.assistant.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.enterprise.assistant.support.AbstractIntegrationTest;

class SeedDataIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void membersAreSeededWithBcryptPasswords() {
        List<Map<String, Object>> members = jdbc.queryForList("SELECT username, role, password_hash FROM member ORDER BY id");
        assertThat(members).extracting(m -> m.get("username"))
                .containsExactly("wangjl", "lijl", "zhangsan", "lisi", "zhaoliu");
        assertThat(members).filteredOn(m -> "PROJECT_MANAGER".equals(m.get("role")))
                .extracting(m -> m.get("username")).containsExactly("wangjl", "lijl");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertThat(members).allSatisfy(m -> {
            String hash = (String) m.get("password_hash");
            assertThat(hash).doesNotContain("demo123");
            assertThat(encoder.matches("demo123", hash)).isTrue();
        });
    }

    @Test
    void eachProjectHasTenTasks() {
        assertThat(jdbc.queryForList(
                "SELECT p.code, count(t.id) AS n FROM project p JOIN task t ON t.project_id = p.id GROUP BY p.code ORDER BY p.code"))
                .extracting(r -> r.get("code") + "=" + r.get("n"))
                .containsExactly("P-001=10", "P-002=10", "P-003=10");
    }

    @Test
    void overdueTasksMatchTheDemoScript() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                SELECT p.code AS project, t.priority, (?::date - t.due_date) AS days
                FROM task t JOIN project p ON p.id = t.project_id
                WHERE t.status IN ('TODO', 'IN_PROGRESS') AND t.due_date < ?::date
                ORDER BY p.code, days
                """, today, today);
        assertThat(overdue).extracting(r -> r.get("project") + ":" + r.get("days") + ":" + r.get("priority"))
                .containsExactly("P-001:2:LOW", "P-001:5:MEDIUM", "P-001:9:HIGH", "P-002:4:HIGH");
    }

    @Test
    void everyProjectCoversAllStatusesAndPriorities() {
        for (String column : List.of("status", "priority")) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT p.code, count(DISTINCT t." + column + ") AS n FROM project p JOIN task t ON t.project_id = p.id GROUP BY p.code");
            int expected = column.equals("status") ? 4 : 3;
            assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("n")).intValue()).isEqualTo(expected));
        }
    }

    @Test
    void everyTeamMemberHasAnUnfinishedTask() {
        List<String> withOpenTasks = jdbc.queryForList("""
                SELECT DISTINCT m.username FROM member m JOIN task t ON t.assignee_id = m.id
                WHERE m.role = 'TEAM_MEMBER' AND t.status IN ('TODO', 'IN_PROGRESS')
                """, String.class);
        assertThat(withOpenTasks).containsExactlyInAnyOrder("zhangsan", "lisi", "zhaoliu");
    }
}
