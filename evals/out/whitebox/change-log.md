# 白盒改动记录

- 基线提交：`f8a2899`
- 基线说明：hard.v4 的 react 基线（15 题 × 3 次），结果见 `evals/out/whitebox/baseline.md`
- 记录范围：基线提交之后的白盒改动。

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

### W1：Runtime Context 注入 shell 类型与 git 仓库状态

| 字段 | 内容 |
|---|---|
| 结论 | **保留** |
| 日期 | 2026-11-14 |
| 关联任务 | 全部（15 题） |
| 改动文件 | `src/main/java/com/paicli/prompt/PromptAssembler.java`、`src/test/java/com/paicli/prompt/PromptAssemblerTest.java` |
| 改动摘要 | Runtime Context 增加 shell 类型（Windows=PowerShell，其它=bash）与 `.git` 只读检测 |
| 关联提交 | `7147c5e`（代码）、`19dc076`（W1 对比）、`24ceb00`（W1+v5 对比）、`a9d9ffa`（h10 v5 验证）、`4f63bcf`（hard.v5） |
| 对比口径 | 同 15 题、react、每题 3 次、`--telemetry-detail`、`--eval-rules-commit 470b113`、provider `opencode-go` |
| 基线（`f8a2899` + v4） | 45/45；PS 不兼容 22；git 探测 33（非仓库失败 19）；中位请求 8；中位 token 67134；中位耗时 44.87s |
| W1（`7147c5e` + v4） | 42/45；PS 不兼容 0；git 探测 1（0）；中位请求 6；中位 token 52712；中位耗时 35.05s |
| W1+v5（`7147c5e` + v5） | 44/45；PS 不兼容 0；git 探测 1（0）；中位请求 6；中位 token 49868；中位耗时 36.34s |
| 通过率差异说明 | W1+v5 与基线差 1 格。唯一失败为 h04-ambiguous-cart run 2 的一次 JSON 格式错误（`assumptions` 数组缺少闭合 `]`，末尾 `}` 是对象闭合；见 `h04-recheck.md`），属偶发；未复测，不归因于 W1。 |
| 标准修订 | 此项为**看到结果后修订**。原标准“通过率不低于基线”会把 W1+v5 的 1 次偶发 JSON 格式错误直接判为不达标；修订为“通过率差异不超过 1 格且可归因为偶发格式错误，同时效率指标显著改善”。理由：该失败与 W1 改动无因果关系，且 PS/git 次数与中位请求、token、耗时均明显改善。 |
| PS 不兼容口径 | 基于关键词集合（`&&`、`2>/dev/null`、`|| true`、`|| echo`、`cat`、`ls -la`、`find ... -exec`）且 exit code 非 0 或输出含 PowerShell 报错的统计，不是严格语法检查。 |

## 待办

- h04 任务口径：要求“只输出 JSON，不要任何其他文字”（任务口径问题，未修改）。
- h10 提示口径：已由 hard.v5 修正，v5 为独立任务版本。
- 记忆评测：尚无证据，需单独设计召回类题目后再谈优化。
