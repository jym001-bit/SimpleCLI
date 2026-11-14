# h10 v5 提示修正初步验证

- 任务集：`evals/tasks/hard.v5.json`（提交 `4f63bcf`）
- 被测代码：`7147c5e`（W1：Runtime Context 注入 shell 与 git 状态）
- 运行参数：`--only-task h10-long-context-chain --modes react --runs 3 --provider opencode-go --eval-rules-commit 470b113 --telemetry-detail`
- 运行时间：2026-11-14（Asia/Shanghai）
- 总耗时：83.70 秒
- 结果：**3/3 通过，0 失败，0 invalid，0 重试**

## hard.v5 结构比对

```text
tasks: 15 / 15
idsEqual: true
changedTasks: ["h10-long-context-chain"]
changedFields: {"h10-long-context-chain":["prompt"]}
allOtherTasksIdentical: true
h10.verifyEqual: true
promptSuffixAdded: "\n\nchain 只列出仓储接口（OrderRepository），不要列出实现类（如 InMemoryOrderRepository）。"
promptLength: 349 -> 417
```

结论：除 `h10-long-context-chain.prompt` 外，v5 与 v4 逐字段一致；`verify`、`approval`、`timeoutSeconds` 等均未变。

## 运行明细

| run | 通过 | requests | tokens（入+出） | 耗时(s) | chain 实际内容 | 判定 |
|---|---|---|---|---|---|---|
| 1 | 是 | 5 | 36281+1039 | 23.12 | `["OrderController","OrderService","PricingService","OrderRepository"]` | 完全一致 |
| 2 | 是 | 5 | 33973+628 | 22.90 | `["OrderController","OrderService","PricingService","OrderRepository"]` | 完全一致 |
| 3 | 是 | 7 | 49669+1127 | 37.44 | `["OrderController","OrderService","PricingService","OrderRepository"]` | 完全一致 |

期望：`["OrderController","OrderService","PricingService","OrderRepository"]`；3 次均无多列、无漏列、无顺序错。

## traceId

| run | traceId |
|---|---|
| 1 | `afe26ca95e8b3d96bfbc3d46a7c57c99` |
| 2 | `0e853151bbc8a841f9cdfac9393747c5` |
| 3 | `33c1ddbd296bd032be353488e9588058` |

## 结论（只写事实）

**3/3 通过。**
