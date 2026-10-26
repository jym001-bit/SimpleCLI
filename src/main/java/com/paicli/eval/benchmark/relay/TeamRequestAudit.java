package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentRole;
import com.paicli.eval.benchmark.ScopedRequestFingerprints.Binding;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.paicli.agent.TeamExecutionObserver.*;

/**
 * Host-owned TEAM attribution: every actual provider request is bound to an independently
 * observed, still-active activation, and the run must close as a complete lifecycle.
 * Request scope is the relay-acknowledged {@code team:<activationId>}; the same activation
 * may issue several requests, while retries are fresh activations. Tool call ids are matched
 * per activation and batch, never as global keys. Envelope templates are frozen per contract:
 * they are drift evidence, not a semantic guarantee about model behaviour. A failed audit is
 * missing evidence, not a Candidate zero.
 */
public final class TeamRequestAudit {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PLANNER_ENVELOPE = "请为以下任务制定执行计划：\n";
    static final String CONTEXT_ENVELOPE = "总任务上下文：";
    static final String TASK_MARKER = "\n\n当前任务：";
    static final String REVIEW_ENVELOPE = "原始任务：";
    static final String REVIEW_RESULT_SEPARATOR = "\n\n执行结果：\n";
    static final String RETRY_FEEDBACK_MARKER = "之前的执行结果被审查拒绝，原因：";
    private static final String SCOPE_PREFIX = "team:";
    private static final int MAX_EVENTS = 16_384;
    private static final int MAX_ACTIVATIONS = 256;
    private static final int MAX_REQUESTS = 4_096;
    private static final int MAX_WRITE_ATTRIBUTIONS = 4_096;

    private final String prompt;
    private final long started = System.nanoTime();
    private final Map<String, Activation> activations = new LinkedHashMap<>();
    private final Map<String, Step> steps = new LinkedHashMap<>();
    private final Map<String, ReviewDecision> reviewDecisions = new HashMap<>();
    private final List<ObservedEvent> events = new ArrayList<>();
    private final List<ProviderAttempt> providerAttempts = new ArrayList<>();
    private final List<WriteAttribution> writeAttributions = new ArrayList<>();
    private final Map<String, java.util.LinkedHashSet<String>> pathActivations = new LinkedHashMap<>();
    private final List<WriteConflict> writeConflicts = new ArrayList<>();
    private String runId;
    private boolean runStarted;
    private boolean runExited;
    private boolean planPrepared;
    private int maxBatchOrdinal;
    private boolean failed;
    private RequestState active;

    public TeamRequestAudit(String prompt) { this.prompt = Objects.requireNonNull(prompt); }

    public record ObservedEvent(int ordinal, long hostElapsedNanos, String eventType, JsonNode event) {
        public ObservedEvent { event = event.deepCopy(); }
        @Override public JsonNode event() { return event.deepCopy(); }
    }

    /** Private host evidence with actual request/response bodies; not a model score. */
    public record ProviderAttempt(int ordinal, ActivationIdentity activation, String scope, Binding binding,
                                  boolean providerDispatched, boolean delivered,
                                  LlmClient.ChatResponse returnedResponse, LlmClient.ChatResponse response,
                                  String failureType, long requestStartedNanos, long terminalNanos,
                                  int eventsSeenAtRequest, List<LlmClient.Message> messages,
                                  List<LlmClient.Tool> tools) {
        public ProviderAttempt {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    public record Snapshot(int schemaVersion, String mode, boolean failed,
                           List<ObservedEvent> events, List<ProviderAttempt> providerAttempts,
                           List<WriteAttribution> writeAttributions, List<WriteConflict> writeConflicts) {
        public Snapshot {
            events = List.copyOf(events);
            providerAttempts = List.copyOf(providerAttempts);
            writeAttributions = List.copyOf(writeAttributions);
            writeConflicts = List.copyOf(writeConflicts);
        }
    }

    /**
     * One write_file tool call attributed to the activation that issued it: the path comes
     * from the actual provider arguments, successful comes from the post-policy batch.
     * Attribution is host observation, not a verdict; the frozen E2 contract decides scoring.
     */
    public record WriteAttribution(int ordinal, String scope, AgentRole role, String stepId, int attempt,
                                   String toolCallId, String path, boolean successful) { }

    /** The same path successfully written by more than one distinct activation. */
    public record WriteConflict(int ordinal, String path, List<String> activationIds) {
        public WriteConflict { activationIds = List.copyOf(activationIds); }
    }

    /** Host evidence failure, never a Candidate execution failure. */
    public static final class Failure extends IOException {
        public Failure(String message) { super(message); }
        public Failure(String message, Throwable cause) { super(message, cause); }
    }

    public synchronized boolean failed() { return failed; }
    public synchronized void markFailed() { failed = true; }

    /** Binding of the request in flight; {@code null} at rest or once the audit has failed. */
    public synchronized Binding currentBinding() {
        return failed || active == null ? null : active.binding;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(1, "TEAM", failed, List.copyOf(events), List.copyOf(providerAttempts),
                List.copyOf(writeAttributions), List.copyOf(writeConflicts));
    }

    /** Explicit private host evidence; not enabled for ordinary product Team execution. */
    public synchronized void writeSnapshot(Path path) throws IOException {
        try (var channel = java.nio.file.Files.newByteChannel(path,
                java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(JSON.writeValueAsBytes(snapshot()));
            while (bytes.hasRemaining()) channel.write(bytes);
        }
    }

    /** Validates the closed event lifecycle; rejections fail the audit instead of being skipped. */
    public synchronized void accept(Event event) throws IOException {
        try {
            require(event != null, "missing Team event");
            require(!failed, "Team audit is unavailable");
            require(events.size() < MAX_EVENTS, "Team event limit exceeded");
            if (event instanceof RunStarted start) {
                require(!runStarted, "duplicate Team run start");
                runStarted = true;
                runId = start.runId();
            } else {
                require(runStarted, "Team event before run start");
                require(runId != null && runId.equals(event.runId()), "Team event from another run");
                if (event instanceof PlanPrepared prepared) acceptPlanPrepared(prepared);
                else if (event instanceof StepEntered entered) acceptStepEntered(entered);
                else if (event instanceof ActivationEvent activationEvent) acceptActivation(activationEvent);
                else if (event instanceof ReviewEvaluated review) acceptReview(review);
                else if (event instanceof StepExited exit) acceptStepExited(exit);
                else if (event instanceof RunExited) {
                    require(!runExited, "duplicate Team run exit");
                    runExited = true;
                } else throw new Failure("unknown Team event type");
            }
            events.add(new ObservedEvent(events.size() + 1, elapsedNanos(),
                    event.getClass().getSimpleName(), TeamObservationWire.encode(event)));
        } catch (IOException | RuntimeException error) {
            failed = true;
            if (error instanceof Failure failure) throw failure;
            if (error instanceof IOException io) throw io;
            throw new Failure("invalid Team lifecycle evidence", error);
        }
    }

    /**
     * Binds one actual provider request to the acknowledged activation scope before dispatch.
     * The last user message must be exactly the observed activation input at the observed index,
     * except for the one no-tools closing request after a BudgetFinalization observation.
     */
    public synchronized void begin(String scope, List<LlmClient.Message> messages, List<LlmClient.Tool> tools)
            throws IOException {
        try {
            require(!failed && active == null, "Team request scope is unavailable/already active");
            require(providerAttempts.size() < MAX_REQUESTS, "Team request limit exceeded");
            require(scope != null && scope.startsWith(SCOPE_PREFIX), "missing team request scope");
            Activation activation = activations.get(scope.substring(SCOPE_PREFIX.length()));
            require(activation != null && activation.exited == null,
                    "request does not belong to an active Team activation");
            require(activation.input != null, "request before observed activation input");
            require(messages != null && !messages.isEmpty() && "system".equals(messages.get(0).role()),
                    "Team request has no system message");
            require(activation.pending.isEmpty(), "prior provider tool calls have no observed tool batch");

            String systemSha256 = BenchmarkRelayProtocol.textSha256(messages.get(0).content());
            if (activation.systemSha256 == null) activation.systemSha256 = systemSha256;
            else require(activation.systemSha256.equals(systemSha256),
                    "Team system prompt drifted inside one activation");

            List<LlmClient.Tool> safeTools = tools == null ? List.of() : List.copyOf(tools);
            if (activation.identity.role() != AgentRole.WORKER)
                require(safeTools.isEmpty(), "planner/reviewer requests must not expose tools");

            int index = lastUserIndex(messages);
            String inputText = messages.get(index).content();
            boolean finalizationRequest = activation.budgetFinalized && !activation.finalizationRequestSeen;
            if (finalizationRequest) {
                require(safeTools.isEmpty(), "budget finalization must not expose tools");
            } else {
                require(index == activation.input.userMessageIndex(),
                        "actual user input index differs from observed activation input");
                require(TextFingerprint.of(inputText).equals(activation.input.userText()),
                        "actual user input differs from observed activation input");
                requireInputEnvelope(activation, inputText);
                if (activation.identity.role() == AgentRole.WORKER && activation.identity.attempt() > 1)
                    requireHistoryCarriesPreviousResult(activation, messages);
            }

            active = new RequestState(providerAttempts.size() + 1, activation,
                    new Binding(scope, BenchmarkRelayProtocol.textSha256(prompt),
                            BenchmarkRelayProtocol.textSha256(inputText)),
                    List.copyOf(messages), safeTools, events.size(), elapsedNanos());
        } catch (IOException | RuntimeException error) {
            failed = true;
            active = null;
            if (error instanceof Failure failure) throw failure;
            if (error instanceof IOException io) throw io;
            throw new Failure("invalid Team request attribution", error);
        }
    }

    /** True only for the first request of an activation that observed BudgetFinalization. */
    public synchronized boolean isBudgetFinalizationRequest() {
        return active != null && active.activation.budgetFinalized && !active.activation.finalizationRequestSeen;
    }

    public synchronized void dispatched() {
        if (active == null) { failed = true; return; }
        active.dispatched = true;
    }

    public synchronized void returnedResponse(LlmClient.ChatResponse response) {
        if (active == null) { failed = true; return; }
        active.returned = response;
        if (active.pendingRecorded) return;
        active.pendingRecorded = true;
        if (response == null || response.toolCalls() == null) return;
        for (LlmClient.ToolCall call : response.toolCalls()) {
            if (call == null || call.id() == null || call.function() == null
                    || call.function().name() == null) {
                failed = true;
                return;
            }
            active.activation.pending.add(new ExpectedToolCall(call.id(),
                    call.function().name(), call.function().arguments(),
                    WriteToolPaths.extract(call.function().name(), call.function().arguments())));
        }
    }

    /** Best-effort path extraction from actual provider write arguments; evidence, not a gate. */
    private static final class WriteToolPaths {
        private static final ObjectMapper STRICT = new ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

        private static String extract(String toolName, String argumentsJson) {
            if (!"write_file".equals(toolName) || argumentsJson == null) return null;
            try {
                JsonNode arguments = STRICT.readTree(argumentsJson);
                if (arguments != null && arguments.isObject() && arguments.path("path").isTextual()) {
                    String path = arguments.path("path").asText();
                    return path.isEmpty() ? null : path;
                }
            } catch (IOException | RuntimeException ignored) {
                // Unparseable provider arguments stay visible through the recorded response.
            }
            return null;
        }
    }

    /** Successful pipe delivery evidence, not proof of semantic consumption by the model. */
    public synchronized void response(LlmClient.ChatResponse response) {
        if (active == null) { failed = true; return; }
        active.delivered = response;
    }

    public synchronized void providerFailure(String failureType) {
        if (active == null) { failed = true; return; }
        if (active.failureType == null && failureType != null) active.failureType = failureType;
    }

    /** Closes the request and materializes its immutable attempt record; must never throw. */
    public synchronized void end() {
        if (active == null) return;
        if (active.activation.budgetFinalized) active.activation.finalizationRequestSeen = true;
        providerAttempts.add(new ProviderAttempt(active.ordinal, active.activation.identity,
                active.activation.scope, active.binding, active.dispatched, active.delivered != null,
                active.returned, active.delivered, active.failureType, active.requestStartedNanos,
                elapsedNanos(), active.eventsSeenAtRequest, active.messages, active.tools));
        active = null;
    }

    /** Requires the full closed lifecycle; any rejection also fails the audit. */
    public synchronized void assertComplete() throws IOException {
        if (failed) throw new Failure("host Team audit already failed");
        try {
            require(active == null, "Team request still active");
            require(runStarted, "Team run start was not observed");
            require(runExited, "Team run exit was not observed");
            for (Activation activation : activations.values()) {
                require(activation.exited != null, "Team activation never exited");
                require(activation.pending.isEmpty(), "Team activation has unresolved tool calls");
            }
            for (Step step : steps.values())
                if (step.entered != null) require(step.exited != null, "Team step never exited");
        } catch (Failure failure) {
            failed = true;
            throw failure;
        }
    }

    private void acceptPlanPrepared(PlanPrepared prepared) throws IOException {
        require(!planPrepared, "duplicate Team plan");
        Activation planner = activations.get(prepared.plannerActivationId());
        require(planner != null && planner.identity.role() == AgentRole.PLANNER,
                "plan does not reference the planner activation");
        require(planner.exited != null, "plan prepared before planner exit");
        require(planner.input != null, "plan prepared without observed planner input");
        require(prepared.plannerResult().present() && prepared.plannerResult().equals(planner.exited.result()),
                "plan result differs from planner exit result");
        for (StepNode node : prepared.steps())
            require(steps.putIfAbsent(node.stepId(), new Step(node.stepId(), node.description())) == null,
                    "duplicate Team plan step");
        planPrepared = true;
    }

    private void acceptStepEntered(StepEntered entered) throws IOException {
        require(planPrepared, "step entered without a prepared plan");
        Step step = steps.get(entered.stepId());
        require(step != null, "unregistered Team step");
        require(step.entered == null && step.exited == null, "step entered twice");
        require(entered.batchOrdinal() >= maxBatchOrdinal, "Team step batch order regressed");
        maxBatchOrdinal = entered.batchOrdinal();
        for (DependencyInput dependency : entered.dependencies()) {
            Step dep = steps.get(dependency.stepId());
            require(dep != null, "dependency is not a plan step");
            require(dep.exited != null && dep.exited.productStatus() == ProductStatus.COMPLETED,
                    "Team dependency has not completed");
        }
        step.entered = entered;
    }

    private void acceptActivation(ActivationEvent event) throws IOException {
        ActivationIdentity identity = event.activation();
        if (event instanceof ActivationEntered) {
            require(activations.get(identity.activationId()) == null, "duplicate Team activation");
            require(activations.size() < MAX_ACTIVATIONS, "Team activation limit exceeded");
            activations.put(identity.activationId(), new Activation(identity));
            requireRetryChain(identity);
            return;
        }
        Activation activation = activations.get(identity.activationId());
        require(activation != null, "unregistered Team activation");
        require(identity.equals(activation.identity), "Team activation identity drifted");
        require(activation.exited == null, "Team event after activation exit");
        if (event instanceof ActivationInputPrepared input) {
            require(activation.input == null, "activation input observed twice");
            require(input.imagePartCount() == 0, "Team image input is not supported");
            activation.input = input;
        } else if (event instanceof CompactionScopeUnsupported || event instanceof HistoryCompacted) {
            throw new Failure("Team compaction is not supported by this evidence channel");
        } else if (event instanceof BudgetFinalization) {
            require(!activation.budgetFinalized, "duplicate Team budget finalization");
            activation.budgetFinalized = true;
        } else if (event instanceof ToolBatchReturned batch) {
            require(activation.input != null, "tool batch before observed activation input");
            require(!activation.budgetFinalized, "tool batch after budget finalization");
            acceptToolBatch(activation, batch);
        } else if (event instanceof ActivationExited exit) {
            require(activation.pending.isEmpty(), "Team activation exited with unresolved tool calls");
            activation.exited = exit;
        }
    }

    private void acceptToolBatch(Activation activation, ToolBatchReturned batch) throws IOException {
        require(batch.iteration() > activation.lastToolIteration, "reused Team tool batch iteration");
        activation.lastToolIteration = batch.iteration();
        List<ToolResult> results = batch.results();
        require(results.size() == activation.pending.size(),
                "Team tool batch does not match the provider tool calls");
        for (int i = 0; i < results.size(); i++) {
            ToolResult result = results.get(i);
            ExpectedToolCall expected = activation.pending.get(i);
            require(expected.callId().equals(result.callId()) && expected.name().equals(result.name())
                            && TextFingerprint.of(expected.argumentsJson()).equals(result.arguments()),
                    "Team tool result differs from the provider tool call");
            if (expected.path() != null) recordWriteAttribution(activation, expected, result);
        }
        activation.pending.clear();
    }

    private void recordWriteAttribution(Activation activation, ExpectedToolCall expected, ToolResult result)
            throws IOException {
        require(writeAttributions.size() < MAX_WRITE_ATTRIBUTIONS, "Team write attribution limit exceeded");
        writeAttributions.add(new WriteAttribution(writeAttributions.size() + 1, activation.scope,
                activation.identity.role(), activation.identity.stepId(), activation.identity.attempt(),
                result.callId(), expected.path(), result.successful()));
        if (!result.successful()) return;
        java.util.LinkedHashSet<String> writers =
                pathActivations.computeIfAbsent(expected.path(), key -> new java.util.LinkedHashSet<>());
        writers.add(activation.identity.activationId());
        if (writers.size() > 1) {
            WriteConflict conflict = new WriteConflict(writeConflicts.size() + 1,
                    expected.path(), List.copyOf(writers));
            // One rolling entry per conflicted path keeps the snapshot bounded and idempotent.
            if (writeConflicts.stream().noneMatch(existing -> existing.path().equals(expected.path()))) {
                writeConflicts.add(conflict);
            } else {
                writeConflicts.removeIf(existing -> existing.path().equals(expected.path()));
                writeConflicts.add(conflict);
            }
        }
    }

    private void acceptReview(ReviewEvaluated review) throws IOException {
        Activation worker = activations.get(review.workerActivationId());
        Activation reviewer = activations.get(review.reviewerActivationId());
        require(worker != null && worker.identity.role() == AgentRole.WORKER
                && review.stepId().equals(worker.identity.stepId()) && worker.exited != null,
                "review does not reference an exited worker activation");
        require(reviewer != null && reviewer.identity.role() == AgentRole.REVIEWER
                && review.stepId().equals(reviewer.identity.stepId()) && reviewer.exited != null,
                "review does not reference an exited reviewer activation");
        require(review.attempt() == worker.identity.attempt(), "review attempt differs from worker attempt");
        require(reviewDecisions.put(review.stepId() + ":" + review.attempt(), review.decision()) == null,
                "duplicate review for one step attempt");
    }

    private void acceptStepExited(StepExited exit) throws IOException {
        Step step = steps.get(exit.stepId());
        require(step != null, "exit for an unregistered Team step");
        if (step.entered == null) {
            require(exit.productStatus() == ProductStatus.PENDING
                    && exit.reason() == StepExitReason.BLOCKED_DEPENDENCY,
                    "exit for a Team step that never entered");
        } else {
            require(step.exited == null, "duplicate Team step exit");
            require(exit.acceptedWorkerActivationId() == null
                            || matchesExitedActivation(exit.acceptedWorkerActivationId(), exit.stepId(), AgentRole.WORKER),
                    "accepted worker activation does not match the step");
            require(exit.lastReviewerActivationId() == null
                            || matchesExitedActivation(exit.lastReviewerActivationId(), exit.stepId(), AgentRole.REVIEWER),
                    "last reviewer activation does not match the step");
        }
        step.exited = exit;
    }

    private boolean matchesExitedActivation(String activationId, String stepId, AgentRole role) {
        Activation activation = activations.get(activationId);
        return activation != null && activation.identity.role() == role
                && stepId.equals(activation.identity.stepId()) && activation.exited != null;
    }

    /** A worker/reviewer retry must follow a rejected review of the previous attempt. */
    private void requireRetryChain(ActivationIdentity identity) throws IOException {
        if (identity.role() != AgentRole.WORKER && identity.role() != AgentRole.REVIEWER) return;
        if (identity.attempt() <= 1) return;
        require(steps.containsKey(identity.stepId()), "retry activation has no plan step");
        require(reviewDecisions.get(identity.stepId() + ":" + (identity.attempt() - 1)) == ReviewDecision.REJECTED,
                "Team retry without a rejected prior review");
    }

    private void requireInputEnvelope(Activation activation, String userText) throws IOException {
        if (activation.identity.role() == AgentRole.PLANNER) {
            require(userText.startsWith(PLANNER_ENVELOPE)
                    && userText.substring(PLANNER_ENVELOPE.length()).equals(prompt),
                    "planner input differs from the session goal");
        } else if (activation.identity.role() == AgentRole.WORKER) {
            require(userText.startsWith(CONTEXT_ENVELOPE) && userText.contains(TASK_MARKER),
                    "worker input does not match the task envelope");
            if (activation.identity.attempt() > 1)
                require(userText.contains(RETRY_FEEDBACK_MARKER), "worker retry input lacks review feedback");
        } else if (activation.identity.role() == AgentRole.REVIEWER) {
            require(userText.startsWith(REVIEW_ENVELOPE), "reviewer input has no review envelope");
            int split = userText.indexOf(REVIEW_RESULT_SEPARATOR);
            require(split >= REVIEW_ENVELOPE.length(), "reviewer input has no execution result section");
            Step step = steps.get(activation.identity.stepId());
            require(step != null && TextFingerprint.of(
                            userText.substring(REVIEW_ENVELOPE.length(), split)).equals(step.description),
                    "reviewed task differs from the plan step description");
        } else {
            throw new Failure("unknown Team activation role");
        }
    }

    /** A worker retry request must carry the previous attempt's actual exit result verbatim. */
    private void requireHistoryCarriesPreviousResult(Activation activation,
                                                     List<LlmClient.Message> messages) throws IOException {
        Activation previous = null;
        for (Activation candidate : activations.values()) {
            if (candidate.identity.role() == AgentRole.WORKER
                    && candidate.identity.attempt() == activation.identity.attempt() - 1
                    && activation.identity.stepId().equals(candidate.identity.stepId())
                    && candidate.exited != null) {
                require(previous == null, "ambiguous previous worker attempt");
                previous = candidate;
            }
        }
        require(previous != null && previous.exited.result().present(),
                "worker retry has no exited previous attempt result");
        TextFingerprint expected = previous.exited.result();
        for (LlmClient.Message message : messages)
            if ("assistant".equals(message.role())
                    && TextFingerprint.of(message.content()).equals(expected)) return;
        throw new Failure("worker retry history does not carry the previous attempt result");
    }

    private static int lastUserIndex(List<LlmClient.Message> messages) throws IOException {
        for (int i = messages.size() - 1; i >= 0; i--)
            if ("user".equals(messages.get(i).role())) return i;
        throw new Failure("Team request has no user message");
    }

    private long elapsedNanos() { return Math.max(0, System.nanoTime() - started); }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new Failure(message);
    }

    /** One provider activation: a planner pass, one worker attempt, or one reviewer pass. */
    private static final class Activation {
        final ActivationIdentity identity;
        final String scope;
        final List<ExpectedToolCall> pending = new ArrayList<>();
        ActivationInputPrepared input;
        String systemSha256;
        boolean budgetFinalized;
        boolean finalizationRequestSeen;
        int lastToolIteration;
        ActivationExited exited;

        Activation(ActivationIdentity identity) {
            this.identity = identity;
            this.scope = SCOPE_PREFIX + identity.activationId();
        }
    }

    private record ExpectedToolCall(String callId, String name, String argumentsJson, String path) { }

    private static final class Step {
        final String stepId;
        final TextFingerprint description;
        StepEntered entered;
        StepExited exited;

        Step(String stepId, TextFingerprint description) {
            this.stepId = stepId;
            this.description = description;
        }
    }

    /** Mutable in-flight state; materialized into a {@link ProviderAttempt} by {@link #end()}. */
    private static final class RequestState {
        final int ordinal;
        final Activation activation;
        final Binding binding;
        final List<LlmClient.Message> messages;
        final List<LlmClient.Tool> tools;
        final int eventsSeenAtRequest;
        final long requestStartedNanos;
        boolean dispatched;
        boolean pendingRecorded;
        LlmClient.ChatResponse returned;
        LlmClient.ChatResponse delivered;
        String failureType;

        RequestState(int ordinal, Activation activation, Binding binding,
                     List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
                     int eventsSeenAtRequest, long requestStartedNanos) {
            this.ordinal = ordinal;
            this.activation = activation;
            this.binding = binding;
            this.messages = messages;
            this.tools = tools;
            this.eventsSeenAtRequest = eventsSeenAtRequest;
            this.requestStartedNanos = requestStartedNanos;
        }
    }
}
