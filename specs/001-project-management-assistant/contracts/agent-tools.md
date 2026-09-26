# Agent 工具契约

**相关需求**：FR-011 ~ FR-015、FR-017、FR-021 ~ FR-023。

这些是大模型可以调用的 Java 业务工具（作业要求至少 3 个，本功能共 6 个）。工具名称、说明和参数说明是
模型选择工具的唯一依据，属于「提示词即代码」，修改需同步更新本文档（宪法原则 II）。

## 通用约定

- **当前用户**：通过 Spring AI `ToolContext` 注入当前登录成员，工具参数中不出现用户身份，模型无法伪造。
- **返回格式**：统一返回 JSON 字符串 `{ "ok": true, "data": ... }` 或 `{ "ok": false, "error": "中文原因", "candidates": [...] }`。
  出错时不抛异常给模型，让模型据此向用户说明或追问（FR-022）。
- **日期**：`YYYY-MM-DD`；状态、优先级使用英文枚举值，工具说明中列出中文对照。
- **数量限制**：列表类结果最多返回 50 条，超过时返回 `total` 与提示「结果较多，请缩小范围」（spec 边界情况）。
- **写操作**：只生成待确认操作，不修改数据（research R4）。

## 工具清单

| 工具名 | 页面显示 | 类型 | 对应需求 |
| --- | --- | --- | --- |
| `findProjects` | 查询项目 | 读 | FR-011 |
| `queryTasks` | 查询任务 | 读 | FR-012、FR-016 |
| `searchKnowledge` | 检索知识库 | 读 | FR-007 ~ FR-009 |
| `proposeCreateTask` | 准备创建任务 | 写（提议） | FR-013、FR-014a |
| `proposeUpdateTaskStatus` | 准备修改任务状态 | 写（提议） | FR-014、FR-014a、FR-016a |
| `findMembers` | 查询成员 | 读 | 支撑按姓名指派任务 |

---

### findProjects

**说明（给模型）**：按名称关键字或编号查询项目；不传参数时返回全部项目。用于确认项目是否存在、获取项目编号、负责人和计划时间。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| keyword | string | 否 | 项目名称关键字或编号，例如「项目A」「P-001」 |

**返回 data**：`[{ "code", "name", "manager", "plannedStartDate", "plannedEndDate", "status", "taskCount", "overdueTaskCount" }]`
**无结果**：`ok=false`，`error="未找到匹配的项目"`，`candidates` 为全部项目名称（验收场景 US1-3）。

### queryTasks

**说明（给模型）**：按条件查询任务。「我的任务」请把 `assignee` 设为 `me`。「延期任务」请设 `overdueOnly=true`。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectCode | string | 否 | 项目编号，例如 `P-001` |
| status | string[] | 否 | `TODO`(待开始) / `IN_PROGRESS`(进行中) / `DONE`(已完成) / `CANCELLED`(已取消) |
| assignee | string | 否 | 负责人姓名，或 `me` 表示当前用户 |
| overdueOnly | boolean | 否 | 只返回延期任务，默认 false |

**返回 data**：`{ "total": 3, "items": [{ "code", "title", "projectCode", "assignee", "priority", "status", "dueDate", "overdueDays" }] }`
`overdueDays` 仅对延期任务给出（FR-016）。

### searchKnowledge

**说明（给模型）**：检索公司制度文档（风险管理规定、项目管理制度等）。凡是涉及公司规定、流程、标准的问题，MUST 先调用本工具，
并只根据返回内容作答；返回为空时如实告诉用户未找到相关规定。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| query | string | 是 | 检索语句 |

**返回 data**：`[{ "ref": 1, "documentName", "section", "content" }]`，最多 5 条，相似度 ≥ 0.5。
`ref` 在一次请求内全局递增，服务端同时把这些片段登记为本次回答的 citations，模型在正文中用 `[ref]` 引用。
**知识库不可用**：`ok=false`，`error="知识库暂不可用"`。

### findMembers

**说明（给模型）**：按姓名查询成员，用于创建任务前确认负责人。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| name | string | 是 | 姓名或部分姓名 |

**返回 data**：`[{ "name", "username", "role" }]`

### proposeCreateTask

**说明（给模型）**：为用户准备一个「创建任务」操作，交由用户在页面上确认后执行。缺少任何必填信息时，不要调用本工具，先向用户询问。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| projectCode | string | 是 | |
| title | string | 是 | 1 ~ 200 字 |
| assigneeName | string | 是 | 负责人姓名 |
| dueDate | string | 是 | 不早于今天 |
| priority | string | 是 | `HIGH`(高) / `MEDIUM`(中) / `LOW`(低) |

**校验顺序**：参数格式 → 项目存在 → 当前用户是该项目的项目经理 → 负责人唯一存在 → 截止日期。
**成功 data**：`{ "pendingActionId": 13, "summary": "…", "message": "已生成待确认操作，请提醒用户在页面上确认" }`，
同时推送 `pending_action` 事件。
**失败**：`ok=false`，例如 `error="只有项目A的负责人王经理可以在该项目中创建任务"`，不生成待确认操作（FR-014b）。

### proposeUpdateTaskStatus

**说明（给模型）**：为用户准备一个「修改任务状态」操作，交由用户确认后执行。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| taskCode | string | 是 | 例如 `T-102` |
| targetStatus | string | 是 | `TODO` / `IN_PROGRESS` / `DONE` / `CANCELLED` |

**校验顺序**：任务存在 → 权限（项目经理：自己负责的项目；团队成员：分配给自己的任务）→ 状态流转合法（FR-016a）。
**失败示例**：`error="任务 T-102 当前为「待开始」，不能直接变为「已完成」，需先变为「进行中」"`（验收场景 US4-7）。
