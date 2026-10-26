package com.paicli.eval.benchmark.finalset.generator;

import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Original seeded fixture, oracle, hidden-test, reference and verifier materializers. */
final class ImplementedCaseMaterializers {
    private static final DateTimeFormatter G2_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
    private static final String WRAPPER = """
            #!/bin/sh
            set -eu
            if [ "$#" -ne 2 ]; then
              exit 2
            fi
            validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
            exec python3 "$validator_root/_private/verify.py" "%s" "$1" "$2"
            """;

    private ImplementedCaseMaterializers() {
    }

    static void writeSharedVerifierRuntime(PrivateSourceWriter writer) throws IOException {
        writer.text("validators/final/_private/verify.py", VERIFIER_RUNTIME);
        writer.text("validators/final/_private/hidden_checks.py", HIDDEN_CHECKS);
        TerminalCaseMaterializers.writeSharedVerifierRuntime(writer);
    }

    static String renderPublicPrompt(FinalSourceRecipeCatalog.Recipe recipe,
                                     SeededVariant variant) {
        return switch (recipe.id()) {
            case "G1" -> renderG1Prompt(g1Problem(variant));
            case "G2" -> renderG2Prompt(g2Problem(variant));
            case "D1" -> D1CaseMaterializer.prompt(variant);
            case "D2" -> D2CaseMaterializer.prompt(variant);
            case "D3" -> D3CaseMaterializer.prompt(variant);
            case "F4" -> F4CaseMaterializer.prompt(variant);
            case "F1" -> F1CaseMaterializer.prompt(variant);
            case "F2" -> F2CaseMaterializer.prompt(variant);
            case "F3" -> F3CaseMaterializer.prompt(variant);
            case "D4" -> D4CaseMaterializer.prompt(variant);
            case "E1" -> com.paicli.eval.benchmark.plan.E1FrozenOracle.taskPrompt();
            case "E2" -> com.paicli.eval.benchmark.team.E2FrozenOracle.taskPrompt();
            default -> recipe.publicPrompt();
        };
    }

    static void materialize(PrivateSourceWriter writer,
                            FinalSourceRecipeCatalog.Recipe recipe,
                            SeededVariant variant) throws IOException {
        String wrapper = "D1".equals(recipe.id()) ? D1CaseMaterializer.wrapper()
                : "D2".equals(recipe.id()) ? D2CaseMaterializer.wrapper()
                : "D3".equals(recipe.id()) ? D3CaseMaterializer.wrapper()
                : "F4".equals(recipe.id()) ? F4CaseMaterializer.wrapper()
                : "F1".equals(recipe.id()) ? F1CaseMaterializer.wrapper()
                : "F2".equals(recipe.id()) ? F2CaseMaterializer.wrapper()
                : "F3".equals(recipe.id()) ? F3CaseMaterializer.wrapper()
                : "D4".equals(recipe.id()) ? D4CaseMaterializer.wrapper()
                : "E1".equals(recipe.id()) ? E1CaseMaterializer.wrapper()
                : "E2".equals(recipe.id()) ? E2CaseMaterializer.wrapper() : TerminalCaseMaterializers.supports(recipe.id())
                ? TerminalCaseMaterializers.wrapper(recipe.id())
                : WRAPPER.formatted(recipe.id());
        writer.executable("validators/final/" + recipe.id(), wrapper);
        switch (recipe.id()) {
            case "A1" -> materializeA1(writer, variant);
            case "A2" -> materializeA2(writer, variant);
            case "A3" -> materializeA3(writer, variant);
            case "A4" -> materializeA4(writer, variant);
            case "B1" -> materializeB1(writer, variant);
            case "B2" -> materializeB2(writer, variant);
            case "B3" -> materializeB3(writer, variant);
            case "B4" -> materializeB4(writer, variant);
            case "B5" -> materializeB5(writer, variant);
            case "B6" -> materializeB6(writer, variant);
            case "C1", "C2", "C3" ->
                    TerminalCaseMaterializers.materialize(writer, recipe, variant);
            case "G1" -> materializeG1(writer, variant);
            case "G2" -> materializeG2(writer, variant);
            case "D1" -> D1CaseMaterializer.materialize(writer, variant);
            case "D2" -> D2CaseMaterializer.materialize(writer, variant);
            case "D3" -> D3CaseMaterializer.materialize(writer, variant);
            case "F4" -> F4CaseMaterializer.materialize(writer, variant);
            case "F1" -> F1CaseMaterializer.materialize(writer, variant);
            case "F2" -> F2CaseMaterializer.materialize(writer, variant);
            case "F3" -> F3CaseMaterializer.materialize(writer, variant);
            case "D4" -> D4CaseMaterializer.materialize(writer, variant);
            case "E1" -> E1CaseMaterializer.materializeRegistered(writer, variant);
            case "E2" -> E2CaseMaterializer.materializeRegistered(writer, variant);
            default -> throw new IllegalArgumentException(
                    "no implemented materializer registered for " + recipe.id());
        }
        writeScoringContract(writer, recipe.id());
    }

    private static void materializeA1(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String targetClass = "RetryWindowPolicy" + variant.word(0);
        String targetPath = "src/main/java/finalcase/a1/schedule/" + targetClass + ".java";
        int base = variant.number(1, 7, 19);
        int cap = variant.number(3, 71, 109);
        writeFixture(writer, "A1", "README.md",
                "# Retry scheduling service\n\nSeveral retry helpers are intentional distractors.\n");
        writeFixture(writer, "A1", targetPath, """
                package finalcase.a1.schedule;
                public final class %s {
                    private static final long BASE_SECONDS = %dL;
                    private static final long CAP_SECONDS = %dL;
                    public long nextRetrySlot(int attempt, long tenantJitterSeconds) {
                        long segment = Math.min(CAP_SECONDS, BASE_SECONDS * (1L << Math.min(attempt, 5)));
                        return segment + Math.floorMod(tenantJitterSeconds, BASE_SECONDS);
                    }
                }
                """.formatted(targetClass, base, cap));
        writeFixture(writer, "A1", "src/main/java/finalcase/a1/schedule/RetryPlanner.java", """
                package finalcase.a1.schedule;
                public final class RetryPlanner {
                    private final %s policy = new %s();
                    public long schedule(int attempt, long tenantKey) {
                        return policy.nextRetrySlot(attempt, tenantKey);
                    }
                }
                """.formatted(targetClass, targetClass));
        for (int index = 0; index < 12; index++) {
            writeFixture(writer, "A1",
                    "src/main/java/finalcase/a1/decoy/RetryHelper" + index + ".java",
                    "package finalcase.a1.decoy;\npublic final class RetryHelper" + index
                            + " { public long delay(int value) { return value * " + (index + 1) + "L; } }\n");
        }
        copyFixtureToReference(writer, "A1");
        Map<String, Object> oracle = retrievalOracle(writer, "A1", List.of(targetPath),
                List.of(targetClass, "nextRetrySlot"), List.of("Math.min", "Math.floorMod"),
                List.of("grep_code", "read_file"), 3, false);
        oracle.put("mustRead", List.of(Map.of(
                "path", targetPath, "offsetAtMost", 5, "coveredLineAtLeast", 8)));
        writer.json(oraclePath("A1"), oracle);
        writer.json(referencePath("A1") + "/evidence.json", retrievalEvidence(
                "A1",
                Map.of("files", List.of(targetPath), "symbols", List.of(targetClass, "nextRetrySlot"),
                        "evidence", List.of("Math.min caps the segment", "Math.floorMod adds jitter")),
                List.of(
                        toolEvent(1, "grep_code", Map.of("pattern", "nextRetrySlot"), targetPath),
                        toolEvent(2, "read_file",
                                Map.of("path", targetPath, "offset", 1, "limit", 12),
                                "Math.min Math.floorMod"))));
    }

    private static void materializeA2(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String targetSymbol = "assignTrialSettlementBucket" + variant.word(2);
        String targetPath = "packages/billing-domain/src/trial/settlement-bucket.ts";
        writeFixture(writer, "A2", "README.md",
                "# Trial billing monorepo\n\nBusiness language intentionally differs from symbol names.\n");
        writeFixture(writer, "A2", targetPath, """
                export function %s(dayOfMonth, monthLength, accountOffsetDays) {
                  const businessDay = Math.max(1, Math.min(monthLength, dayOfMonth + accountOffsetDays));
                  const rollbackBoundary = %d;
                  return businessDay > rollbackBoundary ? monthLength : businessDay;
                }
                """.formatted(targetSymbol, variant.number(4, 25, 28)));
        String[] groups = {"ledger", "calendar", "tenant", "invoice"};
        for (int index = 0; index < 52; index++) {
            writeFixture(writer, "A2",
                    "packages/" + groups[index % groups.length] + "/src/generated/module-%02d.ts".formatted(index),
                    "export function mapDay" + index + "(value) { return value + "
                            + variant.number((index % 20) + 5, 1, 31) + "; }\n");
        }
        copyFixtureToReference(writer, "A2");
        Map<String, Object> oracle = retrievalOracle(writer, "A2", List.of(targetPath),
                List.of(targetSymbol), List.of("rollbackBoundary", "monthLength"),
                List.of("grep_code", "search_code", "read_file"), 5, false);
        oracle.put("exactSearchInsufficient", Map.of(
                "tool", "grep_code", "mustNotReveal", List.of(targetPath, targetSymbol)));
        oracle.put("semanticSearchMustReveal", List.of(targetPath));
        oracle.put("mustRead", List.of(Map.of(
                "path", targetPath, "offsetAtMost", 1, "coveredLineAtLeast", 5)));
        writer.json(oraclePath("A2"), oracle);
        writer.json(referencePath("A2") + "/evidence.json", retrievalEvidence(
                "A2",
                Map.of("files", List.of(targetPath), "symbols", List.of(targetSymbol),
                        "evidence", List.of("rollbackBoundary selects month end", "monthLength bounds the bucket")),
                List.of(
                        toolEvent(1, "grep_code", Map.of("pattern", "business day bucket"),
                                "No exact source matches for the business phrase."),
                        toolEvent(2, "search_code",
                                Map.of("query", "trial account business clock settlement"), targetPath),
                        toolEvent(3, "read_file",
                                Map.of("path", targetPath, "offset", 1, "limit", 7),
                                "rollbackBoundary monthLength"))));
    }

    private static void materializeA3(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String route = "src/gateway/route-" + variant.shortToken(0) + ".ts";
        String service = "services/java/src/finalcase/a3/TraceBridge" + variant.word(5) + ".java";
        String adapter = "workers/python/receipt_store_" + variant.shortToken(7) + ".py";
        writeFixture(writer, "A3", "README.md", "# Correlated receipt pipeline\n");
        writeFixture(writer, "A3", route, """
                export async function acceptReceipt(request, javaBridge) {
                  const correlationId = request.headers["x-correlation-id"];
                  // javaBridge is the typed Java interop boundary owned by the gateway runtime.
                  return javaBridge.validateAndDispatch(correlationId, request.body);
                }
                """);
        writeFixture(writer, "A3", service, """
                package finalcase.a3;
                import java.io.IOException;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Path;
                public final class TraceBridge%s {
                    private final Path adapterScript;
                    private final Path receiptDatabase;
                    public TraceBridge%s() {
                        this(Path.of("%s"), Path.of("receipt-log.sqlite"));
                    }
                    public TraceBridge%s(Path adapterScript, Path receiptDatabase) {
                        this.adapterScript = adapterScript;
                        this.receiptDatabase = receiptDatabase;
                    }
                    public String validateAndDispatch(String correlationId, String payload)
                            throws IOException, InterruptedException {
                        Process process = new ProcessBuilder(
                                "python3", adapterScript.toString(), receiptDatabase.toString(),
                                correlationId, payload).start();
                        String receiptId = new String(
                                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                        if (process.waitFor() != 0) throw new IOException("python receipt store failed");
                        return receiptId;
                    }
                }
                """.formatted(
                        variant.word(5), variant.word(5), adapter, variant.word(5)));
        writeFixture(writer, "A3", adapter, """
                import hashlib
                import sqlite3
                import sys

                def persist_receipt(correlation_id: str, payload: bytes, connection) -> str:
                    connection.execute(
                        "insert into receipt_log(correlation_id, payload) values (?, ?)",
                        (correlation_id, payload),
                    )
                    return hashlib.sha256(correlation_id.encode() + b":" + payload).hexdigest()

                def main(database_path: str, correlation_id: str, payload: str) -> None:
                    with sqlite3.connect(database_path) as connection:
                        connection.execute(
                            "create table if not exists receipt_log "
                            "(correlation_id text not null, payload blob not null)"
                        )
                        print(persist_receipt(correlation_id, payload.encode(), connection))

                if __name__ == "__main__":
                    main(*sys.argv[1:])
                """);
        writeFixture(writer, "A3", "src/gateway/decoy.ts",
                "export function previewReceipt(value) { return { preview: value }; }\n");
        writeFixture(writer, "A3", "workers/python/archive_store.py",
                "def archive_receipt(value):\n    return {'archived': value}\n");
        copyFixtureToReference(writer, "A3");
        Map<String, Object> oracle = retrievalOracle(writer, "A3", List.of(route, service, adapter),
                List.of("acceptReceipt", "validateAndDispatch", "persist_receipt"),
                List.of("x-correlation-id", "ProcessBuilder", "receipt_log"),
                List.of("grep_code", "read_file"), 8, true);
        oracle.put("callOrder", List.of("acceptReceipt", "validateAndDispatch", "persist_receipt"));
        writer.json(oraclePath("A3"), oracle);
        writer.json(referencePath("A3") + "/evidence.json", retrievalEvidence(
                "A3",
                Map.of("files", List.of(route, service, adapter),
                        "symbols", List.of("acceptReceipt", "validateAndDispatch", "persist_receipt"),
                        "callOrder", List.of("acceptReceipt", "validateAndDispatch", "persist_receipt"),
                        "evidence", List.of("x-correlation-id", "ProcessBuilder", "receipt_log")),
                List.of(
                        toolEvent(1, "grep_code", Map.of("pattern", "correlation-id"), route),
                        toolEvent(2, "read_file", Map.of("path", route), route),
                        toolEvent(3, "read_file", Map.of("path", service), service),
                        toolEvent(4, "read_file", Map.of("path", adapter), adapter))));
    }

    private static void materializeA4(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String entry = "src/main/java/finalcase/a4/ConsoleMain.java";
        String parser = "src/main/java/finalcase/a4/CommandParser.java";
        String tests = "src/test/java/finalcase/a4/CommandParserTest.java";
        String docs = "README.md";
        writeFixture(writer, "A4", entry, """
                package finalcase.a4;
                public final class ConsoleMain {
                    public static void main(String[] args) {
                        System.out.println(new CommandParser().parse(args));
                    }
                }
                """);
        writeFixture(writer, "A4", parser, """
                package finalcase.a4;
                public final class CommandParser {
                    public String parse(String[] args) {
                        if (args.length == 0) return "help";
                        if ("status".equals(args[0])) return "status";
                        return "unknown:" + args[0];
                    }
                }
                """);
        writeFixture(writer, "A4", tests,
                "package finalcase.a4;\npublic final class CommandParserTest { }\n");
        writeFixture(writer, "A4", docs,
                "# Harbor CLI " + variant.variantId()
                        + "\n\nCommands: status. Unknown commands are rejected.\n");
        writeFixture(writer, "A4", "docs/unrelated-export.md",
                "The export subcommand is intentionally unrelated.\n");
        copyFixtureToReference(writer, "A4");
        Map<String, Object> oracle = retrievalOracle(writer, "A4",
                List.of(entry, parser, tests, docs),
                List.of("ConsoleMain", "CommandParser", "CommandParserTest"),
                List.of("unknown:", "Commands:"),
                List.of("grep_code", "read_file"), 8, true);
        oracle.put("touchpoints", Map.of(
                "entry", entry, "parser", parser, "tests", tests, "docs", docs));
        writer.json(oraclePath("A4"), oracle);
        writer.json(referencePath("A4") + "/evidence.json", retrievalEvidence(
                "A4",
                Map.of("touchpoints", Map.of(
                                "entry", entry, "parser", parser, "tests", tests, "docs", docs),
                        "files", List.of(entry, parser, tests, docs),
                        "symbols", List.of("ConsoleMain", "CommandParser", "CommandParserTest"),
                        "evidence", List.of("unknown:", "Commands:")),
                List.of(
                        toolEvent(1, "grep_code", Map.of("pattern", "unknown:"), parser),
                        toolEvent(2, "read_file", Map.of("path", entry), entry),
                        toolEvent(3, "read_file", Map.of("path", parser), parser),
                        toolEvent(4, "read_file", Map.of("path", tests), tests),
                        toolEvent(5, "read_file", Map.of("path", docs), docs))));
    }

    private static void materializeG1(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        G1Problem problem = g1Problem(variant);
        copyFixtureToReference(writer, "G1");

        Map<String, Object> oracle = reasoningOracle(writer, "G1", "quantitative_science");
        oracle.put("answerKeys", List.of(
                "storedEnergyDropJ", "deliveredEnergyJ", "averagePowerW", "relations", "units"));
        oracle.put("expectedNumbers", Map.of(
                "storedEnergyDropJ", problem.storedEnergyDropJ(),
                "deliveredEnergyJ", problem.deliveredEnergyJ(),
                "averagePowerW", problem.averagePowerW()));
        oracle.put("numericTolerance", 0.000001d);
        oracle.put("expectedRelations", g1Relations());
        oracle.put("expectedUnits", Map.of(
                "storedEnergyDropJ", "J",
                "deliveredEnergyJ", "J",
                "averagePowerW", "W"));
        writer.json(oraclePath("G1"), oracle);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("storedEnergyDropJ", problem.storedEnergyDropJ());
        answer.put("deliveredEnergyJ", problem.deliveredEnergyJ());
        answer.put("averagePowerW", problem.averagePowerW());
        answer.put("relations", g1Relations());
        answer.put("units", Map.of(
                "storedEnergyDropJ", "J",
                "deliveredEnergyJ", "J",
                "averagePowerW", "W"));
        writer.json(referencePath("G1") + "/evidence.json",
                reasoningEvidence("G1", answer));
    }

    private static void materializeG2(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        G2Problem problem = g2Problem(variant);
        copyFixtureToReference(writer, "G2");

        Map<String, Object> oracle = reasoningOracle(writer, "G2", "dependency_dag");
        oracle.put("answerKeys", List.of(
                "scenarioId", "primaryCause", "orderedCauses", "dependencyEdges",
                "edgeEvidence", "evidenceIds", "normalizedOrder", "normalizedTimeline",
                "effectiveTimeoutMs", "upstreamCompletionMs", "uncertainty"));
        oracle.put("expectedScenarioId", problem.scenarioId());
        oracle.put("expectedPrimaryCause", problem.primaryCause());
        oracle.put("expectedOrderedCauses", problem.orderedCauses());
        oracle.put("expectedDependencyEdges", problem.dependencyEdges());
        oracle.put("expectedEdgeEvidence", problem.edgeEvidence());
        oracle.put("expectedEvidenceIds", problem.evidenceIds());
        oracle.put("expectedNormalizedOrder", problem.normalizedOrder());
        oracle.put("expectedNormalizedTimeline", problem.normalizedTimeline());
        oracle.put("expectedEffectiveTimeoutMs", problem.effectiveTimeoutMillis());
        oracle.put("expectedUpstreamCompletionMs", problem.upstreamCompletionMillis());
        oracle.put("expectedUncertainty", problem.uncertainty());
        writer.json(oraclePath("G2"), oracle);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("scenarioId", problem.scenarioId());
        answer.put("primaryCause", problem.primaryCause());
        answer.put("orderedCauses", problem.orderedCauses());
        answer.put("dependencyEdges", problem.dependencyEdges());
        answer.put("edgeEvidence", problem.edgeEvidence());
        answer.put("evidenceIds", problem.evidenceIds());
        answer.put("normalizedOrder", problem.normalizedOrder());
        answer.put("normalizedTimeline", problem.normalizedTimeline());
        answer.put("effectiveTimeoutMs", problem.effectiveTimeoutMillis());
        answer.put("upstreamCompletionMs", problem.upstreamCompletionMillis());
        answer.put("uncertainty", problem.uncertainty());
        writer.json(referencePath("G2") + "/evidence.json",
                reasoningEvidence("G2", answer));
    }

    private static Map<String, Object> reasoningOracle(PrivateSourceWriter writer,
                                                        String caseId,
                                                        String reasoningKind) throws IOException {
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("caseId", caseId);
        oracle.put("kind", "reasoning");
        oracle.put("reasoningKind", reasoningKind);
        oracle.put("requiresJudge", false);
        oracle.put("expectedToolProfile", "REASONING_ONLY");
        oracle.put("baselineFiles", hashesBelow(writer.root().resolve(fixturePath(caseId))));
        return oracle;
    }

    private static Map<String, Object> reasoningEvidence(String caseId,
                                                          Map<String, Object> answer)
            throws IOException {
        return referenceEnvelope(
                caseId, PrivateSourceWriter.JSON.writeValueAsString(answer), List.of());
    }

    private static String renderG1Prompt(G1Problem problem) {
        return """
                在一个理想化、题面自足的脉冲供能实验中，储能模块 %s 的电容为 %d mF，
                电压从 %d V 下降到 %d V。只有 %d%% 的电容能量减少量到达负载，供能持续 %d ms。
                使用关系式：电容储能 E=0.5*C*V^2，且 1 mF=0.001 F。所有中间量均使用题面精确值，
                最终数值误差不得超过 1e-6。不得调用任何工具。

                只返回一个 JSON 对象，且顶层必须恰好包含：
                storedEnergyDropJ、deliveredEnergyJ、averagePowerW、relations、units。
                relations 必须依次为：
                1. deltaE=0.5*(C_mF/1000)*(V_high^2-V_low^2)
                2. deliveredE=deltaE*efficiencyPercent/100
                3. averagePower=deliveredE/(durationMs/1000)
                units 必须把三个数值字段依次标为 J、J、W。
                """.formatted(
                problem.scenarioId(),
                problem.capacitanceMilliFarads(),
                problem.highVoltage(),
                problem.lowVoltage(),
                problem.efficiencyPercent(),
                problem.durationMillis());
    }

    private static String renderG2Prompt(G2Problem problem) {
        return """
                以下证据来自同一个 %s 服务故障，scenarioId=%s。时间戳保留原始时区，
                需先归一化到 UTC 再判断顺序。仅构造题面证据直接支持的 dependency 边。
                不得调用任何工具。

                %s

                只返回一个 JSON 对象，顶层必须恰好包含：
                scenarioId、primaryCause、orderedCauses、dependencyEdges、edgeEvidence、evidenceIds、
                normalizedOrder、normalizedTimeline、effectiveTimeoutMs、upstreamCompletionMs、uncertainty。
                cause node 只能从以下 ID 中选择：%s；以下为干扰项，不得选择：%s。
                primaryCause 只能从 required_restart_was_omitted、stale_environment_override_present、
                malformed_file_value、upstream_worker_became_unresponsive 中选择。
                uncertainty 只能从 none_material、internal_upstream_failure_mode_unobserved 中选择。
                orderedCauses 按主因到可观测故障的 dependency 顺序排列；不得把仅有时间先后的
                事件写成 dependency 边。dependencyEdges 使用 from->to，并按 from、to 在
                orderedCauses 中的位置排序。edgeEvidence 必须以每条 dependency edge 为 key，
                value 为直接支持该边的题面标签数组；不得缺边或添加无关标签。
                evidenceIds 按题面出现顺序列出所有用于结论的标签；normalizedOrder 按归一化后的
                时间先后排列所有带时间戳的证据。normalizedTimeline 与 normalizedOrder 一一对应，
                每项格式为 LABEL=UTC_TIMESTAMP，UTC_TIMESTAMP 必须使用题面精度和 Z 后缀。
                upstreamCompletionMs 在题面确认完成时为从 GW-START 到完成的毫秒数；若观察窗口内
                没有完成则为 null。
                """.formatted(
                problem.serviceName(),
                problem.scenarioId(),
                String.join("\n", problem.evidenceLines()),
                String.join("、", problem.allowedCauseNodes()),
                String.join("、", problem.distractorCauseNodes()));
    }

    private static G1Problem g1Problem(SeededVariant variant) {
        int capacitanceMilliFarads = variant.number(4, 4, 12);
        int highVoltage = variant.number(6, 24, 42);
        int lowVoltage = variant.number(8, 6, 15);
        int efficiencyPercent = variant.number(10, 14, 18) * 5;
        int durationMillis = variant.number(12, 8, 25) * 100;
        double storedEnergyDrop = 0.5d * (capacitanceMilliFarads / 1000.0d)
                * (highVoltage * highVoltage - lowVoltage * lowVoltage);
        double deliveredEnergy = storedEnergyDrop * efficiencyPercent / 100.0d;
        double averagePower = deliveredEnergy / (durationMillis / 1000.0d);
        return new G1Problem(
                "CAP-" + variant.word(0) + "-" + variant.shortToken(14),
                capacitanceMilliFarads,
                highVoltage,
                lowVoltage,
                efficiencyPercent,
                durationMillis,
                roundNine(storedEnergyDrop),
                roundNine(deliveredEnergy),
                roundNine(averagePower));
    }

    private static List<String> g1Relations() {
        return List.of(
                "deltaE=0.5*(C_mF/1000)*(V_high^2-V_low^2)",
                "deliveredE=deltaE*efficiencyPercent/100",
                "averagePower=deliveredE/(durationMs/1000)");
    }

    private static G2Problem g2Problem(SeededVariant variant) {
        int scenario = variant.number(1, 0, 3);
        int shortTimeoutMillis = variant.number(4, 9, 16) * 100;
        int upstreamLatencyMillis = shortTimeoutMillis + variant.number(6, 5, 12) * 100;
        int safeTimeoutMillis = upstreamLatencyMillis + variant.number(8, 8, 15) * 100;
        int utcHour = variant.number(10, 7, 12);
        int minute = variant.number(12, 15, 40);
        int offsetHours = variant.number(14, 7, 9);
        int pid = variant.number(16, 2000, 9000);
        String requestId = variant.shortToken(20);
        String serviceName = "Relay" + variant.word(0) + variant.shortToken(24);
        OffsetDateTime requestStart = OffsetDateTime.of(
                2032, 4, 17, utcHour, minute, 0, 0, ZoneOffset.UTC);
        ZoneOffset sourceOffset = ZoneOffset.ofHours(offsetHours);
        return switch (scenario) {
            case 0 -> g2RestartOmission(
                    variant, serviceName, requestId, pid, shortTimeoutMillis,
                    upstreamLatencyMillis, safeTimeoutMillis, requestStart, sourceOffset);
            case 1 -> g2EnvironmentOverride(
                    variant, serviceName, requestId, pid, shortTimeoutMillis,
                    upstreamLatencyMillis, safeTimeoutMillis, requestStart, sourceOffset);
            case 2 -> g2MalformedFile(
                    variant, serviceName, requestId, pid, shortTimeoutMillis,
                    upstreamLatencyMillis, safeTimeoutMillis, requestStart, sourceOffset);
            case 3 -> g2UpstreamOutage(
                    variant, serviceName, requestId, pid, upstreamLatencyMillis,
                    safeTimeoutMillis, requestStart, sourceOffset);
            default -> throw new IllegalStateException("unsupported G2 scenario: " + scenario);
        };
    }

    private static G2Problem g2RestartOmission(
            SeededVariant variant,
            String serviceName,
            String requestId,
            int pid,
            int shortTimeoutMillis,
            int upstreamLatencyMillis,
            int safeTimeoutMillis,
            OffsetDateTime start,
            ZoneOffset sourceOffset) {
        OffsetDateTime process = start.minusHours(1);
        OffsetDateTime config = start.minusMinutes(5);
        OffsetDateTime spec = start.minusMinutes(4);
        OffsetDateTime lifecycle = start.minusMinutes(1);
        OffsetDateTime effective = start.plusNanos(100_000_000L);
        OffsetDateTime timeout = start.plusNanos(shortTimeoutMillis * 1_000_000L);
        OffsetDateTime completed = start.plusNanos(upstreamLatencyMillis * 1_000_000L);
        List<String> nodes = List.of(
                "required_restart_was_omitted",
                "old_process_retained_cli_override",
                "effective_timeout_remained_old_cli_value",
                "gateway_deadline_fired_before_upstream_completion");
        List<TimedEvidence> timed = List.of(
                timed("PROC-1", process), timed("CFG-1", config), timed("SPEC-1", spec),
                timed("LIFE-1", lifecycle), timed("GW-START", start),
                timed("EFFECT-1", effective), timed("GW-TIMEOUT", timeout),
                timed("UP-DONE", completed));
        return new G2Problem(
                serviceName,
                "restart-omission-" + variant.shortToken(28),
                List.of(
                        "[POLICY-1] 配置优先级为 CLI > ENV > file > default；配置与发布定义只在新进程启动时加载。",
                        evidence("PROC-1", process, "PID " + pid
                                + " 启动，argv 含 --request-timeout-ms=" + shortTimeoutMillis + "。"),
                        evidence("CFG-1", config, "file request.timeout.ms 改为 "
                                + safeTimeoutMillis + "。"),
                        evidence("SPEC-1", spec, "发布定义移除 CLI timeout，且只对新进程生效。"),
                        evidence("LIFE-1", lifecycle, "supervisor 确认变更后应重启但未执行，PID "
                                + pid + " 仍存活。"),
                        evidence("GW-START", start.withOffsetSameInstant(sourceOffset),
                                "req-" + requestId + " 进入网关。"),
                        evidence("EFFECT-1", effective, "运行中 PID 仍解析到 CLI timeout="
                                + shortTimeoutMillis + "。"),
                        evidence("GW-TIMEOUT", timeout, "网关 deadline 触发。"),
                        evidence("UP-DONE", completed, "上游完成 req-" + requestId + "。")),
                nodes,
                List.of("clock_skew_was_primary", "config_file_parse_failed",
                        "upstream_never_completed"),
                "required_restart_was_omitted",
                nodes,
                List.of(
                        "required_restart_was_omitted->old_process_retained_cli_override",
                        "old_process_retained_cli_override->effective_timeout_remained_old_cli_value",
                        "effective_timeout_remained_old_cli_value->gateway_deadline_fired_before_upstream_completion"),
                Map.of(
                        "required_restart_was_omitted->old_process_retained_cli_override",
                        List.of("POLICY-1", "PROC-1", "SPEC-1", "LIFE-1"),
                        "old_process_retained_cli_override->effective_timeout_remained_old_cli_value",
                        List.of("PROC-1", "EFFECT-1"),
                        "effective_timeout_remained_old_cli_value->gateway_deadline_fired_before_upstream_completion",
                        List.of("GW-START", "EFFECT-1", "GW-TIMEOUT", "UP-DONE")),
                List.of("POLICY-1", "PROC-1", "CFG-1", "SPEC-1", "LIFE-1",
                        "GW-START", "EFFECT-1", "GW-TIMEOUT", "UP-DONE"),
                timed.stream().map(TimedEvidence::id).toList(),
                timed.stream().map(TimedEvidence::normalized).toList(),
                shortTimeoutMillis,
                upstreamLatencyMillis,
                "none_material");
    }

    private static G2Problem g2EnvironmentOverride(
            SeededVariant variant,
            String serviceName,
            String requestId,
            int pid,
            int shortTimeoutMillis,
            int upstreamLatencyMillis,
            int safeTimeoutMillis,
            OffsetDateTime start,
            ZoneOffset sourceOffset) {
        OffsetDateTime config = start.minusMinutes(12);
        OffsetDateTime environment = start.minusMinutes(10);
        OffsetDateTime process = start.minusMinutes(6);
        OffsetDateTime resolved = start.minusMinutes(5).plusNanos(100_000_000L);
        OffsetDateTime timeout = start.plusNanos(shortTimeoutMillis * 1_000_000L);
        OffsetDateTime completed = start.plusNanos(upstreamLatencyMillis * 1_000_000L);
        List<String> nodes = List.of(
                "stale_environment_override_present",
                "environment_value_won_precedence",
                "effective_timeout_was_shortened",
                "gateway_deadline_fired_before_upstream_completion");
        List<TimedEvidence> timed = List.of(
                timed("CFG-1", config), timed("ENV-1", environment), timed("PROC-1", process),
                timed("RESOLVE-1", resolved), timed("GW-START", start),
                timed("GW-TIMEOUT", timeout), timed("UP-DONE", completed));
        return new G2Problem(
                serviceName,
                "environment-override-" + variant.shortToken(28),
                List.of(
                        "[POLICY-1] 配置优先级为 CLI > ENV > file > default。",
                        evidence("CFG-1", config, "file request.timeout.ms=" + safeTimeoutMillis + "。"),
                        evidence("ENV-1", environment, "遗留部署变量 REQUEST_TIMEOUT_MS="
                                + shortTimeoutMillis + "。"),
                        evidence("PROC-1", process, "PID " + pid + " 启动，无 timeout CLI 参数。"),
                        evidence("RESOLVE-1", resolved, "解析日志确认 source=ENV，effectiveTimeoutMs="
                                + shortTimeoutMillis + "。"),
                        evidence("GW-START", start.withOffsetSameInstant(sourceOffset),
                                "req-" + requestId + " 进入网关。"),
                        evidence("GW-TIMEOUT", timeout, "网关 deadline 触发。"),
                        evidence("UP-DONE", completed, "上游完成 req-" + requestId + "。")),
                nodes,
                List.of("process_not_restarted", "config_file_parse_failed",
                        "upstream_never_completed"),
                "stale_environment_override_present",
                nodes,
                List.of(
                        "stale_environment_override_present->environment_value_won_precedence",
                        "environment_value_won_precedence->effective_timeout_was_shortened",
                        "effective_timeout_was_shortened->gateway_deadline_fired_before_upstream_completion"),
                Map.of(
                        "stale_environment_override_present->environment_value_won_precedence",
                        List.of("POLICY-1", "ENV-1", "PROC-1", "RESOLVE-1"),
                        "environment_value_won_precedence->effective_timeout_was_shortened",
                        List.of("ENV-1", "RESOLVE-1"),
                        "effective_timeout_was_shortened->gateway_deadline_fired_before_upstream_completion",
                        List.of("GW-START", "RESOLVE-1", "GW-TIMEOUT", "UP-DONE")),
                List.of("POLICY-1", "CFG-1", "ENV-1", "PROC-1", "RESOLVE-1",
                        "GW-START", "GW-TIMEOUT", "UP-DONE"),
                timed.stream().map(TimedEvidence::id).toList(),
                timed.stream().map(TimedEvidence::normalized).toList(),
                shortTimeoutMillis,
                upstreamLatencyMillis,
                "none_material");
    }

    private static G2Problem g2MalformedFile(
            SeededVariant variant,
            String serviceName,
            String requestId,
            int pid,
            int shortTimeoutMillis,
            int upstreamLatencyMillis,
            int safeTimeoutMillis,
            OffsetDateTime start,
            ZoneOffset sourceOffset) {
        OffsetDateTime config = start.minusMinutes(12);
        OffsetDateTime process = start.minusMinutes(6);
        OffsetDateTime parsed = start.minusMinutes(5);
        OffsetDateTime fallback = parsed.plusNanos(100_000_000L);
        OffsetDateTime timeout = start.plusNanos(shortTimeoutMillis * 1_000_000L);
        OffsetDateTime completed = start.plusNanos(upstreamLatencyMillis * 1_000_000L);
        List<String> nodes = List.of(
                "malformed_file_value",
                "config_file_parse_failed",
                "default_timeout_was_used",
                "gateway_deadline_fired_before_upstream_completion");
        List<TimedEvidence> timed = List.of(
                timed("CFG-1", config), timed("PROC-1", process), timed("PARSE-1", parsed),
                timed("FALLBACK-1", fallback), timed("GW-START", start),
                timed("GW-TIMEOUT", timeout), timed("UP-DONE", completed));
        return new G2Problem(
                serviceName,
                "malformed-file-" + variant.shortToken(28),
                List.of(
                        "[POLICY-1] 无 CLI/ENV 时读取 file；file 只接受裸整数毫秒，解析失败使用 default="
                                + shortTimeoutMillis + "。",
                        evidence("CFG-1", config, "file request.timeout.ms 写成带单位的值 "
                                + safeTimeoutMillis + "ms。"),
                        evidence("PROC-1", process, "PID " + pid + " 启动，无 timeout CLI/ENV。"),
                        evidence("PARSE-1", parsed, "解析器拒绝非裸整数值。"),
                        evidence("FALLBACK-1", fallback, "运行时采用 default，effectiveTimeoutMs="
                                + shortTimeoutMillis + "。"),
                        evidence("GW-START", start.withOffsetSameInstant(sourceOffset),
                                "req-" + requestId + " 进入网关。"),
                        evidence("GW-TIMEOUT", timeout, "网关 deadline 触发。"),
                        evidence("UP-DONE", completed, "上游完成 req-" + requestId + "。")),
                nodes,
                List.of("process_not_restarted", "environment_value_won_precedence",
                        "upstream_never_completed"),
                "malformed_file_value",
                nodes,
                List.of(
                        "malformed_file_value->config_file_parse_failed",
                        "config_file_parse_failed->default_timeout_was_used",
                        "default_timeout_was_used->gateway_deadline_fired_before_upstream_completion"),
                Map.of(
                        "malformed_file_value->config_file_parse_failed",
                        List.of("POLICY-1", "CFG-1", "PARSE-1"),
                        "config_file_parse_failed->default_timeout_was_used",
                        List.of("POLICY-1", "PARSE-1", "FALLBACK-1"),
                        "default_timeout_was_used->gateway_deadline_fired_before_upstream_completion",
                        List.of("GW-START", "FALLBACK-1", "GW-TIMEOUT", "UP-DONE")),
                List.of("POLICY-1", "CFG-1", "PROC-1", "PARSE-1", "FALLBACK-1",
                        "GW-START", "GW-TIMEOUT", "UP-DONE"),
                timed.stream().map(TimedEvidence::id).toList(),
                timed.stream().map(TimedEvidence::normalized).toList(),
                shortTimeoutMillis,
                upstreamLatencyMillis,
                "none_material");
    }

    private static G2Problem g2UpstreamOutage(
            SeededVariant variant,
            String serviceName,
            String requestId,
            int pid,
            int expectedHealthyLatencyMillis,
            int safeTimeoutMillis,
            OffsetDateTime start,
            ZoneOffset sourceOffset) {
        OffsetDateTime config = start.minusMinutes(12);
        OffsetDateTime process = start.minusMinutes(6);
        OffsetDateTime resolved = start.minusMinutes(5);
        OffsetDateTime heartbeat = start.minusSeconds(2);
        OffsetDateTime accepted = start.plusNanos(50_000_000L);
        OffsetDateTime timeout = start.plusNanos(safeTimeoutMillis * 1_000_000L);
        OffsetDateTime observed = timeout.plusNanos(500_000_000L);
        List<String> nodes = List.of(
                "upstream_worker_became_unresponsive",
                "upstream_request_never_completed",
                "gateway_deadline_fired_without_upstream_completion");
        List<TimedEvidence> timed = List.of(
                timed("CFG-1", config), timed("PROC-1", process), timed("RESOLVE-1", resolved),
                timed("UP-HEALTH", heartbeat), timed("GW-START", start),
                timed("UP-ACCEPT", accepted), timed("GW-TIMEOUT", timeout),
                timed("OBS-END", observed));
        return new G2Problem(
                serviceName,
                "upstream-outage-" + variant.shortToken(28),
                List.of(
                        "[POLICY-1] 无 CLI/ENV，file 是唯一 timeout 来源；健康基线完成耗时为 "
                                + expectedHealthyLatencyMillis + "ms。",
                        evidence("CFG-1", config, "file request.timeout.ms=" + safeTimeoutMillis + "。"),
                        evidence("PROC-1", process, "PID " + pid + " 启动，无 timeout CLI/ENV。"),
                        evidence("RESOLVE-1", resolved, "解析成功，effectiveTimeoutMs="
                                + safeTimeoutMillis + "。"),
                        evidence("UP-HEALTH", heartbeat, "上游 worker 心跳停止。"),
                        evidence("GW-START", start.withOffsetSameInstant(sourceOffset),
                                "req-" + requestId + " 进入网关。"),
                        evidence("UP-ACCEPT", accepted, "上游接收 req-" + requestId + "，之后无完成事件。"),
                        evidence("GW-TIMEOUT", timeout, "网关 deadline 触发。"),
                        evidence("OBS-END", observed, "观察窗口结束，上游仍无完成事件。")),
                nodes,
                List.of("process_not_restarted", "environment_value_won_precedence",
                        "config_file_parse_failed"),
                "upstream_worker_became_unresponsive",
                nodes,
                List.of(
                        "upstream_worker_became_unresponsive->upstream_request_never_completed",
                        "upstream_request_never_completed->gateway_deadline_fired_without_upstream_completion"),
                Map.of(
                        "upstream_worker_became_unresponsive->upstream_request_never_completed",
                        List.of("UP-HEALTH", "UP-ACCEPT", "OBS-END"),
                        "upstream_request_never_completed->gateway_deadline_fired_without_upstream_completion",
                        List.of("GW-START", "GW-TIMEOUT", "OBS-END")),
                List.of("POLICY-1", "CFG-1", "PROC-1", "RESOLVE-1", "UP-HEALTH",
                        "GW-START", "UP-ACCEPT", "GW-TIMEOUT", "OBS-END"),
                timed.stream().map(TimedEvidence::id).toList(),
                timed.stream().map(TimedEvidence::normalized).toList(),
                safeTimeoutMillis,
                null,
                "internal_upstream_failure_mode_unobserved");
    }

    private static String evidence(String id, OffsetDateTime timestamp, String text) {
        return "[" + id + "] " + G2_TIMESTAMP.format(timestamp) + " " + text;
    }

    private static TimedEvidence timed(String id, OffsetDateTime timestamp) {
        return new TimedEvidence(
                id,
                id + "=" + G2_TIMESTAMP.format(timestamp.withOffsetSameInstant(ZoneOffset.UTC)));
    }

    private static double roundNine(double value) {
        return Math.round(value * 1_000_000_000.0d) / 1_000_000_000.0d;
    }

    private record G1Problem(String scenarioId,
                             int capacitanceMilliFarads,
                             int highVoltage,
                             int lowVoltage,
                             int efficiencyPercent,
                             int durationMillis,
                             double storedEnergyDropJ,
                             double deliveredEnergyJ,
                             double averagePowerW) {
    }

    private record G2Problem(String serviceName,
                             String scenarioId,
                             List<String> evidenceLines,
                             List<String> allowedCauseNodes,
                             List<String> distractorCauseNodes,
                             String primaryCause,
                             List<String> orderedCauses,
                             List<String> dependencyEdges,
                             Map<String, List<String>> edgeEvidence,
                             List<String> evidenceIds,
                             List<String> normalizedOrder,
                             List<String> normalizedTimeline,
                             int effectiveTimeoutMillis,
                             Integer upstreamCompletionMillis,
                             String uncertainty) {
    }

    private record TimedEvidence(String id, String normalized) {
    }

    private static void materializeB1(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String target = "src/main/java/finalcase/b1/QuotaWindow.java";
        writeFixture(writer, "B1", "README.md", "# Closed quota window\n");
        writeFixture(writer, "B1", target, quotaWindow(false));
        writeFixture(writer, "B1", "src/test/java/finalcase/b1/PublicSmoke.java", """
                package finalcase.b1;
                public final class PublicSmoke {
                    public static void main(String[] args) {
                        if (!new QuotaWindow(10, 20).contains(15)) throw new AssertionError("interior");
                    }
                }
                """);
        copyFixtureToReference(writer, "B1");
        overwriteReference(writer, "B1", target, quotaWindow(true));
        writeEngineeringOracleAndEvidence(writer, "B1", List.of(target), 1, 1);
    }

    private static String quotaWindow(boolean solved) {
        String contains = solved ? "value >= lower && value <= upper" : "value > lower && value < upper";
        String overlaps = solved ? "lower <= other.upper && other.lower <= upper"
                : "lower < other.upper && other.lower < upper";
        return """
                package finalcase.b1;
                public final class QuotaWindow {
                    private final int lower;
                    private final int upper;
                    public QuotaWindow(int lower, int upper) {
                        if (lower > upper) throw new IllegalArgumentException("lower > upper");
                        this.lower = lower; this.upper = upper;
                    }
                    public boolean contains(int value) { return %s; }
                    public boolean overlaps(QuotaWindow other) { return %s; }
                }
                """.formatted(contains, overlaps);
    }

    private static void materializeB2(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String target = "unicode_records.py";
        writeFixture(writer, "B2", "README.md", "# Unicode record normalizer\n");
        writeFixture(writer, "B2", target, unicodeRecords(false));
        writeFixture(writer, "B2", "tests/test_public.py", """
                from unicode_records import normalize_records
                assert normalize_records(b"alpha\\nbeta\\n") == ["alpha", "beta"]
                """);
        writeFixture(writer, "B2", "data/sample.txt", "café\r\nnaïve\r\n");
        copyFixtureToReference(writer, "B2");
        overwriteReference(writer, "B2", target, unicodeRecords(true));
        writeEngineeringOracleAndEvidence(writer, "B2", List.of(target), 1, 1);
    }

    private static String unicodeRecords(boolean solved) {
        return solved ? """
                import unicodedata

                def normalize_records(raw: bytes) -> list[str]:
                    text = raw.decode("utf-8-sig")
                    return [unicodedata.normalize("NFC", line) for line in text.splitlines() if line]
                """ : """
                def normalize_records(raw: bytes) -> list[str]:
                    text = raw.decode("utf-8")
                    return [line for line in text.split("\\n") if line]
                """;
    }

    private static void materializeB3(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String repository = "src/repository.ts";
        String service = "src/service.ts";
        writeFixture(writer, "B3", "README.md", "# Async profile lookup\n");
        writeFixture(writer, "B3", repository, asyncRepository(false));
        writeFixture(writer, "B3", service, asyncService(false));
        writeFixture(writer, "B3", "src/controller.ts", """
                import { loadProfile } from "./service.ts";
                export async function profileEndpoint(id, repository) {
                  return await loadProfile(id, repository);
                }
                """);
        writeFixture(writer, "B3", "tests/public.test.ts",
                "// Happy-path coverage intentionally omits repository rejection.\n");
        writeFixture(writer, "B3", "package.json",
                "{\"name\":\"private-b3-" + variant.variantId()
                        + "\",\"private\":true,\"type\":\"module\"}\n");
        copyFixtureToReference(writer, "B3");
        overwriteReference(writer, "B3", repository, asyncRepository(true));
        overwriteReference(writer, "B3", service, asyncService(true));
        writeEngineeringOracleAndEvidence(writer, "B3",
                List.of(repository, service), 2, 2);
    }

    private static String asyncRepository(boolean solved) {
        return solved ? """
                export async function fetchProfile(id, transport) {
                  try {
                    return await transport.fetch(id);
                  } catch (error) {
                    throw error;
                  }
                }
                """ : """
                export async function fetchProfile(id, transport) {
                  return transport.fetch(id).catch(() => null);
                }
                """;
    }

    private static String asyncService(boolean solved) {
        return solved ? """
                import { fetchProfile } from "./repository.ts";
                export async function loadProfile(id, repository) {
                  try {
                    return await fetchProfile(id, repository);
                  } catch (error) {
                    throw Object.assign(new Error("profile lookup failed", { cause: error }), {
                      code: error.code,
                    });
                  }
                }
                """ : """
                import { fetchProfile } from "./repository.ts";
                export async function loadProfile(id, repository) {
                  const value = await fetchProfile(id, repository);
                  if (value === null) throw new Error("profile unavailable");
                  return value;
                }
                """;
    }

    private static void materializeB4(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String parser = "src/main/java/finalcase/b4/Parser.java";
        String main = "src/main/java/finalcase/b4/Main.java";
        String help = "src/main/java/finalcase/b4/Help.java";
        String tests = "src/test/java/finalcase/b4/PublicTest.java";
        String docs = "README.md";
        writeFixture(writer, "B4", parser, cliParser(false));
        writeFixture(writer, "B4", main, cliMain(false));
        writeFixture(writer, "B4", help, cliHelp(false));
        writeFixture(writer, "B4", docs,
                "# Inspectable CLI\n\nCommands: status. Unknown commands exit with code 2.\n");
        writeFixture(writer, "B4", tests, """
                package finalcase.b4;
                public final class PublicTest {
                    public static void main(String[] args) {
                        if (!"status".equals(new Parser().parse(new String[]{"status"}).name()))
                            throw new AssertionError("status");
                    }
                }
                """);
        copyFixtureToReference(writer, "B4");
        overwriteReference(writer, "B4", parser, cliParser(true));
        overwriteReference(writer, "B4", main, cliMain(true));
        overwriteReference(writer, "B4", help, cliHelp(true));
        overwriteReference(writer, "B4", tests, """
                package finalcase.b4;
                public final class PublicTest {
                    public static void main(String[] args) {
                        Parser parser = new Parser();
                        if (!"status".equals(parser.parse(new String[]{"status"}).name()))
                            throw new AssertionError("status");
                        Parser.Command inspect = parser.parse(
                                new String[]{"inspect", "--format", "json"});
                        if (!"inspect".equals(inspect.name()) || !"json".equals(inspect.format()))
                            throw new AssertionError("inspect");
                    }
                }
                """);
        overwriteReference(writer, "B4", docs,
                "# Inspectable CLI\n\nCommands: status, inspect --format text|json. "
                        + "Unknown commands exit with code 2.\n");
        writeEngineeringOracleAndEvidence(writer, "B4",
                List.of(parser, main, help, tests, docs), 5, 5);
    }

    private static String cliParser(boolean solved) {
        String inspect = solved ? """
                        if ("inspect".equals(args[0])) {
                            if (args.length != 3 || !"--format".equals(args[1])
                                    || !("text".equals(args[2]) || "json".equals(args[2])))
                                throw new IllegalArgumentException("inspect requires --format text|json");
                            return new Command("inspect", args[2]);
                        }
                """ : "";
        return """
                package finalcase.b4;
                public final class Parser {
                    public record Command(String name, String format) { }
                    public Command parse(String[] args) {
                        if (args.length == 1 && "status".equals(args[0]))
                            return new Command("status", "text");
                %s
                        throw new IllegalArgumentException("unknown command");
                    }
                }
                """.formatted(inspect);
    }

    private static String cliMain(boolean solved) {
        String inspect = solved
                ? "if (\"inspect\".equals(command.name())) { System.out.println(command.format()); return; }"
                : "";
        return """
                package finalcase.b4;
                public final class Main {
                    public static void main(String[] args) {
                        try {
                            Parser.Command command = new Parser().parse(args);
                            %s
                            System.out.println("ok");
                        } catch (IllegalArgumentException error) {
                            System.err.println(error.getMessage());
                            System.exit(2);
                        }
                    }
                }
                """.formatted(inspect);
    }

    private static String cliHelp(boolean solved) {
        return "package finalcase.b4; public final class Help { public static String text() { return \""
                + (solved ? "status\\ninspect --format text|json" : "status") + "\"; } }\n";
    }

    private static void materializeB5(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        String target = "src/main/java/finalcase/b5/OrderedFanout.java";
        writeFixture(writer, "B5", "README.md", "# Ordered concurrent fan-out\n");
        writeFixture(writer, "B5", target, orderedFanout(false));
        writeFixture(writer, "B5", "src/test/java/finalcase/b5/PublicSmoke.java",
                "package finalcase.b5; public final class PublicSmoke { }\n");
        copyFixtureToReference(writer, "B5");
        overwriteReference(writer, "B5", target, orderedFanout(true));
        writeEngineeringOracleAndEvidence(writer, "B5", List.of(target), 1, 1);
    }

    private static String orderedFanout(boolean solved) {
        if (!solved) {
            return """
                    package finalcase.b5;
                    import java.util.*;
                    import java.util.concurrent.*;
                    import java.util.function.Function;
                    public final class OrderedFanout {
                        public <T,R> List<R> map(List<T> input, Function<T,R> mapper) throws Exception {
                            ExecutorService pool = Executors.newFixedThreadPool(4);
                            try {
                                CompletionService<R> completed = new ExecutorCompletionService<>(pool);
                                for (T value : input) completed.submit(() -> mapper.apply(value));
                                List<R> output = new ArrayList<>();
                                for (int i = 0; i < input.size(); i++) output.add(completed.take().get());
                                return output;
                            } finally { pool.shutdownNow(); }
                        }
                    }
                    """;
        }
        return """
                package finalcase.b5;
                import java.util.*;
                import java.util.concurrent.*;
                import java.util.function.Function;
                public final class OrderedFanout {
                    public <T,R> List<R> map(List<T> input, Function<T,R> mapper) throws Exception {
                        ExecutorService pool = Executors.newFixedThreadPool(4);
                        try {
                            List<Future<R>> futures = new ArrayList<>();
                            for (T value : input) futures.add(pool.submit(() -> mapper.apply(value)));
                            List<R> output = new ArrayList<>();
                            for (Future<R> future : futures) output.add(future.get());
                            return output;
                        } finally { pool.shutdownNow(); }
                    }
                }
                """;
    }

    private static void materializeB6(PrivateSourceWriter writer, SeededVariant variant)
            throws IOException {
        Map<String, String> broken = b6Files(false, variant);
        Map<String, String> solved = b6Files(true, variant);
        for (Map.Entry<String, String> entry : broken.entrySet()) {
            writeFixture(writer, "B6", entry.getKey(), entry.getValue());
        }
        copyFixtureToReference(writer, "B6");
        List<String> changed = solved.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(broken.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        for (String path : changed) {
            overwriteReference(writer, "B6", path, solved.get(path));
        }
        writeEngineeringOracleAndEvidence(writer, "B6", changed, 8, 15);
    }

    private static Map<String, String> b6Files(boolean solved, SeededVariant variant) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("config/src/main/java/finalcase/b6/ConfigLoader.java", solved ? """
                package finalcase.b6;
                import java.util.Map;
                public final class ConfigLoader {
                    public static final String NEW_KEY = "delivery.timeout.ms";
                    public static final String OLD_KEY = "transport.timeout";
                    public long timeout(Map<String,String> values) {
                        String raw = values.containsKey(NEW_KEY) ? values.get(NEW_KEY) : values.get(OLD_KEY);
                        return raw == null ? 3000L : Long.parseLong(raw);
                    }
                }
                """ : """
                package finalcase.b6;
                import java.util.Map;
                public final class ConfigLoader {
                    public long timeout(Map<String,String> values) {
                        return Long.parseLong(values.getOrDefault("transport.timeout", "3000"));
                    }
                }
                """);
        files.put("api/src/main/java/finalcase/b6/DeliveryRequest.java", solved
                ? "package finalcase.b6; public record DeliveryRequest(String payload, long timeoutMillis) { }\n"
                : "package finalcase.b6; public record DeliveryRequest(String payload) { }\n");
        files.put("service/src/main/java/finalcase/b6/DeliveryService.java", solved ? """
                package finalcase.b6;
                public final class DeliveryService {
                    public String deliver(DeliveryRequest request) {
                        return request.payload() + "@" + request.timeoutMillis();
                    }
                }
                """ : """
                package finalcase.b6;
                public final class DeliveryService {
                    public String deliver(DeliveryRequest request) { return request.payload(); }
                }
                """);
        files.put("cli/src/main/java/finalcase/b6/DeliveryCli.java", solved
                ? "package finalcase.b6; public final class DeliveryCli { public String option() { return \"--delivery-timeout-ms\"; } }\n"
                : "package finalcase.b6; public final class DeliveryCli { public String option() { return \"--transport-timeout\"; } }\n");
        files.put("examples/application.properties", solved
                ? "delivery.timeout.ms=" + variant.number(8, 3200, 8900) + "\n"
                : "transport.timeout=" + variant.number(8, 3200, 8900) + "\n");
        files.put("examples/application-legacy.properties", solved
                ? "# compatibility fallback\ntransport.timeout=" + variant.number(10, 2200, 3100) + "\n"
                : "transport.timeout=" + variant.number(10, 2200, 3100) + "\n");
        files.put("docs/configuration.md", solved
                ? "# Delivery timeout\n\nUse delivery.timeout.ms; transport.timeout remains a one-version fallback.\n"
                : "# Transport timeout\n\nUse transport.timeout.\n");
        files.put("README.md", solved
                ? "# Delivery stack\n\ndelivery.timeout.ms has precedence; transport.timeout is a compatibility fallback.\n"
                : "# Delivery stack\n\nConfigure transport.timeout.\n");
        files.put("analytics/README.md", "unrelated-marker-" + variant.variantId() + "\n");
        files.put("tests/PublicSmoke.java", "// public migration smoke placeholder\n");
        return files;
    }

    private static Map<String, Object> retrievalOracle(PrivateSourceWriter writer,
                                                        String caseId,
                                                        List<String> paths,
                                                        List<String> symbols,
                                                        List<String> tokens,
                                                        List<String> tools,
                                                        int maxTools,
                                                        boolean requiresJudge) throws IOException {
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("caseId", caseId);
        oracle.put("kind", "retrieval");
        oracle.put("expectedPaths", paths);
        oracle.put("expectedSymbols", symbols);
        oracle.put("evidenceTokens", tokens);
        oracle.put("requiredToolsOrdered", tools);
        oracle.put("maxToolCalls", maxTools);
        oracle.put("readOnly", true);
        oracle.put("requiresJudge", requiresJudge);
        oracle.put("expectedToolProfile", expectedToolProfile(caseId));
        oracle.put("baselineFiles", hashesBelow(writer.root().resolve(fixturePath(caseId))));
        return oracle;
    }

    private static void writeEngineeringOracleAndEvidence(PrivateSourceWriter writer,
                                                           String caseId,
                                                           List<String> allowedChanges,
                                                           int minimumChanges,
                                                           int maximumChanges) throws IOException {
        Map<String, String> baseline = hashesBelow(writer.root().resolve(fixturePath(caseId)));
        Map<String, String> reference = hashesBelow(
                writer.root().resolve(referencePath(caseId)).resolve("workspace"));
        List<String> changes = changedFiles(baseline, reference);
        if (changes.size() < minimumChanges || changes.size() > maximumChanges
                || !allowedChanges.containsAll(changes)) {
            throw new IOException("reference change set violates recipe " + caseId + ": " + changes);
        }
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("caseId", caseId);
        oracle.put("kind", "engineering");
        oracle.put("baselineFiles", baseline);
        oracle.put("allowedChanges", allowedChanges);
        oracle.put("minimumChanges", minimumChanges);
        oracle.put("maximumChanges", maximumChanges);
        oracle.put("expectedToolProfile", expectedToolProfile(caseId));
        if ("B5".equals(caseId)) {
            oracle.put("maturityFailClosedReason",
                    "deterministic-concurrency-scheduler-not-frozen");
        }
        writer.json(oraclePath(caseId), oracle);

        List<Map<String, Object>> tools = List.of(
                toolEvent(1, "read_file", Map.of("path", allowedChanges.get(0)), "ok"),
                toolEvent(2, "write_file", Map.of("path", allowedChanges.get(0)), "ok"),
                toolEvent(3, "execute_command", Map.of("command", "run frozen checks"),
                        "命令执行完成 (exit code: 0)\nfrozen checks passed"));
        Map<String, Object> evidence = referenceEnvelope(
                caseId, "reference solution prepared for verifier diagnostics", tools);
        writer.json(referencePath(caseId) + "/evidence.json", evidence);
    }

    private static Map<String, Object> retrievalEvidence(String caseId,
                                                          Map<String, Object> answer,
                                                          List<Map<String, Object>> tools) throws IOException {
        return referenceEnvelope(
                caseId, PrivateSourceWriter.JSON.writeValueAsString(answer), tools);
    }

    static Map<String, Object> referenceEnvelope(String caseId,
                                                         String answer,
                                                         List<Map<String, Object>> tools) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 2);
        evidence.put("caseId", caseId);
        evidence.put("repeat", 1);
        evidence.put("mode", "react");
        evidence.put("toolProfile", expectedToolProfile(caseId));
        evidence.put("answer", answer);
        // Jackson serializes the nullable Metrics fields in a real envelope. LinkedHashMap permits
        // the same legal null placeholders; these are reference-report prototypes, not run evidence.
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

    private static String expectedToolProfile(String caseId) {
        if ("F1".equals(caseId)) return "FILE_ONLY";
        if ("F2".equals(caseId)) return "LOCAL_COMMAND";
        if ("F3".equals(caseId)) return "MOCK_MCP_FILE_ONLY";
        if ("D4".equals(caseId)) return "MOCK_WEB";
        if (List.of("D1", "D2", "D3", "F4").contains(caseId)) return "MOCK_MCP";
        if ("A2".equals(caseId)) {
            return "CODE_RAG";
        }
        if (caseId.startsWith("A")) {
            return "READ_ONLY";
        }
        if (caseId.startsWith("B")) {
            return "LOCAL_COMMAND";
        }
        if (caseId.startsWith("G")) {
            return "REASONING_ONLY";
        }
        throw new IllegalArgumentException("unknown reference prototype case: " + caseId);
    }

    static Map<String, Object> toolEvent(int sequence,
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

    private static void writeScoringContract(PrivateSourceWriter writer, String caseId)
            throws IOException {
        String verifierSha256 = sha256(writer.root().resolve("validators/final/" + caseId));
        List<String> toolchainFiles = verifierRuntimePaths(caseId);
        String toolchainSha256 = digestFiles(writer.root(), toolchainFiles);
        ScoringContract contract = scoringContract(caseId, verifierSha256, toolchainSha256);
        Path contractFile = writer.json(scoringContractPath(caseId), contract);
        if (!contract.equals(ScoringContract.load(contractFile))) {
            throw new IOException("strict scoring contract changed during read-back: " + caseId);
        }
    }

    static List<String> verifierRuntimePaths(String caseId) {
        if ("D1".equals(caseId)) return List.of(D1CaseMaterializer.RUNTIME_PATH);
        if ("D2".equals(caseId)) return List.of(D2CaseMaterializer.RUNTIME_PATH);
        if ("D3".equals(caseId)) return D3CaseMaterializer.RUNTIME_PATHS;
        if ("F4".equals(caseId)) return F4CaseMaterializer.RUNTIME_PATHS;
        if ("F1".equals(caseId)) return F1CaseMaterializer.RUNTIME_PATHS;
        if ("F2".equals(caseId)) return F2CaseMaterializer.RUNTIME_PATHS;
        if ("F3".equals(caseId)) return F3CaseMaterializer.RUNTIME_PATHS;
        if ("D4".equals(caseId)) return D4CaseMaterializer.RUNTIME_PATHS;
        if ("E1".equals(caseId)) return E1CaseMaterializer.RUNTIME_PATHS;
        if ("E2".equals(caseId)) return E2CaseMaterializer.RUNTIME_PATHS;
        return TerminalCaseMaterializers.supports(caseId)
                ? TerminalCaseMaterializers.toolchainFiles()
                : List.of("validators/final/_private/verify.py",
                        "validators/final/_private/hidden_checks.py");
    }

    static ScoringContract scoringContract(String caseId,
                                                    String verifierSha256,
                                                    String toolchainSha256) {
        if ("D1".equals(caseId)) return D1CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("D2".equals(caseId)) return D2CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("D3".equals(caseId)) return D3CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("F4".equals(caseId)) return F4CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("F1".equals(caseId)) return F1CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("F2".equals(caseId)) return F2CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("F3".equals(caseId)) return F3CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("D4".equals(caseId)) return D4CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("E1".equals(caseId)) return E1CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if ("E2".equals(caseId)) return E2CaseMaterializer.scoring(verifierSha256, toolchainSha256);
        if (TerminalCaseMaterializers.supports(caseId)) {
            return TerminalCaseMaterializers.scoringContract(
                    caseId, verifierSha256, toolchainSha256);
        }
        List<ScoringContract.AssertionRule> assertions = new ArrayList<>();
        List<ScoringContract.HardGateRule> hardGates = new ArrayList<>();
        List<ScoringContract.ComponentRule> components = new ArrayList<>();
        if (caseId.startsWith("A")) {
            String facts = "deterministicFacts";
            String trajectory = "toolTrajectory";
            int factsPoints = List.of("A3", "A4").contains(caseId) ? 70 : 80;
            int trajectoryPoints = List.of("A3", "A4").contains(caseId) ? 0 : 20;
            assertions.add(assertion(caseId, "paths", facts));
            assertions.add(assertion(caseId, "symbols", facts));
            assertions.add(assertion(caseId, "evidence", facts));
            assertions.add(assertion(caseId, "read_only", facts));
            assertions.add(assertion(caseId, "tool_order",
                    trajectoryPoints == 0 ? facts : trajectory));
            assertions.add(assertion(caseId, "tool_budget",
                    trajectoryPoints == 0 ? facts : trajectory));
            if (List.of("A1", "A2").contains(caseId)) {
                assertions.add(assertion(caseId, "read_region", trajectory));
            }
            if ("A2".equals(caseId)) {
                assertions.add(assertion(caseId, "exact_search_insufficient", trajectory));
                assertions.add(assertion(caseId, "semantic_search_grounded", trajectory));
            }
            if ("A3".equals(caseId)) {
                assertions.add(assertion(caseId, "call_order", facts));
            }
            if ("A4".equals(caseId)) {
                assertions.add(assertion(caseId, "touchpoints", facts));
            }
            hardGates.add(new ScoringContract.HardGateRule(caseId + ".readonly_mutation"));
            components.add(new ScoringContract.ComponentRule(
                    facts, factsPoints, ScoreSource.DETERMINISTIC));
            if (trajectoryPoints > 0) {
                components.add(new ScoringContract.ComponentRule(
                        trajectory, trajectoryPoints, ScoreSource.DETERMINISTIC));
            } else {
                String judge = "blindedSemanticJudge";
                assertions.add(assertion(caseId, "semantic_judge", judge));
                components.add(new ScoringContract.ComponentRule(judge, 30, ScoreSource.JUDGE));
            }
        } else if (caseId.startsWith("B")) {
            assertions.add(assertion(caseId, "hidden_tests", "hiddenTestsAndRegression"));
            assertions.add(assertion(caseId, "change_scope", "changeScope"));
            assertions.add(assertion(caseId, "command_evidence", "efficiency"));
            if ("B5".equals(caseId)) {
                assertions.add(assertion(caseId, "concurrency_maturity",
                        "hiddenTestsAndRegression"));
            }
            hardGates.add(new ScoringContract.HardGateRule(
                    caseId + ".protected_or_unrelated_file_modified"));
            components.add(new ScoringContract.ComponentRule(
                    "hiddenTestsAndRegression", 80, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "changeScope", 10, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "efficiency", 10, ScoreSource.DETERMINISTIC));
        } else if ("G1".equals(caseId)) {
            assertions.add(assertion(caseId, "structured_answer", "strictSchema"));
            assertions.add(assertion(caseId, "energy_delta", "storedEnergy"));
            assertions.add(assertion(caseId, "delivered_energy", "deliveredEnergy"));
            assertions.add(assertion(caseId, "average_power", "averagePower"));
            assertions.add(assertion(caseId, "relations_and_units", "relationsAndUnits"));
            assertions.add(assertion(caseId, "tool_count_consistency", "reasoningSurface"));
            assertions.add(assertion(caseId, "reasoning_only", "reasoningSurface"));
            hardGates.add(new ScoringContract.HardGateRule(
                    caseId + ".reasoning_surface_violation"));
            components.add(new ScoringContract.ComponentRule(
                    "strictSchema", 10, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "storedEnergy", 20, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "deliveredEnergy", 20, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "averagePower", 20, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "relationsAndUnits", 20, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "reasoningSurface", 10, ScoreSource.DETERMINISTIC));
        } else if ("G2".equals(caseId)) {
            assertions.add(assertion(caseId, "structured_answer", "strictSchema"));
            assertions.add(assertion(caseId, "scenario_binding", "scenarioBinding"));
            assertions.add(assertion(caseId, "primary_cause", "primaryDiagnosis"));
            assertions.add(assertion(caseId, "dependency_dag", "dependencyDag"));
            assertions.add(assertion(caseId, "evidence_binding", "evidenceAndTimeline"));
            assertions.add(assertion(caseId, "uncertainty", "uncertainty"));
            assertions.add(assertion(caseId, "tool_count_consistency", "reasoningSurface"));
            assertions.add(assertion(caseId, "reasoning_only", "reasoningSurface"));
            hardGates.add(new ScoringContract.HardGateRule(
                    caseId + ".reasoning_surface_violation"));
            components.add(new ScoringContract.ComponentRule(
                    "strictSchema", 10, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "scenarioBinding", 15, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "primaryDiagnosis", 15, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "dependencyDag", 20, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "evidenceAndTimeline", 25, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "uncertainty", 5, ScoreSource.DETERMINISTIC));
            components.add(new ScoringContract.ComponentRule(
                    "reasoningSurface", 10, ScoreSource.DETERMINISTIC));
        } else {
            throw new IllegalArgumentException("no strict scoring contract for " + caseId);
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

    static String digestFiles(Path root, List<String> relativePaths) throws IOException {
        MessageDigest digest = sha256Digest();
        for (String relative : relativePaths) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("scoring digest input is missing or unsafe: " + relative);
            }
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var input = Files.newInputStream(file, StandardOpenOption.READ)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
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
        Path target = writer.root().resolve(referencePath(caseId))
                .resolve("workspace").resolve(relative);
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("reference overwrite target is unsafe: " + target);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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

    private static String scoringContractPath(String caseId) {
        return "validators/final/_private/scoring-contracts/" + caseId + ".json";
    }

    private static String referencePath(String caseId) {
        return "references/final/" + caseId;
    }

    private static final String VERIFIER_RUNTIME = """
            import hashlib, json, math, os, pathlib, re, subprocess, sys

            case_id, workspace_raw, evidence_raw = sys.argv[1:]
            private_root = pathlib.Path(__file__).resolve().parent
            contract_file = private_root / "scoring-contracts" / (case_id + ".json")

            def reject_json_constant(value):
                raise ValueError("non-finite JSON number: " + value)

            def reject_duplicate_keys(pairs):
                result = {}
                for key, value in pairs:
                    if key in result:
                        raise ValueError("duplicate JSON key: " + key)
                    result[key] = value
                return result

            def finite_json(value):
                if isinstance(value, float):
                    return math.isfinite(value)
                if isinstance(value, dict):
                    return all(finite_json(item) for item in value.values())
                if isinstance(value, list):
                    return all(finite_json(item) for item in value)
                return True

            def strict_json_loads(value):
                parsed = json.loads(value, object_pairs_hook=reject_duplicate_keys,
                                    parse_constant=reject_json_constant)
                if not finite_json(parsed):
                    raise ValueError("non-finite JSON number")
                return parsed

            contract = strict_json_loads(contract_file.read_text(encoding="utf-8"))
            checks, violated_gates = {}, set()

            def check(name, condition, ref):
                checks[name] = {"pass": bool(condition), "evidenceRefs": [ref]}

            def flatten(value):
                if isinstance(value, dict):
                    return " ".join(str(k) + " " + flatten(v) for k, v in value.items())
                if isinstance(value, list):
                    return " ".join(flatten(v) for v in value)
                return str(value)

            def hash_file(path):
                value = hashlib.sha256()
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(16384), b""):
                        value.update(chunk)
                return value.hexdigest()

            def safe_files(root):
                if not root.is_absolute() or not root.is_dir() or root.is_symlink():
                    raise ValueError("unsafe workspace")
                result = {}
                for current, dirs, files in os.walk(root, followlinks=False):
                    base = pathlib.Path(current)
                    if any((base / name).is_symlink() for name in dirs):
                        raise ValueError("workspace symlink")
                    for name in files:
                        path = base / name
                        if path.is_symlink() or not path.is_file():
                            raise ValueError("workspace special file")
                        result[path.relative_to(root).as_posix()] = hash_file(path)
                return dict(sorted(result.items()))

            def tool_arguments(event):
                value = strict_json_loads(event["argumentsJson"])
                if not isinstance(value, dict):
                    raise ValueError("tool arguments are not an object")
                return value

            def nonnegative_integer(value):
                return isinstance(value, int) and not isinstance(value, bool) and value >= 0

            def trusted_events(evidence, oracle):
                expected_top_level = {
                    "schemaVersion", "caseId", "repeat", "mode", "toolProfile", "answer",
                    "llmMetrics", "toolExecutions", "verifierWorkspaceTreeSha256",
                    "verifierWorkspaceFileCount", "verifierWorkspaceTotalBytes",
                    "verifierBundleTreeSha256", "verifierBundleFileCount",
                    "verifierBundleTotalBytes",
                }
                if set(evidence) != expected_top_level:
                    raise ValueError("evidence envelope v2 top-level schema mismatch")
                if evidence.get("schemaVersion") != 2:
                    raise ValueError("only evidence envelope v2 is accepted")
                if (not nonnegative_integer(evidence.get("repeat")) or evidence["repeat"] <= 0
                        or evidence.get("mode") != "react"
                        or evidence.get("toolProfile") != oracle["expectedToolProfile"]
                        or not isinstance(evidence.get("answer"), str)):
                    raise ValueError("evidence episode metadata is invalid")
                metrics = evidence.get("llmMetrics")
                expected_metrics = {
                    "calls", "inputTokens", "outputTokens", "cachedInputTokens", "toolCalls",
                    "elapsedMillis", "successfulCalls", "resolvedModel",
                    "resolvedModelConsistent", "usageComplete", "systemPromptSha256",
                    "initialToolSchemaSha256", "requestFingerprintComplete",
                }
                if not isinstance(metrics, dict) or set(metrics) != expected_metrics:
                    raise ValueError("llmMetrics schema mismatch")
                for name in {"calls", "inputTokens", "outputTokens", "cachedInputTokens",
                             "toolCalls", "elapsedMillis", "successfulCalls"}:
                    if not nonnegative_integer(metrics[name]):
                        raise ValueError("llmMetrics numeric field is invalid")
                for name in {"resolvedModelConsistent", "usageComplete",
                             "requestFingerprintComplete"}:
                    if not isinstance(metrics[name], bool):
                        raise ValueError("llmMetrics boolean field is invalid")
                for name in {"resolvedModel", "systemPromptSha256", "initialToolSchemaSha256"}:
                    if metrics[name] is not None and not isinstance(metrics[name], str):
                        raise ValueError("llmMetrics optional identity is invalid")
                for name in {"verifierWorkspaceTreeSha256", "verifierBundleTreeSha256"}:
                    value = evidence[name]
                    if value is not None and not re.fullmatch(r"[0-9a-f]{64}", str(value)):
                        raise ValueError("snapshot digest is invalid")
                for name in {"verifierWorkspaceFileCount", "verifierWorkspaceTotalBytes",
                             "verifierBundleFileCount", "verifierBundleTotalBytes"}:
                    value = evidence[name]
                    if value is not None and not nonnegative_integer(value):
                        raise ValueError("snapshot count is invalid")
                events = evidence.get("toolExecutions")
                if not isinstance(events, list):
                    raise ValueError("toolExecutions is required")
                expected_event = {
                    "ordinal", "callId", "toolName", "argumentsJson", "resultPreview",
                    "resultSha256", "resultChars", "elapsedMillis", "timedOut", "successful",
                }
                for ordinal, event in enumerate(events, 1):
                    if (not isinstance(event, dict) or set(event) != expected_event
                            or not nonnegative_integer(event.get("ordinal"))
                            or event.get("ordinal") != ordinal):
                        raise ValueError("tool executions must be contiguous and ordered")
                    if not isinstance(event.get("callId"), str) or not isinstance(event.get("toolName"), str):
                        raise ValueError("tool identity is incomplete")
                    if not isinstance(event.get("argumentsJson"), str):
                        raise ValueError("tool argumentsJson is missing")
                    tool_arguments(event)
                    if not isinstance(event.get("resultPreview"), str):
                        raise ValueError("tool result preview is missing")
                    if not re.fullmatch(r"[0-9a-f]{64}", str(event.get("resultSha256", ""))):
                        raise ValueError("tool result digest is invalid")
                    if not nonnegative_integer(event.get("resultChars")):
                        raise ValueError("tool result length is invalid")
                    if not nonnegative_integer(event.get("elapsedMillis")):
                        raise ValueError("tool elapsed time is invalid")
                    if not isinstance(event.get("successful"), bool):
                        raise ValueError("typed tool success is required")
                    if not isinstance(event.get("timedOut"), bool):
                        raise ValueError("typed tool timeout is required")
                    if event["successful"] and event["timedOut"]:
                        raise ValueError("timed-out tool cannot be successful")
                    preview = event["resultPreview"]
                    if len(preview) > event["resultChars"]:
                        raise ValueError("tool preview exceeds result length")
                return events

            def successful(event):
                return event["successful"] is True and event["timedOut"] is False

            def ordered(required, actual):
                index = 0
                for item in actual:
                    if index < len(required) and item == required[index]:
                        index += 1
                return index == len(required)

            def emit(exit_code=0):
                assertions = []
                for rule in contract["assertions"]:
                    result = checks.get(rule["id"], {
                        "pass": False, "evidenceRefs": ["verifier:missing-assertion"]})
                    assertions.append({"id": rule["id"], **result})
                hard_gates = []
                for rule in contract["hardGates"]:
                    violated = rule["id"] in violated_gates
                    refs = (["workspace:tree-audit",
                             "trajectory:llm-tool-calls-equal-executions-and-zero"]
                            if rule["id"].endswith(".reasoning_surface_violation")
                            else ["workspace:hard-gate-audit"])
                    hard_gates.append({
                        "id": rule["id"], "violated": violated,
                        "evidenceRefs": refs,
                    })
                components = []
                for component in contract["components"]:
                    mapped = [rule for rule in contract["assertions"]
                              if rule["componentId"] == component["id"]]
                    if component["source"] == "JUDGE":
                        earned, refs = 0, ["judge:unavailable"]
                    else:
                        earned = (component["maxPoints"]
                                  if mapped and all(checks.get(rule["id"], {}).get("pass")
                                                    for rule in mapped) else 0)
                        refs = ["component:" + component["id"]]
                    components.append({
                        "id": component["id"], "earnedPoints": earned,
                        "maxPoints": component["maxPoints"], "source": component["source"],
                        "evidenceRefs": refs,
                    })
                report = {
                    "schemaVersion": 1,
                    "caseId": case_id,
                    "assertions": assertions,
                    "hardGates": hard_gates,
                    "components": components,
                    "verifierSha256": contract["verifierSha256"],
                    "toolchainSha256": contract["toolchainSha256"],
                }
                print(json.dumps(report, ensure_ascii=False, sort_keys=True,
                                 separators=(",", ":")))
                raise SystemExit(exit_code)

            try:
                if contract.get("schemaVersion") != 1 or contract.get("caseId") != case_id:
                    raise ValueError("scoring contract binding mismatch")
                workspace = pathlib.Path(workspace_raw).resolve(strict=True)
                evidence_file = pathlib.Path(evidence_raw).resolve(strict=True)
                oracle_file = private_root / "oracles" / (case_id + ".json")
                if evidence_file.is_symlink() or oracle_file.is_symlink():
                    raise ValueError("unsafe verifier input")
                evidence = strict_json_loads(evidence_file.read_text(encoding="utf-8"))
                oracle = strict_json_loads(oracle_file.read_text(encoding="utf-8"))
                if evidence.get("caseId") != case_id or oracle.get("caseId") != case_id:
                    raise ValueError("case binding mismatch")
                events = trusted_events(evidence, oracle)
                files = safe_files(workspace)
                baseline = oracle["baselineFiles"]

                if oracle["kind"] == "retrieval":
                    answer = evidence.get("answer")
                    if not isinstance(answer, str):
                        raise ValueError("envelope answer must be a string")
                    try:
                        answer = strict_json_loads(answer)
                    except (TypeError, ValueError):
                        answer = {}
                    answer_text = flatten(answer)
                    check(case_id + ".paths",
                          all(path in answer_text and path in files for path in oracle["expectedPaths"]),
                          "answer:files")
                    check(case_id + ".symbols",
                          all(symbol in answer_text for symbol in oracle["expectedSymbols"]),
                          "answer:symbols")
                    source_ok = all(
                        any(token in (workspace / path).read_text(encoding="utf-8")
                            for path in oracle["expectedPaths"])
                        for token in oracle["evidenceTokens"])
                    check(case_id + ".evidence",
                          source_ok and all(token in answer_text for token in oracle["evidenceTokens"]),
                          "answer:evidence")
                    successful_tools = [event["toolName"] for event in events if successful(event)]
                    check(case_id + ".tool_order",
                          ordered(oracle["requiredToolsOrdered"], successful_tools),
                          "trajectory:tool-order")
                    check(case_id + ".tool_budget",
                          len(events) <= oracle["maxToolCalls"], "trajectory:tool-budget")
                    for requirement in oracle.get("mustRead", []):
                        matching = []
                        for event in events:
                            if event["toolName"] != "read_file" or not successful(event):
                                continue
                            arguments = tool_arguments(event)
                            if arguments.get("path") == requirement["path"]:
                                matching.append(arguments)
                        region_ok = any(
                            nonnegative_integer(arguments.get("offset", 1))
                            and nonnegative_integer(arguments.get("limit", 2000))
                            and arguments.get("offset", 1) > 0
                            and arguments.get("limit", 2000) > 0
                            and arguments.get("offset", 1) <= requirement["offsetAtMost"]
                            and (arguments.get("offset", 1) + arguments.get("limit", 2000) - 1)
                                >= requirement["coveredLineAtLeast"]
                            for arguments in matching)
                        check(case_id + ".read_region", region_ok, "trajectory:read-file-range")
                    exact_rule = oracle.get("exactSearchInsufficient")
                    if exact_rule:
                        exact_events = [event for event in events
                                        if event["toolName"] == exact_rule["tool"]
                                        and successful(event)]
                        semantic_events = [event for event in events
                                           if event["toolName"] == "search_code"
                                           and successful(event)]
                        first_semantic_ordinal = (semantic_events[0]["ordinal"]
                                                  if semantic_events else None)
                        exact_ok = (bool(exact_events) and first_semantic_ordinal is not None
                                    and all(event["ordinal"] < first_semantic_ordinal
                                            and len(event["resultPreview"]) == event["resultChars"]
                                            and all(marker not in event["resultPreview"]
                                                    for marker in exact_rule["mustNotReveal"])
                                            for event in exact_events))
                        check(case_id + ".exact_search_insufficient", exact_ok,
                              "trajectory:exact-search-result")
                        semantic_ok = bool(semantic_events) and all(
                            marker in semantic_events[0]["resultPreview"]
                            for marker in oracle["semanticSearchMustReveal"])
                        check(case_id + ".semantic_search_grounded", semantic_ok,
                              "trajectory:semantic-search-result")
                    call_order = oracle.get("callOrder", [])
                    if call_order:
                        positions = [answer_text.find(token) for token in call_order]
                        check(case_id + ".call_order",
                              all(pos >= 0 for pos in positions) and positions == sorted(positions),
                              "answer:call-order")
                    touchpoints = oracle.get("touchpoints", {})
                    if touchpoints:
                        actual = answer.get("touchpoints", {}) if isinstance(answer, dict) else {}
                        check(case_id + ".touchpoints",
                              all(actual.get(role) == path for role, path in touchpoints.items()),
                              "answer:touchpoints")
                    mutation_tools = {"write_file", "create_project", "execute_command", "revert_turn"}
                    unchanged = files == baseline and not any(
                        event["toolName"] in mutation_tools for event in events)
                    check(case_id + ".read_only", unchanged, "workspace:tree-and-tools")
                    if not unchanged:
                        violated_gates.add(case_id + ".readonly_mutation")
                    if oracle.get("requiresJudge"):
                        check(case_id + ".semantic_judge", False, "judge:unavailable")
                        emit()
                    emit()

                if oracle["kind"] == "reasoning":
                    answer_raw = evidence.get("answer")
                    try:
                        answer = strict_json_loads(answer_raw) if isinstance(answer_raw, str) else None
                    except (TypeError, ValueError):
                        answer = None
                    structured = (isinstance(answer, dict)
                                  and set(answer) == set(oracle["answerKeys"]))
                    check(case_id + ".structured_answer", structured,
                          "answer:strict-json-schema")
                    answer = answer if isinstance(answer, dict) else {}

                    if oracle["reasoningKind"] == "quantitative_science":
                        expected = oracle["expectedNumbers"]
                        tolerance = oracle["numericTolerance"]

                        def close_number(name):
                            value = answer.get(name)
                            return (isinstance(value, (int, float))
                                    and not isinstance(value, bool)
                                    and math.isfinite(value)
                                    and math.isclose(value, expected[name], rel_tol=0.0,
                                                     abs_tol=tolerance))

                        check(case_id + ".energy_delta", close_number("storedEnergyDropJ"),
                              "answer:stored-energy-delta")
                        check(case_id + ".delivered_energy", close_number("deliveredEnergyJ"),
                              "answer:delivered-energy")
                        check(case_id + ".average_power", close_number("averagePowerW"),
                              "answer:average-power")
                        check(case_id + ".relations_and_units",
                              answer.get("relations") == oracle["expectedRelations"]
                              and answer.get("units") == oracle["expectedUnits"],
                              "answer:relations-and-units")
                    elif oracle["reasoningKind"] == "dependency_dag":
                        check(case_id + ".scenario_binding",
                              answer.get("scenarioId") == oracle["expectedScenarioId"]
                              and answer.get("effectiveTimeoutMs")
                                  == oracle["expectedEffectiveTimeoutMs"]
                              and answer.get("upstreamCompletionMs")
                                  == oracle["expectedUpstreamCompletionMs"],
                              "answer:seed-bound-scenario-and-values")
                        check(case_id + ".primary_cause",
                              answer.get("primaryCause") == oracle["expectedPrimaryCause"],
                              "answer:primary-cause")
                        expected_edges = oracle["expectedDependencyEdges"]
                        expected_edge_evidence = oracle["expectedEdgeEvidence"]
                        oracle_edges_bound = (
                            set(expected_edge_evidence) == set(expected_edges)
                            and all(isinstance(refs, list) and len(refs) > 0
                                    and all(ref in oracle["expectedEvidenceIds"] for ref in refs)
                                    for refs in expected_edge_evidence.values()))
                        check(case_id + ".dependency_dag",
                              answer.get("orderedCauses") == oracle["expectedOrderedCauses"]
                              and answer.get("dependencyEdges") == expected_edges
                              and answer.get("edgeEvidence") == expected_edge_evidence
                              and oracle_edges_bound,
                              "answer:evidence-bound-dependency-dag")
                        check(case_id + ".evidence_binding",
                              answer.get("evidenceIds") == oracle["expectedEvidenceIds"]
                              and answer.get("normalizedOrder")
                                  == oracle["expectedNormalizedOrder"]
                              and answer.get("normalizedTimeline")
                                  == oracle["expectedNormalizedTimeline"],
                              "answer:evidence-and-time-normalization")
                        check(case_id + ".uncertainty",
                              answer.get("uncertainty") == oracle["expectedUncertainty"],
                              "answer:uncertainty")
                    else:
                        raise ValueError("unsupported reasoning oracle")

                    reported_tool_calls = evidence["llmMetrics"]["toolCalls"]
                    count_consistent = reported_tool_calls == len(events)
                    both_zero = reported_tool_calls == 0 and len(events) == 0
                    unchanged = files == baseline
                    check(case_id + ".tool_count_consistency",
                          count_consistent and both_zero,
                          "trajectory:llm-tool-calls-equal-executions-and-zero")
                    check(case_id + ".reasoning_only", unchanged,
                          "workspace:unchanged")
                    if not (unchanged and count_consistent and both_zero):
                        violated_gates.add(case_id + ".reasoning_surface_violation")
                    emit()

                if oracle["kind"] == "engineering":
                    all_paths = sorted(set(baseline) | set(files))
                    changed = [path for path in all_paths if baseline.get(path) != files.get(path)]
                    allowed = set(oracle["allowedChanges"])
                    scope_ok = (oracle["minimumChanges"] <= len(changed)
                                <= oracle["maximumChanges"]
                                and all(path in allowed for path in changed))
                    check(case_id + ".change_scope", scope_ok, "workspace:derived-diff")
                    if any(path not in allowed for path in changed):
                        violated_gates.add(case_id + ".protected_or_unrelated_file_modified")
                    commands = [event for event in events
                                if event["toolName"] == "execute_command"]
                    command_ok = bool(commands)
                    for event in commands:
                        arguments = tool_arguments(event)
                        command = arguments.get("command")
                        command_text = " " + str(command).lower() + " "
                        if (not successful(event) or not isinstance(command, str)
                                or "exit code: 0" not in event["resultPreview"].lower()
                                or "skiptests" in command_text or " retry " in command_text):
                            command_ok = False
                    check(case_id + ".command_evidence", command_ok,
                          "trajectory:trusted-command-exit")
                    hidden = subprocess.run(
                        [sys.executable, str(private_root / "hidden_checks.py"),
                         case_id, str(workspace)],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=60,
                        env={"PATH": os.environ.get("PATH", ""), "LANG": "C.UTF-8"})
                    hidden_ok = hidden.returncode == 0
                    check(case_id + ".hidden_tests", hidden_ok, "hidden:test-exit")
                    if oracle.get("maturityFailClosedReason"):
                        check(case_id + ".concurrency_maturity", False,
                              "maturity:deterministic-scheduler-unavailable")
                    emit()
                raise ValueError("unsupported oracle")
            except SystemExit:
                raise
            except Exception:
                emit(2)
            """;

    private static final String HIDDEN_CHECKS = """
            import importlib.util, os, pathlib, shutil, subprocess, sys, tempfile, textwrap, unicodedata

            case_id, workspace_raw = sys.argv[1:]
            root = pathlib.Path(workspace_raw)
            sys.dont_write_bytecode = True

            def compile_and_run(extra_source, main_class):
                sources = [str(path) for path in root.rglob("*.java")]
                with tempfile.TemporaryDirectory(prefix="paicli-private-hidden-") as temporary:
                    temp = pathlib.Path(temporary)
                    hidden = temp / (main_class.rsplit(".", 1)[-1] + ".java")
                    hidden.write_text(extra_source, encoding="utf-8")
                    result = subprocess.run(
                        ["javac", "-encoding", "UTF-8", "-d", str(temp), *sources, str(hidden)],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=45)
                    if result.returncode:
                        raise SystemExit(1)
                    result = subprocess.run(
                        ["java", "-cp", str(temp), main_class],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=45)
                    raise SystemExit(0 if result.returncode == 0 else 1)

            if case_id == "B1":
                compile_and_run(textwrap.dedent('''
                    package finalcase.b1;
                    public final class HiddenB1 {
                        public static void main(String[] args) {
                            QuotaWindow value = new QuotaWindow(10, 20);
                            if (!value.contains(10) || !value.contains(15) || !value.contains(20)
                                    || value.contains(9) || value.contains(21))
                                throw new AssertionError();
                            QuotaWindow touching = new QuotaWindow(20, 30);
                            if (!value.overlaps(touching) || !touching.overlaps(value))
                                throw new AssertionError();
                            if (value.overlaps(new QuotaWindow(21, 30))
                                    || !new QuotaWindow(20, 20).overlaps(value)
                                    || !new QuotaWindow(10, 10).contains(10))
                                throw new AssertionError();
                            try {
                                new QuotaWindow(3, 2);
                                throw new AssertionError("invalid interval accepted");
                            } catch (IllegalArgumentException expected) { }
                        }
                    }
                '''), "finalcase.b1.HiddenB1")

            if case_id == "B2":
                public = subprocess.run(
                    [sys.executable, str(root / "tests/test_public.py")], cwd=root,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20,
                    env={"PATH": os.environ.get("PATH", ""), "LANG": "C.UTF-8",
                         "PYTHONDONTWRITEBYTECODE": "1", "PYTHONPATH": str(root)})
                if public.returncode:
                    raise SystemExit(1)
                spec = importlib.util.spec_from_file_location("candidate", root / "unicode_records.py")
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                cases = [
                    (b"\\xef\\xbb\\xbfCafe\\xcc\\x81\\r\\nStra\\xc3\\x9fe\\r\\n",
                     [unicodedata.normalize("NFC", "Cafe\\u0301"), "Straße"]),
                    ("A\\rB\\nC\\r\\nD\\n".encode(), ["A", "B", "C", "D"]),
                    ("e\\u0301\\n\\nΩ\\n".encode("utf-8"), ["é", "Ω"]),
                    (b"\\xef\\xbb\\xbf", []),
                ]
                raise SystemExit(0 if all(module.normalize_records(raw) == expected
                                          for raw, expected in cases) else 1)

            if case_id == "B3":
                repository = (root / "src/repository.ts").read_text(encoding="utf-8")
                service = (root / "src/service.ts").read_text(encoding="utf-8")
                controller = (root / "src/controller.ts").read_text(encoding="utf-8")
                static_ok = ("catch(() => null)" not in repository and "throw error" in repository
                             and "cause: error" in service and "code: error.code" in service
                             and "await loadProfile" in controller)
                if not static_ok or shutil.which("node") is None:
                    raise SystemExit(1)
                with tempfile.TemporaryDirectory(prefix="paicli-private-b3-") as temporary:
                    temp = pathlib.Path(temporary)
                    (temp / "repository.mjs").write_text(
                        repository.replace("./repository.ts", "./repository.mjs"), encoding="utf-8")
                    (temp / "service.mjs").write_text(
                        service.replace("./repository.ts", "./repository.mjs"), encoding="utf-8")
                    (temp / "controller.mjs").write_text(
                        controller.replace("./service.ts", "./service.mjs"), encoding="utf-8")
                    (temp / "hidden.mjs").write_text(textwrap.dedent('''
                        import { fetchProfile } from "./repository.mjs";
                        import { loadProfile } from "./service.mjs";
                        import { profileEndpoint } from "./controller.mjs";
                        for (const code of ["E_OFFLINE", "E_TIMEOUT", "E_CANCELLED"]) {
                          const original = Object.assign(new Error(code), { code });
                          const transport = { fetch: async () => { throw original; } };
                          let repositoryCaught;
                          try { await fetchProfile("p-1", transport); }
                          catch (error) { repositoryCaught = error; }
                          if (repositoryCaught !== original) process.exit(1);
                          for (const invoke of [loadProfile, profileEndpoint]) {
                            let caught;
                            try { await invoke("p-1", transport); } catch (error) { caught = error; }
                            if (!caught || caught.code !== code || caught.cause !== original
                                || caught.message !== "profile lookup failed") process.exit(1);
                          }
                        }
                        const success = { fetch: async id => ({ id, active: true }) };
                        const value = await profileEndpoint("p-2", success);
                        if (value.id !== "p-2" || value.active !== true) process.exit(1);
                    '''), encoding="utf-8")
                    result = subprocess.run(
                        ["node", str(temp / "hidden.mjs")],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
                    raise SystemExit(0 if result.returncode == 0 else 1)

            if case_id == "B4":
                docs = (root / "README.md").read_text(encoding="utf-8")
                tests = (root / "src/test/java/finalcase/b4/PublicTest.java").read_text(
                    encoding="utf-8")
                if "inspect --format text|json" not in docs or "inspect" not in tests:
                    raise SystemExit(1)
                hidden_source = textwrap.dedent('''
                    package finalcase.b4;
                    public final class HiddenB4 {
                        public static void main(String[] args) {
                            Parser parser = new Parser();
                            Parser.Command value = parser.parse(
                                new String[]{"inspect", "--format", "json"});
                            if (!"inspect".equals(value.name()) || !"json".equals(value.format()))
                                throw new AssertionError();
                            Parser.Command text = parser.parse(
                                new String[]{"inspect", "--format", "text"});
                            if (!"text".equals(text.format())) throw new AssertionError();
                            for (String[] invalid : new String[][]{
                                    {"missing"}, {"inspect"}, {"inspect", "--format", "xml"},
                                    {"inspect", "json"}}) {
                                try { parser.parse(invalid); throw new AssertionError(); }
                                catch (IllegalArgumentException expected) { }
                            }
                            try { parser.parse(new String[]{"missing"}); throw new AssertionError(); }
                            catch (IllegalArgumentException expected) { }
                            if (!Help.text().contains("inspect --format text|json"))
                                throw new AssertionError();
                        }
                    }
                ''')
                sources = [str(path) for path in root.rglob("*.java")]
                with tempfile.TemporaryDirectory(prefix="paicli-private-b4-") as temporary:
                    temp = pathlib.Path(temporary)
                    hidden = temp / "HiddenB4.java"
                    hidden.write_text(hidden_source, encoding="utf-8")
                    compiled = subprocess.run(
                        ["javac", "-encoding", "UTF-8", "-d", str(temp), *sources, str(hidden)],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=45)
                    if compiled.returncode:
                        raise SystemExit(1)
                    checks = [
                        (["java", "-cp", str(temp), "finalcase.b4.HiddenB4"], 0, "", ""),
                        (["java", "-cp", str(temp), "finalcase.b4.PublicTest"], 0, "", ""),
                        (["java", "-cp", str(temp), "finalcase.b4.Main", "status"], 0, "ok", ""),
                        (["java", "-cp", str(temp), "finalcase.b4.Main",
                          "inspect", "--format", "json"], 0, "json", ""),
                        (["java", "-cp", str(temp), "finalcase.b4.Main", "missing"],
                         2, "", "unknown command"),
                    ]
                    for argv, expected_exit, expected_stdout, expected_stderr in checks:
                        result = subprocess.run(
                            argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, timeout=30)
                        if (result.returncode != expected_exit
                                or (expected_stdout and result.stdout.strip() != expected_stdout)
                                or (expected_stderr and expected_stderr not in result.stderr)):
                            raise SystemExit(1)
                    raise SystemExit(0)

            if case_id == "B5":
                compile_and_run(textwrap.dedent('''
                    package finalcase.b5;
                    import java.util.*;
                    public final class HiddenB5 {
                        public static void main(String[] args) throws Exception {
                            for (int round = 1; round <= 40; round++) {
                                final int seed = round;
                                List<Integer> input = List.of(0, 1, 2, 3, 4, 5);
                                List<Integer> output = new OrderedFanout().map(input, value -> {
                                    try { Thread.sleep(Math.floorMod(seed * 17 - value * 11, 7)); }
                                    catch (InterruptedException error) {
                                        Thread.currentThread().interrupt(); throw new RuntimeException(error);
                                    }
                                    return value * 10 + seed;
                                });
                                List<Integer> expected =
                                    input.stream().map(value -> value * 10 + seed).toList();
                                if (!output.equals(expected)
                                        || new HashSet<>(output).size() != output.size())
                                    throw new AssertionError();
                            }
                        }
                    }
                '''), "finalcase.b5.HiddenB5")

            if case_id == "B6":
                loader = (root / "config/src/main/java/finalcase/b6/ConfigLoader.java").read_text()
                api = (root / "api/src/main/java/finalcase/b6/DeliveryRequest.java").read_text()
                service = (root / "service/src/main/java/finalcase/b6/DeliveryService.java").read_text()
                checks = [
                    "delivery.timeout.ms" in loader, "transport.timeout" in loader,
                    "containsKey(NEW_KEY)" in loader, "timeoutMillis" in api,
                    "request.timeoutMillis()" in service,
                    "delivery.timeout.ms" in (root / "docs/configuration.md").read_text(),
                    "delivery.timeout.ms=" in (root / "examples/application.properties").read_text(),
                    "compatibility fallback" in (root / "README.md").read_text(),
                    "unrelated-marker" in (root / "analytics/README.md").read_text(),
                ]
                if not all(checks):
                    raise SystemExit(1)
                compile_and_run(textwrap.dedent('''
                    package finalcase.b6;
                    import java.util.Map;
                    public final class HiddenB6 {
                        public static void main(String[] args) {
                            ConfigLoader loader = new ConfigLoader();
                            long newWins = loader.timeout(Map.of(
                                "delivery.timeout.ms", "9000", "transport.timeout", "1000"));
                            long oldFallsBack = loader.timeout(Map.of("transport.timeout", "2000"));
                            if (newWins != 9000L || oldFallsBack != 2000L)
                                throw new AssertionError();
                            DeliveryRequest request = new DeliveryRequest("payload", 7000L);
                            if (!new DeliveryService().deliver(request).endsWith("@7000"))
                                throw new AssertionError();
                        }
                    }
                '''), "finalcase.b6.HiddenB6")

            raise SystemExit(2)
            """;
}
