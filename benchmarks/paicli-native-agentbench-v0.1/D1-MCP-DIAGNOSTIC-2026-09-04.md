# D1 MCP 工具选择：真实 Docker 诊断与输出格式复测

## 结论与边界

DeepSeek V4 Flash 和 GLM-5.3-Flash 均在 13 个近义工具中选对唯一目标，参数、工具返回金额正确，且没有副作用。第一批最终回答额外包含 Markdown 代码围栏，违反题面“只返回 JSON”的约定，严格结果保留为失败。

补强 PaiCLI 的通用 `prompts/handoff.md` 后，两家在同一道题、同一工具目录和预算下各复测一次，均通过原有严格检查。修改不是清洗模型答案，也没有放宽 verifier；前后两批证据都保留。本例说明一个输出契约问题及一次对称复测结果，不能外推长期成功率。

混元两批均因本地凭证缺失而未运行，记录为 `CREDENTIAL_UNAVAILABLE`，不是零分。

这只是公开 seed 的单题开发诊断。没有正式分数，不合并进此前 8 题开发集分数；不构成三模型横评、28 题完成或三次重复。所有结果 `publishable=false`、`formalScore=null`。

## 相同的测试条件

- ReAct，`MOCK_MCP`，固定诊断 seed `90401`；1 个正确工具 + 12 个同 Schema 的近义干扰工具，名称和排列由 seed 确定。
- 使用真实 PaiCLI Agent、`McpClient`、JSON-RPC、动态 ToolRegistry；不是脚本代替 Agent 回答。
- MCP 服务仅为宿主确定性模拟账本，不连接真实业务系统；写入型干扰工具只改变模拟状态并留下记录。
- Candidate 在无网络、只读根、非 root 和资源限额 Docker 中运行；LLM API 和密钥仅在宿主，MCP 请求通过 relay v5 转发。
- 每次全新 workspace/home/mock；8 分钟期限，累计 100,000 token，32 次硬迭代、8 次停滞窗口，1,000,000 context、每次 16,384 output。
- 同一验题条件：仅一次正确调用、参数精确匹配、无其他工具调用/副作用、最终回答是字段和值精确匹配的纯 JSON。围栏、重复字段、尾随内容、浮点替代整型均不接受。
- 第一批与复测的工具 Schema SHA-256 相同；可信 runner 内容清单 digest 相同。模型 resolved identity、usage、请求指纹和 cap 证据全部闭环。

## 全部实际结果

| 批次 | 模型 | 工具选择/参数/金额 | 纯 JSON | 严格诊断 | LLM 调用 | 输入/输出 token | Worker 耗时 |
|---|---|---|---|---|---:|---:|---:|
| 原始 | DeepSeek V4 Flash | 正确 | 失败：代码围栏 | 失败，保留 | 2 | 9,730 / 203 | 2,721 ms |
| 原始 | GLM-5.3-Flash | 正确 | 失败：代码围栏 | 失败，保留 | 2 | 9,031 / 168 | 10,336 ms |
| 修复后 | DeepSeek V4 Flash | 正确 | 通过 | 通过 | 2 | 9,933 / 227 | 2,997 ms |
| 修复后 | GLM-5.3-Flash | 正确 | 通过 | 通过 | 2 | 9,246 / 188 | 9,246 ms |
| 两批 | 混元 Hy4 preview | 未运行 | — | 凭证缺失 | 0 | — | — |

四次真实 episode 共 8 次 API 调用、37,940 input token、786 output token、4 次 MCP 工具调用。每次调用记录都只有 `CORRECT_READ`，副作用计数均为零。耗时仅描述这四次观测，不是速度榜单。

本地测试还包含正确工具、只读干扰、写入干扰、错误参数、未授权工具拒绝、握手/重放/帧序校验等控制；这些控制使用脚本 LLM，不能计为模型成绩。

最终回归：65 suites、384 tests，368 passed、16 skipped、0 failure/error；其中已实际执行 15 个已有题目的 Docker 参考解验题控制。16 个跳过项为既有 11 个文件系统能力限制、4 个 Seatbelt 限制、1 个默认关闭的付费 live 测试，与上述单独开启的真实 API 冒烟分开计数。

## 修改与证据身份

产品修改仅针对通用交付提示：用户要求可机器解析的输出时，不附加围栏、标题、说明或完成总结，不翻译数据字段，不编造未知值；未指定严格格式时才采用普通交付总结。规则覆盖所有六种 PromptMode，未加入本题答案、工具名称或模型专用分支。

| 身份 | 原始批次 | 修复后批次 |
|---|---|---|
| Candidate jar SHA-256 | `1b9f83bd6c7faaa061cd239959f2c8d65579afa59da63cbddc309b60ae187751` | `3bc8184bf4064af59c099ffe0edf5d80c45b7202f9ca5c3ba35efaa2893be267` |
| Runner jar SHA-256 | `c883814584c29b53fb3449e6b9cfb44cf2b0ce093efb870d94dd5d9fee25f813` | `524b8eb3b6bd94a2ff26b3a6d7f52707fc39e2479a9d9834c0a2b8ae402b11c1` |
| System prompt SHA-256 | `0c57b0058bec2f99e9ee31445579c386d3fdc450a40863936bdb32091ba00626` | `19b2752fc68efacabd07b0bdd229db65691733d14ea0b31e9c08d4383887926d` |

两批 runner 内容清单均为 `11d77318091fe34e7243c89a3649d73a56b6e32bd1474f635ed6cdaf9533d1e7`；jar 字节 digest 的变化不表示 runner 代码改变。工具 Schema digest 均为 `24d67c680b6dfd97cb4332a55905ea9e2c444e710321aa0dd906e7739d50f53d`。

Worker image 为 `sha256:742ecfeab2543923dffe37aee61ae8b7e3490d88ad28b205f574a07b01754608`。这是沿用的开发运行镜像，label 仍为 `dev-pilot-verifier-only`，不是已冻结的专用正式 Worker image。

原始私有目录 `/private/tmp/paicli-d1-relay-20260904.4UBI0w`；修复后目录 `/private/tmp/paicli-d1-relay-output-contract-20260904.J3UeKm`。每个 provider 子目录保存 `result.json`；实测模型另有完整 trace、workspace/home 和实际挂载 jar 的 staging 快照。目录 `0700`、结果 `0600`，raw 不复制到仓库。这些是本机临时目录，不是长期归档承诺。

结果文件 SHA-256：

| 批次 | DeepSeek | GLM |
|---|---|---|
| 原始 | `0bc0e64f79de1a9e7df39f16471908d69a4b266b0b1e0d19cfed48354fa3ee72` | `bd29ea53314f08a97ede7be429911bc2a9f3e1ed98e02e512e28b9f45b8b2d50` |
| 修复后 | `44f81db1d6142f05d59d21380720a418f489fcc1cd594a92b3fbf9084ce60b64` | `4f3a2a7e0ef778e34fc47fde545530537c0b8af00c6f3d6d4dafcf23069e641f` |

## 剩余缺口

D1 通道已能真实运行，但仍需接入私有 final generator、冻结 mock source/digest、隐藏 verifier 的可信审计输入和正式分项合同。正式 admission 仍拒绝 MCP 题；已物化数量仍为 15/28。混元凭证、其余状态型题、Judge 校准、完整三模型三次重复和专用正式 Worker image 继续待完成。

以上为该批次完成时的工程状态。随后 D1 已接冻结绑定与独立 verifier，generator 扩展为
16/28，见 `FINAL-DATASET-RUNBOOK.md` 第 13 节；原始运行身份和结果不变。
同日 D2 又复现 DeepSeek 输出格式失败，提示加强后的复测仍未解决，见
`D2-MCP-DIAGNOSTIC-2026-09-04.md`。D1 的一次通过不能外推为通用格式遵循问题已解决。
