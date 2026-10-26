# Skill 系统升级计划

> 状态：已执行（2026-10-07，见文末“执行记录”） · 编写日期：2026-10-07 · 基线提交：`e947b81`（main）
>
> 本文是给执行 Agent 和维护者的改造清单，自包含、可直接照着推进。开工前先读仓库根 `AGENTS.md`（文档联动硬规则）和 `docs/phase-15-skill-system.md`（第 15 期原始设计）。

## 1. 结论先行

第 15 期最受质疑的设计是 **load_skill 的正文要等用户下一条消息才注入**。这一点已在 `cc864aa fix(skill): inject loaded skill body in the same turn` 修复：共享的 `SkillContextBuffer` 已删除，改为 `LoadedSkillMessages` 把本批工具结果里成功加载的 Skill 正文作为独立 user 消息，紧跟在工具结果之后追加，同一轮下一次 LLM 请求即可看到。ReAct、Plan、Team 三条路径都有 `LoadSkillSameTurnTest` 覆盖。

但按代码实际行为复核后，Skill 系统还有 **3 个 P0 缺陷**、**3 个 P1 改进**、**3 个 P2 收尾项**。其中最严重的是上下文压缩：Skill 正文以 user 角色注入，压缩器把它当成真实用户轮次，既挤占保留轮次，又会把正文摘要掉，而工具描述又要求模型同一会话不重复加载，结果是压缩之后 Skill 指引永久丢失。

| 编号 | 优先级 | 问题 | 一句话方案 |
|---|---|---|---|
| S1 | P0 | 压缩把 Skill 注入当作用户轮次，并把正文摘要掉 | 注入消息可识别，压缩时跳过计数，压缩后重新挂载已激活 Skill |
| S2 | P0 | 会话级去重只写在工具描述里，代码没有落地 | 每个 Agent 实例维护已激活 Skill 集合，重复加载只返回确认 |
| S3 | P0 | 正文截断提示让模型去调 `/skill show`，这是用户命令，模型调不了 | 截断提示改为 `read_file` 读 SKILL.md 绝对路径 |
| S4 | P1 | 注入正文不带 Skill 目录路径，用户级和项目级 Skill 的 references/ 无从定位 | 注入头部带上 Skill 根目录和 references 目录的绝对路径 |
| S5 | P1 | System Prompt 中按查询变化的 Project Context 排在静态段之前，前缀缓存每轮失效 | 静态段前移，按稳定度从高到低排序 |
| S6 | P1 | 项目级 Skill 来自仓库，却以可信 user 消息注入，存在 Prompt 注入面 | 项目级 Skill 首次加载需用户确认，注入头部标明来源 |
| S7 | P2 | 单个正文有 5KB 上限，但同一会话加载总量没有预算 | 增加会话级 Skill 正文预算，超出时提示先完成当前任务 |
| S8 | P2 | 没有 Skill 触发质量的离线回归 | 增加离线断言测试和触发金标集，真实模型评测只在用户授权后跑 |
| S9 | P2 | `phase-15-skill-system.md` 仍描述 buffer 和下一轮注入 | 文档标注已废弃段落，指向本文 |

## 2. 对照的标准做法

Agent Skills 的标准做法是三级渐进加载（Claude Code、Claude Agent SDK 一致）：

1. **元数据常驻**：启动时只把每个 Skill 的 name 和 description 放进系统提示，每个几十个 Token，让模型知道何时该用哪个 Skill。
2. **正文进入当前对话**：模型判断相关时读取完整 SKILL.md，正文作为对话内容出现在当前这一轮，而不是改写系统提示。系统提示全程不变，前缀缓存稳定；正文追加在消息历史里，当轮生效。
3. **附属文件再按需读**：SKILL.md 引用的 references 文档用到才读；scripts 按引用执行，代码本身不进上下文，只有输出进入。

PaiCLI 当前对照情况：

| 层级 | 标准做法 | PaiCLI 现状 | 差距 |
|---|---|---|---|
| 元数据 | name + description 常驻系统提示 | `SkillIndexFormatter` 生成索引段，≤20 个、≤4KB | 已对齐；但索引段位置影响缓存（S5） |
| 正文 | 当轮进入对话 | `LoadedSkillMessages` 同轮注入（`cc864aa`） | 已对齐；压缩、去重、截断有缺陷（S1–S3） |
| 附属文件 | 相对 Skill 目录按需读取 | 内置 Skill 在 SKILL.md 里写死 `~/.paicli/skills-cache/...` | 用户级、项目级 Skill 拿不到目录路径（S4） |
| 压缩 | 已激活 Skill 不能在压缩中丢失 | 正文被当成普通 user 消息摘要 | 缺失（S1） |

## 3. P0 改造项

### S1 压缩丢失 Skill 正文

**问题**

`Agent.appendLoadedSkillMessage` 以 `LlmClient.Message.user(skills)` 追加正文（`Agent.java:551`，Plan 在 `PlanExecuteAgent.java:819`，Team 在 `SubAgent.java:187`）。`ConversationHistoryCompactor.userIndices` 只按 `role == user` 统计轮次（`ConversationHistoryCompactor.java:316`），于是：

1. 一次 load_skill 就会多出一个假的用户轮次。默认只保留最近 3 轮（`DEFAULT_RETAIN_RECENT_ROUNDS = 3`），Skill 注入和 `runaway_reminder` 都会挤占真实用户轮次，导致更多真实上下文被压进摘要。
2. 正文落在分割点之前时会被喂给摘要模型。摘要提示词要求按用户要求和已确认决定整理，正文会被压缩成一两句，甚至被误记为用户要求。
3. 压缩后模型看不到正文，但工具描述写着不要在同一会话重复加载同一 skill，模型大概率不会重新加载。

**方案**

1. `LoadedSkillMessages` 增加稳定的注入标记常量（例如以 `[PAICLI_SKILL_INJECTION]` 开头的首行）和静态方法 `isSkillInjection(Message)`；`runaway_reminder` 同理给出 `isRuntimeReminder(Message)`。
2. `ConversationHistoryCompactor.userIndices` 跳过这两类内部 user 消息，只把真实用户输入算作轮次；`SessionMemoryCompactor` 中按 user/assistant 统计的地方（`SessionMemoryCompactor.java:258`）同步处理。
3. 摘要输入中剔除 Skill 注入消息，不让摘要模型处理正文。
4. 每个 Agent 实例维护 `activeSkills`（见 S2）。压缩完成后，若有 Skill 正文被移出历史，就在摘要消息之后重新挂载一条合并后的 Skill 注入消息。三条路径的挂载点分别在 `Agent.java:424/477`、`PlanExecuteAgent.java:260`、`SubAgent.java:140` 的压缩成功分支之后。
5. `ToolResultClearer` 只处理 `role=tool`，不受影响，保持不变。

**改动文件**：`LoadedSkillMessages.java`、`ConversationHistoryCompactor.java`、`SessionMemoryCompactor.java`、`Agent.java`、`PlanExecuteAgent.java`、`SubAgent.java`

**验收**

- 新增 `SkillSurvivesCompactionTest`：构造含 load_skill 的长历史，触发自动压缩和 `/compact`，断言压缩后的下一次请求仍包含正文标记；Plan、Team 路径各一个用例。
- 新增 `CompactorIgnoresInternalUserMessagesTest`：3 个真实用户轮次加 2 条 Skill 注入，断言保留的是 3 个真实轮次。
- `LoadSkillSameTurnTest` 全部通过。

### S2 会话级去重未落地

**问题**

`load_skill` 的工具描述和索引段都写着不要重复加载同一 skill（`ToolRegistry.java:819`、`SkillIndexFormatter.java`），但代码层面只在同一批工具结果内去重（`LoadedSkillMessages.from` 中的 `putIfAbsent`）。模型在后续迭代再调一次，就会再注入一份完整正文。约束只靠提示词，是概率性的。

**方案**

1. 在 `Agent`、`PlanExecuteAgent` 的单个任务上下文、`SubAgent` 各自维护 `Set<String> activeSkills`，不放在 `ToolRegistry` 或任何共享对象上，保持 `cc864aa` 之后的并行隔离。
2. `LoadedSkillMessages.from` 增加 `activeSkills` 参数：已激活的 Skill 不再注入正文。
3. load_skill 执行器对已激活的 Skill 返回确认文案：该 Skill 已加载，正文在历史中，直接按指引继续。执行器拿不到 Agent 状态时，由调用方在注入前改写结果文案即可。
4. `/clear` 清空 `activeSkills`；S1 中压缩导致正文被移出且未重新挂载时，从集合中移除，允许重新加载。

**改动文件**：`LoadedSkillMessages.java`、`ToolRegistry.java`、`Agent.java`、`PlanExecuteAgent.java`、`SubAgent.java`

**验收**

- 新增用例：同一会话两次 load_skill 同名 Skill，断言历史中正文只出现一次，第二次工具结果为已加载确认。
- 并行 Plan 任务各自加载同名 Skill，断言互不影响。

### S3 截断提示指向用户命令

**问题**

`LoadedSkillMessages.truncatedBody` 超过 5KB 时追加 `full content via /skill show <name>`（`LoadedSkillMessages.java:62`）。`/skill show` 是 CLI 用户命令，不是模型可调用的工具，模型无法按提示拿到完整内容。

**方案**

截断提示改为给出 `skill.skillMdPath()` 的绝对路径，提示模型用 `read_file` 读取剩余部分。内置 Skill 的 `skillMdPath` 需要指向 `~/.paicli/skills-cache/<name>/SKILL.md`，若当前只解压了 references，`SkillBuiltinExtractor` 同步解压 SKILL.md。

**改动文件**：`LoadedSkillMessages.java`、`SkillBuiltinExtractor.java`（视情况）

**验收**：新增用例，6KB 正文截断后提示里包含可读取的绝对路径，且不再出现 `/skill show`。

## 4. P1 改造项

### S4 注入正文带上 Skill 目录

**问题**

`Skill` 记录里有 `skillMdPath` 和 `referencesDir`，但 `LoadedSkillMessages` 只注入正文。内置 web-access 在 SKILL.md 里写死了 `~/.paicli/skills-cache/web-access/references/`，用户级和项目级 Skill 的作者无法预知安装路径，标准写法 `references/xxx.md` 在 PaiCLI 里没法用。

**方案**

注入头部改为：

```
## 已加载 Skill：<name>（来源：builtin/user/project）
Skill 目录：<skill 根目录绝对路径>
文中的 references/、scripts/ 相对路径都以此目录为基准，用 read_file 按需读取，用 execute_command 执行脚本。
```

scripts 继续走 `execute_command` 和既有 HITL，不注册成虚拟工具，保持第 15 期的约束。内置 web-access 的 SKILL.md 可改回相对路径写法，写回机制里指向用户级目录的那一句保留绝对路径。

**改动文件**：`LoadedSkillMessages.java`、`src/main/resources/skills/web-access/SKILL.md`

**验收**：项目级测试 Skill 的 SKILL.md 引用 `references/a.md`，断言注入消息包含该 Skill 的绝对目录。

### S5 System Prompt 前缀稳定性

**问题**

`PromptAssembler.assemble` 的顺序是 base → personality → mode → approval → runtime → **Project Context** → Skills → context-management → handoff。其中 Project Context 里的 `memoryContext` 来自 `memoryManager.buildContextForQuery(userInput, ...)`（`Agent.java:199`），每条用户输入都会变。它后面的 Skills 索引和两个静态资源段，每个用户轮次都会因为前缀变化而失去缓存。`ROADMAP.md` 第 511 行声称遵循把易变内容放最后的原则，与代码不符。

**方案**

按稳定度从高到低排序：base → personality → mode → approval → context-management → handoff → Skills 索引（会话内稳定，只在 `/skill on/off/reload` 时变）→ runtime（按天变）→ Project Context（按查询变）。调整前确认 `context-management.md`、`handoff.md` 没有依赖前文顺序的措辞。

**改动文件**：`PromptAssembler.java`、`PromptAssemblerTest`、`ROADMAP.md` 第 511 行

**验收**

- `PromptAssemblerTest` 断言新顺序。
- 两次不同用户输入组装出的系统提示，最长公共前缀覆盖到 Skills 索引段末尾。
- 有 provider 返回缓存命中 Token 的话，手工对比改造前后同一会话第二轮的命中数（真实模型调用需按 `AGENTS.md` 评测约定，由用户授权后执行）。

### S6 项目级 Skill 的信任边界

**问题**

`LoadedSkillMessages` 的类注释说明正文不放进工具结果，是因为 Skill 是本地可信指引。这对内置和用户级 Skill 成立，但项目级 Skill 位于 `<project>/.paicli/skills/`，随仓库分发。克隆一个陌生仓库后，其中的 SKILL.md 会以 user 消息身份进入上下文，模型会把它当作可信指令执行，这是一个 Prompt 注入面。

**方案**

1. 项目级 Skill 在一个项目内首次被 load_skill 时，走一次用户确认，展示 Skill 名、来源路径和正文前若干行；确认结果按项目路径记入 `SkillStateStore`。
2. 未确认前，正文按工具结果的不可信数据处理（经 `ToolResultBoundary` 包装），并在提示中说明需用户确认后才可作为指引执行。
3. 注入头部始终标明来源（见 S4），`/skill list` 中项目级 Skill 显示信任状态。

**改动文件**：`SkillStateStore.java`、`LoadedSkillMessages.java`、HITL 相关交互、`SkillCommandHandler.java`、`README.md`

**验收**：未信任的项目级 Skill 被加载时正文带不可信数据边界；确认后再次加载走正常注入。

## 5. P2 收尾项

### S7 会话级正文预算

单个正文已有 5KB 上限，但没有总量控制。增加会话级预算（建议 16KB，可配置），`activeSkills` 正文总量超出时，新的 load_skill 返回提示：当前已加载的 Skill 已占用预算，先完成当前任务或使用 `/skill` 清理。不做按数量的硬上限，也不做先进先出淘汰，这两点正是第 15 期被质疑的旧设计。

### S8 Skill 触发质量回归

`AGENTS.md` 约定评测暂停期间不主动发起真实模型调用，所以分两层：

1. **离线层（本期必做）**：用 `QueueClient` 这类假模型覆盖注入时机、去重、压缩保活、截断路径、信任边界，全部进 `mvn test`。
2. **金标层（本期只建数据）**：新建 `docs/skill-trigger-golden-set.md`，列出 20 条左右任务描述，标注期望触发的 Skill 或期望不触发。真实模型统计触发准确率、首轮完成率和 Token 消耗，需用户逐次授权后执行，结果写进 benchmark 目录的 RUNBOOK。

### S9 文档清理

- `docs/phase-15-skill-system.md` 的 §3.5、§3.6、§4 文件清单中关于 `SkillContextBuffer`、下一轮注入、最多保留 3 个的描述，统一加已废弃标注，指向本文和 `cc864aa`。
- `docs/articles/` 中介绍 Skill 加载机制的文章同步修正，改完按 `AGENTS.md` 用 `tools/sync-articles-to-javabetter.sh` 同步。
- `README.md` 中 Skill 相关说明按新行为更新。

## 6. 执行顺序

1. **S2 → S1**：先有 `activeSkills`，压缩保活和重新挂载才有依据。两者合一个提交。
2. **S3、S4**：都改 `LoadedSkillMessages` 的注入格式，合一个提交。
3. **S5**：独立提交，只动 `PromptAssembler` 和文档。
4. **S6**：涉及交互，独立提交，补手工验证记录。
5. **S7、S8、S9**：收尾。

每一步提交前跑 `mvn test`，并确认 `LoadSkillSameTurnTest` 的三条路径全部通过。

## 7. 面试讲法

这部分改造本身就是 PaiCLI 最好讲的一段工程经历。被问到 Skill 设计时，按五步讲：主动承认问题，说明当初考虑，讲清问题和影响，对标标准做法，给出改造和代价。

参考回答：

第一版 Skill 加载有设计问题。当时把正文放进缓冲区，下一轮用户消息才注入，考虑的是不让一轮推理中途上下文变化。实际用下来，用户触发 Skill 的那一轮恰恰最需要它却用不上。对照 Agent Skills 的三级渐进加载后，把正文改为紧跟工具结果、在同一轮进入对话，系统提示只保留 Skill 索引，缓存不受影响。修完之后又发现一个更隐蔽的问题：正文以 user 消息注入，上下文压缩把它当成真实用户轮次，既挤占保留轮次，又会把正文摘要掉，而模型被要求不重复加载，于是压缩后指引永久丢失。解决办法是让注入消息可识别，压缩时不计入用户轮次也不参与摘要，压缩后按会话内已激活的 Skill 重新挂载，同时把去重从提示词约束改成代码里的确定性约束。代价是每个 Agent 要多维护一份已激活状态，正文长期驻留历史，所以又加了会话级的正文预算。

## 8. 执行记录（2026-10-07）

按代码复核后，实现与上文方案有四处偏差，以这里为准：

1. **S2 已激活集合从历史推导**：不在 Agent 上另存 `Set<String> activeSkills`，而是由 `LoadedSkillMessages.prepare(results, registry, history)` 扫描调用方自己的消息历史得出。ReAct 会话、每个 Plan 任务、每个 Team worker 天然隔离；压缩移出注入、`/clear` 清空历史后，集合和预算都自动释放，不存在另存一份状态、与历史不一致的问题。会话预算（S7）同样按历史里注入消息的长度计算。
2. **S1 在压缩器里统一处理**：最初的实现是 `ConversationHistoryCompactor.rebuildWithSummary` 把分割点之前的 Skill 注入原样挂回摘要之后；后来（提交 `c08159f`）改成直接删掉落在摘要范围里的注入，在摘要末尾列出这些 Skill 的名字，提示模型需要时重新 `load_skill`，16KB 正文预算也随之释放。完整摘要和 Session Memory 两条路径共用这个方法，三条执行路径不需要各自在压缩成功分支里补处理。注入标记 `[PAICLI_SKILL_INJECTION]` 与 `[runaway guard]` 的识别放在 `memory/InternalUserMessages`（`tool` 依赖 `memory`，反向依赖会成环）。副作用：Plan 任务只有一条真实 user 消息（task_input），之后不再能借 Skill 注入 / runaway 提醒当分割点做摘要，只剩工具结果清理。
3. **S3/S4 不用 `read_file`**：PathGuard 强制所有文件工具在项目根内，`read_file` 读不到 `~/.paicli/skills-cache/` 和 `~/.paicli/skills/`。改为给 `load_skill` 增加 `file`（+ offset/limit）参数，只读当前 Skill 自己的目录（realpath 校验），传目录则列出文件；截断提示给出 `load_skill(name, file="SKILL.md", offset=N)`。内置 web-access 的 SKILL.md 同步改用这个调用，`SkillBuiltinExtractor.CURRENT_VERSION` 升到 `1.1.2`。
4. **S6 不弹审批框**：auto 模式约定不弹框、微信通道没有审批面板，load_skill 中途插入交互会让无人值守通道卡住。改为未信任时正文由执行器放进工具结果（经 `ToolResultBoundary` 包成 untrusted-data），用户 `/skill show` 确认后 `/skill trust <name>` 显式信任；信任按 SKILL.md 绝对路径 + 正文 SHA-256 记在 `skills.json` 的 `trustedProjectSkills`，正文改动需重新信任。

未做：S5 真实模型缓存命中对比、S8 金标真实评测（均需用户授权真实模型调用）；Plan 模式 mode 段里的 `{{taskDescription}}` 仍在 Skills 索引之前，Plan 任务之间前缀只能命中到 approval 段。
