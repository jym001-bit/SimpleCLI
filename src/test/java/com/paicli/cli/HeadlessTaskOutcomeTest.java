package com.paicli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.agent.ExecutionOutcome;
import com.paicli.llm.LlmClient;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeEvent;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.task.DurableTask;
import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.runtime.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real Main headless wiring, actual Agent/ToolRegistry, SQLite and loopback HTTP; no model API. */
class HeadlessTaskOutcomeTest {
    @TempDir Path temp;
    private final Map<String, String> savedProperties = new LinkedHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    enum Scenario {
        NORMAL, STREAM_SUCCESS, MODEL_ERROR, STREAM_ERROR, CANCELLED,
        HARD_LIMIT, TOKEN_LIMIT, STAGNATION, TOOL_THEN_ERROR, FINALIZATION_ERROR;

        ExecutionOutcome.Reason reason() {
            return switch (this) {
                case NORMAL, STREAM_SUCCESS -> ExecutionOutcome.Reason.COMPLETED;
                case MODEL_ERROR, STREAM_ERROR, TOOL_THEN_ERROR -> ExecutionOutcome.Reason.MODEL_ERROR;
                case CANCELLED -> ExecutionOutcome.Reason.CANCELLED;
                case HARD_LIMIT, FINALIZATION_ERROR -> ExecutionOutcome.Reason.HARD_ITERATION_LIMIT;
                case TOKEN_LIMIT -> ExecutionOutcome.Reason.TOKEN_BUDGET_EXCEEDED;
                case STAGNATION -> ExecutionOutcome.Reason.STAGNATION_DETECTED;
            };
        }

        int calls() {
            return switch (this) {
                case HARD_LIMIT, TOKEN_LIMIT, TOOL_THEN_ERROR, FINALIZATION_ERROR -> 2;
                case STAGNATION -> 3;
                default -> 1;
            };
        }
    }

    @BeforeEach void isolate() {
        set("user.home", temp.resolve("home").toString());
        set("paicli.log.dir", Path.of("target", "headless-task-test-logs").toAbsolutePath().toString());
        set("paicli.memory.auto.extract.enabled", "false");
        set("paicli.command.sandbox", "off");
        set("paicli.react.hard.max.iterations", "10");
        set("paicli.react.token.budget", "1000000");
        set("paicli.react.stagnation.window", "5");
    }

    @AfterEach void restore() {
        Thread.interrupted();
        savedProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }

    private void set(String key, String value) {
        if (!savedProperties.containsKey(key)) savedProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void configure(Scenario scenario) {
        if (scenario == Scenario.HARD_LIMIT || scenario == Scenario.FINALIZATION_ERROR)
            set("paicli.react.hard.max.iterations", "1");
        if (scenario == Scenario.TOKEN_LIMIT) set("paicli.react.token.budget", "1");
        if (scenario == Scenario.STAGNATION) set("paicli.react.stagnation.window", "2");
    }

    @ParameterizedTest @EnumSource(Scenario.class)
    void actualHeadlessResultSurvivesBackgroundPersistence(Scenario scenario) throws Exception {
        configure(scenario);
        ControlledClient client = new ControlledClient(scenario);
        Path database = temp.resolve("tasks.db");
        String taskId;
        try (DurableTaskManager manager = new DurableTaskManager(database, prompt -> headless(prompt, client), 1)) {
            manager.start();
            taskId = manager.enqueue("inspect").id();
            await(() -> manager.find(taskId).orElseThrow().terminal());
            DurableTask result = manager.find(taskId).orElseThrow();
            assertEquals(scenario.reason(), result.reason());
            assertEquals(expectedStatus(scenario), result.status());
            assertOutput(scenario, result.result());
            assertEquals(scenario.calls(), client.calls);
        }
        try (DurableTaskManager reopened = new DurableTaskManager(database, prompt -> fail("must not replay"), 1)) {
            assertEquals(scenario.reason(), reopened.find(taskId).orElseThrow().reason());
            assertEquals(expectedStatus(scenario), reopened.find(taskId).orElseThrow().status());
        }
    }

    @ParameterizedTest @EnumSource(Scenario.class)
    void actualHeadlessResultSurvivesHttpAndStoredEvents(Scenario scenario) throws Exception {
        configure(scenario);
        ControlledClient client = new ControlledClient(scenario);
        try (RuntimeThreadStore store = new RuntimeThreadStore(temp.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> headless(prompt, client), 0, "local-test")) {
            server.start();
            String threadId = store.createThread();
            String base = "http://127.0.0.1:" + server.port() + "/v1/threads/" + threadId;
            HttpClient http = HttpClient.newHttpClient();
            var submitted = http.send(request(base + "/turns").POST(
                    HttpRequest.BodyPublishers.ofString("{\"input\":\"inspect\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, submitted.statusCode());
            String turnId = JSON.readTree(submitted.body()).path("id").asText();
            await(() -> store.events(threadId, 0).stream().anyMatch(e -> terminal(e.type())));
            List<RuntimeEvent> events = store.events(threadId, 0);
            var endings = events.stream().filter(e -> terminal(e.type())).toList();
            assertEquals(1, endings.size());
            assertEquals("turn." + expectedStatus(scenario).value(), endings.get(0).type());
            var end = JSON.readTree(endings.get(0).data());
            assertEquals(turnId, end.path("turn_id").asText());
            assertEquals(scenario.reason().name(), end.path("reason").asText());
            assertEquals(expectedStatus(scenario).value(), end.path("status").asText());
            RuntimeEvent content = events.stream().filter(e -> e.type().equals("message.delta")).findFirst().orElseThrow();
            assertOutput(scenario, JSON.readTree(content.data()).path("content").asText());
            var sse = http.send(request(base + "/events").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(sse.body().contains("event: " + endings.get(0).type()));
            if (scenario.reason() != ExecutionOutcome.Reason.COMPLETED)
                assertFalse(sse.body().contains("event: turn.completed"));
            assertEquals(scenario.calls(), client.calls);
        }
    }

    @Test void stringApiStillSuppressesAlreadyStreamedSuccessWhileTypedApiRetainsIt() {
        Agent legacy = new Agent(new ControlledClient(Scenario.STREAM_SUCCESS));
        assertEquals("", legacy.run("inspect"));
        Agent typed = new Agent(new ControlledClient(Scenario.STREAM_SUCCESS));
        assertEquals("answer\tpart", typed.runWithOutcome("inspect").content());
    }

    @Test void userCancelReachesActualHeadlessAgentAndRetainsItsPartialOutput() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ControlledClient client = new ControlledClient(Scenario.NORMAL) {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                listener.onContentDelta("partial\toutput");
                started.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                    throw new IOException("test timed out awaiting cancellation");
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("request interrupted", cancelled);
                }
            }
        };
        try (DurableTaskManager manager = new DurableTaskManager(temp.resolve("tasks.db"),
                prompt -> prompt.equals("barrier") ? ExecutionOutcome.completed("done") : headless(prompt, client), 1)) {
            manager.start();
            String id = manager.enqueue("inspect").id();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(manager.cancel(id));
            String barrier = manager.enqueue("barrier").id();
            await(() -> manager.find(barrier).orElseThrow().terminal());
            DurableTask canceled = manager.find(id).orElseThrow();
            assertEquals(TaskStatus.CANCELED, canceled.status());
            assertEquals(ExecutionOutcome.Reason.CANCELLED, canceled.reason());
            assertEquals(com.paicli.runtime.task.TaskCleanupState.STOPPED, canceled.cleanupState());
            assertTrue(canceled.result().contains("partial\toutput"));
            assertEquals(TaskStatus.COMPLETED, manager.find(barrier).orElseThrow().status());
        }
    }

    private static TaskStatus expectedStatus(Scenario scenario) {
        return scenario.reason() == ExecutionOutcome.Reason.COMPLETED ? TaskStatus.COMPLETED
                : scenario == Scenario.CANCELLED ? TaskStatus.CANCELED : TaskStatus.FAILED;
    }

    private static void assertOutput(Scenario scenario, String output) {
        assertNotNull(output);
        switch (scenario) {
            case NORMAL -> assertTrue(output.contains("失败和预算只是普通正文"));
            case STREAM_SUCCESS -> assertEquals("answer\tpart", output);
            case STREAM_ERROR, CANCELLED -> assertTrue(output.contains("partial\toutput"));
            case MODEL_ERROR -> assertTrue(output.contains("controlled failure"));
            case HARD_LIMIT, TOKEN_LIMIT, STAGNATION -> assertTrue(output.contains("retained summary"));
            case TOOL_THEN_ERROR, FINALIZATION_ERROR -> {
                assertTrue(output.contains("<artifactId>"), "Actual pom.xml tool output must survive failure");
                assertTrue(output.contains("controlled failure"));
            }
        }
    }

    private static boolean terminal(String type) {
        return List.of("turn.completed", "turn.failed", "turn.canceled").contains(type);
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer local-test").header("Content-Type", "application/json");
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < end) {
            if (done.getAsBoolean()) return;
            Thread.sleep(15);
        }
        fail("operation did not finish");
    }

    private static ExecutionOutcome headless(String prompt, LlmClient client) throws Exception {
        var method = Main.class.getDeclaredMethod("runHeadlessTask", String.class, LlmClient.class);
        method.setAccessible(true);
        try { return (ExecutionOutcome) method.invoke(null, prompt, client); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static class ControlledClient implements LlmClient {
        private final Scenario scenario;
        private volatile int calls;
        ControlledClient(Scenario scenario) { this.scenario = scenario; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls++;
            if (scenario == Scenario.MODEL_ERROR || scenario == Scenario.STREAM_ERROR
                    || scenario == Scenario.CANCELLED || scenario == Scenario.TOOL_THEN_ERROR && calls > 1
                    || scenario == Scenario.FINALIZATION_ERROR && calls > 1) {
                if (scenario == Scenario.STREAM_ERROR || scenario == Scenario.CANCELLED)
                    listener.onContentDelta("partial\toutput");
                if (scenario == Scenario.CANCELLED) Thread.currentThread().interrupt();
                throw new IOException("controlled failure");
            }
            if (scenario == Scenario.HARD_LIMIT || scenario == Scenario.TOKEN_LIMIT
                    || scenario == Scenario.STAGNATION || scenario == Scenario.TOOL_THEN_ERROR
                    || scenario == Scenario.FINALIZATION_ERROR) {
                if (calls < scenario.calls() || scenario == Scenario.TOOL_THEN_ERROR) {
                    assertTrue(Files.isRegularFile(Path.of("pom.xml")));
                    return new ChatResponse("assistant", "", List.of(new ToolCall("call-" + calls,
                            new ToolCall.Function("read_file", "{\"path\":\"pom.xml\",\"limit\":10}"))), 2, 2);
                }
                assertTrue(tools.isEmpty(), "Budget finalization must remain tool-free");
                return new ChatResponse("assistant", "retained summary", null, 2, 2);
            }
            if (scenario == Scenario.STREAM_SUCCESS) listener.onContentDelta("answer\tpart");
            return new ChatResponse("assistant", scenario == Scenario.STREAM_SUCCESS
                    ? "answer\tpart" : "失败和预算只是普通正文", null, 2, 2);
        }
        @Override public String getModelName() { return "local-controlled"; }
        @Override public String getProviderName() { return "offline"; }
    }
}
