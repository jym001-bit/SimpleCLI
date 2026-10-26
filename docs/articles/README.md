# PaiCLI 教程与面试题

这里是 PaiCLI 配套文章的唯一源。javabetter.cn 上 `/sidebar/itwanger/paicli/` 下的页面是副本，改动先在这里完成，再用 `tools/sync-articles-to-javabetter.sh` 同步过去，线上 URL 保持不变。

文章 frontmatter 沿用 VuePress 格式（title / shortTitle / description / tag / category / author / date），图片统一走 CDN 链接，两边可以直接复用同一份 Markdown。改文章时以当前源码为准，涉及的源码事实优先引用 `AGENTS.md` 和对应类。

## 从零实现系列

| 文章 | 主题 |
|---|---|
| [build-agent-from-scratch.md](build-agent-from-scratch.md) | 第 1 期：ReAct 循环与工具注册 |
| [build-agent-p2-plan-execute.md](build-agent-p2-plan-execute.md) | 第 2 期：Plan-and-Execute 与 DAG 调度 |
| [build-agent-p3-memory.md](build-agent-p3-memory.md) | 第 3 期：短期记忆、长期记忆与上下文压缩 |
| [build-agent-p4-rag.md](build-agent-p4-rag.md) | 第 4 期：RAG 代码检索 |
| [paicli-multi-agent.md](paicli-multi-agent.md) | 第 5 期：Multi-Agent 协作 |
| [paicli-hitl.md](paicli-hitl.md) | 第 6 期：HITL 人工审批 |
| [paicli-async-parallel.md](paicli-async-parallel.md) | 第 7 期：异步与并行工具调用 |
| [paicli-multi-model.md](paicli-multi-model.md) | 第 8 期：多模型切换 |
| [paicli-websearch-webfetch.md](paicli-websearch-webfetch.md) | 第 9 期：联网能力 |
| [paicli-mcp.md](paicli-mcp.md) | 第 10 期：MCP 协议核心 |
| [paicli-mcp-advanced.md](paicli-mcp-advanced.md) | 第 11 期：MCP 高级能力 |
| [paicli-chrome-devtools-mcp.md](paicli-chrome-devtools-mcp.md) | 第 13 期：Chrome DevTools MCP |
| [paicli-cdp-session-reuse.md](paicli-cdp-session-reuse.md) | 第 14 期：CDP 会话复用 |
| [paicli-skill-system.md](paicli-skill-system.md) | 第 15 期：Skill 系统 |
| [paicli-image-input.md](paicli-image-input.md) | 第 21 期：图片输入 |
| [paicli-agentbench.md](paicli-agentbench.md) | 自建评测集：28 道题、证据检查与 LLM-as-a-Judge |

## 面试题

| 文章 | 主题 |
|---|---|
| [paicli-interview-agent-core.md](paicli-interview-agent-core.md) | Agent 核心架构 |
| [paicli-interview-memory-context.md](paicli-interview-memory-context.md) | 记忆与上下文 |
| [paicli-interview-tool-security.md](paicli-interview-tool-security.md) | 工具与安全 |
| [paicli-interview-prompt-skill.md](paicli-interview-prompt-skill.md) | Prompt 与 Skill |
| [paicli-interview-multi-model.md](paicli-interview-multi-model.md) | 多模型与成本 |
| [paicli-interview-mcp.md](paicli-interview-mcp.md) | MCP 协议与生态 |
| [paicli-interview-productization.md](paicli-interview-productization.md) | 产品化工程 |

## 其他

| 文章 | 主题 |
|---|---|
| [paicli-xuexiluxian.md](paicli-xuexiluxian.md) | 学习路线 |
| [paicli-go-launch.md](paicli-go-launch.md) | Go 版上线 |
| [paicli-python-launch.md](paicli-python-launch.md) | Python 版上线 |
| [paicli-ts-launch.md](paicli-ts-launch.md) | TypeScript 版上线 |
