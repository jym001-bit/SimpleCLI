# EVAL_RULES

## 一、baseline

- v3 为正式 baseline；v1、v2 保留在仓库中，文档标注"已废弃，仅作历史记录"。
- 每次对照必须写明对比的 baseline 版本及其 commit hash（当前为 02750c8 或之前对应提交）。

补充记录（两个字段分开记，不合并）：

- `agent_commit = 02750c8`
- `eval_rules_commit = 31cc6cb`

## 二、耗时口径

- 历史 29 格耗时为环境计时，t07 run2 为 runner 计时，两者不可直接比较。
- 不因口径问题整批重跑。
- 报告中耗时列必须标注口径来源。
- 之后所有新运行统一使用 runner 计时（nanoTime）。
- 只有做严格耗时对比时，才统一重跑。

## 三、valid 判定

- budget_exhausted：valid。
- stopped：若为 agent 自主停止则 valid；若由 runner 或外部信号打断则 invalid。
- cancelled：若由超时或人工干预触发则 invalid；若 agent 在条件下主动取消则 valid。
- 原则：valid 表示"这次运行能否反映 agent 的真实能力"，而不是"agent 是否成功"。
- 每种结束原因需在报告中列出触发来源，供我确认后再固化规则。

---

## 附录：操作细则（不改变上方原文含义）

### A1. baseline 文件

| 文件 | 状态 |
|---|---|
| `evals/baseline/baseline-opencode-go-v3.csv` | **正式 baseline** |
| `evals/baseline/baseline-opencode-go-v3.meta.json` | 记录 `agent_commit` / `eval_rules_commit` |
| `evals/baseline/baseline-opencode-go-v2.csv` | 已废弃，仅作历史记录 |
| `evals/baseline/baseline-opencode-go.csv`（v1） | 已废弃，仅作历史记录 |
| `evals/baseline/invalid_runs.csv` | 无效运行原始记录，只追加 |
| `evals/baseline/README.md` | 上述状态的文字说明 |

### A2. 结束原因、触发来源与当前 valid

| exitReason | 触发来源 | 当前 valid |
|---|---|---|
| `completed` | 模型正常结束 | true |
| `error` | LLM 调用失败 / 网络异常 / 引擎异常 | false |
| `no_result` | 未生成结果 JSON | false |
| `timeout` | headless 自超时或 runner 硬超时（外部打断） | true（按阶段 3a 口径，待确认固化） |
| `budget_exhausted` | 请求预算耗尽（agent 行为） | true |
| `stopped` | agent 自身兜底（硬轮数 / 停滞检测） | true |
| `cancelled` | 外部取消（当前 headless 无 agent 自取消路径） | false |

> 说明：`cancelled` 若将来出现 agent 在条件下主动取消的路径，需要按第三节改为 valid；改动前先在报告里列出触发来源。

### A3. 重试策略（当前实现）

| 类型 | 默认重试上限 | 参数 |
|---|---|---|
| 基础设施错误（`error` / `no_result`） | 3 次 | `--max-retries` |
| 超时（`timeout`） | 0 次 | `--timeout-retries` |

- 无效尝试逐条追加写入 `invalid_runs.csv`，不覆盖、不删除。
- 重试后仍无效：保留 `valid=false`，不计入通过率与平均值。
- 有效但验收不过（`model_failed`）不重试。

### A4. 计时与硬超时

- runner 用 `System.nanoTime()` 对整段子进程计时，写入 `elapsedSeconds`。
- 单次硬超时默认 300 秒（`--hard-timeout`）；headless 自身超时取 `min(任务 timeout, 硬超时 - 5)`。
- 超时记 `timeout`，valid 按 A2 表执行。
- 历史行（除 t07 run2 外）的耗时为旧的环境计时，报告里必须标注"环境计时"。

### A5. 结果字段

- 结果 JSON 含 `agentCommit`、`evalRulesCommit`；`--eval-rules-commit` 为必填。
- CSV：`taskId,run,attempt,passed,valid,outcome,requests,inputTokens,outputTokens,totalTokens,elapsedSeconds,exitReason`
- `invalid_runs.csv`：`taskId,run,attempt,exitReason,outcome,requests,totalTokens,elapsedSeconds,recordedAt,note`

### A6. 复现命令

```bash
mvn -B -o -DskipTests package
java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.EvalRunnerMain \
  --tasks evals/tasks/tasks.json \
  --out evals/out/baseline.json --csv evals/out/baseline.csv \
  --env-file .env --provider opencode-go --eval-rules-commit <当前规则提交>
```

单格补跑加 `--only-task <taskId> --only-run <N>`。

### A7. 禁止事项

- 不为让结果好看而改动被测 agent 行为、任务难度或验收标准。
- 不挑选运行、不删无效记录、不覆盖 `invalid_runs.csv`。
- 不重跑已定稿的基线数字；口径变了升版本。
- 评测代码只是工具，不得改动引擎/记忆/MCP/规划实现。
