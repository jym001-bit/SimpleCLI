# D3 审批后创建日程：真实 Docker 开发诊断

## 结论与范围

DeepSeek V4 Flash、GLM-5.3-Flash 在修正输入准备流程后，各完成一次严格 D3 任务：
查询可用时段、输出待批准方案，收到宿主用户批准后只创建一次日程，最终返回纯 JSON。
两者本题诊断分均为 100。混元 Hy4 preview 因本地凭证缺失未运行，分值为空。

这是固定开发 sibling 的两模型单次有效测试，**不是 PaiCLI 总分、正式 D3 成绩、
三模型横评或成功率估计**。generator 仍为 18/28 个原型、NOT_INTEGRATED；
正式 28 × 3 × 3 批次尚未运行，publishable=false、formalScore=null。

## 首轮无效原因与对称重跑

首轮两家业务断言全部通过，但均被 workspace_mutation 门禁记为 0。测试程序在
Candidate 启动前用目录复制器复制了生成器的 CASE-METADATA.json；冻结基线和声明的
fixture 只允许 README.md。两家运行后这两个文件的 SHA 均与生成源一致，工具轨迹
也只有 availability 查询和 calendar 创建。额外文件来自测试程序，不是模型写入。

严格门禁没有错误，错误发生在本次 live harness 的输入准备。首轮两次应标为
EVALUATION_INVALID、有效分值为空，而不是 Candidate 0 分。原始结果、工作区、验题
输出均保留未改写，另存 fixture-admission-erratum.json 说明失效原因；不清洗旧工作区重判。

修正仅涉及测试程序：从生成源另存声明允许的 README，形成只读 admitted fixture，
通过生产 FormalFixtureMaterializer 复制、验证完整清单，在付费调用前保存
fixture-before-worker.json。生成源元数据原封不动保留。新增无 API 回归证明元数据
不进入 Candidate，真实增加文件仍会被拒绝。

随后两家在新目录对称重跑。Candidate/runner JAR、题面、宿主 mock、工具 Schema、
system prompt、评分合同、独立验题程序及预算均相同。此次表面 0→100 不能宣传为
产品能力提升；首轮是无效输入，第二轮才是有效观测。没有降低门禁或剥离答案围栏。

## 实测记录

| 批次 | 模型 | 结果 | LLM / 工具调用 | input / output token | Worker 耗时 |
|---|---|---|---:|---:|---:|
| 首轮 | DeepSeek V4 Flash | 评测无效：输入清单不符 | 4 / 2 | 17,545 / 818 | 6,425 ms |
| 首轮 | GLM-5.3-Flash | 评测无效：输入清单不符 | 4 / 2 | 16,434 / 802 | 25,155 ms |
| 正确输入重跑 | DeepSeek V4 Flash | 本题严格通过，100 | 4 / 2 | 17,689 / 890 | 6,446 ms |
| 正确输入重跑 | GLM-5.3-Flash | 本题严格通过，100 | 4 / 2 | 16,517 / 773 | 23,937 ms |

含无效首轮，共 16 次真实 API 调用、68,185 input、3,283 output、57,792 cached input
token，8 次 MCP 工具调用。缓存是 input 的子集，不另加到输入总量。耗时仅为四次观测，
不是速度排名。两次有效结果各有 10 条宿主业务/批准审计、12 个 relay exchange 和
1 次创建副作用；未连接真实日历或业务系统。

精确模型为 deepseek/deepseek-v4-flash、glm/glm-5.3-flash。resolved model 一致、
usage 和请求指纹完整；requested/effective context 均为 1,000,000，单次 output cap
16,384。有效测试最大单次 input 为 4,968、4,601，不构成长上下文能力证明。
累计 task token 100,000、最多 32 轮、停滞窗口 8、Worker timeout 900 秒。

Candidate 运行于无网络、只读根、非 root、资源受限 Docker，API 和密钥留宿主。
退出后才物化隐藏 verifier，在另一无网络 Docker 重建审批/调用/状态及答案。两次有效
验题均 exit 0、sandboxed、输出无截断，五项断言通过、五项 hard gate 均未触发。
provider 证据、冻结源/快照/验题输入前后摘要和真实密钥 canary 检查均通过。

## 身份与私有证据

| 固定项 | SHA-256 |
|---|---|
| Candidate JAR | `6d92e90142957a6ca9e61ce2e7cdd0fdad3546df5a0d56129ff2e4dcee028a97` |
| runner JAR | `cedd78a67e8ee23e17dd2ae805b087789b5c6988904cbd2ecc01a8476088e6dd` |
| 题面 | `b11c36aa7159f648972b8b0bf947c9cb29ee50d4f2b775f30b16e42b780c6644` |
| 宿主 oracle | `eda85778fffb105e09d8d4ae0f42ee23b7d5b5ef78376f459c79e37299cf3528` |
| verifier bundle | `ff5e381d6166e678f1b965a8ce9bb7b51a9727e7abb08b97719de77b6f1efdbf` |
| system prompt | `36efcfdb8d36a0a857d6ae920be210b585644540e356cbd93a212094a7c8a029` |
| 工具 Schema | `898debe85c454c93a3f9e87123bbd85ac00eab7dff334f3fe9afea25e9d5c89d` |

Worker 开发镜像 sha256:742ecfeab2543923dffe37aee61ae8b7e3490d88ad28b205f574a07b01754608，
verifier 镜像 sha256:770224998e34bdd809f205a26d31bff62684b65e78751a3ab5c319d5791632e8。
前者仍不是专用正式 Worker 镜像冻结证明。

首轮根 `/private/tmp/paicli-d3-live-20260904.N6Pv82`；有效重跑根
`/private/tmp/paicli-d3-live-admitted-20260904.qsC4CS`。结果位于各根的
artifacts/d3-live-diagnostic/cases/D3/models/{provider}-{model}/repeat-001/attempt-001/。
目录 owner-only、结果 0600；保留实际 JAR 快照、原始账本、模型元数据 trace、工具与
宿主审计、工作区快照、隐藏验题 bundle 和 evidence envelope。这些是本机临时证据，
不是长期归档承诺；不公开原始账本、隐藏答案或凭证。

| 批次 | DeepSeek result.json SHA-256 | GLM result.json SHA-256 |
|---|---|---|
| 无效首轮，未改写 | `33ed517007bca59ee74befdfd50f50f6c8858ea5eec571d55a12343c3e949432` | `157d00fb194c55120a4a67975ae96ba679d235cb4e8b97e5e6cced65f9ef3049` |
| 有效重跑 | `427535d73392765064be8a0c9d897f7574af33930343f821c8049c6115b22822` | `129aa731c4732f47813cf5a84b66628264654f0722e7f9ec24a1c83f916fc4b6` |

## 复现与剩余工作

`D3LiveDockerDiagnosticTest` 仅显式设置 paicli.test.d3.live=true 才调用付费 API，
paicli.test.d3.check=true 仅查凭证/产物。使用新的 0700 空输出目录，不复用旧批次；
命令见 [运行手册](FINAL-DATASET-RUNBOOK.md) 第 20 节。

D3 通过不抵销 D2 已记录的 DeepSeek 依赖调用/输出契约失败。仍需 Hy4 凭证与实测、
D4–D5/E1–E4/F1–F4 十个 recipe、Judge/专用轨迹、正式镜像与完整数据冻结，以及
252 次正式真实 episode。原题数、权重与重复次数保持不变。

相关回归为 84 suites / 486 tests：466 passed、20 条件跳过、0 failures/errors。
18 个生成参考解均实际运行 Docker verifier；A3/A4 保持 unscored、B5 保持 20。
这些控制测试不计模型成绩；显式两轮 D3 live 的调用和跳过记录另列于上文。
