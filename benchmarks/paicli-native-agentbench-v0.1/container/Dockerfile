FROM eclipse-temurin:21-jre-alpine@sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11

LABEL org.opencontainers.image.title="PaiCLI Native AgentBench verifier"
LABEL org.opencontainers.image.description="Pinned, networkless dev-pilot runtime for deterministic verifier scripts"
LABEL org.opencontainers.image.base.name="eclipse-temurin:21-jre-alpine"
LABEL org.opencontainers.image.base.digest="sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11"
LABEL com.paicli.agentbench.scope="dev-pilot-verifier-only"
LABEL com.paicli.agentbench.role="verifier"

USER root

RUN apk add --no-cache \
        bash \
        ca-certificates \
        nodejs \
        openjdk21-jdk \
        python3 \
    && update-ca-certificates \
    && addgroup -S -g 10001 agentbench \
    && adduser -S -D -H -u 10001 -G agentbench -h /home/agentbench -s /bin/bash agentbench \
    && mkdir -p /workspace /suite /home/agentbench /opt/agentbench \
    && chown -R 10001:10001 /home/agentbench \
    && apk info -e -v bash ca-certificates nodejs openjdk21-jdk python3 \
        | sort > /opt/agentbench/runtime-packages.txt \
    && chmod 0444 /opt/agentbench/runtime-packages.txt \
    && chmod 0555 /opt/agentbench

ENV HOME=/home/agentbench \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

WORKDIR /suite
USER agentbench:agentbench

ENTRYPOINT ["/usr/bin/java"]
CMD ["-version"]
