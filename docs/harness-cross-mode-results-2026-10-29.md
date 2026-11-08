# 三模式只读受控评测（2026-10-29）

本轮只新增评测入口 `CrossModeReadOnlyMain` 和三题定义；未修改 Agent、Kernel、ToolRegistry、Plan/Team 策略等生产执行代码。一次顺序批次恰好启动 3 题 × 3 模式，共 9 个根任务，原始结果逐条保存在 `target/harness-cross-mode/results.json`，云端读回核验在同目录 `cloud.json`。没有选择性重跑。

## 运行条件与成本门控

同一 DeepSeek `deepseek-flash` 模型、同一仓库工作区、同一任务文字和只读 `read_file` 工具；评测注册表既只展示该工具，也拒绝执行其他工具。三模式各自保持真实决策路径：`Agent.runWithOutcome`、`PlanExecuteAgent.runWithOutcome`、`AgentOrchestrator.runWithOutcome`；Team 为 2 Worker，Reviewer 和 Planner 计入总模型请求。输出上限每次 8192 Token；输入序列化内容每次最多 80KB；全批最多 30 次实际模型请求；HTTP 内部重试设为 1 次；本批硬迭代上限 5，Plan 重规划 0。DeepSeek V4 Flash 的模型请求参数仍由 `DeepSeekClient` 统一设置，temperature=1、top_p=0.95、thinking enabled、reasoning effort max；不能固定供应商后端版本和随机种子。

按官方峰时最高价（百万 Token：cache miss 输入 $0.30，cache hit $0.006，输出 $1.20），30 × 80KB 输入按每字节最多 1 Token 保守计，再加 30 × 8192 输出，理论上限约 $1.015；留一倍余量仍低于用户批准的 $3。输入 Token 与输出上限均由评测客户端在发请求前/请求体中限制；内部重试已关闭。实际已返回 Usage 的估算费用约 $0.031339，非账单；未返回 Usage 的失败请求无法计费复核。价格来源：https://api-docs.deepseek.com/quick_start/pricing/ 。

## 逐次结果

| 题目 | 模式 | 严格判定 | 模型实际 Generation | 工具 | 输入/输出 Token | 耗时 | Trace |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| X01 | ReAct | 通过 | 2 | 1 | 9421 / 582 | 4747ms | eb839b49b391e05204331a69ab2b3dea |
| X01 | Plan | 通过 | 4 | 1 | 19648 / 2148 | 12780ms | 5c1b40f7345aa4395277e539b60c6621 |
| X01 | Team | 通过 | 17 | 4 | 100112 / 8195 | 48105ms | de82cce59197c916974e03202b906eff |
| X02 | ReAct | 通过 | 2 | 2 | 8105 / 2369 | 11019ms | 5fe17fced147492e6aaefabd5219efef |
| X02 | Plan | 未完成：批次上限 | 5 | 3 | 22807 / 3030 | 11970ms | cf17358a1466d7c6d1d2fc301beb964b |
| X02 | Team | 未执行模型：批次上限 | 0 | 0 | 0 / 0 | 15ms | 51f3e379447b25ba2a215aa8d493eef4 |
| X03 | ReAct | 未执行模型：批次上限 | 0 | 0 | 0 / 0 | 9ms | 6751d06f398a56cc59783e4aa56a88e6 |
| X03 | Plan | 未执行模型：批次上限 | 0 | 0 | 0 / 0 | 4ms | 57bbf8c862db8f0c88e2d69f7cbfd6b3 |
| X03 | Team | 未执行模型：批次上限 | 0 | 0 | 0 / 0 | 10ms | 68d00d6d621143b52e59c1ae4d |

根运行计数 9；实际请求 30、工具 11、输入 160093（缓存命中 123392）、输出 16324 Token。评测客户端原始 `modelCalls` 字段在上限后仍计入了被拦截的尝试，因此原始文件的 35 是**尝试数**，不是供应商请求数；已在评测入口改为只有准入成功才累计，此次原始结果不覆盖。云端 9 条根 Trace、30 个 Generation、11 个工具 Observation，9 条都无悬空父节点，敏感文本检查为 false。后四条根 Trace 存在，但没有真实 Generation，不能当成模型任务失败率。

## 严格判定与局限

评分在运行前固定：必须完成、回答包含预先指定的关键事实、实际成功读取全部要求文件、无非只读工具尝试。X01 三模式和 X02 ReAct 通过；其余 5 次受全批模型调用上限影响，不能用作三模式任务质量对比。没有改判分规则或挑选成功样本。三模式的系统提示词、Plan 拆解和 Team Worker/Reviewer 策略本来不同，不能把调用数差异单独解释为浪费。

初步唯一推荐的下一轮生产改造目标是**跨 Planner、Worker、Reviewer 和普通 ReAct 轮次的根任务模型请求预算归集与准入**，复用已有 `AgentBudget` 与 `ExecutionOutcome`，而不是简单减少 Team Worker 数或取消 Reviewer。理由：仅 X01 的 Team 就用了 17/30 个供应商请求；之后的 X02 Plan 在全批上限处被中断，说明分散的局部预算难以控制整批成本和可比较性。这个证据仅证明本小样本的调用分布，不证明 Team 17 次都是无效调用，也不证明根预算会提高质量。下一轮应先设计根预算耗尽时的部分结果语义、Planner/Reviewer 计账和状态传递，再用完全相同三题复测；实施风险是过紧的根预算可能让正确任务提前停止。
