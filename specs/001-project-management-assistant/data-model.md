# Data Model: 项目管理智能助手

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-26

数据库为 PostgreSQL 16 + pgvector，表结构由 Flyway 迁移脚本管理（宪法「技术与平台约束」）。
表名、字段名使用英文 snake_case；枚举值以英文存储，页面和模型输出时映射为中文。

## 实体关系概览

```text
member 1 ──< project (manager_id)
member 1 ──< task (assignee_id)
project 1 ──< task
member 1 ──< conversation
conversation 1 ──< message
conversation 1 ──< pending_action
message 0..1 ──< pending_action (message_id)
kb_document 1 ──< vector_store（通过 metadata.document_id 关联）
```

## member（成员 / 登录账号）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| username | varchar(50) | NOT NULL, UNIQUE | 登录账号 |
| password_hash | varchar(100) | NOT NULL | BCrypt 哈希，MUST NOT 存明文（FR-004b） |
| name | varchar(50) | NOT NULL | 中文姓名，例如「张三」 |
| role | varchar(20) | NOT NULL | `PROJECT_MANAGER` / `TEAM_MEMBER` |

**规则**：按姓名解析成员时（例如「给张三创建任务」），重名 → 返回候选列表让用户确认；查不到 → 返回错误。

## project（项目）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| code | varchar(20) | NOT NULL, UNIQUE | 例如 `P-001` |
| name | varchar(100) | NOT NULL | 例如「项目A」 |
| description | varchar(500) | | |
| manager_id | bigint | NOT NULL, FK → member | 负责人，角色必须是项目经理 |
| planned_start_date | date | NOT NULL | |
| planned_end_date | date | NOT NULL | ≥ planned_start_date |
| status | varchar(20) | NOT NULL | `PLANNING` / `IN_PROGRESS` / `COMPLETED` |

**规则**：按名称查询支持模糊匹配；匹配到多个 → 返回候选列表（spec 边界情况：项目名称有歧义）。

## task（任务）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| code | varchar(20) | NOT NULL, UNIQUE | 例如 `T-101`，新建时按序生成 |
| project_id | bigint | NOT NULL, FK → project | |
| title | varchar(200) | NOT NULL | 不能为空白 |
| assignee_id | bigint | NOT NULL, FK → member | |
| priority | varchar(10) | NOT NULL | `HIGH` / `MEDIUM` / `LOW` |
| status | varchar(20) | NOT NULL | 见下方状态机，新建为 `TODO` |
| due_date | date | NOT NULL | 计划截止日期，新建时不得早于今天 |
| completed_date | date | | 变为 `DONE` 时记录当天；否则为空 |
| created_at | timestamptz | NOT NULL | |
| updated_at | timestamptz | NOT NULL | |

### 任务状态机（FR-016a）

| 英文值 | 中文 |
| --- | --- |
| `TODO` | 待开始 |
| `IN_PROGRESS` | 进行中 |
| `DONE` | 已完成 |
| `CANCELLED` | 已取消 |

允许的状态变化（其余一律拒绝并说明原因）：

```text
TODO ──────────► IN_PROGRESS ──────────► DONE
  │                  │   ▲
  │                  │   └── (退回) IN_PROGRESS ──► TODO
  ▼                  ▼
CANCELLED ◄──────────┘
```

| 从 | 可以变为 |
| --- | --- |
| TODO | IN_PROGRESS、CANCELLED |
| IN_PROGRESS | DONE、TODO、CANCELLED |
| DONE | （终态） |
| CANCELLED | （终态） |

### 延期判定（FR-016）

`status ∈ {TODO, IN_PROGRESS}` 且 `due_date < today`；延期天数 = `today - due_date`。
`today` 取自注入的 `Clock`（时区 Asia/Shanghai），测试中固定。

### 写操作权限（FR-014a）

| 操作 | 项目经理 | 团队成员 |
| --- | --- | --- |
| 查询所有项目和任务 | 允许 | 允许 |
| 创建任务 | 仅限自己负责的项目 | 拒绝 |
| 修改任务状态 | 仅限自己负责的项目中的任务 | 仅限分配给自己的任务 |
| 上传知识库文档 | 允许 | 拒绝 |

## conversation（会话）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| owner_id | bigint | NOT NULL, FK → member | 用户只能访问自己的会话 |
| title | varchar(100) | | 取首条消息前 30 个字 |
| created_at | timestamptz | NOT NULL | |
| updated_at | timestamptz | NOT NULL | 列表按此倒序 |

## message（消息）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| conversation_id | bigint | NOT NULL, FK → conversation | |
| role | varchar(10) | NOT NULL | `USER` / `ASSISTANT` |
| content | text | NOT NULL | 助手消息为 Markdown |
| steps | jsonb | | 仅助手消息：执行步骤列表，见下 |
| citations | jsonb | | 仅助手消息：引用列表，见下 |
| status | varchar(20) | NOT NULL | `COMPLETED` / `FAILED` / `STEP_LIMIT_REACHED` |
| created_at | timestamptz | NOT NULL | |

**steps 元素（执行步骤，FR-020 / FR-024）**：
`{ "seq": 1, "tool": "queryTasks", "label": "查询任务", "input": {...}, "resultSummary": "找到 3 个延期任务", "success": true, "durationMs": 120 }`

**citations 元素（FR-008）**：
`{ "index": 1, "documentName": "项目风险管理规定", "section": "二、延期风险等级", "excerpt": "……原文片段……" }`

**记忆规则**：请求模型时，取该会话最近 20 条 `USER` / `ASSISTANT` 消息作为历史（research R8）。

## pending_action（待确认操作，FR-021；同时作为写操作审计记录）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| conversation_id | bigint | NOT NULL, FK → conversation | |
| message_id | bigint | FK → message，可为空 | 生成该操作的助手消息；在该条助手消息保存后回填 |
| requested_by | bigint | NOT NULL, FK → member | 发起人（当前登录用户） |
| type | varchar(30) | NOT NULL | `CREATE_TASK` / `UPDATE_TASK_STATUS` |
| payload | jsonb | NOT NULL | 操作参数（已解析为 ID），例如 `{ "projectId": 1, "title": "...", "assigneeId": 3, "dueDate": "2026-10-02", "priority": "HIGH" }` |
| summary | varchar(500) | NOT NULL | 给用户看的中文描述 |
| status | varchar(20) | NOT NULL | 见下方状态机 |
| result | varchar(500) | | 执行结果，例如「已创建任务 T-131」或失败原因 |
| created_at | timestamptz | NOT NULL | |
| resolved_at | timestamptz | | 确认 / 取消 / 作废的时间 |
| resolved_by | bigint | FK → member | 执行确认或取消的人（必须等于 requested_by） |

### 待确认操作状态机

```text
PENDING ──确认且执行成功──► EXECUTED
PENDING ──确认但执行时校验失败──► FAILED
PENDING ──用户取消──► CANCELLED
PENDING ──同会话出现新的用户消息──► EXPIRED
```

**规则**：
- 只有 `requested_by` 本人可以确认或取消。
- 确认时 MUST 重新校验权限和任务状态流转（数据可能已被他人修改）。
- 非 `PENDING` 状态的操作再次确认 → 返回 409，不重复执行。

## kb_document（知识文档）

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | bigint | PK | |
| name | varchar(200) | NOT NULL, UNIQUE | 文档名（取文件名去掉扩展名），同名上传即替换 |
| source | varchar(20) | NOT NULL | `BUILT_IN`（启动自动导入）/ `UPLOADED` |
| content_hash | char(64) | NOT NULL | 内容 SHA-256，用于启动时去重 |
| chunk_count | int | NOT NULL | |
| uploaded_by | bigint | FK → member | 内置文档为空 |
| imported_at | timestamptz | NOT NULL | |

**校验**：仅接受 `.md`，UTF-8，非空，不超过 1 MB。

## vector_store（知识片段，Spring AI PgVectorStore 标准表结构）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | uuid | PK |
| content | text | 片段原文 |
| metadata | json | `{ "document_id": 1, "document_name": "项目风险管理规定", "section": "二、延期风险等级", "chunk_index": 3 }` |
| embedding | vector(1024) | text-embedding-v4 生成；HNSW 索引，余弦距离 |

**规则**：替换文档时，在同一事务中按 `metadata->>'document_id'` 删除旧片段，再写入新片段并更新 `kb_document`。

## 示例数据（FR-010）

由 Flyway Java 迁移生成，日期以初始化当天（记为 D）为基准（research R14）。所有演示账号初始密码为 `demo123`。

| 账号 | 姓名 | 角色 | 说明 |
| --- | --- | --- | --- |
| wangjl | 王经理 | 项目经理 | 负责项目A |
| lijl | 李经理 | 项目经理 | 负责项目B、项目C |
| zhangsan | 张三 | 团队成员 | |
| lisi | 李四 | 团队成员 | |
| zhaoliu | 赵六 | 团队成员 | |

| 编号 | 名称 | 负责人 | 任务数 | 延期任务 |
| --- | --- | --- | --- | --- |
| P-001 | 项目A（客户门户升级） | 王经理 | 10 | 3 个：延期 2 天（低）、5 天（中）、高优先级延期 9 天（高） |
| P-002 | 项目B（数据中台建设） | 李经理 | 10 | 1 个：延期 4 天（中） |
| P-003 | 项目C（移动办公 App） | 李经理 | 10 | 0 个（用于验证「无延期任务」场景） |

每个项目的任务覆盖全部 4 种状态和 3 种优先级，分配给不同成员，保证「我的任务」查询对每个成员都有结果。
