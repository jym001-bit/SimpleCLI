# Simple CLI (paicli) 架构速览

> 本文是**代码实测**的架构索引，配合同目录的架构图使用；每个结论都标注了源码位置，便于直接跳读。
> 权威性顺序：代码实际行为 > `AGENTS.md` > `PAI.md` > `README.md` > `ROADMAP.md`。

- 项目定位：面向商业使用的 **Java Agent CLI**，对标 Claude Code
- 技术栈：Java 17 · Maven · JLine 4（inline 交互）· Lanterna（TUI）· OkHttp · Jackson · SQLite JDBC · JavaParser · JGit · jsoup · jieba
- 入口类：`src/main/java/com/paicli/cli/Main.java`（jar 产物 `paicli-1.0-SNAPSHOT.jar`，banner 版本 `v16.1.0`）

## 1. 启动装配顺序（实测 `Main.java`）

| 顺序 | 动作 | 位置 |
|---|---|---|
| 1 | `LlmClientFactory.createFromConfig(config)` 建默认模型客户端 | `Main.java:231` |
| 2 | `new HitlToolRegistry(hitlHandler)` 建工具拦截层 | `Main.java:243` |
| 3 | `new McpServerManager(hitlToolRegistry, Path.of("."))` 启动 MCP（最多等 8s，不阻塞首屏） | `Main.java:247` |
| 4 | `RendererFactory.create(RendererFactory.resolveMode(), terminal)` 选渲染器 | `Main.java:285` |
| 5 | `new Agent(llmClient, hitlToolRegistry)` 建 ReAct 主循环 | `Main.java:329` |
| 6 | `/plan` → `PlanExecuteAgent`；`/team` → `new AgentOrchestrator(...)` 复用同一 ToolRegistry / MemoryManager | `Main.java:1091`、`Main.java:1116` |
| 7 | `RuntimeApiServer` 独立启动路径 | `Main.java:885` |

微信通道是**另一条装配路径**：`WechatCommandMain.isWechatCommand(args)` 提前分流（`Main.java:214`），`WechatAgentSession` 自建 `new Agent(client, registry)`，其中 `registry = WechatToolRegistry(new WechatPolicyDecider(...))` —— 即微信链路**不走** CLI 的 `HitlToolRegistry`。

## 2. 三条执行路径

| 路径 | 入口类 | 触发 | 关键点 |
|---|---|---|---|
| ReAct | `agent/Agent.java` | 默认 | `executeTools()` 并行执行，最多 4 并发且结果保序 |
| Plan-and-Execute | `agent/PlanExecuteAgent.java` + `plan/Planner.java` | `/plan` | 产出 `ExecutionPlan` / `Task` DAG；审阅交互 Enter 执行 / Ctrl+O 展开 / ESC 取消 / I 补充重规划 |
| Multi-Agent | `agent/AgentOrchestrator.java` + `agent/SubAgent.java` | `/team` | 角色编排复用 ReAct 的工具与记忆 |

## 3. 工具与治理

- 内置工具（`tool/ToolRegistry.java` 实测 12 个）：`read_file` `write_file` `list_dir` `glob_files` `grep_code` `execute_command` `create_project` `search_code` `web_search` `web_fetch` `revert_turn` `load_skill`
- 代码检索：`tool/RipgrepCodeSearchEngine.java` 优先用本机 ripgrep，缺失时回退 `JavaCodeSearchEngine`
- **拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard**；用户无法批准被策略拒绝的请求
- 策略层：`policy/PathGuard.java`（强制路径限定项目根）、`policy/CommandGuard.java`（辅助黑名单，非主防线）、`policy/AuditLog.java`（默认 `~/.paicli/audit`）
- MCP 动态工具命名 `mcp__{server}__{tool}`，配置合并 `~/.paicli/mcp.json` 与 `.paicli/mcp.json`

## 4. 上下文与记忆

- `prompt/PromptAssembler.java`：system prompt 分层，按 `~/.paicli/PAI.md` → 项目 `PAI.md` → `.paicli/PAI.md` → `PAI.local.md` 顺序注入，受字符预算约束
- `memory/MemoryManager.java`：短期对话 + 长期记忆；**两道压缩不能混淆**（shortTermMemory 与 conversationHistory）
- 自动压缩阈值（实测 `context/ContextProfile.java`）：`summaryReserve = min(20k, max(1k, window/4))`、`buffer = min(13k, max(1k, window/8))`，触发点 `window - summaryReserve - buffer`，占比下限 50%（`MIN_COMPRESSION_TRIGGER_RATIO`，`ContextProfile.java:29-30`、`95-101`）。代入 200k 窗口 → 167k 触发，1M 窗口 → 967k 触发
- 其他派生预算：`agentBudget = max(4k, window×0.8)`、`shortTermBudget = max(4k, window×0.45)`、记忆注入上限 `max(500, min(5k, window/200))`
- 长期记忆只经 `/save` 或用户明确要求保存，可 `/memory list|search|delete|clear` 审计

## 5. 检索（RAG）与语言诊断

- `rag/CodeIndex.java` → `CodeChunker` / `CodeAnalyzer` → `VectorStore` → `CodeRetriever` → `SearchResultFormatter`
- `rag/VectorStore.java`：**SQLite** 存向量（JSON 数组）+ 代码关系图谱，检索时内存算余弦相似度
- `lsp/LspManager.java`：编辑后收集语言诊断并回灌

## 6. 模型接入

`llm/LlmClientFactory.java` 支持的 provider（实测 case 分支）：`glm` · `deepseek` · `step` · `kimi` · `freellmapi` · `xfyun` · `agnes`；并做别名归一（如 `stepfun`→`step`、`moonshot`→`kimi`、`iflytek`→`xfyun`、`sapiens`→`agnes`）。

两个易踩的坑（来自 `AGENTS.md`，与代码行为一致）：
- DeepSeek 默认强制 HTTP/1.1，且按**纯文本 provider** 处理：历史/工具回灌中的图片 `ContentPart` 会被替换为文本，不能发 `image_url`
- DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 的 `reasoning_content` 必须随下一轮请求历史带回

## 7. 外部集成与本地状态

- MCP：`mcp/McpServerManager.java` → `McpClient` → `StdioTransport` / `StreamableHttpTransport`
- Web：`web/SearchProviderFactory.java`（Searxng / SerpApi / Zhipu）+ `WebFetcher` + `HtmlExtractor`（jsoup）+ `NetworkPolicy`
- 浏览器：`browser/BrowserSession.java`（Chrome CDP）+ `BrowserGuard` / `SensitivePagePolicy`
- Skill：`skill/SkillRegistry.java` 扫内建缓存 + `~/.paicli/skills` + `<project>/.paicli/skills`；`load_skill` 注入下一轮 user message
- 微信 iLink：`wechat/`（`IlinkClient`、`WechatQrLogin`、`WechatMessageLoop`、`WechatPolicyDecider`），无人工审批面板 → 非交互默认拒绝
- 快照：`snapshot/SideGitManager.java` 用 JGit 建**侧历史仓库**（纯 Java，不依赖系统 git），支撑 `revert_turn`
- 本地状态：`~/.paicli`（记忆 / 审计 / 技能 / 历史 / 导出）、`.paicli`（PAI.md / mcp.json / skills）、SQLite 向量库、side-git 仓库、`.env` 密钥

## 8. 改动联动清单（改之前先看这张表）

| 改动 | 必须同步 |
|---|---|
| 行为 | `AGENTS.md` / `README.md`（状态变化才动 `ROADMAP.md`） |
| 命令入口 | `Main.java` + `CliCommandParser.java` + 测试 + 文档 |
| 工具集 | `ToolRegistry.java` + 三条路径提示词 + 可能 `Planner` 提示词 + 文档 |
| 模型/接口 | 对应 `*Client.java` + `LlmClientFactory.java` + `.env.example` + 文档 |
| HITL/策略 | `policy/` + `ToolRegistry` + `HitlToolRegistry` + 提示词 + `.env.example` + 文档 + 测试 |
| MCP | `mcp/` + `ToolRegistry` + HITL + `AuditLog` + 提示词 + 文档 + 测试 |

回归：`mvn test -Pquick`（常规）、`mvn test -Pphase16-smoke`（TUI）、`mvn test -Dtest=XxxTest -DskipTests=false`（定向）。

## 9. 当前已知边界（路线图有、但未交付）

容器/VM 沙箱 · MCP OAuth / sampling / server 自动重启。**不要把 `ROADMAP.md` 的"将来要做"当成"现在已有"。**

---

## 附：本目录图表的用途与取舍

| 文件 | 用途 |
|---|---|
| `paicli-architecture.ir.json` | Diagram IR 源模型（30 节点 / 38 边，每节点带 `provenance` 指向真实源码文件） |
| `paicli-architecture.drawio` | 可编辑 draw.io 资产，5 个视图页：System / Executive / Deployment / Dataflow / Security（均非 fallback 降级） |
| `paicli-architecture-story.html` | 离线讲解页（可键盘操作、含文本替代），适合快速通读 |
| `paicli-architecture.svg` / `.png` | 面向阅读的分层总览渲染（Style 1 Flat Icon，PNG 为 3120×2900 @2x） |
| `paicli-architecture.fireworks.json` | 上述 SVG 的生成输入，便于改文案/结构后重新出图 |
| `paicli-architecture.layout.json` | 渲染报告（几何、调色板对比度、文字完整性） |

制作过程中的真实取舍，避免误读：

1. `composition` 质量预算放宽了「跨容器连线数」（8 → 24）。原因：官方 showcase 档要求跨容器连线为 0、总折弯 ≤8，那是宣传图的规格；真实分层架构里「执行层 → 工具层 → 集成层」的跨层边天然超过该预算。**间距/标签净空/对比度等要求仍按严格档执行**（节点间距 ≥40px、容器内边距 ≥20px、对比度 ≥4.5）。
2. 有一条边（`a-tools-lsp`）带 2px 拐点，「最小线段」阈值因此设为 2px；其余边不受影响。
3. `PathGuard / CommandGuard` 的长英文标签在卡片里会截断，故标签改为中文短名，类名写进 `.drawio` 模型。
4. **视觉核验未执行**：生成侧只能做程序化校验（几何 / 碰撞 / 构图 / 标记 / XML 全部通过，文字无截断），渲染出的 PNG 未经人眼确认——请直接打开 `paicli-architecture.png` 过一遍。
