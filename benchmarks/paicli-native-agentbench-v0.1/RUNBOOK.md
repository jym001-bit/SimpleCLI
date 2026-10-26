# PaiCLI Native AgentBench v0.1 运行手册

## 先读结论

最新完成批次见 [2026-09-04 开发复测报告](DEV-PILOT-REPORT-2026-09-04.md)：DeepSeek / GLM 完整 Docker relay 运行及全部冻结产物复验已完成。下文“两个宿主完整批次/后续单题 smoke”是 8 月 31 日历史边界，不是最新批次。新一轮仍非正式榜单。

当前仓库已经落地可执行的 **dev-suite 顺序 Runner**：`BenchmarkCoordinatorMain` 会为每个 case / repeat 创建独立 workspace、独立 user home 和独立 Worker JVM，再调用位于 workspace 之外的确定性 verifier。它可以生成可复现的开发诊断证据，但当前结果统一属于 **dev pilot**，不能冒充 SWE-bench、Terminal-Bench、MCP benchmark 等外部评测的官方成绩，也不能作为 PaiCLI Native AgentBench 的正式公开分数。开发集 verifier 本身是公开资产，不得称为隐藏测试。

无论是单题还是完整 8 题，当前 `publishable` 都应为 `false`。主要边界是：

- 两个完整 8 题横评批次使用宿主 Worker，Docker 只隔离 verifier；后续 DeepSeek / GLM 单题已通过 `DOCKER_RELAY` 验证 Candidate 容器链路，但它们是 subset smoke，且使用临时离线派生 Worker image，不能反推完整批次已经全进程隔离；
- 两个完整 8 题横评批次只记录请求模型 ID，服务端 resolved model 为 `UNAVAILABLE`，usage 完整性门禁也未闭合；后续两次单题 relay smoke 已取得匹配的 resolved model、完整 usage 和请求指纹，但不能替代三个模型完整批次的身份与 usage 证据；
- 28 题 final 数据集、隐藏 verifier、顺序、预算与 digest 链尚未完成正式冻结；
- 正式协议所需的三次独立重复是后续规则；现在即使传入 `--repeats 3`，产物仍只是 dev diagnostic。

开发试跑也遵守以下底线：

- 同一批次的候选横评应使用同一 PaiCLI commit、同一 `dev-suite.json`、工具配置、超时与 verifier 镜像；修复前后诊断若改变二进制或镜像，必须保留旧 run 并明确标成开发修复对照；
- 单题 `--case` 运行只能称为 **subset diagnostic**，不能称为总分或完整榜单；
- 完整 8 题运行必须省略 `--case`，不得删除失败题、修改既定权重或只展示最好结果；
- 不使用 best-of-3；产品或 adapter 修复后保留旧证据，用新 run ID 重跑同一套件。

### Run evidence v3

当前 manifest / aggregate schema 为 v3，记录每次输出上限、服务端观测到的单次最大
input + output tokens、输出策略、请求指纹与完整 provider evidence 门禁。
`fullProviderEvidenceGate` 表示没有发现使评测失效的 provider 证据缺陷，不表示做题成功；
`NO_PROVIDER_CALL`、普通 API/adapter 错误和 Candidate 输入预算超限仍保留有效失败与 0 分。
零成功调用时 resolved model / usage 可以显示不可用，不能据此捏造模型身份。
episode outcome 另外保留有界 `failureType`，防止早期缺失 usage 等错误被后续正常 metrics 掩盖。
这些字段不改变 dev-pilot 的 `publishable=false`。

Provider 门禁通过不代表 verifier 自身没有缺陷。9 月 4 日发现的 safe-bundle 只读副本权限错误会以普通退出码 1 被旧 Runner 误计 0 分；发现这类有复现证据的 setup 故障时，保留原始结果并标注原分数失效，修复后对各模型全部冻结产物做对称 verifier-only replay。不得把普通断言失败仅凭低分改标 infra，也不得修改候选产物后冒充原样复验。

## 1. 候选组合

| 展示名 | PaiCLI provider | Runner 锁定的请求模型 ID | 凭证变量 |
|---|---|---|---|
| DeepSeek V4 Flash | `deepseek` | `deepseek-v4-flash` | `DEEPSEEK_API_KEY` |
| 混元 Hy4 preview | `hunyuan` | `hy4-preview` | `HUNYUAN_API_KEY` |
| GLM-5.3-Flash | `glm` | `glm-5.3-flash` | `GLM_API_KEY` |

Coordinator 会拒绝 provider 与模型 ID 不匹配的组合。展示名不能替代真实 API 证据；两个完整 8 题历史 manifest 虽写入 `requestedModelLocked=true`，但 `serverResolvedModel=UNAVAILABLE`，因此不能据此宣称完整横评的服务端模型身份已经验证。后续单题 relay smoke 只证明该次调用链的 resolved model / usage 门禁，不把证据外推到旧批次。

没有某个 provider 的凭证时，不运行、不补写也不推断该模型的分数。尤其不能用另一个模型或兼容接口的结果代替混元 Hy4 preview。

## 2. 环境、构建与密钥

### 2.1 基础环境

- Java 17 或更高版本；
- Maven；
- Docker Desktop / Docker Engine，用于当前推荐的 networkless、read-only verifier；
- fixture verifier 所需的 Java、Python 3、Node.js 和 Bash，由冻结的 verifier 镜像提供。

先记录本轮代码和构建身份：

```bash
java -version
mvn -version
git rev-parse HEAD
git status --short
git diff --check

mvn -q -DskipTests package
shasum -a 256 target/paicli-1.0-SNAPSHOT.jar
```

测试通过、jar 生成成功与真实模型 episode 是三类不同证据，报告中必须分开说明。dirty run 可以用于调查，但必须如实记录，不能与未来 clean final 批次混在一起。

### 2.2 密钥只保存在本地 `.env`

```bash
cp .env.example .env
chmod 600 .env
git check-ignore -v .env
```

只在 `.env` 中填写真实值，不要把 key 复制进命令行、聊天、终端录屏、CI 参数或报告：

```dotenv
DEEPSEEK_API_KEY=<local-secret>
DEEPSEEK_MODEL=deepseek-v4-flash

HUNYUAN_API_KEY=<local-secret>
HUNYUAN_MODEL=hy4-preview
HUNYUAN_BASE_URL=https://tokenhub.tencentmaas.com/v1

GLM_API_KEY=<local-secret>
GLM_MODEL=glm-5.3-flash
```

真实凭证的传递路径固定为：

```text
仓库根目录 .env
  -> Coordinator 内的 PaiCliConfig
  -> 单 episode 的严格 WorkerRequest envelope
  -> Worker JVM stdin（一次性 JSON 协议）
  -> provider client 内存
```

key 不进入 Worker 的 argv 或环境变量。Worker 启动前会清空继承环境，只重建固定 `PATH`、隔离的 `HOME` / `TMPDIR`、UTC 与 locale。Coordinator 和 Worker 都会执行凭证 canary 检查；若 key 出现在 stdout、stderr、answer、verifier 输出或 episode 文件树，case 触发安全 hard gate，并尝试清理该 episode 的私有内容。

这条传递链降低了意外泄漏风险，但不能替代尚未完成的 Worker 全进程隔离，因此当前仍仅限 dev pilot。

## 3. 已落地 Runner 的真实入口

Runner 入口不是交互式 PaiCLI 命令，而是 jar 中的 Coordinator 主类：

```bash
java -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.eval.benchmark.BenchmarkCoordinatorMain \
  --help
```

当前真实参数形状为：

```text
--suite <suite.json>
--provider <deepseek|hunyuan|glm>
--model <locked-model-id>
[--tool-profile <REASONING_ONLY|READ_ONLY|FILE_ONLY|LOCAL_COMMAND>]
[--verifier-isolation <SEATBELT|DOCKER>]
[--docker-verifier-image sha256:<64-lowercase-hex>]
[--docker-executable /absolute/path/to/docker]
[--repeats N]
[--output DIR]
[--run-id ID]
[--case ID[,ID...]]
[--timeout-seconds N]
```

本手册只使用公开开发套件：

```text
benchmarks/paicli-native-agentbench-v0.1/dev-suite.json
```

它包含 8 个 active ReAct case。Coordinator / Worker 已按 manifest 原样分发 `react`、`plan`、`team`，不会静默降级模式；当前公开 dev suite 仍只有 ReAct。Plan / Team 的 DAG、并发时序和角色归属证据尚未进入 verifier envelope，因此模式入口已打通不等于 E1 / E2 已达到正式可发布状态。

### 3.1 显式任务 envelope

每个 episode 都由 Coordinator 构造一份严格 envelope，字段包括：协议版本、provider、锁定模型 ID、受控 base URL、API key、manifest 原始 mode、工具 profile、套件中的原始 prompt，以及本轮绝对 `workspace`、`home`、`episodeDirectory`。Worker 对未知字段、重复字段、尾随 JSON、相对路径和路径不一致 fail closed。

Worker 通过对应模式的可信 `runExplicitTask(...)` 执行套件 prompt，并额外注入冻结的 Benchmark Tool Profile。它不是普通自由聊天，也不会复用上一个 case 的会话、workspace、home 或长期记忆。

### 3.2 默认工具面：`FILE_ONLY`

未提供 `--tool-profile` 时默认是 `FILE_ONLY`。为了让证据自描述，命令模板仍显式写出它。可用工具只有：

```text
read_file
write_file
list_dir
glob_files
grep_code
create_project
```

`REASONING_ONLY` 不暴露任何工具；`READ_ONLY` 只开放 `read_file` / `list_dir` / `glob_files` / `grep_code`，写工具在 exposure 和 execution 两层拒绝。`execute_command` 不会在上述 profile 或 `FILE_ONLY` 中暴露。`LOCAL_COMMAND` 会在文件工具面上额外开放 `execute_command`，但要求宿主命令沙箱可用；当前 macOS Seatbelt 路径只适合 fail-closed 开发调查，不能作为正式发布隔离证据。本轮 8 题统一使用 `FILE_ONLY`，候选代码的编译和断言由 Docker verifier 执行。

## 4. 构建并固定 Docker verifier 镜像

Docker verifier 只接受本地不可变 image ID，格式必须精确匹配 `sha256:` 加 64 位小写十六进制；tag、短 ID 和 `repository@sha256:...` 都不会被 Runner 接受。

在仓库根目录构建镜像，并让 Docker 把准确 image ID 写入私有临时文件：

```bash
CONTAINER_DIR=benchmarks/paicli-native-agentbench-v0.1/container
DOCKER_IID_FILE=/private/tmp/paicli-agentbench-verifier.iid

docker build \
  --pull \
  --no-cache \
  --platform linux/arm64 \
  --iidfile "${DOCKER_IID_FILE}" \
  --tag paicli-agentbench-verifier:v0.1-dev \
  --file "${CONTAINER_DIR}/Dockerfile" \
  "${CONTAINER_DIR}"

VERIFIER_IMAGE_ID="$(tr -d '\r\n' < "${DOCKER_IID_FILE}")"
printf '%s\n' "${VERIFIER_IMAGE_ID}" | grep -Eq '^sha256:[0-9a-f]{64}$'
docker image inspect --format '{{.Id}} {{.Architecture}}/{{.Os}}' "${VERIFIER_IMAGE_ID}"
```

不要把 `paicli-agentbench-verifier:v0.1-dev` 这样的可变 tag 传给 `--docker-verifier-image`。不同 CPU 架构、完整 image ID 或运行时包版本形成不同环境身份，不能混进同一批次。Verifier-only 镜像不包含 Candidate jar；PaiCLI commit 与 jar SHA-256 必须作为独立证据字段记录。

当前 Docker verifier 使用 `--network none`、只读根文件系统、只读 `/suite` 与 `/workspace` 挂载、drop capabilities、`no-new-privileges`、PID / 内存 / CPU / fd 限额和私有 tmpfs。它保护的是 verifier 执行，不代表宿主侧 Worker 已经容器化。

## 5. 私有 artifact 根

输出必须是仓库和 suite 目录之外的绝对路径。推荐在 `/private/tmp` 下创建 owner-only 根目录：

```bash
ARTIFACT_ROOT="$(mktemp -d /private/tmp/paicli-agentbench-private.XXXXXX)"
chmod 700 "${ARTIFACT_ROOT}"
printf '%s\n' "${ARTIFACT_ROOT}"

COORDINATOR_HOME="$(mktemp -d /private/tmp/paicli-agentbench-coordinator.XXXXXX)"
chmod 700 "${COORDINATOR_HOME}"
```

不要使用仓库内的 `benchmarks/results`，也不要把私有证据提交到 Git。默认的 `~/.paicli-benchmark-results` 同样必须保持仅当前用户可访问。

命令模板使用一个新的 `COORDINATOR_HOME`，避免已有 `~/.paicli/config.json` 或用户级 `~/.env` 覆盖仓库内的冻结配置。进程环境变量的优先级仍高于 `.env`；正式预检要在不回显值的前提下确认或清除同名 provider 环境变量。

## 6. 单题 probe：只能称为 subset diagnostic

单题用于验证 provider、tool-call、Worker、artifact 与 Docker verifier 链路。例如：

```bash
java -Duser.home="${COORDINATOR_HOME}" -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.eval.benchmark.BenchmarkCoordinatorMain \
  --suite benchmarks/paicli-native-agentbench-v0.1/dev-suite.json \
  --provider deepseek \
  --model deepseek-v4-flash \
  --tool-profile FILE_ONLY \
  --verifier-isolation DOCKER \
  --docker-verifier-image "${VERIFIER_IMAGE_ID}" \
  --docker-executable /usr/local/bin/docker \
  --repeats 1 \
  --case dev-code-retry-location \
  --timeout-seconds 600 \
  --output "${ARTIFACT_ROOT}" \
  --run-id probe-deepseek-retry-r1
```

因为使用了 `--case`，manifest 和 aggregate 会记录 `subset=true`，这次运行只能报告为“单题 subset diagnostic 通过 / 失败”。`subsetDiagnosticScore` 不能写成套件总分、模型总分或公开榜单分数。

## 7. 完整 8 题 dev pilot

完整开发套件必须省略 `--case`。当前建议每个模型先运行一次；三个命令除了 provider、model 和 run ID 外保持一致，且都不在命令行包含 key。

### 7.1 DeepSeek V4 Flash

```bash
java -Duser.home="${COORDINATOR_HOME}" -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.eval.benchmark.BenchmarkCoordinatorMain \
  --suite benchmarks/paicli-native-agentbench-v0.1/dev-suite.json \
  --provider deepseek \
  --model deepseek-v4-flash \
  --tool-profile FILE_ONLY \
  --verifier-isolation DOCKER \
  --docker-verifier-image "${VERIFIER_IMAGE_ID}" \
  --docker-executable /usr/local/bin/docker \
  --repeats 1 \
  --timeout-seconds 600 \
  --output "${ARTIFACT_ROOT}" \
  --run-id dev8-deepseek-r1
```

### 7.2 混元 Hy4 preview

```bash
java -Duser.home="${COORDINATOR_HOME}" -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.eval.benchmark.BenchmarkCoordinatorMain \
  --suite benchmarks/paicli-native-agentbench-v0.1/dev-suite.json \
  --provider hunyuan \
  --model hy4-preview \
  --tool-profile FILE_ONLY \
  --verifier-isolation DOCKER \
  --docker-verifier-image "${VERIFIER_IMAGE_ID}" \
  --docker-executable /usr/local/bin/docker \
  --repeats 1 \
  --timeout-seconds 600 \
  --output "${ARTIFACT_ROOT}" \
  --run-id dev8-hunyuan-r1
```

缺少 `HUNYUAN_API_KEY` 时 Coordinator 会在创建 episode 前失败。此时应记录“未运行：缺少凭证”，不能填 0 分、沿用其他 provider 分数或估算混元成绩。

### 7.3 GLM-5.3-Flash

```bash
java -Duser.home="${COORDINATOR_HOME}" -cp target/paicli-1.0-SNAPSHOT.jar \
  com.paicli.eval.benchmark.BenchmarkCoordinatorMain \
  --suite benchmarks/paicli-native-agentbench-v0.1/dev-suite.json \
  --provider glm \
  --model glm-5.3-flash \
  --tool-profile FILE_ONLY \
  --verifier-isolation DOCKER \
  --docker-verifier-image "${VERIFIER_IMAGE_ID}" \
  --docker-executable /usr/local/bin/docker \
  --repeats 1 \
  --timeout-seconds 600 \
  --output "${ARTIFACT_ROOT}" \
  --run-id dev8-glm-r1
```

Coordinator 的 suite preflight 会先验证 active fixture、verifier 类型和路径安全；这不是模型服务端身份 preflight。普通聊天成功或单题通过也不能替代后续正式协议所需的 SSE、usage、reasoning history、重试和 resolved model 验证。

## 8. 实际私有证据结构

每个 run 的当前结构如下；`<model-alias>` 是 provider、model 与短 hash 组成的安全目录名：

```text
<artifact-root>/<run-id>/
├── manifest.json
├── aggregate.json
└── cases/
    └── <case-id>/
        └── models/
            └── <model-alias>/
                └── repeat-001/
                    ├── run.json
                    ├── verifier.json
                    ├── answer.md
                    ├── llm-trace.jsonl
                    ├── conversation/
                    │   └── raw/
                    │       └── benchmark-episode.jsonl
                    ├── workspace/
                    │   ├── <copied-fixture-and-agent-output>
                    │   └── .paicli-benchmark-tmp/
                    ├── home/
                    └── verifier-docker-tmp/
```

目录项是否为空、以及安全 hard gate 后是否仍存在，取决于 episode 结果。`conversation/raw/benchmark-episode.jsonl`、workspace、answer 和 LLM 轨迹都属于 owner-only 私有取证材料；其中 conversation ledger 可能包含完整 prompt、reasoning、工具参数和结果，绝不能直接公开。`llm-trace.jsonl` 只记录最小化调用元数据，但仍应随整个 run 保持私有，发布前再次脱敏审查。

需要重点检查：

- `manifest.json`：suite hash、provider/model lock、tool profile、verifier image ID、选题、预算、环境指纹与隔离边界；
- `aggregate.json`：完整 dev 诊断值、覆盖率、hard gate、`subset` 与 `publishabilityReason`；
- `run.json`：单 episode 状态、Worker / verifier 时延、token 与工具调用统计；
- `verifier.json`：容器内实际 argv、exit code、截断标记与 verifier policy fingerprint；
- `answer.md`、conversation 和 workspace：仅用于私有失败归因，不能挑选性公开成功样本。

当前完整 8 题、无 infra error 的运行仍只读取 `diagnosticMeanWeightedScore`。正式 `meanWeightedScore` 等发布字段保持 `null`，控制台应显示 `Publishable: false`。

## 9. 失败、基础设施错误与修复重跑

下列情况属于有效失败并记 0，不能借 `infra_invalid` 擦除：

- Agent 达到固定超时、迭代或 token 预算；
- 候选代码或 Agent 行为导致 verifier 超时 / 失败；
- 错误补丁、错误答案、无效工具参数或 adapter 的确定性协议错误；
- 模型返回确定性 `400` / `401`；
- 安全 hard gate；
- verifier 启动后返回非零，或 verifier 执行发生普通 I/O 错误。

仅允许把 Runner 子进程无法启动、明确的 verifier sandbox unavailable、冻结基础设施不可用，或既定有限重试后仍确认的 provider 临时故障分类为基础设施错误。处理规则是：

1. 保留原 run，不覆盖、不删除；
2. 记录原始 failure class 与证据；
3. 使用新 run ID 补跑相同 case / 模型 / repeat；
4. 共性 PaiCLI 或 adapter 修复后，用同一套件和权重重跑所有候选组合；
5. 报告旧结果、修复 commit 和新结果，不只展示修复后的最好分数。

## 10. 正式协议是未来工作，不是当前命令

当前不要运行或发布所谓 final 命令。正式阶段至少要先完成：

- 28 题 final fixture、隐藏 verifier、mock、权重和 case 顺序在看分前冻结；
- PaiCLI commit、jar、suite、prompt、ToolRegistry、adapter 与 verifier image 的完整 digest 链；
- Worker 的可证明全进程 / OS 隔离，以及 provider transport 与 Agent 子进程的网络用途隔离；
- trusted verifier 与候选 runtime 的隔离，保证模型 / Agent 无法读取隐藏 oracle；
- 服务端 resolved model 身份与 usage 完整性门禁；
- 预注册的 `infra_invalid` 白名单、对称补跑规则和 100% 有效覆盖率；
- 三个模型相同预算、相同顺序、相同工具面和盲化报告流程。

这些门槛完成后，正式协议才会对每个 final case、每个模型运行 **3 次独立 episode**，报告算术平均值、总体标准差和逐题稳定性。绝不使用 best-of-3。任何 `--repeats 3` 的当前 dev-suite 运行都仍是开发稳定性诊断，不能提前称作正式三次评测。

未来发布还必须完整展示 L1 / L2 / L3、七个类别、全部失败 case、hard gate、token / 成本 / 时间 / 工具调用统计和所有已完成模型；final 看分后不得删题、改权重或降低门槛。报告格式使用同目录的 `REPORT-TEMPLATE.md`。
