# 白盒改动记录

- 基线提交：`f8a2899`
- 基线说明：hard.v4 的 react 基线（15 题 × 3 次），结果见 `evals/out/whitebox/baseline.md`
- 记录范围：基线提交之后的白盒改动；本文件暂无改动条目。

## 改动记录表（字段模板）

| 字段 | 说明 |
|---|---|
| 编号 | 自增编号，例如 W-001 |
| 日期 | 改动日期（YYYY-MM-DD） |
| 关联任务 | 受影响的 hard.v4 任务 id；全量改动写“全部” |
| 改动文件 | 修改的文件路径清单 |
| 改动摘要 | 一句话说明改了什么 |
| 回归命令 | 复跑用的 runner 参数或验收命令 |
| 改动前 | 对应任务在 baseline 的通过数/指标 |
| 改动后 | 同口径的通过数/指标 |
| 提交 | 改动提交的 git commit |
| 结论 | 通过 / 失败 / 无效，以及是否保留 |

## 已记录改动

| 编号 | 日期 | 关联任务 | 改动文件 | 改动摘要 | 回归命令 | 改动前 | 改动后 | 提交 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| W1 | 2026-11-14 | 全部 | `src/main/java/com/paicli/prompt/PromptAssembler.java`、`src/test/java/com/paicli/prompt/PromptAssemblerTest.java` | Runtime Context 增加 shell 类型（Windows=PowerShell，其它=bash）与 `.git` 只读检测 | `java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.EvalRunnerMain --tasks evals/tasks/hard.v4.json --out evals/out/whitebox/baseline-after.json --csv evals/out/whitebox/baseline-after.csv --provider opencode-go --modes react --runs 3 --eval-rules-commit 470b113 --telemetry-detail` | `f8a2899` | `7147c5e` | `7147c5e` | 待定（未跑 45 格对比） |

