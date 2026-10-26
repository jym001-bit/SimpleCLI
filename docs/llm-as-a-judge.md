# LLM-as-a-Judge 离线评测教程

PaiCLI 的 `com.paicli.eval` 包提供两个可独立调用的评测组件：

- `LlmJudge`：按 Rubric（评分规则）对单个 Agent 输出逐维打分，适合 Reference-based 或 Pointwise 评测。
- `PositionBalancedPairwiseJudge`：对 baseline 和 candidate 做两次 A/B 对比，第二次交换位置；只有两次都指向同一个逻辑候选时才接受胜者。

当前实现是评测核心库，不是 `/eval` CLI 命令，也不会自动读取 `~/.paicli/history/raw`。调用方需要显式提供候选答案、参考答案和经过筛选的轨迹，避免把完整会话记录中的密钥、图片和完整系统提示词直接交给 Judge。

## 为什么总分要由 Java 计算

LLM 只返回各维度的 1–5 分、证据和硬性失败项。权重、总分和是否通过由 Java 计算。

这样可以避免 Judge 在输出中自行修改及格线，也便于对 Rubric 做版本控制。模型返回未知维度、重复维度、缺少维度、越界分数或非法 JSON 时，评测会失败，而不是悄悄把异常结果当成 0 分。

## Pointwise 示例

```java
LlmJudge judge = new LlmJudge(judgeClient, 80);

LlmJudge.EvaluationCase evalCase = new LlmJudge.EvaluationCase(
        "shopping-001",
        "推荐一台 5000 元以内、明天能送达的笔记本电脑",
        "不得超预算；库存和配送时效必须实时校验；下单前必须确认",
        agentAnswer,
        filteredTrajectory);

List<LlmJudge.Rubric> rubrics = List.of(
        new LlmJudge.Rubric(
                "constraint_satisfaction",
                "预算、库存和配送时效均满足；违反任一项即为硬性失败",
                50,
                true),
        new LlmJudge.Rubric(
                "tool_use",
                "在正确时机调用价格、库存和配送工具，参数与用户约束一致",
                30,
                true),
        new LlmJudge.Rubric(
                "recommendation_quality",
                "推荐理由与用户用途一致，不用文风掩盖事实缺失",
                20,
                false));

LlmJudge.EvaluationResult result = judge.evaluate(evalCase, rubrics);
System.out.printf("case=%s score=%.1f passed=%s%n",
        result.caseId(), result.weightedScore(), result.passed());
```

`filteredTrajectory` 只保留评测所需的工具名、脱敏参数、结果状态和最终答案。不要默认把完整会话记录整体传入 Judge。

## Pairwise 示例

```java
PositionBalancedPairwiseJudge pairwise =
        new PositionBalancedPairwiseJudge(judgeClient);

PositionBalancedPairwiseJudge.PairwiseResult result = pairwise.compare(
        userInput,
        referenceAnswer,
        baselineAnswer,
        candidateAnswer,
        "先比较事实正确性和业务约束，再比较表达；不得偏好更长的回答");

System.out.println(result.winner());
System.out.println(result.positionConsistent());
```

第一次输入顺序是 `baseline=A, candidate=B`，第二次是 `candidate=A, baseline=B`。如果 Judge 两次都选择 A，说明判断随位置变化，最终结果会降级为 `TIE`，同时把 `positionConsistent` 标记为 `false`。

## 一套可落地的评测流程

1. 先用程序化断言检查预算、工具名、参数 Schema、幂等键等确定性规则。确定性事实不交给 LLM 猜。
2. 对无法写成规则的语义质量使用 `LlmJudge`，每条 Rubric 写清 1–5 分锚点和硬性失败条件。
3. 对新旧版本做 `PositionBalancedPairwiseJudge` 双向盲测，分别统计 Win、Tie、Loss 和位置一致率。
4. 人工标注一批校准集，比较 Judge 与人工的逐条结果。发生分歧时先改 Rubric，不要只替换一个更贵的模型。
5. 每次报告保存数据集版本、Rubric 版本、Judge provider/model、失败样本和 token。provider 没有返回 token 时标记为未知，不能按 0 成本统计。

## 当前边界

- 没有独立的评测数据集加载器、批量并发 Runner、持久化报告或 `/eval` 命令。
- 没有统一设置 Judge 的 temperature、seed 或 JSON Schema Structured Output，不能声称完全可复现或 100% 可解析。
- 没有与人工标注跑过一致率，也没有真实的业务提升百分比。
- `hardGate` 会随 Rubric 交给 Judge，当前硬性失败仍依赖 Judge 返回的 `hardFailures`；高风险业务规则还应在模型评测之前用确定性代码再校验一次。

## 验证

```bash
mvn test -Dtest=LlmJudgeTest,PositionBalancedPairwiseJudgeTest -DskipTests=false
mvn test -Pquick
```
