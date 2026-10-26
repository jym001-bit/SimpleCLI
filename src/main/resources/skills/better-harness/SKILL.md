---
name: better-harness
description: 审查 PaiCLI 编码 Agent 的任务理解、受控执行、变更验证、可靠交付与经验沉淀；只通过 /better-harness 原生命令调用。
version: 0.1.0
author: PaiCLI
tags: [harness, review, workflow]
---

# PaiCLI Better Harness

这是 QoderAI Better Harness 方法在 PaiCLI 中的原生适配。审查对象是编码 Agent
外层工作流，不是单次代码 diff，也不是模型能力排行榜。

必须把三类证据保持独立，直到 lead 汇总：

1. Session Evidence：当前 PaiCLI `ConversationLedger` 的脱敏元数据，只证明被观察到的
   调用、模式和生命周期信号；未提供正文时，不得推断任务是否正确完成。
2. Project Harness：`AGENTS.md`、`PAI.md`、README、测试、CI 和交付约束，只证明机制
   存在；是否真正执行需要会话或结果证据。
3. Agent Customize：Skill、Prompt、MCP、Memory 入口、HITL 和策略资产，只证明配置
   表面；数量多或少本身不能形成 finding。

最终按 Agent Work Loop 五个维度汇总：

- Task Understanding
- Controlled Execution
- Change Validation
- Reliable Delivery
- Learning Capture

每个 finding 必须包含可观察后果、证据边界、最小修复责任方和可执行验收检查。
没有证据的行为标记为 unobserved，不得编造成缺陷或分数。一次检查通过只能证明措施被
执行过，只有后续可比较的 Task Episode 才能证明工作闭环得到改善。

默认只允许确定性的报告文件写入 `.paicli/better-harness/`。项目修改、外部写入、
Memory 正文、用户目录资产、历史原始会话和其他 provider 都需要单独授权。

方法来源：QoderAI/better-harness，MIT License。
