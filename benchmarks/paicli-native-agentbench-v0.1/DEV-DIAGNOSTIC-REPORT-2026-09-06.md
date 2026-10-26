# PaiCLI 评测开发诊断报告（草稿）：2026-09-06

状态：阶段 1 交付物 + 阶段 2 小规模实测结果（2026-09-07 补充，见第 6 节）。
除第 6 节的 2 个授权 episode 外，本报告汇总既有开发诊断证据与边界；
`publishable=false`、`formalScores=null`、整套 `NOT_INTEGRATED` 不变。
本轮工程修复（阶段 0）见第 5 节；正式 168 次批次仍未开始。

## 1. 本报告是什么、不是什么

这是当前全部**开发诊断**证据的单一入口：8 题开发集复测、D1/D2/D3 单题诊断、
跨通道复测与脚本控制的合并视图。它是草稿，供正式报告模板填充前审阅。

它**不是**：正式榜单、三模型横评、成功率估计、SWE-bench / Terminal-Bench 对标，
也不是 PaiCLI 产品能力的总结论。所有分数都是开发诊断分或脚本控制分。

模型范围（2026-09-05 修订）：DeepSeek V4 Flash + GLM-5.3-Flash。
混元 Hy4 preview 不参与新批次，历史上因缺凭证未运行的记录保留为
`CREDENTIAL_UNAVAILABLE`，不计 0 分。

## 2. 既有真实模型诊断证据汇总

| 通道 | 日期 | DeepSeek V4 Flash | GLM-5.3-Flash | 性质 |
|---|---|---|---|---|
| 8 题 dev.2 全量（ReAct/FILE_ONLY） | 2026-09-04 | 100（8/8 复验 PASS） | 100（8/8 复验 PASS） | 单次生成 + verifier-only replay |
| D1 工具选择（1 正确 + 12 干扰） | 2026-09-04 | 首批严格失败（围栏）→ 补强 handoff 提示后通过 | 同左 | 单题两批对称复测 |
| D2 三服务 MCP 联查 | 2026-09-04 | 两轮均业务正确但严格失败（解释 + 围栏） | 两轮均严格通过 | 单题诊断 + 提示补强实验 |
| D3 审批后创建日程 | 2026-09-04 | 首轮评测无效（harness 输入错误）→ 对称重跑 100 | 同左 | 单题诊断 + 勘误保留 |
| F3 注入/密钥防护正式控制 | 2026-09-05 | 脚本 provider，非模型成绩 | 同左 | 容器正反控制 |
| 跨通道复测（F1/F2/F4/E1/D4/F3） | 2026-09-05 | 真实 API 调用 0，24 项全通过 | 同左 | 脚本控制回归 |

关键数字（首次真实生成，复验不增加模型调用）：8 题全量两家共 81 次 LLM 调用、
381,035 input+output token；D1 四 episode 8 次调用；D2 四 episode 16 次调用；
D3 含无效首轮共 16 次调用。逐项明细、digest 与复现程序见各原始报告：
[8 题](DEV-PILOT-REPORT-2026-09-04.md) / [D1](D1-MCP-DIAGNOSTIC-2026-09-04.md) /
[D2](D2-MCP-DIAGNOSTIC-2026-09-04.md) / [D3](D3-MCP-DIAGNOSTIC-2026-09-04.md)。

### 2.1 从这些证据里能读出什么

- **两家的主要差异在输出契约，不在工具能力。** 8 题全量两家满分；D1 首批两家同因
  Markdown 围栏失败；D2 中 GLM 严格通过、DeepSeek 业务数据正确但回答带解释与围栏。
  这是产品提示层与模型输出习惯的交互问题，不是"哪家模型更聪明"的结论。
- **提示补强是产品改动，不是模型修复。** D1 修改 `prompts/handoff.md` 后对称复测通过；
  D2 同样补强后 DeepSeek 仍未解决。提示存在不等于行为被强制，两次结果都不外推。
- **无效样本和有效失败必须分开。** 8 题原始 89 分源于 verifier scratch 权限错误
  （验证器准备阶段故障），对 16 份冻结产物做 verifier-only replay 后 16/16 PASS，
  原始分数标记为"验证器故障，被复验替代"；D3 首轮是 harness 把 CASE-METADATA 带进
  Candidate 输入（评测无效），原始记录保留并另存勘误，对称重跑才是有效观测。

### 2.2 这些证据不能支撑的说法

- 不能说"DeepSeek/GLM 在 PaiCLI 基准上得 100 分"——那是 8 题开发集单次诊断。
- 不能把 D3 的 0→100 说成产品提升——首轮是无效输入，不是有效 0 分。
- 不能把 verifier 修复说成模型/产品提升——模型调用为零。
- 不能用单题结果外推成功率；每次都是固定 seed、单次或对称两次的观测。
- Hy4 未运行不是 0 分；新批次不参与。

## 3. 距离正式批次还差什么

题集侧（详见 [实施矩阵](FINAL-IMPLEMENTATION-MATRIX.md)）：

- 4 题未物化：D5 浏览器、E2 Team、E3 长上下文、E4 强制压缩。
- 24 个已物化 recipe（原权重 84/100）仍有生产缺口：A3/A4 Judge 通道与校准、
  语义检索证据、B5 确定性并发验证、C1–C3 toolchain/进程/命令来源证明、
  各通道失败/超时/证据截断语义。
- 专用 Worker image 未冻结；完整 suite/source/artifact digest 未齐。

运行侧：

- 正式 168 次（28 × 2 × 3）从未开始；准入 preflight 未驱动生产批次。
- F3 双模型入口仅预检通过，真实调用被外部数据传输授权拦截，需单独批准。
- 正式批次只能按冻结顺序有界小批执行，每批先定上限，保存进度与消耗后停止。

## 4. 运行入口现状

- `FormalBenchmarkCoordinatorMain` → `FormalBenchmarkAdmission` → `FormalBatchPreparation`
  → `FormalBatchRunner` 链路已接；缺能力或缺凭证整批拒绝，不接受运行时覆盖。
- 命令与边界见 [运行手册](FINAL-DATASET-RUNBOOK.md) 第 9–10、45–47 节。

## 5. 本轮工程变更（阶段 0，2026-09-06）

2026-09-05 暂停时 E2 宿主接线半成品导致源码不可编译
（`relay/TeamRequestAudit.java` 缺失但被引用）。本轮按交接第 4 节阶段 0 补齐：

- 新增 `TeamRequestAudit`：TEAM 宿主审计（scope 绑定激活、输入/索引/系统提示一致性、
  逐激活工具批次对账、预算收尾判定、封闭生命周期断言、私有 0600 快照）。
- 未改动其他任何生产文件；`TeamRelayProtocolTest` / `TeamRelayEvidenceTest`
  仍属阶段 3，未实现。

最小定向验证（真实 API 调用 0）：

| 测试 | 结果 |
|---|---|
| `TeamRequestAuditTest`（含 9 类漂移/伪造负对照） | 16/16 通过 |
| `BenchmarkRelayProtocolTest` / `TracingLlmClientTest` / `RelayLlmClientEndToEndTest` / `BenchmarkProviderEvidenceGateTest` | 29/29 通过 |

仓库当前恢复为可编译检查点；这不构成 E2 完成，也不构成任何模型证据。

## 6. 阶段 2 小规模实测（2026-09-07，用户授权）

范围严格按交接第 4 节：**一个开发题 × 两模型各一次 = 2 个真实 episode**，
单次生成、不自动复跑。选用 8 题中最轻的 `dev-code-retry-location`
（L1、权重 10、只读分析 + 落一个 JSON 答案），以最小消耗验证阶段 0 修复后的
真实链路：Docker relay（无网络、只读根、非 root Candidate）→ 宿主 relay
持真实 provider → 独立无网络 Docker verifier。

| 模型（服务端解析一致） | 结果 | LLM 调用 | input / output token | 工具调用 | Worker 耗时 |
|---|---|---:|---:|---:|---:|
| `deepseek-v4-flash` | SCORED_PASS，诊断 100 | 4 | 16,710 / 1,367（缓存 14,592） | 8，全部成功 | 10.1 s |
| `glm-5.3-flash` | SCORED_PASS，诊断 100 | 7 | 25,439 / 927（缓存 20,992） | 10，全部成功 | 42.5 s |

两集证据门禁全绿：resolved model 与请求一致、usage 完整、请求指纹完整、
1M context / 16384 output cap 满足（单次最大 input+output 分别 5,323 / 4,498）、
无 hard gate 违例；verifier 均 `PASSED`（Docker 沙箱、exit 0）。
`publishable=false`，原因字段如实标注 `subset diagnostic run`。

本轮身份：

| 项目 | 值 |
|---|---|
| 基础源码 | 阶段 0 修复后 dirty 工作区（含新 `TeamRequestAudit.java`，未被本 ReAct 路径使用） |
| Suite SHA-256 | `0603ecbf7162b84fbe574604786d5f6383473a0b8119ad4231f6a55ad464f4b5`（与 2026-09-04 冻结一致） |
| Candidate JAR | `2893f10c54af7897aff38e00b9dd7e57197e408f5c5f89a5c83e70c20d455225` |
| Runner JAR | `e311004efd416765a55914bc36d09859b768871ddd0255b2242ad3e69f3f2107` |
| Worker / Verifier image | 与 2026-09-04 相同（`742ecf…` / `770224…`） |
| Run ID / 私有产物 | `phase2-deepseek-code-retry-r1`、`phase2-glm-code-retry-r1`；输出根 `~/.paicli/benchmark-runs/phase2-2026-09-07`（0700，git 外） |
| 预算 | 单集 500k token / 80 轮 / 停滞 3 / 600 s；实际远未触及 |

边界：这是当前源码的单题子集冒烟 + 链路验证，不是正式分数、不是 8 题复测、
不能外推成功率；与 2026-09-04 的 8 题结果不可直接比较（Candidate jar 换代）。
被测模型 API 消耗即上表 token 数（复验不重跑）；代理/编码侧额度消耗单列，
两者不混算。

## 7. 阶段 3 子阶段 1：E2 请求证据的 relay 级验证（2026-09-07，真实模型调用 0）

交接文档点名缺失的 `TeamRelayProtocolTest` / `TeamRelayEvidenceTest` 已补齐，
覆盖此前只有审计类单元测试、没有 relay 端到端验证的缺口：

- `TeamRelayProtocolTest`（5 项）：TEAM 会话的精确绑定矩阵（缺审计、共享
  MCP 通道、PLAN 冲突均拒绝且零 I/O）；TEAM_EVENT→ACK 往返后请求按
  `team:<activationId>` 归因；未确认激活的请求在 Worker 本地拒绝；
  宿主预算拒绝保留完整 attempt 记录（dispatched=false、failureType 保留）；
  观察失败时只能以 `REQUEST_FINGERPRINT_UNPROVEN` 的 WorkerFailure 收尾，
  宿主审计同步标记失败。
- `TeamRelayEvidenceTest`（2 项）：完整原生 TEAM episode（planner→worker→reviewer）
  走真实帧，宿主证据封闭、attempts 与 provider 调用一一对应、私有请求体保留
  而 digest-only 事件不含明文；TracingLlmClient 输出 schema 2 TEAM 指纹且
  `completeFor` 为真。负对照：Worker 侧丢弃 `ActivationInputPrepared` 观察后，
  宿主以 `TeamRequestAudit.Failure` 拒绝该请求、provider 调用为 0、episode
  不能给出干净结论——漂移导致评测失效而不是给分。

本轮验证顺带钉死了三个既有 fail-closed 行为：`connect` 的短重载会自动创建
TEAM 审计（显式 9 参才可注入 null 做负对照）；Worker 未接观察失败计数
supplier 时第一次观察即失败；宿主对 `REQUEST_FINGERPRINT_UNPROVEN` 的
WorkerFailure 同步 `markFailed`。三者均由测试固化。

定向回归：`TeamRequestAuditTest`(16) + 两个新测试(7) + relay/协议/tracing/
证据门禁既有组(29) = 52 项全绿。

## 8. 阶段 3 子阶段 2：写入归属/冲突与整题预算收尾（2026-09-08，真实模型调用 0）

- **写入归属**：`TeamRequestAudit` 快照（schema 仍为 v1，team-audit.json 尚未在
  任何真实运行中产出，归属段自出生即属 v1）新增 `WriteAttribution`——每次
  `write_file` 从 provider 实际实参提取 path，按激活 scope/角色/步骤/attempt
  归属，`successful` 取自 post-policy 工具批次；路径不可解析时保留 null，
  原始实参仍可从 attempt 响应审计。**冲突观察**：同一 path 被多个不同激活
  成功写入时记录 `WriteConflict`（含双方激活身份），只作为证据不定罪——
  冲突如何计分由冻结的 E2 合同决定。
- **经真实帧的对照**（`TeamRelayEvidenceTest` 3 项）：单 worker 写 notes.txt
  正确归属且无冲突；两个并行 worker 写 shared.txt 时两条归属 + 一条冲突
  （双方身份保留），审计本身保持健康——冲突是产品行为证据，不是宿主故障。
- **预算收尾语义**（`TeamRelayProtocolTest` 6 项）：只有先观察到产品自己的
  `BudgetFinalization` 事件，宿主才在预算耗尽后放行一次无工具收尾调用；
  第二次收尾式调用被拒为 `EPISODE_BUDGET_EXHAUSTED`；三次请求的 attempt
  全部保留（第二次 delivered，第三次 dispatched=false 且 failureType 保留）。

定向回归合计 54 项全绿。E2 剩余缺口：独立 Python 验题、正式 recipe/逐题
合同接线与 Docker 正反控制（阶段 3 子阶段 3，需另行授权）。

## 9. 阶段 3 子阶段 3a：E2 独立 Python 计分（2026-09-09，真实模型调用 0）

- 新增 [`e2_team_verify.py`](../../src/main/resources/benchmark/e2_team_verify.py)
  （与 e1/d3/d4/f 系独立验题器同目录、同调用约定）：只读 `team-audit.json`
  （schema 1）+ 题目 workspace + 冻结 oracle，独立重建运行生命周期、从真实
  provider 请求与 post-policy 工具批次重推导写入归属（含参数指纹交叉核验），
  再与审计自身的归属/冲突段比对。**分层与既有通道一致**：证据自相矛盾或不完整
  → evaluation-invalid、无数值分；证据一致但行为不符合合同 → 有效 0 分。
- `E2IndependentVerificationTest` 4 项控制：正确 episode 满诊断 100（oracle 与
  证据 SHA-256 由验题器独立回算核对）；双 worker 写 shared.txt 的冲突 episode
  为有效 0 分且归属段仍一致；篡改归属段 path、删除 RunExited 事件均被判
  evaluation-invalid 且 `diagnosticScore=null`；原始 0600 快照在篡改控制中保持
  不动。矛盾证据只会得到"无效"，永远不会得到一个看起来合法的分数。
- 定向回归合计 58 项全绿。

## 10. 阶段 3 子阶段 3b：E2 真实 Docker 正反控制（2026-09-09，真实模型调用 0）

`E2DockerControlTest`（默认关闭，`-Dpaicli.test.e2.docker=true` 显式开启）：

- 脚本 provider 驱动**真实无网络 Docker Candidate Worker**（TEAM 模式、
  FILE_ONLY、非 root、只读根、资源限额；candidate jar `5ce1fc55…`、runner
  `9abfcfdb…`，均为当前源码重建，worker/verifier 镜像与 2026-09-04 冻结一致），
  再由**真实 Docker verifier** 内的 `e2_team_verify.py` 独立验题。
- 同一冻结 oracle（契约与验题程序均 0400、摘要前后复核）判两个控制：
  **correct 满诊断 100；conflict（两个并行 worker 写 shared.txt）有效 0 分**
  ——即使脚本把两个 worker 写同一文件，验题器给出的也是一个"行为错误的
  有效零分"，归属段与独立重推导保持一致，而不是证据无效。
- oracle、验题程序、evidence 的 SHA-256 在验题前后核对不变；每次控制的
  私有 provenance 写入 episode 内 `independent-replay.json`（0600，git 外，
  `~/.paicli/benchmark-runs/e2-docker-controls`）。
- 排障记录：首轮 0.19s 失败为 Docker daemon 未运行（启动 Docker Desktop 后
  复跑即过），与 Candidate/协议无关；frozen 契约跨次运行复用前强制字节
  一致校验，绝不覆写。

至此 E2 在开发诊断层面达成交接文档的停止点：**recipe（冻结 oracle+独立
验题）/执行（真实 Docker Worker）/验题（独立 Docker verifier + Python 计分）
正反控制闭环**。与 D3/D4/F4 的正式接线相比，仍缺 generator 正式 recipe/
v4 逐题合同注册（正式 24/28 计数不变，E2 不计入）；整套依旧
`NOT_INTEGRATED`、`formalScores=null`、`publishable=false`。

## 11. 阶段 3 子阶段 3c：E2 正式 recipe 注册（2026-09-09，真实模型调用 0）

generator catalog 将 E2 由 planned 转 implemented（TEAM + LOCAL_COMMAND、
L2 权重 4 不变）：

- 新增 `team/E2FrozenOracle`：严格 schema 的冻结私有契约，seeded fixture 为
  README + spec/rules.md + check.sh 三件套（tier 阈值表由 seed 确定）。
- 新增 `finalset/generator/E2CaseMaterializer`：确定性参考工作区 + 合成
  team-audit envelope（planner→CODE/TEST/DOC 三步含依赖、审阅与写入归属），
  manifest 明确标注 reference-is-synthetic-not-provider-evidence。
- `e2_team_verify.py` 升级：同时接受 v5 evidence envelope（内嵌 teamAudit）
  与裸 schema-1 审计；新增 `doc_consumes_dependencies`（DOC 步骤产物必须
  逐字包含依赖步骤 worker 的最终结果）与 `no_unrequested_files`（工作区
  清单 = 冻结 fixture ∪ 合同产物）两项检查。
- v4 逐题合同要求 `team_audit` + `scoped_request_fingerprints` 证据，
  limits 为 2400s / 300k token / 48 轮 / 停滞 8。
- **真实 `generate-incomplete` 全量跑通**：implementedRecipes=25/28
  （原权重合计 88/100），`finalReady=false` 保持 fail-closed；E2 contract
  编译成功，生成物 wrapper + 独立验题器对参考 envelope 打 100（8 项检查
  全真，含 oracle/evidence 摘要回算）。

新增 `E2GeneratedVerifierTest` 3 项；相关定向回归 99 项全绿（12 跳过为
既有平台限制与 opt-in 入口）。**24/28 → 25/28**；D5/E3/E4 仍未物化，
正式批次与生产准入继续关闭，`NOT_INTEGRATED` / `formalScores=null` /
`publishable=false` 不变。
