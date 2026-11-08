# 跨模式只读评测：运行前检查

## 冻结的三题

题目与机器可读的预先验收事实在 `benchmarks/harness-live/cross-mode-readonly-cases-2026-10-29.json`。X01 为单文件预算默认值和配置项；X02 为两文件根结果到后台状态映射；X03 为根取消作用域的隔离及动作。三题都要求实际源码路径和方法名，禁止写文件、命令及外网。评分必须核对关键事实、证据文件和禁止操作，不能仅看措辞或关键词。

## 真实入口及门控结论

- ReAct：`Agent.runWithOutcome`；现有 `LiveHarnessMain` 只构造 ReAct，`SafeRegistry` 只暴露并执行 `read_file`。
- Plan：`PlanExecuteAgent.runWithOutcome`，CLI `/plan` 的真实入口；现有真实评测器尚未使用这个入口。
- Team：`AgentOrchestrator.runWithOutcome`，CLI `/team` 的真实入口；默认两个 Worker、每步骤最多两次重试，Planner、Worker、Reviewer 都可能请求模型。现有真实评测器尚未使用这个入口。
- `.env` 中存在 DeepSeek 凭据，但没有在日志或报告中读取或复制其值；当前进程的 `DEEPSEEK_API_KEY` 环境变量为空。评测器会从配置加载模型，现有历史批次为 `deepseek-v4-flash`；新批次实际模型尚未启动验证。Langfuse 读取凭据在当前进程存在。
- `AgentBudget.fromSystemProperties` 默认 Token 和硬迭代上限近似无限；Team 计划解析器未限制步骤数。现有评测器没有共享的跨模式总模型调用硬上限，且其只读 `SafeRegistry` 是 ReAct 专用内部类。仅将任务文字写成“只读”不能形成工具权限边界。
- DeepSeek 当前官方 Flash 价格按百万 Token 分档计费，峰时 cache miss 输入 $0.30、cache hit $0.006、输出 $1.20；非峰时分别 $0.15、$0.003、$0.60。旧别名 `deepseek-v4-flash` 由 Flash 提供服务。单价已知，但模型请求数量和输出量没有同一评测硬上限，因此本轮 9 次根任务的可信最高费用**不可计算**。价格来源：https://api-docs.deepseek.com/quick_start/pricing/ 。

运行前必须先在非生产评测入口接入三模式同一只读工具限制、模型请求数与单次输出上限，再根据用户批准的总费用上限计算并证明最坏情况不超限。不能直接用已有 ReAct 评测脚本代跑 Plan/Team，也不能把 ReAct 数据冒充跨模式结果。

## 当前结果与下一轮目标

实际真实根任务执行 **0/9**；没有产生新的 run ID、Trace ID、Token、耗时或成功率。保留既有工作区全部生产修改；本轮只新增冻结任务定义和本门控记录。跨模式生产优化目标仍**不能由实测确定**。下一轮先完成受控评测入口与费用门控，再运行最多九次、逐次保存全部结果；只有取得至少三个模式的可比较结果后，才能在质量、调用量和风险之间推荐唯一生产改造。
