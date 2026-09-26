package com.enterprise.assistant.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;

import com.enterprise.assistant.agent.AgentEvent;
import com.enterprise.assistant.agent.AgentService;
import com.enterprise.assistant.conversation.Citation;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.knowledge.KnowledgeHealthIndicator;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;

/**
 * 评估问题集运行器（宪法原则 II、research R12）：调用真实的百炼模型，逐题判定并写出 target/eval-report.md。
 * 只在 {@code ./mvnw verify -Peval} 且设置了 DASHSCOPE_API_KEY 时运行。
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.ai.openai.api-key=${DASHSCOPE_API_KEY}")
class EvalRunnerTest {

    static final Path QUESTIONS = Path.of("eval/questions.yaml");
    static final Path REPORT = Path.of("target/eval-report.md");

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @Autowired
    AgentService agent;

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    @Autowired
    KnowledgeHealthIndicator knowledgeHealth;

    /** 一轮问答的观测结果。 */
    record Turn(String answer, List<Citation> citations, List<String> tools, boolean pendingAction, long millis) {
    }

    /** 一次判定。 */
    record Check(String caseId, String category, String type, String question, boolean passed, List<String> problems,
                 long millis) {
        static Check of(String caseId, String category, String type, String question, List<String> problems,
                        long millis) {
            return new Check(caseId, category, type, question, problems.isEmpty(), problems, millis);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void runEvaluation() throws Exception {
        assertThat(knowledgeHealth.isAvailable()).as("知识库导入失败，请检查 DASHSCOPE_API_KEY 与向量模型").isTrue();
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(QUESTIONS)) {
            root = new Yaml().load(in);
        }
        List<Check> checks = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) root.get("cases")) {
            String id = (String) c.get("id");
            String category = (String) c.get("category");
            String type = (String) c.getOrDefault("type", "query");
            CurrentMember member = users.loadUserByUsername((String) c.getOrDefault("user", "wangjl"));

            if (c.containsKey("turns")) {
                long conversationId = conversations.create(member).getId();
                int turnNo = 0;
                for (Map<String, Object> t : (List<Map<String, Object>>) c.get("turns")) {
                    turnNo++;
                    String question = (String) t.get("question");
                    Turn turn = ask(member, conversationId, question);
                    checks.add(Check.of(id + "#" + turnNo, category, type, question, problems(t, turn), turn.millis()));
                }
                continue;
            }

            int repeat = ((Number) c.getOrDefault("repeat", 1)).intValue();
            for (int i = 1; i <= repeat; i++) {
                String question = (String) c.get("question");
                Turn turn = ask(member, conversations.create(member).getId(), question);
                String caseId = repeat > 1 ? id + "#" + i : id;
                checks.add(Check.of(caseId, category, type, question, problems(c, turn), turn.millis()));
            }
        }

        Summary summary = new Summary(checks);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, summary.toMarkdown(), StandardCharsets.UTF_8);

        assertThat(summary.passed("policy")).as("SC-002 制度问答 ≥ 9/10，详见 " + REPORT).isGreaterThanOrEqualTo(9);
        assertThat(summary.passed("not_found")).as("SC-002 知识库外问题 3/3").isEqualTo(summary.total("not_found"));
        assertThat(summary.passed("core")).as("SC-001 核心用例 5 次至少 4 次").isGreaterThanOrEqualTo(4);
        assertThat(summary.ratio("followup")).as("SC-005 指代追问正确率 ≥ 90%").isGreaterThanOrEqualTo(0.9);
    }

    Turn ask(CurrentMember member, long conversationId, String question) {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        agent.handle(member, conversationId, question, List.of(), events::add);
        long millis = (System.nanoTime() - start) / 1_000_000;
        String answer = "";
        List<Citation> citations = List.of();
        List<String> tools = new ArrayList<>();
        boolean pending = false;
        for (AgentEvent e : events) {
            switch (e) {
                case AgentEvent.Answer a -> {
                    answer = a.content();
                    citations = a.citations();
                }
                case AgentEvent.Error err -> answer = "【错误】" + err.message();
                case AgentEvent.StepStarted s -> tools.add(s.tool());
                case AgentEvent.PendingActionProposed p -> pending = true;
                default -> {
                }
            }
        }
        return new Turn(answer, citations, tools, pending, millis);
    }

    @SuppressWarnings("unchecked")
    static List<String> problems(Map<String, Object> expect, Turn turn) {
        List<String> problems = new ArrayList<>();
        for (String keyword : (List<String>) expect.getOrDefault("expectKeywords", List.of())) {
            if (Arrays.stream(keyword.split("\\|")).noneMatch(turn.answer()::contains)) {
                problems.add("回答缺少「" + keyword + "」");
            }
        }
        String document = (String) expect.get("expectDocument");
        if (document != null && turn.citations().stream().noneMatch(c -> c.documentName().equals(document))) {
            problems.add("引用中没有《" + document + "》");
        }
        List<String> expectedTools = (List<String>) expect.getOrDefault("expectTools", List.of());
        int next = 0;
        for (String tool : turn.tools()) {
            if (next < expectedTools.size() && tool.equals(expectedTools.get(next))) {
                next++;
            }
        }
        if (next < expectedTools.size()) {
            problems.add("工具调用顺序不符：期望 " + expectedTools + "，实际 " + turn.tools());
        }
        if (Boolean.TRUE.equals(expect.get("expectNoPendingAction")) && turn.pendingAction()) {
            problems.add("不应生成待确认操作");
        }
        if (Boolean.TRUE.equals(expect.get("expectNoTools")) && !turn.tools().isEmpty()) {
            problems.add("不应调用工具，实际调用了 " + turn.tools());
        }
        return problems;
    }

    static final class Summary {
        private final List<Check> checks;

        Summary(List<Check> checks) {
            this.checks = checks;
        }

        long total(String category) {
            return checks.stream().filter(c -> c.category().equals(category)).count();
        }

        long passed(String category) {
            return checks.stream().filter(c -> c.category().equals(category) && c.passed()).count();
        }

        double ratio(String category) {
            long total = total(category);
            return total == 0 ? 1 : (double) passed(category) / total;
        }

        String toMarkdown() {
            StringBuilder md = new StringBuilder("# 评估报告\n\n生成时间：").append(LocalDateTime.now().withNano(0))
                    .append("\n\n## 汇总\n\n| 指标 | 结果 | 要求 |\n| --- | --- | --- |\n")
                    .append(row("制度问答（SC-002）", passed("policy") + "/" + total("policy"), "≥ 9/10"))
                    .append(row("知识库外问题（SC-002）", passed("not_found") + "/" + total("not_found"), "3/3"))
                    .append(row("核心演示用例（SC-001）", passed("core") + "/" + total("core"), "≥ 4/5"))
                    .append(row("指代追问正确率（SC-005）", percent(ratio("followup")), "≥ 90%"))
                    .append(row("缺少信息先追问（FR-022）", passed("ask_missing") + "/" + total("ask_missing"), "全部"))
                    .append(row("超出服务范围", passed("out_of_scope") + "/" + total("out_of_scope"), "全部"))
                    .append(row("单项查询 ≤ 10 秒（SC-003）", timely("query", 10_000), "全部"))
                    .append(row("多步骤分析 ≤ 60 秒（SC-003）", timely("analysis", 60_000), "全部"))
                    .append("\n## 逐题结果\n\n| 编号 | 结果 | 耗时 | 问题 | 未通过原因 |\n| --- | --- | --- | --- | --- |\n");
            for (Check c : checks) {
                md.append("| ").append(c.caseId()).append(" | ").append(c.passed() ? "✅" : "❌").append(" | ")
                        .append(String.format("%.1f 秒", c.millis() / 1000.0)).append(" | ")
                        .append(c.question().replace("|", "\\|")).append(" | ")
                        .append(String.join("；", c.problems()).replace("|", "\\|")).append(" |\n");
            }
            return md.toString();
        }

        private String timely(String type, long limitMillis) {
            List<Check> ofType = checks.stream().filter(c -> c.type().equals(type)).toList();
            long ok = ofType.stream().filter(c -> c.millis() <= limitMillis).count();
            return ok + "/" + ofType.size();
        }

        private static String row(String name, String value, String requirement) {
            return "| " + name + " | " + value + " | " + requirement + " |\n";
        }

        private static String percent(double ratio) {
            return String.format("%.0f%%", ratio * 100);
        }
    }
}
