---
title: TypeScript 终端 Agent PaiCLI 上线了，类 Claude Code 和Qoder CLI
shortTitle: TypeScript 版 PaiCLI 上线
description: TypeScript 版 PaiCLI 正式开源，5396 行代码实现完整终端 Agent CLI，涵盖 ReAct 推理、Ink+React TUI、Plan-and-Execute、Multi-Agent 编排、MCP 协议、Skill 系统等核心能力，附简历包装模板。
keywords:
  - PaiCLI TypeScript
  - TypeScript AI Agent
  - 终端 Agent CLI
  - Ink React TUI
  - Plan-and-Execute Agent
tag:
  - Agent
  - TypeScript
category:
  - AI
author: 沉默王二
date: 2026-07-08
---

大家好，我是二哥呀。

PaiCLI 的 TypeScript 版本也上线了。目前 PaiCLI 已经有 Java 版、TypeScript 版和 Python 版了，三个版本的核心架构完全一致，区别只在语言和生态层面。

和 Python 版一样，PaiCLI-TS 不是要造一个 Claude Code，咱没那水平。哈哈。

对于前端背景的小伙伴来说，TS 版就是学习终端 Agent 最好的入口。

代码已经同步到 gitcode 上了。

> https://gitcode.com/javabetter/paicli-ts

![](https://cdn.paicoding.com/stutymore/qoder-desktop-release-20260628234804.png)

## 01、TypeScript 版有什么值得学的？

PaiCLI-TS 用 Ink + React 搭了一套声明式 TUI，核心依赖有 8 个。

**Ink + React**，终端 UI 框架。Ink 让你可以用 React 组件的方式写终端界面，状态变了 UI 自动重渲染，和写 Web 前端的体验一致。PaiCLI 的流式输出、工具调用状态展示、进度面板，全部是 React 组件。对熟悉前端的小伙伴来说，上手成本几乎为零。

![](https://cdn.paicoding.com/stutymore/paicli-ts-launch-20260708182232.png)

**undici**，Node.js 官方高性能 HTTP 客户端。和 node-fetch 相比，undici 直接走 Node.js 底层的 HTTP 解析器，性能更好。PaiCLI 所有和大模型 API 的通信、SSE 流式解析都走 undici。

**commander**，CLI 框架。用链式调用定义命令和选项，比手写 process.argv 解析省不少工作量。PaiCLI 的所有子命令都用 commander 定义。

**zod**，运行时类型校验。TypeScript 的类型只在编译期生效，运行时就没了。zod 填的就是这个坑，用来校验 LLM 返回的 JSON 结构、用户配置文件格式、工具输入参数。Agent 系统里模型输出的结构不可控，没有运行时校验很容易崩。

**@modelcontextprotocol/sdk**，Anthropic 官方 MCP SDK 的 TypeScript 版本。直接用官方 SDK 接入 MCP 生态，支持 stdio 和 HTTP 两种传输协议。

**better-sqlite3**，同步 SQLite 驱动。better-sqlite3 是同步 API，用起来比异步的 sqlite3 包简洁，记忆系统和代码索引都用它做持久化存储。

**fast-glob**，文件匹配库。比 Node.js 原生的 glob 模块快 2-3 倍，PaiCLI 的 Glob 工具和 Skill 发现都依赖它。

**chalk**，终端着色。和 Ink 配合使用，给日志、错误信息、状态提示加颜色。

![](https://cdn.paicoding.com/stutymore/paicli-ts-launch-20260708182953.png)

整个项目用 pnpm 做包管理，tsup 做打包构建，vitest 做单元测试。TypeScript 5.5+ 的类型推断能力在写工具注册、事件系统这类泛型密集的代码时帮助很大。

## 02、实现了哪些功能？

每个模块的源码都是独立文件，想学哪个就看哪个。

**ReAct 推理循环**。接收用户输入 → 调用大模型推理 → 判断是否需要工具 → 执行工具 → 把结果喂回模型 → 继续推理。整个循环基于 AsyncGenerator 实现流式事件输出，上层可以按事件消费。

**Plan-and-Execute**。接到复杂任务后，先调用 LLM 生成一份分步计划，然后逐步执行每个步骤，每步执行完把结果带入下一步的上下文。适合“重构整个模块”“迁移数据库”这类多步骤任务。

**Multi-Agent 编排**。内置了 4 个预定义角色：架构师（负责设计决策）、开发者（负责代码实现）、审查者（负责质量把控）、研究员（负责信息搜集）。可以把一个大任务拆成子任务分配给不同角色的 Agent，支持任务之间的依赖关系。

![](https://cdn.paicoding.com/stutymore/paicli-ts-launch-20260708203603.png)

**Skill 系统**。支持三级 Skill 加载：内置 Skill、用户级 Skill（~/.paicli/skills/）、项目级 Skill。每个 Skill 是一个目录，里面放 SKILL.md 定义描述和触发条件。这套机制和 Claude Code 的 Skill 系统思路一致。

**Prompt 分层组装**。系统提示词不是一坨硬编码的字符串，而是按层组装的：基础 Prompt → 模式 Prompt → 人格 Prompt → 用户自定义 Prompt → 项目级 Prompt。每一层独立维护，修改一层不影响其他层。

**Token 预算管理**。提供三种模式：short（上下文窗口的 30%）、balanced（60%）、long（85%）。超过压缩阈值时自动触发上下文压缩，保留最近 30% 的消息，旧消息合并成摘要。

**SDK 无头模式**。提供了一个独立的 SDK 入口（`import { Agent } from 'paicli-ts/sdk'`），可以作为 npm 包被其他项目引用。不需要启动终端交互，直接在代码里调用 Agent 的能力。

![](https://cdn.paicoding.com/stutymore/paicli-ts-launch-20260708203624.png)

**9 个内置工具**。Bash、ReadFile、WriteFile、Grep、Glob、ListDir、WebSearch、WebFetch、SaveMemory，覆盖了 Agent 日常工作的核心场景。扩展工具通过 MCP 协议接入，不需要改内置代码。

## 03、如何把 PaiCLI 写到简历上

对照目前市面上 AI Agent 岗位的 JD，PaiCLI-TS 的功能模块基本全覆盖了。下面是简历上可以直接用的项目描述模板。

项目名称：PaiCLI-TS 终端 Agent CLI（2026.05 - 2026.07）

项目描述：类似 Claude Code 的终端 Agent 命令行工具（TypeScript 版），支持 ReAct 推理、Plan-and-Execute 任务规划、Multi-Agent 多角色编排、MCP 协议接入、Skill 系统、三层记忆、上下文压缩，可在终端中通过自然语言驱动代码开发和调试。

技术栈：TypeScript 5.5+、Node.js 18+、Ink + React、undici、commander、zod、@modelcontextprotocol/sdk、better-sqlite3

核心职责：

- 基于 Ink + React 构建声明式终端 UI，实现流式文本输出、工具调用状态面板、Markdown 渲染等组件，利用 React 状态驱动自动重渲染
- 基于 undici 实现多模型 LLM 适配层，支持 DeepSeek、GLM、OpenAI 三个厂商的流式推理接口，通过工厂模式统一客户端创建
- 实现 Plan-and-Execute 任务规划模式，LLM 先生成分步计划再逐步执行，每步结果自动注入下一步上下文，支持中途中止
- 构建 Multi-Agent 编排器，内置架构师、开发者、审查者、研究员四种预定义角色，支持子任务依赖关系声明和按依赖顺序调度
- 设计三级 Skill 加载机制（内置、用户级、项目级），运行时按目录自动发现和注册，支持 SKILL.md 描述驱动的触发匹配
- 实现 Prompt 分层组装器，按基础、模式、人格、用户、项目五层独立维护系统提示词，修改任一层不影响其他层
- 设计 Token 预算管理模块，支持 short/balanced/long 三种预算模式，超过压缩阈值自动触发上下文摘要压缩
- 基于 zod 实现运行时类型校验，覆盖 LLM 返回结构、工具输入参数、用户配置文件，防止模型输出结构异常导致系统崩溃
- 提供 SDK 无头模式入口，其他 Node.js 项目可通过 npm 包直接引用 Agent、Plan-and-Execute、Multi-Agent 编排等能力
- 使用 pnpm 做包管理、tsup 做构建打包、vitest 做单元测试，项目支持 `npm link` 本地开发


![](https://cdn.paicoding.com/stutymore/paicli-ts-launch-20260708204922.png)

同一套 Agent 架构，三种语言实现，ReAct、MCP、Memory 的核心机制一模一样。

【选一门最顺手的语言，把 ReAct、MCP、Memory 这几个模块从头到尾写一遍，就够了。】

加油吧，兄弟姐妹们。我们下期见。
