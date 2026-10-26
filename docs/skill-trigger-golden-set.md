# Skill 触发金标集

> 用途：衡量模型看到 Skill 索引后是否在该加载时加载、不该加载时不加载。本文件只是数据；真实模型统计按 `AGENTS.md` 评测约定，需用户逐次授权后执行，结果写进 `benchmarks/` 下的 RUNBOOK。离线行为（注入时机、去重、压缩保活、截断、信任边界）由 `mvn test` 覆盖，不在这里。

## 统计方式

- **触发准确率**：期望触发的条目里，首个工具调用批次之前或之中调用了期望 Skill 的比例；期望不触发的条目里，整轮没有调用 `load_skill` 的比例。两者分开报告，不合成一个分数
- **首轮完成率**：一次用户输入内完成任务、没有追问澄清也没有中途失败的比例
- **Token 消耗**：每条的输入 / 输出 / 缓存命中 Token，对比关闭对应 Skill（`/skill off`）时的同一条
- 每条只跑一次，不挑子集，不重试；provider 调用失败记为评测无效，不计入分母

## 环境

- 启用内置 `web-access`、`better-harness`
- 项目级 `.paicli/skills/code-review/SKILL.md`（按 `docs/articles/paicli-skill-system.md` 第 10 节的示例），已 `/skill trust code-review`
- 每条在新会话中执行（`/clear`），ReAct 模式

## 条目

| # | 用户输入 | 期望 | 说明 |
|---|---|---|---|
| 1 | 帮我看下 https://mp.weixin.qq.com/s/xxxx 这篇文章讲了什么 | web-access | 已知 URL，SPA 站点 |
| 2 | 搜一下 Java 21 虚拟线程的最新基准测试结果，给我三个来源 | web-access | 需要联网搜索 |
| 3 | 打开我的小红书收藏夹，列出最近 5 条 | web-access | 需要登录态 |
| 4 | 帮我把 https://github.com/itwanger/paicli 的 README 总结成 5 条 | web-access | 已知 URL |
| 5 | 知乎专栏这篇 https://zhuanlan.zhihu.com/p/xxxx 的评论区主要观点是什么 | web-access | 懒加载站点 |
| 6 | 审查一下当前项目的 AI 编码工作流有什么问题 | better-harness | 与 `/better-harness` 同主题 |
| 7 | 看看这个仓库的 AGENTS.md、PAI.md 对 Agent 的约束是否够用 | better-harness | Harness 资产审查 |
| 8 | 帮我 review 一下 src/main/java/com/paicli/tool/LoadedSkillMessages.java | code-review | 项目级 Skill |
| 9 | 这次改动有没有 SQL 注入或者路径遍历风险，帮我查一下 | code-review | 匹配安全维度 |
| 10 | 帮我检查 ToolRegistry 里有没有方法超过 50 行、嵌套太深 | code-review | 匹配风格维度 |
| 11 | 当前项目用的是哪个 Java 版本 | 不触发 | 本地文件即可回答 |
| 12 | 把 README 里的“第十五期”改成“第 15 期” | 不触发 | 纯本地编辑 |
| 13 | 解释一下 ReAct 和 Plan-and-Execute 的区别 | 不触发 | 常识问答 |
| 14 | 运行 mvn test -Dtest=LoadSkillToolTest -DskipTests=false | 不触发 | 纯命令 |
| 15 | 《深入理解 Java 虚拟机》 | 不触发 | 裸标题，应先澄清，不联网 |
| 16 | 不要联网，根据你已知的信息介绍一下 MCP 协议 | 不触发 | 用户明确禁止联网 |
| 17 | 给 SkillStateStore 加一个单测 | 不触发 | 写代码，不是审查 |
| 18 | 先帮我看下 https://juejin.cn/post/xxxx，再按文章里的方法 review 一下 LoadedSkillMessages | web-access + code-review | 两个 Skill，各加载一次 |
| 19 | （接第 1 条同一会话）再看一篇 https://mp.weixin.qq.com/s/yyyy | 不再加载 | 已加载过，应直接按前面的指引 |
| 20 | （接第 8 条同一会话，执行 `/compact` 后）继续 review ToolRegistry.java | 不再加载 | 压缩后正文仍在 |
