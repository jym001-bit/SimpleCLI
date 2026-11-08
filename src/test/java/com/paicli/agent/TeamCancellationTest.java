package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.AbstractOpenAiCompatibleClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.tool.CommandCancellationFixture;
import com.paicli.tool.CommandExecutionObserver;
import com.paicli.tool.ToolRegistry;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TeamCancellationTest {
    @TempDir Path temp;
    private static final PrintStream SILENT = new PrintStream(OutputStream.nullOutputStream());
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void parallelTeamCancelsActualHttpCallsAndQueuedWorkersWithoutAffectingIndependentTeam() throws Exception {
        CountDownLatch aRequests = new CountDownLatch(2), bRequest = new CountDownLatch(1), releaseB = new CountDownLatch(1);
        List<Call> aCalls = new CopyOnWriteArrayList<>(), bCalls = new CopyOnWriteArrayList<>();
        List<String> actors = new CopyOnWriteArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    String body = request.getBody().readUtf8();
                    if (body.contains("HTTP_A")) { aRequests.countDown(); return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE); }
                    bRequest.countDown();
                    if (!releaseB.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"independent success\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n");
                }
            });
            OkHttpClient transport = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                    .callTimeout(15, TimeUnit.SECONDS).build();
            // HTTP calls execute on private Team pool threads, so identify them by the request Token.
            CancellationToken tokenA = new CancellationToken(), tokenB = new CancellationToken();
            transport = transport.newBuilder().eventListenerFactory(call -> {
                if (CancellationContext.current() == tokenA) aCalls.add(call); else bCalls.add(call);
                return EventListener.NONE;
            }).build();
            HttpClient client = new HttpClient(server.url("/chat/completions").toString(), transport, actors);
            var a = team(client, "a"); var b = team(client, "b");
            List<TeamExecutionObserver.Event> events = new CopyOnWriteArrayList<>();
            a.setExecutionObserver(events::add);
            ExecutorService roots = Executors.newFixedThreadPool(2);
            try {
                // Prewarm the executor so correctness cannot depend on inherited thread locals.
                roots.submit(() -> {}).get(); roots.submit(() -> {}).get();
                Future<String> aResult = roots.submit(() -> inRun(tokenA, () -> a.run("ROOT_A")));
                Future<String> bResult = roots.submit(() -> inRun(tokenB, () -> b.run("ROOT_B")));
                assertTrue(aRequests.await(10, TimeUnit.SECONDS));
                assertTrue(bRequest.await(10, TimeUnit.SECONDS));
                tokenA.cancel();
                assertTrue(aResult.get(9, TimeUnit.SECONDS).startsWith("⏹️"));
                assertEquals(2, aCalls.size()); assertTrue(aCalls.stream().allMatch(Call::isCanceled));
                assertEquals(1, bCalls.size()); assertFalse(bCalls.get(0).isCanceled());
                assertFalse(bResult.isDone());
                assertFalse(actors.stream().anyMatch(s -> s.startsWith("REVIEW_A") || s.contains("QUEUED_A")));
                assertEquals(TeamExecutionObserver.RunExitReason.CANCELLED, events.stream()
                        .filter(TeamExecutionObserver.RunExited.class::isInstance)
                        .map(TeamExecutionObserver.RunExited.class::cast).findFirst().orElseThrow().reason());
                releaseB.countDown();
                assertTrue(bResult.get(5, TimeUnit.SECONDS).startsWith("✅"));
                assertEquals(3, server.getRequestCount(), "No reviewer, retry or queued HTTP request for cancelled Team");
            } finally { tokenA.cancel(); tokenB.cancel(); releaseB.countDown(); roots.shutdownNow(); roots.awaitTermination(10, TimeUnit.SECONDS); }
            transport.connectionPool().evictAll(); transport.dispatcher().executorService().shutdownNow();
        }
    }

    @Test void cancellationDuringReviewerRetainsWorkerOutputAndDoesNotRetry() throws Exception {
        CancellationToken token = new CancellationToken(); AtomicInteger requests = new AtomicInteger();
        Client client = new Client((messages, listener) -> {
            return switch (requests.incrementAndGet()) {
                case 1 -> text(plan("ONE"));
                case 2 -> text("VERIFIED_WORKER_OUTPUT");
                case 3 -> { token.cancel(); throw new IOException("review cancelled"); }
                default -> throw new AssertionError("Unexpected retry");
            };
        });
        String result = inRun(token, () -> team(client, "review").run("ROOT"));
        assertTrue(result.startsWith("⏹️")); assertTrue(result.contains("VERIFIED_WORKER_OUTPUT"));
        assertEquals(3, requests.get());
    }

    @Test void cancellationDuringRetryRetainsFirstResultAndStreamedPartialWithoutAnotherReviewer() throws Exception {
        CancellationToken token = new CancellationToken(); AtomicInteger requests = new AtomicInteger();
        Client client = new Client((messages, listener) -> switch (requests.incrementAndGet()) {
            case 1 -> text(plan("ONE"));
            case 2 -> text("FIRST_RESULT");
            case 3 -> text("{\"approved\":false,\"issues\":[\"check again\"]}");
            case 4 -> { listener.onContentDelta("ACTUAL_RETRY_PARTIAL"); token.cancel(); throw new IOException("cancelled"); }
            default -> throw new AssertionError("Unexpected request after cancellation");
        });
        String result = inRun(token, () -> team(client, "retry").run("ROOT"));
        assertTrue(result.startsWith("⏹️")); assertTrue(result.contains("FIRST_RESULT"));
        assertTrue(result.contains("ACTUAL_RETRY_PARTIAL")); assertEquals(4, requests.get());
    }

    @Test void parallelWorkerCommandCancellationRetainsCompletedAndPartialResults() throws Exception {
        CancellationToken token = new CancellationToken(); Path ready = temp.resolve("worker-child.pid");
        var commandEvents = new CopyOnWriteArrayList<CommandExecutionObserver.Event>();
        AtomicInteger reviews = new AtomicInteger(); CountDownLatch blocking = new CountDownLatch(1);
        Client client = new Client((messages, listener) -> {
            String input = user(messages);
            if (input.startsWith("请为以下任务制定执行计划")) return text("{\"steps\":["
                    + node("first", "FINISHED", List.of()) + ","
                    + node("command", "COMMAND_BRANCH", List.of("first")) + ","
                    + node("blocking", "BLOCKING_BRANCH", List.of("first")) + ","
                    + node("queued", "QUEUED", List.of("first")) + "]}");
            if (input.startsWith("原始任务：")) { reviews.incrementAndGet(); return text("{\"approved\":true}"); }
            if (input.endsWith("FINISHED")) return text("DONE_FIRST");
            if (input.contains("COMMAND_BRANCH")) return tool("execute_command", JSON.writeValueAsString(
                    Map.of("command", CommandCancellationFixture.command(ready))));
            if (input.contains("BLOCKING_BRANCH")) {
                CountDownLatch cancelled = new CountDownLatch(1);
                try (var registration = CancellationContext.current().onCancel(cancelled::countDown)) {
                    blocking.countDown();
                    try { cancelled.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new IOException("cancelled blocking worker");
                }
            }
            throw new AssertionError("Queued or unexpected work: " + input);
        });
        ToolRegistry registry = new ToolRegistry(); registry.setProjectPath(temp.toString());
        registry.setCommandExecutionObserver(commandEvents::add);
        AgentOrchestrator agent = team(client, registry, "command");
        ExecutorService root = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = root.submit(() -> inRun(token, () -> agent.run("ROOT")));
            assertTrue(blocking.await(10, TimeUnit.SECONDS)); CommandCancellationFixture.awaitReady(ready);
            token.cancel(); String cancelled = result.get(9, TimeUnit.SECONDS);
            assertTrue(cancelled.startsWith("⏹️"), cancelled);
            assertTrue(cancelled.contains("DONE_FIRST"), cancelled);
            assertTrue(cancelled.contains("ACTUAL_PARTIAL_OUTPUT"), cancelled);
            assertEquals(1, reviews.get());
            assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(ready))).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(1, commandEvents.stream().filter(e -> e.phase() == CommandExecutionObserver.Phase.STARTED).count());
        } finally { token.cancel(); root.shutdownNow(); root.awaitTermination(10, TimeUnit.SECONDS); }
    }

    @Test void teamWithoutCallerTokenDoesNotBorrowOtherRunsCancelledGlobalToken() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(); pool.submit(() -> {}).get();
        CancellationToken unrelated = CancellationContext.startRun(); unrelated.cancel();
        try {
            Client client = new Client((messages, listener) -> {
                assertNotSame(unrelated, CancellationContext.current());
                assertNotNull(CancellationContext.currentThreadToken());
                return text(user(messages).startsWith("请为以下任务制定执行计划") ? plan("ONE")
                        : user(messages).startsWith("原始任务：") ? "{\"approved\":true}" : "success");
            });
            assertTrue(pool.submit(() -> team(client, "unscoped").run("ROOT")).get(5, TimeUnit.SECONDS).startsWith("✅"));
        } finally { CancellationContext.clear(unrelated); pool.shutdownNow(); }
    }

    @Test void nonCooperativeWorkerDoesNotBlockCancellationForeverOrAllowActorReuseDuringCleanup() throws Exception {
        CancellationToken token = new CancellationToken(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        Client client = new Client((messages, listener) -> {
            requests.incrementAndGet();
            if (user(messages).startsWith("请为以下任务制定执行计划")) return text(plan("STUCK"));
            started.countDown();
            while (true) {
                try { release.await(); break; } catch (InterruptedException ignored) { /* Deliberately unsupported client. */ }
            }
            return text("late output");
        });
        AgentOrchestrator agent = team(client, "stuck"); ExecutorService root = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = root.submit(() -> inRun(token, () -> agent.run("ROOT")));
            assertTrue(started.await(5, TimeUnit.SECONDS)); token.cancel();
            assertTrue(result.get(9, TimeUnit.SECONDS).startsWith("⏹️"));
            assertTrue(agent.run("another run").contains("清理"));
            assertEquals(2, requests.get());
        } finally { release.countDown(); token.cancel(); root.shutdownNow(); root.awaitTermination(10, TimeUnit.SECONDS); }
    }

    private AgentOrchestrator team(LlmClient client, String directory) throws Exception {
        ToolRegistry registry = new ToolRegistry(); registry.setProjectPath(temp.toString());
        return team(client, registry, directory);
    }
    private AgentOrchestrator team(LlmClient client, ToolRegistry registry, String directory) throws Exception {
        Path memoryDir = Files.createDirectories(temp.resolve(directory));
        MemoryManager memory = new MemoryManager(client, 32768, 200000, new LongTermMemory(memoryDir.toFile()));
        return new AgentOrchestrator(client, registry, memory, SILENT);
    }
    private static String inRun(CancellationToken token, Callable<String> body) throws Exception {
        try (var ignored = CancellationContext.bind(token)) { return body.call(); }
    }
    private static String user(List<LlmClient.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).role().equals("user")) return messages.get(i).content();
        return "";
    }
    private static String node(String id, String task, List<String> dependencies) throws IOException {
        return JSON.writeValueAsString(Map.of("id", id, "description", task, "type", "ANALYSIS", "dependencies", dependencies));
    }
    private static String plan(String... tasks) throws IOException {
        List<String> nodes = new ArrayList<>(); for (int i = 0; i < tasks.length; i++) nodes.add(node("s" + i, tasks[i], List.of()));
        return "{\"steps\":[" + String.join(",", nodes) + "]}";
    }
    private static LlmClient.ChatResponse text(String content) { return new LlmClient.ChatResponse("assistant", content, null, 1, 1); }
    private static LlmClient.ChatResponse tool(String name, String args) {
        return new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall("call", new LlmClient.ToolCall.Function(name, args))), 1, 1);
    }
    @FunctionalInterface interface Script { LlmClient.ChatResponse chat(List<LlmClient.Message> messages, LlmClient.StreamListener listener) throws IOException; }
    private static class Client implements LlmClient {
        private final Script script; Client(Script script) { this.script = script; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException { return script.chat(messages, listener); }
        @Override public String getModelName() { return "controlled"; }
        @Override public String getProviderName() { return "local"; }
    }
    private static class HttpClient extends AbstractOpenAiCompatibleClient {
        private final String url; private final OkHttpClient transport; private final List<String> actors;
        HttpClient(String url, OkHttpClient transport, List<String> actors) { this.url = url; this.transport = transport; this.actors = actors; }
        @Override protected String getApiUrl() { return url; }
        @Override protected String getModel() { return "local-http"; }
        @Override protected String getApiKey() { return "not-a-real-key"; }
        @Override protected OkHttpClient httpClient() { return transport; }
        @Override public String getModelName() { return getModel(); }
        @Override public String getProviderName() { return "local-http"; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            String input = user(messages);
            if (input.startsWith("请为以下任务制定执行计划")) return text(input.contains("ROOT_A")
                    ? plan("HTTP_A_1", "HTTP_A_2", "QUEUED_A_1", "QUEUED_A_2") : plan("HTTP_B"));
            if (input.startsWith("原始任务：")) { actors.add(input.contains("HTTP_A") ? "REVIEW_A" : "REVIEW_B"); return text("{\"approved\":true}"); }
            actors.add(input);
            return super.chat(messages, tools, listener);
        }
    }
}
