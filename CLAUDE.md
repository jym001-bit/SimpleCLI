@AGENTS.md

## Claude Code 补充

- 本仓库规则以 `AGENTS.md` 为准（上面已导入），这里只放 Claude Code 专属说明，保持简短
- 改 Plan 失败处理、重规划或 `parsePlan` 前，先看 `src/main/resources/benchmark/e1_replay.py` 的对应约束
- 单测必须带 `-DskipTests=false`，否则 `mvn test` 什么都不跑也会显示成功
- 评测相关实现和任何真实模型 / Docker 调用都需要用户逐阶段明确授权
