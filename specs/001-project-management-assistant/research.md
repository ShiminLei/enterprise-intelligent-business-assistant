# Research: 项目管理智能助手

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-26

本文记录 Technical Context 中各项技术决策的依据。每项按「决策 / 理由 / 备选方案」记录。

## R1. AI 框架：Spring AI

- **决策**：使用 Spring AI 1.1.x（与 Spring Boot 3.5.x 兼容的最新版本，以初始化项目时 start.spring.io 提供的版本为准），
  通过 `spring-ai-starter-model-openai` 接入百炼的 OpenAI 兼容接口。
- **理由**：
  - 与 Spring Boot 原生集成（自动配置、`@Tool` 注解、Micrometer 观测），学习和维护成本最低。
  - 自带 `PgVectorStore` 和 `TokenTextSplitter`，知识库链路只需自行实现按 Markdown 标题切分（几十行代码，见 R7），
    不引入额外的 Markdown 解析依赖。
  - 支持「由调用方控制工具执行」模式，可以自己实现 Agent 循环（见 R3）。
  - 走 OpenAI 兼容协议，更换模型提供商只需改配置，符合宪法「业务代码不直接依赖具体提供商」。
- **备选方案**：
  - LangChain4j：能力相当，但与 Spring 的集成不如 Spring AI 自然，且会多出一套配置风格。
  - Spring AI Alibaba（DashScope 原生 starter）：会让代码直接依赖百炼，违背「可替换」要求；兼容接口已够用。

## R2. 百炼接入参数

- **决策**：
  - base-url：`https://dashscope.aliyuncs.com/compatible-mode`（Spring AI 会自动拼接 `/v1/chat/completions`、`/v1/embeddings`），
    通过配置项可改为业务空间专属域名（`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode`）。
  - 对话模型：`qwen-plus`，temperature 0.2（偏确定性，减少编造）。
  - 向量模型：`text-embedding-v4`，`dimensions=1024`，`encoding_format=float`。
  - API Key：环境变量 `DASHSCOPE_API_KEY`，对话和向量共用。
- **关键限制**（来自百炼官方文档）：text-embedding-v4 单次请求最多 **10 条**文本，每条最多 **8,192 Token**。
  Spring AI 默认的批处理策略按 Token 数分批，不限制条数，可能超过 10 条。
  → 知识库导入时 MUST 使用自定义批处理策略，每批不超过 10 条。
- **备选方案**：使用 1536 / 2048 维可略微提升精度，但示例知识库很小，收益可忽略，且占用更多存储。

## R3. Agent 循环：自己控制工具执行

- **决策**：关闭 Spring AI 的内部自动工具执行（`internalToolExecutionEnabled=false`），在 `AgentService` 中自己写循环：
  调用模型 → 若返回工具调用则通过 `ToolCallingManager` 执行 → 把结果交回模型 → 直到模型给出最终回答。
- **理由**：spec 对每一步都有明确要求，自动执行模式无法满足：
  - FR-019：单次请求最多 10 次工具调用，达到上限要停止并说明。
  - FR-020：每一步都要实时推送到页面，展示步骤和结果。
  - 宪法 IV：每次模型调用和工具调用都要记录耗时、Token、是否成功。
- **备选方案**：Spring AI 默认自动工具执行 + Advisor 拦截。实现限次和逐步推送需要绕路，可读性差。

## R4. 写操作确认：工具只「提议」，执行由按钮触发

- **决策**：写操作工具（创建任务、修改状态）不直接修改数据，而是：
  1. 先校验权限和状态流转规则，不通过则直接返回错误（FR-014b：不生成待确认操作）；
  2. 通过后生成一条 `PendingAction`（待确认），返回给模型「已生成待确认操作 #id」；
  3. 页面展示操作内容和「确认 / 取消」按钮；用户点击确认后，由 REST 接口直接执行，**不经过大模型**。
  4. 执行前再次校验权限和状态（防止期间数据变化）。
- **理由**：确认是确定性的服务端逻辑，模型或注入的文本都无法绕过（FR-021、FR-023、宪法 I）；也便于测试。
- **补充规则**：同一会话中用户发出新消息时，该会话中所有「待确认」的操作自动作废（spec 边界情况）。
- **备选方案**：让模型在下一轮对话中根据用户说「确认」再调用执行工具。依赖模型判断，可被提示词注入绕过，不可接受。

## R5. 知识库检索方式：作为工具由 Agent 调用

- **决策**：知识库检索实现为 `searchKnowledge` 工具，由模型按需调用；系统提示词要求「涉及公司制度、规定、流程的问题
  必须先调用 searchKnowledge」。返回 Top 5 片段，相似度阈值 0.5（实施时用评估问题集校准）。
- **引用来源的生成**：检索结果中的「文档名 + 章节标题 + 原文片段」由服务端直接收集，作为结构化的 `citations`
  随回答推送给页面，而不是依赖模型在文本里写出处。模型文本中只用「[1]」「[2]」等编号引用。
- **理由**：
  - 符合作业「AI 能根据用户问题判断需要调用哪些工具」的要求（FR-017）；综合分析场景需要和其他工具一起编排。
  - 出处由服务端生成，保证 FR-008 的可靠性，不会出现模型编造的出处。
- **备选方案**：每次对话都先做 RAG 检索并塞进提示词（Spring AI `QuestionAnswerAdvisor`）。数据查询类问题也会被塞入
  无关制度内容，浪费 Token 并干扰回答。

## R6. 数据存储：PostgreSQL + pgvector（单一数据库）

- **决策**：使用 PostgreSQL 16 + pgvector 扩展（镜像 `pgvector/pgvector:pg16`），业务数据、会话数据和向量都存在同一个库。
  向量表由 Flyway 迁移脚本创建（`vector(1024)`、HNSW 索引、余弦距离），Spring AI `PgVectorStore` 配置
  `initialize-schema=false`。
- **启动方式**：使用 Spring Boot 的 `spring-boot-docker-compose` 模块，执行 `./mvnw spring-boot:run` 时
  自动按 `compose.yaml` 启动数据库容器，满足「单条命令启动」。
- **理由**：
  - 一个数据库同时解决业务数据和向量存储，重启后知识库不丢失，不必每次启动都重新向量化（节省 API 调用，启动更快）。
  - 去重、替换同名文档可以直接用 SQL 事务完成（FR-006、FR-006a）。
  - 贴近企业真实技术栈，便于演示讲解。
- **代价**：需要本机安装 Docker。已在 plan 的复杂度追踪中说明。
- **备选方案**：H2 + Spring AI `SimpleVectorStore`（内存向量，可存文件）。完全不需要 Docker，但向量文件和 H2 数据要分别维护一致性，
  去重和替换要自己写文件逻辑；且 H2 与真实数据库行为有差异。

## R7. 知识库导入与去重

- **决策**：
  - 示例文档放在 `src/main/resources/knowledge/*.md`，应用启动完成后（`ApplicationReadyEvent`）自动导入。
  - `kb_document` 表记录文档名和内容 SHA-256。启动时：内容哈希相同 → 跳过；不同 → 在一个事务中删除旧片段、写入新片段。
  - 页面上传同名文档时走同样的替换逻辑（FR-006a）。
  - 切分：先按 Markdown 标题切成章节（每个片段在元数据中记录文档名、章节标题），章节过长时再按约 500 Token 切分，
    保留 50 Token 重叠。
  - 向量化按每批 ≤ 10 条调用（R2）。
- **失败处理**：导入失败（例如 API Key 未配置或百炼不可用）只记录错误日志，应用照常启动；`/actuator/health`
  中的自定义 `knowledgeBase` 指标显示不可用，检索工具返回「知识库暂不可用」，模型据此告知用户（spec 边界情况）。

## R8. 会话记忆

- **决策**：会话和消息存入自己的 `conversation`、`message` 表；每次请求把该会话最近 20 条消息（用户消息和助手最终回答）
  作为历史传给模型。工具调用的中间过程只存为步骤记录，不作为历史回放给模型。
- **理由**：页面展示会话列表和历史消息本来就需要这两张表，用同一份数据做记忆最简单；20 条足以覆盖「3 轮以上指代追问」（SC-005）。
- **备选方案**：Spring AI `ChatMemory` + JDBC 仓库。会出现两份消息数据，需要保持同步。

## R9. 登录与权限

- **决策**：
  - Spring Security 表单登录，基于 Session；密码用 BCrypt 存储；演示账号在 Flyway 种子数据中预置（密码为 BCrypt 哈希）。
  - 页面用 `fetch` 调用接口，CSRF 使用 `CookieCsrfTokenRepository`（前端从 `XSRF-TOKEN` Cookie 读取并放入请求头）。
  - 未登录访问页面 → 重定向到登录页；未登录访问 `/api/**` → 返回 401 JSON（页面据此跳转登录页）。
  - 权限在 Service 层校验：`TaskService`、`KnowledgeService` 接收显式的当前成员参数，不从线程上下文隐式获取。
  - Agent 调用工具时，通过 Spring AI 的 `ToolContext` 传入当前成员，工具再传给 Service。
- **理由**：显式传入当前用户，避免 SSE 异步线程丢失安全上下文；也让权限逻辑可以脱离 Spring Security 做单元测试。
- **备选方案**：JWT 无状态认证。对单体 Web 应用没有收益，反而要处理 Token 存储与刷新。

## R10. 进度推送：SSE

- **决策**：`POST /api/conversations/{id}/messages` 返回 `text/event-stream`，按顺序推送事件：
  `step_started`、`step_finished`、`pending_action`、`answer`、`error`、`done`（详见 [contracts/chat-stream-events.md](contracts/chat-stream-events.md)）。
  前端用 `fetch` + `ReadableStream` 解析（`EventSource` 只支持 GET）。服务端用 Spring MVC `SseEmitter`，
  Agent 循环在独立线程池中执行，超时 120 秒。
- **理由**：满足 SC-003「处理过程中页面持续显示进度」和 FR-020；不需要 WebFlux 或 WebSocket。
- **备选方案**：WebSocket（双向通信，对本场景多余）；轮询（实现简单但体验差）。
- **说明**：最终回答整段推送，不做逐字流式输出。逐字流式与自控工具循环结合会增加复杂度，而步骤进度已经解决了「等待时没反馈」的问题。

## R11. 前端：静态页面 + 原生 JavaScript

- **决策**：`src/main/resources/static/` 下的 `login.html`、`index.html`、`app.js`、`app.css`，不使用前端构建工具。
  引入一个随仓库提交的 `marked.min.js` 渲染模型输出的 Markdown（风险报告需要表格），并用 `DOMPurify` 净化 HTML。
- **理由**：宪法要求前端与后端打包在同一应用、一键启动；原生 JS 无需 Node 环境。
- **备选方案**：Vue / React 单页应用。需要 Node 构建链，超出「简单 Web 页面」的需要。

## R12. 测试策略

- **决策**：
  - 单元测试（JUnit 5 + AssertJ + Mockito）：状态流转、延期判定（注入固定的 `Clock`）、权限校验、工具参数校验、Agent 循环（stub 模型）。
  - 集成测试（Spring Boot Test + Testcontainers `pgvector/pgvector:pg16`）：Repository、知识库导入去重、待确认操作的完整流程。
  - 接口测试（MockMvc + spring-security-test）：每个接口覆盖正常、未登录（401）、无权限（403）三类。
  - 模型替身：测试配置中提供 `ChatModel` 替身（按脚本返回预设的工具调用和回答）和 `EmbeddingModel` 替身（基于文本哈希生成确定性向量）。
  - 默认构建 `./mvnw verify` 不需要 API Key，需要 Docker（Testcontainers）。
- **评估问题集**（宪法 II）：`eval/questions.yaml` 维护约 15 个问题（10 个制度问答、3 个知识库外问题、核心演示用例及其追问），
  由标记为 `@Tag("eval")` 的测试调用真实模型运行，只在 `./mvnw verify -Peval` 时执行，结果写入 `target/eval-report.md`。
  判定以关键词和引用文档名匹配为主，人工复核报告。

## R13. 可观测性

- **决策**：
  - 使用 Spring Boot 3.4+ 内置的结构化日志（`logging.structured.format.console=ecs`），无需额外依赖。
  - 过滤器为每个请求生成 `requestId` 放入 MDC；Agent 线程池提交任务时复制 MDC，保证模型和工具调用日志带同一个 ID。
  - Agent 循环中每次模型调用记录模型名、耗时、输入 / 输出 Token（取自 `ChatResponse` 的 usage 元数据）；每次工具调用记录工具名、耗时、是否成功。
  - Spring Boot Actuator 只开放 `health` 端点。
- **备选方案**：Logstash encoder + ELK、OpenTelemetry 分布式追踪。对单体本地演示没有必要（宪法 2.0.0 已删除该要求）。

## R14. 示例数据与「今天」

- **决策**：种子数据中的日期以「应用首次初始化的当天」为基准生成（Flyway Java 迁移 `V2__SeedDemoData`），
  保证任何时候启动都有预期数量的延期任务可以演示；延期判定统一使用注入的 `Clock`，测试中固定日期。
- **理由**：如果写死日期，过一段时间后所有任务都会变成延期，演示效果和验收场景（「项目 A 有 3 个延期任务」）都会失效。

## R15. 示例知识库内容提纲

实施阶段编写，内容 MUST 与 spec 中的规则一致：

- 《项目风险管理规定》：延期风险等级（延期 1–3 天为低风险；4–7 天为中风险；超过 7 天，或高优先级任务延期超过 3 天为高风险）、
  各等级应对措施、上报对象与时限、风险分析报告应包含的内容。
- 《项目管理制度》：任务状态定义与流转规则（与 FR-016a 完全一致）、优先级定义、里程碑与周报要求、任务指派规则。
