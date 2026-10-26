package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual offline Worker terminal controls, not formal E1 admission or model scores. */
@EnabledIfSystemProperty(named="paicli.test.e1.terminal.docker", matches="true")
@Timeout(180)
class E1TerminalDispositionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    enum Control { TOKEN_BUDGET, PERMANENT_INITIAL, TRANSIENT_INITIAL, PERMANENT_BRANCH,
        TRANSIENT_BRANCH, MISSING_USAGE_BRANCH, MODEL_DRIFT_BRANCH }

    @Test void hostTerminalClassificationSurvivesPartialTasksAndRecoveredReplans() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        for (Control control : Control.values()) {
            Path episode = root.resolve(control.name().toLowerCase(Locale.ROOT)); assertFalse(Files.exists(episode));
            var input = new E1IndependentReplayTest().fixture(BenchmarkProcessEnvironment.preparePrivateDirectory(episode), true);
            var client = new FailureClient(input.oracle, control);
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                    Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> client);
            var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, client.getProviderName(), client.getModelName(),
                    null, "synthetic-terminal-control-no-provider", "PLAN", BenchmarkToolProfile.FILE_ONLY,
                    new BenchmarkProtocol.AgentLimits(control == Control.TOKEN_BUDGET ? 250 : 200_000, 32, 8, 1_000_000, 16_384),
                    "2026-09-04", input.prompt, input.workspace.toString(), input.home.toString(), episode.toString());
            var execution = worker.execute(request, input.workspace, input.home, Duration.ofSeconds(30));
            var disposition = BenchmarkFailureClassifier.classifyWorker(execution);
            String expectedError = switch (control) {
                case TOKEN_BUDGET -> "EPISODE_BUDGET_EXHAUSTED";
                case PERMANENT_INITIAL, PERMANENT_BRANCH -> "LLM_API_ERROR";
                case TRANSIENT_INITIAL, TRANSIENT_BRANCH -> "PROVIDER_TRANSIENT";
                case MISSING_USAGE_BRANCH -> "USAGE_UNPROVEN";
                case MODEL_DRIFT_BRANCH -> "MODEL_IDENTITY_UNPROVEN";
            };
            var expected = switch (control) {
                case TOKEN_BUDGET, PERMANENT_INITIAL, PERMANENT_BRANCH -> BenchmarkFailureClassifier.Disposition.SCORED_FAILURE;
                default -> BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
            };
            var audit = JSON.readTree(episode.resolve("plan-audit.json").toFile());
            long plans = 0, throwsSeen = 0;
            for (var event : audit.path("events")) {
                if (event.path("eventType").asText().equals("PlanStarted")) plans++;
                if (event.path("eventType").asText().equals("TaskExited") && event.path("event").path("kind").asText().equals("THREW")) throwsSeen++;
            }
            boolean artifact = Files.exists(input.workspace.resolve("report.json"));
            var record = JSON.createObjectNode().put("control", control.name()).put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE")
                    .put("realProviderCalls", 0).put("publicationEligible", false).putNull("formalScore")
                    .put("e1VerifierInvoked", false).put("expectedDisposition", expected.name()).put("actualDisposition", disposition.name())
                    .put("expectedErrorType", expectedError).put("plansObserved", plans).put("throwsObserved", throwsSeen)
                    .put("artifactExists", artifact).put("candidateSha256", worker.candidateJarSha256())
                    .put("runnerSha256", worker.runnerJarSha256()).put("runnerInventorySha256", worker.runnerContentManifestSha256());
            record.set("execution", JSON.valueToTree(execution));
            try (var out = Files.newByteChannel(episode.resolve("terminal-result.json"), Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                var bytes = java.nio.ByteBuffer.wrap(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
                while (bytes.hasRemaining()) out.write(bytes);
            }
            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status(), control.name());
            assertFalse(execution.response().success()); assertEquals(expectedError, execution.response().errorType(), control.name());
            assertEquals(expected, disposition, control.name()); assertTrue(execution.response().metrics().requestFingerprintComplete());
            assertFalse(audit.path("failed").asBoolean());
            if (Set.of(Control.PERMANENT_BRANCH, Control.TRANSIENT_BRANCH, Control.MISSING_USAGE_BRANCH).contains(control)) {
                assertEquals(2, plans, "a recovered second plan must actually run"); assertEquals(1, throwsSeen);
                assertTrue(artifact, "successful recovery cannot erase an earlier terminal policy failure");
            }
            if (control == Control.TOKEN_BUDGET) { assertEquals(3, execution.response().metrics().calls()); assertFalse(artifact); }
            if (control == Control.PERMANENT_INITIAL || control == Control.TRANSIENT_INITIAL) {
                assertEquals(1, execution.response().metrics().calls()); assertEquals(0, plans); assertFalse(artifact);
            }
            if (control == Control.MODEL_DRIFT_BRANCH) { assertEquals(1, plans); assertTrue(artifact); }
            System.out.println("E1 terminal " + control + ": " + expectedError + "/" + disposition + ", plans=" + plans + ", realProviderCalls=0");
        }
    }

    private static final class FailureClient implements LlmClient {
        private final E1IndependentReplayTest.Script delegate;
        private final Control control;
        private boolean injected;
        FailureClient(com.fasterxml.jackson.databind.node.ObjectNode oracle, Control control) {
            delegate = new E1IndependentReplayTest.Script(oracle, E1IndependentReplayTest.Control.CORRECT); this.control = control;
        }
        public ChatResponse chat(List<Message> m, List<Tool> t, StreamListener s) throws IOException { return chat(m, t); }
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            String user = messages.stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
            boolean initial = control == Control.PERMANENT_INITIAL || control == Control.TRANSIENT_INITIAL;
            boolean inject = !injected && control != Control.TOKEN_BUDGET && (initial || user.contains("当前任务：RIGHT\n"));
            if (inject) {
                injected = true;
                if (control == Control.TRANSIENT_INITIAL || control == Control.TRANSIENT_BRANCH) throw new SocketTimeoutException("synthetic transient failure");
                if (control == Control.PERMANENT_INITIAL || control == Control.PERMANENT_BRANCH) throw new IOException("synthetic permanent failure");
            }
            var response = delegate.chat(messages, tools);
            return new ChatResponse(response.role(), response.content(), response.reasoningContent(), response.toolCalls(),
                    response.inputTokens(), response.outputTokens(), response.cachedInputTokens(),
                    inject && control == Control.MODEL_DRIFT_BRANCH ? "different-scripted-model" : response.resolvedModel(),
                    !(inject && control == Control.MISSING_USAGE_BRANCH));
        }
        public String getProviderName() { return delegate.getProviderName(); }
        public String getModelName() { return delegate.getModelName(); }
        public int maxContextWindow() { return 1_000_000; }
    }
}
