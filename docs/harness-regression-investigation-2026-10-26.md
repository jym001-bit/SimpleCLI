# Harness 回归调查（2026-10-26）

## 取证范围与对照

保留当前工作区全部改动。用 `git archive HEAD` 建立独立的 `37b09ee` 基线目录，未对工作区执行 Git 回滚。解析 Surefire XML 的每个失败方法、异常和完整堆栈，并与原始 Maven 日志核对；项目未配置 Failsafe，也没有 Failsafe 报告。原始日志中 `1662 tests / 134 failures / 600 errors` 是**中途结果**：日志随后出现 `Java heap space`，不能作为完整测试基线。

| 运行 | JDK 与条件 | 测试 | 断言失败 | 错误 | 跳过 | 失败类 |
|---|---|---:|---:|---:|---:|---:|
| 未改动 HEAD 基线 | 默认 JDK 23；`-Pquick -DreuseForks=false`，逐类 fork、限制堆/元空间以完成全量运行 | 2475 | 150 | 644 | 86 | 85 |
| 本轮修复前工作区 | 与 HEAD 相同 | 2479 | 149 | 644 | 86 | 84 |
| 本轮修复后工作区 | 支持的 JDK 17；标准 `mvn test -Pquick` | 2480 | 143 | 600 | 86 | 74 |

修复前 793 个失败方法全部也在 HEAD 失败；修复后 743 个失败方法也全部在 HEAD 失败，**没有新出现的失败方法**。HEAD 独有的一个 `PromptAssemblerTest` 失败由归档文件的 CRLF 行尾触发：现有代码按 `\n## Tools\n` 匹配标题，工作区资源文件为 LF；这是对照目录的行尾差异，不能归为本轮改造。原始未完成运行中能核对的 733 个失败方法也都在 HEAD 重现。逐方法异常、最深 `Caused by`、源码栈帧、前后状态见 [`quick-failure-inventory-2026-10-26.csv`](../benchmarks/harness-offline/results/quick-failure-inventory-2026-10-26.csv)；原始和三次完整运行的日志、XML 副本及带完整堆栈的 JSON 在 `target/harness-investigation/`，该目录为本地未跟踪构建产物。

## 剩余失败分类与根因

| 类别 | 方法数 | 证据和具体根因 |
|---|---:|---|
| 环境、配置、外部服务 | 551 | 280：Windows 不支持所需 POSIX 权限；190：`python3` 找不到，堆栈为进程启动 `CreateProcess error=2`；73：Windows `BasicFileAttributes.fileKey()` 返回 `null`，触发评测工件身份校验，不能概括成“包过大”；6：创建符号链接权限不足；2：`CodeIndexTest` 默认连接本地 Ollama embedding 的 `localhost:11434` 被拒绝，应用日志有对应 `ConnectException`。 |
| 测试自身缺陷 | 189 | 68：把 Windows 路径分隔符与测试字典键混用；55：把 `/usr/bin/docker` 当作 Windows 绝对路径；46：Mock 期望裸 JSON，却收到 HEAD 已有的 `<tool_result trust="untrusted-data">` 包装；13：写死 `/private/tmp`；3：Linux bubblewrap 夹具在 Windows 上运行；另有 4 项分别为非法 Windows 文件名、写死 `/` 分隔符、检索项目键规范化不一致、预期异常类型与先发生的安全拒绝不符。 |
| 改动前已有的产品限制 | 3 | `ImageReferenceParserTest` 的 `file:` URI 转换未正确处理 Windows 盘符，HEAD 与工作区相同。此项不属于 Harness 改造触达代码，本轮未扩大生产代码范围。 |
| 本轮代码直接引入、修复后仍失败 | 0 | 上述剩余 743 项均在 HEAD 复现。 |
| 当前无法确定根因 | 0 | 对当前 743 项已完成归类；历史上另有一项 PNG EOF 错误在切换 JDK 与堆设置后消失，两项条件同时变化，无法单独归因，不计入当前剩余失败。 |

分类是按**方法的主异常**统计。某些失败另带清理阶段的 suppressed 异常，已查看完整堆栈，没有把清理异常误当成主因。JDK 23 下还有 43 项 Mockito/Byte Buddy 兼容错误，JDK 17 完整运行后消失；这是历史失败减少的一部分。完整工具注册表测试不属于 `quick`（现有 profile 排除），额外运行相关四类共 105 项、1 项断言失败和 1 项错误，5 项跳过。两项均在独立 HEAD 基线上重现：`ToolRegistryTest.commandExitCodeControlsTypedToolSuccess` 用 Windows PowerShell 执行 Bash 的 `printf ok`；`commandSandboxBuildsEscapedSeatbeltProfileAndDirectArgv` 在 Windows 创建含双引号的非法文件名。未为制造全绿而修改这些生产路径。

## 本轮实际修复与覆盖核查

唯一确认由 Harness 改动直接引入的回归是遥测测试隔离：生产代码新增优先级更高的 `LANGFUSE_ENABLED` 后，Surefire 只覆盖旧的 `PAICLI_LANGFUSE_ENABLED=false`。用本地假密钥与 `LANGFUSE_ENABLED=true` 复现了测试进程意外启用 exporter；现已在 `pom.xml` 中同时将新变量设为 `false`，并增加对应测试。正向遥测探针显式开启新变量。未使用真实密钥。

修正 `AgentConversationLedgerTest` 的 Plan 模型队列，补齐实际会先发生的规划响应；修正 `CommandExecutionObservationTest` 的 Windows 命令/参数夹具，保留退出状态、事件、PID 和超时断言，并加强超时后进程退出检查。这些属于测试修复，未据此改动生产 Agent。增强 H18/H19 的 Team 审核决策和步骤状态断言、H21 的失败后空闲状态与无伪造回答断言、H31 的最终回答/调度次数/会话持久化断言。修复前 H18/H19/H31 存在只凭最终文本或警告缺失也能通过的弱断言。

35 项离线用例逐项审计见 [`coverage-audit-2026-10-26.csv`](../benchmarks/harness-offline/results/coverage-audit-2026-10-26.csv)。它们调用真实状态机、账本或工具策略并使用可控 Mock 输入，但边界明确：H20 验证本地 OTLP 请求的元数据、脱敏和故障隔离，未解析父 Span ID/状态码；H24 验证 MCP 启动配置失败，未覆盖连接建立后的断线恢复；H32 检查摘要调用输入，固定 Mock 摘要不能证明关键约束被保留；H33 检查项目隔离，未评估语义相关性。因而 **35/35 表示固定离线行为通过，不表示真实任务成功率或所有故障恢复能力**。

修复后 7 个直接相关测试类共 **51/51 通过**；增强后的固定离线评测 **35/35 通过，0 个基线回归**，结果见 [`investigation-offline-after-2026-10-26.json`](../benchmarks/harness-offline/results/investigation-offline-after-2026-10-26.json)。`mvn test -Pquick` 已完整执行但仍为 **143 项断言失败、600 项错误**，按上表分类。没有跳过新测试、降低断言或删除失败用例；本轮没有修改四个核心生产 Java 文件。未知边界是未在 Linux/macOS、真实模型和真实 Langfuse 服务上复核；历史 PNG EOF 的单独触发因素也尚未确定。
