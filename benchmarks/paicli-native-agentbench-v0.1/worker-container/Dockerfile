FROM eclipse-temurin:21-jre-alpine@sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11

LABEL org.opencontainers.image.title="PaiCLI Native AgentBench Candidate Worker"
LABEL org.opencontainers.image.description="Networkless runtime for a read-only mounted PaiCLI Candidate jar"
LABEL com.paicli.agentbench.role="candidate-worker"
LABEL com.paicli.agentbench.scope="relay-worker-only"

USER root
RUN addgroup -S -g 10002 paicliworker \
    && adduser -S -D -H -u 10002 -G paicliworker -h /home/paicli -s /sbin/nologin paicliworker \
    && mkdir -p /workspace /home/paicli /opt/paicli \
    && chown 10002:10002 /workspace /home/paicli \
    && chmod 0555 /opt/paicli

ENV HOME=/home/paicli \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /workspace
USER paicliworker:paicliworker

ENTRYPOINT ["java"]
CMD ["-version"]
