package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.ScopedRequestFingerprints;
import com.paicli.llm.LlmClient;
import com.paicli.plan.PlanExecutionObserver;
import com.paicli.plan.Task;

import java.io.IOException;
import java.util.*;

import static com.paicli.plan.PlanExecutionObserver.*;

/** Host-owned attribution from actual provider requests/responses plus a closed Plan lifecycle. */
public final class PlanRequestAudit {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String prompt;
    private final long started = System.nanoTime();
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final List<ObservedEvent> events = new ArrayList<>();
    private String planningResponse;
    private String planningInput;
    private boolean planningResponseClaimed;
    private boolean failed;
    private ScopedRequestFingerprints.Binding active;
    private String activeInput;
    private List<LlmClient.Message> activeMessages = List.of();
    private List<LlmClient.Tool> activeTools = List.of();
    private int activeEventsSeen;
    private long activeStarted;
    private final List<ProviderTurn> providerTurns = new ArrayList<>();
    public record ProviderTurn(int ordinal, int eventsSeenAtRequest, long requestStartedNanos,
                               long responseCompletedNanos, ScopedRequestFingerprints.Binding binding,
                               List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
                               LlmClient.ChatResponse response) {
        public ProviderTurn { messages = List.copyOf(messages); tools = List.copyOf(tools); }
    }
    public record Snapshot(int schemaVersion, String mode, boolean failed,
                           List<ObservedEvent> events, List<ProviderTurn> providerTurns) {
        public Snapshot { events = List.copyOf(events); providerTurns = List.copyOf(providerTurns); }
    }

    public PlanRequestAudit(String prompt) { this.prompt = Objects.requireNonNull(prompt); }
    public record ObservedEvent(int ordinal, long hostElapsedNanos, String eventType,
                                com.fasterxml.jackson.databind.JsonNode event) {
        public ObservedEvent { event = event.deepCopy(); }
        @Override public com.fasterxml.jackson.databind.JsonNode event() { return event.deepCopy(); }
    }
    public synchronized List<ObservedEvent> events() { return List.copyOf(events); }
    public synchronized boolean failed() { return failed; }
    public synchronized Snapshot snapshot() { return new Snapshot(2, "PLAN", failed, events, providerTurns); }
    public synchronized void markFailed() { failed = true; }
    public synchronized ScopedRequestFingerprints.Binding currentBinding() { return failed ? null : active; }

    public synchronized void accept(PlanExecutionObserver.Event event) throws IOException {
        try {
            require(!failed && events.size() < 16_384, "Plan audit unavailable/oversized");
            if (event instanceof PlanStarted plan) {
                require(!runs.containsKey(plan.executionId()) && runs.size() < 32, "reused Plan execution identity");
                require(runs.values().stream().allMatch(r -> r.active.isEmpty()), "replan overlaps active tasks");
                List<TaskNode> expected;
                String goal;
                String origin;
                if (planningResponse == null) {
                    require(runs.isEmpty(), "missing planning response");
                    goal = prompt;
                    expected = List.of(new TaskNode("task_1", simpleType(prompt), TextFingerprint.of(prompt.trim()), List.of()));
                    origin = BenchmarkRelayProtocol.textSha256(prompt);
                } else {
                    require(!planningResponseClaimed, "planning response was already claimed");
                    goal = planningInput;
                    expected = normalize(planningResponse);
                    origin = BenchmarkRelayProtocol.textSha256(planningResponse);
                    planningResponseClaimed = true;
                }
                require(TextFingerprint.of(goal).equals(plan.goal()) && expected.equals(plan.tasks()),
                        "normalized Plan differs from actual provider planning response");
                require(new HashSet<>(plan.executionOrder()).size() == expected.size()
                        && new HashSet<>(plan.executionOrder()).equals(expected.stream().map(TaskNode::taskId).collect(java.util.stream.Collectors.toSet())),
                        "invalid Plan execution order");
                for (TaskNode task : expected) for (String dep : task.dependencies())
                    require(plan.executionOrder().indexOf(dep) < plan.executionOrder().indexOf(task.taskId()), "non-topological Plan order");
                runs.put(plan.executionId(), new Run(plan, origin));
            } else {
                Run run = runs.get(event.executionId());
                require(run != null, "unregistered Plan execution");
                String taskId = taskId(event);
                require(run.nodes.containsKey(taskId), "unregistered Plan task");
                if (event instanceof TaskEntered entered) {
                    require(!run.entered.contains(taskId) && entered.threadId() > 0, "task entered twice/invalid thread");
                    for (String dep : run.nodes.get(taskId).dependencies())
                        require(run.exited.containsKey(dep) && run.exited.get(dep).kind() == ExitKind.RETURNED, "dependency has not returned");
                    run.entered.add(taskId); run.active.add(taskId);
                } else {
                    require(run.active.contains(taskId), "event outside active task");
                    if (event instanceof TaskInputPrepared input) {
                        require(!run.inputs.containsKey(taskId), "task input reported twice");
                        require(input.dependencies().stream().map(DependencyInput::taskId).toList().equals(run.nodes.get(taskId).dependencies()),
                                "input dependency identities differ from graph");
                        for (var dep : input.dependencies()) require(dep.status() == Task.TaskStatus.COMPLETED
                                && dep.result().equals(run.exited.get(dep.taskId()).result()), "dependency input digest changed");
                        run.inputs.put(taskId, input);
                    } else if (event instanceof ToolBatchReturned batch) {
                        require(run.inputs.containsKey(taskId), "tool batch before input");
                        int previous = run.lastToolIteration.getOrDefault(taskId, 0);
                        require(batch.iteration() > previous, "reused tool batch iteration");
                        for (int i = 0; i < batch.results().size(); i++) require(batch.results().get(i).ordinal() == i,
                                "invalid task tool ordinal");
                        run.lastToolIteration.put(taskId, batch.iteration());
                    } else if (event instanceof TaskExited exit) {
                        require(exit.kind() != null, "missing task exit kind");
                        run.active.remove(taskId); run.exited.put(taskId, exit);
                    }
                }
            }
            events.add(new ObservedEvent(events.size() + 1, System.nanoTime() - started,
                    event.getClass().getSimpleName(), PlanObservationWire.encode(event)));
        } catch (IOException | RuntimeException e) {
            failed = true;
            if (e instanceof IOException io) throw io;
            throw new IOException("invalid Plan lifecycle evidence", e);
        }
    }

    public synchronized void begin(String scope, List<LlmClient.Message> messages, List<LlmClient.Tool> tools) throws IOException {
        try {
            require(!failed && active == null, "Plan scope is unavailable/already active");
            activeMessages = RelayWireConversions.fromWireMessages(RelayWireConversions.toWireMessages(messages));
            activeTools = tools == null ? List.of() : tools.stream().map(t -> new LlmClient.Tool(
                    t.name(), t.description(), t.parameters() == null ? null : t.parameters().deepCopy())).toList();
            activeEventsSeen = events.size();
            activeStarted = System.nanoTime() - started;
            String input = messages.stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
            if ("planner".equals(scope)) {
                require(runs.values().stream().allMatch(r -> r.active.isEmpty()), "planner request during active task");
                require(tools == null || tools.isEmpty(), "planner must not expose tools");
                require(input != null && input.startsWith("请为以下任务制定执行计划：\n"), "missing planning envelope");
                String goal = input.substring("请为以下任务制定执行计划：\n".length());
                if (runs.isEmpty()) require(prompt.equals(goal), "initial planning goal differs from session");
                active = new ScopedRequestFingerprints.Binding(scope, BenchmarkRelayProtocol.textSha256(prompt),
                        BenchmarkRelayProtocol.textSha256(input));
                activeInput = goal;
            } else {
                int split = scope == null ? -1 : scope.indexOf(':');
                require(split > 0, "missing task scope");
                Run run = runs.get(scope.substring(0, split));
                String taskId = scope.substring(split + 1);
                require(run != null && run.active.contains(taskId), "request does not belong to an active task");
                TaskInputPrepared prepared = run.inputs.get(taskId);
                require(prepared != null && prepared.userText().equals(TextFingerprint.of(input)), "actual provider input differs from observed task input");
                active = new ScopedRequestFingerprints.Binding(scope, run.origin, BenchmarkRelayProtocol.textSha256(input));
                activeInput = input;
            }
        } catch (IOException | RuntimeException e) {
            failed = true; active = null;
            if (e instanceof IOException io) throw io;
            throw new IOException("invalid Plan request attribution", e);
        }
    }

    public synchronized void response(LlmClient.ChatResponse response) {
        if (active != null) providerTurns.add(new ProviderTurn(providerTurns.size() + 1, activeEventsSeen,
                activeStarted, System.nanoTime() - started, active, activeMessages, activeTools,
                RelayWireConversions.fromWireResponse(RelayWireConversions.toWireResponse(response))));
        if (active != null && "planner".equals(active.scope())) {
            planningResponse = response.content(); planningInput = activeInput; planningResponseClaimed = false;
        }
    }
    public synchronized void end() { active = null; activeInput = null; activeMessages = List.of(); activeTools = List.of(); }

    /** Explicit private host evidence; not enabled for ordinary product Plan execution. */
    public synchronized void writeSnapshot(java.nio.file.Path path) throws IOException {
        try (var channel = java.nio.file.Files.newByteChannel(path,
                java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(JSON.writeValueAsBytes(snapshot()));
            while (bytes.hasRemaining()) channel.write(bytes);
        }
    }

    /** Independent mapping of original ids; never use Candidate-supplied normalized task ids as authority. */
    private static List<TaskNode> normalize(String response) throws IOException {
        var root = JSON.readTree(response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim());
        require(root != null && root.isObject() && root.path("tasks").isArray() && !root.path("tasks").isEmpty()
                && root.path("tasks").size() <= 128, "invalid planning response graph");
        var ids = new LinkedHashMap<String, String>();
        for (var task : root.path("tasks")) {
            require(task.isObject() && task.path("id").isTextual() && !task.path("id").asText().isBlank(), "invalid original task id");
            require(ids.putIfAbsent(task.path("id").asText(), "task_" + (ids.size() + 1)) == null, "duplicate original task id");
        }
        var result = new ArrayList<TaskNode>();
        for (var task : root.path("tasks")) {
            require(task.path("description").isMissingNode() || task.path("description").isTextual(),
                    "invalid planning task description");
            var deps = new LinkedHashSet<String>();
            require(task.path("dependencies").isMissingNode() || task.path("dependencies").isArray(), "invalid dependencies");
            for (var dep : task.path("dependencies")) {
                require(dep.isTextual() && ids.containsKey(dep.asText()), "unknown dependency");
                deps.add(ids.get(dep.asText()));
            }
            Task.TaskType type;
            try { type = Task.TaskType.valueOf(task.path("type").asText().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { type = Task.TaskType.ANALYSIS; }
            result.add(new TaskNode(ids.get(task.path("id").asText()), type,
                    TextFingerprint.of(task.path("description").asText()), List.copyOf(deps)));
        }
        return List.copyOf(result);
    }
    private static String taskId(Event event) {
        if (event instanceof TaskEntered e) return e.taskId();
        if (event instanceof TaskInputPrepared e) return e.taskId();
        if (event instanceof ToolBatchReturned e) return e.taskId();
        if (event instanceof TaskExited e) return e.taskId();
        throw new IllegalArgumentException("not a task event");
    }
    private static Task.TaskType simpleType(String goal) {
        String text = goal.trim();
        if (text.contains("读取") || text.contains("打开") || text.contains("查看") && text.contains("文件")) return Task.TaskType.FILE_READ;
        if (text.contains("写入") || text.contains("修改") || text.contains("创建文件")) return Task.TaskType.FILE_WRITE;
        if (text.contains("分析") || text.contains("总结") || text.contains("解释")) return Task.TaskType.ANALYSIS;
        if (text.contains("验证") || text.contains("检查")) return Task.TaskType.VERIFICATION;
        return Task.TaskType.COMMAND;
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    private static final class Run {
        final String origin;
        final Map<String, TaskNode> nodes = new LinkedHashMap<>();
        final Set<String> entered = new HashSet<>(), active = new HashSet<>();
        final Map<String, TaskInputPrepared> inputs = new HashMap<>();
        final Map<String, TaskExited> exited = new HashMap<>();
        final Map<String, Integer> lastToolIteration = new HashMap<>();
        Run(PlanStarted plan, String origin) { this.origin = origin; plan.tasks().forEach(t -> nodes.put(t.taskId(), t)); }
    }
}
