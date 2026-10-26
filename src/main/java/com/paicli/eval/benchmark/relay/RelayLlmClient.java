package com.paicli.eval.benchmark.relay;

import com.paicli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Worker-side LLM client backed by the coordinator relay. It never retries calls. */
public final class RelayLlmClient implements LlmClient {
    private final BenchmarkFramedChannel channel;
    private final BenchmarkRelayProtocol.Validator validator;
    private final BenchmarkRelayProtocol.SessionStart session;
    private final AtomicLong callCounter = new AtomicLong();
    private int turn = 1;
    private int chatCalls;
    private long usedTokens;
    private final ThreadLocal<String> planScope = new ThreadLocal<>();
    private final ThreadLocal<com.paicli.agent.TeamExecutionObserver.ActivationIdentity> teamActivation = new ThreadLocal<>();
    private boolean planObservationFailed;
    private boolean teamObservationFailed;
    private LongSupplier teamObservationFailureSupplier;
    private boolean f3ObservationFailed;
    private int f3RawResults;
    private long f3RawChars;

    public synchronized void markF3ToolObservationFailed() { f3ObservationFailed = true; }

    /** One native Team instance owns this session. The counter also detects failures before the callback. */
    public synchronized void setTeamObservationFailureSupplier(LongSupplier supplier) {
        if (session.mode() != BenchmarkRelayProtocol.AgentMode.TEAM || teamObservationFailureSupplier != null)
            throw new IllegalStateException("Team observation counter cannot be bound");
        teamObservationFailureSupplier = Objects.requireNonNull(supplier, "supplier");
    }

    /** Worker finally-path cross-check; a missing terminal event must not hide a native observer failure. */
    public synchronized void verifyTeamObservationFailures(long failures) {
        if (session.mode() != BenchmarkRelayProtocol.AgentMode.TEAM || failures != 0)
            teamObservationFailed = true;
        refreshTeamObservationFailures();
    }

    private void refreshTeamObservationFailures() {
        if (session.mode() != BenchmarkRelayProtocol.AgentMode.TEAM || teamObservationFailed) return;
        try {
            if (teamObservationFailureSupplier == null || teamObservationFailureSupplier.getAsLong() != 0)
                teamObservationFailed = true;
        } catch (RuntimeException | AssertionError error) {
            teamObservationFailed = true;
        }
    }

    /** Closed, acknowledged Team metadata. Never exposed as a model tool or a planner fallback. */
    public synchronized void observeTeam(com.paicli.agent.TeamExecutionObserver.Event event) {
        try {
            refreshTeamObservationFailures();
            if (session.mode() != BenchmarkRelayProtocol.AgentMode.TEAM || teamObservationFailed)
                throw new IOException("Team observation is unavailable");
            ensureDeadline(session);
            JsonNode encoded = TeamObservationWire.encode(event);
            var current = teamActivation.get();
            if (event instanceof com.paicli.agent.TeamExecutionObserver.ActivationEntered) {
                if (current != null) throw new IOException("nested Team activation");
            } else if (event instanceof com.paicli.agent.TeamExecutionObserver.ActivationEvent activationEvent) {
                if (!activationEvent.activation().equals(current)) throw new IOException("Team activation does not own this thread");
            } else if (current != null) throw new IOException("unscoped Team event during activation");
            var request = new BenchmarkRelayProtocol.TeamEvent(
                    requestHeader(BenchmarkRelayProtocol.FrameType.TEAM_EVENT, "team-event-" + callCounter.incrementAndGet()),
                    event.getClass().getSimpleName(), encoded);
            acceptSent(validator, request); channel.write(request);
            var response = requireFrame(channel.read(), "Team event acknowledgement");
            acceptReceived(validator, response);
            if (!(response instanceof BenchmarkRelayProtocol.TeamEventAck)) throw new IOException("expected Team event acknowledgement");
            // The current thread acquires/releases its scope only after the exact host ACK.
            if (event instanceof com.paicli.agent.TeamExecutionObserver.ActivationEntered entered)
                teamActivation.set(entered.activation());
            if (event instanceof com.paicli.agent.TeamExecutionObserver.ActivationExited) teamActivation.remove();
            if (event instanceof com.paicli.agent.TeamExecutionObserver.CompactionScopeUnsupported
                    || event instanceof com.paicli.agent.TeamExecutionObserver.HistoryCompacted
                    || event instanceof com.paicli.agent.TeamExecutionObserver.ActivationInputPrepared input && input.imagePartCount() != 0
                    || event instanceof com.paicli.agent.TeamExecutionObserver.ToolBatchReturned batch
                    && batch.results().stream().anyMatch(result -> result.imagePartCount() != 0))
                throw new IOException("Team compaction or image scope is not supported");
        } catch (IOException | RuntimeException | AssertionError error) {
            teamObservationFailed = true;
            throw new IllegalStateException("Team observation relay failed", error);
        }
    }

    /** Native post-policy result, before the terminal preview is truncated. Not a model-callable tool. */
    public synchronized void observeF3ToolResult(com.paicli.tool.ToolRegistry.ToolExecutionResult result) {
        try {
            if (session.toolProfile() != BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY || f3ObservationFailed)
                throw new IOException("F3 raw observation unavailable");
            ensureDeadline(session);
            String full = result.result() == null ? "" : result.result();
            if (full.length() > F3ToolResultAudit.MAX_RAW_RESULT_CHARS - f3RawChars)
                throw new IOException("F3 raw observation total limit");
            var raw = new BenchmarkRelayProtocol.RawToolResult(++f3RawResults, result.id(), result.name(),
                    result.argumentsJson(), full, result.elapsedMillis(), result.timedOut(), result.successful());
            var frame = new BenchmarkRelayProtocol.F3ToolResult(
                    requestHeader(BenchmarkRelayProtocol.FrameType.F3_TOOL_RESULT, "f3-result-" + callCounter.incrementAndGet()), raw);
            acceptSent(validator, frame); channel.write(frame);
            var ack = requireFrame(channel.read(), "F3 raw result acknowledgement");
            acceptReceived(validator, ack);
            if (!(ack instanceof BenchmarkRelayProtocol.F3ToolResultAck)) throw new IOException("expected F3 raw acknowledgement");
            f3RawChars += full.length();
        } catch (IOException | RuntimeException error) {
            f3ObservationFailed = true;
            throw new IllegalStateException("F3 raw tool observation failed", error);
        }
    }

    /** Called only by the trusted worker's native Plan observer; not exposed as a model tool. */
    public synchronized void observePlan(com.paicli.plan.PlanExecutionObserver.Event event) {
        try {
            ensureDeadline(session);
            var request = new BenchmarkRelayProtocol.PlanEvent(
                    requestHeader(BenchmarkRelayProtocol.FrameType.PLAN_EVENT, "plan-event-" + callCounter.incrementAndGet()),
                    event.getClass().getSimpleName(), PlanObservationWire.encode(event));
            acceptSent(validator, request); channel.write(request);
            var response = requireFrame(channel.read(), "Plan event acknowledgement");
            acceptReceived(validator, response);
            if (!(response instanceof BenchmarkRelayProtocol.PlanEventAck)) throw new IOException("expected Plan event acknowledgement");
            if (event instanceof com.paicli.plan.PlanExecutionObserver.TaskEntered entered)
                planScope.set(event.executionId() + ":" + entered.taskId());
            if (event instanceof com.paicli.plan.PlanExecutionObserver.TaskExited) planScope.remove();
        } catch (IOException | RuntimeException e) {
            planObservationFailed = true;
            throw new IllegalStateException("Plan observation relay failed", e);
        }
    }

    private RelayLlmClient(BenchmarkFramedChannel channel,
                           BenchmarkRelayProtocol.Validator validator,
                           BenchmarkRelayProtocol.SessionStart session) {
        this.channel = channel;
        this.validator = validator;
        this.session = session;
    }

    /** Reads and validates SESSION_START, then answers with WORKER_READY. */
    public static RelayLlmClient accept(BenchmarkFramedChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        BenchmarkRelayProtocol.Validator validator =
                new BenchmarkRelayProtocol.Validator(BenchmarkRelayProtocol.EndpointRole.WORKER);
        BenchmarkRelayProtocol.Frame frame = requireFrame(channel.read(), "session start");
        acceptReceived(validator, frame);
        if (!(frame instanceof BenchmarkRelayProtocol.SessionStart start)) {
            throw new IOException("relay protocol error: expected session start");
        }
        ensureDeadline(start);
        BenchmarkRelayProtocol.WorkerReady ready = new BenchmarkRelayProtocol.WorkerReady(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_READY, "", 0),
                start.capabilities());
        acceptSent(validator, ready);
        channel.write(ready);
        return new RelayLlmClient(channel, validator, start);
    }

    public BenchmarkRelayProtocol.SessionStart session() {
        return session;
    }

    /** Episode totals include both user turns and compaction/finalization calls. */
    public synchronized long remainingTokens() { return Math.max(0, session.agentLimits().tokenBudget() - usedTokens); }
    public synchronized int remainingCalls() { return Math.max(0, session.agentLimits().hardMaxIterations() - chatCalls); }

    public synchronized String continueTurn(String answer, List<BenchmarkRelayProtocol.WireToolExecution> evidence) throws IOException {
        ensureDeadline(session);
        var request = new BenchmarkRelayProtocol.TurnComplete(
                requestHeader(BenchmarkRelayProtocol.FrameType.TURN_COMPLETE, "turn-" + callCounter.incrementAndGet()), turn, answer, evidence);
        acceptSent(validator, request);
        channel.write(request);
        var response = requireFrame(channel.read(), "turn continuation");
        acceptReceived(validator, response);
        if (!(response instanceof BenchmarkRelayProtocol.TurnContinue next)) throw new IOException("expected host continuation");
        turn = next.turn();
        return next.userMessage();
    }

    /** Called by the native HITL handler, not exposed as a model tool. All wire exchanges share this lock. */
    public synchronized BenchmarkRelayProtocol.ApprovalComplete requestApproval(String name, String arguments) throws IOException {
        ensureDeadline(session);
        var request = new BenchmarkRelayProtocol.ApprovalRequest(
                requestHeader(BenchmarkRelayProtocol.FrameType.APPROVAL_REQUEST, "approval-" + callCounter.incrementAndGet()),
                turn, name, arguments);
        acceptSent(validator, request);
        channel.write(request);
        var response = requireFrame(channel.read(), "approval decision");
        acceptReceived(validator, response);
        if (!(response instanceof BenchmarkRelayProtocol.ApprovalComplete complete)) throw new IOException("expected host approval decision");
        return complete;
    }

    private static BenchmarkRelayProtocol.Header requestHeader(BenchmarkRelayProtocol.FrameType type, String id) {
        return new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR, type, id, 0);
    }

    /** Shares the LLM wire lock so parallel product tools cannot interleave frame exchanges. */
    public synchronized JsonNode exchangeMcp(JsonNode message) throws IOException {
        return exchangeMcp("benchmark", message);
    }

    public synchronized JsonNode exchangeMcp(String server, JsonNode message) throws IOException {
        ensureDeadline(session);
        if ((session.toolProfile() != BenchmarkRelayProtocol.ToolProfile.MOCK_MCP
                && session.toolProfile() != BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY)
                || !session.mockServers().contains(server)) {
            throw new IOException("MCP relay is unavailable for this profile");
        }
        String callId = "mcp-" + callCounter.incrementAndGet();
        BenchmarkRelayProtocol.McpRequest request = new BenchmarkRelayProtocol.McpRequest(
                new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.MCP_REQUEST, callId, 0), server, message);
        acceptSent(validator, request);
        channel.write(request);
        BenchmarkRelayProtocol.Frame response = requireFrame(channel.read(), "MCP response");
        acceptReceived(validator, response);
        if (!(response instanceof BenchmarkRelayProtocol.McpComplete complete)) {
            throw new IOException("relay protocol error: expected MCP response");
        }
        return complete.message();
    }

    /** All native search/fetch/policy calls share the LLM/MCP wire lock; no parallel frame interleaving. */
    public synchronized BenchmarkRelayProtocol.WebComplete exchangeWeb(
            BenchmarkRelayProtocol.WebOperation operation, String input, int topK) throws IOException {
        ensureDeadline(session);
        if (session.toolProfile() != BenchmarkRelayProtocol.ToolProfile.MOCK_WEB)
            throw new IOException("Web relay is unavailable for this profile");
        var request = new BenchmarkRelayProtocol.WebRequest(
                requestHeader(BenchmarkRelayProtocol.FrameType.WEB_REQUEST, "web-" + callCounter.incrementAndGet()), operation, input, topK);
        acceptSent(validator, request); channel.write(request);
        var response = requireFrame(channel.read(), "Web response");
        acceptReceived(validator, response); ensureDeadline(session);
        if (!(response instanceof BenchmarkRelayProtocol.WebComplete complete)) throw new IOException("expected host Web response");
        return complete;
    }

    /** Sends the worker's one terminal success frame. No further chat call is valid afterwards. */
    public synchronized void complete(String answer) throws IOException {
        complete(answer, List.of());
    }

    /** Sends terminal success together with bounded trusted tool-execution evidence. */
    public synchronized void complete(
            String answer,
            List<BenchmarkRelayProtocol.WireToolExecution> toolExecutions) throws IOException {
        complete(answer, toolExecutions, List.of(), 0);
    }

    /** Command observations are opt-in process-local diagnostics, not independent host OS audit. */
    public synchronized void complete(String answer,
            List<BenchmarkRelayProtocol.WireToolExecution> toolExecutions,
            List<com.paicli.tool.CommandExecutionObserver.Event> commandObservations,
            long commandObservationFailures) throws IOException {
        refreshTeamObservationFailures();
        if (f3ObservationFailed) {
            fail("F3_TOOL_RESULT_EVIDENCE_INVALID", "F3 raw result evidence is incomplete");
            return;
        }
        if (planObservationFailed) {
            fail("REQUEST_FINGERPRINT_UNPROVEN", "Plan observation evidence is incomplete");
            return;
        }
        if (teamObservationFailed) {
            fail("REQUEST_FINGERPRINT_UNPROVEN", "Team observation evidence is incomplete");
            return;
        }
        BenchmarkRelayProtocol.WorkerComplete complete = new BenchmarkRelayProtocol.WorkerComplete(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                answer,
                toolExecutions, commandObservations, commandObservationFailures);
        acceptSent(validator, complete);
        channel.write(complete);
    }

    /** Sends a bounded, provider-neutral worker failure without exposing exception details. */
    public synchronized void fail(String errorType, String errorMessage) throws IOException {
        refreshTeamObservationFailures();
        if (f3ObservationFailed) {
            errorType = "F3_TOOL_RESULT_EVIDENCE_INVALID";
            errorMessage = "F3 raw result evidence is incomplete";
        } else if (teamObservationFailed) {
            errorType = "REQUEST_FINGERPRINT_UNPROVEN";
            errorMessage = "Team observation evidence is incomplete";
        }
        BenchmarkRelayProtocol.WorkerFailure failure = new BenchmarkRelayProtocol.WorkerFailure(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_FAILURE, "", 0),
                safeErrorType(errorType), errorMessage);
        acceptSent(validator, failure);
        channel.write(failure);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    /** Synchronization serializes concurrent callers into distinct logical relay calls. */
    @Override
    public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools,
                                          StreamListener listener) throws IOException {
        refreshTeamObservationFailures();
        if (teamObservationFailed) throw new IOException("Team observation was lost");
        ensureDeadline(session);
        if (planObservationFailed) throw new IOException("Plan observation was lost");
        if (f3ObservationFailed) throw new IOException("F3 raw result observation was lost");
        StreamListener sink = listener == null ? StreamListener.NO_OP : listener;
        String callId = "call-" + callCounter.incrementAndGet();
        final BenchmarkRelayProtocol.ChatRequest request;
        try {
            String scope = null;
            if (session.mode() == BenchmarkRelayProtocol.AgentMode.PLAN)
                scope = Objects.requireNonNullElse(planScope.get(), "planner");
            if (session.mode() == BenchmarkRelayProtocol.AgentMode.TEAM) {
                var activation = teamActivation.get();
                if (activation == null || messages == null || messages.stream().anyMatch(message -> message.imagePartCount() != 0)) {
                    teamObservationFailed = true;
                    throw new IOException("Team request has no acknowledged text-only activation");
                }
                scope = "team:" + activation.activationId();
            }
            request = new BenchmarkRelayProtocol.ChatRequest(
                    new BenchmarkRelayProtocol.Header(
                            BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                            BenchmarkRelayProtocol.FrameType.CHAT_REQUEST, callId, 0),
                    RelayWireConversions.toWireMessages(messages),
                    RelayWireConversions.toWireTools(tools), scope);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("relay request rejected", error);
        }
        acceptSent(validator, request);
        channel.write(request);

        chatCalls++;
        while (true) {
            ensureDeadline(session);
            BenchmarkRelayProtocol.Frame frame = requireFrame(channel.read(), "chat response");
            acceptReceived(validator, frame);
            if (frame instanceof BenchmarkRelayProtocol.StreamDelta delta) {
                if (delta.kind() == BenchmarkRelayProtocol.DeltaKind.REASONING) {
                    sink.onReasoningDelta(delta.delta());
                } else {
                    sink.onContentDelta(delta.delta());
                }
                continue;
            }
            if (frame instanceof BenchmarkRelayProtocol.ChatComplete complete) {
                try {
                    usedTokens = Math.addExact(usedTokens, (long) complete.response().inputTokens() + complete.response().outputTokens());
                    return RelayWireConversions.fromWireResponse(complete.response());
                } catch (IllegalArgumentException | NullPointerException error) {
                    throw new IOException("relay response rejected", error);
                }
            }
            if (frame instanceof BenchmarkRelayProtocol.ChatFailure failure) {
                throw new IOException("relay provider failure [" + safeErrorType(failure.errorType()) + "]");
            }
            if (frame instanceof BenchmarkRelayProtocol.SessionCancel) {
                throw new IOException("relay session cancelled");
            }
            throw new IOException("relay protocol error: unexpected chat frame");
        }
    }

    @Override
    public String getModelName() {
        return session.model();
    }

    @Override
    public String getProviderName() {
        return session.provider();
    }

    @Override
    public int maxContextWindow() {
        return Math.min(
                session.capabilities().maxContextWindow(),
                session.agentLimits().contextWindowCapTokens());
    }

    @Override
    public boolean supportsPromptCaching() {
        return session.capabilities().promptCaching();
    }

    @Override
    public boolean supportsTools() {
        return session.capabilities().tools();
    }

    @Override
    public boolean supportsImageInput() {
        return session.capabilities().imageInput();
    }

    @Override
    public String promptCacheMode() {
        return session.capabilities().promptCacheMode();
    }

    private static BenchmarkRelayProtocol.Frame requireFrame(BenchmarkRelayProtocol.Frame frame,
                                                               String expected) throws IOException {
        if (frame == null) {
            throw new IOException("relay closed before " + expected);
        }
        return frame;
    }

    private static void acceptSent(BenchmarkRelayProtocol.Validator validator,
                                   BenchmarkRelayProtocol.Frame frame) throws IOException {
        try {
            validator.acceptSent(frame);
        } catch (IllegalStateException error) {
            throw new IOException("relay protocol order violation", error);
        }
    }

    private static void acceptReceived(BenchmarkRelayProtocol.Validator validator,
                                       BenchmarkRelayProtocol.Frame frame) throws IOException {
        try {
            validator.acceptReceived(frame);
        } catch (IllegalStateException error) {
            throw new IOException("relay protocol order violation", error);
        }
    }

    private static void ensureDeadline(BenchmarkRelayProtocol.SessionStart session) throws IOException {
        if (System.currentTimeMillis() >= session.deadlineEpochMillis()) {
            throw new IOException("relay session deadline exceeded");
        }
    }

    private static String safeErrorType(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
            return "PROVIDER_ERROR";
        }
        return value;
    }
}
