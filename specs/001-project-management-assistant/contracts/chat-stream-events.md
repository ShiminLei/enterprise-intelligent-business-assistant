# 对话事件流契约（SSE）

**接口**：`POST /api/conversations/{conversationId}/messages`，响应 `Content-Type: text/event-stream`。
**相关需求**：FR-018、FR-019、FR-020、FR-021、SC-003。

每个事件由 `event:` 和一行 JSON `data:` 组成。事件严格按以下顺序出现：

```text
message_accepted
( step_started → step_finished )*      # 0 到 10 次
pending_action*                        # 在产生它的 step_finished 之后
answer | error                         # 二选一，恰好一次
done
```

## 事件定义

### message_accepted

用户消息已保存，开始处理。

```json
{ "userMessageId": 101, "expiredActionIds": [12] }
```

`expiredActionIds`：因本条新消息而作废的待确认操作，页面应把对应卡片的按钮置灰。

### step_started

助手开始调用某项能力。

```json
{ "seq": 1, "tool": "queryTasks", "label": "查询任务" }
```

### step_finished

```json
{ "seq": 1, "success": true, "resultSummary": "找到 3 个延期任务", "durationMs": 120 }
```

失败时 `success=false`，`resultSummary` 为中文原因，例如「知识库暂不可用」。

### pending_action

写操作工具通过校验后生成的待确认操作。页面展示操作说明与「确认 / 取消」按钮。

```json
{ "id": 13, "type": "CREATE_TASK", "summary": "在「项目A（客户门户升级）」中创建任务「跟进支付接口联调」，负责人张三，截止 2026-10-02，优先级高", "status": "PENDING" }
```

### answer

最终回答，一次性推送完整内容（不逐字流式，见 research R10）。

```json
{
  "assistantMessageId": 102,
  "content": "## 项目A 风险分析\n\n| 任务 | 延期天数 | 风险等级 |\n| --- | --- | --- |\n...\n依据《项目风险管理规定》[1] ……",
  "status": "COMPLETED",
  "citations": [
    { "index": 1, "documentName": "项目风险管理规定", "section": "二、延期风险等级", "excerpt": "……" }
  ]
}
```

`status` 取值：

| 值 | 含义 |
| --- | --- |
| `COMPLETED` | 正常完成 |
| `STEP_LIMIT_REACHED` | 工具调用达到 10 次上限，`content` 说明已完成的部分和停止原因（FR-019） |

### error

无法给出回答时（例如大模型服务不可用、超时）。已保存的用户消息保留，用户可以重试。

```json
{ "code": "MODEL_UNAVAILABLE", "message": "AI 服务暂时不可用，请稍后重试" }
```

`code` 取值：`MODEL_UNAVAILABLE`、`TIMEOUT`（120 秒）、`INTERNAL_ERROR`。

### done

流结束，无数据：`{}`。

## 页面行为约定

- 收到 `step_started` 立即显示「正在查询任务…」，收到 `step_finished` 更新为结果摘要或失败原因。
- `answer.content` 用 Markdown 渲染（经 DOMPurify 净化），`[n]` 对应下方引用列表中的第 n 条。
- 登录失效时接口直接返回 401（不建立事件流），页面跳转登录页，登录后回到原会话。
