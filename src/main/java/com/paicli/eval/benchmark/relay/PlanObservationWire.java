package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.paicli.plan.PlanExecutionObserver;

import java.io.IOException;
import java.util.Map;

/** Closed, bounded encoding of native Plan observations. No polymorphic class names from the wire. */
public final class PlanObservationWire {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private static final Map<String, Class<? extends PlanExecutionObserver.Event>> TYPES = Map.of(
            "PlanStarted", PlanExecutionObserver.PlanStarted.class,
            "TaskEntered", PlanExecutionObserver.TaskEntered.class,
            "TaskInputPrepared", PlanExecutionObserver.TaskInputPrepared.class,
            "ToolBatchReturned", PlanExecutionObserver.ToolBatchReturned.class,
            "TaskExited", PlanExecutionObserver.TaskExited.class);
    private PlanObservationWire() {}

    public static JsonNode encode(PlanExecutionObserver.Event event) {
        try { return JSON.readTree(JSON.writeValueAsBytes(event)); }
        catch (IOException e) { throw new IllegalStateException("cannot encode Plan event", e); }
    }

    public static PlanExecutionObserver.Event decode(String type, JsonNode value) throws IOException {
        Class<? extends PlanExecutionObserver.Event> target = TYPES.get(type);
        if (target == null || value == null || !value.isObject() || value.toString().length() > 262_144)
            throw new IOException("invalid Plan event type/size");
        PlanExecutionObserver.Event event = JSON.treeToValue(value, target);
        if (!JSON.readTree(JSON.writeValueAsBytes(event)).equals(JSON.readTree(value.toString())))
            throw new IOException("Plan event fields must be exact and typed");
        if (event.executionId() == null || !event.executionId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || event.elapsedNanos() < 0) throw new IOException("invalid Plan event identity/time");
        if (event instanceof PlanExecutionObserver.PlanStarted plan) {
            if (plan.tasks().isEmpty() || plan.tasks().size() > 128 || plan.executionOrder().size() > 128)
                throw new IOException("Plan graph exceeds event limit");
            for (var task : plan.tasks()) if (task.dependencies().size() > 128) throw new IOException("too many dependencies");
        }
        if (event instanceof PlanExecutionObserver.TaskInputPrepared input
                && (input.dependencies().size() > 128 || input.imagePartCount() < 0 || input.imagePartCount() > 128))
            throw new IOException("invalid Plan input dimensions");
        if (event instanceof PlanExecutionObserver.ToolBatchReturned batch
                && (batch.iteration() <= 0 || batch.results().isEmpty() || batch.results().size() > 128))
            throw new IOException("invalid Plan tool batch dimensions");
        return event;
    }
}
