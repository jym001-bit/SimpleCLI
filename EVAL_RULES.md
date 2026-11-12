# EVAL_RULES

本文件是 SimpleCLI 评测的唯一口径来源。之后每个阶段开始前都要读这里，不得凭记忆改口径；口径要变，先改本文件并记下原因。

## 1. 基线

- **当前唯一有效基线**：`evals/baseline/baseline-opencode-go-v3.csv`
- v3 的两个来源字段（分开记，不合并）：
  - `agent_commit: 02750c8` —— 被测 agent 代码对应的提交
  - `eval_rules_commit: 31cc6cb` —— 当前生效的评测规则提交
- 说明：v3 数据在 `02750c8` 的规则下产出；`31cc6cb` 把 `timeout` 改为 invalid，v3 的 30 行全部是 `completed`，因此不受影响。
- 元数据见 `evals/baseline/baseline-opencode-go-v3.meta.json`。
- 任何“升级前后对照”都必须写明：**对比 v3 baseline（agent_commit 02750c8 / eval_rules_commit 02750c8）**，并把本次运行的 `agent_commit`、`eval_rules_commit` 记在报告里。
- runner 每次运行都会把这两个字段写进结果 JSON，并在启动时打印；可用 `--agent-commit` / `--eval-rules-commit` 覆盖，默认自动取 git 值。
- `baseline-opencode-go.csv`（v1）和 `baseline-opencode-go-v2.csv`（v2）**已废弃，仅作历史记录**，禁止用于对比或引用为成绩。
- v3 的数字不再重跑；需要新口径时新建版本号，不覆盖旧文件。

## 2. 有效性（valid）

只有“agent 得到完整机会跑完”的运行才算有效。

| exitReason | 含义 | valid |
|---|---|---|
| `completed` | 正常结束 | true |
| `budget_exhausted` | 请求预算耗尽（agent 自己的行为结果） | true |
| `stopped` | agent 自身兜底（硬轮数上限 / 停滞检测） | true |
| `error` | LLM 调用失败、网络异常、引擎异常 | false |
| `no_result` | 没生成结果 JSON | false |
| `timeout` | 超时（headless 自超时或 runner 硬超时） | false |
| `cancelled` | 外部取消（headless 没有 agent 自取消路径） | false |

判据实现：`com.paicli.eval.runner.RunValidity`。通过率、平均请求/token/耗时**只统计 valid=true 的运行**。

## 3. 重试

两类无效原因分开计数、分开限额：

| 类型 | 默认重试上限 | 参数 |
|---|---|---|
| 基础设施错误（`error` / `no_result`） | 3 次 | `--max-retries` |
| 超时（`timeout`） | 1 次 | `--timeout-retries` |

- 同一格：1 次初始 + 对应类型允许的重试次数。
- 每次无效尝试都**追加**写入 `invalid_runs.csv`（同表头，不覆盖、不删除）。
- 重试后仍无效：保留 `valid=false` 的记录，不计入统计。
- 只有无效才重试；`model_failed`（有效但验收不过）不重试。

## 4. 计时与超时

- runner 用 `System.nanoTime()` 对整段子进程计时，作为 `elapsedSeconds`。
- 单次硬超时默认 300 秒（`--hard-timeout`），触发即强杀进程，记 `timeout`、`valid=false`。
- headless 自身的 `--timeout` 取 `min(任务 timeout, 硬超时 - 5)`，保证优先由 headless 自己收尾。

## 5. 结果文件

- `--out`：JSON 汇总（含 `agentCommit` / `evalRulesCommit` 与每次运行的答案与错误）。
- CSV 只放运行数据，不混入元数据；元数据以 JSON 和报告文字为准。
- `--csv`：`taskId,run,attempt,passed,valid,outcome,requests,inputTokens,outputTokens,totalTokens,elapsedSeconds,exitReason`
- `--invalid-csv`：`taskId,run,attempt,exitReason,outcome,requests,totalTokens,elapsedSeconds,recordedAt,note`

## 6. 复现命令

```bash
mvn -B -o -DskipTests package
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.EvalRunnerMain \
  --tasks evals/tasks/tasks.json \
  --out evals/out/baseline.json --csv evals/out/baseline.csv \
  --env-file .env --provider opencode-go
```

单格补跑：

```bash
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.EvalRunnerMain \
  --tasks evals/tasks/tasks.json --only-task <taskId> --only-run <N> \
  --out evals/out/one.json --csv evals/out/one.csv --env-file .env --provider opencode-go
```

## 7. 禁止事项

- 不为让结果好看而改被测 agent 的行为、任务难度或验收标准。
- 不挑选运行、不删无效记录、不覆盖 `invalid_runs.csv`。
- 不重跑已定稿的基线数字；口径变了升版本。
- 评测代码只是工具，不得改动引擎/记忆/MCP/规划实现。
