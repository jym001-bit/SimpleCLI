# PaiCLI Native AgentBench：三模型协议冻结记录

## 2026-09-05 模型范围修订

用户明确跳过 Hy4，后续只评测 `deepseek/deepseek-v4-flash` 与
`glm/glm-5.3-flash`。新 batch v4 / plan v5 按此顺序登记 28 × 2 × 3 = 168
次尝试，旧 batch v3 / plan v4 的三模型 252 次合同及历史记录保留。
Hy4 不参与新合同，不读取其凭证用于准备、不产生它的 episode 或 0 分。
以下 2026-08-31 协议与证据为历史快照；其中“三模型全部”及 Hy4 前置项只适用于
旧 v3，新 v4 的对称检查、校准与重跑覆盖登记的两个模型。
共同 1M context / 16384 output、完整 28 题、每模型三次重复及身份/usage/指纹门禁不变。
本轮未重新联网核实厂商公开能力，也尚未获得完整正式实测分数。

## 状态

- 核对日期：`2026-08-31`（Asia/Shanghai）
- 用途：为 PaiCLI 自建 Agent 评测冻结 provider 请求协议；不是外部 benchmark 成绩证明。
- 当前结论：模型 ID、官方端点、上下文、每次调用最大输出与工具/usage 语义已经冻结；正式批次仍被 Hy4 凭证、E3 三模型非计分 usage 校准与三模型同批 preflight 阻塞。

## 官方协议快照

| 组合 | 冻结请求 ID / 端点 | 官方能力边界 | 当前请求策略 |
|---|---|---|---|
| DeepSeek V4 Flash | `deepseek-v4-flash` / `https://api.deepseek.com/chat/completions` | 1M context；最大输出 384K；支持 thinking、工具调用与 usage | `temperature=1.0`、`top_p=0.95`、`reasoning_effort=max`、`thinking.type=enabled`；HTTP/1.1；工具续轮回填 `reasoning_content` |
| GLM-5.3-Flash | `glm-5.3-flash` / GLM Coding Plan OpenAI Chat Completion 端点 `https://open.bigmodel.cn/api/coding/paas/v4/chat/completions` | 1M context；最大输出 128K；支持 Function Calling、上下文缓存与流式工具参数 | `temperature=1.0`、`top_p=0.95`、`reasoning_effort=max`、`thinking.type=enabled`、`thinking.clear_thinking=false`、`tool_stream=true`、`stream_options.include_usage=true`；工具续轮回填 `reasoning_content` |
| Tencent Hy4 preview | `hy4-preview` / `https://tokenhub.tencentmaas.com/v1/chat/completions` | 1024K context；最大输入 960K；最大输出 64K；支持保留式思考、Function Calling、缓存与 usage | `reasoning_effort=high`、`stream_options.include_usage=true`，并应显式冻结 `temperature=0.9` 与 `thinking.type=enabled`；工具续轮原样回填 `reasoning_content` |

GLM 同时提供普通模型 API 端点 `https://open.bigmodel.cn/api/paas/v4/chat/completions`。本评测现有凭证与真实 smoke 使用的是官方支持的 GLM Coding Plan OpenAI 兼容端点，因此正式合同必须绑定这一端点类型，不能在批次中途切换到普通按量 API，也不能把两种凭证额度混用。

## 正式运行硬门禁

1. provider / model / endpoint 三元组必须与上表完全一致；不得用 alias、聚合路由或更强模型替跑。
2. 每个成功调用都必须返回非空且一致的服务端 `model`，并与请求 ID 完全相等。
3. 每个成功调用都必须出现 usage；缺失 usage、跨调用模型不一致或请求指纹不完整时，整次 episode 不进入计分。
4. `paicli-formal-batch-contract-v3` 强制三模型使用同一个 `1,000,000` context cap；E3 的 60k–100k token 是共享 fixture 工作量，不是 cap。冻结前必须让三家用同一 fixture 做非计分 usage 校准，且不能按模型改正文。
5. 三个 benchmark 专用 provider 构造器均在请求体发送同一个 `max_tokens=16,384`；pre-call byte-budget 与服务端 `input+output` usage 都必须落在 1M cap 内，服务端 output 还必须不超过 16,384。
6. Hy4 必须先完成单轮、原生工具调用、工具结果续轮、SSE usage 与 resolved-model preflight；凭证缺失时保持 `not_run`，不计 0 分也不估算。
7. sampling、thinking、max output、system prompt、首轮工具 Schema 和 adapter/JAR digest 必须写入 batch manifest；冻结后不得按某个模型的得分单独调整。

## 已有证据边界

- 完整 8 题 `0.1-dev.2` 运行早于 resolved-model / usage 完整门禁：DeepSeek 开发诊断 100、GLM 开发诊断 87，但正式字段为 `null`，且不能用作模型身份闭环。
- 后续 DeepSeek 与 GLM 各完成一次同题 Docker relay subset smoke，4/4 provider calls 均返回一致模型 ID、完整 usage 与相同 system/tool 指纹；它们只证明适配链路可用，不是完整集得分。
- Hy4 本地三种兼容凭证名均未配置，尚无真实请求证据。

## 一手来源

- [DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)
- [DeepSeek V4 Flash release / agent benchmark settings](https://api-docs.deepseek.com/updates/)
- [GLM-5.3-Flash model guide](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-5.3-flash)
- [GLM Coding Plan endpoint guide](https://docs.bigmodel.cn/cn/coding-plan/quick-start)
- [Tencent TokenHub language-model overview](https://cloud.tencent.com/document/product/1823/130079)
- [Tencent Hy model guide](https://cloud.tencent.com/document/product/1823/132252)
- [Tencent TokenHub model list](https://cloud.tencent.com/document/product/1823/130051)
