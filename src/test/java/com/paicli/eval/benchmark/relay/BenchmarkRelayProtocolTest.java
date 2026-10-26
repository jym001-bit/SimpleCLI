package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRelayProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void commandObservationsRoundTripWithoutInventingMissingTerminalEvents() throws Exception {
        var event = commandStarted();
        var original = new BenchmarkRelayProtocol.WorkerComplete(workerComplete().header(), "done", List.of(), List.of(event), 2);
        var decoded = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class,
                BenchmarkRelayProtocol.decode(BenchmarkRelayProtocol.encode(original)));
        assertEquals(original, decoded);
        assertEquals(12, BenchmarkRelayProtocol.VERSION);
        assertEquals(1, decoded.commandObservations().size(), "no synthetic FINISHED event");
        assertEquals(2, decoded.commandObservationFailures());
        assertFalse(decoded.toString().contains("printf"));
        assertEquals(List.of(), workerComplete().commandObservations());
        assertEquals(0, workerComplete().commandObservationFailures());
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WorkerComplete(
                original.header(), "done", List.of(), java.util.Collections.nCopies(8193, event), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WorkerComplete(
                original.header(), "done", List.of(), List.of(), -1));
    }

    @Test
    void commandObservationsRequireLocalCommandSessionIncludingFailureOnlyEvidence() {
        for (var profile : BenchmarkRelayProtocol.ToolProfile.values()) {
            for (boolean failuresOnly : List.of(false, true)) {
                var validator = new BenchmarkRelayProtocol.Validator();
                validator.accept(session(BenchmarkRelayProtocol.AgentMode.REACT, profile,
                        "2026-08-31", "UTC", System.currentTimeMillis() + 60_000));
                validator.accept(ready());
                var complete = new BenchmarkRelayProtocol.WorkerComplete(workerComplete().header(), "done", List.of(),
                        failuresOnly ? List.of() : List.of(commandStarted()), failuresOnly ? 1 : 0);
                if (profile == BenchmarkRelayProtocol.ToolProfile.LOCAL_COMMAND) validator.accept(complete);
                else assertThrows(IllegalStateException.class, () -> validator.accept(complete));
            }
        }
    }

    @Test
    void commandObservationWireRejectsMissingUnknownAndCoercedScalars() throws Exception {
        var complete = new BenchmarkRelayProtocol.WorkerComplete(workerComplete().header(), "done", List.of(), List.of(commandStarted()), 0);
        var valid = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(BenchmarkRelayProtocol.encode(complete));
        List<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> corruptions = List.of(
                n -> n.remove("invocationId"), n -> n.put("invocationId", "1"), n -> n.putNull("invocationId"),
                n -> n.put("invocationId", 1.0), n -> n.put("processId", true), n -> n.put("timestampMillis", "1"),
                n -> n.put("exitCode", false), n -> n.put("phase", 0), n -> n.put("outcome", 0),
                n -> n.putNull("command"), n -> n.put("workingDirectory", 1), n -> n.put("resultChars", "0"),
                n -> n.putNull("resultSha256"), n -> n.put("unexpected", true),
                n -> n.putNull("arguments"), n -> n.putArray("arguments").add(5));
        for (var corrupt : corruptions) {
            var changed = valid.deepCopy();
            corrupt.accept((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("payload").path("commandObservations").get(0));
            assertThrows(IOException.class, () -> BenchmarkRelayProtocol.decode(JSON.writeValueAsBytes(changed)));
        }
        for (String field : List.of("commandObservations", "commandObservationFailures")) {
            var changed = valid.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("payload")).remove(field);
            assertThrows(IOException.class, () -> BenchmarkRelayProtocol.decode(JSON.writeValueAsBytes(changed)));
        }
        var changed = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("payload")).put("commandObservationFailures", "0");
        assertThrows(IOException.class, () -> BenchmarkRelayProtocol.decode(JSON.writeValueAsBytes(changed)));
    }

    private static com.paicli.tool.CommandExecutionObserver.Event commandStarted() {
        return new com.paicli.tool.CommandExecutionObserver.Event(1,
                com.paicli.tool.CommandExecutionObserver.Phase.STARTED, "printf ok", "/workspace",
                List.of("bash", "-c", "printf ok"), 17, 1_800_000_000_000L, null,
                com.paicli.tool.CommandExecutionObserver.Outcome.NONE, "", 0);
    }

    @Test
    void roundTripsIndependentWireTypesWithoutSecretsOrHostPaths() throws Exception {
        BenchmarkRelayProtocol.ChatRequest request = request("call-1");
        byte[] encoded = BenchmarkRelayProtocol.encode(request);
        BenchmarkRelayProtocol.ChatRequest decoded = assertInstanceOf(
                BenchmarkRelayProtocol.ChatRequest.class, BenchmarkRelayProtocol.decode(encoded));

        assertEquals("system", decoded.messages().get(0).role());
        assertEquals("line1\n中文", decoded.messages().get(0).content());
        assertEquals("read_file", decoded.tools().get(0).name());
        assertFalse(request.toString().contains("line1"));
        assertFalse(decoded.messages().get(0).toString().contains("中文"));
        assertFalse(decoded.tools().get(0).toString().contains("description=read"));
    }

    @Test
    void constructorsRejectWrongDirectionTypeCallIdSequenceAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.ChatRequest(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.CHAT_REQUEST, "call", 0),
                List.of(new BenchmarkRelayProtocol.WireMessage("user", "x", null, null, null)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.ChatRequest(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_READY, "call", 0),
                List.of(new BenchmarkRelayProtocol.WireMessage("user", "x", null, null, null)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.ChatRequest(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.CHAT_REQUEST, "", 0),
                List.of(new BenchmarkRelayProtocol.WireMessage("user", "x", null, null, null)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.StreamDelta(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.STREAM_DELTA, "call", 0),
                BenchmarkRelayProtocol.DeltaKind.CONTENT, "x"));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireMessage(
                "user", "x".repeat(BenchmarkRelayProtocol.MAX_TEXT_CHARS + 1), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.ChatRequest(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.CHAT_REQUEST, "call", 0),
                java.util.Collections.nCopies(BenchmarkRelayProtocol.MAX_MESSAGES + 1,
                        new BenchmarkRelayProtocol.WireMessage("user", "x", null, null, null)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireTool(
                "bad", "bad", JSON.createArrayNode()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireContentPart(
                "unknown", "x", null, null, null));
    }

    @Test
    void decoderRejectsEnvelopePayloadHeaderMismatchAndUnknownPayloadField() throws Exception {
        String valid = new String(BenchmarkRelayProtocol.encode(request("call-1")), StandardCharsets.UTF_8);
        String mismatch = valid.replaceFirst("\"callId\":\"call-1\"", "\"callId\":\"call-2\"");
        assertThrows(IOException.class, () -> BenchmarkRelayProtocol.decode(mismatch.getBytes(StandardCharsets.UTF_8)));

        String unknownPayload = valid.replaceFirst(
                "\"payload\":\\{", "\"payload\":{\"unexpectedPayloadField\":1,");
        assertThrows(IOException.class,
                () -> BenchmarkRelayProtocol.decode(unknownPayload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void lifecycleValidatorRejectsOutOfOrderAndWrongSequence() {
        BenchmarkRelayProtocol.Validator validator = new BenchmarkRelayProtocol.Validator();
        assertThrows(IllegalStateException.class, () -> validator.accept(request("call-1")));

        validator.accept(start());
        validator.accept(ready());
        validator.accept(request("call-1"));
        assertThrows(IllegalStateException.class, () -> validator.accept(delta("call-1", 2)));
        assertThrows(IllegalStateException.class, () -> validator.accept(delta("another-call", 1)));

        validator.accept(delta("call-1", 1));
        validator.accept(complete("call-1", 2));
        validator.accept(request("call-2"));
        validator.accept(failure("call-2", 1));
        validator.accept(workerComplete());
        assertThrows(IllegalStateException.class, () -> validator.accept(request("call-3")));
    }

    @Test
    void sessionMetadataIsStrictAndToStringRedactsPromptRuntimeAndPathLikeSecrets() {
        BenchmarkRelayProtocol.SessionStart valid = start();
        String rendered = valid.toString();
        assertFalse(rendered.contains("private prompt"));
        assertFalse(rendered.contains("sk-secret"));
        assertFalse(rendered.contains("2026-08-31"));
        assertFalse(rendered.contains("UTC"));
        assertFalse(rendered.contains("/Users/private"));
        assertTrue(rendered.contains("prompt=<redacted"));

        assertThrows(IllegalArgumentException.class, () -> session(
                BenchmarkRelayProtocol.AgentMode.REACT,
                BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                "2026/08/31", "UTC", System.currentTimeMillis() + 60_000));
        assertThrows(IllegalArgumentException.class, () -> session(
                BenchmarkRelayProtocol.AgentMode.REACT,
                BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                "2026-08-31", "Asia/Shanghai", System.currentTimeMillis() + 60_000));
        assertThrows(IllegalArgumentException.class, () -> session(
                BenchmarkRelayProtocol.AgentMode.REACT,
                BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                "2026-08-31", "UTC", System.currentTimeMillis() - 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(0, 10, 3, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10_000_001, 10, 3, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 0, 3, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 100_001, 3, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 1, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 11, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(
                        10, 100_000, 10_001, 100_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 3, 7_999, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 3, 10_000_001, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 3, 100_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 3, 100_000, 16_385));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRelayProtocol.AgentLimits(10, 10, 3, 8_000, 8_001));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.SessionStart(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "session-1", "deepseek", "deepseek-v4-flash",
                BenchmarkRelayProtocol.AgentMode.REACT, BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                "prompt", "2026-08-31", "UTC", System.currentTimeMillis() + 60_000,
                new BenchmarkRelayProtocol.AgentLimits(
                        10, 10, 3, 99_999, 16_384), capabilities(),
                new BenchmarkRelayProtocol.Limits(
                        BenchmarkFramedChannel.MAX_FRAME_BYTES,
                        BenchmarkFramedChannel.MAX_SESSION_BYTES,
                        BenchmarkRelayProtocol.MAX_MESSAGES,
                        BenchmarkRelayProtocol.MAX_TOOLS)));
    }

    @Test
    void sessionRoundTripsBothCapsAndRejectsLegacyAgentLimitsWithoutEither() throws Exception {
        BenchmarkRelayProtocol.SessionStart original = start();
        byte[] encoded = BenchmarkRelayProtocol.encode(original);
        BenchmarkRelayProtocol.SessionStart decoded = assertInstanceOf(
                BenchmarkRelayProtocol.SessionStart.class,
                BenchmarkRelayProtocol.decode(encoded));

        assertEquals(1_000_000, decoded.agentLimits().contextWindowCapTokens());
        assertEquals(16_384, decoded.agentLimits().maxOutputTokensPerCall());
        assertEquals(original, decoded);

        String legacyContext = new String(encoded, StandardCharsets.UTF_8)
                .replace(",\"contextWindowCapTokens\":1000000", "");
        assertThrows(IOException.class,
                () -> BenchmarkRelayProtocol.decode(legacyContext.getBytes(StandardCharsets.UTF_8)));
        String legacyOutput = new String(encoded, StandardCharsets.UTF_8)
                .replace(",\"maxOutputTokensPerCall\":16384", "");
        assertThrows(IOException.class,
                () -> BenchmarkRelayProtocol.decode(legacyOutput.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void roleAwareValidatorRejectsWrongEndpointDirection() {
        BenchmarkRelayProtocol.Validator coordinator = new BenchmarkRelayProtocol.Validator(
                BenchmarkRelayProtocol.EndpointRole.COORDINATOR);
        assertThrows(IllegalStateException.class, () -> coordinator.acceptReceived(start()));
        coordinator.acceptSent(start());
        assertThrows(IllegalStateException.class, () -> coordinator.acceptSent(ready()));
        coordinator.acceptReceived(ready());
    }

    @Test
    void workerCompleteRoundTripsOrderedBoundedToolExecutionEvidence() throws Exception {
        BenchmarkRelayProtocol.WireToolExecution first = toolExecution(
                1, "call-1", "read_file", "{\"path\":\"README.md\"}", "contents", true);
        BenchmarkRelayProtocol.WireToolExecution second = toolExecution(
                2, "call-2", "grep_code", "{\"pattern\":\"needle\"}", "not found", false);
        BenchmarkRelayProtocol.WorkerComplete original = new BenchmarkRelayProtocol.WorkerComplete(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                "done", List.of(first, second));

        BenchmarkRelayProtocol.WorkerComplete decoded = assertInstanceOf(
                BenchmarkRelayProtocol.WorkerComplete.class,
                BenchmarkRelayProtocol.decode(BenchmarkRelayProtocol.encode(original)));

        assertEquals("done", decoded.answer());
        assertEquals(List.of(1, 2),
                decoded.toolExecutions().stream().map(event -> event.ordinal()).toList());
        assertEquals(List.of("read_file", "grep_code"),
                decoded.toolExecutions().stream().map(event -> event.toolName()).toList());
        assertEquals("{\"path\":\"README.md\"}", decoded.toolExecutions().get(0).argumentsJson());
        assertEquals("contents", decoded.toolExecutions().get(0).resultPreview());
        assertTrue(decoded.toolExecutions().get(0).successful());
        assertFalse(decoded.toolExecutions().get(1).successful());
        assertFalse(decoded.toString().contains("contents"));
        assertFalse(decoded.toolExecutions().get(0).toString().contains("README.md"));
    }

    @Test
    void toolExecutionEvidenceRejectsOversizedUnsafeAndInconsistentFrames() {
        BenchmarkRelayProtocol.WireToolExecution valid = toolExecution(
                1, "call-1", "read_file", "{}", "ok", true);

        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WorkerComplete(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                "done", java.util.Collections.nCopies(
                        BenchmarkRelayProtocol.MAX_TOOL_EXECUTIONS + 1, valid)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WorkerComplete(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                "done", List.of(toolExecution(
                        2, "call-2", "read_file", "{}", "ok", true))));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WorkerComplete(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                "done", List.of(
                        valid,
                        toolExecution(3, "call-3", "grep_code", "{}", "ok", true))));
        assertThrows(IllegalArgumentException.class, () -> toolExecution(
                0, "call-1", "read_file", "{}", "ok", true));
        assertThrows(IllegalArgumentException.class, () -> toolExecution(
                BenchmarkRelayProtocol.MAX_TOOL_EXECUTIONS + 1,
                "call-1", "read_file", "{}", "ok", true));
        assertThrows(IllegalArgumentException.class, () -> toolExecution(
                1, "call id", "read_file", "{}", "ok", true));
        assertThrows(IllegalArgumentException.class, () -> toolExecution(
                1, "call-1", "bad tool", "{}", "ok", true));
        assertThrows(IllegalArgumentException.class, () -> toolExecution(
                1, "call-1", "read_file",
                "x".repeat(BenchmarkRelayProtocol.MAX_TEXT_CHARS + 1), "ok", true));
        assertThrows(IllegalArgumentException.class, () -> toolExecution(
                1, "call-1", "read_file", "{}", "x".repeat(16_385), true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireToolExecution(
                1, "call-1", "read_file", "{}", "ok", "A".repeat(64),
                2, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireToolExecution(
                1, "call-1", "read_file", "{}", "ok", "0".repeat(64),
                -1, 1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireToolExecution(
                1, "call-1", "read_file", "{}", "ok", "0".repeat(64),
                2, -1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireToolExecution(
                1, "call-1", "read_file", "{}", "ok", "0".repeat(64),
                3, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireToolExecution(
                1, "call-1", "read_file", "{}", "x".repeat(16_383), "0".repeat(64),
                16_385, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.WireToolExecution(
                1, "call-1", "read_file", "{}", "ok", "0".repeat(64),
                2, 1, true, true));
    }

    @Test
    void decoderRejectsTamperedInvalidToolExecutionEvidence() throws Exception {
        BenchmarkRelayProtocol.WorkerComplete complete = new BenchmarkRelayProtocol.WorkerComplete(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                "done", List.of(toolExecution(
                        1, "call-1", "read_file", "{}", "ok", true)));
        String valid = new String(BenchmarkRelayProtocol.encode(complete), StandardCharsets.UTF_8);
        String invalidOrdinal = valid.replaceFirst("\"ordinal\":1", "\"ordinal\":0");
        String invalidDigest = valid.replaceFirst("\"resultSha256\":\"[0-9a-f]{64}\"",
                "\"resultSha256\":\"invalid\"");

        assertThrows(IOException.class,
                () -> BenchmarkRelayProtocol.decode(invalidOrdinal.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class,
                () -> BenchmarkRelayProtocol.decode(invalidDigest.getBytes(StandardCharsets.UTF_8)));
    }

    private static BenchmarkRelayProtocol.SessionStart start() {
        return session(BenchmarkRelayProtocol.AgentMode.REACT,
                BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                "2026-08-31", "UTC", System.currentTimeMillis() + 60_000);
    }

    private static BenchmarkRelayProtocol.SessionStart session(
            BenchmarkRelayProtocol.AgentMode mode,
            BenchmarkRelayProtocol.ToolProfile toolProfile,
            String runtimeDate,
            String runtimeZone,
            long deadline) {
        return new BenchmarkRelayProtocol.SessionStart(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "session-1", "deepseek", "deepseek-v4-flash",
                mode, toolProfile,
                "private prompt sk-secret /Users/private/key", runtimeDate, runtimeZone, deadline,
                new BenchmarkRelayProtocol.AgentLimits(
                        100_000, 100, 3, 1_000_000, 16_384), capabilities(),
                new BenchmarkRelayProtocol.Limits(
                        BenchmarkFramedChannel.MAX_FRAME_BYTES,
                        BenchmarkFramedChannel.MAX_SESSION_BYTES,
                        BenchmarkRelayProtocol.MAX_MESSAGES,
                        BenchmarkRelayProtocol.MAX_TOOLS),
                toolProfile == BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY ? List.of("support")
                        : toolProfile == BenchmarkRelayProtocol.ToolProfile.MOCK_MCP ? List.of("benchmark") : List.of(),
                BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN);
    }

    private static BenchmarkRelayProtocol.WorkerReady ready() {
        return new BenchmarkRelayProtocol.WorkerReady(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.WORKER_READY, "", 0), capabilities());
    }

    private static BenchmarkRelayProtocol.ChatRequest request(String callId) {
        BenchmarkRelayProtocol.WireMessage message = new BenchmarkRelayProtocol.WireMessage(
                "system", "line1\n中文", null, List.of(), null);
        BenchmarkRelayProtocol.WireTool tool = new BenchmarkRelayProtocol.WireTool(
                "read_file", "read", JSON.createObjectNode().put("type", "object"));
        return new BenchmarkRelayProtocol.ChatRequest(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                        BenchmarkRelayProtocol.FrameType.CHAT_REQUEST, callId, 0),
                List.of(message), List.of(tool));
    }

    private static BenchmarkRelayProtocol.StreamDelta delta(String callId, long sequence) {
        return new BenchmarkRelayProtocol.StreamDelta(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.STREAM_DELTA, callId, sequence),
                BenchmarkRelayProtocol.DeltaKind.CONTENT, "delta");
    }

    private static BenchmarkRelayProtocol.ChatComplete complete(String callId, long sequence) {
        return new BenchmarkRelayProtocol.ChatComplete(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.CHAT_COMPLETE, callId, sequence),
                new BenchmarkRelayProtocol.WireChatResponse(
                        "assistant", "done", null, List.of(), 1, 2, 0, "resolved", true));
    }

    private static BenchmarkRelayProtocol.ChatFailure failure(String callId, long sequence) {
        return new BenchmarkRelayProtocol.ChatFailure(
                header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.CHAT_FAILURE, callId, sequence),
                "UPSTREAM", "failed", true);
    }

    private static BenchmarkRelayProtocol.WorkerComplete workerComplete() {
        return new BenchmarkRelayProtocol.WorkerComplete(
                header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0), "done");
    }

    private static BenchmarkRelayProtocol.WireToolExecution toolExecution(
            int ordinal, String callId, String toolName, String arguments,
            String resultPreview, boolean successful) {
        return new BenchmarkRelayProtocol.WireToolExecution(
                ordinal, callId, toolName, arguments, resultPreview, "0".repeat(64),
                resultPreview.length(), 3, false, successful);
    }

    private static BenchmarkRelayProtocol.Capabilities capabilities() {
        return new BenchmarkRelayProtocol.Capabilities(
                true, true, true, true, false, "automatic-prefix-cache", 1_000_000);
    }

    private static BenchmarkRelayProtocol.Header header(BenchmarkRelayProtocol.Direction direction,
                                                         BenchmarkRelayProtocol.FrameType type,
                                                         String callId, long sequence) {
        return new BenchmarkRelayProtocol.Header(direction, type, callId, sequence);
    }
}
