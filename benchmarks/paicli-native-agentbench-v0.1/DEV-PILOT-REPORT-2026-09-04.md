# PaiCLI Native AgentBench 开发复测：2026-09-04

状态：真实模型调用已完成；验证器修复后对称复验完成；仅开发诊断，`publishable=false`。

## 结果

| PaiCLI 组合 | 服务端返回模型 | 冻结产物复验 | 开发诊断分 | 状态 |
|---|---|---:|---:|---|
| DeepSeek V4 Flash | `deepseek-v4-flash` | 8/8 PASS | 100 | 单次生成，验证器复验 |
| GLM-5.3-Flash | `glm-5.3-flash` | 8/8 PASS | 100 | 单次生成，验证器复验 |
| 混元 Hy4 preview | 未取得 | 未运行 | 无分数 | 缺少本地凭证 |

两家本轮都真实运行完整 `0.1-dev.2` 的 8 题，统一 ReAct / FILE_ONLY、每题 600 秒、1 次生成；不是挑题、best-of-N，也不是隐藏正式集分数。Candidate 使用 `DOCKER_RELAY` 无网络容器，验证器使用独立无网络容器。完整服务端模型、usage 和请求指纹门禁在两组原始 run 均为 true。

### 原始 89 分为何不能直接引用

两组原始 `aggregate.json` 均自动记录 7/8、89 分。唯一失败项都是 `dev-secret-safe-bundle`（权重 11）：验证脚本把只读快照 `cp -R` 到临时目录后，该副本仍不可写，`rm` 删除旧输出时报 Permission denied，尚未执行生成器的 allowlist 断言。

这不是已证实的 Candidate 失败，而是验证器准备阶段错误。旧 Runner 把退出码 1 统一计作做题失败，在此处误分类；原始 JSON 不改写，但该次原始分数在报告中标为**验证器故障，失效/被复验替代**，不能继续当有效 89 分使用。此处也不把原始 `infraErrorEpisodes=0` 误称为“没有任何评测基础设施问题”。

修复仅在验证器自建的临时副本上加 `chmod -R u+rwX`，随后仍对敏感文件施加 `chmod 000`。题目、权重、预期输出、敏感读取断言和所有 Candidate 产物均未更改。新增回归在修复前稳定复现错误；修复后正确生成器 PASS，读取敏感文件的错误生成器 FAIL，原始快照保持不变。

修复完成后，使用同一镜像对两家**全部 16 份原始冻结产物**重新执行验证：16/16 PASS。每份产物在复制前后及验证后核对原始 snapshot digest；复验没有再次调用 provider。除这道题的 verifier bundle 外，其余 7 道题的 bundle digest 完全不变。这是同一次模型生成的 verifier-only replay，不是第二次独立模型运行。

## 逐题结果（复验）

| Case | 权重 | DeepSeek | GLM |
|---|---:|---|---|
| dev-java-closed-range | 14 | PASS | PASS |
| dev-java-shared-normalizer | 16 | PASS | PASS |
| dev-python-order-summary | 13 | PASS | PASS |
| dev-node-event-report | 13 | PASS | PASS |
| dev-terminal-release-manifest | 12 | PASS | PASS |
| dev-code-retry-location | 10 | PASS | PASS |
| dev-safe-path-copy | 11 | PASS | PASS |
| dev-secret-safe-bundle | 11 | PASS | PASS |

GLM 此次 Node 报告产物通过；8 月 31 日同题失败的历史记录仍保留。两次使用的 PaiCLI 二进制、隔离链路和服务状态不同，不能仅凭两次观测归因为某项产品修复或宣称稳定提升。

## 本轮身份与资源

| 项目 | SHA-256 / 值 |
|---|---|
| 基础 commit | `c086e4d238046f653f281e45f121c9365a282da1`，dirty 工作区 |
| Candidate JAR | `79100733ba7cbe2d385d81a96007bd548a24c98694951d63815c94a999d542f3` |
| Trusted runner JAR | `d446b22aee302a20fc9783b9bfda9e9c48c9abc2ca3b7083d04523dff6fb0b58` |
| Suite JSON | `0603ecbf7162b84fbe574604786d5f6383473a0b8119ad4231f6a55ad464f4b5` |
| Worker image | `sha256:742ecfeab2543923dffe37aee61ae8b7e3490d88ad28b205f574a07b01754608` |
| Verifier image | `sha256:770224998e34bdd809f205a26d31bff62684b65e78751a3ab5c319d5791632e8` |
| 修复前 safe-bundle script | `cfc7605c8bec2d0b412b157c30eb818217de6b33703e4b2ef75b104e84d99736` |
| 修复后 safe-bundle script | `f1dac5d2652c1dc6b2fe2be9cd96fdee30f9908fde97d7fb95f685cb09c71260` |
| 私有 replay summary | `4dcc73b719b9e1e52d6e3f57ac9a603d65a2b656ad3381e48df33cf19f02c795` |

原始 run ID：`resume-deepseek-full-dev-r1`、`resume-glm-full-dev-r1`。原始会话、题目执行轨迹、完整答案与私有路径不放入公开摘要。允许公开的字段见 [机器摘要](dev-pilot-results-2026-09-04.json)。

| Provider usage（首次生成，复验不增加） | DeepSeek | GLM |
|---|---:|---:|
| 调用次数 | 39 | 42 |
| 输入 token | 170,326 | 171,327 |
| 输出 token | 21,138 | 18,244 |
| 缓存 token（输入子集） | 157,312 | 143,168 |
| 返回工具调用 | 63 | 68 |
| 累计 LLM 耗时 | 158.347 秒 | 512.948 秒 |
| 单次最大 input + output | 8,941 | 7,295 |

统一配置 1,000,000 context / 每次 16,384 output。实际题目远未触及 1M，不能据此声称完成满窗口压力验证。两家并发运行，宿主期间还执行本地测试；这里的耗时仅作原始观测，不作稳定速度排名。两家累计 81 次模型调用、381,035 input + output tokens，缓存不重复累加。

## 复现与验证边界

保留的 [一次性复验程序](replays/2026-09-04/VerifierReplay.java) 只接受两个原始 run 都完成的私有根目录，按完整 8 题顺序运行；它不会调用模型、修改旧产物或覆盖旧 run。用本轮 Candidate JAR 编译它，再传入私有根目录与本 suite 路径即可；输出为新的 `verifier-replay-v1`，已存在则拒绝覆盖。需要原始私有证据与相同镜像，不应公开 raw 来方便复现。

本轮验证：292 项 benchmark / provider 相关测试通过；常规 `mvn test -Pquick` 在 verifier 修复前通过，修复后另执行了完整 benchmark 定向组；构建、trusted runner 产物策略和 `git diff --check` 通过。没有提交或推送。

正式 28 题仍只有 15 题物化原型，未完成生产集成；Hy4 凭证、正式 preflight / 独立冻结 Worker image、剩余 13 题及三个模型各三次独立运行尚未齐备。当前结果不代表 SWE-bench / Terminal-Bench 官方成绩，不代表正式集、Plan/Team、终端执行或长程能力。两家的正式分数字段继续为 null，`publishable=false`。
