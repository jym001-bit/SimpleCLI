# evals/out 目录索引

## 命名说明（先读这一段）

项目里有两个“v3”，不要混：

- **冒烟集 v3 基线**：`evals/baseline/baseline-opencode-go-v3.csv`，10 个冒烟任务 × 3 次，`agent_commit 02750c8` / `eval_rules_commit 31cc6cb`。本目录的 `smoke/baseline-v3/` 是它的原始记录（旧 9 列 CSV 格式）。
- **困难集 hard.v3**：`evals/tasks/hard.v3.json`，`agent_commit 08d6af9`。本目录的 `hard/v3/` 是它的运行结果。

两者不是同一套任务，也不是同一版基线。

## 目录表

| 目录 | 内容 | 任务文件 | agent_commit | eval_rules_commit | 状态 | 备注 |
|---|---|---|---|---|---|---|
| `smoke/baseline-v3/` | 冒烟集 30 格基线原始记录（json/csv/log） | `evals/tasks/tasks.json` | `02750c8` | `31cc6cb` | **正式基线（原始记录，旧格式）** | 与 `evals/baseline/baseline-opencode-go-v3.csv` 同源 |
| `smoke/modes-3b/` | 3b ReAct/Plan 初版对照（10 任务 × 2 模式 × 1 次）；report + raw + Team 证据 + 重复原始 | `evals/tasks/tasks.json` | `5f9dce8`（report 记 `e82f9c6`） | `470b113` | 已被取代（Team 未参与） | Team 失败证据在 `evidence/` |
| `smoke/selftest/` | runner 自测、valid 判定、LLM 错误、预算自测 | `evals/selftest/*`、`tasks.json` | `68d70db`（validity） | `68d70db` | 调试残留 | 无长期结论 |
| `smoke/t07/` | t07 单任务重跑与重试 | `tasks.json`、t07 任务副本 | 未记录 | 未记录 | 调试残留 | — |
| `smoke/smoke-runs/` | 早期 smoke 单任务运行 | `tasks.json` | `470b113` | `470b113` | 调试残留 | — |
| `hard/v1-3d/` | hard.json v1 第一轮（15 格 ×1，6/15） | `evals/tasks/hard.json` | `5bde89d` | `470b113` | 能力结论无效（提示/验收口径过松） | report 在目录根，raw 在 `raw/` |
| `hard/v2/` | hard.v2 基线（15 格 ×1，15/15） | `evals/tasks/hard.v2.json`（`15d95b6`） | `abc287d` | `470b113` | 能力结论无效（验收太松） | 同上 |
| `hard/v3/` | hard.v3（15 格 ×1） | `evals/tasks/hard.v3.json`（`08d6af9`） | `08d6af9` | `470b113` | 已被取代（v4 收紧验收） | 只有 raw |
| `hard/v4/` | hard.v4（15 格 ×1，14/15） | `evals/tasks/hard.v4.json`（`8bbfc02`） | `8bbfc02` | `470b113` | 已被取代（h10 提示口径） | 只有 raw；h10 由 v5 修正 |
| `hard/v5/` | hard.v5（h10 提示修正）原始运行 | `evals/tasks/hard.v5.json`（`4f63bcf`） | `7147c5e` | `470b113` | **当前任务版本（配套 W1）** | 只有 raw；结论在 `whitebox/h10-v5-check.md` |
| `whitebox/` | hard.v4/v5 的白盒基线、W1/W1+v5 对比、h10/h04 复测、change-log、简历素材 | `hard.v4.json`、`hard.v5.json` | `f8a2899`、`7147c5e` | `470b113` | **正式记录** | 结论 md 在根，raw 在 `raw/` |
| `audit/` | 口径审计、system prompt 审计 | — | — | — | 审计记录 | 不是能力基线 |
| `_unsorted/` | 提交说明草稿、来源不明的早期无效记录 | — | — | — | 无法判断 | 不猜，原样保留 |

## 历史位置（按“只复制、不 git mv”的确认执行）

三个已跟踪目录**保留在原位置**，作为历史原件；`smoke/modes-3b/`、`hard/v1-3d/`、`hard/v2/` 是它们的整理副本：

- `3b-mode-compare/` → 副本 `smoke/modes-3b/`
- `3d-react/` → 副本 `hard/v1-3d/`
- `v2-react/` → 副本 `hard/v2/`

原件保持被 git 跟踪；整理副本未跟踪。这样历史提交里的路径不变，`git log --follow` 和已写进报告/简历的提交号都不受影响。

## 原始数据与备份

- `whitebox/raw/`、`hard/*/raw/`、`smoke/*/raw/` 中的 json/csv/log **不在版本库中**（未跟踪）。
- 原始数据的外部备份：`C:\Users\l\OneDrive\SimpleCLI-backup\evals-out-20261114.zip`。
- 对应的 worktree（`D:\pailcli\wt-detail`、`wt-w1`、`wt-v3`、`wt-v4` 等）仍保留未提交原始结果；worktree 本身未移动、未删除。

## 未纳入本轮整理的文档

- `docs/eval-blackbox-guide.md`（未跟踪草稿）仍使用旧路径 `out/3d-react/`；按要求未修改，避免把草稿带入提交。

## 维护约定

- 顶层 `budget-work/` 是空目录（预算自测的 workdir 占位），本轮未动。
- 新运行按阶段放入 `smoke/`、`hard/vN/`、`whitebox/raw/`；不要在 `evals/out` 根目录新增散文件。
- 结论性 `.md` 放阶段目录根；原始 `json/csv/log` 放 `raw/`（或 `evidence/`、`duplicates/`）。
- 新增/移动/复制文件时更新 `_reorg-manifest.csv`，保留旧路径、新路径和前后 sha256。
