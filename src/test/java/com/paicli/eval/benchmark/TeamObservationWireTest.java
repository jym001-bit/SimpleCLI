package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.AgentRole;
import com.paicli.eval.benchmark.relay.TeamObservationWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.paicli.agent.TeamExecutionObserver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Codec controls are synthetic observations, not actual Team, relay or model execution. */
class TeamObservationWireTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = UUID.randomUUID().toString();
    private static final String ACTOR = UUID.randomUUID().toString();
    private static final String WORKER = UUID.randomUUID().toString();
    private static final String REVIEWER = UUID.randomUUID().toString();
    private static final String PLANNER = UUID.randomUUID().toString();
    private static final TextFingerprint TEXT = TextFingerprint.of("synthetic-private-body 你好");
    private static final TextFingerprint ABSENT = TextFingerprint.of(null);
    private static final ActivationIdentity ACTIVE = new ActivationIdentity(
            RUN, "step_1", 2, AgentRole.WORKER, WORKER, ACTOR, 3);

    static Stream<Event> events() {
        return Stream.of(
                new RunStarted(RUN, 0, TEXT, TEXT, true, 2, 2),
                new PlanPrepared(RUN, 1, PLANNER, TEXT, List.of(new StepNode(
                        "step_1", TEXT, TEXT, List.of(TextFingerprint.of("unknown dependency is a product fact"))))),
                new StepEntered(RUN, 2, "step_1", 1, 1, TEXT, List.of(new DependencyInput(
                        "step_2", ProductStatus.COMPLETED, TEXT, TEXT, false))),
                new ActivationEntered(ACTIVE, 3, 1, 9),
                new ActivationInputPrepared(ACTIVE, 4, 9, TEXT, 0),
                new CompactionScopeUnsupported(ACTIVE, 5, 1, false, true),
                new HistoryCompacted(ACTIVE, 5, 1, 50, 8, false),
                new BudgetFinalization(ACTIVE, 6, 32, "HARD_ITERATION_LIMIT"),
                batch(),
                new ActivationExited(ACTIVE, 8, ExitKind.PARTIAL, TEXT, null),
                new ReviewEvaluated(RUN, 9, "step_1", 2, WORKER, REVIEWER, ReviewDecision.ERROR, TEXT, ABSENT),
                new StepExited(RUN, 10, "step_1", ProductStatus.COMPLETED,
                        StepExitReason.REVIEW_ERROR_RETAINED, WORKER, REVIEWER, TEXT, null),
                new RunExited(RUN, 11, RunExitReason.THREW, ABSENT, "java.lang.IllegalStateException"));
    }

    private static ToolBatchReturned batch() {
        return new ToolBatchReturned(ACTIVE, 7, 1, List.of(
                new ToolResult(0, "reused-call-id", "read_file", TEXT, TEXT, true, false, 0),
                new ToolResult(1, "reused-call-id", "write_file", TEXT, TEXT, false, false, 0)));
    }

    @ParameterizedTest @MethodSource("events")
    void allClosedEventTypesRoundTripWithoutRawBodies(Event event) throws Exception {
        var node = TeamObservationWire.encode(event);
        assertEquals(event, TeamObservationWire.decode(event.getClass().getSimpleName(), node));
        assertEquals(event, TeamObservationWire.decode(event.getClass().getSimpleName(), JSON.writeValueAsBytes(node)));
        assertFalse(node.toString().contains("synthetic-private-body"));
        assertFalse(node.toString().contains("unknown dependency is a product fact"));
        assertEquals(1, TeamObservationWire.SCHEMA_VERSION);
    }

    @Test void rejectsUnknownMissingDuplicateTrailingAndPolymorphicPayloads() throws Exception {
        Event event = events().findFirst().orElseThrow();
        ObjectNode node = tree(event);
        assertThrows(IOException.class, () -> TeamObservationWire.decode("java.lang.Runtime", node));
        assertThrows(IOException.class, () -> TeamObservationWire.decode((String) null, node));
        reject(event, n -> n.put("@class", "java.lang.Runtime"));
        reject(event, n -> n.remove("explicitTaskEnvelope"));
        reject(event, n -> n.putNull("input"));
        reject(event, n -> n.putNull("workerCount"));
        String original = node.toString();
        assertThrows(IOException.class, () -> TeamObservationWire.decode("RunStarted",
                original.replaceFirst("\\{", "{\"workerCount\":2,").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> TeamObservationWire.decode("RunStarted", (original + " false").getBytes(StandardCharsets.UTF_8)));
        for (String invalid : List.of("null", "[]", "false", "0", "\"text\""))
            assertThrows(IOException.class, () -> TeamObservationWire.decode("RunStarted", invalid.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void rejectsCoercedNumbersEnumsBooleansAndMalformedFingerprints() throws Exception {
        Event start = events().findFirst().orElseThrow();
        reject(start, n -> n.put("workerCount", 2.0));
        reject(start, n -> n.put("workerCount", "2"));
        reject(start, n -> n.put("elapsedNanos", 0.0));
        reject(start, n -> n.put("explicitTaskEnvelope", "true"));
        reject(start, n -> n.put("elapsedNanos", -1));
        reject(start, n -> n.put("runId", "not-a-run"));
        reject(start, n -> ((ObjectNode)n.get("input")).put("utf8Bytes", -1));
        reject(start, n -> ((ObjectNode)n.get("input")).put("sha256", "not-a-hash"));
        reject(start, n -> ((ObjectNode)n.get("input")).put("present", false));
        Event exit = new ActivationExited(ACTIVE, 1, ExitKind.NORMAL, TEXT, null);
        reject(exit, n -> n.put("kind", 0));
        reject(exit, n -> n.put("kind", "SUCCESS"));
        reject(exit, n -> n.putNull("kind"));
        reject(exit, n -> n.put("exceptionType", "raw exception message or secret"));
    }

    @Test void rejectsInvalidActivationIdentityAndDimensionsButKeepsReusedHistory() throws Exception {
        Event event = new ActivationInputPrepared(ACTIVE, 1, 9, TEXT, 0);
        assertEquals(event, TeamObservationWire.decode("ActivationInputPrepared", tree(event)));
        reject(event, n -> ((ObjectNode)n.get("activation")).put("attempt", 0));
        reject(event, n -> ((ObjectNode)n.get("activation")).put("attempt", 4));
        reject(event, n -> ((ObjectNode)n.get("activation")).put("historyGeneration", -1));
        reject(event, n -> ((ObjectNode)n.get("activation")).putNull("role"));
        reject(event, n -> ((ObjectNode)n.get("activation")).put("role", 1));
        reject(event, n -> ((ObjectNode)n.get("activation")).put("role", "PLANNER"));
        reject(event, n -> ((ObjectNode)n.get("activation")).putNull("stepId"));
        reject(event, n -> ((ObjectNode)n.get("activation")).put("actorInstanceId", "same"));
        reject(event, n -> n.put("userMessageIndex", -1));
        reject(event, n -> n.put("imagePartCount", 129));
        var planner = new ActivationIdentity(RUN, null, 1, AgentRole.PLANNER, PLANNER, ACTOR, 0);
        var entered = new ActivationEntered(planner, 0, 1, 1);
        assertEquals(entered, TeamObservationWire.decode("ActivationEntered", tree(entered)));
    }

    @Test void rejectsFakeOrdinalsAndCompactionIndexClaimsWithoutRejectingFailedProductOutcomes() throws Exception {
        var batch = batch();
        assertEquals(batch, TeamObservationWire.decode("ToolBatchReturned", tree(batch)));
        reject(batch, n -> ((ObjectNode)n.withArray("results").get(1)).put("ordinal", 0));
        reject(batch, n -> n.put("iteration", 0));
        reject(batch, n -> ((ObjectNode)n.withArray("results").get(0)).putNull("result"));
        reject(new HistoryCompacted(ACTIVE, 1, 1, 50, 8, false), n -> n.put("userMessageIndexValid", true));
        reject(new CompactionScopeUnsupported(ACTIVE, 1, 1, false, true), n -> n.put("fullSummaryThresholdReached", false));
        reject(new BudgetFinalization(ACTIVE, 1, 1, "TOKEN_BUDGET_EXCEEDED"), n -> n.put("exitReason", "WITHIN_BUDGET"));
        var emptyPlan = new PlanPrepared(RUN, 1, PLANNER, TEXT, List.of());
        assertEquals(emptyPlan, TeamObservationWire.decode("PlanPrepared", tree(emptyPlan)));
        var retained = new StepExited(RUN, 1, "step_1", ProductStatus.COMPLETED,
                StepExitReason.RETRIES_EXHAUSTED_RETAINED, WORKER, REVIEWER, TEXT, null);
        assertEquals(retained, TeamObservationWire.decode("StepExited", tree(retained)));
        assertNotEquals(TextFingerprint.of(null), TextFingerprint.of(""));
    }

    @Test void byteAndCollectionBoundsAreEnforcedBeforeWireUse() throws Exception {
        var many = new ArrayList<ToolResult>();
        for (int i = 0; i < 129; i++) many.add(new ToolResult(i, "id", "name", TEXT, TEXT, false, false, 0));
        assertThrows(IOException.class, () -> TeamObservationWire.encode(new ToolBatchReturned(ACTIVE, 1, 1, many)));
        many.clear();
        String metadata = "🙂".repeat(300); // Below the per-identifier character cap, but costly in UTF-8.
        for (int i = 0; i < 128; i++) many.add(new ToolResult(i, metadata, metadata, TEXT, TEXT, false, false, 0));
        var tooLarge = new ToolBatchReturned(ACTIVE, 1, 1, many);
        String serialized = JSON.writeValueAsString(tooLarge);
        assertTrue(serialized.length() < TeamObservationWire.MAX_EVENT_BYTES);
        assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length > TeamObservationWire.MAX_EVENT_BYTES);
        assertThrows(IOException.class, () -> TeamObservationWire.encode(tooLarge));
        assertThrows(IOException.class, () -> TeamObservationWire.decode("ToolBatchReturned", serialized.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> TeamObservationWire.encode(null));
    }

    private static ObjectNode tree(Event event) { return JSON.valueToTree(event); }
    private static void reject(Event event, Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode node = tree(event); mutation.accept(node);
        assertThrows(IOException.class, () -> TeamObservationWire.decode(event.getClass().getSimpleName(), node));
    }
}
