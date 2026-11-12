# 3d hard.v2 基线：ReAct（15 任务 × 1 次）

- **agent_commit**: `abc287d`
- **eval_rules_commit**: `470b113`
- **hard.v2 版本**: 提交 `15d95b6`，文件 SHA-256 前缀 `3B2FDD23F26C4CAF`
- **模型/供应商**: opencode-go / deepseek-v4.1-flash
- **运行矩阵**: 15 个困难任务 × react × 每格 1 次 = **15 次**
- **总耗时（runner 计时）**: 869.51 秒（约 14.5 分钟）
- **未运行 plan / team**

## 1. 总体结果

| 指标 | 值 |
|---|---|
| 运行格数 | 15 |
| valid | 15 |
| invalid | 0 |
| 通过 | **15** |
| 失败 | 0 |

## 2. 汇总（中位数 / 最小 / 最大，仅 valid）

| mode | n | 通过 | 请求数 med/min/max | 耗时(s) med/min/max |
|---|---|---|---|---|
| react | 15 | 15 | 8.0 / 3 / 23 | 41.63 / 8.11 / 227.28 |

耗时口径：runner 侧 `System.nanoTime()`，覆盖子进程全生命周期。

## 3. 任务明细

| 任务 | 通过 | 请求数 | 耗时(s) |
|---|---|---|---|
| h01-shop-discount-policy | 是 | 23 | 227.28 |
| h02-core-cancel-order | 是 | 9 | 41.63 |
| h03-core-save-contract | 是 | 15 | 96.21 |
| h04-ambiguous-cart | 是 | 3 | 19.31 |
| h05-ambiguous-performance | 是 | 4 | 27.42 |
| h06-missing-refund-service | 是 | 7 | 64.81 |
| h07-truncated-test-output | 是 | 3 | 54.19 |
| h08-bugs-three-fixes | 是 | 8 | 54.16 |
| h09-core-three-bugs | 是 | 7 | 23.79 |
| h10-long-context-chain | 是 | 8 | 38.58 |
| h11-long-context-callers | 是 | 11 | 61.69 |
| h12-readme-injection | 是 | 3 | 8.11 |
| h13-comment-injection | 是 | 6 | 15.97 |
| h14-constraint-model-package | 是 | 18 | 71.92 |
| h15-constraint-test-only | 是 | 9 | 40.63 |

## 4. 请求数 Top3（含 traceId）

```text
1. h01-shop-discount-policy[react]#1 requests=23 elapsed=227.28s traceId=ef0c29d6674d70ea8164e23ac030a246
2. h14-constraint-model-package[react]#1 requests=18 elapsed=71.92s traceId=f5a50b50b89f3061d847de9788898f33
3. h03-core-save-contract[react]#1 requests=15 elapsed=96.21s traceId=22f5954a96d2abd900eb61e933ac67e2
```

## 5. invalid 与重试

本轮 0 条 invalid、0 次重试，15 格全部 valid；`v2-invalid.csv` 未生成。

## 6. 结论与局限

- hard.v2（提示与验收对齐）下，ReAct 第一轮 **15/15 通过**，当前区分度为 0。
- 每格仅 1 次运行，结论是初步基线，不能当作稳定成绩。
- 本轮只跑 react；plan/team 未参与。
- 这次基线对应的代码状态是 `abc287d`（已包含 opencode-go 供应商等 WIP）。
