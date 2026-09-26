# Implementation Plan: 项目管理智能助手

**Branch**: `001-project-management-assistant` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-project-management-assistant/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

构建一个面向项目经理和团队成员的项目管理智能助手：用户登录后在 Web 页面中用自然语言多轮对话，
助手自主调用 6 个 Java 业务工具（查询项目、查询任务、查询成员、检索知识库、准备创建任务、准备修改任务状态），
综合业务数据和公司制度给出带出处的回答，核心演示用例是「项目A 延期任务风险分析」。

技术方案：单个 Spring Boot 应用，Spring AI 经 OpenAI 兼容接口接入阿里云百炼（qwen-plus 对话、text-embedding-v4 向量化）；
自己控制 Agent 工具调用循环，以实现 10 次调用上限、逐步 SSE 推送和调用日志；写操作工具只生成「待确认操作」，
由用户点击按钮后经 REST 接口直接执行，不经过大模型；PostgreSQL + pgvector 同时存放业务数据和向量。
各项决策依据见 [research.md](research.md)。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.x（Web、Security、Data JPA、Validation、Actuator、Docker Compose 支持）；
Spring AI 1.1.x（`spring-ai-starter-model-openai`、`spring-ai-starter-vector-store-pgvector`）；Markdown 按标题切分自行实现，超长章节用 Spring AI `TokenTextSplitter`；
Flyway；前端内置 `marked.min.js` + `DOMPurify`（随仓库提交，无 Node 构建）

**Storage**: PostgreSQL 16 + pgvector（`pgvector/pgvector:pg16`），业务数据、会话和向量同库；Flyway 管理结构和示例数据

**Testing**: JUnit 5、AssertJ、Mockito、Spring Boot Test、MockMvc + spring-security-test、Testcontainers；
模型替身（脚本化 `ChatModel`、确定性 `EmbeddingModel`）；评估问题集（`-Peval`，调用真实模型）

**Target Platform**: 本地开发机（macOS / Linux / Windows，需 JDK 21 与 Docker），浏览器访问 `http://localhost:8080`

**Project Type**: Web 应用（单体：后端 + 静态前端打包在同一个 Spring Boot 应用中）

**Performance Goals**: 单项查询 10 秒内回答；多步骤综合分析 60 秒内完成，过程中持续推送步骤进度（SC-003）

**Constraints**: 单次请求工具调用 ≤ 10 次；单次请求超时 120 秒；向量化每批 ≤ 10 条、每条 ≤ 8,192 Token（百炼限制）；
仓库中无真实 API Key；`./mvnw verify` 不需要 API Key

**Scale/Scope**: 本地演示，个位数并发用户；5 个成员、3 个项目、约 30 个任务；2 份以上制度文档；
约 4 个页面视图（登录、对话、会话列表、知识库）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

对照宪法 v2.0.0 逐条检查。**Phase 0 前：通过；Phase 1 设计后复查：通过。**

| 原则 / 约束 | 要求 | 本计划如何满足 | 结果 |
| --- | --- | --- | --- |
| I 安全 | 除登录页外必须登录；服务端按角色校验权限 | Spring Security 表单登录；权限在 Service 层以显式传入的当前成员校验（research R9） | ✅ |
| I 安全 | 密码不存明文、不进日志 | BCrypt；种子数据只含哈希 | ✅ |
| I 安全 | 密钥只经环境变量注入 | `DASHSCOPE_API_KEY`；`.gitignore` 排除本地配置；交付前 `git grep` 检查 | ✅ |
| I 安全 | 写操作经确认才执行，并记录执行人、时间、内容 | 写工具只生成 `pending_action`；按钮确认后由 REST 直接执行；该表即审计记录（research R4） | ✅ |
| I 安全 | 用户输入与知识库内容视为数据 | 执行路径不经过模型，注入内容无法绕过确认和权限；当前用户经 `ToolContext` 注入，模型无法伪造 | ✅ |
| II 有据可依 | 制度回答标注出处；依据不足不编造 | `searchKnowledge` 结果由服务端登记为结构化 citations；提示词要求空结果如实告知（research R5） | ✅ |
| II 有据可依 | 提示词与工具描述在仓库中；模型名可配置 | `src/main/resources/prompts/`、工具注解；[contracts/agent-tools.md](contracts/agent-tools.md) 同步维护 | ✅ |
| II 有据可依 | 维护评估问题集，交付前运行 | `eval/questions.yaml` + `-Peval`（research R12） | ✅ |
| II 有据可依 | 回答可追溯到工具调用和来源 | `message.steps`、`message.citations` 持久化 | ✅ |
| III 测试先行 | 业务规则与工具先写测试 | tasks 阶段按「测试 → 实现」排序：权限、状态流转、延期判定、工具校验 | ✅ |
| III 测试先行 | 接口测试覆盖正常 / 未登录 / 无权限 | MockMvc + spring-security-test，每个接口 3 类用例 | ✅ |
| III 测试先行 | 模型调用以 stub 替代，无 API Key 可运行 | 脚本化 `ChatModel` 与确定性 `EmbeddingModel` 替身 | ✅ |
| IV 可观测性 | 结构化日志 + 请求 ID 贯穿 | Spring Boot 内置 ECS 结构化日志；MDC 在 Agent 线程池中传递（research R13） | ✅ |
| IV 可观测性 | 记录模型名、耗时、Token；工具名、耗时、成败 | 自控 Agent 循环中逐次记录（research R3） | ✅ |
| IV 可观测性 | 健康检查端点 | Actuator `health`，含自定义 `knowledgeBase` 组件 | ✅ |
| V 简单性 | 单个可部署应用、单 Agent、不引入多智能体 | 单体 Spring Boot；单 Agent + 6 个工具 | ✅ |
| V 简单性 | 新增依赖 / 数据存储需说明理由 | 见下方 Complexity Tracking | ✅（已说明） |
| 技术约束 | Java 21 + Spring Boot 3.x；百炼；单一 API Key；抽象层 | Spring AI OpenAI 兼容接口；base-url 与模型名可配置 | ✅ |
| 技术约束 | 版本化迁移；示例数据首次启动自动初始化 | Flyway（SQL 结构 + Java 迁移生成示例数据） | ✅ |
| 技术约束 | 一键启动 | `./mvnw spring-boot:run` 通过 `spring-boot-docker-compose` 自动拉起数据库 | ✅ |

## Project Structure

### Documentation (this feature)

```text
specs/001-project-management-assistant/
├── plan.md                        # 本文件
├── research.md                    # Phase 0：技术决策
├── data-model.md                  # Phase 1：数据模型、状态机、权限、示例数据
├── quickstart.md                  # Phase 1：启动与端到端验收指南
├── contracts/
│   ├── openapi.yaml               # REST 接口
│   ├── chat-stream-events.md      # 对话 SSE 事件流
│   └── agent-tools.md             # 大模型可调用的业务工具
├── checklists/
│   └── requirements.md            # 规格质量检查清单
└── tasks.md                       # Phase 2（/speckit-tasks 生成，本命令不创建）
```

### Source Code (repository root)

按业务功能分包；每个包内包含实体、Repository、Service 和 Controller。

```text
pom.xml
mvnw, mvnw.cmd, .mvn/
compose.yaml                               # PostgreSQL 16 + pgvector
.env.example                               # 只含变量名：DASHSCOPE_API_KEY=
README.md
eval/
└── questions.yaml                         # 评估问题集

src/main/java/com/enterprise/assistant/
├── AssistantApplication.java
├── common/            # 统一错误响应、请求 ID 过滤器、Clock 配置、MDC 传递
├── security/          # SecurityConfig、登录用户解析（CurrentMember）
├── member/            # Member 实体与查询
├── project/           # Project 实体、ProjectService（查询、模糊匹配）
├── task/              # Task、TaskStatus 状态机、延期判定、TaskService（含权限校验）
├── knowledge/         # 文档导入（启动时 + 上传）、切分、≤10 条分批向量化、检索、健康指标、KnowledgeController
├── conversation/      # Conversation、Message、ConversationController（含 SSE）
├── action/            # PendingAction、确认 / 取消 / 作废、PendingActionController
└── agent/             # AgentService（自控工具循环、步数上限、步骤与引用收集、调用日志）
    └── tools/         # ProjectTools、TaskTools、MemberTools、KnowledgeTools

src/main/resources/
├── application.yml                        # 百炼 base-url、模型名、维度等均可配置；API Key 取自环境变量
├── db/migration/
│   ├── V1__schema.sql                     # 业务表、会话表、pending_action、kb_document、vector_store(1024) + HNSW
│   └── (Java) V2__SeedDemoData            # 以初始化当天为基准的示例数据
├── knowledge/
│   ├── 项目风险管理规定.md
│   └── 项目管理制度.md
├── prompts/
│   └── system-prompt.st                   # 系统提示词
└── static/
    ├── login.html
    ├── index.html                         # 对话、会话列表、知识库视图
    ├── app.js
    ├── app.css
    └── vendor/ (marked.min.js, purify.min.js)

src/test/java/com/enterprise/assistant/
├── seed/          # 示例数据校验（集成）
├── project/       # 项目查询与模糊匹配（集成）
├── task/          # 状态机、延期判定、权限（单元）、任务查询与写入（集成）
├── conversation/  # 会话归属、历史记录（集成）
├── agent/         # Agent 循环：上限、步骤事件、写工具只生成待确认操作（stub 模型）
├── knowledge/     # 切分、分批 ≤10、去重替换（Testcontainers）
├── action/        # 确认 / 取消 / 作废 / 重复确认 409（集成）
├── api/           # 各接口正常 / 401 / 403（MockMvc）
├── support/       # ScriptedChatModel、HashEmbeddingModel、Testcontainers 基类
└── eval/          # @Tag("eval") 评估问题集运行器（仅 -Peval）
```

**Structure Decision**: 采用单项目结构（宪法 V：单个可部署应用）。前端作为静态资源放在同一应用的
`src/main/resources/static/` 中，不单独建 `frontend/` 工程。Java 代码按业务功能分包，而不是按技术分层，
这样每个用户故事涉及的代码集中在少数几个包里，便于按用户故事拆分任务。

## Complexity Tracking

> 宪法 V 要求：新增第三方依赖、中间件或数据存储须说明理由及被否决的更简单方案。以下均非违规，而是按要求记录的理由。

| 引入项 | 为什么需要 | 被否决的更简单方案及原因 |
| --- | --- | --- |
| PostgreSQL + pgvector（需 Docker） | 一个库同时存业务数据与向量；重启后知识库不丢失、不必重复向量化；去重和同名替换可在一个事务内完成 | H2 + `SimpleVectorStore`：免 Docker，但向量文件与数据库要分别维护一致性，去重 / 替换需自写文件逻辑，且 H2 行为与真实数据库有差异 |
| Testcontainers | 集成测试需要真实的 pgvector 做向量检索与事务验证 | 用 H2 测试：不支持 pgvector，关键路径（检索、去重）无法测试 |
| Spring Security | FR-004 登录、FR-014a 权限、CSRF 防护 | 自写拦截器与 Session 处理：代码更多且容易出安全漏洞 |
| 自控 Agent 循环（而非 Spring AI 自动工具执行） | FR-019 上限、FR-020 逐步推送、宪法 IV 逐次记录 | 自动执行 + Advisor 拦截：限次和逐步推送需要绕路，可读性更差 |
| `marked.min.js` + `DOMPurify`（前端内置文件） | 风险报告含表格，需要渲染 Markdown；渲染结果须净化以防 XSS | 纯文本显示：报告表格不可读；前端框架：需要 Node 构建链 |
