# baseline 目录说明

| 文件 | 状态 | 说明 |
|---|---|---|
| `baseline-opencode-go-v3.csv` | **正式 baseline** | `agent_commit` 02750c8 / `eval_rules_commit` 31cc6cb；10 任务 × ReAct × 3 次 |
| `baseline-opencode-go-v3.meta.json` | **有效** | 记录 `agent_commit` 与 `eval_rules_commit` |
| `baseline-opencode-go-v2.csv` | 已废弃，仅作历史记录 | 禁止用于对比 |
| `baseline-opencode-go.csv`（v1） | 已废弃，仅作历史记录 | 禁止用于对比 |
| `invalid_runs.csv` | 有效 | 无效运行原始记录，只追加 |
| `t07-*.json` | 证据 | 单格重跑的原始结果 |

口径见仓库根目录 `EVAL_RULES.md`。
