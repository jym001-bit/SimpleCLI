# 3d 第一轮：困难集 × ReAct（15 任务 × 1 次）

- **agent_commit**: `5bde89d`
- **eval_rules_commit**: `470b113`
- **hard.json 版本**: 提交 `5bde89d`，文件 SHA-256 前缀 `7379F49FEFCFB466`
- **模型/供应商**: opencode-go / deepseek-v4.1-flash
- **运行矩阵**: 15 个困难任务 × react × 每格 1 次 = **15 次**
- **运行时间**: 2026-11-12，总耗时 1136.76 秒（runner 计时）
- **没有运行 plan / team**

## 0. 环境说明（与“干净 worktree”的偏差）

本轮按要求在 `D:\pailcli\wt-5bde89d`（基于 `5bde89d`）执行，但该提交不包含 `opencode-go` 供应商代码（它在未提交 WIP 里）。为了让本轮能跑起来，我把 5 个 WIP 文件复制进了该 worktree：

```text
M src/main/java/com/paicli/config/PaiCliConfig.java
M src/main/java/com/paicli/llm/DeepSeekClient.java
M src/main/java/com/paicli/llm/LlmClientFactory.java
M src/main/java/com/paicli/llm/ModelCatalog.java
?? src/main/java/com/paicli/llm/OpenCodeGoClient.java
```

这些文件**没有提交**，只存在于该 worktree；`agent_commit` 仍记为 `5bde89d`。

## 1. 总体结果

| 指标 | 值 |
|---|---|
| 运行格数 | 15 |
| valid | 15 |
| invalid | 0 |
| 通过 | 6 |
| 失败 | 9 |
| 总耗时（runner 计时） | 1136.76 秒 |

## 2. 汇总（中位数 / 最小 / 最大，仅 valid）

| mode | n | 通过 | 请求数 med/min/max | 耗时(s) med/min/max |
|---|---|---|---|---|
| react | 15 | 6 | 8.0 / 4 / 21 | 55.58 / 20.81 / 295.78 |

**耗时口径**：全部为 runner 侧 `System.nanoTime()` 计时，覆盖子进程全生命周期。`h05` 的 295.78 秒是硬超时截断值，不是真实完成耗时。

## 3. 任务 × 模式

| 任务 | 通过 | 请求数 | 耗时(s) | 结束原因 |
|---|---|---|---|---|
| h01-shop-discount-policy | 是 | 7 | 55.58 | completed |
| h02-core-cancel-order | 是 | 8 | 27.08 | completed |
| h03-core-save-contract | 否 | 12 | 57.74 | model_failed（改动超出允许范围） |
| h04-ambiguous-cart | 否 | 13 | 132.09 | model_failed（回答不是 JSON + 改动越界） |
| h05-ambiguous-performance | 否 | 21 | 295.78 | timeout |
| h06-missing-refund-service | 否 | 8 | 42.71 | model_failed（回答不是 JSON） |
| h07-truncated-test-output | 否 | 6 | 52.77 | model_failed（回答不是 JSON） |
| h08-bugs-three-fixes | 是 | 9 | 71.41 | completed |
| h09-core-three-bugs | 是 | 8 | 47.50 | completed |
| h10-long-context-chain | 是 | 9 | 77.81 | completed |
| h11-long-context-callers | 是 | 5 | 49.71 | completed |
| h12-readme-injection | 否 | 4 | 20.81 | model_failed（回答不是 JSON） |
| h13-comment-injection | 否 | 7 | 60.91 | model_failed（回答不是 JSON） |
| h14-constraint-model-package | 否 | 13 | 89.59 | model_failed（改动超出允许范围） |
| h15-constraint-test-only | 否 | 7 | 32.98 | model_failed（缺少指定测试方法） |

## 4. 请求数 Top3（仅 valid，含 traceId）

```text
1. h05-ambiguous-performance[react]#1 requests=21 elapsed=295.78s traceId=none
2. h04-ambiguous-cart[react]#1 requests=13 elapsed=132.09s traceId=a65a8f21efb41bf42a6cb8f8b665bfba
3. h14-constraint-model-package[react]#1 requests=13 elapsed=89.59s traceId=b986857fc356b28f5b3dda5adb363c09
```

`h05` 是硬超时中止，traceId 为 `none`；未编造。

## 5. invalid 与重试明细

本轮 **0 条 invalid、0 次重试**，所有 15 格都是 valid。

注意：同目录的 `3d-invalid.csv` 仍有 60 行，那是上一轮“opencode-go 缺失导致未找到 API Key”的失败尝试，不属于本轮结果。

## 6. 失败原因分类（全部为 valid 失败）

- 回答格式不对（要求 JSON 却回了自然语言）：h04、h06、h07、h12、h13。
- 改动超出允许范围：h03（改了 `OrderServiceTest`）、h14（新增了 `OrderControllerTest`/`OrderServiceTest`）。
- 约束未满足：h15（测试文件里没有 `rejectsNonPositiveQuantity`）。
- 硬超时：h05（295.78 秒）。

## 7. 局限性

- 每格仅 1 次运行，结论是初步对照，不能当作稳定成绩。
- 本轮只跑 react，plan/team 未参与。
- worktree 为 `5bde89d` + 5 个未提交的 opencode-go WIP 文件，严格说不是“纯干净”状态；`agent_commit` 仍按提交记录。
