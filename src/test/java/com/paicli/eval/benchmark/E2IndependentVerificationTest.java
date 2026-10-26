package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import com.paicli.memory.AutoCompactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The independent E2 scoring path: a real relayed TEAM episode is recorded by the host
 * audit, then {@code e2_team_verify.py} re-derives the lifecycle and write attribution
 * from the raw evidence alone. Consistent-but-wrong behavior is a valid zero; tampered
 * evidence yields evaluation-invalid with no score. Script providers, zero model calls.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class E2IndependentVerificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORACLE = "{\"contractVersion\":1,\"case\":\"e2-team-dev-diagnostic\","
            + "\"expectedWrites\":[{\"path\":\"notes.txt\",\"role\":\"WORKER\"}],"
            + "\"requireNoConflicts\":true,"
            + "\"requireWorkspaceFiles\":[\"notes.txt\"],"
            + "\"minApprovedReviews\":1}";

    private String priorSessionMemory;

    @BeforeEach void noAsynchronousSummaryRequests() {
        priorSessionMemory = System.getProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "false");
    }

    @AfterEach void restoreConfiguration() {
        if (priorSessionMemory == null) System.clearProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        else System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, priorSessionMemory);
    }

    @Test void correctEpisodeScoresFullDiagnostic(@TempDir Path temp) throws Exception {
        Root root = episode(temp, false);
        JsonNode report = verify(root, root.auditFile);
        assertFalse(report.path("evaluationInvalid").asBoolean(), report.toString());
        assertEquals(100, report.path("diagnosticScore").asInt(), report.toString());
        assertTrue(report.path("diagnosticSatisfied").asBoolean());
        assertTrue(allChecks(report), report.toString());
        assertEquals(sha256(root.oracleBytes), report.path("oracleSha256").asText());
        assertEquals(sha256(Files.readAllBytes(root.auditFile)), report.path("evidenceSha256").asText());
        assertFalse(report.path("publishable").asBoolean());
    }

    @Test void conflictedEpisodeIsAValidZeroWithoutEvidenceFault(@TempDir Path temp) throws Exception {
        Root root = episode(temp, true);
        JsonNode report = verify(root, root.auditFile);
        assertFalse(report.path("evaluationInvalid").asBoolean(), report.toString());
        assertEquals(0, report.path("diagnosticScore").asInt(), report.toString());
        assertFalse(report.path("checks").path("no_conflicts").asBoolean());
        assertFalse(report.path("checks").path("expected_writes").asBoolean(),
                "the shared-write episode never produces the contracted notes.txt");
        assertFalse(report.path("checks").path("workspace_files").asBoolean());
        assertTrue(report.path("checks").path("attribution_consistent").asBoolean(),
                "the audit section still matches the independently re-derived writes");
        assertTrue(report.path("invalidReasons").isEmpty());
    }

    @Test void tamperedAttributionSectionIsEvaluationInvalidWithoutScore(@TempDir Path temp) throws Exception {
        Root root = episode(temp, false);
        ObjectNode evidence = (ObjectNode) JSON.readTree(root.evidenceBytes);
        ((ObjectNode) evidence.path("writeAttributions").get(0)).put("path", "forged.txt");
        Path tampered = Files.write(root.dir.resolve("team-audit-tampered.json"),
                JSON.writeValueAsBytes(evidence));
        JsonNode report = verify(root, tampered);
        assertTrue(report.path("evaluationInvalid").asBoolean(), report.toString());
        assertTrue(report.path("diagnosticScore").isNull(), "tampered evidence has no numeric score");
        assertTrue(report.path("invalidReasons").toString().contains("attribution_section_mismatch"));
        assertFalse(report.path("diagnosticSatisfied").asBoolean());
        assertTrue(java.util.Arrays.equals(root.evidenceBytes, Files.readAllBytes(root.auditFile)),
                "the original private snapshot is never mutated by a tamper control");
    }

    @Test void missingRunExitIsEvaluationInvalidWithoutScore(@TempDir Path temp) throws Exception {
        Root root = episode(temp, false);
        ObjectNode evidence = (ObjectNode) JSON.readTree(root.evidenceBytes);
        ArrayNode events = (ArrayNode) evidence.path("events");
        assertFalse(events.isEmpty());
        assertEquals("RunExited", events.get(events.size() - 1).path("eventType").asText(),
                "RunExited is the terminal observation in a healthy episode");
        events.remove(events.size() - 1);
        Path tampered = Files.write(root.dir.resolve("team-audit-tampered.json"),
                JSON.writeValueAsBytes(evidence));
        JsonNode report = verify(root, tampered);
        assertTrue(report.path("evaluationInvalid").asBoolean(), report.toString());
        assertTrue(report.path("diagnosticScore").isNull(), "tampered evidence has no numeric score");
        assertTrue(report.path("invalidReasons").toString().contains("run_exit_missing_or_duplicated"));
    }

    private boolean allChecks(JsonNode report) {
        for (Iterator<JsonNode> it = report.path("checks").elements(); it.hasNext(); ) {
            if (!it.next().asBoolean()) return false;
        }
        return true;
    }

    /** Runs one real relayed TEAM episode and records its private host audit snapshot. */
    private Root episode(Path temp, boolean sharedWrite) throws Exception {
        Root root = new Root();
        root.dir = Files.createDirectory(temp.resolve("e2-" + System.nanoTime()));
        root.workspace = Files.createDirectory(root.dir.resolve("workspace"));
        TeamRelayEvidenceTest.Script script = new TeamRelayEvidenceTest.Script(sharedWrite);
        TeamRelayEvidenceTest.Pool pool = new TeamRelayEvidenceTest.Pool(3);
        TeamRelayEvidenceTest.Host host = new TeamRelayEvidenceTest.Host(
                script, root.workspace, pool.pool(), 120_000);
        try (pool) {
            Future<String> run = pool.pool().submit(() -> {
                String answer;
                try { answer = host.worker().runExplicitTask(TeamRelayEvidenceTest.GOAL, TeamRelayEvidenceTest.GOAL); }
                finally { host.relay().verifyTeamObservationFailures(host.worker().getExecutionObservationFailures()); }
                host.relay().complete(answer, List.of());
                return answer;
            });
            Future<BenchmarkProviderRelay.ServeResult> serving = pool.pool().submit(host::serveUntilTerminal);
            assertTrue(run.get(60, TimeUnit.SECONDS).startsWith("✅"));
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, serving.get(60, TimeUnit.SECONDS));
            host.audit().assertComplete();
            assertFalse(host.audit().failed());
        } finally {
            host.close();
        }
        root.auditFile = root.dir.resolve("team-audit.json");
        host.audit().writeSnapshot(root.auditFile);
        root.evidenceBytes = Files.readAllBytes(root.auditFile);
        Files.writeString(root.dir.resolve("oracle.json"), ORACLE);
        root.oracleBytes = ORACLE.getBytes(StandardCharsets.UTF_8);
        root.script = root.dir.resolve("e2_team_verify.py");
        try (var stream = getClass().getResourceAsStream("/benchmark/e2_team_verify.py")) {
            assertNotNull(stream);
            Files.copy(stream, root.script);
        }
        return root;
    }

    private JsonNode verify(Root root, Path evidenceFile) throws Exception {
        var process = new ProcessBuilder("python3", "-B", root.script.toString(),
                root.dir.resolve("oracle.json").toString(), root.workspace.toString(), evidenceFile.toString());
        var result = BenchmarkSubprocess.run(process, null, Duration.ofSeconds(30), 32768, 32768);
        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.timedOut());
        return JSON.readTree(result.stdout());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class Root {
        Path dir;
        Path workspace;
        Path auditFile;
        Path script;
        byte[] evidenceBytes;
        byte[] oracleBytes;
    }
}
