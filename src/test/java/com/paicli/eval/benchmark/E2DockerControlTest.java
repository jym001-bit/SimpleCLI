package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2 scripted controls through a real networkless Docker Candidate Worker and a real
 * networkless Docker verifier running the independent Python program. The same frozen
 * oracle judges both controls; a conflicting but honest episode is a valid zero, and the
 * verifier cross-checks the host team audit independently. Zero provider API calls.
 */
@EnabledIfSystemProperty(named = "paicli.test.e2.docker", matches = "true")
class E2DockerControlTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOAL = TeamRelayEvidenceTest.GOAL;
    private static final String ORACLE = "{\"contractVersion\":1,\"case\":\"e2-team-dev-diagnostic\","
            + "\"expectedWrites\":[{\"path\":\"notes.txt\",\"role\":\"WORKER\"}],"
            + "\"requireNoConflicts\":true,"
            + "\"requireWorkspaceFiles\":[\"notes.txt\"],"
            + "\"minApprovedReviews\":1}";

    @Test void scriptedTeamControlsRunRealDockerWorkersAndIndependentDockerVerifier() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e2.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        String candidateJar = System.getProperty("paicli.test.candidate.jar");
        String runnerJar = System.getProperty("paicli.test.runner.jar");
        String workerImage = System.getProperty("paicli.test.worker.image");
        String verifierImage = System.getProperty("paicli.test.verifier.image");
        assertNotNull(candidateJar); assertNotNull(runnerJar); assertNotNull(workerImage); assertNotNull(verifierImage);

        // One frozen contract and one frozen program judge every control. Prior runs may
        // have frozen them already; reuse only on byte identity, never rewrite.
        Path frozen = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("frozen"));
        Path oracleFile = frozen.resolve("oracle.json");
        byte[] oracleBytes = ORACLE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Files.exists(oracleFile)) {
            assertTrue(java.util.Arrays.equals(oracleBytes, Files.readAllBytes(oracleFile)),
                    "the frozen oracle already exists and must stay byte-identical");
        } else {
            writePrivate(oracleFile, oracleBytes);
        }
        Path script = frozen.resolve("e2_team_verify.py");
        try (var input = getClass().getResourceAsStream("/benchmark/e2_team_verify.py")) {
            assertNotNull(input);
            if (Files.exists(script)) {
                assertTrue(java.util.Arrays.equals(input.readAllBytes(), Files.readAllBytes(script)),
                        "the frozen verifier program already exists and must stay byte-identical");
            } else {
                writePrivate(script, input.readAllBytes());
            }
        }
        Files.setPosixFilePermissions(oracleFile, PosixFilePermissions.fromString("r--------"));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("r--------"));
        String oracleSha = hash(oracleFile);
        String scriptSha = hash(script);

        for (boolean sharedWrite : List.of(false, true)) {
            String control = sharedWrite ? "conflict" : "correct";
            Path episode = BenchmarkProcessEnvironment.preparePrivateDirectory(
                    root.resolve(control + "-" + System.nanoTime()));
            Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
            Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
            Files.writeString(workspace.resolve("README.md"),
                    "# E2 scripted control fixture\n\n只读 fixture；结论写入 answer 约定的文件。\n");

            var client = new TeamRelayEvidenceTest.Script(sharedWrite);
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), workerImage,
                    Path.of(candidateJar), Path.of(runnerJar), ignored -> client);
            var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION,
                    client.getProviderName(), client.getModelName(), null, "synthetic-key-not-a-real-credential",
                    "TEAM", BenchmarkToolProfile.FILE_ONLY,
                    new BenchmarkProtocol.AgentLimits(100_000, 32, 3, 1_000_000, 16_384),
                    "2026-09-09", GOAL, workspace.toString(), home.toString(), episode.toString());
            var execution = worker.execute(request, workspace, home, Duration.ofSeconds(180));

            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status(), control);
            assertNotNull(execution.response());
            assertTrue(execution.response().success(), control + ": " + execution.response().errorType());
            assertEquals(0, execution.exitCode(), control);
            assertTrue(execution.response().answer().startsWith("✅"), control);
            assertTrue(execution.response().metrics().requestFingerprintComplete(), control);
            assertTrue(execution.response().metrics().usageComplete(), control);

            Path auditFile = episode.resolve("team-audit.json");
            assertTrue(Files.exists(auditFile), "the host writes the private team audit on the success path");
            Path evidenceFile = BenchmarkProcessEnvironment.preparePrivateDirectory(
                    episode.resolve("verifier-evidence")).resolve("team-audit.json");
            writePrivate(evidenceFile, Files.readAllBytes(auditFile));
            Files.setPosixFilePermissions(evidenceFile, PosixFilePermissions.fromString("r--------"));

            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), verifierImage);
            var replay = verifier.verify(new CaseDefinition.VerifierInvocation(frozen,
                            List.of("python3", "-B", "e2_team_verify.py", "oracle.json", "{workspace}", "{evidence}")),
                    workspace, home, evidenceFile, Duration.ofSeconds(30));
            assertEquals(0, replay.exitCode(), replay.stderr());
            assertTrue(replay.sandboxed(), control);
            assertFalse(replay.stdoutTruncated(), control);
            var report = JSON.readTree(replay.stdout());
            assertFalse(report.path("evaluationInvalid").asBoolean(), report.toString());
            assertEquals(sharedWrite ? 0 : 100, report.path("diagnosticScore").asInt(), report.toString());
            assertEquals(!sharedWrite, report.path("diagnosticSatisfied").asBoolean(), control);
            assertTrue(report.path("checks").path("attribution_consistent").asBoolean(),
                    control + ": attribution must stay consistent even for the conflicting control");
            assertEquals(sharedWrite ? 1 : 0, report.path("checks").path("no_conflicts").asBoolean() ? 0 : 1,
                    control + ": conflict count reflected in the check");

            var record = JSON.createObjectNode()
                    .put("kind", "E2_SCRIPTED_REAL_DOCKER_WORKER_AND_INDEPENDENT_DOCKER_VERIFIER")
                    .put("publishable", false).put("realProviderCalls", 0).putNull("formalScore")
                    .put("control", control).put("relayVersion", BenchmarkRelayProtocol.VERSION)
                    .put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_TOKEN_COUNTS")
                    .put("candidateSha256", worker.candidateJarSha256())
                    .put("runnerSha256", worker.runnerJarSha256())
                    .put("runnerInventorySha256", worker.runnerContentManifestSha256())
                    .put("workerImage", workerImage).put("verifierImage", verifierImage)
                    .put("oracleSha256", oracleSha).put("replayProgramSha256", scriptSha)
                    .put("evidenceSha256", hash(evidenceFile))
                    .put("diagnosticSatisfied", !sharedWrite);
            record.set("verification", report);
            writePrivate(episode.resolve("independent-replay.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
            assertEquals(oracleSha, hash(oracleFile));
            assertEquals(scriptSha, hash(script));
            System.out.println("E2 actual Docker control " + control + ": diagnosticScore="
                    + report.path("diagnosticScore").asInt());
        }
    }

    private static void writePrivate(Path path, byte[] bytes) throws Exception {
        try (var channel = Files.newByteChannel(path, java.util.Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    private static String hash(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
