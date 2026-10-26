package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Mode/artifact smoke with host-scoped request fingerprints. Still no full E1 verifier or model API. */
@EnabledIfSystemProperty(named = "paicli.test.e1.docker", matches = "true")
@Timeout(150)
class E1DockerPlanSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "并行读取 left.txt 与 right.txt 的数据，然后把两分支完整结果按左/右顺序合并到 merged.txt；不要联网。";

    @Test void realWorkerBindsPlanFingerprintsAndRejectsInvalidDependencyWithoutScoringE1() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        for (String control : List.of("independent", "serialized", "invalid_dependency", "episode_budget")) {
            Path episode = root.resolve(control);
            assertFalse(Files.exists(episode), "never overwrite previous control evidence");
            BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
            Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
            Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
            writePrivate(workspace.resolve("left.txt"), "value=甲-17-29\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            writePrivate(workspace.resolve("right.txt"), "value=乙-31-43\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            writePrivate(episode.resolve("prompt.txt"), PROMPT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var client = new PlanClient(control);
            String image = System.getProperty("paicli.test.worker.image");
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                    Path.of(System.getProperty("paicli.test.candidate.jar")),
                    Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> client);
            var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION,
                    client.getProviderName(), client.getModelName(), null, "synthetic-control-key-no-provider",
                    "PLAN", BenchmarkToolProfile.FILE_ONLY,
                    new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384), "2026-09-04",
                    PROMPT, workspace.toString(), home.toString(), episode.toString());
            var execution = worker.execute(request, workspace, home, Duration.ofSeconds(40));
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("kind", "E1_SCRIPTED_DOCKER_PLAN_MODE_ARTIFACT_SMOKE_ONLY");
            evidence.put("publicationEligible", false); evidence.put("formalScore", null);
            evidence.put("e1AssertionsEvaluated", false); evidence.put("realProviderCalls", 0);
            evidence.put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE");
            evidence.put("control", control); evidence.put("workerImage", image);
            evidence.put("candidateSha256", worker.candidateJarSha256());
            evidence.put("runnerSha256", worker.runnerJarSha256());
            evidence.put("runnerInventorySha256", worker.runnerContentManifestSha256());
            evidence.put("execution", execution); evidence.put("hostProviderRequests", client.requests);
            evidence.put("scriptedPlanResponse", client.planResponse());
            evidence.put("expectedBoundary", control.equals("invalid_dependency")
                    ? "INVALID_GRAPH_REJECTED_BEFORE_TOOLS" : control.equals("episode_budget")
                    ? "CUMULATIVE_BUDGET_VALID_FAILURE" : "HOST_SCOPED_FINGERPRINTS_NOT_E1_SCORE");
            evidence.put("missingEvidence", List.of("independent-E1-replay", "frozen-E1-recipe", "formal-E1-binding"));
            Path merged = workspace.resolve("merged.txt");
            evidence.put("artifactSha256", Files.exists(merged)
                    ? java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(merged))) : null);
            writePrivate(episode.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status(), control);
            assertNotNull(execution.response(), control);
            assertEquals(0, execution.exitCode());
            assertFalse(Files.exists(episode.resolve("worker-docker-tmp/container.cid")));
            if (control.equals("episode_budget")) {
                assertFalse(execution.response().success());
                assertEquals("EPISODE_BUDGET_EXHAUSTED", execution.response().errorType());
                assertEquals(BenchmarkFailureClassifier.Disposition.SCORED_FAILURE,
                        BenchmarkFailureClassifier.classifyWorker(execution));
                assertEquals(3, execution.response().metrics().calls(), "one shared closing call, not new budgets per task/replan");
                assertTrue(execution.response().metrics().requestFingerprintComplete());
                assertFalse(Files.exists(merged));
            } else if (control.equals("invalid_dependency")) {
                assertTrue(execution.response().success(), "worker completed; task answer must still report failure");
                assertEquals(1, client.requests.size());
                assertTrue(execution.response().answer().contains("执行失败"));
                assertFalse(Files.exists(merged));
                assertTrue(execution.toolExecutions().isEmpty());
            } else {
                assertTrue(execution.response().success(), execution.response().errorType());
                assertEquals(7, client.requests.size());
                assertEquals(7, execution.response().metrics().calls());
                assertEquals(3, execution.response().metrics().toolCalls());
                assertTrue(execution.response().metrics().requestFingerprintComplete());
                assertNull(execution.response().metrics().systemPromptSha256(), "multiple prompts are not one system digest");
                var scoped = execution.response().metrics().scopedRequestFingerprints();
                assertNotNull(scoped); assertTrue(scoped.completeFor(7));
                assertEquals(4, scoped.requests().stream().map(r -> r.binding().scope()).distinct().count());
                assertEquals(3, execution.toolExecutions().size());
                assertEquals("LEFT=甲-17-29\nRIGHT=乙-31-43", Files.readString(merged));
                assertTrue(execution.response().answer().contains("计划执行完成"));
            }
            assertTrue(Files.exists(episode.resolve("plan-audit.json")));
            assertEquals("value=甲-17-29\n", Files.readString(workspace.resolve("left.txt")));
            assertEquals("value=乙-31-43\n", Files.readString(workspace.resolve("right.txt")));
        }
    }

    private static void writePrivate(Path path, byte[] bytes) throws IOException {
        try (var channel = Files.newByteChannel(path,
                java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    private static final class PlanClient implements LlmClient {
        private final String control;
        private final List<List<Message>> requests = new ArrayList<>();
        PlanClient(String control) { this.control = control; }
        String planResponse() {
            String dependencies = switch (control) {
                case "serialized" -> "[\"left\"]";
                case "invalid_dependency" -> "[\"undeclared\"]";
                default -> "[]";
            };
            return """
                    {"tasks":[
                      {"id":"left","description":"LEFT","type":"FILE_READ","dependencies":[]},
                      {"id":"right","description":"RIGHT","type":"FILE_READ","dependencies":%s},
                      {"id":"merge","description":"MERGE","type":"FILE_WRITE","dependencies":["left","right"]}
                    ]}
                    """.formatted(dependencies);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            requests.add(List.copyOf(messages));
            String input = messages.stream().filter(m -> m.role().equals("user")).findFirst().orElseThrow().content();
            if (input.startsWith("请为以下任务制定执行计划")) return response(planResponse(), List.of());
            String task = capture("当前任务：([^\\r\\n]+)", input);
            var results = messages.stream().filter(m -> m.role().equals("tool")).toList();
            if (task.equals("MERGE")) {
                String merged = "LEFT=" + capture("LEFT=([^\\r\\n]+)", input)
                        + "\nRIGHT=" + capture("RIGHT=([^\\r\\n]+)", input);
                if (results.isEmpty()) return tool("write_file", Map.of("path", "merged.txt", "content", merged));
                return response("merged results", List.of());
            }
            if (results.isEmpty()) return tool("read_file", Map.of("path", task.toLowerCase(java.util.Locale.ROOT) + ".txt"));
            return response(task + "=" + capture("value=([^\\r\\n]+)", results.get(0).content()), List.of());
        }
        private ChatResponse tool(String name, Map<String, String> args) throws IOException {
            return response("", List.of(new ToolCall("call-shared", new ToolCall.Function(name, JSON.writeValueAsString(args)))));
        }
        private ChatResponse response(String text, List<ToolCall> calls) {
            return new ChatResponse("assistant", text, null, calls, control.equals("episode_budget") ? 60_000 : 100,
                    30, 0, getModelName(), true);
        }
        private String capture(String regex, String input) throws IOException {
            var matcher = Pattern.compile(regex).matcher(input);
            if (!matcher.find()) throw new IOException("required native input not observed");
            return matcher.group(1);
        }
        public String getModelName() { return "deepseek-v4-flash"; }
        public String getProviderName() { return "deepseek"; }
        public int maxContextWindow() { return 1_000_000; }
    }
}
