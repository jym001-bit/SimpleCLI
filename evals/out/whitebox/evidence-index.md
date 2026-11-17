# 简历素材证据索引

- 范围：`evals/out/whitebox/resume-notes.md` 中的数字与声明。
- 规则：每个简历数字一行；来源只写可复核文件；找不到来源写“无法确认”；不补写、不推算。
- 当前分支：`eval-harness`，HEAD `9b7aeac`（简历文档整理提交除外）。
- 路径均相对于仓库根 `D:\pailcli\paicli-main`。

| 简历声明 | 数字 | 来源文件（路径） | 提交号 | 复现命令 | 口径限制 |
|---|---:|---|---|---|---|
| W1+v5 通过率 | 44/45 | `evals/out/whitebox/W1-v5-after.md` | `7147c5e` | 见 W1 45 格命令（hard.v5、react、每题3次、`--eval-rules-commit 470b113`） | 是 W1+v5，不是 W1 单独；invalid 不计入通过率 |
| W1 耗时 | 44.87s → 36.34s，约 19% | `evals/out/whitebox/W1-v5-after.md` | `7147c5e` | 同上 | 耗时受网络影响，只作辅助观察，不作主要指标 |
| W1 PS 不兼容 | 22 → 0 | `evals/out/whitebox/W1-v5-after.md` | `7147c5e` | 同上 | 关键词统计，不是严格语法检查 |
| W1 git 探测 | 33（非仓库失败 19）→ 1（0） | `evals/out/whitebox/W1-v5-after.md` | `7147c5e` | 同上 | 命令含 `git` 即计入；非仓库失败看 `not a git repository` |
| W1 请求中位数 | 8 → 6 | `evals/out/whitebox/W1-v5-after.md` | `7147c5e` | 同上 | 45 次逐次值取中位数 |
| W2 缺口组 | 22 红 → 6 红 | `evals/out/whitebox/change-log.md` | `b80c33d` | `mvn -B "-Dtest=SafetyEvalGapTest" -DskipTests=false "-Dpaicli.safety.gap=true" test` | 按输入计数；与早期 `safety-gap-results.md` 的 13 方法/10 缺口不是同一时点 |
| W2 回归组 | 16 过 3 跳过 | `evals/out/whitebox/change-log.md` | `b80c33d` | `mvn -B "-Dtest=SafetyEvalRegressionTest,W2CommandBenignTest" -DskipTests=false test` | 3 个跳过是平台/权限条件；不是全量 |
| W2 良性命令 | B1–B16 全放行 | `evals/out/whitebox/change-log.md` | `b80c33d` | 同上 | 只覆盖 B1–B16，不代表所有命令 |
| W2 语料回放 | 8 条，新增拒绝 0 | `evals/out/whitebox/change-log.md` | `b80c33d` | 见 `evals/out/audit/real-commands-corpus.txt` 与 W2 记录 | 只 8 条可核对命令，不是 47 条全量 |
| W2 45 格原始 | 41/45（3 invalid、1 model_failed）；有效 41/42 | `evals/out/whitebox/change-log.md` | `b80c33d` | W2 45 格命令（hard.v5、react、3 次、`--telemetry-detail`） | h04 网络 invalid 和 h10 model_failed；**45 格仅用于证明未引入严重回归，不作为效果证据** |
| W2 PS/git/命令失败 | 无法确认 | `evals/out/whitebox/change-log.md` / `final-regression-v2.md` | `b80c33d` | — | W2 没有本地 tool-calls JSONL，不能用本次同口径重算；**45 格仅用于证明未引入严重回归，不作为效果证据** |
| 最终状态 45 格 | 45/45 | `evals/out/whitebox/final-regression-v2.md`、`change-log.md` | `1834b18` | `evals/out/final-v2/full/full-v5.json` 对应 45 格命令 | 代码差异含 W1/W2/W3/hard.v5，不能全部归因 W1 |
| 最终状态无效运行 | 0 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | 0 invalid，0 自动重试 |
| 最终状态请求中位 | 6 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | 45 次逐次值中位数 |
| 最终状态 token 中位 | 48,450 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | input+output；不从缓存重复累加 |
| 最终状态耗时中位 | 32.47s | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | 单次运行中位数；网络会影响 |
| 最终状态 PS 不兼容 | 0 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上；本地 tool-calls JSONL 关键词统计 | 关键词统计，不是严格语法检查 |
| 最终状态 git 探测 | 2（非仓库失败 0） | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | 命令含 `git` 即计入；2 次是 `git status` 和 `.gitignore` 读取 |
| 最终状态 execute_command | 43 次，失败 5 次 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | 5 = 4 次 `Select-String` 参数错误 + 1 次 HITL deny，失败含未执行 |
| 最终状态策略拒绝 | 0 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | HITL deny 不是“策略拒绝” |
| 最终状态本地工具日志 | 44 个 JSONL、526 次调用 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | h05#2 没有工具调用，无文件 |
| 最终状态 read_file | 251 次，额外重复 218 次 | `evals/out/whitebox/final-regression-v2.md` | `1834b18` | 同上 | 30 个路径重复；24 个 run 内有重复 |
| W3 相关回归 | 111 过 / 0 失败 / 3 跳过 | `evals/out/whitebox/change-log.md` | `1834b18` | `mvn -B "-Dtest=ToolRegistryEnvSanitizeTest,AtMentionExpanderTest,EvalToolCallLogTest,HeadlessToolLogOptionsTest,SafetyEvalRegressionTest,W2CommandBenignTest,CommandGuardTest,PromptAssemblerTest,HeadlessOptionsTest,VerifierTest,StatsTest,RunValidityTest,InvalidRunCsvTest" -DskipTests=false test` | 是相关回归，不是全仓库 |
| W3 JSONL 测试 | 8 GREEN | `evals/out/whitebox/change-log.md` | `1834b18` | `mvn -B "-Dtest=EvalToolCallLogTest,HeadlessToolLogOptionsTest" -DskipTests=false test` | 只测脱敏/路径/写失败/失败调用，不测真实模型 |
| 记忆/压缩 A 层回归 | 8 GREEN / 0 失败 | `evals/out/audit/memory-compression-results.md` | `9b7aeac` | `mvn -B "-Dtest=MemoryCompressionBoundaryTest,CheckpointPairRegressionTest" -DskipTests=false test` | 只 A 层确定性，不含真实模型 |
| 记忆/压缩缺口 | 4 RED | `evals/out/audit/memory-compression-results.md` | `9b7aeac` | `mvn -B "-Dtest=MemoryCompressionGapTest" -DskipTests=false "-Dpaicli.memory.gap=true" test` | 确认的是输入已有孤立/重复时不会被修复 |
| 记忆/压缩现有测试 | 93 过 / 0 失败 | `evals/out/audit/memory-compression-results.md` | `9b7aeac` | 同文件第 3.1 节命令 | 不含两个已知 Windows/既有失败 |
| 记忆条目 | 65 条仍全在 | `evals/out/audit/memory-compression-results.md` | `9b7aeac` | 同文件 G6 | 只验证 65 条，不能证明“无上限” |
| 既有失败 1 | 1 failure | `evals/out/audit/memory-compression-results.md` | `9b7aeac` | `mvn -B "-Dtest=MemoryManagerTest" -DskipTests=false test` | 已知 Windows POSIX 路径假设，不是本轮新增 |
| 既有失败 2 | 1 failure | `evals/out/audit/memory-compression-results.md` | `9b7aeac` | `mvn -B "-Dtest=AgentClearHistoryTest" -DskipTests=false test` | 已知 ctx token 断言失败，不是本轮新增 |
| 45 格单次最大上下文 | 29,309 tokens | `evals/out/audit/tool-stability.md` | W1+v5 运行（`7147c5e`） | Langfuse `inputUsage` 复算 | 单次 generation 输入代理值，不是完整历史精确审计；未触发 100k/967k 阈值 |
| 落盘/清理/摘要阈值 | 32k 字符 / 100k tokens / 967k tokens | `evals/out/audit/memory-compression-design.md` | 代码基线 `9b7aeac` | 代码行号见设计文档 | 清理由摘要阈值派生；摘要阈值没有直接配置项 |

## 口径不一致或无法核对

1. **W2 的 22→6 与 safety-gap-results 的 13/10 不一致**：前者来自 change-log，按“输入”计数；后者是早期按测试方法计数。两者不能相加、混用或互相证明。
2. **PS 不兼容与 git 探测都是关键词统计**：PS 不是严格语法错误检测；git 探测是命令含 `git`，不区分只读查看与真正仓库操作；W2 没有本地 JSONL，PS/git/execute_command 失败无法用最终口径重算。
3. **W1 的 44/45 是 W1+v5 口径**：不是 W1 单独；最终 45/45 的代码差异包含 W1、W2、W3 和 hard.v5，不能全部归因于 W1。
4. **W1 的 19% 耗时下降受网络影响**：只作辅助观察，不是主要指标；不同批次供应商状态/缓存也会影响。
5. **最终 execute_command 5 次失败包含 1 次 HITL deny**：不是 5 次命令实际执行失败；4 次是 `Select-String` 参数绑定错误。
6. **记忆/压缩只有 A 层确定性**：8 GREEN 不代表记忆/压缩效果优化；65 条不是上限证明；两个既有失败未修。
7. **W2/W3 的 45 格不是同一代码/同一天**：W2 有 invalid 和 model_failed，最终状态是 W3 收尾后 45/45；只能各自按自己的口径报告。
8. **完整旧 jar 的 Maven 回归日志无法确认**：仓库只有冻结探测和 comparison 记录，没有找到完整 109 项原始日志，相关说法只能引用这些记录。
9. **W1+v5 的 token 中位数 49,868 与最终状态的 48,450 是不同代码状态下的数字**，不得混用；前者是 W1+v5，后者包含 W1/W2/W3/hard.v5。
10. **W2 之后命令失败的变化只有最终回归的本地 JSONL 数据，没有 W2 单独的数据**：不能写成 W2 降低了命令失败率。
