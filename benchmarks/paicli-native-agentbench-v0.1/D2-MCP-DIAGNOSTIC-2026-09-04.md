# D2 三服务 MCP 联查：真实 Docker 诊断与提示补强实验

## 结论与范围

GLM-5.3-Flash 两轮均完成三服务 ID 关联，并按要求输出纯 JSON。DeepSeek V4 Flash
两轮最终都取得正确业务数据，但把存在依赖的调用放入同一批次，且最终回复含解释和
JSON 围栏，严格任务结果均失败。第二轮补强通用提示未解决问题，不能称为修复成功。
混元 Hy4 preview 两轮均因本地凭证缺失未调用，不记为产品 0 分。

这是固定 sibling 的开发诊断与一次对称复测，不是三模型横评、正式 D2 成绩或稳定成功率。
原始失败没有覆盖；不把 JSON 从回复中剥离后改算通过。每份记录均保持
`publishable=false` / `formalScore=null`，不合并进此前 8 题开发集成绩。

## 执行条件

- ReAct / MOCK_MCP；同一诊断 seed 的三套独立服务 directory、ticket、calendar，
  各有独立原生 McpClient、握手、请求 ID、业务状态与审计。共 3 个读工具、3 个写负对照。
- 同名人属于不同部门；employeeId、ticketAssigneeId、calendarPersonId、calendarReference
  不可互换。查询需排除过期/关闭工单和过去/取消日程，再选择最早的有效日程。
- 100k 累计 token、32 轮、8 轮停滞、12 分钟 timeout；1M context、单次 16384 output。
- Candidate 在无网络、只读根、非 root、资源受限 Docker 中执行；模型 API 与密钥只在
  宿主，MCP 走 relay v6 的已登记服务路由，不连接真实业务系统。
- 使用现有开发 runtime image：
  `sha256:742ecfeab2543923dffe37aee61ae8b7e3490d88ad28b205f574a07b01754608`。
  镜像标签仍为 verifier / dev-pilot-verifier-only，**不是正式 Worker 镜像冻结证明**。

## 实测结果

| 批次 | 模型 | 最终业务数据 | 严格任务 | LLM / 工具调用 | input / output token | Worker 耗时 |
|---|---|---|---|---:|---:|---:|
| 原始 | DeepSeek V4 Flash | 正确 | 失败：解释与围栏 | 4 / 4 | 17,343 / 1,414 | 9,636 ms |
| 原始 | GLM-5.3-Flash | 正确 | 通过 | 4 / 3 | 16,342 / 1,208 | 38,930 ms |
| 提示补强后 | DeepSeek V4 Flash | 正确 | 失败：解释与围栏 | 4 / 4 | 17,763 / 1,315 | 8,941 ms |
| 提示补强后 | GLM-5.3-Flash | 正确 | 通过 | 4 / 3 | 16,494 / 1,137 | 29,890 ms |

四次真实 episode 共 16 次 LLM API 调用，67,942 input、5,074 output、50,048 cached input
token，14 次 MCP 工具调用。resolved model、usage、请求指纹、context/output cap 门禁均通过；
三服务初末状态 digest 均一致，副作用为零。耗时仅为这四次观测，不是速度榜单。

DeepSeek 原始第 2 次 LLM 返回同时包含 ticket 与 calendar 调用，calendar 使用了未经
结果提供的关联值，返回空记录；下一轮才用真实 reference 纠正。提示补强后仍并发发出
ticket 与 calendar，后者的 reference 为空，被服务拒绝，随后纠正。开发诊断允许只读
探索后恢复，因此这些轨迹单独记录，最终严格失败仍由非纯 JSON 回答触发。

## 改动与复测约束

只修改通用 `prompts/base.md` / `prompts/handoff.md`：说明并行调用不会自动传递前序结果，
依赖参数必须来自已观察到的结果；严格 JSON 约束适用于整条回复。未加入 D2、服务字段、
题目 ID 或参考答案，未改题面、mock、工具 Schema、预算或诊断判定。

对实际挂载的两份 Candidate ZIP 全部 9,155 个文件逐项计算 SHA-256，只有上述两个
prompt 资源不同；两份 runner 的全部 49 个文件内容一致。JAR 文件摘要不同不能据此
声称 runner 代码有改动。提示词存在不等于行为被强制执行，本次证据明确显示补强仍不足。

| 身份 | 原始批次 | 提示补强后 |
|---|---|---|
| Candidate SHA-256 | `46c58814085c5eb7905ccf0def310b23bac8157640729f6c225982f6a38b275e` | `3123550c4dc22b1ecd246577190ce1ab6192cf2592dd66224b9b16452800a547` |
| Runner SHA-256 | `4380f9cecd03f8dc1567c9564a9c328345f66a0469b534a45e2f8b8187fa7571` | `33ff528715bac7cd6462256861d0ec46e6778e31f490c3afa6981494bdab06de` |
| system prompt SHA-256 | `d2f83d0f4a7b8e08757b75d61e06880b1bffa274d486d689a535a5ab2b696d52` | `6ceaa71e31b2945b9eac466c398fe03a5c2849f480ee260a1e697d6c76b67f97` |

四次共用 host definition SHA-256
`87d0e7df38b62bf11958adaf7341bdc5ff8832246215f29864f152062444b876`，
工具 Schema SHA-256 `f49955f02f714a7354a104bb68e69d0584fd261ed3ecad04e908c0172b107fca`，
runner inventory SHA-256 `134ab29a17cb816b540ba6ba6f2c6721efb1382345c78e025f7d4280682276a0`。

## 私有证据与后续

原始根 `/private/tmp/paicli-d2-live-20260904.jPuYle`，复测根
`/private/tmp/paicli-d2-dependency-format-20260904.QkpKFg`。目录 `0700`，结果 `0600`；
每个已运行 provider 保存 host definition、LLM 元数据 trace、工具/宿主审计、答案、
状态摘要和实际挂载 JAR 快照。未声称保存完整逐 token 原始模型响应。
这些是本机临时证据，不是长期归档承诺；不公开原始业务数据、完整提示或凭证。

| 批次 | DeepSeek result.json SHA-256 | GLM result.json SHA-256 |
|---|---|---|
| 原始 | `8049ab8926d9b2bf581ea62e00d555bcea7dd7db460d32c4ef0aadaea4e83427` | `cfa97b99391bdf53aff86db26ea3b2fb6e75369b170d8b2477412dc651d5fd38` |
| 提示补强后 | `276dcf65a858b6dc4b8e89eeaf7326ab8b0eb8d8abe71df8c12e028453bbee59` | `73060c20784a5adb5b7b77ae2e5d92e3f2fb82ed6b5550a9c612eec98568f51b` |

D2 还需私有 final recipe、严格源数据校验、冻结绑定、独立 verifier 与正式 audit envelope。
因此 generator 仍为 16/28，正式 preparation 仍拒绝 D2。还需继续处理 DeepSeek 的依赖
调用和输出契约遵循问题；两次失败不足以估计通用失败率，也不能用此前 D1 的一次通过
声称同类问题已经解决。Hy4、其余正式题、Judge/专用轨迹、镜像与三次重复门槛保持不变。

本轮基础回归为 69 suites / 397 tests：380 passed、17 skipped、0 failures/errors。
跳过项是 11 项宿主文件系统、4 项 Seatbelt、2 项默认关闭的 D1/D2 live 测试；四次显式
D2 模型实测与单测计数分开。16 题参考解 Docker 控制、D1 的 9 次 Docker 正反控制和
D2 原生 Agent 多服务控制均实际执行，但测试控制不计模型分数。

后续接线更新（同日）：D2 已补齐严格私有 recipe、冻结 binding、状态审计 envelope 和
独立验题器，generator 现为 17/28；详见运行手册第 15 节。新增的是无模型 API 的
正反控制，不是再次模型复测，本报告中 DeepSeek 两次失败、GLM 两次通过的结论保持不变。
