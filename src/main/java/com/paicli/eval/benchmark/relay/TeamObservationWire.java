package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.TeamExecutionObserver;
import com.paicli.agent.AgentRole;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static com.paicli.agent.TeamExecutionObserver.*;

/**
 * Closed codec for native Team metadata, carried by relay v12 TEAM_EVENT frames.
 * Decoding alone does not establish trusted host attribution or make an E2 episode scoreable;
 * actual provider requests still require an independently checked, acknowledged activation scope.
 * Text bodies are fingerprints; correlation identifiers remain provider-controlled metadata.
 */
public final class TeamObservationWire {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_EVENT_BYTES = 262_144;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private static final Map<String, Class<? extends TeamExecutionObserver.Event>> TYPES = Map.ofEntries(
            Map.entry("RunStarted", TeamExecutionObserver.RunStarted.class),
            Map.entry("PlanPrepared", TeamExecutionObserver.PlanPrepared.class),
            Map.entry("StepEntered", TeamExecutionObserver.StepEntered.class),
            Map.entry("ActivationEntered", TeamExecutionObserver.ActivationEntered.class),
            Map.entry("ActivationInputPrepared", TeamExecutionObserver.ActivationInputPrepared.class),
            Map.entry("CompactionScopeUnsupported", TeamExecutionObserver.CompactionScopeUnsupported.class),
            Map.entry("HistoryCompacted", TeamExecutionObserver.HistoryCompacted.class),
            Map.entry("BudgetFinalization", TeamExecutionObserver.BudgetFinalization.class),
            Map.entry("ToolBatchReturned", TeamExecutionObserver.ToolBatchReturned.class),
            Map.entry("ActivationExited", TeamExecutionObserver.ActivationExited.class),
            Map.entry("ReviewEvaluated", TeamExecutionObserver.ReviewEvaluated.class),
            Map.entry("StepExited", TeamExecutionObserver.StepExited.class),
            Map.entry("RunExited", TeamExecutionObserver.RunExited.class));

    private TeamObservationWire() {}

    /** Reject malformed native observations at the same boundary as received observations. */
    public static JsonNode encode(TeamExecutionObserver.Event event) throws IOException {
        if (event == null) throw new IOException("missing Team observation");
        byte[] bytes = JSON.writeValueAsBytes(event);
        if (bytes.length > MAX_EVENT_BYTES) throw new IOException("Team observation exceeds byte limit");
        JsonNode value = JSON.readTree(bytes);
        decode(event.getClass().getSimpleName(), value);
        return value;
    }

    /** Raw decoding also rejects duplicate keys and trailing JSON, unlike an already-built tree. */
    public static TeamExecutionObserver.Event decode(String type, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_EVENT_BYTES)
            throw new IOException("invalid Team observation byte length");
        return decode(type, JSON.readTree(bytes));
    }

    public static TeamExecutionObserver.Event decode(String type, JsonNode value) throws IOException {
        Class<? extends TeamExecutionObserver.Event> target = type == null ? null : TYPES.get(type);
        if (target == null || value == null || !value.isObject()
                || JSON.writeValueAsBytes(value).length > MAX_EVENT_BYTES)
            throw new IOException("invalid Team observation type or size");
        try {
            TeamExecutionObserver.Event event = JSON.treeToValue(value, target);
            // Compare reparsed trees so integral node widths do not depend on caller construction.
            if (!JSON.readTree(JSON.writeValueAsBytes(event)).equals(JSON.readTree(JSON.writeValueAsBytes(value))))
                throw new IOException("Team observation fields must be exact and typed");
            validate(event);
            return event;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("invalid Team observation structure", error);
        }
    }

    private static void validate(TeamExecutionObserver.Event event) throws IOException {
        uuid(event.runId());
        if (event.elapsedNanos() < 0) throw new IOException("negative Team observation time");
        if (event instanceof ActivationEvent scoped) {
            ActivationIdentity a = scoped.activation();
            required(a); required(a.role()); uuid(a.activationId()); uuid(a.actorInstanceId());
            range(a.attempt(), 1, 3); range(a.historyGeneration(), 0, Long.MAX_VALUE);
            if (a.role() == AgentRole.PLANNER) {
                if (a.stepId() != null || a.attempt() != 1)
                    throw new IOException("planner activation cannot belong to a step retry");
            } else step(a.stepId());
        }
        if (event instanceof RunStarted e) {
            required(e.input()); required(e.submittedInput());
            range(e.workerCount(), 1, 128); range(e.maxRetriesPerStep(), 0, 2);
        } else if (event instanceof PlanPrepared e) {
            uuid(e.plannerActivationId()); required(e.plannerResult()); bounded(e.steps());
            Set<String> ids = new java.util.HashSet<>();
            for (StepNode node : e.steps()) {
                required(node); step(node.stepId()); required(node.description()); required(node.type());
                if (!ids.add(node.stepId())) throw new IOException("duplicate normalized Team step");
                bounded(node.dependencies());
                for (TextFingerprint dependency : node.dependencies()) required(dependency);
            }
        } else if (event instanceof StepEntered e) {
            step(e.stepId()); range(e.batchOrdinal(), 1, Integer.MAX_VALUE);
            range(e.threadId(), 1, Long.MAX_VALUE); required(e.context()); bounded(e.dependencies());
            for (DependencyInput dependency : e.dependencies()) {
                required(dependency); step(dependency.stepId()); required(dependency.productStatus());
                required(dependency.fullResult()); required(dependency.injectedResult());
            }
        } else if (event instanceof ActivationEntered e) {
            range(e.threadId(), 1, Long.MAX_VALUE); range(e.historyMessagesBefore(), 0, Integer.MAX_VALUE);
        } else if (event instanceof ActivationInputPrepared e) {
            range(e.userMessageIndex(), 0, Integer.MAX_VALUE); required(e.userText());
            range(e.imagePartCount(), 0, 128);
        } else if (event instanceof CompactionScopeUnsupported e) {
            range(e.iteration(), 1, Integer.MAX_VALUE);
            if (!e.sessionMemoryEnabled() && !e.fullSummaryThresholdReached())
                throw new IOException("missing Team compaction scope limitation reason");
        } else if (event instanceof HistoryCompacted e) {
            range(e.iteration(), 1, Integer.MAX_VALUE); range(e.beforeMessages(), 1, Integer.MAX_VALUE);
            range(e.afterMessages(), 1, Integer.MAX_VALUE);
            if (e.userMessageIndexValid()) throw new IOException("compaction invalidates the prior input index");
        } else if (event instanceof BudgetFinalization e) {
            range(e.iteration(), 1, Integer.MAX_VALUE);
            if (e.exitReason() == null || !Set.of("TOKEN_BUDGET_EXCEEDED", "STAGNATION_DETECTED", "HARD_ITERATION_LIMIT").contains(e.exitReason()))
                throw new IOException("invalid Team budget finalization reason");
        } else if (event instanceof ToolBatchReturned e) {
            range(e.iteration(), 1, Integer.MAX_VALUE); bounded(e.results());
            // An empty returned batch can describe a partial/failed product tool path, not success.
            for (int i = 0; i < e.results().size(); i++) {
                ToolResult result = e.results().get(i); required(result);
                if (result.ordinal() != i) throw new IOException("Team tool ordinals must retain original order");
                metadata(result.callId()); metadata(result.name());
                required(result.arguments()); required(result.result()); range(result.imagePartCount(), 0, 128);
            }
        } else if (event instanceof ActivationExited e) {
            required(e.kind()); required(e.result()); exceptionType(e.exceptionType());
        } else if (event instanceof ReviewEvaluated e) {
            step(e.stepId()); range(e.attempt(), 1, 3); uuid(e.workerActivationId()); uuid(e.reviewerActivationId());
            required(e.decision()); required(e.reviewResult()); required(e.issues());
        } else if (event instanceof StepExited e) {
            step(e.stepId()); required(e.productStatus()); required(e.reason()); required(e.result());
            if (e.acceptedWorkerActivationId() != null) uuid(e.acceptedWorkerActivationId());
            if (e.lastReviewerActivationId() != null) uuid(e.lastReviewerActivationId());
            exceptionType(e.exceptionType());
        } else if (event instanceof RunExited e) {
            required(e.reason()); required(e.result()); exceptionType(e.exceptionType());
        }
    }

    private static void range(long value, long min, long max) throws IOException {
        if (value < min || value > max) throw new IOException("Team observation dimension out of bounds");
    }
    private static void required(Object value) throws IOException {
        if (value == null) throw new IOException("missing Team observation field");
    }
    private static void bounded(List<?> values) throws IOException {
        required(values);
        if (values.size() > 128) throw new IOException("Team observation collection limit exceeded");
    }
    private static void step(String value) throws IOException {
        if (value == null || !value.matches("step_[1-9][0-9]{0,8}"))
            throw new IOException("invalid normalized Team step identifier");
    }
    private static void metadata(String value) throws IOException {
        if (value != null && value.length() > 1024)
            throw new IOException("Team tool correlation metadata exceeds limit");
    }
    private static void exceptionType(String value) throws IOException {
        if (value != null && (!value.matches("[A-Za-z_$][A-Za-z0-9_.$]{0,255}")))
            throw new IOException("invalid Team exception type");
    }

    private static void uuid(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("invalid Team observation identity");
    }
}
