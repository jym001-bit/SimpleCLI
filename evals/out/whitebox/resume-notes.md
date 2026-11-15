# 简历素材与口径

## 简历表述草稿

基于离线评测定位 ReAct Agent 在 Windows 环境下的命令失效问题：15 题中大量命令使用 bash 语法导致 PowerShell 报错，且反复执行无效的 git 探测。在系统提示中注入运行环境信息（shell 类型、是否 git 仓库）后，同口径 45 次运行中，shell 语法错误由 22 次降至 0，git 探测由 33 次降至 1，请求数中位数由 8 降至 6，耗时中位数由 44.87s 降至 36.34s（下降约 19%）；通过率 44/45，差异来自一次偶发的 JSON 格式错误。

## 口径说明

- **同口径 45 次**：15 道 hard 任务 × react × 每题 3 次；provider `opencode-go`；`--eval-rules-commit 470b113`；开启 `--telemetry-detail`。基线为 `f8a2899` + hard.v4，W1 为 `7147c5e` + hard.v4，W1+v5 为 `7147c5e` + hard.v5。
- **关键词统计**：PS 不兼容 = 命令含 `&&`、`2>/dev/null`、`|| true`、`|| echo`、`cat`、`ls -la`、`find ... -exec` 之一，且 exit code 非 0 或输出含 PowerShell 报错；git 探测 = 命令含 `git`；非仓库失败 = 输出含 `not a git repository`。这是基于关键词/输出的统计，不是严格语法检查。
- **中位数**：45 次运行的逐次值取中位数；token = 输入 + 输出；耗时 = runner 侧 `System.nanoTime()`，覆盖子进程全生命周期。
- **通过率**：45 格中统计 `passed=true`；invalid 运行不计入通过率。

## W2 草稿：CommandGuard 命令安全规则

- 改动：`CommandGuard` 增加 PowerShell `Remove-Item`/`del`/`rd`/`ri`/`erase`、`cmd /c`、`-EncodedCommand`、`iex` 网络组合、下载后执行、`rm` 分离参数/引号/变量规则。
- 事实：缺口组 22 红→6 红；回归组 16 过 3 跳过；良性命令 B1–B16 全放行；语料回放仅 8 条且新增拒绝 0。
- 45 格仅作回归检查：W2 原始 41/45，3 格 h04 因网络握手错误 invalid，1 格 h10 model_failed；token/耗时变化在噪声内。
- 残余风险：变量拼接与多层编码无法封住；C7-3、C8-3、硬链接、HITL 超时、B2、A1 未修。

## 不可写入简历的内容

- **记忆优化**：尚无评测证据，不能写成已做记忆优化。
- **其他工具调用优化**：未单独验证，不能归因到本次改动。
- **plan/team 对比**：本轮未运行 plan/team，不能写对比结论。
- **保证命令安全**：W2 是黑名单 + 目标判定，不能写成已保证命令安全。
- **零误拦**：R1/R2 变量目标 fail-closed、R4/R5 组合规则都可能误拦，不能写零误拦。
- **W2 带来效率提升**：45 格 token/耗时变化在噪声内，不能写成 W2 带来效率提升。
