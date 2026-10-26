---
title: 又一个Agent项目诞生了，Python 版 PaiCLI 类似 Claude Code 的终端 Agent
shortTitle: Python 版 PaiCLI 上线
description: Python 版 PaiCLI 正式开源，4428 行代码实现完整终端 Agent CLI，涵盖 ReAct 推理、MCP 协议、三层记忆、RAG 代码检索等核心能力，附简历包装模板。
keywords:
  - PaiCLI Python
  - Python AI Agent
  - 终端 Agent CLI
  - MCP 协议
  - ReAct 推理循环
tag:
  - Agent
  - Python
category:
  - AI
author: 沉默王二
date: 2026-07-08
---

大家好，我是二哥呀。

目前 PaiCLI 已经有 Java 版、TypeScript 版和 Python 版了，三个版本的核心架构完全一致，区别只在语言层面。

先叠个甲，PaiCLI 的 Harness 能力和 Claude Code 有很大的距离，但让大家把 AI Agent 的核心技术栈全部亲手实现一遍的目的是可以实现的。

跟着做完这个项目，你会发现，ReAct、MCP、Memory 这些听起来高大上的概念，落到代码里其实没有想象中那么复杂。

代码已经同步到gitcode上了。

> https://gitcode.com/javabetter/PaiCLI-Python

![](https://cdn.paicoding.com/stutymore/best-city-ai-agent-jd-20260708142704.png)

## 01、为什么要做Python版本？

AI 时代，编程语言不再成为大家学习编程的门槛，因为你不精通的 Python/Java/TypeScript/Go，Claude Code/Codex这些 Agent 都很精通。

Python 也是很多小伙伴学习 AI Agent 开发最情有独钟的语言，所以我们就也搞了 Python 版本。

![](https://cdn.paicoding.com/stutymore/paicli-python-launch-20260708155410.png)

PaiCLI-Python 的核心依赖有 6 个。

**httpx**，异步 HTTP 客户端。和 requests 最大的区别在于原生支持 async/await，Agent 调用大模型 API 的时候可以做到非阻塞流式输出。

PaiCLI 里所有和模型的通信都走 httpx 的 AsyncClient，SSE 流式解析也是用它做的。

![](https://cdn.paicoding.com/stutymore/paicli-python-launch-20260708153647.png)

**prompt-toolkit**，交互式终端输入框架。支持语法高亮、自动补全、多行编辑、历史记录。PaiCLI 的终端交互体验全靠它，按上箭头翻历史、Tab 补全命令，这些都不需要自己从零实现。

**Rich**，终端渲染库。Markdown 渲染、语法高亮、进度条、表格、面板，全部开箱即用。Agent 输出代码片段的时候能直接在终端里高亮显示，不是一坨纯文本。

**Typer**，CLI 框架。基于 Python 类型注解自动生成命令行参数解析，比 argparse 写起来少一半代码。PaiCLI 的所有子命令都用 Typer 定义。

**MCP SDK**，Anthropic 官方的 MCP SDK。PaiCLI 直接用官方 SDK 接入 MCP 生态，支持 stdio 和 HTTP 两种传输协议，不需要自己实现协议层。

**SQLite**，记忆系统和代码索引的持久化存储。不需要额外装数据库，Python 标准库自带 sqlite3 模块，Agent 的长期记忆和 RAG 代码索引都存在本地 SQLite 文件里。

![](https://cdn.paicoding.com/stutymore/paicli-python-launch-20260708154609.png)

这 6 个库覆盖了网络通信、终端交互、命令行解析、协议对接、数据持久化五个方向。

顺便提一嘴，整个项目用 uv 做包管理，hatchling 做构建后端。

## 02、实现了哪些功能

PaiCLI-Python 的功能可以按 Agent 的核心能力来拆分。以下每个模块都是独立的，想学哪个就看哪个的源码。

**ReAct**。接收用户输入 → 调用大模型推理 → 判断是否需要工具 → 执行工具 → 把结果喂回模型 → 继续推理。

**15 个内置工具**。读文件、写文件、执行命令、搜索文件、Glob 匹配、代码搜索、记忆读写、快照创建恢复、网页搜索、网页抓取、LSP 诊断。每个工具都标注了是否只读、是否并发安全，Agent 可以根据标注做并行调度。

![](https://cdn.paicoding.com/stutymore/paicli-python-launch-20260708154950.png)

**MCP 协议接入**。通过 MCP SDK 连接外部工具服务器，支持 stdio 和 Streamable HTTP 两种传输协议。配置文件写好服务器地址和启动命令，PaiCLI 启动时自动加载外部工具。实现了工具发现、调用、资源读取、Prompt 模板获取等全部 MCP 客户端能力。

**三层记忆系统**。对话上下文是短期记忆，SQLite 持久化的跨会话数据是长期记忆，支持按关键词搜索历史记忆。

**RAG 代码库检索**。把项目源码按行拆分后存入 SQLite，支持多关键词 AND 匹配搜索。Agent 回答代码相关问题时，先用 RAG 检索定位到具体文件和行号，再去读取上下文。

![](https://cdn.paicoding.com/stutymore/paicli-python-launch-20260708160342.png)

**多模型支持**。开箱支持 DeepSeek、OpenAI、GLM（智谱）、Kimi（月之暗面）、Step（阶跃星辰）五个厂商。DeepSeek 单独做了百万 token 上下文窗口的适配和前缀缓存。切换模型只需要改配置文件里的 provider 和 model 字段。

**安全策略**。三道防线：命令拦截器过滤危险命令（rm -rf、格式化磁盘等），路径守卫限制文件读写范围不超出项目目录，审计日志记录所有工具调用的完整轨迹。高危工具调用会触发人工审批（HITL），终端弹出确认提示，用户同意后才执行。

**Snapshot 快照**。任务执行前自动保存项目快照，出了问题可以一键回滚到任意历史节点。快照按时间戳和任务阶段命名，存在 ~/.paicli/snapshots/ 目录下，不污染 git 历史。

**Runtime API**。提供 HTTP 接口，其他程序可以通过 API 调用 PaiCLI 的能力，支持后台任务队列和异步执行。

## 03、如何把 PaiCLI 写到简历上

对照目前市面上 AI Agent 岗位的 JD，PaiCLI 的功能模块基本全覆盖了。下面是简历上可以直接用的项目描述模板。

项目名称：PaiCLI-Python 终端 Agent CLI（2026.05 - 2026.07）

项目描述：类似 Claude Code 的终端 Agent 命令行工具（Python 版），支持 ReAct 推理、Multi-Model 切换、MCP 协议接入、三层记忆、RAG 代码检索、Snapshot 快照回滚，可在终端中通过自然语言驱动代码开发和调试。

![](https://cdn.paicoding.com/stutymore/paicli-python-launch-20260708161001.png)

技术栈：Python 3.11+、httpx、MCP SDK、prompt-toolkit、Rich、Typer、SQLite

核心职责：

- 基于 httpx AsyncClient 实现多模型 LLM 适配层，利用 Python 原生 async/await 做非阻塞流式推理，统一 DeepSeek、OpenAI、GLM、Kimi、Step 五个厂商的 SSE 流式接口，DeepSeek 适配百万 token 窗口并开启前缀缓存
- 实现 ReAct 推理循环，最大 20 轮自主决策，支持 15 个内置工具的并行调度，每个工具标注只读和并发安全属性供调度器判断
- 基于 prompt-toolkit 构建交互式终端，实现语法高亮、自动补全、多行编辑、历史记录等功能，结合 Rich 做 Markdown 渲染和代码高亮输出，使用 Typer 基于类型注解自动生成 CLI 参数解析
- 集成 MCP 协议客户端，基于 Anthropic 官方 MCP SDK 支持 stdio 和 Streamable HTTP 双传输协议，实现工具发现、远程调用、资源读取、Prompt 模板获取等完整客户端能力
- 设计三层记忆架构，短期记忆管理当前对话上下文，长期记忆基于 Python 标准库 sqlite3 持久化并按 scope 隔离项目空间，支持多关键词 AND 匹配的记忆检索
- 构建 RAG 代码检索模块，将项目源码按行索引存入 SQLite，覆盖 20 种文件后缀，自动跳过非代码目录，Agent 定位代码时先检索再精确读取
- 实现安全策略三件套，命令拦截防止危险操作、路径守卫限制文件访问范围、审计日志记录全量工具调用，高危操作触发人工审批确认
- 使用 uv 做包管理、hatchling 做构建后端，项目结构遵循 Python 标准 src layout，支持 `pip install -e .` 本地开发模式



## ending

AI Agent 的技术栈就那么多，ReAct、Tool Use、MCP、Memory、RAG，掰着手指头都数得过来。

无论用 Java、TypeScript 还是 Python 来实现，底层的核心机制是一样的。

语言不是壁垒，理解机制才是真正的竞争力。

【当然了，PaiCLI 不是一个完美的 Agent，但足够让大家把每一个核心模块都亲手摸一遍。】

加油吧，兄弟姐妹们。我们下期见。

