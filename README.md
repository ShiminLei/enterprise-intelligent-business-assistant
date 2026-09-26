# 企业智能业务助手 · 项目管理助手

企业级 Java + AI 项目实战营作业（三）· 题目二「综合难度——企业智能业务助手」。

## 业务场景

项目经理和团队成员日常要在多个地方来回查信息：项目进度、任务状态在项目管理系统里，风险管理规定、项目管理制度在内部文档里。回答「项目A现在有什么风险？」这类问题，往往要人工查多份数据、对照制度，再手工整理成报告。

本项目是一个**项目管理智能助手**：用户登录后用自然语言提出需求，助手自行判断要查询哪些业务数据、检索哪些公司制度，必要时连续调用多个 Java 业务工具，最后给出带出处的回答；还能在用户确认后创建任务、修改任务状态。

## 已实现的功能

| 作业要求 | 实现情况 |
| --- | --- |
| **AI 对话** | Web 对话页面，支持多轮对话（同一会话内保留最近 20 条消息作为上下文，可以追问「其中优先级最高的是哪个？」）；可新建会话 |
| **企业知识库** | 内置《项目风险管理规定》《项目管理制度》两份文档，启动时自动切分、向量化并导入 PostgreSQL + pgvector；项目经理可在页面上传新的 Markdown 文档；回答制度问题时展示引用的文档名、章节和原文片段，找不到依据时明确说明 |
| **Tool Calling（≥ 3 个 Java 工具）** | 6 个工具：查询项目、查询任务、查询成员、检索知识库、准备创建任务、准备修改任务状态 |
| **业务数据库** | 成员、项目、任务三类业务数据（3 个项目、30 个任务，含延期任务），由 Flyway 自动建表并初始化 |
| **Agent 任务处理** | 单 Agent 自行规划工具调用顺序，可连续调用多个工具后综合作答；单次请求最多 10 次工具调用；页面实时显示每一步 |

另外还实现了：

- **登录与权限**：账号密码登录；项目经理只能在自己负责的项目中创建任务、修改任务状态，团队成员只能修改分配给自己的任务。
- **写操作必须确认**：创建任务、修改状态的工具只生成「待确认操作」，用户在页面上点击「确认」后才由后端执行，不经过大模型，提示词注入也无法绕过。
- **任务状态流转规则**：待开始 → 进行中 → 已完成，不允许的变化会被拒绝并说明原因。
- **可追溯**：每条回答保存了调用的工具、每步结果和引用来源；日志中每个请求有唯一的 requestId，并记录每次模型调用的耗时和 Token 用量。

## 主要技术

| 类别 | 选型 |
| --- | --- |
| 语言与框架 | Java 21、Spring Boot 3.5、Spring Security、Spring Data JPA |
| AI 框架 | Spring AI 1.1（OpenAI 兼容协议，便于替换模型提供商） |
| 大模型 | 阿里云百炼：`qwen-plus`（对话与工具调用）、`text-embedding-v4`（向量化，1024 维） |
| 数据库 | PostgreSQL 16 + pgvector（业务数据、会话与向量同库），Flyway 管理表结构与示例数据 |
| 前端 | 静态页面 + 原生 JavaScript，marked 渲染 Markdown、DOMPurify 净化 HTML，SSE 实时推送处理进度 |
| 测试 | JUnit 5、Mockito、MockMvc、Testcontainers；模型调用用替身代替，测试不需要 API Key |

### 处理流程

```text
浏览器 ──SSE──► ConversationController ──► AgentService（自控的工具调用循环，最多 10 次）
                                               │
                     ┌─────────────────────────┼──────────────────────────┐
                     ▼                         ▼                          ▼
             qwen-plus（百炼）         Java 业务工具（6 个）          PostgreSQL + pgvector
                                     查询项目 / 任务 / 成员            业务数据、会话
                                     检索知识库（text-embedding-v4）    知识片段向量
                                     准备创建任务 / 修改状态 ──► 待确认操作 ──► 用户点击确认 ──► 执行
```

## 快速开始

### 1. 准备

| 项目 | 要求 |
| --- | --- |
| JDK | 21 或更高版本（`java -version`） |
| Docker | Docker Desktop 或兼容运行时，已启动 |
| 阿里云百炼 API Key | 一个即可，对话和向量化共用 |

**申请百炼 API Key**：登录 [阿里云百炼控制台](https://bailian.console.aliyun.com/)，开通服务后在「API-KEY 管理」中创建一个 API Key；确认账号可以调用 `qwen-plus` 与 `text-embedding-v4`（新用户通常有免费额度）。

### 2. 启动

```bash
export DASHSCOPE_API_KEY=sk-xxxx   # 只通过环境变量提供，不要写进任何文件
./mvnw spring-boot:run             # 自动启动 compose.yaml 中的 PostgreSQL + pgvector
```

首次启动会下载 Maven 与依赖、拉取数据库镜像，并导入知识库。看到日志「知识库导入完成」后，打开 <http://localhost:8080>。

- 健康检查：`curl localhost:8080/actuator/health`，`db` 与 `knowledgeBase` 均为 `UP`。
- 数据库容器的宿主机端口是随机分配的，不会与本机已有的 PostgreSQL 冲突。
- 需要重置演示数据时（示例数据的日期以首次初始化当天为基准）：`docker compose down -v`，再重新启动。
- 如果使用百炼业务空间专属域名，可设置 `DASHSCOPE_BASE_URL`；更换对话模型可设置 `CHAT_MODEL`（例如 `qwen-max`）。

### 3. 演示账号

所有账号的初始密码都是 `demo123`。

| 账号 | 姓名 | 角色 |
| --- | --- | --- |
| `wangjl` | 王经理 | 项目经理（负责项目A） |
| `lijl` | 李经理 | 项目经理（负责项目B、项目C） |
| `zhangsan` | 张三 | 团队成员 |
| `lisi` | 李四 | 团队成员 |
| `zhaoliu` | 赵六 | 团队成员 |

## 使用示例

以 `wangjl` 登录：

1. **查询与追问**：「项目A有哪些延期任务？」→「其中优先级最高的是哪个？」
2. **制度问答**：「任务延期超过几天算高风险？」——回答下方显示引用的规定原文，点击正文中的 [1] 可定位到出处。
3. **核心演示用例**：

   > 查询项目A目前有哪些延期任务，并结合公司的风险管理规定生成一份风险分析。

   页面依次显示「查询项目 → 查询任务 → 检索知识库」三个步骤，最后输出包含延期任务清单、风险等级、判定依据（带出处）和应对措施的风险分析报告。
4. **写操作**：「帮我给张三创建一个任务，跟进其中风险最高的那项，周五前完成。」→ 页面出现待确认卡片 → 点击「确认执行」后任务才会被创建。
5. **权限**：以 `zhangsan` 登录，请求「在项目A创建一个任务」会被拒绝，并说明原因。

完整的验收场景见 [quickstart.md](specs/001-project-management-assistant/quickstart.md)。

## 测试

```bash
./mvnw verify           # 单元测试、集成测试、接口测试；需要 Docker，不需要 API Key
./mvnw verify -Peval    # 额外运行评估问题集（调用真实模型，需要 DASHSCOPE_API_KEY），报告在 target/eval-report.md
```

评估问题集在 [eval/questions.yaml](eval/questions.yaml)，覆盖制度问答、知识库外问题、核心演示用例（运行 5 次）、多轮指代追问、缺少信息时的追问和超出服务范围的请求。

## 项目结构

```text
src/main/java/com/enterprise/assistant/
├── agent/          # Agent 循环、工具调用记录、系统事件
│   └── tools/      # 6 个大模型可调用的业务工具
├── action/         # 待确认操作：确认 / 取消 / 作废
├── conversation/   # 会话、消息、SSE 对话接口
├── knowledge/      # 知识库导入、切分、检索、上传接口
├── task/ project/ member/   # 业务数据、状态流转、权限
├── security/       # 登录与访问控制
└── common/         # 错误处理、requestId、线程池
src/main/resources/
├── db/migration/   # Flyway 表结构（示例数据在 src/main/java/db/migration）
├── knowledge/      # 内置制度文档
├── prompts/        # 系统提示词
└── static/         # 前端页面
```

## 文档

本项目按 Spec Kit 的规格驱动开发（SDD）流程完成：

| 文档 | 说明 |
| --- | --- |
| [PRD](docs/enterprise-intelligent-business-assistant-prd.md) | 产品需求（由作业要求整理） |
| [项目宪法](.specify/memory/constitution.md) | 开发原则：安全、有据可依的 AI、测试先行、可观测性、简单性 |
| [spec.md](specs/001-project-management-assistant/spec.md) | 功能规格：用户故事、功能需求、成功标准 |
| [plan.md](specs/001-project-management-assistant/plan.md) · [research.md](specs/001-project-management-assistant/research.md) | 实现计划与技术决策 |
| [data-model.md](specs/001-project-management-assistant/data-model.md) · [contracts/](specs/001-project-management-assistant/contracts/) | 数据模型、接口与工具契约 |
| [tasks.md](specs/001-project-management-assistant/tasks.md) | 任务清单 |
| [AI 编程说明](docs/ai-programming.md) | 使用的 AI 编程工具与典型协作案例 |
