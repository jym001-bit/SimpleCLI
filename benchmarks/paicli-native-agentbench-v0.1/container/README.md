# PaiCLI Native AgentBench verifier 镜像

## 当前定位

本目录只定义 **dev-pilot verifier** 的固定运行时。`DockerBenchmarkVerifier` 用该镜像在无网络、只读挂载和资源受限的容器里执行确定性 verifier；Candidate Worker JVM 仍在宿主机运行。

因此，这个镜像：

- 是 verifier-only 镜像，不是 Worker 镜像；
- 不包含 PaiCLI jar、Candidate Agent、API key 或模型调用逻辑；
- 只能支撑开发诊断，不能证明 Worker 已完成容器/VM 隔离，也不能单独支撑正式榜单分数。

把 Candidate jar 从镜像中移除是有意设计：修复或重建 PaiCLI 时，verifier 镜像身份不应随 Candidate jar digest 漂移。Candidate commit、jar SHA-256、suite SHA-256 和 verifier image ID 应作为彼此独立的证据字段记录。

## 镜像内容

镜像包含公开开发集 verifier 所需的最小通用运行时：

- Java 21 JDK（`java` / `javac`）；
- Bash；
- Python 3；
- Node.js；
- UID/GID `10001:10001` 的非 root 默认用户 `agentbench`；
- `/workspace` 与 `/suite` 两个只读挂载目标；
- `/opt/agentbench/runtime-packages.txt` 中记录的 APK 包版本。

基础镜像按 digest 固定：

```text
eclipse-temurin:21-jre-alpine@sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11
```

基础层提供 Temurin 21 JRE，Dockerfile 另外安装 Alpine 的 `openjdk21-jdk`，并把 `JAVA_HOME` 固定到 `/usr/lib/jvm/java-21-openjdk`。APK 仓库解析结果仍可能随构建时间变化，所以冻结身份必须使用构建后的完整 image ID，不能只记录 tag 或基础镜像 digest。

## 构建并冻结 image ID

在仓库根目录执行。构建上下文限定为本 `container/` 目录；Dockerfile 不读取仓库根、`target/`、`.env`、Git 历史或 Candidate jar：

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

当前开发机示例使用 `linux/arm64`。改用 `linux/amd64` 会形成新的 verifier 环境身份；不同架构、image ID 或运行时包集合不能混入同一批次。

`DockerBenchmarkVerifier` 只接受本地不可变 ID，格式必须是 `sha256:` 加 64 位小写十六进制。可变 tag、短 ID 和 `repository@sha256:...` 都不能传给 `--docker-verifier-image`。

建议随每批结果记录：

- verifier image ID 与架构；
- 固定基础镜像 digest；
- `runtime-packages.txt` 内容；
- Docker Engine 版本；
- 独立的 Candidate commit / jar SHA-256；
- 独立的 suite SHA-256。

## 只读 smoke

下面的 smoke 只检查 verifier 运行时，不运行 Agent，也不接触 API key：

```bash
docker run --rm \
  --pull=never \
  --read-only \
  --network none \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --entrypoint /bin/bash \
  "${VERIFIER_IMAGE_ID}" \
  -lc 'java -version && javac -version && python3 --version && node --version && id && cat /opt/agentbench/runtime-packages.txt'
```

真实评测不应手写另一套 `docker run` 参数。`DockerBenchmarkVerifier` 负责生成冻结执行边界，包括：

- `--pull=never` 与不可变 image ID；
- `--network none`、只读根文件系统；
- 只读 `/suite` 与 `/workspace` bind mount；
- `cap-drop ALL`、`no-new-privileges`；
- PID、内存、CPU、文件描述符上限和私有 tmpfs；
- 使用宿主 episode workspace 的非 root UID/GID 执行 verifier；
- 每次验证结束后的容器清理。

verifier 只读取 Candidate 产生的 workspace，并执行 suite 内的公开开发集验证脚本。它不接收 provider 凭据，也不需要网络。

## 尚未实现：Worker 镜像与全 Worker 隔离

不要用本 Dockerfile 启动 `BenchmarkWorkerMain`，也不要向镜像临时复制 PaiCLI jar 后把它称为冻结 Worker。完整 Worker 容器/VM 隔离仍是后续工作，至少需要另行解决：

- Candidate jar 与 Worker launcher 的独立冻结、签名和证据链；
- 只允许 LLM transport 访问指定 provider endpoint，同时禁止 Agent 工具和子进程任意联网；
- Linux 下 `execute_command` 的 fail-closed 沙箱与 canary；
- 每个 case / repeat / model 独立 workspace、home、进程树和清理证明；
- API key 仅通过受控凭据通道进入 LLM transport，且不进入 argv、环境、文件、日志或 Candidate 可读空间；
- 服务端 resolved model、完整 usage 与正式 28 题冻结证据。

在这些边界被实现和验证前，当前 Runner 与本镜像都只应表述为 dev pilot。
