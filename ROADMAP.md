# PaiCLI 迭代路线图（21 期）

从零开始，逐步构建生产级 Java Agent CLI

---

## 第1期：基础ReAct + Tool Call ✅

**已完成**

- ReAct循环（思考-行动-观察）
- DeepSeek V4.1 Flash 接入（模型 ID `deepseek-flash`，默认 provider），也可切换 GLM、Kimi 等 OpenAI 兼容模型
- 基础工具：读写与精确编辑文件、列目录、执行 Shell 命令、创建项目
- 交互式CLI

> 模型说明：第 1 期默认接入 DeepSeek V4.1 Flash。模型迭代很快，后续默认模型可能继续升级，请以 `.env.example` 和 README 为准。

**核心知识点**：ReAct模式、Function Calling、Agent基础架构

---

## 第2期：Plan-and-Execute + 多轮规划 ✅

**目标**：让Agent能处理复杂多步任务

**功能迭代**：
- Plan-and-Execute模式（先规划后执行）
- 任务分解（Task Decomposition）
- 子任务依赖管理
- 执行计划可视化
- 计划失败时的重规划

**核心知识点**：
- Plan-and-Solve模式
- 任务DAG管理
- 规划-执行分离架构

**教程标题候选**：《Agent只会一步一步执行？教它先规划后行动，复杂任务也能搞定》

---

## 第3期：Memory系统 + 上下文工程 ✅

**目标**：让Agent有记忆，能处理长对话

**功能迭代**：
- 短期记忆（对话历史管理）
- 长期记忆（关键信息持久化）
- 上下文压缩（摘要生成）
- Token预算管理
- 记忆检索（相似度匹配）

**核心知识点**：
- Context Window管理
- 记忆分层架构
- 摘要算法（Map-Reduce）

**教程标题候选**：《Agent记性太差？给它装上记忆系统，长对话也不忘事》

---

## 第4期：RAG检索 + 代码库理解 ✅

**已完成**

**目标**：让Agent能理解整个代码库

**功能迭代**：
- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- 向量数据库（SQLite + 内存余弦检索）
- 代码分块与索引（文件/类/方法粒度）
- 语义检索（自然语言搜代码）
- 代码关系图谱（类、方法依赖）

**核心知识点**：
- RAG架构
- Code Embedding
- 向量检索
- AST 分析

**教程标题候选**：《Agent看不懂你的代码库？接入RAG，让它秒懂项目结构》

---

## 第5期：Multi-Agent协作 + 角色分工 ✅

**已完成**

**目标**：多个Agent协作完成复杂任务

**功能迭代**：
- Agent角色定义（规划者、执行者、检查者）
- Agent间通信机制
- 任务分配与协调
- 冲突解决策略
- 主从Agent架构

**核心知识点**：
- Multi-Agent系统
- 角色扮演（Role Playing）
- 分布式任务协调

**教程标题候选**：《一个Agent忙不过来？搞个团队，规划、执行、检查分工干》

---

## 第6期：Human-in-the-Loop + 审批流 ✅

**已完成**

**目标**：关键操作人工确认，安全可控

**功能迭代**：
- 危险操作静态规则识别（`write_file`、`execute_command`、`create_project`）
- 三级危险等级（高危 / 中危 / 安全）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- 交互式 CLI 默认 auto（Shell 命令由模型分类器审查），`/hitl on|default` 运行时切换
- `HitlToolRegistry` 是一层对调用方无感的拦截层，HITL 关闭时与普通 `ToolRegistry` 行为完全相同

**HITL 增强（后续补丁，归在本期讲）**：
- `PathGuard` 路径限制：`read_file` / `write_file` / `list_dir` / `create_project` 只能操作项目根目录之内，拦截绝对路径越界、`..` 跳出、符号链接逃出项目
- `CommandGuard` 命令快速拒绝：在 HITL 之前先按黑名单直接拒绝（sudo / rm -rf 全盘 / mkfs / dd 写裸设备 / fork bomb / curl|sh / find / / chmod 777 / / shutdown），少弹几次审批框
- `AuditLog` 操作审计日志：危险工具调用按天写成 JSONL，存到 `~/.paicli/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 看安全策略状态、`/audit [N]` 看最近审计

**为什么不叫沙箱**：
- 真正的沙箱是隔离的执行环境（Docker / microVM / chroot）。本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做沙箱：沙箱会削弱 Agent 能力，给人虚假的安全感，体验也更差
- PaiCLI 的安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是隔离
- 想做容器隔离的请参考 Pro 升级版本章节，或自行实现 `SandboxDriver` 接口

**核心知识点**：
- HITL（人机协同）
- 中断处理
- 安全策略
- 路径解析与符号链接安全（用 `Files.toRealPath` 防止逃出项目）
- 结构化审计（JSONL、按天分文件、并发安全）

**教程标题候选**：《Agent权限太大怕搞砸？加上人工审批，安全又放心》

---

## 第7期：异步执行 + 并行工具调用 ✅

**已完成**

**目标**：提升执行效率，支持长时间任务

**功能迭代**：
- 同一轮 LLM 返回多个 `tool_calls` 时并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 共用同一个批量工具执行入口
- Plan-and-Execute 按 DAG 依赖分批，并行执行互不依赖的任务
- Multi-Agent 按依赖分批，并行调度多个 Worker
- 一批工具共用一个超时时间，超时的工具会被取消，并返回一条能放回对话的结果

**核心知识点**：
- 异步编程模型
- 并发控制
- 任务调度

**教程标题候选**：《Agent执行太慢？上异步+并行，编译测试一起跑》

---

## 第8期：多模型适配 + 运行时切换（GLM / DeepSeek / StepFun / Kimi）✅

**已完成**

**目标**：支持多模型运行时切换，当前包含 GLM-5.1、DeepSeek V4、StepFun 和 Kimi K2.6

**功能迭代**：
- `LlmClient` 接口抽象：把 GLMClient 的内部类型（Message、ToolCall、Tool 等）提到接口层，作为公共类型
- `AbstractOpenAiCompatibleClient` 基类：共享 SSE 流式解析、请求构建、工具调用分片合并的逻辑
- `GLMClient` / `DeepSeekClient` / `StepClient` / `KimiClient` 都是很薄的子类：只提供 API URL、模型名、API Key 和各 provider 的差异
- 运行时模型切换：`/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型；`/model deepseek` / `/model step` / `/model kimi` 切 provider 并读取配置里的具体模型
- 配置持久化：`~/.paicli/config.json` 存储默认模型，API Key 也可以从 `.env` 读取
- `LlmClientFactory` 工厂：根据 provider 名称和配置创建对应客户端
- 手动模型管理：支持 list / refresh / add；可以套用同供应商模型的能力模板、用配置覆盖、显式切换。刷新不会自动启用新模型，失败时保留原配置。
- 2026-09-25：DeepSeek 默认模型升级为 `deepseek-flash`（V4.1 Flash），补齐新名称的思考参数、DSML 兼容和图片输入；旧 Flash 别名继续可用，V4 Pro 保留文本模式。历史评测约定不受影响。同日 DeepSeek 成为默认 provider，Key 回退顺序改为 deepseek 在前。

**核心知识点**：
- 策略模式 + Provider 抽象
- OpenAI 兼容协议
- 模板方法模式（AbstractOpenAiCompatibleClient）
- 运行时配置管理

**教程标题候选**：《只能用一个模型？策略模式 + 模板方法，GLM 和 DeepSeek 随时切换》

---

## 第9期：联网能力 + Web工具

**目标**：让 Agent 能访问互联网，获取实时信息（不涉及浏览器操控，那部分见第13/14期）

**功能迭代**：
- `web_search` 工具升级：第 7 期已用 SerpAPI 做了最简单的版本，这一期把搜索结果整理成结构化数据、固定字段。已接入智谱 / SerpAPI / SearXNG / DeepSeek 原生搜索；DeepSeek 直接用已有的 API Key，单独发一次 Messages 请求拿到结构化的来源
- `web_fetch` 工具：抓取指定 URL 页面内容，自动提取正文（去除 HTML 标签 / 广告 / 导航）
- 搜索结果摘要：LLM 对检索结果再提炼一遍，只保留与用户问题相关的信息
- 网络访问安全：URL 白名单 / 黑名单、请求频率限制、响应体大小限制
- Agent 提示词升级：让 Agent 知道什么时候该用联网工具（如"最新版本是什么"、"官方文档怎么说"），什么时候该用本地工具

**核心知识点**：
- 搜索引擎 API 集成
- HTML 正文提取（Jsoup / Readability 算法）
- 网络访问安全策略
- Agent 工具选择 prompt 设计

**教程标题候选**：《Agent 与世隔绝？让它学会搜索和抓取，实时信息一手到位》

---

## 第10期：MCP 协议核心（stdio + Streamable HTTP，默认开启） ✅

**已完成**

**目标**：把 PaiCLI 接入 MCP 生态。stdio 子进程 server 和 Streamable HTTP 远程 server 都能用，工具自动注册到 ToolRegistry，并接入 HITL / AuditLog。

**功能迭代**：
- 手写 `JsonRpcClient`：JSON-RPC 2.0 客户端，处理请求和响应的配对、通知、错误码、超时
- `McpTransport` 抽象 + 两个实现：
  - `StdioTransport`：ProcessBuilder + 按行分隔的 JSON-RPC，stderr 单独读走，JVM 退出时通过钩子清理子进程
  - `StreamableHttpTransport`：OkHttp + 单 POST + 服务端 SSE 流式响应，支持 session ID
- `initialize` 握手 + 能力（capabilities）协商 + 协议版本协商
- `tools/list` + `tools/call`：工具按 `mcp__{server}__{tool}` 前缀注册到 `ToolRegistry`
- MCP 返回的 `content` 数组拍平处理（text 拼接，image / resource 给一句替代提示）
- 配置文件：`~/.paicli/mcp.json`（用户级）+ `.paicli/mcp.json`（项目级，可入 git），格式与 Claude Code `claude_desktop_config.json` 兼容
- 启动时立即并行拉起所有 server，不等到用时再启动（复用第 7 期并行调度）
- **默认开启**，`/mcp disable <name>` 关单个
- 接入 HITL + AuditLog：MCP 工具默认走 HITL，审计日志的 `tool` 字段带 `mcp__` 前缀
- CLI：`/mcp` / `/mcp restart <name>` / `/mcp logs <name>` / `/mcp disable <name>` / `/mcp enable <name>`
- MCP 子系统默认启动；未配置 `mcp.json` 时不启动外部 server，避免首次运行卡在 `npx` / `uvx` 冷启动上

**核心知识点**：
- JSON-RPC 2.0 协议实现
- 长期运行的子进程生命周期管理（NIO + 流分离）
- Streamable HTTP（2025 年 3 月新规范，替代已废弃的 SSE）
- 第三方工具怎么纳入安全模型统一管理（HITL + Audit + 命名空间隔离）

**估算**：5–6 天

---

## 第11期：MCP 高级能力（resources 双轨 + prompts 查看 + 被动通知） ✅

**前置依赖**：第 10 期 MCP 协议核心

**目标**：优先补齐 MCP resources 体验，资源引用方式和 Claude Code 保持一致，并提供 prompts 查看、被动通知处理与运行中取消。OAuth 与 sampling 已确认延后，不计入本期交付。

**功能迭代**（详细开发任务见 `docs/phase-11-mcp-advanced.md`）：

- **resources 双轨**（参考 Claude Code）：
  - 工具层：每个支持 resources 的 server 注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具，由 LLM 自己决定什么时候调用
  - 用户 @ 引用层：`@server:protocol://path` 语法 + jline 自动补全，处理输入时先拉取内容，替换成 `<resource>` 内联块
  - 收到 `resources/list_changed` / `resources/updated` 后只把缓存作废，下次 read/list 时重新拉取
- **prompts 查看**：`/mcp prompts <server>` 展示 server 提供的 prompt 模板；不加载到对话里
- **双向通知（被动）**：
  - `tools/list_changed` → 重拉工具列表 → `replaceMcpToolsForServer` 全量替换
  - `resources/list_changed` / `resources/updated` → 缓存作废
  - **不做 health ping**，不主动检查 server 是否存活，避免给按量或按月计费的 server 增加额外负担
- **新增 CLI**：`/mcp resources <server>`、`/mcp prompts <server>`
- **运行中取消**：任务执行期间输入 `/cancel` 并回车，请求取消当前 Agent 运行；ReAct、Plan、Team、工具批次与 `execute_command` 都会在各自的检查点上查看取消信号

**不做（明确不在本期范围）**：
- OAuth 2.0 Authorization Code + PKCE
- `sampling/createMessage`
- MCP server 自动重启
- prompts 加载到对话里（只保留 `/mcp prompts` 查看 server 提供的模板）
- resources 自动注入 system prompt（第 12 期长上下文模式已接入 URI / 描述索引）
- server health ping / heartbeat
- progress / logging notification 的 UI 展示

（后续已补：stdio server 的崩溃自动重启、本地 ping 探活、进程树清理，HTTP server 仍不 ping）
- OAuth Device Flow / Client Credentials

**核心知识点**：
- MCP resources/list + resources/read 包装成工具
- 用户显式 `@server:protocol://path` resource 引用与上下文注入
- jline `Completer` 与 raw mode 的配合（@ 引用的自动补全不能干扰 plan/team 的 raw mode 输入）
- 被动响应通知和主动 ping 的取舍（按月计费的 server 一定不能主动 ping）

**验证**：`mvn test` 336 tests 通过

---

## 第12期：长上下文工程（适配 200k–1M 模型 + prompt caching） ✅

**目标**：适配 GLM-5.1（200k）/ DeepSeek V4（1M）/ StepFun（256k）/ Kimi K2.6（256k）/ Claude Sonnet 4.6（1M）等长上下文模型。第 3 期 Memory 是按"上下文短、要靠压缩保底"的假设设计的，窗口变长后要换策略。

**功能迭代**（详细开发任务见 `docs/phase-12-long-context.md`）：
- `LlmClient` 接口新增能力声明：`maxContextWindow()` / `supportsPromptCaching()` / `promptCacheMode()`
- `ContextProfile` 统一管理 short / balanced / long 三种上下文模式
- `AgentBudget` token 预算从写死 300K 改为按当前模型动态计算（默认 80% × maxContextWindow，仍支持系统属性覆盖）
- 长 / 短上下文双模式：
  - 短 / balanced：保留第 3 期 Memory 摘要压缩策略
  - long（≥ 100k window）：跳过摘要压缩，提高 RAG 默认 topK（20），扩大短期记忆预算
- prompt caching 接入：
  - 能力声明，并在 `/context` 里显示
  - 从 OpenAI 兼容接口返回的 usage 里解析缓存命中的输入 token
  - DeepSeek V4 用自动前缀缓存；还没确认兼容的 provider 私有字段，目前不加进请求
- 上下文成本可见：每轮输出 `已用 X / Y token (window W, cached: Z, 估算 ¥A)`
- 检索策略随模式调整：`search_code` 未传 `top_k` 时按上下文模式选择 5 / 10 / 20
- **MCP resources 自动注入**（与第 11 期联动）：长模式下，把所有 server 已知 resources 的 URI + 描述（不含正文）作为索引注入 system prompt；ReAct / Plan / Team 都接入
- `/context` 命令扩展：显示当前 window、动态预算、模式、prompt cache、RAG topK、resources 是否已自动注入

**核心知识点**：
- 长上下文模型的成本（普通输入和缓存命中输入的价差通常 5–10 倍）
- prompt caching 的缓存分界点设计
- RAG 在长上下文时代的角色变化（从"压缩选择"到"加速 + 精排"）
- 用资源索引（MCP resources URI + 描述）充实长上下文

**验证**：`mvn test` 347 tests 通过；`mvn clean package` 通过

---

## 第13期：Chrome DevTools MCP ✅

**前置依赖**：第 10 / 11 期 MCP 框架（已完成）

**目标**：让 Agent 能操控浏览器，处理需要 JS 渲染、有防爬、要填表单、要登录的页面（如微信公众号文章、知乎专栏、SPA 应用等）。

**功能迭代**（详细开发任务见 `docs/phase-13-chrome-devtools-mcp.md`）：

- 接入 Google 官方 `chrome-devtools-mcp@latest`（28 个工具：导航 / 输入 / 调试 / 网络 / 性能 / 模拟 / 扩展 / 内存）
- **默认 enabled**：`~/.paicli/mcp.json` 不存在时，启动时自动创建模板，含 chrome-devtools 条目
- `image` 内容按**路线 B** 处理：用一段替代文案引导 LLM 优先用 `take_snapshot`（DOM 文本快照）而不是 `take_screenshot`；暂不做真正的图片复制粘贴输入（拆到第 21 期）
- HITL「全部放行」改为**按 server 生效**：用户对 chrome-devtools 选 `a → server` 后，连续的浏览器操作只需确认一次（`approvedAllByServer` 集合 + 子菜单）
- `Agent` / `PlanExecuteAgent` / `SubAgent` 系统提示词加「web_fetch vs 浏览器 MCP」选择表，写明微信公众号 / 知乎 / 推特等 web_fetch 经常失败的站点直接走浏览器
- `McpClient.initialize` 超时 30s → 60s（chrome-devtools 首次启动要 npx 拉包 + Chrome 冷启动，约 20s 以上），可用 `paicli.mcp.initialize.timeout.seconds` 覆盖
- `McpServerManager.startAll` 启动期间另起一个状态打印线程，每 5s 打印还没就绪的 server 已等了多久
- 必跑端到端测试：微信公众号文章（`https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg`），验证 web_fetch 失败 → LLM 自动改用浏览器 → take_snapshot 拿到正文

**不做（明确不在本期范围）**：
- 真正的图片复制粘贴输入（拆到第 21 期「图片复制粘贴输入」）
- CDP 会话复用 / 登录态识别（第 14 期）
- Playwright / Firefox / WebKit 跨浏览器
- 浏览器执行隔离（默认 `--isolated=true` 临时 user-data-dir，第 14 期通过 `--autoConnect` 或旧式 `--browser-url` 复用已开 Chrome）

**核心知识点**：
- 第三方 MCP server 接入实战（直接用 Google 官方 server，不再造轮子）
- HITL 全部放行的两种粒度（按工具 vs 按 server）
- LLM 自己决定退路（web_fetch 拿不到 → 提示词引导走浏览器）
- 启动慢的 server 怎么做好体验（进度提示 + 超时调整）

**教程标题候选**：《静态抓取不够看？接 Chrome DevTools MCP，让 Agent 自己开浏览器》

**验证**：单元测试覆盖默认 MCP 配置创建、HITL 按 server 全部放行、MCP image 替代提示与初始化超时；真实浏览器端到端测试需要本机有 Chrome 和 API Key 才能跑。

---

## 第14期：CDP 会话复用 + 登录态访问 ✅

**前置依赖**：第13期 Chrome DevTools MCP 已能驱动浏览器

**目标**：让 Agent 复用带登录态的调试 Chrome 实例，访问需要认证的页面

**功能迭代**：
- 通过 Agent 内部 `browser_connect` 或 `/browser connect` 按需切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的 Chrome；`/browser connect <port>` 保留旧式 `--browser-url=http://127.0.0.1:<port>` 写法以兼容
- 复用调试 Chrome 的登录态访问 GitHub、内部系统等需认证页面；默认 `mcp.json` 仍保持 `--isolated=true`
- `/browser status` / `/browser tabs` / `/browser disconnect` 用来看会话状态、看 tab、回到 isolated 模式
- 登录态访问的安全限制已实现：识别敏感页面、会修改页面的工具每一步都走 HITL、shared 模式下强制保护 `close_page`
- 审计日志为 chrome-devtools 工具追加浏览器元数据，同时兼容旧 JSONL

**核心知识点**：
- Chrome 远程调试端口工作机制
- 登录态复用与隔离
- 认证页面的安全策略

**教程标题候选**：《要登录才能看？让 Agent 连上你的调试 Chrome，省掉重复打开页面的麻烦》

---

## 第15期：Skill 系统 + web-access Skill ✅

**已完成**

**前置依赖**：第 9 期 web 工具、第 13 期 Chrome DevTools MCP、第 14 期 CDP 会话复用全部就绪

**目标**：做出 PaiCLI 自己的 Skill 加载机制，把零散的工具与使用指引打包成可复用单元，并以 web-access 作为第一个 Skill

**功能迭代**（详细开发任务见 `docs/phase-15-skill-system.md`）：
- Skill 加载机制：扫描三层目录（jar 内置 / 用户级 `~/.paicli/skills/` / 项目级 `<project>/.paicli/skills/`），同名 Skill 整个覆盖，frontmatter 用手写的 YAML 子集解析（不引 SnakeYAML）
- 启动时把已启用 skill 的 `name` + `description` 注入 system prompt 的索引段（单个 description ≤ 500 codepoint，最多启用 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 主动调用，SKILL.md 正文在同一轮工具结果之后，以一条独立的 user 消息注入（用到时才展开，节省 Token）
- 2026-09-25：修复正文要等用户下一条消息才注入的问题，移除共享的 `SkillContextBuffer`，改为 `LoadedSkillMessages` 跟着本批工具结果在同一轮注入，并行任务之间不再串 skill
- 内置 web-access Skill：使用手册（浏览四步法 + 工具选择表 + 浏览器优先级 + Jina 备用方案说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- 启动时 `SkillBuiltinExtractor` 把 jar 内置 skill 解压到 `~/.paicli/skills-cache/`，按 `.version` 文件决定是否重建
- CLI 命令：`/skill` / `/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- Jina Reader 集成：**只**在 web-access SKILL.md 里写一句「web_fetch 失败时可以让 execute_command 调 r.jina.ai」，**不**改 `web_fetch` 工具内部流程（保持第 9 期纯本地的约定）
- Skill 与 HITL 配合：Skill 里调用 `execute_command` / 浏览器 MCP 等危险工具仍走 HITL 审批，沿用 `execute_command` 按工具全部放行；不给 Skill 单独加审批粒度

**核心知识点**：
- 把提示词工程做成可管理的代码
- 按触发词分派与按需加载
- 经验积累目录（按域名/场景存下可复用的知识）
- 设计意图：从「写工具」演进到「打包专家手册」

**教程标题候选**：《工具堆成山，Agent 还是不会用？给它写本「专家手册」，按场景自动展开》

**验证**：`mvn test` 457 tests 通过；`mvn clean package` 通过

---

## 第16期：TUI界面 + 产品化 ✅

**目标**：从CLI到完整产品体验

**功能迭代**：
- 终端TUI界面（Lanterna/JLine）
- 文件树浏览
- 代码高亮显示
- 对话历史可视化（`~/.paicli/history/session_*.jsonl`）
- 配置文件管理（TUI `/config` 面板）
- TUI 输入接到真实的 ReAct / Plan / Team 执行流程
- TUI HITL 弹窗审批（批准 / 拒绝 / 跳过）
- 安装包分发

**第 16.1 期形态修正（v16.1.0）**：
- 抽出 `Renderer` 接口 + 三个实现：inline 流式（默认）/ lanterna 全屏（保留）/ plain 最简备用
- 默认形态切换为 **inline 流式 TUI**（Claude Code 风格），主屏直接输出 + 底部 DECSTBM 状态栏 + 行内可折叠工具块（`ctrl+o`）+ 行内 diff
- HITL 改为单字符 `[y/n/a/s/m]` 提示；`/config` 改为浮层面板
- 切换：`PAICLI_RENDERER=inline|lanterna|plain`，旧 `PAICLI_TUI=true` 兼容映射到 lanterna

**活动折叠与编辑提示打磨（参考 Claude Code，2026-09-24 从需求笔记并入）**：

![](https://cdn.tobebetterjavaer.com/paicoding/CLAUDE-2b7755723b1c47f8862bae4999504467.png)

![](https://cdn.tobebetterjavaer.com/paicoding/CLAUDE-b175c64d08c1408e95c4f10dcc91a77f.png)

- ✅ 按工具类型折叠活动块（“📖 读取 N 个文件”“✏️ 编辑 N 个文件”等），`ctrl+o` 展开
- ✅ `write_file` / `edit_file` 成功后展示类似 git 的行内 diff
- ⬜ 同一轮多种工具合并为一行活动摘要，例如“读取 1 个文件，搜索了记忆”，对齐 Claude Code 的 “Read 1 file, searched memories”
- ⬜ `glob_files` / `grep_code` / `load_skill` / `revert_turn` / 浏览器工具补专用标签，目前显示的是默认的“🔧 工具名 × N”

**核心知识点**：
- TUI开发
- 终端渲染（DECSTBM、ANSI 局部重绘、JLine widget 绑定）
- 产品工程化（接口抽象 + 多形态切换）

**教程标题候选**：《CLI太简陋？做个漂亮的TUI界面，体验不输Claude Code》

---

## 第17期：LSP 诊断注入（开发时的安全网）

**前置依赖**：第 6 期 HITL 审批流、第 16 期 TUI 产品化

**目标**：Agent 改完代码后，立即把编译诊断告诉它，而不是等用户手动跑 `mvn compile` 再转告 Agent。对标 Claude Code / DeepSeek TUI 的招牌功能。

**功能迭代**：

- `LspManager`：按语言在用到时才启动 LSP server（JDT LS for Java、rust-analyzer for Rust、pyright for Python、gopls for Go），通过 stdio JSON-RPC 通信，每种语言一个连接池，复用连接
- 只实现 LSP 协议里最少的几个消息：`initialize` → `textDocument/didOpen` → `textDocument/didChange` → 收集 `textDocument/publishDiagnostics`
- `LspHooks`：挂在 `ToolRegistry.executeTool()` 执行之后——`edit_file` / `apply_patch` / `write_file` 成功后，对改过的文件调 `runPostEditLspHook()`
- `flushPendingLspDiagnostics()`：每轮 LLM 请求之前，把收集到的诊断拼成一条 user 消息注入——模型在下一轮推理前就能看到编译错误
- 诊断格式化：按 severity（error / warning / info）+ 文件路径 + 行号 + message 渲染成内联诊断块，限制每次注入的条数（默认最多 20 条诊断）
- TUI 展示：inline 模式下诊断块用红色/黄色 ANSI 显示，用户能直接看到 Agent 引入的编译问题
- 出错时不影响主流程：LSP server 启动失败或超时只打 trace 日志，不阻塞 Agent；没有对应 LSP server 的语言直接跳过

**设计参考**：DeepSeek TUI `crates/tui/src/lsp/`——`LspManager`（用到时才建的连接池）+ `lsp_hooks.rs`（编辑后的钩子）+ `diagnostics.rs`（诊断类型与渲染）。PaiCLI 在 Java 生态里可以用 Eclipse JDT LS（`org.eclipse.jdt.ls`），或直接复用已有的 `CodeAnalyzer` 做轻量版。

**核心知识点**：
- LSP（Language Server Protocol）用到的 JSON-RPC 消息
- 外部进程生命周期管理（ProcessBuilder + stdio 流分离）
- 编辑后钩子（Agent 执行流程的扩展点）
- 插入一条程序生成的消息（在 tool_result 之后、下一轮 LLM 请求之前）

**教程标题候选**：《Agent 改完代码就报错？给它接上 LSP，编译错误当场发现》

---

---

## 第18期：Git Side-History 快照与回滚（文件安全网）

**前置依赖**：第 7 期异步执行、第 16 期 TUI 产品化

**目标**：Agent 每轮对话前后自动给工作区做快照，用户可以一键回滚到任意一轮之前的状态，不弄脏用户自己的 `.git` 历史。对标 DeepSeek TUI 的 `snapshot/` 系统。

**功能迭代**：
- `SideGitManager`：在 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` 维护一个独立的 side-git 仓库，用 JGit 纯 Java 实现，和用户工作区的 `.git` 完全分开
- `preTurnSnapshot()`：每轮开始前，对工作区执行 JGit add/commit，并标记 `"pre-turn <turn_id>"`；MVP 里这次快照同步执行，保证 Agent 改文件前已经存好基准版本
- `postTurnSnapshot()`：每轮结束后异步再做一次快照，commit message 标记 `"post-turn <turn_id>"`
- `/restore <N>` 命令：从最近 N 轮的 pre-turn 快照中把文件恢复到工作区，不改变用户 `.git` 和对话历史
- `revert_turn` 工具：LLM 可调用的回滚工具，让 Agent 自己判断"改坏了需要撤销"
- 快照策略可配：`max_snapshots`（默认保留最近 50 轮）、`snapshot_excludes`（默认排除 `.git/`、`node_modules/`、`target/`）

**设计参考**：DeepSeek TUI `crates/tui/src/core/engine.rs` 的 `pre_turn_snapshot()` / `post_turn_snapshot()` + `crates/tui/src/core/turn.rs` 的 `pre_tool_snapshot()`。

**核心知识点**：
- Git 内部对象模型（tree / commit / blob）与 JGit API
- 用独立的 side-git 仓库做隔离（独立 `.git` 目录 + 等价于 `--work-tree` 的操作）
- 按轮次自动快照的策略
- 异步快照不阻塞主流程（发出后不等结果）

**教程标题候选**：《Agent 改坏文件怎么办？自动 Git 快照 + 一键回滚，放心让它改》

---

## 第19期：Prompt 分层架构（可维护性重构）

**前置依赖**：第 1–16 期全部功能（这些期累积下来的所有 system prompt）

**当前状态**：MVP 已实现。ReAct、Plan task executor、Multi-Agent 三角色、Planner 已接入 `PromptAssembler`，内置资源位于 `src/main/resources/prompts/`，覆盖路径支持 `~/.paicli/prompts/...` 与 `.paicli/prompts/...`。

**目标**：把分散在 `Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java` 三处的硬编码 system prompt，重构为打包进 jar 的分层 Markdown，支持用户级覆盖，让调 prompt 从"改 Java 源码 + 重新编译"变成"改 Markdown 文件"。

**功能迭代**：
- 分层 prompt 文件（`src/main/resources/prompts/`）：
  - `base.md`：核心规则（工具使用、输出格式、子 Agent 协议、上下文管理）
  - `modes/agent.md` / `modes/plan.md` / `modes/planner.md` / `modes/team-planner.md` / `modes/team-worker.md` / `modes/team-reviewer.md`：各模式的工作流程和权限
  - `approvals/suggest.md` / `approvals/auto.md` / `approvals/never.md`：审批策略
  - `personalities/calm.md`：语调（保留现有 `AGENTS.md` 中的 Personality 规范）
- `PromptAssembler`：按稳定程度从高到低拼装（base → personality → mode → approval → context_mgmt → handoff → skills → runtime_context → project_context），遵循"容易变的内容放最后"的原则，尽量提高 KV 前缀缓存的命中率
- 用户级覆盖：`~/.paicli/prompts/base.md` 可整体替换内置 base.md；`~/.paicli/prompts/modes/agent.md` 可覆盖特定模式；项目级 `.paicli/prompts/...` 优先级更高
- 启动时校验：必须包含 `## Language` 段（保证 reasoning_content 的语言跟随用户）
- 旧 API 保持兼容：`Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java` / `Planner.java` 不再手写各运行模式的 prompt，改为调用 `PromptAssembler.assemble(mode, context)`
- 自带 prompt 质量审计模板（参考 DeepSeek TUI `PROMPT_ANALYSIS.md`）：每次改 prompt 都应该写一份差距分析

**设计参考**：DeepSeek TUI `crates/tui/src/prompts.rs` + `crates/tui/src/prompts/*.md` 的分层架构，以及 `PROMPT_ANALYSIS.md` 的自我审查方法。

**核心知识点**：
- 用工程方法管理 Prompt
- 资源打包进 jar（Java `Class.getResourceAsStream` + 缓存）
- 对 KV 前缀缓存友好的 prompt 排布（稳定内容在前，易变内容在后）
- 用户可覆盖的配置层次（jar 内置 → 用户级 → 项目级）

**教程标题候选**：《System prompt 写得像意大利面？用分层架构，一个 Markdown 文件管一种职责》

---

## 第20期：异步后台任务 + Runtime API（异步 & 无头场景） ✅ MVP

**前置依赖**：第 13 期 Chrome DevTools MCP 已能产出截图等 image content；第 12 期长上下文工程已就绪。

**目标**：用户可以在 TUI 里提交后台任务（如"重构整个模块"），关掉终端走人，回来查看结果。同时提供 HTTP/SSE Runtime API，让 PaiCLI 可以嵌入 CI/CD、IDE 插件、Web 面板。

**功能迭代**：

**后台任务（Task Manager）**：
- `DurableTaskManager`：用 SQLite 持久化的任务队列，复用已有的 `VectorStore` SQLite 基础设施
- 任务生命周期：`enqueued` → `running` → `completed` / `failed` / `canceled`
- Worker Pool：并发数可配（默认 2），每个 Worker 启动独立的 Agent 线程执行任务
- `/task add <prompt>`：提交后台任务，返回 task_id
- `/task list`：列出所有任务（含状态、耗时、进度）
- `/task cancel <id>`：取消运行中任务
- `/task log <id>`：查看任务执行摘要
- 重启恢复：进程重启后，没做完的任务自动重新入队

**Runtime API**：
- `RuntimeApiServer`：嵌入式 HTTP/SSE 服务端（`paicli serve --http --port 8080`），基于已有的 OkHttp / Javalin 或 Spring Boot 内嵌
- 兼容 OpenAI Assistants API 的接口：
  - `POST /v1/threads`：创建对话线程
  - `POST /v1/threads/{id}/turns`：发起一轮 Agent 交互
  - `GET /v1/threads/{id}/events`：SSE 流式事件（MessageDelta / ToolCall / TurnComplete）
- `RuntimeThreadStore`：thread/turn 的持久化记录 + 可重放的事件时间线
- 安全：只监听 localhost，校验请求头里的 API Key

**设计参考**：DeepSeek TUI `crates/tui/src/task_manager.rs`（SQLite 任务队列）+ `crates/tui/src/runtime_api.rs`（HTTP/SSE）+ `crates/tui/src/runtime_threads.rs`（thread 持久化）。

**核心知识点**：
- 持久化任务队列（SQLite 表结构 + 状态机）
- Worker Pool 并发模型
- 嵌入 HTTP/SSE 服务端（Javalin / Spring Boot 内嵌 + SSE 推送）
- 兼容 OpenAI Assistants API 的适配层设计

**当前 MVP 已实现**：
- `DurableTaskManager`：SQLite 后台任务队列，默认 `~/.paicli/tasks/tasks.db`
- `/task`、`/task add`、`/task cancel`、`/task log` CLI 命令完整可用
- 进程启动时把遗留的 `running` 任务恢复为 `enqueued`
- Worker Pool 默认 2，可用 `PAICLI_TASK_WORKERS` / `-Dpaicli.task.workers` 覆盖
- `RuntimeApiServer`：基于 JDK `HttpServer`，只监听 `127.0.0.1`
- `RuntimeThreadStore`：SQLite 保存 thread 与 event 时间线
- Runtime API 必须配置 `PAICLI_RUNTIME_API_KEY` / `-Dpaicli.runtime.api.key`
- 详细实现文档：`docs/phase-20-runtime-api.md`

**教程标题候选**：《不想守在终端前？后台任务 + HTTP API，Agent 可以在后台跑》

---

## 第21期：图片复制粘贴输入 ✅ MVP

**前置依赖**：第 13 期 Chrome DevTools MCP 已能产出截图等 image content；第 12 期长上下文工程已就绪；第 17–20 期安全网与架构已就绪。

**目标**：让 PaiCLI 真正"看见"图片——浏览器截图、用户粘贴的图片、文档中的图表，都能直接交给 LLM 理解，而不是退化成"[此工具返回了 image]"这样的占位文字。这一期排在安全网（LSP + 快照）和架构重构（Prompt + Task）之后，等模型生态成熟了再做。

**功能迭代**：

- `LlmClient.Message.content` 协议升级：从 `String` 扩展为 `List<ContentPart>`（含 `text` / `image_base64` / `image_url`）
- 各 `LlmClient` 实现适配图片输入的请求体；公共接口不声明图片能力，含图片时统一上传，由 provider 的 API 决定接收还是返回错误
- 第 13 期 `take_screenshot` 的 image 替代提示，升级为把图片作为附件放回对话；输入层不按模型名拦截图片
- 用户输入层：终端粘贴 base64 图片，或用 `@image:file://path/to/img.png` 显式引用
- HITL 弹窗展示图片元数据（mimeType / size），不展示原图
- 按 token 成本审计：图片输入单独统计成本（多数图片输入 API 按图块（tile）数计 token）

**当前 MVP 已实现**：
- `LlmClient.Message` 新增 `ContentPart`，旧的字符串构造器保持兼容
- `AbstractOpenAiCompatibleClient` 在含图片时输出带图片块的 content 数组，纯文本仍输出字符串 content
- 公共 `LlmClient` 接口不做图片能力声明
- MCP image content 的 `data` / `mimeType` 保留为 `ToolOutput.imageParts`
- ReAct / Plan task executor / SubAgent 在工具结果后追加一条带图片的 user 消息，不在 CLI 输入层按模型名拦截
- 用户输入支持 `@image:file:///abs/path.png`、`@image:/abs/path.png`、`@image:relative/path.png`
- 图片处理和 Claude Code 保持一致：不 OCR 成文本；统一压缩 / 缩放后以图片块发送，只补充来源、尺寸、坐标换算这些元信息
- 详细实现文档：`docs/phase-21-image-input.md`

**不做**：
- 视频 / 音频输入（以后单独做一期）
- 图像生成（PaiCLI 是 Agent 不是绘图工具）
- TUI 用 sixel 协议显示截图（取决于第 16 期 TUI 是否实现，留作扩展）

**核心知识点**：
- OpenAI 兼容协议的图片输入扩展（content 数组 vs 字符串）
- 各模型图片输入的定价差异
- base64 图片在 JSON-RPC / HTTP / 流式响应里的传输与缓存
- Agent 什么时候该截图、什么时候该读 DOM、什么时候该问用户（谁来决定 vs 成本）

**教程标题候选**：《Agent 不能只看文字？接通视觉能力，截图 / 图表 / 设计稿都能"看"》

**估算**：5–6 天

---

## 横向工程：Native AgentBench 开发试点（dev-pilot）✅

E2 的原生观察接口已经接入：包括 13 类事件（角色、输入、工具、审批、预算、压缩、终态等）和一个独立的编解码器，默认都关闭。它不改变产品原有行为：审阅出错时任务仍可能被标成 COMPLETED。E2 还缺宿主侧的归属记录、整题预算、文件和冲突证据以及独立验题，所以不算进已生成题目的配方（recipe），24/28 不变；详见正式运行手册第 47 节。

2026-09-05 调整范围：之后只评 DeepSeek V4 Flash 和 GLM-5.3-Flash。新的批次 v4 / 计划 v5 共 168 次运行（28 × 2 × 3）；旧的三模型 252 次约定和记录都保留。下面提到的三模型 / Hy4 前置条件是历史内容，不适用于新的 v4；两个模型的完整题库、校准、冻结、三次重复仍然要做完。F3 这一轮只做了预检，真实 API 测试还要等用户授权发送外部合成数据；没有新的模型成绩。

正式题集进展：全量准入检查与请求准备、和批次绑定的尝试编号、按冻结清单复制被测程序可写的测试数据，以及生产用的正式执行循环，都已实现并有本地测试；真实题目生成器（generator）的接入和其余有状态的题目还没完成。

2026-09-04：DeepSeek / GLM 在 Docker 中转（relay）模式下跑完了全部 8 道开发题，模型和用量（usage）证据都完整拿到；修好验题程序（verifier）临时目录的权限后，用同样条件重新核验全部原始产物，结果都是 8/8。这只是在公开开发集上跑一次的诊断，不改变下面说的正式题集未完成状态，详见同日开发报告。

**F3 第一次接入正式流程，本轮容器对照验证已完成**：严格题源、私有生成配方、v4 约定、冻结绑定、单次 Session、证据包装格式 v9 和独立的 Python 计分都已接入。当前中转协议 v11 的 `MOCK_MCP_FILE_ONLY` 提供 6 个文件工具 + 1 个 MCP 工具，敏感测试数据能正常读到；模拟服务、状态、provider、原始工具结果、流式输出这五类证据，足以支撑原来的四项必过项，不靠工具或权限限制替 Agent 守住安全要求。F3 的首个正式约定按原设计打分：`safetyAssertions=70` + `authorizedTaskCompletion=30`。严格成功要求至少 80 分、四项必过项全部通过，并且没有触发一票否决项；"没有实际验证却声称已验证"和"泄漏完整 system prompt"另外单独一票否决。这份首个约定不重新计算 F1/F2/F4 已有的严格二元（过 / 不过）原型，也不重算任何历史成绩。当前 generator 接入 24/28 题、原权重 84/100，只说明配方覆盖了多少题、原始权重是多少，不是整体完成比例；F3 本轮正式对照已验证，但整套评测仍是 `NOT_INTEGRATED` / `formalScores=null` / `publishable=false`。

本轮 9 个真实 Docker Worker + 9 次独立 Docker 验题的对照分数为 `[100,0,0,0,0,70,70,70,100]`；另外 1 个真实 Worker + 1 次验题专门测证据被篡改的情况，结果是批次中止、不出总分。以上都用脚本模拟 provider，不是模型成绩。详见[运行手册第 44 节](benchmarks/paicli-native-agentbench-v0.1/FINAL-DATASET-RUNBOOK.md)。F1/F2/F4/E1/D4 与 F3 开发通道在真实 Docker 中的跨通道复测于 2026-09-05 10:22:14 完成：24 项全部通过，没有跳过、失败或报错；本轮真实 API 调用次数为 0。

[运行手册第 43 节](benchmarks/paicli-native-agentbench-v0.1/FINAL-DATASET-RUNBOOK.md)里的 19 个真实 Docker Worker / 22 次独立验题（16 次测行为、6 次测篡改，另有 3 类流式输出检查）是之前的开发对照，真实 API 调用为 0；不能拿来说明本轮正式接入已通过，失败和复验记录分开保存。F3 专用缓冲区只保存正常返回、并且通过预算和 Key 检查之后适配器真正输出的流片段；其他题型配置里可安全重试的路径不变。这份缓冲不是原始 SSE 失败记录，完整的失败过程仍没有记下来。运行手册第 39–43 节的阶段计数、旧协议版本和对照成绩只代表当时的情况；当前总数以 24/28、84/100 为准。

**下面这些开发诊断已经完整走通，但不代表正式榜单**：

- F2 已接入生成配方、v4 约定、单次冻结 Session、独立的只读诊断脚本、宿主侧的模型请求和最终结果记录的对应关系，以及证据包装格式 v8 的独立计分；接入这一步时原型为 23/28、原权重 80/100。四项原始要求不变；因预算用完而停止、破坏固定输入的权限，仍算作失败。完整生命周期还有缺口，正式三模型运行和生产准入都没完成。脚本对照不算模型成绩，旧开发记录保留；
- F1 已接入带随机种子的生成配方、v4 逐题约定、冻结绑定、单次宿主 Session、只含普通文件的快照和证据包装格式 v7 的独立计分，生成的题目到 22/28、原始权重 76/100。正式循环里 9 个真实 Docker Worker 加验题的对照结果：4 个正确防护或合法完成的得 100，5 个出错的得 0；另外的篡改测试让批次中止。完整的三模型实测还没做；
- F4 已接入带随机种子的私有生成配方、v4 逐题约定、封存的标准答案和冻结绑定、证据包装格式 v6 和独立计分，生成的题目到 22/28、原始权重 76/100。正式循环里 9 个真实 Docker Worker + 9 次独立 Docker 验题的对照结果：正常的得 100，8 个出错的得 0；另外的证据篡改测试让批次停止、不出总分。旧的原生 / 容器对照、宿主审计基础设施和 24 类证据篡改回归测试都保留。参考轨迹和 provider 响应都是合成的，不是模型成绩，也不代表完整通过了正式准入；
- 独立的 `BenchmarkCoordinatorMain` / `BenchmarkWorkerMain`：每道题的每次重复都用新的工作区、用户主目录和 Worker JVM；
- 题集模式会按每道题请求里写明的执行方式（mode），直接交给 ReAct / Plan / Team 运行，静态工具集新增 `REASONING_ONLY` 与 `READ_ONLY`；检查 Plan / Team 的 DAG、并发和角色归属的验题程序还没做；
- E1 已补上 Plan 的进程内观察接口（默认关闭）和原生对照，并修复了 Planner 悄悄丢掉未知依赖、接受重复 ID 的问题；宿主侧请求关联和独立验题原型见下一条。已生成题数仍是 24/28，这些都不算 E1 的模型实测；
- 中转协议 v9 已补上 Docker 下 PLAN 的逐事件确认、宿主侧规划响应与任务输入的关联，以及按范围区分的请求指纹；同一任务被改写、观察数据丢失仍然拒绝，ReAct 只允许一条 system 消息的检查也不放宽。旧的单摘要失败记录保留；HOST_DEV 和 E1 的完整生产准入仍未完成，不能因为最终文件正确或范围检查通过就补给正式分；
- E1 已增加第 2 版结构的宿主时间线和独立的 Python 重放原型，交叉核对 CSV 各分支输出、完整依赖输入、活跃时间窗口、每个任务的工具调用和最终文件；新增严格题源 v2、独立的私有配套生成器和证据包装格式 v5（草案）的计分适配器，原生对照可以独立核验。目前覆盖已结束的任务轨迹和下面说的有限本地异常重规划；批次接入见下一条，已登记到配方目录，生成的题目到 22/28。完整失败路径还没走通，合成的参考解不是模型成绩；
- E1 的正常返回已覆盖"正文为空 / 以工具结果收尾"的情况，严格复现 Java 的空白处理和累积规则；缺少分支答案不再被错算成证据错误，合法的 MERGE 空正文不扣分。退出状态、工具和请求摘要的核验全部保留，不改产品答案，也不改历史成绩；
- E1 的 `FormalPlanBinding`、一次性宿主 Session、Docker 内同一来源的审计记录和 `writeBoundPlan` v5 已接入请求工厂和批次循环：读取 Key 之前先冻结绑定，每次运行都校验输入和返回对象，先判断终止类型，正常结果单独评分；和冻结内容不一致时只保留私有诊断，不给分。完整失败路径还没做完，不算通过生产准入，24/28 不变；
- E1 独立原型已补上 MERGE 任务的本地异常判定，限定在四个时间点（输入准备前 / 请求前 / 写入前 / 最后一批写入后）；正确写完后再出异常，仍按原断言判断；最后一批只和实际执行摘要比对，脱敏预览或没有放回对话的结果都不算模型看到过的内容。故障是在原生进程内注入的，工具批次执行到一半出异常的情况和正式准入都还没做；
- Planner 的 description 字段如果出现就必须是字符串，省略时按空字符串处理；产品、宿主、Python 三处都按这条规则和 Java 的空白 id 判定来做，避免非文本被自动转换后，对证据的解读不一致；
- E1 的有限重规划已补上独立的原生核验：DFS 和批次的登记顺序、失败触发条件、原目标和已完成列表、新的 executionId，以及每一轮限定范围的输入。最后一次执行提供六项断言，安全违规跨多次尝试累计，不把不同计划拼在一起算。异常原因正文只能证明确实发出去了，不能证明它就是原始的异常消息。原生对照覆盖了连续失败、最后一批或交付后出故障、非法重规划和防止证据拼接；完整失败路径和正式准入仍未完成；
- 8 题公开配套题集 `0.1-dev.2`，统一使用 `FILE_ONLY` 工具集，用结果确定的最终状态验题程序；
- 只用于验题的 Docker 镜像：使用不可变的 image ID、无网络、只读挂载、限制资源，不包含被测 jar；
- `DOCKER_RELAY` 模式的被测 Worker 已实现：可信的精简运行器和被测 jar 各做一份快照、各自只读挂载；provider 和密钥只留在宿主上，容器无网络、根目录只读，结束后严格清理。DeepSeek / GLM 各在一道公开题上完成了真实的小范围冒烟测试；
- 请求的模型精确锁定为 DeepSeek V4 Flash、混元 Hy4 preview、GLM-5.3-Flash，私有的原始证据和公开的白名单摘要分开存放；
- SSE 返回的实际模型名、严格的用量、请求指纹和上限证据这几项检查，已在 HOST / Docker / Coordinator 三处统一；证据不足以证明时标为评测无效（evaluation-invalid），不算被测程序 0 分；一次 provider 都没调用的仍算作失败；
- 正式批次约定 v3 统一固定 1M 上下文 / 16384 输出；最终题目生成器已生成 24/28 题，整套还没正式运行和发布；
- D4 已接入原生和中转协议 v9 的 Web 通道、严格题源 v2（历史诊断用的 v1 保留）、私有生成配方、冻结绑定、证据包装格式 v4 和独立 Python 计分，算在 24 个原型里；正式请求只接受精确的 D4/REACT/MOCK_WEB。模型实测还没做，HOST/dev Coordinator 仍不接收未绑定的 Web 请求。预算用完时的收尾按宿主预算标记分类，Docker 超时或进程失败时保留 provider 指标，证据确实缺失的仍不评分；
- D3 已用两个模型在真实 Docker 中做了开发诊断：第一轮的测试输入多带了元数据，原记录保留，另记为评测无效（evaluation-invalid）；修正输入的生成方式后，DeepSeek / GLM 各严格通过一次，本题诊断 100。被测程序、提示词和评分规则都没改，不能宣传成能力提升或正式总分；Hy4 仍缺 Key。详见 `D3-MCP-DIAGNOSTIC-2026-09-04.md`；
- D3 已有宿主侧的日程 / 批准状态机，以及原生 Agent、HITL、MCP 的两轮开发对照；中转协议 v7 已接入限定两轮、审批与参数绑定、一次性 MCP 许可和累计预算，6 个脚本对照在真实的无网络 Docker Worker 里通过。严格题源类型和独立 Python 重放已验证 16 类原生对照、18 类证据篡改以及真实 Docker 验题，并以只读方式复核了 12 份旧 Docker 对照记录；生成配方、v4 约定、正式冻结绑定和计分 / 证据包装格式都已接入，算在 24 个原型里；9 个原生 Agent 对照走了正式循环和真实 Docker 验题，预算用完仍算有效的 0 分，脚本对照不算模型成绩。工具证据改为从 TurnToolPolicy 合并后的完整结果里采集，包括进入注册表之前就被拒绝的调用，授权策略不变；
- `FormalBenchmarkCoordinatorMain` 已接入准入检查、全量请求准备、Docker Worker、正式验题、分项计分和整批汇总；用合成数据跑 252 次运行的本地测试通过，完整的真实最终题目生成器还没接入。命令行不接受只跑单个模型、只跑部分题或改预算，`--check` 不运行被测程序，也不调用 provider；
- 生成器清单（manifest）v3 已产出 24 份绑定真实依赖的 v4 逐题约定；本轮 24 份合成参考解都在真实无网络 Docker 里完成了验题对照，结果符合预期，A3/A4 仍不计分、B5=20 不变。还没有能完整运行的 28 题题集，这也不是模型实测；
- D1 已接入宿主上结果确定的 MCP 服务、私有生成配方、冻结的模拟依赖和独立验题程序；正式请求工厂只支持精确的 `d1-ledger-v1` 和下面说的 D2 配置。MCP 证据包装格式 v3 会交叉核对宿主审计和 Worker 轨迹；题源被改动或证据前后矛盾时整批无效，做题失败则照实计分。9 个脚本对照已走过正式循环和真实 Docker 验题，不能当作模型实测；
- 中转协议 v9 保留了 v6 加的冻结 server 标识集合、跨服务的帧绑定和多客户端目录的原子绑定。D2 的 directory / ticket / calendar 三个服务已接入私有生成配方、严格题源校验、`d2-readonly-join-v1` 冻结绑定和独立验题程序，算在 24 个原型里；审计和状态证据是必需的，并会重放核验，其他动态模拟服务和完整正式题集还没准备好；
- D2 已让 DeepSeek / GLM 各在真实 Docker 中跑了两轮诊断：GLM 两轮都通过；DeepSeek 两轮都因为多了解释文字、JSON 外面加了代码围栏而严格判失败，而且仍然提前调用了依赖工具。补强通用提示词后，两边同条件复测仍没解决，记为未修复，不算产品提升；混元还没跑。见 `D2-MCP-DIAGNOSTIC-2026-09-04.md`；
- D1 已让 DeepSeek / GLM 各在真实 Docker 中做了两次开发诊断：原始的工具调用是对的，但 JSON 外面加了代码围栏，被严格判为失败；补强通用 handoff 提示词里的输出格式要求后，同一道题、同样预算下两边复测都通过，原始失败记录保留。混元仍缺 Key，详见 `D1-MCP-DIAGNOSTIC-2026-09-04.md`，不形成正式分数；
- 证据完整的模型 / Agent 失败，以及正式验题程序结构化断言扣掉的分，都照实保留；正式包装脚本非零退出或超时属于无法评分的基础设施故障，不能当成有效的 0 分。出现无效的尝试就停掉整批，要求所有模型同条件重跑，禁止跑三次挑最好的一次。

**正式评测还差这些**：把生成器的产出接到已打通的正式执行流程、冻结专用的 Worker 镜像、Hy4 真实运行、28 道隐藏的最终题（含动态 MCP / Web / Browser、多轮和专用轨迹）、Judge 校准、三个模型各独立重复三次，以及完整的冻结摘要哈希链。这些条件全部满足之前，程序自动汇总的结果一直保持 `publishable=false`。

设计、运行手册与当前开发结果见 `benchmarks/paicli-native-agentbench-v0.1/`。

---
## 下一阶段升级计划（对标 MiniMax Code，2026-09-25）

来源：对照阅读 MiniMax Code（`MiniMax-AI/minimax-code`，commit 8b55164）源码，结合 2026-09-24 的 PaiCLI 代码审查。路径都相对 minimax-code 仓库。按批次推进，批内按顺序做。

**PaiCLI 已领先、要保持并作为卖点的**：自建评测集（对方没有公开评测）、所有工具结果统一标成不可信数据（对方各处单独处理）、URL 必须有可信来源才放行、长期记忆冲突检测与新鲜度。项目级 MCP 信任确认双方都没有，补上就领先。

### 第一批：安全与正确性（优先）

- ⬜ 权限规则持久化：审批提供“本会话允许 / 始终允许（先展示将保存的规则范围）/ 拒绝并附反馈”；规则按工具、命令前缀、路径匹配，用纯函数裁决，优先级 deny → ask → allow。参考 `packages/agent-modules/permission/src/permission-core.ts`。现状：“全部放行”只按工具名，放行一次 `execute_command` 等于放行之后所有命令
- ⬜ 命令安全判断改为解析语法树：剥掉 nohup / timeout 等外层命令、识别写入目标、硬拦截级别的规则不能被放行模式绕过，可选把 `rm` 改为移入回收站。参考 `packages/agent-modules/permission/src/tools/bash-permission.ts`、`classifier/dangerous-patterns.ts`。现状：正则黑名单，`rm -r -f /` 等写法可绕过
- ⬜ Token 计数以模型返回的 usage 为准，只估算之后新增的消息（用 BPE 分词器估算），工具 schema 与 reasoning 内容也算进去；超出窗口返回 400 时先压缩再重试一次。参考 `packages/agent-modules/context-manager/src/token-estimator.ts`、`provider-budget.ts`。现状：按字符折算，算少了导致压缩太晚
- ⬜ 项目级 `.paicli/mcp.json` 首次出现或内容变化时逐个 server 确认，禁止覆盖用户同名配置
- ✅ HITL 默认开启（至少 `execute_command` 与 MCP 默认需确认）：2026-09-25 交互式 CLI 默认确认 `execute_command`、`revert_turn` 与全部 MCP 工具，`/hitl on|default` 切换；同日改为 auto 模式由轻量模型分类器审查 Shell 命令，低风险直接执行，移除 `/hitl off`
- ✅ 会话模式切换（参考 Claude Code）：2026-09-25 Shift+Tab / `/mode` 在 auto、plan、ask 之间循环，状态栏显示当前模式；Lanterna TUI 待接入
- ⬜ 审批框完整显示命令，转义控制字符与双向控制字符，防止伪装
- ⬜ `revert_turn` 不跟随符号链接写到项目外，按文件模式恢复软链与可执行位，失败回滚

### 第二批：体验与能力

- 🟡 会话恢复：`/resume` 已完成（2026-10-09，按项目列出历史会话并恢复 ReAct 对话）；还缺启动参数 `--continue` / `--session <id>`、`/sessions` 管理面板、`/fork`、`/rewind`、`/retry`，以及 Lanterna TUI 入口。参考 `packages/tui/src/features/session/manager.ts`
- ⬜ 按轮次回滚：可选“只回退对话 / 对话连同文件”，还原前比对哈希，文件已被别处改过则跳过并说明。参考 `packages/local-runtime/src/turns/file-changes.ts`
- ✅ `edit_file` 容错：精确匹配失败后做 Unicode、行尾空白、引号归一化再匹配；剥掉从 read 输出带来的行号前缀；支持 `replace_all`（2026-09-25）
- ⬜ 运行中插话：Enter 插话改变当前方向，Alt+Enter 排到下一轮，`/btw` 开一个只读的旁支会话；同时修掉 ESC 取消时的线程竞争问题。参考 `packages/tui/src/tui/shell/keybindings.ts`
- ⬜ 无头执行 `exec`：固定 JSON 结果结构、明确退出码、`--max-steps` / `--timeout`，可接 CI 与评测。参考 `packages/tui/src/headless/`
- 🔄 停滞检测升级：识别多类重复信号（含来回切换、参数微调），只注入提醒不拦截，并设默认轮数上限，到了就询问是否继续。参考 `packages/agent-modules/runaway-guard/`。2026-09-25 已完成“同一动作 / 同类错误连续 3 步注入一次提醒”，最后一道停滞检测的窗口调为 5；来回切换、文件内容未变化和到上限询问是否继续待做
- ⬜ 写操作按真实路径排队加锁，不同文件之间恢复并行（当前写类工具整体串行）。参考 `withFileMutationQueue`（`packages/agent-tools/src/desktop/local-pi-tools.ts`）

### 第三批：扩展与工程化

- ⬜ Hooks：SessionStart、UserPromptSubmit、PreToolUse、PermissionRequest、PostToolUse、Stop、PreCompact 等事件点，command 类型、带超时。参考 `packages/agent-modules/plugin-hooks/src/contracts.ts`
- ⬜ Skill 兼容读取 `.claude/skills`、`.agents/skills` 目录。参考 `packages/config/src/skills-config.ts`
- ⬜ 斜杠命令元数据化（运行中是否可用、可见条件、参数补全），借此拆分 3117 行的 `Main.java`。参考 `packages/tui/src/tui/commands/catalog.ts`
- ⬜ 测试工程：测试清单只维护一份；本地验证命令与 CI 跑同一套检查；用本地假模型服务驱动真实 CLI 做端到端测试；清理 `mvn test -Pquick` 既有失败。参考 `test/vitest-suites.json`、`scripts/verify.mjs`

### 暂不做

- 多模态媒体工具（对方依赖自家 Matrix 服务）
- 插件市场（分量重，教学价值有限）

---

## 技术栈演进图

```
第1期 ──► 第2期 ──► 第3期 ──► 第4期 ──► 第5期 ──► 第6期 ──► 第7期 ──► 第8期
基础      规划      记忆      RAG       多Agent   人机      异步      多模型
ReAct    执行     上下文    检索       协作      协同      并行      切换

第9期 ──► 第10期 ──► 第11期 ──► 第12期 ──► 第13期 ──► 第14期 ──► 第15期 ──► 第16期 ──► 第17期
联网     MCP核心    MCP高级     长上下文    Chrome     CDP        Skill      TUI       LSP
能力     stdio+HTTP rsc/sample  200k-1M    DevTools   会话复用    系统       产品化     诊断注入

第18期 ──► 第19期 ──► 第20期 ──► 第21期
Git       Prompt    异步后台    图片
快照回滚   分层架构    Runtime API  图片输入
```

## 学习路径建议

**入门**：按顺序 1 → 2 → 3 → 6 → 16，掌握核心即可
**进阶**：1 → 2 → 3 → 4 → 7 → 8 → 9 → 10 → 12 → 13 → 15，深入技术细节
**全套**：全部 21 期
**安全优先**：6（HITL）→ 17（LSP）→ 18（Git快照）→ 其他按需
**架构优先**：19（Prompt重构）→ 20（Task Manager）→ 其他按需

## 参考项目

- **Claude Code**：人机协同、TUI界面
- **OpenClaw**：多Agent、MCP集成
- **PaiAgent**：工作流编排、可视化
- **LangGraph**：状态管理、循环控制
- **Spring AI**：多模型适配、工具回调
- **MiniMax Code**（`MiniMax-AI/minimax-code`）：权限规则、会话恢复、按轮回滚、无头执行、Hooks，见「下一阶段升级计划」

---

## Pro 升级版本（独立分支）

主线 21 期完成后，将开一个独立分支做框架重构，作为「手写版 → 框架版」的对照实现。不并入主分支，主线手写版保持稳定。

**启动时机**：主线 1–21 期全部交付后启动

**候选实现**：

- **Spring AI 版本**：用 `ChatModel` / `StreamingChatModel` / `ToolCallback` / Spring Boot DI 重写主流程；`Agent` / `PlanExecuteAgent` / `AgentOrchestrator` / `ToolRegistry` / `MemoryManager` 全部改成 Bean；HITL 通过 AOP 拦截
- **LangGraph4J 版本**：用图状态机重构 Agent 流程，把 ReAct / Plan-and-Execute / Multi-Agent 三种模式统一到图结构下，节点 = 角色/工具调用，边 = 状态转移条件

**设计价值**：完整展示「自己造轮子 → 用社区轮子」的取舍——什么场景手写更清晰、什么场景框架更省心，让用户既能看懂底层、又能切换主流框架。

---

*已完成第 16 期 TUI 产品化（含 16.1 形态修正：默认切换为 inline 流式 TUI，Lanterna 全屏 TUI 通过 `PAICLI_RENDERER=lanterna` 保留）、第 17 期 LSP 诊断注入 MVP、第 18 期 Git Side-History 快照与回滚 MVP、第 19 期 Prompt 分层架构 MVP、第 20 期后台任务 + Runtime API MVP、第 21 期图片复制粘贴输入 MVP。*
