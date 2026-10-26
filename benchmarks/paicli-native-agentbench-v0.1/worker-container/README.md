# PaiCLI Candidate Worker 镜像

本目录定义独立于 verifier 的 Candidate Worker 运行时。镜像不包含 PaiCLI jar、评测集、verifier、API key 或 provider endpoint；运行时由宿主分别只读挂载可信 thin runner jar 与本次 Candidate fat jar，并通过 framed stdio relay 调用宿主侧真实 provider。

## 隔离边界

- Candidate 容器固定 `--network none`，模型调用只能经 stdin/stdout relay；
- API key、Base URL、宿主 home、episode、suite 和 verifier 不进入容器参数、环境或挂载；
- 唯一可写宿主挂载是本 episode 的 `/workspace`；trusted runner jar 与 Candidate jar 分别只读挂载；
- 根文件系统只读，`/tmp` 与 `/home/paicli` 使用有界私有 tmpfs；
- 使用非 root UID/GID、`cap-drop ALL`、`no-new-privileges`、PID/内存/CPU/文件限制；
- `REASONING_ONLY`、`READ_ONLY`、`FILE_ONLY`、`LOCAL_COMMAND` 都在相同的 networkless、只读 root、资源限额容器边界内运行；
- ReAct、Plan、Team 共用 `RelayLlmClient`，stdout 只允许协议帧。

## 构建与冻结

先从同一源码树生成两个职责不同的产物：

```bash
mvn -DskipTests package

RUNNER_JAR=target/paicli-1.0-SNAPSHOT-agentbench-runner.jar
CANDIDATE_JAR=target/paicli-1.0-SNAPSHOT.jar

java -cp target/classes \
  com.paicli.eval.benchmark.BenchmarkRunnerArtifactPolicy \
  "${RUNNER_JAR}"

shasum -a 256 "${RUNNER_JAR}"
shasum -a 256 "${CANDIDATE_JAR}"
```

`agentbench-runner` classifier 只包含 `BenchmarkRelayWorkerMain*`、`BenchmarkToolRegistry*`、`BenchmarkToolProfile*` 与 `eval/benchmark/relay/**`，Manifest 入口固定为 `BenchmarkRelayWorkerMain`；它不包含 Agent、LLM provider 或产品 `ToolRegistry` 实现。产物策略会拒绝缺少入口、重复/路径逃逸条目、额外产品类或 provider 类，并输出一个不受 ZIP 条目顺序与时间戳影响的内容清单 SHA-256。独立单测无需先执行 package：

```bash
mvn -DskipTests=false -Dtest=BenchmarkRunnerArtifactPolicyTest test
```

容器内的目标启动顺序必须是 trusted runner 在前、Candidate 在后，避免 Candidate fat jar 中的同名 runner 类抢先加载；Agent、`LlmClient` 接口及第三方依赖仍由 Candidate fat jar 提供：

```bash
java -cp /opt/paicli/agentbench-runner.jar:/opt/paicli/candidate.jar \
  com.paicli.eval.benchmark.BenchmarkRelayWorkerMain
```

镜像构建上下文只包含本目录，不能复制仓库或 Candidate jar：

```bash
WORKER_CONTAINER_DIR=benchmarks/paicli-native-agentbench-v0.1/worker-container
WORKER_IID_FILE=/private/tmp/paicli-agentbench-worker.iid

docker build \
  --pull \
  --no-cache \
  --platform linux/arm64 \
  --iidfile "${WORKER_IID_FILE}" \
  --tag paicli-agentbench-worker:v0.1-dev \
  --file "${WORKER_CONTAINER_DIR}/Dockerfile" \
  "${WORKER_CONTAINER_DIR}"

WORKER_IMAGE_ID="$(tr -d '\r\n' < "${WORKER_IID_FILE}")"
printf '%s\n' "${WORKER_IMAGE_ID}" | grep -Eq '^sha256:[0-9a-f]{64}$'
```

正式证据必须分别记录 Worker image ID、trusted runner jar SHA-256 与 Candidate jar SHA-256。三者不能合并为一个身份：runner 锁住可信容器入口，Candidate 表示被测 PaiCLI，Worker image 表示网络与进程隔离运行时。verifier 继续使用 `container/` 下的独立镜像，不能复用本镜像。

Coordinator 的 Docker relay 路径必须显式传入两个绝对、regular、非 symlink 的 JAR：

```bash
java -cp "${CANDIDATE_JAR}" \
  com.paicli.eval.benchmark.BenchmarkCoordinatorMain \
  --suite /absolute/path/to/suite.json \
  --provider glm \
  --model glm-5.3-flash \
  --worker-isolation DOCKER_RELAY \
  --docker-worker-image "${WORKER_IMAGE_ID}" \
  --candidate-jar "$(pwd)/${CANDIDATE_JAR}" \
  --runner-jar "$(pwd)/${RUNNER_JAR}"
```

Coordinator 在启动时固定 Candidate JAR SHA-256、runner JAR SHA-256 和 runner 内容清单 SHA-256；每个 episode 再把两个原始 JAR 分别以 `NOFOLLOW` 方式复制到 owner-only 私有目录，复核启动时哈希，并重新执行 runner 产物策略。容器 classpath 固定 runner 在前、Candidate 在后；manifest 与 episode run 只记录这些哈希，不记录宿主 JAR 路径。`HOST_DEV` 会拒绝 `--runner-jar`，防止把两条信任边界混用。

旧的 host dev-pilot 路径保持不变；接入可信 runner 不会自行把 `publishable` 改为 `true`。
