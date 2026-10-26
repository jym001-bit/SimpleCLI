package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import com.paicli.plan.PlanExecutionObserver;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.textSha256;
import static org.junit.jupiter.api.Assertions.*;

/** Native product + independent Python controls. Scripted provider, not an E1 model score. */
@Timeout(180)
class E1IndependentReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    enum Control { CORRECT, REORDERED, CHUNKED_READS, RELATIVE_PATHS, SERIALIZED, MISSING_DEPENDENCY,
        WRONG_BRANCH, WRONG_MERGE, MISSING_READ, PARTIAL_READ, FENCED_BRANCH, EXTRA_WRITE, INVALID_GRAPH,
        INVALID_DESCRIPTION, MISSING_DESCRIPTION, NBSP_ID,
        EMPTY_BRANCH, NULL_BRANCH, EMPTY_BRANCH_AFTER_READ, NULL_BRANCH_AFTER_READ,
        EMPTY_MERGE, NULL_MERGE, EMPTY_MERGE_AFTER_WRITE, NULL_MERGE_AFTER_WRITE,
        UNICODE_BLANK_MERGE_AFTER_WRITE, NBSP_MERGE_AFTER_WRITE }
    static boolean expectedPass(Control control) {
        return Set.of(Control.CORRECT, Control.REORDERED, Control.CHUNKED_READS, Control.RELATIVE_PATHS, Control.NBSP_ID,
                Control.EMPTY_MERGE_AFTER_WRITE, Control.NULL_MERGE_AFTER_WRITE,
                Control.UNICODE_BLANK_MERGE_AFTER_WRITE, Control.NBSP_MERGE_AFTER_WRITE).contains(control);
    }

    /** Test-only local faults, after two branches return; no provider failure or worker hook. */
    enum LocalFault { MERGE_BEFORE_INPUT, MERGE_BEFORE_REQUEST, MERGE_BEFORE_TOOL, MERGE_AFTER_TOOL }
    enum ReplanFault { LEFT_BEFORE_TOOL, RIGHT_BEFORE_TOOL, RIGHT_AFTER_TOOL, RIGHT_AFTER_ANSWER, RIGHT_TWICE,
        RIGHT_WITH_EXTRA_WRITE, RIGHT_REJECTED_REPLAN }
    static boolean replanPass(ReplanFault fault) {
        return fault != ReplanFault.RIGHT_WITH_EXTRA_WRITE && fault != ReplanFault.RIGHT_REJECTED_REPLAN;
    }

    @ParameterizedTest @EnumSource(ReplanFault.class)
    void independentlyReplaysNativeRecoveryWithoutCombiningDifferentPlans(ReplanFault fault) throws Exception {
        var input = nativeInput(Control.CORRECT, true, null, fault);
        var report = validReport(input);
        assertEquals(replanPass(fault), report.path("diagnosticSatisfied").asBoolean(), report.toString());
        assertEquals(input.metrics.calls(), input.metrics.successfulCalls(), "no provider failure is being forgiven");
        var planners = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (var turn : input.evidence.path("planAudit").path("providerTurns"))
            if (turn.path("binding").path("scope").asText().equals("planner")) planners.add(turn);
        assertEquals(fault == ReplanFault.RIGHT_TWICE ? 3 : 2, planners.size());
        String second = planners.get(1).path("messages").get(1).path("content").asText();
        assertTrue(second.contains("原任务: " + input.prompt + "\n失败原因: "));
        assertTrue(second.endsWith(fault == ReplanFault.LEFT_BEFORE_TOOL
                ? "已完成的任务:\n\n请制定新的执行计划，避开之前的问题。"
                : "已完成的任务:\n- task_1: LEFT\n\n请制定新的执行计划，避开之前的问题。"));
    }

    @ParameterizedTest @EnumSource(value=Control.class, names={"WRONG_MERGE", "MISSING_DEPENDENCY", "SERIALIZED"})
    void recoveredPlanStillMustSatisfyAllOriginalAssertions(Control control) throws Exception {
        var input = nativeInput(control, true, null, ReplanFault.RIGHT_BEFORE_TOOL);
        assertFalse(validReport(input).path("diagnosticSatisfied").asBoolean());
    }

    @ParameterizedTest @EnumSource(value=Control.class, names={"REORDERED", "CHUNKED_READS", "RELATIVE_PATHS"})
    void reorderedAndChunkedRecoveryUsesFreshPlanAndTaskScopes(Control control) throws Exception {
        var input = nativeInput(control, true, null, ReplanFault.RIGHT_AFTER_TOOL);
        assertTrue(validReport(input).path("diagnosticSatisfied").asBoolean());
    }

    @Test void replanDoesNotEraseEarlierForbiddenToolEvenIfFinalFileSetIsClean() throws Exception {
        var input = nativeInput(Control.CORRECT, true, null, ReplanFault.RIGHT_WITH_EXTRA_WRITE);
        Files.delete(input.workspace.resolve("attempt-note.txt")); // Test-owned control artifact only.
        var report = validReport(input);
        assertFalse(report.path("hardGates").path("workspace_mutation").asBoolean());
        assertTrue(report.path("hardGates").path("forbidden_tool_or_path").asBoolean());
        assertTrue(report.path("checks").path("artifact").asBoolean());
        assertFalse(report.path("diagnosticSatisfied").asBoolean());
    }

    @Test void completedReplanCannotBorrowSourceObservationFromEarlierAttempt() throws Exception {
        var input = nativeInput(Control.MISSING_READ, true, null, ReplanFault.RIGHT_AFTER_ANSWER);
        // First attempt reads both sources. The completed replan deliberately skips them.
        var report = validReport(input);
        assertFalse(report.path("checks").path("source_observed").asBoolean());
        assertTrue(report.path("checks").path("artifact").asBoolean());
        String firstId = firstEvent(input.evidence, "PlanStarted").path("executionId").asText();
        Set<String> observed = new HashSet<>();
        for (var turn : input.evidence.path("planAudit").path("providerTurns")) {
            if (!turn.path("binding").path("scope").asText().startsWith(firstId + ":")) continue;
            for (var message : turn.path("messages")) if (message.path("role").asText().equals("tool"))
                observed.add(turn.path("binding").path("scope").asText());
        }
        assertEquals(2, observed.size(), "both earlier source results really reached provider requests");
    }

    @Test void selfConsistentReplanGoalAndCompletedListTamperingStillHasNoVerdict() throws Exception {
        var input = nativeInput(Control.CORRECT, true, null, ReplanFault.RIGHT_BEFORE_TOOL);
        assertTrue(validReport(input).path("diagnosticSatisfied").asBoolean());
        for (var change : List.<java.util.function.UnaryOperator<String>>of(
                g -> g.replace("- task_1: LEFT\n", "- task_2: RIGHT\n"),
                g -> g.replace("- task_1: LEFT\n", ""),
                g -> g.replace("原任务: " + input.prompt, "原任务: substituted task"))) {
            var bad = input.evidence.deepCopy(); rewriteSecondPlanGoal(bad, change);
            JSON.writeValue(input.evidenceFile.toFile(), bad);
            var result = replay(input); assertEquals(2, result.exitCode(), result.stdout());
            assertTrue(result.stderr().contains("replanning goal/completed tasks differ"), result.stderr());
        }
    }

    @Test void forgedTopologicalOrderCannotJustifyForgedCompletedList() throws Exception {
        var input = nativeInput(Control.CORRECT, true, null, ReplanFault.RIGHT_BEFORE_TOOL);
        var bad = input.evidence.deepCopy();
        rewriteSecondPlanGoal(bad, g -> g.replace("- task_1: LEFT\n", ""));
        // Still a legal topological order, but not native insertion-ordered DFS.
        firstEvent(bad, "PlanStarted").putArray("executionOrder").add("task_2").add("task_1").add("task_3");
        JSON.writeValue(input.evidenceFile.toFile(), bad);
        var result = replay(input); assertEquals(2, result.exitCode(), result.stdout());
        assertTrue(result.stderr().contains("native execution order differs"), result.stderr());
    }

    private static void rewriteSecondPlanGoal(ObjectNode evidence, java.util.function.UnaryOperator<String> change) {
        ObjectNode second = null; int count = 0;
        for (var turn : evidence.path("planAudit").path("providerTurns"))
            if (turn.path("binding").path("scope").asText().equals("planner") && ++count == 2) { second = (ObjectNode)turn; break; }
        assertNotNull(second);
        String prefix = "请为以下任务制定执行计划：\n";
        String oldGoal = second.path("messages").get(1).path("content").asText().substring(prefix.length());
        String newGoal = change.apply(oldGoal);
        ((ObjectNode)second.path("messages").get(1)).put("content", prefix + newGoal);
        ((ObjectNode)second.path("binding")).put("inputSha256", textSha256(prefix + newGoal));
        String id = null; int starts = 0;
        for (var observed : evidence.path("planAudit").path("events")) if (observed.path("eventType").asText().equals("PlanStarted") && ++starts == 2) {
            id = observed.path("event").path("executionId").asText();
            ((ObjectNode)observed.path("event")).set("goal", JSON.valueToTree(PlanExecutionObserver.TextFingerprint.of(newGoal))); break;
        }
        assertNotNull(id);
        Map<String, String> inputs = new HashMap<>();
        for (var turn : evidence.path("planAudit").path("providerTurns")) {
            String scope = turn.path("binding").path("scope").asText();
            if (scope.startsWith(id + ":")) {
                for (var message : turn.path("messages")) if (message.path("role").asText().equals("user")) {
                    String text = message.path("content").asText().replace("总目标：" + oldGoal + "\n", "总目标：" + newGoal + "\n");
                    ((ObjectNode)message).put("content", text); inputs.put(scope.substring(id.length() + 1), text);
                    ((ObjectNode)turn.path("binding")).put("inputSha256", textSha256(text));
                }
            }
            int ordinal = turn.path("ordinal").asInt();
            ((ObjectNode)evidence.path("scopedRequestFingerprints").path("requests").get(ordinal - 1)).set("binding", turn.path("binding").deepCopy());
        }
        for (var observed : evidence.path("planAudit").path("events"))
            if (observed.path("eventType").asText().equals("TaskInputPrepared") && observed.path("event").path("executionId").asText().equals(id))
                ((ObjectNode)observed.path("event")).set("userText", JSON.valueToTree(PlanExecutionObserver.TextFingerprint.of(inputs.get(observed.path("event").path("taskId").asText()))));
    }

    @ParameterizedTest @EnumSource(LocalFault.class)
    void localMergeExceptionsHaveTaskVerdictsRatherThanInvalidEvidence(LocalFault fault) throws Exception {
        var input = nativeInput(Control.CORRECT, true, fault);
        var report = validReport(input);
        assertEquals(fault == LocalFault.MERGE_AFTER_TOOL, report.path("diagnosticSatisfied").asBoolean(), report.toString());
        assertEquals(fault == LocalFault.MERGE_AFTER_TOOL, report.path("checks").path("artifact").asBoolean());
        assertEquals(input.metrics.calls(), input.metrics.successfulCalls(), "fault is local, not a provider failure");
        assertFalse(report.path("publicationEligible").asBoolean()); assertTrue(report.path("formalScore").isNull());
    }

    @ParameterizedTest @EnumSource(value=Control.class, names={"WRONG_MERGE", "EXTRA_WRITE", "MISSING_DEPENDENCY", "SERIALIZED"})
    void localExceptionAfterWriteDoesNotHideWrongArtifactOrEarlierViolations(Control control) throws Exception {
        var input = nativeInput(control, true, LocalFault.MERGE_AFTER_TOOL);
        var report = validReport(input);
        assertFalse(report.path("diagnosticSatisfied").asBoolean(), report.toString());
        if (control == Control.EXTRA_WRITE) assertTrue(report.path("hardGates").path("workspace_mutation").asBoolean());
    }

    @Test void terminalExceptionStillRequiresUnmodifiedCompleteToolEvidence() throws Exception {
        var input = nativeInput(Control.CORRECT, true, LocalFault.MERGE_AFTER_TOOL);
        assertTrue(validReport(input).path("diagnosticSatisfied").asBoolean());
        List<Consumer<ObjectNode>> mutations = List.of(
                n -> ((ObjectNode)n.path("toolExecutions").get(2)).put("resultSha256", "0".repeat(64)),
                n -> ((ObjectNode)n.path("toolExecutions").get(2)).put("resultChars", 20_000),
                n -> n.withArray("toolExecutions").remove(2),
                n -> terminalExit(n).putNull("exceptionType"),
                n -> terminalExit(n).put("exceptionType", ""),
                n -> terminalExit(n).with("result").put("present", true),
                n -> terminalBatch(n).withArray("results").remove(0),
                n -> ((ObjectNode)terminalBatch(n).path("results").get(0)).with("result").put("utf8Bytes", 1),
                n -> ((ObjectNode)terminalBatch(n).path("results").get(0)).put("successful", false));
        for (var mutation : mutations) {
            var bad = input.evidence.deepCopy(); mutation.accept(bad); JSON.writeValue(input.evidenceFile.toFile(), bad);
            var result = replay(input); assertEquals(2, result.exitCode(), result.stdout()); assertTrue(result.stdout().isBlank());
        }
    }

    @Test void terminalDisplayPreviewIsNotUsedAsRawResultOrModelObservation() throws Exception {
        var input = nativeInput(Control.CORRECT, true, LocalFault.MERGE_AFTER_TOOL);
        var good = validReport(input);
        var changed = input.evidence.deepCopy();
        ((ObjectNode)changed.path("toolExecutions").get(2)).put("resultPreview", "[REDACTED]");
        JSON.writeValue(input.evidenceFile.toFile(), changed);
        assertEquals(good, validReport(input), "display preview is not raw SHA-bound text");
    }

    private static ObjectNode terminalExit(ObjectNode evidence) {
        for (var event : evidence.path("planAudit").path("events"))
            if (event.path("eventType").asText().equals("TaskExited") && event.path("event").path("kind").asText().equals("THREW"))
                return (ObjectNode)event.path("event");
        throw new AssertionError("missing local exception");
    }
    private static ObjectNode terminalBatch(ObjectNode evidence) {
        for (var event : evidence.path("planAudit").path("events"))
            if (event.path("eventType").asText().equals("ToolBatchReturned") && event.path("event").path("taskId").asText().equals("task_3"))
                return (ObjectNode)event.path("event");
        throw new AssertionError("missing merge batch");
    }

    @ParameterizedTest @EnumSource(Control.class)
    void independentlyReplaysNativePlanAndRejectsCorrectArtifactShortcuts(Control control) throws Exception {
        var input = nativeInput(control);
        var report = validReport(input);
        assertEquals(expectedPass(control), report.path("diagnosticSatisfied").asBoolean(), report.toString());
        assertFalse(report.path("publicationEligible").asBoolean()); assertTrue(report.path("formalScore").isNull());
        if (control == Control.SERIALIZED) {
            assertTrue(report.path("checks").path("artifact").asBoolean());
            assertFalse(report.path("checks").path("overlapping_lifecycles").asBoolean());
        }
        if (control == Control.MISSING_READ || control == Control.PARTIAL_READ || control == Control.MISSING_DEPENDENCY || control == Control.FENCED_BRANCH)
            assertTrue(report.path("checks").path("artifact").asBoolean(), "deliberate answer-only shortcut still has correct file");
        if (control == Control.EXTRA_WRITE) assertTrue(report.path("hardGates").path("workspace_mutation").asBoolean());
    }

    @Test void contradictoryEvidenceHasNoVerdictAndCannotBeTurnedIntoProductZero() throws Exception {
        var input = nativeInput(Control.CORRECT);
        assertTrue(validReport(input).path("diagnosticSatisfied").asBoolean(), "the baseline must qualify before mutation tests");
        List<Consumer<ObjectNode>> mutations = List.of(
                n -> n.put("diagnosticSatisfied", true), n -> n.put("sourceSha256", "0".repeat(64)),
                n -> n.put("relayVersion", 9.0), n -> n.put("relayVersion", 13),
                n -> n.put("relayVersion", 10.0), n -> n.put("relayVersion", "10"), n -> n.put("modelToolCalls", 0),
                n -> n.with("planAudit").put("schemaVersion", 1), n -> n.with("planAudit").put("failed", true),
                n -> n.with("planAudit").withArray("events").remove(0),
                n -> firstTaskTurn(n).put("eventsSeenAtRequest", 0),
                n -> firstTaskTurn(n).put("requestStartedNanos", 0),
                n -> firstTaskTurn(n).put("responseCompletedNanos", Long.MAX_VALUE),
                n -> firstTaskTurn(n).remove("tools"),
                n -> firstTaskTurn(n).with("binding").put("scope", "planner"),
                n -> ((ObjectNode)n.path("scopedRequestFingerprints").path("requests").get(1)).put("systemPromptSha256", "0".repeat(64)),
                n -> ((ObjectNode)n.path("scopedRequestFingerprints").path("requests").get(1)).put("toolSchemaSha256", "0".repeat(64)),
                n -> ((ObjectNode)n.path("toolExecutions").get(0)).put("argumentsJson", "{}"),
                n -> ((ObjectNode)n.path("toolExecutions").get(0)).put("resultChars", 0),
                n -> ((ObjectNode)n.path("toolExecutions").get(0)).put("successful", "true"),
                n -> firstEvent(n, "ToolBatchReturned").withArray("results").remove(0),
                n -> ((ObjectNode)firstEvent(n, "ToolBatchReturned").path("results").get(0)).with("arguments").put("sha256", "0".repeat(64)),
                n -> firstEvent(n, "TaskExited").with("result").put("sha256", "0".repeat(64)),
                n -> ((ObjectNode)firstEvent(n, "PlanStarted").path("tasks").get(0)).put("type", "COMMAND"));
        int index = 0;
        for (var mutation : mutations) {
            ObjectNode bad = input.evidence.deepCopy(); mutation.accept(bad);
            JSON.writeValue(input.evidenceFile.toFile(), bad);
            var run = replay(input); assertEquals(2, run.exitCode(), "mutation " + index++ + ": " + run.stdout());
            assertTrue(run.stdout().isBlank());
        }
        JSON.writeValue(input.evidenceFile.toFile(), input.evidence);
        String original = Files.readString(input.evidenceFile);
        Files.writeString(input.evidenceFile, original.replaceFirst("\\{", "{\"caseId\":\"E1\","));
        assertEquals(2, replay(input).exitCode(), "duplicate fields are rejected before replay");
    }

    @Test void missingActualDependencyTextIsValidFailureEvenIfAllItsDigestsAgree() throws Exception {
        var input = nativeInput(Control.CORRECT); ObjectNode bad = input.evidence.deepCopy();
        String mergeScope = null, replacement = null;
        for (var turn : bad.path("planAudit").path("providerTurns")) {
            for (var message : turn.path("messages")) if (message.path("role").asText().equals("user")
                    && message.path("content").asText().contains("当前任务：MERGE\n")) {
                replacement = message.path("content").asText().replaceAll("\\{[^\\r\\n]*\\\"branch\\\":\\\"right\\\"[^\\r\\n]*}\\n", "");
                ((ObjectNode)message).put("content", replacement); mergeScope = turn.path("binding").path("scope").asText();
                ((ObjectNode)turn.path("binding")).put("inputSha256", textSha256(replacement));
            }
        }
        assertNotNull(replacement); assertFalse(replacement.contains("\"branch\":\"right\""));
        for (var request : bad.path("scopedRequestFingerprints").path("requests"))
            if (request.path("binding").path("scope").asText().equals(mergeScope))
                ((ObjectNode)request.path("binding")).put("inputSha256", textSha256(replacement));
        for (var observed : bad.path("planAudit").path("events")) if (observed.path("eventType").asText().equals("TaskInputPrepared")
                && mergeScope.endsWith(":" + observed.path("event").path("taskId").asText()))
            ((ObjectNode)observed.path("event")).set("userText", JSON.valueToTree(PlanExecutionObserver.TextFingerprint.of(replacement)));
        JSON.writeValue(input.evidenceFile.toFile(), bad);
        var report = validReport(input); assertTrue(report.path("checks").path("artifact").asBoolean());
        assertFalse(report.path("checks").path("complete_dependency_inputs").asBoolean());
        assertFalse(report.path("diagnosticSatisfied").asBoolean());
    }

    @Test void mutatedSourcesWorkspaceAndDeclaredCandidateClockCannotImproveTheVerdict() throws Exception {
        var input = nativeInput(Control.CORRECT);
        Files.writeString(input.workspace.resolve("left.csv"), "id,amount_cents\nchanged,0\n");
        assertTrue(validReport(input).path("hardGates").path("workspace_mutation").asBoolean());
        var serialized = nativeInput(Control.SERIALIZED); var bad = serialized.evidence.deepCopy();
        for (var o : bad.path("planAudit").path("events")) ((ObjectNode)o.path("event")).put("elapsedNanos", 0);
        JSON.writeValue(serialized.evidenceFile.toFile(), bad);
        var report = validReport(serialized); assertFalse(report.path("checks").path("overlapping_lifecycles").asBoolean());
        assertFalse(report.path("diagnosticSatisfied").asBoolean());
    }

    @Test void independentCanonicalizationMatchesFrozenJavaBytesForUnicodeAndControlCharacters() throws Exception {
        var input = fixture(Files.createTempDirectory(temp, "canonical-").toRealPath());
        var values = JSON.createArrayNode();
        for (String text : List.of("中文 🧪 😀", "quotes\" slash\\ newline\n\r\t", "\\u000B is literal", "control\u000B\u001F", ""))
            values.addObject().put("z", text).put("a", 123).putNull("null").putArray("nested").add(true).add(text);
        var sorted = new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        // JsonNode itself preserves insertion order; recursively sorted maps model TracingLlmClient's canonical input.
        var expected = JSON.createArrayNode();
        for (var value : values) {
            var map = new TreeMap<String, Object>(); value.fields().forEachRemaining(e -> map.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
            expected.add(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sorted.writeValueAsBytes(map))));
        }
        Path sample = input.root.resolve("canonical-samples.json"); writePrivateJson(sample, values);
        var run = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", "-c",
                "import runpy,pathlib,sys;f=runpy.run_path(sys.argv[1]);print(f['compact']([f['digest'](f['jackson_canonical'](v)) for v in f['load'](pathlib.Path(sys.argv[2]),32768)]))",
                input.script.toString(), sample.toString()), null, Duration.ofSeconds(10), 4096, 4096);
        assertEquals(0, run.exitCode(), run.stderr()); assertEquals(expected, JSON.readTree(run.stdout()));
    }

    @Test @EnabledIfSystemProperty(named="paicli.test.e1.replay.docker", matches="true")
    void freshRealDockerWorkersAreReplayedInSeparateNetworklessVerifier() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        for (var control : Control.values()) {
            Path episode = root.resolve(control.name().toLowerCase(Locale.ROOT)); assertFalse(Files.exists(episode));
            var input = fixture(BenchmarkProcessEnvironment.preparePrivateDirectory(episode), Boolean.getBoolean("paicli.test.e1.source.v2"));
            var provider = new Script(input.oracle, control);
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                    Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> provider);
            var req = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, provider.getProviderName(), provider.getModelName(),
                    null, "synthetic-control-no-provider", "PLAN", BenchmarkToolProfile.FILE_ONLY,
                    new BenchmarkProtocol.AgentLimits(200_000, 32, 8, 1_000_000, 16_384), "2026-09-04", input.prompt,
                    input.workspace.toString(), input.home.toString(), episode.toString());
            var execution = worker.execute(req, input.workspace, input.home, Duration.ofSeconds(40));
            assertEquals(input.sourceHash, hash(input.oracleFile)); assertEquals(input.programHash, hash(input.script));
            var raw = JSON.createObjectNode().put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE").put("realProviderCalls", 0)
                    .put("publicationEligible", false).putNull("formalScore").put("candidateSha256", worker.candidateJarSha256())
                    .put("runnerSha256", worker.runnerJarSha256()).put("runnerInventorySha256", worker.runnerContentManifestSha256());
            raw.set("execution", JSON.valueToTree(execution)); writePrivateJson(episode.resolve("worker-result.json"), raw);
            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
            assertTrue(execution.response().success(), execution.response().errorType());
            assertTrue(execution.response().metrics().requestFingerprintComplete());
            input.evidence = envelope(input, JSON.readTree(episode.resolve("plan-audit.json").toFile()), execution.response().metrics(), execution.toolExecutions());
            writePrivateJson(input.evidenceFile, input.evidence);
            for (Path path : List.of(input.evidenceFile, input.oracleFile, input.script))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
            var result = verifier.verify(new CaseDefinition.VerifierInvocation(input.script.getParent(), List.of("python3", "-B", "e1_replay.py", "oracle.json", "{workspace}", "{evidence}")),
                    input.workspace, input.home, input.evidenceFile, Duration.ofSeconds(20));
            assertEquals(input.sourceHash, hash(input.oracleFile)); assertEquals(input.programHash, hash(input.script));
            var record = JSON.createObjectNode().put("publicationEligible", false).put("realProviderCalls", 0).putNull("formalScore")
                    .put("sourceSha256", hash(input.oracleFile)).put("programSha256", hash(input.script)).put("evidenceSha256", hash(input.evidenceFile));
            record.set("verification", JSON.valueToTree(result)); writePrivateJson(episode.resolve("independent-replay.json"), record);
            assertEquals(0, result.exitCode(), control + ": " + result.stderr()); assertTrue(result.sandboxed());
            assertFalse(result.stdoutTruncated()); assertFalse(result.stderrTruncated());
            var report = JSON.readTree(result.stdout());
            assertEquals(expectedPass(control), report.path("diagnosticSatisfied").asBoolean(), control + ": " + report);
            System.out.println("E1 actual Docker replay " + control + ": diagnosticSatisfied=" + report.path("diagnosticSatisfied") + ", realProviderCalls=0");
        }
    }

    private Input nativeInput(Control control) throws Exception { return nativeInput(control, false); }
    Input nativeInput(Control control, boolean sourceV2) throws Exception {
        return nativeInput(control, sourceV2, null);
    }
    Input nativeInput(Control control, boolean sourceV2, LocalFault fault) throws Exception {
        return nativeInput(control, sourceV2, fault, null);
    }
    Input nativeInput(Control control, boolean sourceV2, LocalFault fault, ReplanFault replanFault) throws Exception {
        Input input = fixture(Files.createTempDirectory(temp, "e1-").toRealPath(), sourceV2);
        var audit = new PlanRequestAudit(input.prompt);
        var planRound = new AtomicInteger();
        var script = new Script(input.oracle, control, replanFault, planRound);
        var tracing = new TracingLlmClient(ContextWindowCappedLlmClient.cap(script, 1_000_000, 16_384), input.root.resolve("trace.jsonl"), audit);
        var scopes = new ThreadLocal<String>(); Object lock = new Object();
        LlmClient client = new LlmClient() {
            public ChatResponse chat(List<Message> m, List<Tool> t, StreamListener s) throws IOException { return chat(m, t); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                synchronized (lock) {
                    var normalized = messages.stream().map(m -> new Message(m.role(), m.content(), m.reasoningContent(),
                            m.toolCalls() == null ? List.<ToolCall>of() : List.copyOf(m.toolCalls()), m.toolCallId(),
                            m.contentParts() == null ? List.<ContentPart>of() : List.copyOf(m.contentParts()))).toList();
                    audit.begin(scopes.get() == null ? "planner" : scopes.get(), normalized, tools);
                    try {
                        var response = tracing.chat(normalized, tools);
                        // Like the actual Docker relay, deliver WireChatResponse's null-to-empty
                        // body normalization. Auditing a normalized copy but delivering raw null
                        // creates contradictory evidence in this in-process test harness.
                        var delivered = new ChatResponse(response.role(), response.content() == null ? "" : response.content(),
                                response.reasoningContent(), response.toolCalls() == null ? List.of() : response.toolCalls(),
                                response.inputTokens(), response.outputTokens(), response.cachedInputTokens(), response.resolvedModel(), response.usagePresent());
                        audit.response(delivered);
                        if (replanFault == ReplanFault.RIGHT_AFTER_ANSWER && planRound.get() == 1
                                && scopes.get() != null && scopes.get().endsWith(control == Control.REORDERED ? ":task_1" : ":task_2")
                                && !delivered.hasToolCalls())
                            throw new IllegalStateException("scripted local post-provider delivery failure");
                        return delivered;
                    }
                    finally { audit.end(); }
                }
            }
            public String getProviderName() { return script.getProviderName(); }
            public String getModelName() { return script.getModelName(); }
            public int maxContextWindow() { return 1_000_000; }
        };
        var benchmarkTools = new BenchmarkToolRegistry(BenchmarkToolProfile.FILE_ONLY);
        benchmarkTools.setProjectPath(input.workspace.toString());
        var writeReturned = new AtomicBoolean();
        var branchToolsReturned = ConcurrentHashMap.<String>newKeySet();
        String faultSide = replanFault == ReplanFault.LEFT_BEFORE_TOOL ? "left" : "right";
        int faultIndex = (faultSide.equals("left") ^ control == Control.REORDERED) ? 1 : 2;
        int faultRounds = replanFault == ReplanFault.RIGHT_TWICE ? 2 : 1;
        boolean faultAfterRead = replanFault == ReplanFault.RIGHT_AFTER_TOOL || replanFault == ReplanFault.RIGHT_WITH_EXTRA_WRITE;
        ToolRegistry tools = fault == null && replanFault == null ? benchmarkTools : new ToolRegistry() {
            @Override public String getProjectPath() {
                if (fault == LocalFault.MERGE_BEFORE_INPUT && scopes.get() != null && scopes.get().endsWith(":task_3"))
                    throw new IllegalStateException("scripted local input-preparation failure");
                return super.getProjectPath();
            }
            @Override public List<LlmClient.Tool> getToolDefinitions() {
                if (replanFault != null && faultAfterRead && planRound.get() <= faultRounds
                        && scopes.get() != null && branchToolsReturned.contains(scopes.get()))
                    throw new IllegalStateException("scripted local branch post-batch failure");
                if (scopes.get() != null && scopes.get().endsWith(":task_3")
                        && (fault == LocalFault.MERGE_BEFORE_REQUEST || writeReturned.get()))
                    throw new IllegalStateException("scripted local tool-definition failure");
                return benchmarkTools.getToolDefinitions();
            }
            @Override public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
                if (replanFault != null && replanFault != ReplanFault.RIGHT_AFTER_ANSWER && !faultAfterRead && planRound.get() <= faultRounds
                        && scopes.get() != null && scopes.get().endsWith(":task_" + faultIndex))
                    throw new IllegalStateException("scripted local branch pre-execution failure");
                if (fault == LocalFault.MERGE_BEFORE_TOOL && invocations.stream().anyMatch(i -> i.name().equals("write_file")))
                    throw new IllegalStateException("scripted local pre-execution failure");
                return benchmarkTools.executeTools(invocations);
            }
            @Override public void onPolicyToolResults(List<ToolExecutionResult> results) {
                benchmarkTools.onPolicyToolResults(results);
                if (fault == LocalFault.MERGE_AFTER_TOOL && results.stream().anyMatch(r -> r.name().equals("write_file")))
                    writeReturned.set(true);
                if (replanFault != null && scopes.get() != null && scopes.get().endsWith(":task_" + faultIndex))
                    branchToolsReturned.add(scopes.get());
            }
        };
        tools.setProjectPath(input.workspace.toString());
        var records = new ArrayList<BenchmarkRelayProtocol.WireToolExecution>();
        benchmarkTools.setExecutionObserver(result -> { synchronized (records) {
            records.add(new BenchmarkRelayProtocol.WireToolExecution(records.size() + 1, result.id(), result.name(), result.argumentsJson(),
                    result.result(), textSha256(result.result()), result.result().length(), result.elapsedMillis(), result.timedOut(), result.successful()));
        }});
        var rootsEntered = new ConcurrentHashMap<String, CountDownLatch>();
        var agent = new PlanExecuteAgent(client, tools, null, (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), new PrintStream(OutputStream.nullOutputStream()));
        agent.setExternalContextSupplier(benchmarkTools::promptPolicy);
        agent.setExecutionObserver(event -> {
            synchronized (lock) {
                try { audit.accept(event); } catch (IOException e) { throw new IllegalStateException(e); }
                if (event instanceof PlanExecutionObserver.PlanStarted e) {
                    planRound.incrementAndGet(); rootsEntered.put(e.executionId(), new CountDownLatch(2));
                }
                if (event instanceof PlanExecutionObserver.TaskEntered e) scopes.set(e.executionId() + ":" + e.taskId());
                if (event instanceof PlanExecutionObserver.TaskExited) scopes.remove();
            }
            if (control != Control.SERIALIZED && event instanceof PlanExecutionObserver.TaskEntered e && !e.taskId().equals("task_3")) {
                var barrier = rootsEntered.get(e.executionId()); barrier.countDown();
                try { assertTrue(barrier.await(3, TimeUnit.SECONDS), "two actual runnable threads must enter"); }
                catch (InterruptedException x) { Thread.currentThread().interrupt(); throw new IllegalStateException(x); }
            }
        });
        String answer = agent.runExplicitTask(input.prompt, input.prompt);
        assertTrue(answer.contains(fault != null ? "计划部分完成" : control == Control.INVALID_GRAPH || control == Control.INVALID_DESCRIPTION
                || replanFault == ReplanFault.RIGHT_REJECTED_REPLAN ? "执行失败" : "计划执行完成"), answer);
        assertEquals(0, agent.getExecutionObservationFailures()); assertFalse(audit.failed());
        if (fault != null) {
            assertEquals(1, audit.events().stream().filter(e -> e.eventType().equals("PlanStarted")).count());
            assertEquals(1, audit.events().stream().filter(e -> e.eventType().equals("TaskExited")
                    && e.event().path("kind").asText().equals("THREW")).count());
        }
        if (replanFault != null) {
            assertEquals(replanFault == ReplanFault.RIGHT_REJECTED_REPLAN ? 1 : faultRounds + 1, planRound.get());
            assertEquals(faultRounds, audit.events().stream().filter(e -> e.eventType().equals("TaskExited")
                    && e.event().path("kind").asText().equals("THREW")).count());
        }
        input.metrics = tracing.metrics();
        input.evidence = envelope(input, JSON.valueToTree(audit.snapshot()), input.metrics, records);
        writePrivateJson(input.evidenceFile, input.evidence); return input;
    }

    private Input fixture(Path root) throws Exception { return fixture(root, false); }
    Input fixture(Path root, boolean sourceV2) throws Exception {
        Input input = new Input(); input.root = root;
        Path bundle = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("bundle"));
        input.workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("workspace"));
        input.home = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("home"));
        input.script = bundle.resolve("e1_replay.py");
        try (var stream = getClass().getResourceAsStream("/benchmark/e1_replay.py")) { assertNotNull(stream); Files.copy(stream, input.script); }
        input.oracle = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "E1").put("profile", "e1-plan-join-v1").put("variantId", "b7".repeat(12));
        if (sourceV2) input.oracle.put("schemaVersion", 2).put("expectedToolProfile", "FILE_ONLY");
        input.oracle.putObject("files").put("left.csv", "id,amount_cents\n甲_17,1299\n甲_29,-320\n甲_41,75\n")
                .put("right.csv", "id,amount_cents\n乙_31,850\n乙_43,960\n乙_57,-215\n");
        input.oracleFile = bundle.resolve("oracle.json"); writePrivateJson(input.oracleFile, input.oracle);
        for (String name : List.of("left.csv", "right.csv")) Files.writeString(input.workspace.resolve(name), input.oracle.path("files").path(name).asText());
        var getPrompt = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", "-c", "import runpy,sys,json;print(runpy.run_path(sys.argv[1])['prompt'](json.load(open(sys.argv[2],encoding='utf-8'))),end='')", input.script.toString(), input.oracleFile.toString()), null, Duration.ofSeconds(10), 16384, 4096);
        assertEquals(0, getPrompt.exitCode(), getPrompt.stderr()); input.prompt = getPrompt.stdout();
        input.sourceHash = hash(input.oracleFile); input.programHash = hash(input.script);
        for (Path path : List.of(input.oracleFile, input.script)) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
        input.evidenceFile = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("verifier-evidence")).resolve("evidence.json"); return input;
    }

    private ObjectNode envelope(Input input, com.fasterxml.jackson.databind.JsonNode audit, TracingLlmClient.Metrics metrics, Object records) throws Exception {
        assertEquals(input.sourceHash, hash(input.oracleFile)); assertEquals(input.programHash, hash(input.script));
        var n = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "E1").put("profile", "e1-plan-join-v1")
                .put("relayVersion", BenchmarkRelayProtocol.VERSION).put("sourceSha256", input.sourceHash).put("modelToolCalls", metrics.toolCalls());
        n.set("planAudit", audit); n.set("scopedRequestFingerprints", JSON.valueToTree(metrics.scopedRequestFingerprints()));
        n.set("toolExecutions", JSON.valueToTree(records)); return n;
    }
    private com.fasterxml.jackson.databind.JsonNode validReport(Input input) throws Exception {
        var result = replay(input); assertEquals(0, result.exitCode(), result.stderr());
        var report = JSON.readTree(result.stdout()); assertTrue(report.path("evaluationValid").asBoolean()); return report;
    }
    private BenchmarkSubprocess.Result replay(Input input) throws Exception {
        String diagnostic = "import runpy,pathlib,sys,traceback\nf=runpy.run_path(sys.argv[1])\ntry:\n p=pathlib.Path(sys.argv[2]);print(f['compact'](f['qualify'](f['load'](p,131072),f['load'](pathlib.Path(sys.argv[4]),16777216),pathlib.Path(sys.argv[3]),f['digest'](p.read_bytes()))))\nexcept Exception:\n traceback.print_exc();raise SystemExit(2)\n";
        return BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", "-c", diagnostic, input.script.toString(), input.oracleFile.toString(), input.workspace.toString(), input.evidenceFile.toString()), null, Duration.ofSeconds(10), 32768, 32768);
    }
    private static ObjectNode firstTaskTurn(ObjectNode evidence) { return (ObjectNode)evidence.path("planAudit").path("providerTurns").get(1); }
    private static ObjectNode firstEvent(ObjectNode evidence, String type) {
        for (var event : evidence.path("planAudit").path("events")) if (event.path("eventType").asText().equals(type)) return (ObjectNode)event.path("event");
        throw new AssertionError(type);
    }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static void writePrivateJson(Path path, Object value) throws IOException {
        try (var out = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(JSON.writeValueAsBytes(value)); while (bytes.hasRemaining()) out.write(bytes);
        }
    }
    static final class Input { Path root, workspace, home, oracleFile, script, evidenceFile; ObjectNode oracle, evidence; String prompt, sourceHash, programHash; TracingLlmClient.Metrics metrics; }

    static final class Script implements LlmClient {
        final ObjectNode oracle; final Control control;
        final ReplanFault replanFault; final AtomicInteger planRound;
        private String provider = "deepseek", model = "deepseek-v4-flash";
        Script(ObjectNode oracle, Control control) { this(oracle, control, null, new AtomicInteger()); }
        Script(ObjectNode oracle, Control control, String provider, String model) {
            this(oracle, control); this.provider = provider; this.model = model;
        }
        Script(ObjectNode oracle, Control control, ReplanFault replanFault, AtomicInteger planRound) {
            this.oracle = oracle; this.control = control; this.replanFault = replanFault; this.planRound = planRound;
        }
        public ChatResponse chat(List<Message> m, List<Tool> t, StreamListener s) throws IOException { return chat(m, t); }
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            String user = messages.stream().filter(m -> m.role().equals("user")).findFirst().orElseThrow().content();
            if (user.startsWith("请为以下任务制定执行计划")) {
                var graph = JSON.createObjectNode(); var tasks = graph.putArray("tasks");
                String leftId = control == Control.NBSP_ID ? "\u00a0" : "original-left";
                for (String side : control == Control.REORDERED ? List.of("right", "left") : List.of("left", "right")) {
                    var node = tasks.addObject().put("id", side.equals("left") ? leftId : "original-right").put("description", side.toUpperCase(Locale.ROOT)).put("type", "FILE_READ");
                    if (control == Control.INVALID_DESCRIPTION && side.equals("right")) node.put("description", 123);
                    var deps = node.putArray("dependencies"); if (control == Control.SERIALIZED && side.equals("right")) deps.add(leftId);
                    if ((control == Control.INVALID_GRAPH || replanFault == ReplanFault.RIGHT_REJECTED_REPLAN && planRound.get() > 0)
                            && side.equals("right")) deps.add("undeclared");
                }
                var merge = tasks.addObject().put("id", "original-merge").put("description", "MERGE").put("type", "FILE_WRITE");
                if (control == Control.MISSING_DESCRIPTION) merge.remove("description");
                var deps = merge.putArray("dependencies");
                deps.add(leftId); if (control != Control.MISSING_DEPENDENCY) deps.add("original-right");
                return response(graph.toString(), List.of());
            }
            var matcher = Pattern.compile("当前任务：([^\\r\\n]+)").matcher(user);
            if (!matcher.find()) {
                if (control == Control.MISSING_DESCRIPTION) return response("no task description", List.of());
                throw new IOException("native task input missing");
            }
            String role = matcher.group(1); var seen = messages.stream().filter(m -> m.role().equals("tool")).toList();
            if (role.equals("MERGE")) {
                if (control == Control.EMPTY_MERGE || control == Control.NULL_MERGE)
                    return response(control == Control.NULL_MERGE ? null : "", List.of());
                if (!seen.isEmpty()) return response(switch (control) {
                    case EMPTY_MERGE_AFTER_WRITE -> "";
                    case NULL_MERGE_AFTER_WRITE -> null;
                    case UNICODE_BLANK_MERGE_AFTER_WRITE -> "\u2003\t\n";
                    case NBSP_MERGE_AFTER_WRITE -> "\u00a0";
                    default -> "report written";
                }, List.of());
                var joined = JSON.createObjectNode();
                for (String side : List.of("left", "right")) {
                    ObjectNode branch = null;
                    for (String line : user.split("\\R")) if (line.startsWith("{")) {
                        var candidate = JSON.readTree(line); if (candidate.path("branch").asText().equals(side)) branch = (ObjectNode)candidate;
                    }
                    if (branch == null) branch = summarize(side, oracle.path("files").path(side + ".csv").asText()); // deliberate missing-dependency shortcut
                    joined.set(side, branch);
                }
                joined.put("combined_cents", joined.path("left").path("sum_cents").asInt() + joined.path("right").path("sum_cents").asInt() + (control == Control.WRONG_MERGE ? 1 : 0));
                var calls = new ArrayList<ToolCall>(); calls.add(call("shared", "write_file", Map.of("path", control == Control.RELATIVE_PATHS ? "./report.json" : "report.json", "content", joined.toString())));
                if (control == Control.EXTRA_WRITE) calls.add(call("extra", "write_file", Map.of("path", "extra.txt", "content", "unrequested")));
                return response("", calls);
            }
            String side = role.toLowerCase(Locale.ROOT);
            if (side.equals("right") && (control == Control.EMPTY_BRANCH || control == Control.NULL_BRANCH
                    || !seen.isEmpty() && (control == Control.EMPTY_BRANCH_AFTER_READ || control == Control.NULL_BRANCH_AFTER_READ)))
                return response(control == Control.NULL_BRANCH || control == Control.NULL_BRANCH_AFTER_READ ? null : "", List.of());
            boolean missingRead = control == Control.MISSING_READ && !(replanFault == ReplanFault.RIGHT_AFTER_ANSWER && planRound.get() == 1);
            if (seen.isEmpty() && !missingRead) {
                if (replanFault == ReplanFault.RIGHT_WITH_EXTRA_WRITE && planRound.get() == 1 && side.equals("right"))
                    return response("", List.of(call("shared", "read_file", Map.of("path", "right.csv")),
                            call("extra", "write_file", Map.of("path", "attempt-note.txt", "content", "unrequested first attempt"))));
                if (control == Control.CHUNKED_READS || control == Control.PARTIAL_READ) {
                    var calls = new ArrayList<ToolCall>(); calls.add(call("shared", "read_file", Map.of("path", side + ".csv", "offset", 1, "limit", 2)));
                    if (control == Control.CHUNKED_READS) calls.add(call("tail", "read_file", Map.of("path", side + ".csv", "offset", 3, "limit", 2000)));
                    return response("", calls);
                }
                return response("", List.of(call("shared", "read_file", Map.of("path", (control == Control.RELATIVE_PATHS ? "./" : "") + side + ".csv"))));
            }
            String csv;
            if (seen.isEmpty() || control == Control.PARTIAL_READ) csv = oracle.path("files").path(side + ".csv").asText(); // deliberate coverage shortcut
            else if (control == Control.CHUNKED_READS) {
                var text = new StringBuilder();
                for (var result : seen) for (String line : result.content().split("\\R")) {
                    var numbered = Pattern.compile("^\\s*\\d+ \\| (.*)$").matcher(line); if (numbered.find()) text.append(numbered.group(1)).append('\n');
                }
                csv = text.toString();
            } else csv = seen.get(0).content().substring(seen.get(0).content().indexOf("id,amount_cents\n"));
            ObjectNode summary = summarize(side, csv);
            if (control == Control.WRONG_BRANCH && side.equals("right")) summary.put("sum_cents", summary.path("sum_cents").asInt() + 1);
            String text = summary.toString(); if (control == Control.FENCED_BRANCH && side.equals("right")) text = "```json\n" + text + "\n```";
            return response(text, List.of());
        }
        private ObjectNode summarize(String side, String text) {
            String[] rows = text.split("\\R"); var n = JSON.createObjectNode().put("branch", side).put("rows", rows.length - 1);
            var ids = n.putArray("ids"); int sum = 0;
            for (int i = 1; i < rows.length; i++) { String[] parts = rows[i].split(","); ids.add(parts[0]); sum += Integer.parseInt(parts[1]); }
            return n.put("sum_cents", sum);
        }
        private ToolCall call(String id, String name, Object args) throws IOException { return new ToolCall(id, new ToolCall.Function(name, JSON.writeValueAsString(args))); }
        private ChatResponse response(String text, List<ToolCall> calls) { return new ChatResponse("assistant", text, null, calls, 100, 30, 0, getModelName(), true); }
        public String getProviderName() { return provider; } public String getModelName() { return model; }
        public int maxContextWindow() { return 1_000_000; }
    }
}
