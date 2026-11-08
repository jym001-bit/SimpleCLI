# 第二阶段：真实模型评测与 Langfuse 云端 Trace 报告

日期：2026-10-26（Asia/Shanghai）

## 结论与口径

- 真实模型：DeepSeek `deepseek-v4-flash`。本次 12 个任务 ID 的最新执行结果为 12/12 通过（100%）；这不是一次 12 个任务全在单批次内成功的声明。第一轮完整批次遭遇 DNS 错误；修复后的数据集 v1 为 11/12，唯一失败 L08 是上下文过短导致压缩器正确跳过压缩；扩大 L08 上下文后单独复测通过。最后又重跑 L11 验证失败 Trace 分类。修复后共 13 次任务尝试，12 次通过，1 次失败，失败是已复测成功的 L08 初始压缩场景。
- 离线确定性回归：35/35 通过，Maven exit 0。它与真实模型任务分别统计。
- Langfuse 云端：12 个最新任务 Trace 均能通过官方 Public API 读回结构；不是仅凭 OTLP HTTP 成功响应作结论。

## 真实模型指标

任务成功以每个用例的明确判定器为准：简单回答精确匹配；工具任务检查实际工具/路径/返回值/调用次数和最终回答；失败恢复检查真实失败工具结果及严格 JSON；压缩和记忆任务检查实际状态及记忆事实。没有使用 LLM-as-a-Judge，也没有把关键词命中单独作为成功标准。仅提供 `read_file` 工具，任务使用隔离的合成文件。

| 指标 | 最新 12 个用例结果 |
|---|---:|
| 成功 / 用例数 | 12 / 12（100%） |
| 逻辑模型调用 | 29 |
| 工具调用 | 12 次，其中失败工具调用 3 次 |
| Provider 输入 Token | 104,428（缓存未命中 9,452；缓存命中 94,976） |
| 输出 Token | 3,375 |
| 端到端耗时 | 合计 41.547 秒；单任务均值 3.462 秒 |
| 估算成本 | USD 0.007455456 |

Token 数来自 DeepSeek 响应。供应商的 `prompt_tokens` 含缓存 Token，因此成本估算把缓存命中数从总输入中扣除后，分别按缓存未命中、缓存命中、输出价格计费；价格快照日期为 2026-10-26、峰时档，估算并非账单。当前官方 OTLP 接入使用 `/api/public/otel/v1/traces`、OTLP/HTTP、Basic Auth 和 `x-langfuse-ingestion-version: 4`。[Langfuse OTel 接入文档](https://langfuse.com/integrations/native/opentelemetry)；[DeepSeek 官方价格](https://api-docs.deepseek.com/quick_start/pricing/)。

## 云端 Trace 验收

云端读回的 12/12 条 Trace 均有且仅有一个根 Observation；Agent、模型和工具父子链有效，无悬空父节点；12/12 的 `task_id`、工具调用 ID 与实际结果相符，时间顺序有效。29/29 个模型 Observation 的输入/缓存/输出 Usage 与真实模型响应相符。未发现非空 Observation input/output、合成文件哨兵或凭据字符串。失败工具 Trace 中的工具 Observation 状态为 ERROR；只记录安全错误类别，不上传路径、参数、文件内容或原始异常正文。并行两任务烟测得到各自独立 Trace，父子关系和 task ID 均匹配。

结构示例：

- 简单问答：Trace `6be1ee2d692a7996fe276c106de7592a`，1 根 Trace → Agent → 1 个模型 Generation；无工具。
- 单工具读取：Trace `0a6826900f9a8dce34d143e6019ec7b4`，1 根 Trace → Agent → 2 个模型 Generation 与 1 个工具 Observation，工具调用 ID 匹配模型决策。
- 受控失败恢复：Trace `9c22ede59587a308116e687dcca050b2`，1 根 Trace → Agent → 2 个模型 Generation 与 1 个 ERROR 工具 Observation；`tool_error_type=READ_FILE_FAILED`，未带文件路径或异常正文。
- 并行烟测 Trace：`d945df9c2d8ca5c28f93896ef3945884` 与 `c15a3e8966e69ab547760811dc7bf623`，两个任务各自只有一个根 Trace，没有串线。

## 已确认问题及修复

1. 缺失文件的 `read_file` 返回中文失败文本，但 ToolRegistry 原来未把该文本归类为失败，导致 Observation 被记成成功。加入该失败前缀识别，并新增 `ToolRegistryTest.missingReadFileIsRecordedAsTypedToolFailure`。云端复核现为 ERROR，脱敏错误类别 `READ_FILE_FAILED`。
2. Langfuse Usage 曾把含缓存的输入总量同时写入 `input` 与 `input_cached`，云端展示可能重复计数。现将输入拆成互斥的 cache-miss 与 cached buckets；29/29 个 Generation 的云端 Usage 总和与 Provider 响应吻合。新增相应序列化与脱敏测试。
3. 为评测关联，任务 ID 跨根 Trace/子 Observation 传递，模型决策与工具 Observation 记录匹配的工具调用 ID；模型与工具错误只上报安全分类。

直接相关 `LangfuseTelemetryTest` 和新增 `ToolRegistryTest` 定向用例通过（4 + 1 个测试）。35 项离线 Harness 用例全部通过。此前 `mvn test -Pquick` 的 143 断言失败和 600 错误已在未改动 HEAD 基线复现，故归类为既有/基线问题，本轮没有据此绕过或降低测试。

## 根因分类与限制

- **本轮确认并修复：** 工具失败识别错误；OTel 缓存输入桶重复计数。
- **环境/网络：** 最早的真实模型批次在两例后发生 `api.deepseek.com` DNS 失败，剩余任务未能请求模型；这些属于 `MODEL_IO_ERROR`，不纳入任务质量成功率。凭据存在且后续模型与云端请求成功。
- **Harness 任务设计反馈：** L08 初始上下文不足以产生净压缩，Agent 正确跳过压缩；扩大上下文后真实触发压缩（3,545 → 3,316 字符）并保留答案。作为一次失败尝试保留，不删除。
- **未验证：** Provider 内部 HTTP 重试次数；其他模型/价格档；Team/Plan worker 的并行父子 Trace。并行验证仅覆盖两个独立用户任务。云端敏感信息扫描只针对已配置凭据和合成哨兵字符串。
- **失败测试剩余项：** 完整 `mvn test -Pquick` 仍有 143 断言失败、600 错误，均可在干净的未改动 HEAD 基线上重现；这轮仅重跑相关定向测试和离线 Harness，没有把基线失败归到本轮改动。

## 可复核产物

- 真实评测完整结果（任务 ID、每次结果、Trace ID、Token、耗时、成本及历史尝试）：[`real-model-evaluation-2026-10-26.json`](../benchmarks/harness-live/results/real-model-evaluation-2026-10-26.json)
- 离线 35 项结果：[`phase2-offline-2026-10-26.json`](../benchmarks/harness-offline/results/phase2-offline-2026-10-26.json)
- 12 条云端 Trace 结构审计及并行烟测记录：包含在真实评测 JSON 的 `allObservedRuns` 中。
- 任务集：[`cases.json`](../benchmarks/harness-live/cases.json)；运行器：[`run-harness-live-eval.ps1`](../tools/run-harness-live-eval.ps1)；云端核验器：[`verify-harness-live-traces.ps1`](../tools/verify-harness-live-traces.ps1)。

## 下一轮优先项

暂不建议再改 Agent Loop。应先对 L08 压缩任务做多次固定数据集重复运行，并为模型调用延迟、缓存命中和成功率建立按模型/任务类别的分布；当前样本量不足以判断偶发质量波动。若后续要验 Team/Plan 并行 Trace，应在隔离模拟工具下单独增加云端 Trace 结构评测。
