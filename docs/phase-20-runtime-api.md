# 第 20 期：异步后台任务 + Runtime API

> 当前状态：MVP 已落地。第 20 期补齐无头与后台执行入口；第 21 期 图片输入 已独立完成，不依赖本期 API。

## 已交付

### 后台任务

- `DurableTaskManager`：SQLite 持久化任务队列
- 默认数据库：`~/.paicli/tasks/tasks.db`
- 生命周期：
  - `enqueued`
  - `running`
  - `completed`
  - `failed`
  - `canceled`
- Worker Pool：默认 2 个后台 worker，可用 `PAICLI_TASK_WORKERS` 或 `-Dpaicli.task.workers` 覆盖
- 进程启动时把上次残留的 `running` 任务恢复为 `enqueued`
- CLI 命令：
  - `/task` 或 `/task list [N]`
  - `/task add <任务内容>`
  - `/task cancel <task_id>`
  - `/task log <task_id>`

### Runtime API

实现位于 `src/main/java/com/paicli/runtime/api/`，使用 JDK 内置 `HttpServer`，不引入 Spring / Javalin。

启动：

```bash
PAICLI_RUNTIME_API_KEY=your_local_api_key \
java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8080
```

安全策略：

- 仅监听 `127.0.0.1`
- 必须配置 `PAICLI_RUNTIME_API_KEY` 或 `-Dpaicli.runtime.api.key`
- 请求头支持：
  - `Authorization: Bearer <key>`
  - `X-PaiCLI-API-Key: <key>`

端点：

- `POST /v1/threads`：创建 thread
- `POST /v1/threads/{id}/turns`：提交一轮 Agent 输入，异步执行
- `GET /v1/threads/{id}/events`：以 SSE 格式回放事件

事件类型：

- `thread.created`
- `turn.started`
- `message.delta`
- `turn.completed`
- `turn.failed`
- `turn.canceled`

### 阶段十三：明确的终止原因

Headless 通过 `Agent.runWithOutcome` 返回现有 `ExecutionOutcome`，`TaskRunner` 不再只返回 String。只有 `COMPLETED` 映射 completed；`CANCELLED` 映射 canceled；预算停止和模型/执行错误映射 failed，已有正文和部分结果仍然保留。

SQLite 任务记录新增可空 `reason` 字段，旧表自动增列，旧行保留 null，不根据历史文字推断状态。`/task log` 显示已知原因。API 保留原端点、提交响应及 message.delta 的 content；终止事件包含 `turn_id`、`status`、`reason`，失败/取消保留 error。预算停止例：

```json
{"turn_id":"turn_example","status":"failed","reason":"HARD_ITERATION_LIMIT","error":"部分完成内容"}
```

取消后的迟到结果不能覆盖终态；返回 CANCELLED 的同一操作可补存实际部分输出而不改变终态时间。没有增加 API cancel 端点，也没有把事件回放改成实时流式接口。验证与未迁移范围见 [阶段十三记录](harness-stage13-2026-10-28.md)。

阶段十四为后台任务和 API turn 绑定独立根任务取消信号，避免线程池复用时借用其他任务的 Token。后台 `/task cancel` 最多等待 2 秒确认执行线程退出，并通过 SQLite `cleanup_state` 和 `/task log` 显示结果；`canceled` 仍表示取消决定，不等于清理完成。API 没有取消端点，作用域只提供隔离。详见 [阶段十四记录](harness-stage14-2026-10-29.md)。

## 当前边界

- Runtime API MVP 是事件回放式 SSE，不做长连接持续阻塞推送
- 后台任务 runner 使用 headless ReAct Agent，不复用交互式 TUI 的 HITL 输入
- 后台任务取消通过线程中断 + 状态标记实现；正在进行的远端 LLM HTTP 调用能否立即停止取决于底层 client 边界
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径

## 验证

```bash
mvn test -Dtest=DurableTaskManagerTest,RuntimeApiServerTest,CliCommandParserTest
```

建议回归：

```bash
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
PAICLI_RUNTIME_API_KEY=test java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 0
```
