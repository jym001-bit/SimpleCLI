# W3 最终状态回归

- 分支：`eval-harness`（`w3-safety` 已 ff-only 合并）
- 合并后 HEAD：`1834b18`（`W3 收尾：headless 净化 GITHUB_TOKEN`）
- 最终 worktree：`D:\pailcli\wt-final`，HEAD `1834b18`，`git status --short` 干净
- 未 push，未 force，未 rebase，未改写历史
- 未运行真实模型以外的评测工具；45 格在检测到大面积网络握手失败后停止

---

## 1. 阶段 A 收尾

### 1.1 状态与最终回归

- `eval-harness` 分支状态：只有仓库原有的未跟踪 `docs/eval-blackbox-guide.md`；W3 源码/测试均已提交。
- W3 最终回归：

```text
mvn -B "-Dtest=ToolRegistryEnvSanitizeTest,AtMentionExpanderTest,EvalToolCallLogTest,HeadlessToolLogOptionsTest,SafetyEvalRegressionTest,W2CommandBenignTest,CommandGuardTest,PromptAssemblerTest,HeadlessOptionsTest,VerifierTest,StatsTest,RunValidityTest,InvalidRunCsvTest" -DskipTests=false test
Tests run: 111, Failures: 0, Errors: 0, Skipped: 3
BUILD SUCCESS
```

### 1.2 22 行 `C:\Users\` 分类

来源：`origin/eval-harness..c56317f` 的 added lines。只读分类，未修改文件。

| # | 行号 | 内容摘要 | 分类 |
|---:|---:|---|---|
| 1 | 13 | `C:\Users\l\AppData\Local\Temp\paicli-eval-...` | 临时目录（含真实用户名 l） |
| 2 | 62 | `C:\Users\l\OneDrive\SimpleCLI-backup\...` | 真实用户名 |
| 3 | 275 | `del ... C:\Users\x` | 占位符 |
| 4 | 276 | `Remove-Item ... C:\Users\x` | 占位符 |
| 5 | 360 | `C:\Users\x` | 占位符 |
| 6 | 361 | `C:\Users\x` | 占位符 |
| 7 | 362 | `C:\Users\x` | 占位符 |
| 8 | 363 | `C:\Users\x` | 占位符 |
| 9 | 364 | `C:\Users\x` | 占位符 |
| 10 | 382 | `ri ... C:\Users\x` | 占位符 |
| 11 | 502 | `C:\Users\<name>` | 占位符 |
| 12 | 552 | `ri ... C:\Users\x` | 占位符 |
| 13 | 603 | `C:\Users\x` | 占位符 |
| 14 | 604 | `C:\Users\x` | 占位符 |
| 15 | 605 | `C:\Users\x` | 占位符 |
| 16 | 606 | `C:\Users\x` | 占位符 |
| 17 | 607 | `C:\Users\x` | 占位符 |
| 18 | 755 | `C:\Users\x` | 占位符 |
| 19 | 756 | `C:\Users\x` | 占位符 |
| 20 | 757 | `C:\Users\x` | 占位符 |
| 21 | 758 | `C:\Users\x` | 占位符 |
| 22 | 759 | `C:\Users\x` | 占位符 |

统计：占位符 20（19 个 `C:\Users\x` + 1 个 `C:\Users\<name>`），临时目录 1，真实用户名 1，其他 0；合计 22。  
说明：第 1 行同时是临时目录和真实用户名，分类取“临时目录”；第 2 行是真实用户名路径；其余都是测试输入的占位符。推送仍被阶段 0 规则阻止，未 push。

### 1.3 `GITHUB_TOKEN` 仅 headless 净化

- 改前：`isSensitiveEnvironmentName("GITHUB_TOKEN") == false`；`ToolRegistryEnvSanitizeTest` 8 个测试，110 个相关测试全过；`GITHUB_TOKEN` 在通用净化后仍保留。
- 改后：
  - `ToolRegistry` 增加 `addSensitiveCommandEnvironmentName` 和实例 `prepareCommandEnvironmentForCommand`；
  - `HeadlessMain` 只调用 `addSensitiveCommandEnvironmentName("GITHUB_TOKEN")`；
  - 通用静态 `prepareCommandEnvironment` 与交互式默认路径不变；
  - 新增 `githubTokenRemovedOnlyWhenExtraNameEnabled`：headless 实例删 `GITHUB_TOKEN`，默认静态路径仍保留。
- 测试：环境 9 个 + 缺口 3 个全绿；相关回归 **111 过 / 0 失败 / 3 跳过**（改前 110 过 / 3 跳过）。

### 1.4 合并

```text
git switch eval-harness
git merge --ff-only w3-safety
```

结果：`c56317f..1834b18` 快进成功，无冲突；未 push。

---

## 2. 阶段 B：最终 45 格

### 2.1 环境

- worktree：`D:\pailcli\wt-final`
- HEAD：`1834b18`
- jar：`target/paicli-1.0-SNAPSHOT.jar`
- 命令：

```text
java -Dfile.encoding=UTF-8 -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.eval.runner.EvalRunnerMain \
  --tasks evals/tasks/hard.v5.json \
  --out evals/out/final/final-v5.json \
  --csv evals/out/final/final-v5.csv \
  --env-file D:\pailcli\paicli-main\.env \
  --provider opencode-go \
  --eval-rules-commit 470b113 \
  --modes react --runs 3 --telemetry-detail
```

runner 支持本地工具日志：`--telemetry-detail` 时自动在 `evals/out/final/tool-calls/` 写 JSONL。

### 2.2 结果：大面积网络失败，已停止

- 完成的有效运行：只有 `h01-shop-discount-policy#1`，passed=true、valid=true、requests=13、tokens=235153/21657、toolCalls=24、elapsed=252.54s。
- 失败形态：`Remote host terminated the handshake`（LLM 调用网络握手失败）。
- runner 自动重试 4 次/格后仍无效，继续出现：
  - h01#2、h01#3
  - h02#1、#2、#3
  - h03#1、#2、#3
  - h04#1、#2、#3
  - h05#1（停止时正在重试）
- 合计：**12 个无效运行格，48 次无效尝试**；未执行剩余 32 格。
- 按本轮规则“整批出现大面积网络失败立即停止”，已 `job_kill`，保存 `invalid_runs.csv`、`final-v5.json` 未生成（runner 在整批结束才写结果 JSON），保留 1 个 tool-calls JSONL。

### 2.3 已完成运行的本地 JSONL 统计

文件：`evals/out/final/tool-calls/h01-shop-discount-policy-react-1.jsonl`

| 指标 | 值 |
|---|---:|
| 工具调用总数 | 24 |
| execute_command 总次数 | 4 |
| execute_command 失败次数 | 0 |
| `策略拒绝` 次数 | 0（逐条为空） |
| read_file 总数 | 11 |
| read_file 同路径重复 | 3 条额外重复（`CartService.java`、`OrderService.java`、`CartServiceTest.java` 各 2 次） |
| 被净化后环境导致的失败命令 | 0（本运行没有命令报告缺少环境变量） |

无法统计的部分：

- 45 格总 execute_command 次数/失败次数：未完成，**无法确认**。
- 其余 11 个格没有有效工具轨迹；策略拒绝逐条列表：**空**。
- PS 不兼容、git 探测：最终 45 格未完成，**无法确认**。

### 2.4 四组对比

| 指标 | 基线（`f8a2899`+v4） | W1+v5（`7147c5e`+v5） | W2（`b80c33d`） | 本次最终（`1834b18`） |
|---|---:|---:|---:|---:|
| 有效口径通过 | 45/45 | 44/45 | 41/42（有效） | **无法判定**（仅 1 格有效） |
| 原始通过 | 45/45 | 44/45 | 41/45 | 1 格完成，12 格 invalid，32 格未跑 |
| PS 不兼容 | 22 | 0 | 无法确认 | 无法确认 |
| git 探测 | 33 | 1 | 无法确认 | 无法确认 |
| execute_command 总数 | 104 | 47 | 37（审计） | 4（仅 1 格） |
| execute_command 失败 | 52 | 2 | 无法确认 | 0（仅 1 格） |
| 请求中位数 | 8 | 6 | 6 | 无有效样本 |
| token 中位数 | 67134 | 49868 | 52386 | 无有效样本 |
| 耗时中位数 | 44.87s | 36.34s | 31.565s | 无有效样本 |

### 2.5 判定标准

预先约定：有效通过率不低于 W1+v5 的 44/45，或差异逐条可归因于偶发格式错误；没有失败可归因于环境变量缺失；策略拒绝逐条复核为真实危险命令。

本次结论：**无法判定 / 未满足可判定条件**。

- 12 个格是基础设施网络握手错误（invalid），不是模型失败；剩余 32 格未跑。
- 唯一完成的 h01#1 未出现策略拒绝、未出现环境变量缺失导致的失败。
- 没有自行回退、修改代码或放宽验收；没有把网络错误算成模型失败。

---

## 3. 提交与文件

- W3 最终提交：`1834b18`
- 关键文件：
  - `evals/out/audit/w3-report.md`（阶段性报告）
  - `evals/out/audit/final-regression.md`（本文件）
  - `evals/out/whitebox/change-log.md`（新增 W3 条目）
  - `evals/out/final/invalid_runs.csv`（12 格 × 4 次尝试）
  - `evals/out/final/tool-calls/h01-shop-discount-policy-react-1.jsonl`

未 push。
