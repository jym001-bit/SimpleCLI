# AGENTS.md

AI 编码助手进入 PaiCLI 仓库时先读这个文件：Codex 直接读取，Claude Code 通过 `CLAUDE.md` 里的 `@AGENTS.md` 导入。这里只放每次改动都要遵守的规则；实现细节写在 `docs/agents-reference.md`，评测历史写在 `benchmarks/paicli-native-agentbench-v0.1/AGENTS-SNAPSHOT-ARCHIVE.md`。本文件控制在 200 行以内，新内容优先写进那两个文件。

写文档和回复用户都用大白话：类名、内部叫法、英文术语第一次出现时说明它是什么；不要生造“账本”“合同”“门禁”这类让人看不懂的叫法。

## 信息优先级

代码实际行为 > `AGENTS.md` > `PAI.md` > `README.md` > `ROADMAP.md` > `CLAUDE.md`。`ROADMAP.md` 写的是打算做什么，不代表已经做完。

## 项目概况

- 面向商业使用的 Java Agent 命令行工具，对标 Claude Code。已交付 23 期：ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP → 长上下文 → Chrome DevTools → CDP 复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 文本消息（最小可用版）
- 启动画面显示的版本是 `v16.1.0`，Maven 打出的 jar 是 `paicli-1.0-SNAPSHOT.jar`，两者不一致是正常的
- `PAI.md` 是项目级记忆，启动时放进 system prompt，写团队共享、长期不变的规则；个人的、会变化的事实用 `/save` 存成长期记忆
- 下一步：MCP 的 OAuth 登录、sampling。面试题对齐升级（`docs/interview-upgrade-plan.md`）已全部完成，里面记着各项的取舍和实测数据
- 配套教程和面试文章以 `docs/articles/` 为准，改完用 `tools/sync-articles-to-javabetter.sh` 同步到 javabetter.cn 的副本

### 评测（Native AgentBench）

- 2026-09-05 起，用户因为 Token 消耗太大暂停了评测：**不要主动继续做评测代码，也不要主动调用真实模型或跑 Docker**。只在用户明确同意某一步时执行那一步，默认不开子代理
- 题目生成器已接入 25/28 题（按原权重是 88/100），还缺 D5、E3、E4。`formalScores=null`、`publishable=false`、`NOT_INTEGRATED` 这些状态不变，不能对外说成正式榜单
- 正式评测只评 DeepSeek V4 Flash 和 GLM-5.3-Flash。缺少证据算评测无效，不算 PaiCLI 得 0 分；模型一次都没被调用，算 PaiCLI 失败。禁止多跑几次挑最好的、禁止只挑部分题、禁止自动重试
- 已知问题：E2 的 `validators/final/E2` 输出的是原始判定 JSON，不是统一格式的 `VerifierScoringReport`，所以 `FinalSourceGeneratorTest.referencePrototypes…` 失败；修它需要用户同意
- 评测统计工具调用时，从 `ToolRegistry.onPolicyToolResults` 取数据（包括被策略拒绝的调用，顺序不变），不能只看 `executeTools` 里被放行的那部分
- 细节见 `benchmarks/paicli-native-agentbench-v0.1/` 下的 RUNBOOK、FINAL-DATASET-RUNBOOK 和 AGENTS-SNAPSHOT-ARCHIVE

## 运行与命令

需要 Java 17+ 和 Maven；`ripgrep` 可选（`grep_code` 优先用它，没装就用 Java 自己扫描）。至少配一个 API Key：`DEEPSEEK_API_KEY`、`GLM_API_KEY`、`HUNYUAN_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY`、`FREELLMAPI_API_KEY`、`XFYUN_MAAS_API_KEY`、`AGNES_API_KEY`。

```bash
cp .env.example .env
mvn clean package                           # 默认跳过测试（pom 中 skipTests=true）
java -jar target/paicli-1.0-SNAPSHOT.jar
java -jar target/paicli-1.0-SNAPSHOT.jar wechat setup|start
mvn test -Pquick                            # 常规回归
mvn test -Pphase16-smoke                    # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性（必须带 -DskipTests=false）
mvn test -DskipTests=false                  # 全量
```

交互命令：`/plan`、`/team`、`/mode auto|plan|ask`（Shift+Tab 轮流切换）、`/hitl on|default`、`/save`、`/memory`、`/compact`、`/context`、`/init`、`/export`、`/resume`、`/better-harness`、`/wechat`。不认识的 `/xxx` 由命令行直接报“未知命令”，不交给模型处理。

`mvn test -Pquick` 目前有一批原本就失败的测试：评测包（`eval.benchmark.*`）和 `TerminalMarkdownRendererTest`；`ToolRegistryTest.macSandboxAllowsDevelopmentRuntimesAndDevNull` 在较新的 macOS 上，JVM 在 Seatbelt 沙箱里会以 139 退出。判断有没有引入新问题，要对比改动前后失败的测试名单，不要只看失败总数。

## 架构

三种执行方式共用 ToolRegistry、MemoryManager、SnapshotService、ConversationLedger：ReAct（`Agent.java`，默认）、Plan-and-Execute（`PlanExecuteAgent.java`，`/plan`）、Multi-Agent（`AgentOrchestrator.java`，`/team`）。

```
src/main/java/com/paicli/
├── agent/  cli/  plan/  memory/  context/  history/  prompt/
├── tool/ (ToolRegistry)  hitl/  policy/ (PathGuard, CommandGuard, AuditLog)
├── llm/ (8 个 provider 客户端 + LlmClientFactory)  web/  browser/  mcp/  skill/  rag/
├── lsp/  snapshot/  runtime/ (api + task)  image/  wechat/  harness/  render/  tui/
└── eval/ (LLM-as-a-Judge + benchmark)
```

- 内置工具 18 个：`read_file`、`write_file`、`edit_file`、`list_dir`、`glob_files`、`grep_code`、`execute_command`、`create_project`、`search_code`、`web_search`、`web_fetch`、`browser_connect`、`browser_disconnect`、`browser_status`、`save_memory`、`load_skill`、`save_skill_reference`、`revert_turn`。MCP 工具运行时注册，名字是 `mcp__{server}__{tool}`
- `edit_file` 默认只替换唯一一处匹配的 `old_text`，`replace_all=true` 时全部替换。精确匹配失败后，逐行统一写法再匹配（NFKC、行尾空白、引号、横线、特殊空格）；还失败、并且片段每行都带着 read_file 输出的行号前缀时，去掉行号前缀再试一次。CRLF 文件保留原来的换行符。新建文件用 `write_file`
- 读代码默认顺序：`glob_files` → `grep_code` → `read_file`；`search_code`（RAG）只用来做模糊的语义查找
- MCP 配置合并 `~/.paicli/mcp.json` 和项目里的 `.paicli/mcp.json`；检测到 `STEP_API_KEY` 时自动加上 `step_search`。项目级 server（包括覆盖用户级的同名 server）要 `/mcp trust` 确认后才启动，确认绑定配置内容指纹；stdio 子进程只继承 `McpChildEnvironment` 白名单里的环境变量，不要改回继承全部
- stdio server 意外退出或连续 2 次 ping 不回（判定卡死）时，移除工具、结束整棵进程树，按 1s/2s/4s 自动重启，连续 3 次没救回来就停下等用户 `/mcp restart`；提示攒在 `McpServerManager.drainNotices()`，等下一个输入框前打印，不在后台线程直接输出。ping 只发给本地 stdio server，server 正在处理请求时跳过
- 思考模型（DeepSeek Flash / V4 Pro、GLM-5.3、混元 Hy4、Kimi thinking）返回的思考内容 `reasoning_content` 必须在下一轮请求里带回去；各家接口差异和重试策略见 `docs/agents-reference.md`
- 手动管理模型：`/model list [provider]`、`/model refresh <provider>`、`/model add <provider> <id> [--like <同供应商模型>]`；模型支持什么能力保存在 `providers.<provider>.models`。刷新只新增不删除，添加后不自动切换，不知道能力的模型按 128k 纯文本处理；用户主动切换后才生效，保存失败时保留原来的选择；不靠实际调用模型来探测它支持什么
- DeepSeek 是默认供应商（`PaiCliConfig.defaultProvider`，多个 Key 都有时优先用 DeepSeek），默认模型 DeepSeek V4.1 Flash（模型 ID `deepseek-flash`），支持图片。官方旧名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 现在也由 V4.1 Flash 提供服务，同样支持图片；它们只写成兼容说明，不作为单独的内置模型，刷新时也不新增，用户已有的选择和手动配置保留并注明对应关系。`deepseek-v4-pro` 仍然只支持文本。以前评测记录里写的模型标识不跟着改
- 模型、MCP、Skill 列表统一用 `TerminalTable` 显示：按实际显示宽度处理中文和颜色控制符；窗口窄时把最后一列挪到下一行，或改成一条一条竖着显示，不截断模型 ID。列表只放用来比较的字段，单个模型的详细配置用 `/model info` 看
- 启动时用哪个模型：环境变量 → 项目 `.env` → 用户 `.env` → 上次保存的模型 → 默认值（`getStartupModel`）；会话里用 `/model` 主动选的优先。显示名只用在启动画面和状态栏，不能影响 API 请求、工具策略和评测记录里的模型 ID
- 交互过程中的输出走 `Renderer.stream()`，主流程不要新增直接的 `System.out.println`；inline 界面的显示约定见 `docs/agents-reference.md`

## 关键行为约束

### Memory 与上下文

- 短期上下文就是各个 Agent 实际发给模型的 `conversationHistory`，不要再额外维护一份消息副本
- 会话记录（`~/.paicli/history/raw/`，由 `ConversationLedger` 写入）只追加、不改写；`/resume` 靠它还原 ReAct 对话。所以任何直接改动 `conversationHistory` 的操作（压缩、清理旧工具结果、恢复会话）之后，都要写一条 `view_checkpoint`，否则恢复出来的对话和模型当时看到的对不上
- 上下文压缩按代价从小到大分三步：单个工具结果太大（默认超过 32000 字符）就写入文件，对话里只留说明 → 清理旧工具结果（触发线 `min(100k, 摘要阈值×0.6)`，只替换正文，保留 `tool_call_id`）→ 生成摘要（会话记忆摘要是实验功能，默认关；它失败时改用分四个栏目的完整摘要）。摘要阈值 = `window - min(20k, window/4) - min(13k, window/8)`；摘要栏目不全或压缩后 Token 没减少，就保留原来的历史
- 长期记忆默认只对当前项目生效（project），跨项目都适用的偏好才存成 global；只存长期不变的事实。用户明确要求保存时，先去重，再检查冲突（数字、版本号不同，或 Dice 相似度 ≥ 0.8 时不写入，由用户选 replace 或 force），模型不能替用户选；超过 30 天没核实的记忆标“可能已过时”
- 长期记忆文件会被多个 PaiCLI 进程同时读写：修改必须通过 `withStorageLock`（加锁后重新读取 → 修改 → 整体写回），不要绕过它直接写文件
- 自动提取记忆只能从用户原话里逐字摘取，最多 3 条、只存为 project 级、标“待核实”，重复或冲突的一律跳过；会话里读过网页、浏览器、MCP、curl 等外部内容后，暂停自动写入。`save_memory` 只在用户本轮明确要求“记住”时调用
- system prompt 里的记忆规则：记忆只是线索，不一定还对，行动前要对照当前文件核实
- Agent 循环不设固定轮数上限。同一个动作或同一类错误连续出现 3 步时，`RunawayGuard` 往对话里加一次 `[runaway guard]` 提醒（只提醒，不拦工具）；停滞检测默认看最近 5 步，这个数必须大于提醒的 3 步。超出预算或判定停滞后，关掉工具，让模型把已完成的部分整理成结果，不丢弃已有工作

### 工具执行

- ReAct/Plan/Team 的机器入口用 `runWithOutcome` / `runExplicitTaskWithOutcome` 传递现有 `ExecutionOutcome`，不能用正常返回或正文关键词推断完成。`content` 保留完整结果，`displayContent` 是尚需显示的文字，旧字符串 `run` 保留展示兼容。后台/API 的 `TaskRunner` 继续只接既有 ReAct 路径，预算停止保留内容但不映射 COMPLETED。
- 取消信号只从当前线程绑定的根任务继承，线程池执行任务时显式绑定；不能从全局变量借用另一个任务的 Token。后台任务的 canceled 表示已请求取消，`cleanup_state` 另记执行线程是否确认退出；不能把状态变化当成资源已经停止。

- `execute_command` 在 Windows 宿主机用 Windows PowerShell，在 Linux/macOS 用 Bash；命令沙箱启用时仍由对应沙箱构造命令。`glob_files` / `grep_code` 输出统一用 `/` 分隔相对路径
- 三种执行方式都通过 `executeTools()` 执行工具，不要自己写循环挨个调用
- 只有 `ToolRegistry.PARALLEL_SAFE_TOOLS` 里的只读工具可以并行（最多同时 4 个）；写文件、执行命令、MCP、`save_memory`、`revert_turn` 和不认识的工具都按原顺序一个一个执行。新增只读工具要加进这个名单。一批调用里只要有浏览器工具，整批都一个一个执行。结果按原来的顺序返回
- 工具执行前按 JSON Schema 校验参数（`ToolArgumentValidator`），不合格就把字段级错误还给模型。内置工具只拦结构问题（缺参数、传了对象 / 数组、不在 enum 里），单值参数的类型和 null 仍由工具自己解析，评测复算依赖这套语义；MCP 工具按 server 的原始 Schema 严格校验，不能用清洗后给模型看的那份
- 工具结果放进对话前，统一用 `ToolResultBoundary.wrap()` 包一层 `trust="untrusted-data"` 标记，表示这是外部数据，不代表允许做任何事；太大的结果由 `ToolResultOffloader` 写进 `.paicli/tool-outputs/`

### HITL（人工确认）与安全策略

- 检查顺序：TurnToolPolicy（网址来源等本轮工具策略）→ HitlToolRegistry → ToolRegistry（参数校验）→ PathGuard / CommandGuard。被安全策略拒绝的操作，用户也无法批准
- 会话模式（`SessionMode`、`SessionModeController`）：Shift+Tab 按 auto → plan → ask 轮流切换，`/mode` 效果相同。交互式命令行没有“全部放行”，最宽松的就是 auto。模式只是“确认严格程度 + 是否走 plan”的组合，不另外保存权限状态。plan 模式下普通输入走 Plan-and-Execute，用户明确输入的 `/team` 优先；启动时默认 auto。目前只在 inline 命令行界面生效，Lanterna 全屏界面还没接
- auto 模式（`/hitl default`，启动默认）：
  - 读写文件直接执行
  - `execute_command` 先交给 `LlmApprovalClassifier` 审查：用当前供应商发一个关闭思考的小请求，可用 `PAICLI_AUTO_CLASSIFIER_MODEL` 换模型。通过就执行，并写一条 `auto-classifier` 审计记录
  - 没通过、审查出错（超时、异常、返回的不是约定格式的 JSON、参数超过 32000 字符、没有可用的审查模型），以及 `revert_turn`、所有 `mcp__*` 工具、改动敏感页面，都直接以 `[AUTO]` 开头的失败结果还给模型，不弹确认框
  - 同一轮里连续被拦 3 次（`AUTO_ESCALATE_AFTER`）才转成人工确认；有一次通过或用户输入新一轮（`startAutoReviewTurn`）就重新计数
  - 审查只看用户本轮原话、工具名和参数，不看工具结果；审查出错时绝不放行；“通过”的结论只在同一个请求里复用；审查之后 PathGuard、CommandGuard 照常检查
- ask 模式（`/hitl on`）：额外要求确认 `write_file`、`edit_file`、`create_project`，不经过审查模型
- 默认的人工确认只由 `SwitchableHitlHandler` 打开；评测这类无人值守的处理器保持关闭，不能让没人看着的流程卡在等待确认上
- PathGuard 强制所有路径都在项目根目录内；CommandGuard 只是辅助的命令黑名单，不是主要的安全措施。命令沙箱由 `PAICLI_COMMAND_SANDBOX=off|auto|required` 控制，默认 off
- 微信通道没有人工确认界面，默认拒绝：只读工具允许；`execute_command`、`mcp__*` 必须在白名单里；`revert_turn` 和切换浏览器会话一律拒绝

### Web 与浏览器

- 搜索服务：设置了 `SEARCH_PROVIDER` 就用它；否则按 GLM Key → SerpAPI Key → SearXNG URL → DeepSeek Key 的顺序自动选，已有配置的优先级不变。DeepSeek 搜索单独发一个 Anthropic Messages 格式的请求，模型由 `DEEPSEEK_SEARCH_MODEL` 指定（默认 `deepseek-flash`）；链接只从返回的结构化搜索结果里取，缺少搜索结果或工具报错就直接失败，不从模型写的正文里找链接
- `web_fetch` 和浏览器能打开的网址，只能来自用户本轮亲手输入的原文，或者本次执行分支里 `web_search` 返回的结构化结果 `discoveredUrls`。工具返回的正文、模型的输出都不能让新网址变成可访问；被拒绝的调用不能换个工具绕过去
- Plan 的并行任务和 Team 的每个 worker 各用一份独立的策略副本；只有在 DAG 里声明了依赖，才能用上游任务 `web_search` 找到的网址
- 根据用户说的话做判断，只能用来少给工具，不能用来多给工具。工具默认全部开放，不要再用“动作词表”决定给不给模型工具（漏判会让正常任务因为缺工具悄悄失败）。只有输入明显像一个标题（`《…》`、`# `、“（附…面试题）”结尾、“X：Y？”），并且前面没有“帮我…”这类请求、也没有网址时，才关掉联网工具并提示用户；没有这些标记的纯标题，由提示词要求模型先问清楚。问“当前项目/文件”的问题优先用本地工具
- 最终回复里如果残留着没被执行的工具调用文本（比如 DeepSeek 的 DSML 格式），由 `LlmClient.looksLikeUnexecutedToolCall` 识别，并提示用户“工具调用未执行”；三种执行方式都要提示
- 已知网址先用 `web_fetch`；单页应用或有反爬时再用 Chrome DevTools MCP，读取页面优先 `take_snapshot`；公开页面不要提前切到 shared 模式（复用用户自己的浏览器）

### Plan 与 Team

- 计划审阅时的按键：`Enter` 执行、`Ctrl+O` 展开、`ESC` 取消、`I` 补充要求后在当前计划上修改（`Planner.revisePlan`）；方向键不能被误认成 ESC。改这块要在 raw mode 和退回的普通行输入模式下都验证一遍
- 所有 `/plan` 目标都交给模型规划，不要在调模型之前用关键词判断“这是简单任务”而跳过规划（关键词会把危险操作或多步任务误判成一步）；计划尽量短，靠规划提示词来约束
- Planner 生成的计划要严格校验：tasks 不能为空，id 必须是唯一、非空的字符串，依赖只能引用已声明的 id，不能有环。不合格就直接失败，不要悄悄删掉依赖或猜测 id。`parsePlan` 里提取 ``` 代码块的正则必须和 `e1_replay.py` 完全一致，改之前先改 `e1_replay.py`
- 用户取消导致中断的任务不算失败，不触发重新规划。任务失败且完成度低于 50% 时重新规划，最多 `PAICLI_PLAN_MAX_REPLANS` 次（默认 2）；到达上限后立刻不再启动新任务，剩下的标 SKIPPED（和 `e1_replay` 的“触发后不再有新工作”一致）。完成度不低于 50% 时，失败任务下游所有依赖它的任务标 SKIPPED。汇总时先列已完成的结果，再列失败和跳过的原因
- Team 的规划结果由 `TeamPlanParser` 按同一套规则校验；Reviewer 只认 JSON 里的布尔值 `approved: true`，不要再加“看到某些关键词就算通过”的退路
- planner 和 reviewer 的请求不提供工具，这是评测审计的要求；想引入 `submit_*` 工具或 `response_format` 之前，先改评测的审计约定

### 输出与 Skill

- 共用的 `prompts/handoff.md` 要求模型遵守用户指定的严格输出格式（只要 JSON、CSV 或一个值时，不加代码块围栏、不加总结），但这只是提示词要求，模型不一定每次都照做
- Skill 分三部分：
  - 索引（SKILL.md 开头 frontmatter 里的 name 和 description）一直放在 system prompt 里，最多 20 个、4KB
  - SKILL.md 正文（frontmatter 之后的内容）在 `load_skill(name)` 成功后，同一轮里紧跟在工具结果后面，作为一条单独的 user 消息加进对话（`LoadedSkillMessages`），不要改回等用户下一条消息再加
  - 附属文件用 `load_skill(name, file)` 读取，像普通工具结果一样返回
- Skill 正文消息第一行带 `[PAICLI_SKILL_INJECTION]` 标记：
  - 压缩时不算对话轮次、不交给摘要；落在被摘要范围里的直接删掉，摘要末尾列出这些 Skill 的名字，模型需要时重新 `load_skill`（不要改回“原样放回去”，也不要在提示词里禁止重复加载）
  - 某个 Skill 的正文还在对话里时，重复加载不再追加。去重和 16KB 上限（对话里所有 Skill 正文的总字符数）都直接从对话历史计算，不另外存状态
  - `references/` 目录会合并用户自己的补充目录 `~/.paicli/skills/<name>/references/`；经验写回只能用 `save_skill_reference`（只写补充目录里的 `references/**.md`）
  - 不放宽 PathGuard，不让 SKILL.md 指示模型用 `read_file` 或 `execute_command` 去读 `~/.paicli/`；项目自带的 Skill 没用 `/skill trust` 信任之前，不生成正文消息，SKILL.md 内容只作为不可信的工具结果返回
- system prompt 按内容变化频率从低到高排列：固定内容 → Skills → 运行时信息 → 项目上下文。新增段落按它的变化频率插到对应位置，不要放到随每次提问变化的记忆前面

## 修改时要一起改的地方

| 改动 | 需要一起改 |
|---|---|
| 行为 | `AGENTS.md`（规则变了时）、`README.md`、`ROADMAP.md`（只在完成状态变化时）、受影响的 `docs/articles/` |
| 命令入口 | `Main.java` + `CliCommandParser.java` + 测试 + `README.md` |
| Plan 审阅交互 | `Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证 |
| 工具集 | `ToolRegistry.java` + 三种执行方式和 Planner 的提示词 + 文档 |
| 模型 / 接口 | 对应的 Client + `LlmClientFactory.java` + `.env.example` + 文档 |
| Embedding | `EmbeddingClient` + `VectorStore` + `.env.example` + 文档 |
| Web / 搜索 | `web/` + ToolRegistry + `.env.example` + 文档 + 测试 |
| Memory | `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档 |
| HITL / 安全策略 | `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试 |
| MCP | `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试 |

不提交 `.env`、真实 API Key 和 `target/` 里的构建产物；代码保持好读，不过度抽象。

## 验证命令

| 场景 | 命令（都要带 `-DskipTests=false`） |
|---|---|
| 工具 / 代码搜索 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| Plan / DAG | `mvn test -Dtest=ExecutionPlanTest,PlannerGraphValidationTest,PlanExecuteAgentTest` |
| Memory | `mvn test -Dtest='com.paicli.memory.*Test'` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| TUI | `mvn test -Pphase16-smoke` |

## 从哪里开始读

先看本文件 → `README.md` → `Main.java`，再按任务进入对应模块：命令行命令看 `Main` + `CliCommandParser`；规划看 `PlanExecuteAgent` + `Planner` + `ExecutionPlan`；工具看 `ToolRegistry` + `Agent`；模型看 `llm/*Client` + `LlmClientFactory`；Multi-Agent 看 `AgentOrchestrator` + `SubAgent`；MCP 看 `McpServerManager` + `McpClient`；界面输出看 `render/Renderer` + `RendererFactory`；评测看 `eval/benchmark/BenchmarkCoordinatorMain` + `BenchmarkWorkerMain`。

## 还没做到的

- 还没有：通用的容器或虚拟机沙箱；MCP 的 OAuth 登录和 sampling；HTTP 类型 MCP server 的断线重连（自动重启只管 stdio 子进程）。评测用的 `DOCKER_RELAY` 只是让评测彼此隔离，不改变交互式 PaiCLI 的安全措施
- 命令沙箱默认 off：沙箱里没有网络，也看不到 `~/.m2`、`~/.gitconfig`，所以 `mvn`、`npm install`、`git push` 会失败；在 Seatbelt 沙箱里 `java -version` 仍会以 139 退出
- 不要把 `ROADMAP.md` 里“将来要做”的内容当成“现在已经有”

## OpenCode Go

新增供应商 `opencode-go`，使用 `OPENCODE_GO_API_KEY`、可选 `OPENCODE_GO_BASE_URL` 和 `OPENCODE_GO_MODEL`。默认 DeepSeek V4.1 Flash，`/model opencode-go` 切换。复用 DeepSeek 请求与工具协议，通过客户端实例稳定的 `x-opencode-session` 和 SimpleCLI User-Agent 接入 Go。原官方模型 ID 有歧义时优先保留 deepseek 归属，Go Pro 使用供应商前缀选择。

## 维护约定

用户将 Simple CLI 作为长期维护项目（2026-11-08）：后续完成有意义的功能或修复后，主动检查是否适合整理更新，按功能及依赖划分 Git 提交，并写中文更新说明（改了什么、验证结果、已知限制）。每次推送 GitHub 前，把具体提交清单和目标仓库/分支给用户确认，确认后才推送；不能把本次确认当成以后自动推送的许可。不承诺在会话之外自动监控。整理提交时保留原有改动，不伪造历史日期或把当前差异声称为逐轮原始记录。

形成稳定的协作规则时补进本文件，但保持精简；实现细节写进 `docs/agents-reference.md`，评测进展写进 benchmark 目录下的 RUNBOOK 或 ARCHIVE。
