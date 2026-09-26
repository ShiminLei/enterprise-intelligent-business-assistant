package db.migration;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 演示数据（FR-010）。日期以迁移执行当天（Asia/Shanghai）为基准 D 生成，
 * 保证任何时候初始化都有预期数量的延期任务可以演示（research R14）。
 */
public class V2__SeedDemoData extends BaseJavaMigration {

    static final String DEMO_PASSWORD = "demo123";

    private record SeedMember(String username, String name, String role) {
    }

    private record SeedProject(String code, String name, String description, String manager,
                               int startOffset, int endOffset) {
    }

    /** dueOffset / completedOffset 为相对 D 的天数；completedOffset 仅对 DONE 有效。 */
    private record SeedTask(String code, String project, String title, String assignee, String priority,
                            String status, int dueOffset, Integer completedOffset) {
    }

    private static final List<SeedMember> MEMBERS = List.of(
            new SeedMember("wangjl", "王经理", "PROJECT_MANAGER"),
            new SeedMember("lijl", "李经理", "PROJECT_MANAGER"),
            new SeedMember("zhangsan", "张三", "TEAM_MEMBER"),
            new SeedMember("lisi", "李四", "TEAM_MEMBER"),
            new SeedMember("zhaoliu", "赵六", "TEAM_MEMBER"));

    private static final List<SeedProject> PROJECTS = List.of(
            new SeedProject("P-001", "项目A（客户门户升级）", "升级客户自助门户，新增在线支付与工单功能", "wangjl", -60, 60),
            new SeedProject("P-002", "项目B（数据中台建设）", "建设统一数据仓库、指标体系与数据服务", "lijl", -45, 90),
            new SeedProject("P-003", "项目C（移动办公 App）", "开发覆盖考勤、审批、消息的移动办公应用", "lijl", -30, 75));

    private static final List<SeedTask> TASKS = List.of(
            // 项目A：3 个延期任务（2 天低、5 天中、高优先级 9 天高）
            new SeedTask("T-101", "P-001", "需求调研与用户访谈", "zhangsan", "MEDIUM", "DONE", -40, -42),
            new SeedTask("T-102", "P-001", "用户中心页面改版", "lisi", "MEDIUM", "TODO", 10, null),
            new SeedTask("T-103", "P-001", "支付接口联调", "zhangsan", "HIGH", "IN_PROGRESS", -9, null),
            new SeedTask("T-104", "P-001", "订单查询性能优化", "lisi", "MEDIUM", "IN_PROGRESS", -5, null),
            new SeedTask("T-105", "P-001", "客服工单模块开发", "zhaoliu", "LOW", "TODO", -2, null),
            new SeedTask("T-106", "P-001", "前端组件库升级", "zhaoliu", "LOW", "DONE", -20, -21),
            new SeedTask("T-107", "P-001", "旧版报表迁移", "lisi", "LOW", "CANCELLED", -15, null),
            new SeedTask("T-108", "P-001", "安全渗透测试", "zhangsan", "HIGH", "TODO", 14, null),
            new SeedTask("T-109", "P-001", "上线方案评审", "zhaoliu", "HIGH", "IN_PROGRESS", 3, null),
            new SeedTask("T-110", "P-001", "用户验收测试", "lisi", "MEDIUM", "TODO", 25, null),
            // 项目B：1 个延期任务（4 天中）
            new SeedTask("T-111", "P-002", "数据源梳理", "zhangsan", "HIGH", "DONE", -30, -31),
            new SeedTask("T-112", "P-002", "数据仓库建模", "lisi", "HIGH", "IN_PROGRESS", -4, null),
            new SeedTask("T-113", "P-002", "ETL 任务开发", "zhaoliu", "MEDIUM", "IN_PROGRESS", 7, null),
            new SeedTask("T-114", "P-002", "数据质量校验规则", "zhangsan", "MEDIUM", "TODO", 12, null),
            new SeedTask("T-115", "P-002", "指标口径统一", "lisi", "LOW", "TODO", 20, null),
            new SeedTask("T-116", "P-002", "BI 看板搭建", "zhaoliu", "MEDIUM", "TODO", 18, null),
            new SeedTask("T-117", "P-002", "历史数据迁移", "zhangsan", "LOW", "CANCELLED", -10, null),
            new SeedTask("T-118", "P-002", "数据权限设计", "lisi", "HIGH", "TODO", 5, null),
            new SeedTask("T-119", "P-002", "数据服务 API 开发", "zhaoliu", "HIGH", "IN_PROGRESS", 9, null),
            new SeedTask("T-120", "P-002", "运维监控接入", "zhangsan", "LOW", "DONE", -3, -5),
            // 项目C：无延期任务
            new SeedTask("T-121", "P-003", "需求原型设计", "zhaoliu", "HIGH", "DONE", -25, -26),
            new SeedTask("T-122", "P-003", "登录与考勤模块", "zhangsan", "HIGH", "IN_PROGRESS", 6, null),
            new SeedTask("T-123", "P-003", "审批流模块", "lisi", "MEDIUM", "IN_PROGRESS", 10, null),
            new SeedTask("T-124", "P-003", "消息推送", "zhaoliu", "MEDIUM", "TODO", 15, null),
            new SeedTask("T-125", "P-003", "通讯录同步", "zhangsan", "LOW", "TODO", 20, null),
            new SeedTask("T-126", "P-003", "离线缓存", "lisi", "LOW", "CANCELLED", -5, null),
            new SeedTask("T-127", "P-003", "性能压测", "zhaoliu", "MEDIUM", "TODO", 22, null),
            new SeedTask("T-128", "P-003", "应用商店上架准备", "zhangsan", "LOW", "TODO", 30, null),
            new SeedTask("T-129", "P-003", "UI 走查", "lisi", "HIGH", "DONE", -2, -3),
            new SeedTask("T-130", "P-003", "灰度发布方案", "zhaoliu", "HIGH", "TODO", 12, null));

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Timestamp now = Timestamp.from(Instant.now());
        String passwordHash = new BCryptPasswordEncoder().encode(DEMO_PASSWORD);

        Map<String, Long> memberIds = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO member (username, password_hash, name, role) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (SeedMember m : MEMBERS) {
                ps.setString(1, m.username());
                ps.setString(2, passwordHash);
                ps.setString(3, m.name());
                ps.setString(4, m.role());
                ps.executeUpdate();
                memberIds.put(m.username(), generatedId(ps));
            }
        }

        Map<String, Long> projectIds = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO project (code, name, description, manager_id, planned_start_date, planned_end_date, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS')",
                Statement.RETURN_GENERATED_KEYS)) {
            for (SeedProject p : PROJECTS) {
                ps.setString(1, p.code());
                ps.setString(2, p.name());
                ps.setString(3, p.description());
                ps.setLong(4, memberIds.get(p.manager()));
                ps.setDate(5, Date.valueOf(today.plusDays(p.startOffset())));
                ps.setDate(6, Date.valueOf(today.plusDays(p.endOffset())));
                ps.executeUpdate();
                projectIds.put(p.code(), generatedId(ps));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO task (code, project_id, title, assignee_id, priority, status, due_date, completed_date, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (SeedTask t : TASKS) {
                ps.setString(1, t.code());
                ps.setLong(2, projectIds.get(t.project()));
                ps.setString(3, t.title());
                ps.setLong(4, memberIds.get(t.assignee()));
                ps.setString(5, t.priority());
                ps.setString(6, t.status());
                ps.setDate(7, Date.valueOf(today.plusDays(t.dueOffset())));
                ps.setDate(8, t.completedOffset() == null ? null : Date.valueOf(today.plusDays(t.completedOffset())));
                ps.setTimestamp(9, now);
                ps.setTimestamp(10, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static long generatedId(PreparedStatement ps) throws Exception {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong("id");
        }
    }
}
