# 简历素材与口径

## 简历表述草稿

基于离线评测定位 ReAct Agent 在 Windows 环境下的命令失效问题：15 题中大量命令使用 bash 语法导致 PowerShell 报错，且反复执行无效的 git 探测。在系统提示中注入运行环境信息（shell 类型、是否 git 仓库）后，同口径 45 次运行中，shell 语法错误由 22 次降至 0，git 探测由 33 次降至 1，请求数中位数由 8 降至 6，耗时中位数由 44.87s 降至 36.34s（下降约 19%）；通过率 44/45，差异来自一次偶发的 JSON 格式错误。

## 口径说明

- **同口径 45 次**：15 道 hard 任务 × react × 每题 3 次；provider `opencode-go`；`--eval-rules-commit 470b113`；开启 `--telemetry-detail`。基线为 `f8a2899` + hard.v4，W1 为 `7147c5e` + hard.v4，W1+v5 为 `7147c5e` + hard.v5。
- **关键词统计**：PS 不兼容 = 命令含 `&&`、`2>/dev/null`、`|| true`、`|| echo`、`cat`、`ls -la`、`find ... -exec` 之一，且 exit code 非 0 或输出含 PowerShell 报错；git 探测 = 命令含 `git`；非仓库失败 = 输出含 `not a git repository`。这是基于关键词/输出的统计，不是严格语法检查。
- **中位数**：45 次运行的逐次值取中位数；token = 输入 + 输出；耗时 = runner 侧 `System.nanoTime()`，覆盖子进程全生命周期。
- **通过率**：45 格中统计 `passed=true`；invalid 运行不计入通过率。

## 不可写入简历的内容

- **记忆优化**：尚无评测证据，不能写成已做记忆优化。
- **其他工具调用优化**：未单独验证，不能归因到本次改动。
- **plan/team 对比**：本轮未运行 plan/team，不能写对比结论。
