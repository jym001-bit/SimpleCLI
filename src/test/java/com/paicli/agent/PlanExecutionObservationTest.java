package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.PlanExecutionObserver;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.paicli.plan.PlanExecutionObserver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native, offline controls for E1 groundwork; these are not a generated E1 recipe or model scores. */
class PlanExecutionObservationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOAL = "并行读取 left.txt 与 right.txt 两个独立文件，最后合并完整结果到 merged.txt；不要联网。";
    @TempDir Path tempDir;

    @Test
    void recordsRealTaskWindowsNormalizedDagAndCompleteDependencyInputs() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var tools = new BarrierReads();
        var client = new MergeClient(false);
        var agent = agent(client, tools, events::add);
        String answer = agent.runExplicitTask(GOAL, GOAL);

        assertTrue(answer.contains("计划执行完成"));
        assertEquals(7, client.calls);
        assertEquals(2, tools.threads.size(), "barrier was entered by two real task threads");
        assertFalse(tools.barrierFailed);
        assertEquals(0, agent.getExecutionObservationFailures());
        PlanStarted start = only(events, PlanStarted.class);
        assertEquals(List.of("task_1", "task_2", "task_3"), start.executionOrder());
        assertEquals(List.of(), start.tasks().get(0).dependencies());
        assertEquals(List.of(), start.tasks().get(1).dependencies());
        assertEquals(List.of("task_1", "task_2"), start.tasks().get(2).dependencies());
        assertTrue(overlap(events, "task_1", "task_2") > 0);
        long branchEnd = Math.max(exit(events, "task_1").elapsedNanos(), exit(events, "task_2").elapsedNanos());
        assertTrue(entered(events, "task_3").elapsedNanos() >= branchEnd);

        var mergeInput = select(events, TaskInputPrepared.class).stream()
                .filter(event -> event.taskId().equals("task_3")).findFirst().orElseThrow();
        assertEquals(TextFingerprint.of(client.inputs.get("MERGE")), mergeInput.userText());
        assertEquals(0, mergeInput.imagePartCount());
        assertEquals(List.of("task_1", "task_2"), mergeInput.dependencies().stream()
                .map(DependencyInput::taskId).toList());
        for (var dependency : mergeInput.dependencies()) {
            assertEquals(Task.TaskStatus.COMPLETED, dependency.status());
            assertEquals(exit(events, dependency.taskId()).result(), dependency.result());
        }
        assertEquals(client.outputs.get("LEFT") + "\n===\n" + client.outputs.get("RIGHT"),
                Files.readString(tempDir.resolve("merged.txt")));
        assertTrue(client.mergeConsumedBoth, "script consumed both entire real dependency outputs");

        var batches = select(events, ToolBatchReturned.class);
        assertEquals(3, batches.size());
        assertEquals(Set.of("task_1", "task_2", "task_3"), batches.stream()
                .map(ToolBatchReturned::taskId).collect(java.util.stream.Collectors.toSet()));
        for (var batch : batches) {
            assertEquals(1, batch.iteration());
            assertEquals(1, batch.results().size());
            assertEquals(0, batch.results().get(0).ordinal());
            assertEquals("call-shared", batch.results().get(0).callId());
            assertTrue(batch.results().get(0).successful());
        }
        assertTrue(events.stream().allMatch(event -> event.executionId().equals(start.executionId())));
        assertThrows(UnsupportedOperationException.class, () -> start.tasks().clear());
        assertThrows(UnsupportedOperationException.class, () -> start.tasks().get(2).dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> mergeInput.dependencies().clear());
        String serialized = JSON.writeValueAsString(events);
        assertFalse(serialized.contains("甲-17-29"), "observations must not contain raw tool payloads");
        assertFalse(serialized.contains(GOAL), "observations must not contain raw user prompts");
    }

    @Test
    void artificialDependencyDoesNotProduceParallelWindowsEvenWhenFinalArtifactIsCorrect() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var client = new MergeClient(true);
        var agent = agent(client, new ToolRegistry(), events::add);
        assertTrue(agent.runExplicitTask(GOAL, GOAL).contains("计划执行完成"));
        assertEquals(List.of("task_1"), only(events, PlanStarted.class).tasks().get(1).dependencies());
        assertTrue(overlap(events, "task_1", "task_2") <= 0);
        assertEquals(client.outputs.get("LEFT") + "\n===\n" + client.outputs.get("RIGHT"),
                Files.readString(tempDir.resolve("merged.txt")));
        // A correct final file alone cannot pass the frozen E1 parallelism requirement.
    }

    @Test
    void queuedFifthTaskHasNoEnteredEventUntilAnExecutorThreadIsAvailable() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var occupied = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var tools = new ToolRegistry() {
            @Override public String executeTool(String name, String arguments) {
                if (name.equals("read_file")) {
                    occupied.countDown();
                    await(release);
                }
                return super.executeTool(name, arguments);
            }
        };
        var client = new QueueClient();
        var agent = agent(client, tools, events::add);
        var executor = Executors.newSingleThreadExecutor();
        var running = executor.submit(() -> agent.runExplicitTask(GOAL, GOAL));
        try {
            assertTrue(occupied.await(10, TimeUnit.SECONDS), "four task bodies must reach real tools");
            assertEquals(4, select(events, TaskEntered.class).size());
            assertFalse(select(events, TaskEntered.class).stream().anyMatch(event -> event.taskId().equals("task_5")));
            release.countDown();
            assertTrue(running.get(10, TimeUnit.SECONDS).contains("计划执行完成"));
            assertEquals(5, select(events, TaskEntered.class).size());
            assertEquals(5, select(events, TaskExited.class).size());
            long firstActualExit = select(events, TaskExited.class).stream()
                    .filter(event -> !event.taskId().equals("task_5"))
                    .mapToLong(TaskExited::elapsedNanos).min().orElseThrow();
            assertTrue(entered(events, "task_5").elapsedNanos() >= firstActualExit);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void observerFailureIsExplicitAndDoesNotChangeProductOutcome() throws Exception {
        var client = new MergeClient(false);
        var agent = agent(client, new ToolRegistry(), event -> {
            if (event instanceof TaskInputPrepared) throw new IllegalStateException("private sink error");
        });
        assertTrue(agent.runExplicitTask(GOAL, GOAL).contains("计划执行完成"));
        assertEquals(1, agent.getExecutionObservationFailures());
        assertTrue(client.mergeConsumedBoth);
        assertTrue(Files.exists(tempDir.resolve("merged.txt")));
    }

    @Test
    void defaultDisabledObserverPreservesTheSameProductResult() throws Exception {
        var client = new MergeClient(false);
        var agent = agent(client, new ToolRegistry(), null);
        assertTrue(agent.runExplicitTask(GOAL, GOAL).contains("计划执行完成"));
        assertEquals(0, agent.getExecutionObservationFailures());
        assertEquals(7, client.calls);
        assertTrue(client.mergeConsumedBoth);
    }

    @Test
    void failureAndReplanHaveDistinctExecutionIdsEvenWhenTaskAndPlanIdsAreReused() throws Exception {
        var calls = new AtomicInteger();
        var client = new BaseClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                    throws IOException {
                if (calls.incrementAndGet() == 1) throw new IOException("private failure detail");
                return text("replanned result");
            }
        };
        var planner = new Planner(client, quiet()) {
            @Override public ExecutionPlan createPlan(String goal) {
                var plan = new ExecutionPlan("same-plan-id", goal);
                plan.addTask(new Task("same-task-id", "分析", Task.TaskType.ANALYSIS));
                plan.computeExecutionOrder();
                return plan;
            }
        };
        var tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        var agent = new PlanExecuteAgent(client, tools, planner, memory(client),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), quiet());
        var events = new CopyOnWriteArrayList<Event>();
        agent.setExecutionObserver(events::add);
        assertTrue(agent.runExplicitTask(GOAL, GOAL).contains("replanned result"));
        var plans = select(events, PlanStarted.class);
        assertEquals(2, plans.size());
        assertEquals(plans.get(0).planId(), plans.get(1).planId());
        assertNotEquals(plans.get(0).executionId(), plans.get(1).executionId());
        var exits = select(events, TaskExited.class);
        assertEquals(ExitKind.THREW, exits.get(0).kind());
        assertEquals(IOException.class.getName(), exits.get(0).exceptionType());
        assertFalse(exits.get(0).result().present());
        assertEquals(ExitKind.RETURNED, exits.get(1).kind());
        assertEquals(TextFingerprint.of("replanned result"), exits.get(1).result());
        assertFalse(JSON.writeValueAsString(events).contains("private failure detail"));
    }

    @Test
    void fingerprintsDistinguishNullEmptyAndUtf8Text() {
        assertEquals(new TextFingerprint(false, 0, null), TextFingerprint.of(null));
        assertNotEquals(TextFingerprint.of(null), TextFingerprint.of(""));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                TextFingerprint.of("").sha256());
        assertEquals(3, TextFingerprint.of("甲").utf8Bytes());
        assertEquals(TextFingerprint.of("甲"), TextFingerprint.of("甲"));
        assertNotEquals(TextFingerprint.of("甲"), TextFingerprint.of("甲\n"));
    }

    @Test
    void toolObservationIncludesPolicyDenialAndPreservesBatchOrdinals() throws Exception {
        var calls = new AtomicInteger();
        var client = new BaseClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                    throws IOException {
                return switch (calls.incrementAndGet()) {
                    case 1 -> text("{\"tasks\":[{\"id\":\"read\",\"description\":\"READ\"}]}");
                    case 2 -> new ChatResponse("assistant", "", List.of(
                            new ToolCall("read", new ToolCall.Function("read_file", "{\"path\":\"left.txt\"}")),
                            new ToolCall("denied", new ToolCall.Function("web_fetch",
                                    "{\"url\":\"https://untrusted.invalid/page\"}"))), 1, 1);
                    default -> text("read local data; external request denied");
                };
            }
        };
        var events = new CopyOnWriteArrayList<Event>();
        var agent = agent(client, new ToolRegistry(), events::add);
        assertTrue(agent.runExplicitTask(GOAL, GOAL).contains("计划执行完成"));
        var batch = only(events, ToolBatchReturned.class);
        assertEquals(List.of(0, 1), batch.results().stream().map(ToolResult::ordinal).toList());
        assertEquals(List.of("read", "denied"), batch.results().stream().map(ToolResult::callId).toList());
        assertTrue(batch.results().get(0).successful());
        assertFalse(batch.results().get(1).successful());
        assertEquals(TextFingerprint.of("{\"url\":\"https://untrusted.invalid/page\"}"),
                batch.results().get(1).arguments());
    }

    private PlanExecuteAgent agent(BaseClient client, ToolRegistry tools, PlanExecutionObserver observer)
            throws IOException {
        Files.writeString(tempDir.resolve("left.txt"), "value=甲-17-29\n");
        Files.writeString(tempDir.resolve("right.txt"), "value=乙-31-43\n");
        for (int i = 0; i < 5; i++) Files.writeString(tempDir.resolve("q" + i + ".txt"), "queue " + i);
        tools.setProjectPath(tempDir.toString());
        var agent = new PlanExecuteAgent(client, tools, memory(client),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), quiet());
        agent.setExecutionObserver(observer);
        return agent;
    }

    private MemoryManager memory(LlmClient client) {
        return new MemoryManager(client, 4096, 1_000_000,
                new LongTermMemory(tempDir.resolve("memory").toFile()));
    }

    private static PrintStream quiet() { return new PrintStream(OutputStream.nullOutputStream()); }
    private static <T extends Event> List<T> select(List<Event> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
    private static <T extends Event> T only(List<Event> events, Class<T> type) {
        var matching = select(events, type);
        assertEquals(1, matching.size());
        return matching.get(0);
    }
    private static TaskEntered entered(List<Event> events, String id) {
        return select(events, TaskEntered.class).stream().filter(event -> event.taskId().equals(id))
                .findFirst().orElseThrow();
    }
    private static TaskExited exit(List<Event> events, String id) {
        return select(events, TaskExited.class).stream().filter(event -> event.taskId().equals(id))
                .findFirst().orElseThrow();
    }
    private static long overlap(List<Event> events, String first, String second) {
        return Math.min(exit(events, first).elapsedNanos(), exit(events, second).elapsedNanos())
                - Math.max(entered(events, first).elapsedNanos(), entered(events, second).elapsedNanos());
    }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("local control barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("local control interrupted", e);
        }
    }
    private static final class BarrierReads extends ToolRegistry {
        final Set<Long> threads = ConcurrentHashMap.newKeySet();
        final CountDownLatch both = new CountDownLatch(2);
        volatile boolean barrierFailed;
        @Override public String executeTool(String name, String arguments) {
            if (name.equals("read_file")) {
                threads.add(Thread.currentThread().getId());
                both.countDown();
                try { await(both); } catch (RuntimeException e) { barrierFailed = true; throw e; }
            }
            return super.executeTool(name, arguments);
        }
    }
    private abstract static class BaseClient implements LlmClient {
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        public String getModelName() { return "scripted-control"; }
        public String getProviderName() { return "offline-control"; }
        public int maxContextWindow() { return 1_000_000; }
        static ChatResponse text(String value) { return new ChatResponse("assistant", value, null, 1, 1); }
        static ChatResponse tool(String name, Map<String, String> args) throws IOException {
            return new ChatResponse("assistant", "", List.of(new ToolCall("call-shared",
                    new ToolCall.Function(name, JSON.writeValueAsString(args)))), 1, 1);
        }
        static String input(List<Message> messages) {
            return messages.stream().filter(message -> message.role().equals("user"))
                    .findFirst().orElseThrow().content();
        }
        static String task(String input) {
            var matcher = Pattern.compile("当前任务：([^\\r\\n]+)").matcher(input);
            if (!matcher.find()) throw new IllegalStateException("missing actual task input");
            return matcher.group(1);
        }
    }
    private static final class MergeClient extends BaseClient {
        final boolean serialized;
        final Map<String, String> inputs = new HashMap<>();
        final Map<String, String> outputs = new HashMap<>();
        int calls;
        boolean mergeConsumedBoth;
        MergeClient(boolean serialized) { this.serialized = serialized; }
        // Match the real relay's serialized provider exchange; overlap must occur in local tools.
        @Override public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            calls++;
            String input = input(messages);
            if (input.startsWith("请为以下任务制定执行计划")) {
                return text("""
                        {"summary":"two roots and merge","tasks":[
                          {"id":"left","description":"LEFT","type":"FILE_READ","dependencies":[]},
                          {"id":"right","description":"RIGHT","type":"FILE_READ","dependencies":%s},
                          {"id":"merge","description":"MERGE","type":"FILE_WRITE","dependencies":["left","right"]}
                        ]}
                        """.formatted(serialized ? "[\"left\"]" : "[]"));
            }
            String task = task(input);
            inputs.put(task, input);
            var results = messages.stream().filter(message -> message.role().equals("tool")).toList();
            if (task.equals("MERGE")) {
                mergeConsumedBoth = outputs.size() == 2 && outputs.values().stream().allMatch(input::contains);
                if (!mergeConsumedBoth) throw new IOException("merge missing full dependency results");
                if (results.isEmpty()) return tool("write_file", Map.of("path", "merged.txt", "content",
                        outputs.get("LEFT") + "\n===\n" + outputs.get("RIGHT")));
                return text("MERGED");
            }
            if (results.isEmpty()) return tool("read_file", Map.of("path", task.toLowerCase() + ".txt"));
            var matcher = Pattern.compile("value=([^\\r\\n]+)").matcher(results.get(0).content());
            if (!matcher.find()) throw new IOException("branch missing real file tool output");
            String value = task + "_OUTPUT\n" + matcher.group(1).repeat(160) + "\nEND_" + task;
            outputs.put(task, value);
            return text(value);
        }
    }
    private static final class QueueClient extends BaseClient {
        @Override public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            String input = input(messages);
            if (input.startsWith("请为以下任务制定执行计划")) {
                var tasks = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < 5; i++) tasks.add(Map.of("id", "q" + i, "description", "q" + i,
                        "type", "FILE_READ", "dependencies", List.of()));
                return text(JSON.writeValueAsString(Map.of("tasks", tasks)));
            }
            String task = task(input);
            if (messages.stream().noneMatch(message -> message.role().equals("tool"))) {
                return tool("read_file", Map.of("path", task + ".txt"));
            }
            return text("done " + task);
        }
    }
}
