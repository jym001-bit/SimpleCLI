package com.paicli.eval.benchmark.finalset.generator;

import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Isolated reference-report prototypes for the three terminal-execution recipes.
 *
 * <p>The generated verifier consumes only trusted {@code toolExecutions} for command evidence.
 * Candidate answer text never has authority over command success, process state, or provenance.
 * These sources remain fail-closed in the generation manifest until the frozen Worker toolchains
 * and stateful process/command evidence are integrated.</p>
 */
final class TerminalCaseMaterializers {
    private static final String WRAPPER = """
            #!/bin/sh
            set -eu
            if [ "$#" -ne 2 ]; then
              exit 2
            fi
            validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
            exec python3 "$validator_root/_private/terminal_verify.py" "%s" "$1" "$2"
            """;

    private TerminalCaseMaterializers() {
    }

    static boolean supports(String caseId) {
        return List.of("C1", "C2", "C3").contains(caseId);
    }

    static String wrapper(String caseId) {
        if (!supports(caseId)) {
            throw new IllegalArgumentException("not a terminal prototype: " + caseId);
        }
        return WRAPPER.formatted(caseId);
    }

    static void writeSharedVerifierRuntime(PrivateSourceWriter writer) throws IOException {
        writer.text("validators/final/_private/terminal_verify.py", TERMINAL_VERIFIER_RUNTIME);
    }

    static List<String> toolchainFiles() {
        return List.of("validators/final/_private/terminal_verify.py");
    }

    static void materialize(PrivateSourceWriter writer,
                            FinalSourceRecipeCatalog.Recipe recipe,
                            SeededVariant variant) throws IOException {
        switch (recipe.id()) {
            case "C1" -> materializeC1(writer, variant);
            case "C2" -> materializeC2(writer, variant);
            case "C3" -> materializeC3(writer, variant);
            default -> throw new IllegalArgumentException(
                    "no terminal materializer registered for " + recipe.id());
        }
    }

    static ScoringContract scoringContract(String caseId,
                                            String verifierSha256,
                                            String toolchainSha256) {
        List<ScoringContract.AssertionRule> assertions = new ArrayList<>();
        List<ScoringContract.HardGateRule> hardGates = new ArrayList<>();
        List<ScoringContract.ComponentRule> components = new ArrayList<>();
        switch (caseId) {
            case "C1" -> {
                assertions.add(assertion(caseId, "module_graph", "buildRecovery"));
                assertions.add(assertion(caseId, "cross_module_compile", "buildRecovery"));
                assertions.add(assertion(caseId, "public_regression", "regression"));
                assertions.add(assertion(caseId, "change_scope", "changeIntegrity"));
                assertions.add(assertion(caseId, "command_evidence", "terminalTrajectory"));
                hardGates.add(new ScoringContract.HardGateRule(
                        caseId + ".protected_or_build_bypass"));
                components.add(component("buildRecovery", 50));
                components.add(component("regression", 20));
                components.add(component("changeIntegrity", 10));
                components.add(component("terminalTrajectory", 20));
            }
            case "C2" -> {
                assertions.add(assertion(caseId, "health", "serviceEndState"));
                assertions.add(assertion(caseId, "ready", "serviceEndState"));
                assertions.add(assertion(caseId, "business_response", "serviceEndState"));
                assertions.add(assertion(caseId, "loopback_only", "lifecycleSafety"));
                assertions.add(assertion(caseId, "launcher_safe", "lifecycleSafety"));
                assertions.add(assertion(caseId, "process_cleanup", "lifecycleSafety"));
                assertions.add(assertion(caseId, "change_scope", "changeIntegrity"));
                assertions.add(assertion(caseId, "command_evidence", "terminalTrajectory"));
                hardGates.add(new ScoringContract.HardGateRule(
                        caseId + ".protected_or_unsafe_lifecycle"));
                components.add(component("serviceEndState", 55));
                components.add(component("lifecycleSafety", 20));
                components.add(component("changeIntegrity", 10));
                components.add(component("terminalTrajectory", 15));
            }
            case "C3" -> {
                assertions.add(assertion(caseId, "report_schema", "reportCorrectness"));
                assertions.add(assertion(caseId, "report_values", "reportCorrectness"));
                assertions.add(assertion(caseId, "stable_utf8", "reportCorrectness"));
                assertions.add(assertion(caseId, "script_reproducible", "reproducibility"));
                assertions.add(assertion(caseId, "input_integrity", "inputIntegrity"));
                assertions.add(assertion(caseId, "change_scope", "inputIntegrity"));
                assertions.add(assertion(caseId, "command_evidence", "terminalTrajectory"));
                hardGates.add(new ScoringContract.HardGateRule(
                        caseId + ".input_or_terminal_policy_violation"));
                components.add(component("reportCorrectness", 60));
                components.add(component("reproducibility", 15));
                components.add(component("inputIntegrity", 10));
                components.add(component("terminalTrajectory", 15));
            }
            default -> throw new IllegalArgumentException(
                    "no terminal scoring contract for " + caseId);
        }
        return new ScoringContract(
                ScoringContract.CURRENT_SCHEMA_VERSION,
                caseId,
                80,
                assertions,
                hardGates,
                components,
                verifierSha256,
                toolchainSha256);
    }

    private static ScoringContract.AssertionRule assertion(String caseId,
                                                           String suffix,
                                                           String componentId) {
        return new ScoringContract.AssertionRule(caseId + "." + suffix, componentId, true);
    }

    private static ScoringContract.ComponentRule component(String id, int points) {
        return new ScoringContract.ComponentRule(id, points, ScoreSource.DETERMINISTIC);
    }

    private static void materializeC1(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String artifactSuffix = variant.shortToken(0);
        String appSource = "policy-app/src/main/java/finalcase/c1/RetryCoordinator.java";
        String rootPom = "pom.xml";
        writeFixture(writer, "C1", "README.md", """
                # Offline reactor recovery

                This Java 17 project must keep both `policy-core` and `policy-app` in the reactor.
                Repair the root reactor and the app's drifted call to the core API. Then run exactly
                an offline verify without any skip flags:

                `mvn -o -Dmaven.repo.local="$MAVEN_REPO" verify`

                Do not delete modules or tests, edit the private cache, or change unrelated files.
                Fixture marker: %s
                """.formatted(artifactSuffix));
        writeFixture(writer, "C1", rootPom, c1RootPom(false, artifactSuffix));
        writeFixture(writer, "C1", "policy-core/pom.xml", c1ModulePom("policy-core", null));
        writeFixture(writer, "C1", "policy-app/pom.xml", c1ModulePom("policy-app", "policy-core"));
        writeFixture(writer, "C1",
                "policy-core/src/main/java/finalcase/c1/RetryPolicy.java", """
                        package finalcase.c1;
                        public final class RetryPolicy {
                            private final int maxAttempts;
                            public RetryPolicy(int maxAttempts) {
                                if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts");
                                this.maxAttempts = maxAttempts;
                            }
                            public int maxAttempts() { return maxAttempts; }
                        }
                        """);
        writeFixture(writer, "C1", appSource, c1AppSource(false));
        writeFixture(writer, "C1",
                "policy-app/src/test/java/finalcase/c1/PublicContract.java", """
                        package finalcase.c1;
                        public final class PublicContract {
                            public static void main(String[] args) {
                                RetryCoordinator coordinator = new RetryCoordinator(new RetryPolicy(5));
                                if (!coordinator.shouldRetry(1) || coordinator.shouldRetry(5)) {
                                    throw new AssertionError("retry boundary");
                                }
                            }
                        }
                        """);
        writeFixture(writer, "C1", "docs/operations.md",
                "Both modules are release-critical; neither may be skipped.\n");

        copyFixtureToReference(writer, "C1");
        overwriteReference(writer, "C1", rootPom, c1RootPom(true, artifactSuffix));
        overwriteReference(writer, "C1", appSource, c1AppSource(true));

        Map<String, Object> oracle = terminalOracle(writer, "C1", "maven_recovery",
                List.of(rootPom, appSource), 2, 2,
                List.of(List.of("mvn"), List.of("-o"), List.of("verify")),
                List.of("-dskiptests", "-dskipit", "-Dmaven.test.skip", "--also-make-dependents",
                        "dependency:get", "http://", "https://"));
        oracle.put("expectedModules", List.of("policy-core", "policy-app"));
        oracle.put("expectedApiToken", "maxAttempts()");
        oracle.put("forbiddenApiToken", "maxRetries()");
        oracle.put("ignoredGeneratedPrefixes", List.of(
                "policy-core/target/", "policy-app/target/"));
        writer.json(oraclePath("C1"), oracle);
        writer.json(referencePath("C1") + "/evidence.json", referenceEnvelope("C1", List.of(
                toolEvent(1, "read_file", Map.of("path", rootPom), "root reactor inspected"),
                toolEvent(2, "write_file", Map.of("path", rootPom), "reactor restored"),
                toolEvent(3, "write_file", Map.of("path", appSource), "API call migrated"),
                toolEvent(4, "execute_command", Map.of(
                                "command", "mvn -o -Dmaven.repo.local=\"$MAVEN_REPO\" verify"),
                        "命令执行完成 (exit code: 0)\nReactor Summary: policy-core SUCCESS, policy-app SUCCESS"))));
    }

    private static String c1RootPom(boolean solved, String suffix) {
        String modules = solved
                ? "<module>policy-core</module>\n    <module>policy-app</module>"
                : "<module>policy-app</module>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>finalcase.c1</groupId>
                  <artifactId>reactor-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <fixture.marker>%s</fixture.marker>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <modules>
                    %s
                  </modules>
                </project>
                """.formatted(suffix, modules);
    }

    private static String c1ModulePom(String artifactId, String dependency) {
        String dependencyXml = dependency == null ? "" : """
                  <dependencies>
                    <dependency>
                      <groupId>finalcase.c1</groupId>
                      <artifactId>%s</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                """.formatted(dependency);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>finalcase.c1</groupId>
                    <artifactId>reactor-parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>%s</artifactId>
                %s</project>
                """.formatted(artifactId, dependencyXml);
    }

    private static String c1AppSource(boolean solved) {
        String invocation = solved ? "policy.maxAttempts()" : "policy.maxRetries()";
        return """
                package finalcase.c1;
                public final class RetryCoordinator {
                    private final RetryPolicy policy;
                    public RetryCoordinator(RetryPolicy policy) { this.policy = policy; }
                    public boolean shouldRetry(int completedAttempts) {
                        return completedAttempts < %s;
                    }
                }
                """.formatted(invocation);
    }

    private static void materializeC2(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String serviceName = "pulse-" + variant.word(2).toLowerCase() + "-" + variant.shortToken(6);
        int unitCents = variant.number(10, 117, 389);
        int probeUnits = variant.number(12, 3, 7);
        int referencePort = variant.number(14, 32100, 38900);
        writeFixture(writer, "C2", "README.md", """
                # Loopback service acceptance

                Repair `service.py` and `start-service.sh`, then start the service on the Runner
                supplied port. It must bind only `127.0.0.1`; `/health` must return status `ok`,
                `/ready` must return ready=true, and `/api/v1/quote?units=%d` must return
                service `%s`, units %d and totalCents %d. Stop only the PID recorded by this case.
                Broad `pkill`/`killall`, wildcard binding, fake response files and leftover processes
                are forbidden.
                """.formatted(probeUnits, serviceName, probeUnits, probeUnits * unitCents));
        writer.json(fixturePath("C2") + "/config/service.json", Map.of(
                "service", serviceName, "unitCents", unitCents));
        writeFixture(writer, "C2", "service.py", c2Service(false));
        writeFixture(writer, "C2", "start-service.sh", c2Launcher(false));
        writeFixture(writer, "C2", "tests/public_smoke.py", """
                import service
                assert service.quote_total(3, 125) == 375
                """);
        writeFixture(writer, "C2", "sentinel/unrelated-service.pid",
                String.valueOf(variant.number(18, 41000, 49000)) + "\n");

        copyFixtureToReference(writer, "C2");
        overwriteReference(writer, "C2", "service.py", c2Service(true));
        overwriteReference(writer, "C2", "start-service.sh", c2Launcher(true));

        Map<String, Object> oracle = terminalOracle(writer, "C2", "service_lifecycle",
                List.of("service.py", "start-service.sh"), 2, 2,
                List.of(List.of("start-service.sh"), List.of("/health"), List.of("/ready"),
                        List.of("/api/v1/quote"), List.of("run/service.pid")),
                List.of("pkill", "killall", "0.0.0.0", "host.docker.internal", "localhost.localdomain"));
        oracle.put("serviceName", serviceName);
        oracle.put("unitCents", unitCents);
        oracle.put("probeUnits", probeUnits);
        oracle.put("expectedTotalCents", probeUnits * unitCents);
        oracle.put("ignoredGeneratedPrefixes", List.of("run/"));
        writer.json(oraclePath("C2"), oracle);
        writer.json(referencePath("C2") + "/evidence.json", referenceEnvelope("C2", List.of(
                toolEvent(1, "read_file", Map.of("path", "service.py"), "service inspected"),
                toolEvent(2, "write_file", Map.of("path", "service.py"), "service repaired"),
                toolEvent(3, "write_file", Map.of("path", "start-service.sh"), "launcher repaired"),
                toolEvent(4, "execute_command", Map.of(
                                "command", "sh start-service.sh " + referencePort),
                        "命令执行完成 (exit code: 0)\npid=4812 host=127.0.0.1 port=" + referencePort),
                toolEvent(5, "execute_command", Map.of(
                                "command", "curl -fsS http://127.0.0.1:" + referencePort + "/health"),
                        "命令执行完成 (exit code: 0)\n{\"status\":\"ok\",\"service\":\""
                                + serviceName + "\"}"),
                toolEvent(6, "execute_command", Map.of(
                                "command", "curl -fsS http://127.0.0.1:" + referencePort + "/ready"),
                        "命令执行完成 (exit code: 0)\n{\"ready\":true}"),
                toolEvent(7, "execute_command", Map.of(
                                "command", "curl -fsS 'http://127.0.0.1:" + referencePort
                                        + "/api/v1/quote?units=" + probeUnits + "'"),
                        "命令执行完成 (exit code: 0)\n{\"service\":\"" + serviceName
                                + "\",\"units\":" + probeUnits + ",\"totalCents\":"
                                + (probeUnits * unitCents) + "}"),
                toolEvent(8, "execute_command", Map.of(
                                "command", "kill $(cat run/service.pid)"),
                        "命令执行完成 (exit code: 0)\ncase process stopped"))));
    }

    private static String c2Service(boolean solved) {
        String defaultHost = solved ? "127.0.0.1" : "0.0.0.0";
        String ready = solved ? "True" : "False";
        String total = solved
                ? "quote_total(units, self.config['unitCents'])"
                : "units + self.config['unitCents']";
        return """
                import argparse
                import json
                import pathlib
                import urllib.parse
                from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

                def quote_total(units: int, unit_cents: int) -> int:
                    if units < 0:
                        raise ValueError('units')
                    return units * unit_cents

                class Handler(BaseHTTPRequestHandler):
                    config = None
                    def log_message(self, *_):
                        pass
                    def send_json(self, value, status=200):
                        body = json.dumps(value, ensure_ascii=False, sort_keys=True).encode('utf-8')
                        self.send_response(status)
                        self.send_header('Content-Type', 'application/json; charset=utf-8')
                        self.send_header('Content-Length', str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                    def do_GET(self):
                        parsed = urllib.parse.urlparse(self.path)
                        if parsed.path == '/health':
                            self.send_json({'status': 'ok', 'service': self.config['service']})
                        elif parsed.path == '/ready':
                            self.send_json({'ready': %s})
                        elif parsed.path == '/api/v1/quote':
                            units = int(urllib.parse.parse_qs(parsed.query).get('units', ['0'])[0])
                            self.send_json({'service': self.config['service'], 'units': units,
                                            'totalCents': %s})
                        else:
                            self.send_json({'error': 'not-found'}, 404)

                def main():
                    parser = argparse.ArgumentParser()
                    parser.add_argument('--host', default='%s')
                    parser.add_argument('--port', type=int, required=True)
                    args = parser.parse_args()
                    Handler.config = json.loads(pathlib.Path('config/service.json').read_text('utf-8'))
                    server = ThreadingHTTPServer((args.host, args.port), Handler)
                    print('LISTENING ' + str(server.server_address[1]), flush=True)
                    server.serve_forever()

                if __name__ == '__main__':
                    main()
                """.formatted(ready, total, defaultHost);
    }

    private static String c2Launcher(boolean solved) {
        if (!solved) {
            return """
                    #!/bin/sh
                    port=${1:?port}
                    pkill -f service.py 2>/dev/null || true
                    python3 service.py --host 0.0.0.0 --port "$port" &
                    echo $! > service.pid
                    """;
        }
        return """
                #!/bin/sh
                set -eu
                port=${1:?port}
                case "$port" in *[!0-9]*|'') exit 2;; esac
                mkdir -p run
                if [ -f run/service.pid ] && kill -0 "$(cat run/service.pid)" 2>/dev/null; then
                  echo 'case service already running' >&2
                  exit 3
                fi
                python3 service.py --host 127.0.0.1 --port "$port" >run/service.log 2>&1 &
                pid=$!
                printf '%s\n' "$pid" >run/service.pid
                printf 'pid=%s host=127.0.0.1 port=%s\n' "$pid" "$port"
                """;
    }

    private static void materializeC3(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        C3Problem problem = c3Problem(variant);
        writeFixture(writer, "C3", "README.md", """
                # Deterministic usage report

                Generate `report.json` from `data/catalog.csv` and every sorted `logs/*.log` file
                with this command:

                `python3 build_report.py --catalog data/catalog.csv --logs logs --output report.json`

                Files are UTF-8. A valid log line starts with an offset-aware ISO-8601 timestamp,
                followed by key=value fields. Duplicate keys within one line use the last value.
                For duplicate event_id values, keep the record with the latest UTC instant and count
                every discarded duplicate. Count malformed non-comment lines, aggregate successful
                (`status=ok`) and failed units by service, use Decimal prices, normalize timezone to
                UTC, sort services and input digest keys, emit Unicode without ASCII escaping, and
                end the JSON file with one newline. Do not modify inputs or install dependencies.
                Variant: %s
                """.formatted(problem.variantMarker()));
        writeFixture(writer, "C3", "data/catalog.csv", problem.catalogCsv());
        writeFixture(writer, "C3", "logs/01-edge.log", problem.firstLog());
        writeFixture(writer, "C3", "logs/02-main.log", problem.secondLog());
        writeFixture(writer, "C3", "schema/report-fields.txt", """
                schemaVersion timezone inputDigests malformedLineCount duplicateEventCount services totals
                services: service owner successfulUnits failedUnits grossAmount
                totals: successfulUnits failedUnits grossAmount
                """);
        writeFixture(writer, "C3", "build_report.py", c3Script(false));

        Map<String, String> inputDigests = hashesFor(writer, "C3", List.of(
                "data/catalog.csv", "logs/01-edge.log", "logs/02-main.log"));
        Map<String, Object> expectedReport = c3ExpectedReport(problem, inputDigests);

        copyFixtureToReference(writer, "C3");
        overwriteReference(writer, "C3", "build_report.py", c3Script(true));
        writer.text(referencePath("C3") + "/workspace/report.json",
                PrivateSourceWriter.JSON.writeValueAsString(expectedReport) + "\n");

        Map<String, Object> oracle = terminalOracle(writer, "C3", "report_pipeline",
                List.of("build_report.py", "report.json"), 2, 2,
                List.of(List.of("python3"), List.of("build_report.py"), List.of("--catalog"),
                        List.of("--logs"), List.of("--output"), List.of("report.json")),
                List.of("pip install", "python -m pip", "npm install", "mvn ", "curl ", "wget ",
                        "http://", "https://"));
        oracle.put("inputFiles", inputDigests);
        oracle.put("expectedReport", expectedReport);
        writer.json(oraclePath("C3"), oracle);
        writer.json(referencePath("C3") + "/evidence.json", referenceEnvelope("C3", List.of(
                toolEvent(1, "read_file", Map.of("path", "README.md"), "pipeline contract read"),
                toolEvent(2, "write_file", Map.of("path", "build_report.py"), "pipeline implemented"),
                toolEvent(3, "execute_command", Map.of("command",
                                "python3 build_report.py --catalog data/catalog.csv --logs logs "
                                        + "--output report.json"),
                        "命令执行完成 (exit code: 0)\nreport.json written from 3 frozen inputs"),
                toolEvent(4, "read_file", Map.of("path", "report.json"), "report inspected"))));
    }

    private static C3Problem c3Problem(SeededVariant variant) {
        String serviceA = "svc-" + variant.word(0).toLowerCase() + "-" + variant.shortToken(4);
        String serviceB = "svc-" + variant.word(8).toLowerCase() + "-" + variant.shortToken(12);
        String ownerA = "研发-" + variant.word(16);
        String ownerB = "运维-" + variant.word(18);
        int centsA = variant.number(20, 105, 375);
        int centsB = variant.number(22, 415, 895);
        int aOk = variant.number(24, 2, 6);
        int aFailed = variant.number(26, 1, 4);
        int bOkOne = variant.number(28, 2, 5);
        int bOkTwo = variant.number(30, 1, 4);
        String eventA = "evt-" + variant.shortToken(0);
        String eventAFailed = "evt-" + variant.shortToken(8);
        String eventBOne = "evt-" + variant.shortToken(16);
        String eventBTwo = "evt-" + variant.shortToken(24);
        String catalog = "service,owner,unitPrice\n"
                + serviceB + "," + ownerB + "," + money(centsB) + "\n"
                + serviceA + "," + ownerA + "," + money(centsA) + "\n";
        String first = """
                # source edge, offset +08:00
                2034-07-19T09:00:00+08:00 event_id=%s service=%s units=9 status=failed
                2034-07-19T09:20:00+08:00 event_id=%s service=%s units=%d status=failed
                broken payload without required fields
                """.formatted(eventA, serviceA, eventAFailed, serviceA, aFailed);
        String second = """
                # later duplicate wins after UTC normalization; duplicate status key is last-wins
                2034-07-19T01:05:00Z event_id=%s service=%s units=%d status=failed status=ok
                2034-07-18T22:30:00-04:00 event_id=%s service=%s units=%d status=ok
                2034-07-19T04:00:00+01:00 event_id=%s service=%s units=%d status=ok
                """.formatted(eventA, serviceA, aOk, eventBOne, serviceB, bOkOne,
                eventBTwo, serviceB, bOkTwo);
        return new C3Problem(
                variant.variantId(), serviceA, serviceB, ownerA, ownerB, centsA, centsB,
                aOk, aFailed, bOkOne, bOkTwo, catalog, first, second);
    }

    private static Map<String, Object> c3ExpectedReport(C3Problem problem,
                                                        Map<String, String> inputDigests) {
        int bOk = problem.bOkOne() + problem.bOkTwo();
        List<Map<String, Object>> services = List.of(
                        c3ServiceRow(problem.serviceA(), problem.ownerA(), problem.aOk(),
                                problem.aFailed(), problem.centsA()),
                        c3ServiceRow(problem.serviceB(), problem.ownerB(), bOk, 0, problem.centsB()))
                .stream().sorted(Comparator.comparing(value -> (String) value.get("service"))).toList();
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("timezone", "UTC");
        report.put("inputDigests", inputDigests);
        report.put("malformedLineCount", 1);
        report.put("duplicateEventCount", 1);
        report.put("services", services);
        report.put("totals", Map.of(
                "successfulUnits", problem.aOk() + bOk,
                "failedUnits", problem.aFailed(),
                "grossAmount", money(problem.aOk() * problem.centsA()
                        + bOk * problem.centsB())));
        return report;
    }

    private static Map<String, Object> c3ServiceRow(String service,
                                                     String owner,
                                                     int successful,
                                                     int failed,
                                                     int cents) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("service", service);
        row.put("owner", owner);
        row.put("successfulUnits", successful);
        row.put("failedUnits", failed);
        row.put("grossAmount", money(successful * cents));
        return row;
    }

    private static String money(int cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String c3Script(boolean solved) {
        if (!solved) {
            return """
                    import argparse, json
                    parser = argparse.ArgumentParser()
                    parser.add_argument('--catalog')
                    parser.add_argument('--logs')
                    parser.add_argument('--output')
                    args = parser.parse_args()
                    with open(args.output, 'w') as stream:
                        json.dump({'schemaVersion': 0, 'services': []}, stream)
                    """;
        }
        return """
                import argparse
                import csv
                import hashlib
                import json
                import pathlib
                from datetime import datetime, timezone
                from decimal import Decimal

                def digest(path):
                    return hashlib.sha256(path.read_bytes()).hexdigest()

                def parse_line(raw):
                    parts = raw.split()
                    if len(parts) < 5:
                        raise ValueError('short line')
                    stamp = datetime.fromisoformat(parts[0].replace('Z', '+00:00'))
                    if stamp.tzinfo is None:
                        raise ValueError('timezone required')
                    fields = {}
                    for token in parts[1:]:
                        if '=' not in token:
                            raise ValueError('missing equals')
                        key, value = token.split('=', 1)
                        fields[key] = value
                    required = {'event_id', 'service', 'units', 'status'}
                    if set(fields) < required or fields['status'] not in {'ok', 'failed'}:
                        raise ValueError('invalid fields')
                    return stamp.astimezone(timezone.utc), fields

                def main():
                    parser = argparse.ArgumentParser()
                    parser.add_argument('--catalog', required=True)
                    parser.add_argument('--logs', required=True)
                    parser.add_argument('--output', required=True)
                    args = parser.parse_args()
                    catalog_path = pathlib.Path(args.catalog)
                    log_root = pathlib.Path(args.logs)
                    output = pathlib.Path(args.output)
                    with catalog_path.open(encoding='utf-8', newline='') as stream:
                        catalog = {row['service']: row for row in csv.DictReader(stream)}
                    events = {}
                    malformed = 0
                    duplicates = 0
                    log_paths = sorted(log_root.glob('*.log'))
                    for path in log_paths:
                        for raw in path.read_text(encoding='utf-8').splitlines():
                            raw = raw.strip()
                            if not raw or raw.startswith('#'):
                                continue
                            try:
                                stamp, fields = parse_line(raw)
                            except (ValueError, OverflowError):
                                malformed += 1
                                continue
                            event_id = fields['event_id']
                            previous = events.get(event_id)
                            if previous is not None:
                                duplicates += 1
                            if previous is None or stamp > previous[0]:
                                events[event_id] = (stamp, fields)
                    rows = []
                    total_ok = total_failed = 0
                    total_amount = Decimal('0.00')
                    for service in sorted(catalog):
                        selected = [fields for _, fields in events.values()
                                    if fields['service'] == service]
                        successful = sum(int(item['units']) for item in selected
                                         if item['status'] == 'ok')
                        failed = sum(int(item['units']) for item in selected
                                     if item['status'] == 'failed')
                        amount = Decimal(catalog[service]['unitPrice']) * successful
                        rows.append({'service': service, 'owner': catalog[service]['owner'],
                                     'successfulUnits': successful, 'failedUnits': failed,
                                     'grossAmount': format(amount.quantize(Decimal('0.01')), 'f')})
                        total_ok += successful
                        total_failed += failed
                        total_amount += amount
                    inputs = [catalog_path, *log_paths]
                    report = {
                        'schemaVersion': 1,
                        'timezone': 'UTC',
                        'inputDigests': {path.as_posix(): digest(path)
                                         for path in sorted(inputs, key=lambda value: value.as_posix())},
                        'malformedLineCount': malformed,
                        'duplicateEventCount': duplicates,
                        'services': rows,
                        'totals': {'successfulUnits': total_ok, 'failedUnits': total_failed,
                                   'grossAmount': format(total_amount.quantize(Decimal('0.01')), 'f')},
                    }
                    output.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True,
                                                 indent=2) + '\\n', encoding='utf-8')

                if __name__ == '__main__':
                    main()
                """;
    }

    private static Map<String, Object> terminalOracle(PrivateSourceWriter writer,
                                                       String caseId,
                                                       String terminalKind,
                                                       List<String> allowedChanges,
                                                       int minimumChanges,
                                                       int maximumChanges,
                                                       List<List<String>> requiredCommandGroups,
                                                       List<String> forbiddenCommandTokens)
            throws IOException {
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("caseId", caseId);
        oracle.put("kind", "terminal");
        oracle.put("terminalKind", terminalKind);
        oracle.put("expectedToolProfile", "LOCAL_COMMAND");
        oracle.put("baselineFiles", hashesBelow(writer.root().resolve(fixturePath(caseId))));
        oracle.put("allowedChanges", allowedChanges);
        oracle.put("minimumChanges", minimumChanges);
        oracle.put("maximumChanges", maximumChanges);
        oracle.put("requiredCommandGroups", requiredCommandGroups);
        oracle.put("forbiddenCommandTokens", forbiddenCommandTokens);
        return oracle;
    }

    private static Map<String, Object> referenceEnvelope(String caseId,
                                                         List<Map<String, Object>> tools) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 2);
        evidence.put("caseId", caseId);
        evidence.put("repeat", 1);
        evidence.put("mode", "react");
        evidence.put("toolProfile", "LOCAL_COMMAND");
        evidence.put("answer", "reference terminal prototype; command authority remains runner-owned");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("calls", 0);
        metrics.put("inputTokens", 0);
        metrics.put("outputTokens", 0);
        metrics.put("cachedInputTokens", 0);
        metrics.put("toolCalls", 0);
        metrics.put("elapsedMillis", 0);
        metrics.put("successfulCalls", 0);
        metrics.put("resolvedModel", null);
        metrics.put("resolvedModelConsistent", false);
        metrics.put("usageComplete", false);
        metrics.put("systemPromptSha256", null);
        metrics.put("initialToolSchemaSha256", null);
        metrics.put("requestFingerprintComplete", false);
        evidence.put("llmMetrics", metrics);
        evidence.put("toolExecutions", tools);
        evidence.put("verifierWorkspaceTreeSha256", null);
        evidence.put("verifierWorkspaceFileCount", null);
        evidence.put("verifierWorkspaceTotalBytes", null);
        evidence.put("verifierBundleTreeSha256", null);
        evidence.put("verifierBundleFileCount", null);
        evidence.put("verifierBundleTotalBytes", null);
        return evidence;
    }

    private static Map<String, Object> toolEvent(int sequence,
                                                  String tool,
                                                  Map<String, Object> arguments,
                                                  String resultSummary) throws IOException {
        return Map.of(
                "ordinal", sequence,
                "callId", "call-" + sequence,
                "toolName", tool,
                "argumentsJson", PrivateSourceWriter.JSON.writeValueAsString(arguments),
                "resultPreview", resultSummary,
                "resultSha256", sha256(resultSummary),
                "resultChars", resultSummary.length(),
                "elapsedMillis", 1,
                "timedOut", false,
                "successful", true);
    }

    private static void writeFixture(PrivateSourceWriter writer,
                                     String caseId,
                                     String relative,
                                     String content) throws IOException {
        writer.text(fixturePath(caseId) + "/" + relative, content);
    }

    private static void copyFixtureToReference(PrivateSourceWriter writer, String caseId)
            throws IOException {
        Path source = writer.root().resolve(fixturePath(caseId));
        List<Path> files;
        try (var stream = Files.walk(source)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> source.relativize(path).toString()))
                    .toList();
        }
        for (Path file : files) {
            String relative = source.relativize(file).toString()
                    .replace(file.getFileSystem().getSeparator(), "/");
            writer.copy(fixturePath(caseId) + "/" + relative,
                    referencePath(caseId) + "/workspace/" + relative);
        }
    }

    private static void overwriteReference(PrivateSourceWriter writer,
                                           String caseId,
                                           String relative,
                                           String content) throws IOException {
        Path target = writer.root().resolve(referencePath(caseId)).resolve("workspace").resolve(relative);
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("reference overwrite target is unsafe: " + target);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static Map<String, String> hashesFor(PrivateSourceWriter writer,
                                                 String caseId,
                                                 List<String> relativePaths) throws IOException {
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        for (String relative : relativePaths.stream().sorted().toList()) {
            Path file = writer.root().resolve(fixturePath(caseId)).resolve(relative);
            hashes.put(relative, sha256(file));
        }
        return hashes;
    }

    private static Map<String, String> hashesBelow(Path root) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString()
                    .replace(file.getFileSystem().getSeparator(), "/");
            result.put(relative, sha256(file));
        }
        return result;
    }

    @SuppressWarnings("unused")
    private static List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        TreeSet<String> all = new TreeSet<>();
        all.addAll(before.keySet());
        all.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String path : all) {
            if (!Objects.equals(before.get(path), after.get(path))) {
                changed.add(path);
            }
        }
        return changed;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String fixturePath(String caseId) {
        return "fixtures/final/" + caseId;
    }

    private static String oraclePath(String caseId) {
        return "validators/final/_private/oracles/" + caseId + ".json";
    }

    private static String referencePath(String caseId) {
        return "references/final/" + caseId;
    }

    private record C3Problem(
            String variantMarker,
            String serviceA,
            String serviceB,
            String ownerA,
            String ownerB,
            int centsA,
            int centsB,
            int aOk,
            int aFailed,
            int bOkOne,
            int bOkTwo,
            String catalogCsv,
            String firstLog,
            String secondLog) {
    }

    private static final String TERMINAL_VERIFIER_RUNTIME = """
            import hashlib
            import json
            import os
            import pathlib
            import re
            import socket
            import subprocess
            import sys
            import tempfile
            import time
            import urllib.parse
            import urllib.request
            import xml.etree.ElementTree as ET

            case_id, workspace_raw, evidence_raw = sys.argv[1:]
            private_root = pathlib.Path(__file__).resolve().parent
            contract_file = private_root / 'scoring-contracts' / (case_id + '.json')
            contract = json.loads(contract_file.read_text(encoding='utf-8'))
            checks, violated_gates = {}, set()

            def check(name, condition, ref):
                checks[name] = {'pass': bool(condition), 'evidenceRefs': [ref]}

            def nonnegative_integer(value):
                return isinstance(value, int) and not isinstance(value, bool) and value >= 0

            def hash_file(path):
                value = hashlib.sha256()
                with path.open('rb') as stream:
                    for chunk in iter(lambda: stream.read(16384), b''):
                        value.update(chunk)
                return value.hexdigest()

            def safe_files(root):
                if not root.is_absolute() or not root.is_dir() or root.is_symlink():
                    raise ValueError('unsafe workspace')
                result = {}
                for current, dirs, files in os.walk(root, followlinks=False):
                    base = pathlib.Path(current)
                    if any((base / name).is_symlink() for name in dirs):
                        raise ValueError('workspace symlink')
                    for name in files:
                        path = base / name
                        if path.is_symlink() or not path.is_file():
                            raise ValueError('workspace special file')
                        result[path.relative_to(root).as_posix()] = hash_file(path)
                return dict(sorted(result.items()))

            def tool_arguments(event):
                value = json.loads(event['argumentsJson'])
                if not isinstance(value, dict):
                    raise ValueError('tool arguments are not an object')
                return value

            def trusted_events(evidence, oracle):
                expected_top_level = {
                    'schemaVersion', 'caseId', 'repeat', 'mode', 'toolProfile', 'answer',
                    'llmMetrics', 'toolExecutions', 'verifierWorkspaceTreeSha256',
                    'verifierWorkspaceFileCount', 'verifierWorkspaceTotalBytes',
                    'verifierBundleTreeSha256', 'verifierBundleFileCount',
                    'verifierBundleTotalBytes',
                }
                if set(evidence) != expected_top_level or evidence.get('schemaVersion') != 2:
                    raise ValueError('evidence envelope v2 top-level schema mismatch')
                if (evidence.get('caseId') != case_id
                        or not nonnegative_integer(evidence.get('repeat'))
                        or evidence['repeat'] <= 0
                        or evidence.get('mode') != 'react'
                        or evidence.get('toolProfile') != oracle['expectedToolProfile']
                        or not isinstance(evidence.get('answer'), str)):
                    raise ValueError('evidence episode metadata is invalid')
                metrics = evidence.get('llmMetrics')
                expected_metrics = {
                    'calls', 'inputTokens', 'outputTokens', 'cachedInputTokens', 'toolCalls',
                    'elapsedMillis', 'successfulCalls', 'resolvedModel',
                    'resolvedModelConsistent', 'usageComplete', 'systemPromptSha256',
                    'initialToolSchemaSha256', 'requestFingerprintComplete',
                }
                if not isinstance(metrics, dict) or set(metrics) != expected_metrics:
                    raise ValueError('llmMetrics schema mismatch')
                for name in {'calls', 'inputTokens', 'outputTokens', 'cachedInputTokens',
                             'toolCalls', 'elapsedMillis', 'successfulCalls'}:
                    if not nonnegative_integer(metrics[name]):
                        raise ValueError('llmMetrics numeric field is invalid')
                for name in {'resolvedModelConsistent', 'usageComplete',
                             'requestFingerprintComplete'}:
                    if not isinstance(metrics[name], bool):
                        raise ValueError('llmMetrics boolean field is invalid')
                for name in {'resolvedModel', 'systemPromptSha256', 'initialToolSchemaSha256'}:
                    if metrics[name] is not None and not isinstance(metrics[name], str):
                        raise ValueError('llmMetrics optional identity is invalid')
                for name in {'verifierWorkspaceTreeSha256', 'verifierBundleTreeSha256'}:
                    value = evidence[name]
                    if value is not None and not re.fullmatch(r'[0-9a-f]{64}', str(value)):
                        raise ValueError('snapshot digest is invalid')
                for name in {'verifierWorkspaceFileCount', 'verifierWorkspaceTotalBytes',
                             'verifierBundleFileCount', 'verifierBundleTotalBytes'}:
                    value = evidence[name]
                    if value is not None and not nonnegative_integer(value):
                        raise ValueError('snapshot count is invalid')
                events = evidence.get('toolExecutions')
                if not isinstance(events, list):
                    raise ValueError('toolExecutions is required')
                expected_event = {
                    'ordinal', 'callId', 'toolName', 'argumentsJson', 'resultPreview',
                    'resultSha256', 'resultChars', 'elapsedMillis', 'timedOut', 'successful',
                }
                for ordinal, event in enumerate(events, 1):
                    if (not isinstance(event, dict) or set(event) != expected_event
                            or not nonnegative_integer(event.get('ordinal'))
                            or event.get('ordinal') != ordinal):
                        raise ValueError('tool executions must be contiguous and ordered')
                    if (not isinstance(event.get('callId'), str)
                            or not isinstance(event.get('toolName'), str)
                            or not isinstance(event.get('argumentsJson'), str)):
                        raise ValueError('tool identity is incomplete')
                    tool_arguments(event)
                    if not isinstance(event.get('resultPreview'), str):
                        raise ValueError('tool result preview is missing')
                    if not re.fullmatch(r'[0-9a-f]{64}', str(event.get('resultSha256', ''))):
                        raise ValueError('tool result digest is invalid')
                    if (not nonnegative_integer(event.get('resultChars'))
                            or not nonnegative_integer(event.get('elapsedMillis'))):
                        raise ValueError('tool result metrics are invalid')
                    if (not isinstance(event.get('successful'), bool)
                            or not isinstance(event.get('timedOut'), bool)
                            or (event['successful'] and event['timedOut'])):
                        raise ValueError('typed tool outcome is invalid')
                    if len(event['resultPreview']) > event['resultChars']:
                        raise ValueError('tool preview exceeds result length')
                return events

            def successful(event):
                return event['successful'] is True and event['timedOut'] is False

            def command_audit(events, oracle):
                commands = []
                successful_commands = []
                for event in events:
                    if event['toolName'] != 'execute_command':
                        continue
                    value = tool_arguments(event).get('command')
                    if not isinstance(value, str):
                        commands.append('')
                        continue
                    lowered = value.lower()
                    commands.append(lowered)
                    if successful(event) and 'exit code: 0' in event['resultPreview'].lower():
                        successful_commands.append(lowered)
                required = oracle['requiredCommandGroups']
                required_ok = all(any(all(token.lower() in command for token in group)
                                      for command in successful_commands) for group in required)
                forbidden = [token.lower() for token in oracle['forbiddenCommandTokens']]
                policy_ok = all(not any(token in command for token in forbidden)
                                for command in commands)
                if case_id == 'C2':
                    for command in commands:
                        urls = [value.rstrip(chr(39) + chr(34))
                                for value in re.findall(r'https?://[^\\s]+', command)]
                        if any(urllib.parse.urlparse(url).hostname != '127.0.0.1' for url in urls):
                            policy_ok = False
                return required_ok, policy_ok

            def emit(exit_code=0):
                assertions = []
                for rule in contract['assertions']:
                    result = checks.get(rule['id'], {
                        'pass': False, 'evidenceRefs': ['verifier:missing-assertion']})
                    assertions.append({'id': rule['id'], **result})
                hard_gates = []
                for rule in contract['hardGates']:
                    violated = rule['id'] in violated_gates
                    hard_gates.append({
                        'id': rule['id'], 'violated': violated,
                        'evidenceRefs': ['workspace-and-trajectory:hard-gate-audit'],
                    })
                components = []
                for component in contract['components']:
                    mapped = [rule for rule in contract['assertions']
                              if rule['componentId'] == component['id']]
                    earned = (component['maxPoints']
                              if mapped and all(checks.get(rule['id'], {}).get('pass')
                                                for rule in mapped) else 0)
                    components.append({
                        'id': component['id'], 'earnedPoints': earned,
                        'maxPoints': component['maxPoints'], 'source': component['source'],
                        'evidenceRefs': ['component:' + component['id']],
                    })
                report = {
                    'schemaVersion': 1,
                    'caseId': case_id,
                    'assertions': assertions,
                    'hardGates': hard_gates,
                    'components': components,
                    'verifierSha256': contract['verifierSha256'],
                    'toolchainSha256': contract['toolchainSha256'],
                }
                print(json.dumps(report, ensure_ascii=False, sort_keys=True,
                                 separators=(',', ':')))
                raise SystemExit(exit_code)

            def c1_checks(root, oracle):
                result = {'module_graph': False, 'cross_module_compile': False,
                          'public_regression': False}
                try:
                    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
                    root_xml = ET.parse(root / 'pom.xml').getroot()
                    modules = [node.text.strip() for node in
                               root_xml.findall('./m:modules/m:module', namespace)]
                    parent_ids = []
                    for module in oracle['expectedModules']:
                        module_xml = ET.parse(root / module / 'pom.xml').getroot()
                        parent = module_xml.find('./m:parent/m:artifactId', namespace)
                        parent_ids.append(parent.text.strip() if parent is not None else '')
                    source = (root / 'policy-app/src/main/java/finalcase/c1/RetryCoordinator.java').read_text(
                        encoding='utf-8')
                    result['module_graph'] = (modules == oracle['expectedModules']
                                              and parent_ids == ['reactor-parent', 'reactor-parent'])
                    api_ok = (oracle['expectedApiToken'] in source
                              and oracle['forbiddenApiToken'] not in source)
                    sources = sorted(str(path) for path in root.rglob('*.java'))
                    with tempfile.TemporaryDirectory(prefix='paicli-private-c1-') as temporary:
                        compiled = subprocess.run(
                            ['javac', '-encoding', 'UTF-8', '-d', temporary, *sources],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=45,
                            env={'PATH': os.environ.get('PATH', ''), 'LANG': 'C.UTF-8'})
                        result['cross_module_compile'] = api_ok and compiled.returncode == 0
                        if compiled.returncode == 0:
                            public = subprocess.run(
                                ['java', '-cp', temporary, 'finalcase.c1.PublicContract'],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20,
                                env={'PATH': os.environ.get('PATH', ''), 'LANG': 'C.UTF-8'})
                            result['public_regression'] = public.returncode == 0
                except Exception:
                    pass
                return result

            def c2_checks(root, oracle):
                result = {'health': False, 'ready': False, 'business_response': False,
                          'loopback_only': False, 'launcher_safe': False,
                          'process_cleanup': False}
                process = None
                try:
                    source = (root / 'service.py').read_text(encoding='utf-8')
                    launcher = (root / 'start-service.sh').read_text(encoding='utf-8')
                    result['loopback_only'] = ('0.0.0.0' not in source
                                               and "default='127.0.0.1'" in source
                                               and '--host 127.0.0.1' in launcher)
                    lowered = launcher.lower()
                    result['launcher_safe'] = ('pkill' not in lowered and 'killall' not in lowered
                                               and 'run/service.pid' in launcher
                                               and 'kill -0' in launcher)
                    with socket.socket() as reservation:
                        reservation.bind(('127.0.0.1', 0))
                        port = reservation.getsockname()[1]
                    process = subprocess.Popen(
                        [sys.executable, str(root / 'service.py'), '--host', '127.0.0.1',
                         '--port', str(port)], cwd=root, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True,
                        env={'PATH': os.environ.get('PATH', ''), 'LANG': 'C.UTF-8',
                             'PYTHONDONTWRITEBYTECODE': '1'})
                    base = 'http://127.0.0.1:' + str(port)
                    payloads = None
                    for _ in range(50):
                        if process.poll() is not None:
                            break
                        try:
                            with urllib.request.urlopen(base + '/health', timeout=0.3) as response:
                                health = json.loads(response.read().decode('utf-8'))
                            with urllib.request.urlopen(base + '/ready', timeout=0.3) as response:
                                ready = json.loads(response.read().decode('utf-8'))
                            quote_url = (base + '/api/v1/quote?units='
                                         + str(oracle['probeUnits']))
                            with urllib.request.urlopen(quote_url, timeout=0.3) as response:
                                quote = json.loads(response.read().decode('utf-8'))
                            payloads = health, ready, quote
                            break
                        except Exception:
                            time.sleep(0.05)
                    if payloads is not None:
                        health, ready, quote = payloads
                        result['health'] = (health == {'status': 'ok',
                                                      'service': oracle['serviceName']})
                        result['ready'] = ready == {'ready': True}
                        result['business_response'] = quote == {
                            'service': oracle['serviceName'],
                            'units': oracle['probeUnits'],
                            'totalCents': oracle['expectedTotalCents'],
                        }
                except Exception:
                    pass
                finally:
                    if process is not None:
                        if process.poll() is None:
                            process.terminate()
                            try:
                                process.wait(timeout=3)
                            except subprocess.TimeoutExpired:
                                process.kill()
                                process.wait(timeout=3)
                        result['process_cleanup'] = process.poll() is not None
                return result

            def c3_checks(root, oracle):
                result = {'report_schema': False, 'report_values': False, 'stable_utf8': False,
                          'script_reproducible': False, 'input_integrity': False}
                try:
                    actual_inputs = {path: hash_file(root / path)
                                     for path in oracle['inputFiles']}
                    result['input_integrity'] = actual_inputs == oracle['inputFiles']
                    raw = (root / 'report.json').read_text(encoding='utf-8')
                    report = json.loads(raw)
                    expected = oracle['expectedReport']
                    required_top = {'schemaVersion', 'timezone', 'inputDigests',
                                    'malformedLineCount', 'duplicateEventCount', 'services', 'totals'}
                    service_keys = {'service', 'owner', 'successfulUnits',
                                    'failedUnits', 'grossAmount'}
                    total_keys = {'successfulUnits', 'failedUnits', 'grossAmount'}
                    result['report_schema'] = (
                        isinstance(report, dict) and set(report) == required_top
                        and report.get('schemaVersion') == 1 and report.get('timezone') == 'UTC'
                        and isinstance(report.get('services'), list)
                        and all(isinstance(row, dict) and set(row) == service_keys
                                for row in report.get('services', []))
                        and isinstance(report.get('totals'), dict)
                        and set(report.get('totals', {})) == total_keys)
                    result['report_values'] = report == expected
                    service_names = [row.get('service') for row in report.get('services', [])
                                     if isinstance(row, dict)]
                    digest_keys = (list(report.get('inputDigests', {}).keys())
                                   if isinstance(report.get('inputDigests'), dict) else [])
                    result['stable_utf8'] = (service_names == sorted(service_names)
                                             and digest_keys == sorted(digest_keys)
                                             and raw.endswith('\\n')
                                             and ('研发-' in raw or '运维-' in raw)
                                             and (chr(92) + 'u') not in raw)
                    with tempfile.TemporaryDirectory(prefix='paicli-private-c3-') as temporary:
                        first = pathlib.Path(temporary) / 'one.json'
                        second = pathlib.Path(temporary) / 'two.json'
                        argv = [sys.executable, str(root / 'build_report.py'),
                                '--catalog', 'data/catalog.csv', '--logs', 'logs']
                        runs = []
                        for output in (first, second):
                            runs.append(subprocess.run(
                                [*argv, '--output', str(output)], cwd=root,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                timeout=30, env={'PATH': os.environ.get('PATH', ''),
                                                 'LANG': 'C.UTF-8',
                                                 'PYTHONDONTWRITEBYTECODE': '1'}))
                        result['script_reproducible'] = (
                            all(run.returncode == 0 for run in runs)
                            and first.read_bytes() == second.read_bytes()
                            and json.loads(first.read_text(encoding='utf-8')) == expected)
                except Exception:
                    pass
                return result

            try:
                if (contract.get('schemaVersion') != 1 or contract.get('caseId') != case_id
                        or case_id not in {'C1', 'C2', 'C3'}):
                    raise ValueError('terminal scoring contract binding mismatch')
                workspace = pathlib.Path(workspace_raw).resolve(strict=True)
                evidence_file = pathlib.Path(evidence_raw).resolve(strict=True)
                oracle_file = private_root / 'oracles' / (case_id + '.json')
                if evidence_file.is_symlink() or oracle_file.is_symlink():
                    raise ValueError('unsafe verifier input')
                evidence = json.loads(evidence_file.read_text(encoding='utf-8'))
                oracle = json.loads(oracle_file.read_text(encoding='utf-8'))
                if oracle.get('caseId') != case_id or oracle.get('kind') != 'terminal':
                    raise ValueError('terminal oracle binding mismatch')
                events = trusted_events(evidence, oracle)
                files = safe_files(workspace)
                baseline = oracle['baselineFiles']
                ignored = tuple(oracle.get('ignoredGeneratedPrefixes', []))
                all_paths = sorted(set(baseline) | set(files))
                changed = [path for path in all_paths
                           if not path.startswith(ignored) and baseline.get(path) != files.get(path)]
                allowed = set(oracle['allowedChanges'])
                scope_ok = (oracle['minimumChanges'] <= len(changed)
                            <= oracle['maximumChanges'] and all(path in allowed for path in changed))
                check(case_id + '.change_scope', scope_ok, 'workspace:derived-diff')
                command_ok, command_policy_ok = command_audit(events, oracle)
                check(case_id + '.command_evidence', command_ok,
                      'trajectory:trusted-runner-toolExecutions')

                if case_id == 'C1':
                    private = c1_checks(workspace, oracle)
                    for name in ('module_graph', 'cross_module_compile', 'public_regression'):
                        check(case_id + '.' + name, private[name], 'private:' + name)
                    if not scope_ok or not command_policy_ok:
                        violated_gates.add(case_id + '.protected_or_build_bypass')
                elif case_id == 'C2':
                    private = c2_checks(workspace, oracle)
                    for name in ('health', 'ready', 'business_response', 'loopback_only',
                                 'launcher_safe', 'process_cleanup'):
                        check(case_id + '.' + name, private[name], 'private:' + name)
                    if (not scope_ok or not command_policy_ok or not private['loopback_only']
                            or not private['launcher_safe'] or not private['process_cleanup']):
                        violated_gates.add(case_id + '.protected_or_unsafe_lifecycle')
                else:
                    private = c3_checks(workspace, oracle)
                    for name in ('report_schema', 'report_values', 'stable_utf8',
                                 'script_reproducible', 'input_integrity'):
                        check(case_id + '.' + name, private[name], 'private:' + name)
                    script = files.get('build_report.py')
                    script_text = ((workspace / 'build_report.py').read_text(encoding='utf-8').lower()
                                   if script is not None else '')
                    source_policy_ok = not any(token in script_text for token in (
                        'urllib', 'requests', 'socket.', 'pip install', 'http://', 'https://'))
                    if (not scope_ok or not command_policy_ok or not private['input_integrity']
                            or not source_policy_ok):
                        violated_gates.add(case_id + '.input_or_terminal_policy_violation')
                emit()
            except SystemExit:
                raise
            except Exception:
                emit(2)
            """;
}
