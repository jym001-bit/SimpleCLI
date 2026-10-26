package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.tool.CommandExecutionObserver;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned, provider-neutral wire protocol for relaying LLM calls out of a
 * network-isolated benchmark worker.
 *
 * <p>The protocol deliberately has no API-key, endpoint, or host-path field.
 * Those values must remain on the coordinator side of the isolation boundary.
 */
public final class BenchmarkRelayProtocol {
    public static final int VERSION = 12;
    public static final int MAX_MCP_SERVERS = 8;
    public static final int MAX_TEXT_CHARS = 1_048_576;
    public static final int MAX_RESULT_CHARS = 4 * 1_048_576;
    public static final int MAX_MESSAGES = 512;
    public static final int MAX_TOOLS = 128;
    public static final int MAX_TOOL_CALLS = 128;
    public static final int MAX_TOOL_EXECUTIONS = 4_096;
    public static final int MAX_COMMAND_OBSERVATIONS = 8_192;
    public static final int MAX_TEAM_EVENTS = 16_384;
    public static final int MAX_TEAM_FRAME_BYTES = TeamObservationWire.MAX_EVENT_BYTES + 2_048;
    public static final int MAX_CONTENT_PARTS = 128;
    public static final long MIN_DEADLINE_EPOCH_MILLIS = 1_577_836_800_000L;
    public static final long MAX_DEADLINE_EPOCH_MILLIS = 4_102_444_800_000L;
    public static final long MAX_DEADLINE_AHEAD_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BenchmarkRelayProtocol() {
    }

    public enum Direction {
        COORDINATOR_TO_WORKER,
        WORKER_TO_COORDINATOR
    }

    public enum FrameType {
        SESSION_START,
        WORKER_READY,
        CHAT_REQUEST,
        STREAM_DELTA,
        CHAT_COMPLETE,
        CHAT_FAILURE,
        MCP_REQUEST,
        MCP_COMPLETE,
        WEB_REQUEST,
        WEB_COMPLETE,
        TURN_COMPLETE,
        TURN_CONTINUE,
        APPROVAL_REQUEST,
        APPROVAL_COMPLETE,
        PLAN_EVENT,
        PLAN_EVENT_ACK,
        TEAM_EVENT,
        TEAM_EVENT_ACK,
        F3_TOOL_RESULT,
        F3_TOOL_RESULT_ACK,
        WORKER_COMPLETE,
        WORKER_FAILURE,
        SESSION_CANCEL
    }

    public enum DeltaKind {
        REASONING,
        CONTENT
    }

    public enum AgentMode { REACT, PLAN, TEAM }

    /** Closed workflow, not a Candidate-controlled number of turns or arbitrary host callbacks. */
    public enum InteractionMode { SINGLE_TURN, TWO_TURN_APPROVAL }

    public enum ToolProfile { REASONING_ONLY, READ_ONLY, CODE_RAG, FILE_ONLY, LOCAL_COMMAND, MOCK_MCP, MOCK_WEB, MOCK_MCP_FILE_ONLY }
    public enum WebOperation { SEARCH, CHECK_URL, FETCH }

    public enum EndpointRole { COORDINATOR, WORKER }

    public record Header(Direction direction, FrameType type, String callId, long eventSequence) {
        public Header {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(type, "type");
            callId = callId == null ? "" : callId;
            if (!callId.isEmpty()) {
                requireIdentifier(callId, 128, "callId");
            }
            if (eventSequence < 0) {
                throw new IllegalArgumentException("eventSequence must be non-negative");
            }
        }
    }

    public sealed interface Frame permits SessionStart, WorkerReady, ChatRequest, StreamDelta,
            ChatComplete, ChatFailure, McpRequest, McpComplete, TurnComplete, TurnContinue,
            ApprovalRequest, ApprovalComplete, WebRequest, WebComplete, PlanEvent, PlanEventAck,
            TeamEvent, TeamEventAck,
            F3ToolResult, F3ToolResultAck,
            WorkerComplete, WorkerFailure, SessionCancel {
        Header header();

        default Direction direction() {
            return header().direction();
        }

        default FrameType type() {
            return header().type();
        }

        default String callId() {
            return header().callId();
        }

        default long eventSequence() {
            return header().eventSequence();
        }
    }

    public record SessionStart(Header header, String sessionId, String provider, String model,
                               AgentMode mode, ToolProfile toolProfile, String prompt,
                               String runtimeDate, String runtimeZone,
                               long deadlineEpochMillis, AgentLimits agentLimits,
                               Capabilities capabilities, Limits limits, List<String> mockServers,
                               InteractionMode interactionMode) implements Frame {
        public SessionStart(Header header, String sessionId, String provider, String model,
                            AgentMode mode, ToolProfile toolProfile, String prompt,
                            String runtimeDate, String runtimeZone, long deadlineEpochMillis,
                            AgentLimits agentLimits, Capabilities capabilities, Limits limits, List<String> mockServers) {
            this(header, sessionId, provider, model, mode, toolProfile, prompt, runtimeDate, runtimeZone,
                    deadlineEpochMillis, agentLimits, capabilities, limits, mockServers, InteractionMode.SINGLE_TURN);
        }
        public SessionStart(Header header, String sessionId, String provider, String model,
                            AgentMode mode, ToolProfile toolProfile, String prompt,
                            String runtimeDate, String runtimeZone, long deadlineEpochMillis,
                            AgentLimits agentLimits, Capabilities capabilities, Limits limits) {
            this(header, sessionId, provider, model, mode, toolProfile, prompt, runtimeDate, runtimeZone,
                    deadlineEpochMillis, agentLimits, capabilities, limits,
                    toolProfile == ToolProfile.MOCK_MCP ? List.of("benchmark") : List.of());
        }

        public SessionStart {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.SESSION_START, false, true);
            requireIdentifier(sessionId, 128, "sessionId");
            requireIdentifier(provider, 64, "provider");
            requireIdentifier(model, 256, "model");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(toolProfile, "toolProfile");
            Objects.requireNonNull(interactionMode, "interactionMode");
            if (mode == AgentMode.TEAM && (interactionMode != InteractionMode.SINGLE_TURN
                    || toolProfile == ToolProfile.MOCK_MCP || toolProfile == ToolProfile.MOCK_WEB
                    || toolProfile == ToolProfile.MOCK_MCP_FILE_ONLY))
                throw new IllegalArgumentException("Team relay requires a single-turn static tool profile");
            if (toolProfile == ToolProfile.MOCK_WEB && mode != AgentMode.REACT)
                throw new IllegalArgumentException("mock Web currently requires ReAct");
            if (interactionMode == InteractionMode.TWO_TURN_APPROVAL
                    && (mode != AgentMode.REACT || toolProfile != ToolProfile.MOCK_MCP))
                throw new IllegalArgumentException("two-turn approval requires ReAct and mock MCP");
            mockServers = List.copyOf(Objects.requireNonNull(mockServers, "mockServers"));
            if (toolProfile == ToolProfile.MOCK_MCP_FILE_ONLY && (mode != AgentMode.REACT
                    || interactionMode != InteractionMode.SINGLE_TURN || !mockServers.equals(List.of("support"))))
                throw new IllegalArgumentException("F3 requires single-turn ReAct and support MCP");
            if ((toolProfile == ToolProfile.MOCK_MCP || toolProfile == ToolProfile.MOCK_MCP_FILE_ONLY) != !mockServers.isEmpty()
                    || mockServers.size() > MAX_MCP_SERVERS
                    || new java.util.HashSet<>(mockServers).size() != mockServers.size())
                throw new IllegalArgumentException("invalid frozen MCP server set");
            mockServers.forEach(BenchmarkRelayProtocol::validateMockServer);
            requireText(prompt, MAX_TEXT_CHARS, "prompt");
            validateRuntimeDate(runtimeDate);
            if (!"UTC".equals(runtimeZone)) {
                throw new IllegalArgumentException("runtimeZone must be UTC");
            }
            long now = System.currentTimeMillis();
            if (deadlineEpochMillis < MIN_DEADLINE_EPOCH_MILLIS
                    || deadlineEpochMillis > MAX_DEADLINE_EPOCH_MILLIS
                    || deadlineEpochMillis <= now
                    || deadlineEpochMillis > now + MAX_DEADLINE_AHEAD_MILLIS) {
                throw new IllegalArgumentException("deadlineEpochMillis out of range");
            }
            Objects.requireNonNull(agentLimits, "agentLimits");
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(limits, "limits");
            if (capabilities.maxContextWindow() != agentLimits.contextWindowCapTokens()) {
                throw new IllegalArgumentException(
                        "capabilities maxContextWindow must equal contextWindowCapTokens");
            }
        }

        @Override
        public String toString() {
            return "SessionStart[header=" + header + ", sessionId=" + sessionId
                    + ", provider=" + provider + ", model=" + model + ", mode=" + mode
                    + ", toolProfile=" + toolProfile + ", interactionMode=" + interactionMode
                    + ", prompt=<redacted chars=" + prompt.length()
                    + ">, runtimeDate=<frozen>, runtimeZone=<frozen>"
                    + ", deadlineEpochMillis=" + deadlineEpochMillis + ", agentLimits=" + agentLimits
                    + ", capabilities=" + capabilities + ", limits=" + limits + "]";
        }
    }

    public record WorkerReady(Header header, Capabilities capabilities) implements Frame {
        public WorkerReady {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_READY, false, true);
            Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    /** Only the first, completed Agent turn may request the one host-owned continuation. */
    public record TurnComplete(Header header, int turn, String answer,
                               List<WireToolExecution> toolExecutions) implements Frame {
        public TurnComplete {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.TURN_COMPLETE, true, true);
            if (turn != 1) throw new IllegalArgumentException("only turn one has a continuation");
            answer = nullableText(answer, MAX_RESULT_CHARS, "answer");
            toolExecutions = executionCopy(toolExecutions);
        }
        @Override public String toString() { return "TurnComplete[header=" + header + ", turn=" + turn + ", evidence=<redacted>]"; }
    }

    public record TurnContinue(Header header, int turn, String userMessage) implements Frame {
        public TurnContinue {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.TURN_CONTINUE, true, false);
            if (turn != 2) throw new IllegalArgumentException("only turn two can be continued");
            requireText(userMessage, MAX_TEXT_CHARS, "userMessage");
        }
        @Override public String toString() { return "TurnContinue[header=" + header + ", turn=" + turn + ", userMessage=<redacted>]"; }
    }

    /** Native HITL asks the host; neither the LLM nor an MCP method can grant approval. */
    public record ApprovalRequest(Header header, int turn, String toolName, String argumentsJson) implements Frame {
        public ApprovalRequest {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.APPROVAL_REQUEST, true, true);
            if (turn < 1 || turn > 2) throw new IllegalArgumentException("invalid approval turn");
            requireIdentifier(toolName, 128, "toolName");
            requireSize(argumentsJson, 65_536, "argumentsJson");
            Objects.requireNonNull(argumentsJson, "argumentsJson");
        }
        @Override public String toString() { return "ApprovalRequest[header=" + header + ", turn=" + turn + ", arguments=<redacted>]"; }
    }

    /** No edited arguments, approve-all flag, or server-wide grant exists on this wire. */
    public record ApprovalComplete(Header header, int turn, String toolName, String argumentsSha256,
                                   boolean approved, String reason) implements Frame {
        public ApprovalComplete {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.APPROVAL_COMPLETE, true, false);
            if (turn < 1 || turn > 2) throw new IllegalArgumentException("invalid approval turn");
            requireIdentifier(toolName, 128, "toolName");
            if (argumentsSha256 == null || !argumentsSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid approval arguments digest");
            reason = nullableText(reason, 4096, "reason");
        }
        @Override public String toString() { return "ApprovalComplete[header=" + header + ", turn=" + turn + ", approved=" + approved + ", reason=<redacted>]"; }
    }

    public static String textSha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record PlanEvent(Header header, String eventType, JsonNode event) implements Frame {
        public PlanEvent {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.PLAN_EVENT, true, true);
            try { PlanObservationWire.decode(eventType, event); }
            catch (IOException e) { throw new IllegalArgumentException("invalid Plan event", e); }
            event = event.deepCopy();
        }
        @Override public JsonNode event() { return event.deepCopy(); }
    }

    public record PlanEventAck(Header header) implements Frame {
        public PlanEventAck {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.PLAN_EVENT_ACK, true, false);
        }
    }

    /** Native Team metadata requires a host acknowledgement before an activation may call the provider. */
    public record TeamEvent(Header header, String eventType, JsonNode event) implements Frame {
        public TeamEvent {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.TEAM_EVENT, true, true);
            try { TeamObservationWire.decode(eventType, event); }
            catch (IOException e) { throw new IllegalArgumentException("invalid Team event", e); }
            event = event.deepCopy();
        }
        @Override public JsonNode event() { return event.deepCopy(); }
        @Override public String toString() { return "TeamEvent[header=" + header + ", eventType=" + eventType + ", event=<redacted>]"; }
    }

    public record TeamEventAck(Header header) implements Frame {
        public TeamEventAck {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.TEAM_EVENT_ACK, true, false);
        }
    }

    public record ChatRequest(Header header, List<WireMessage> messages, List<WireTool> tools,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                              String executionScope) implements Frame {
        public ChatRequest(Header header, List<WireMessage> messages, List<WireTool> tools) {
            this(header, messages, tools, null);
        }
        public ChatRequest {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.CHAT_REQUEST, true, true);
            messages = boundedCopy(messages, MAX_MESSAGES, "messages");
            tools = boundedCopy(tools, MAX_TOOLS, "tools");
            if (executionScope != null) requireIdentifier(executionScope, 128, "executionScope");
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages must not be empty");
            }
        }

        @Override
        public String toString() {
            return "ChatRequest[header=" + header + ", messages=<" + messages.size()
                    + " redacted>, tools=" + tools.size() + "]";
        }
    }

    public record StreamDelta(Header header, DeltaKind kind, String delta) implements Frame {
        public StreamDelta {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.STREAM_DELTA, true, false);
            Objects.requireNonNull(kind, "kind");
            requireSize(delta, MAX_TEXT_CHARS, "delta");
            if (delta == null || delta.isEmpty()) {
                throw new IllegalArgumentException("delta must not be empty");
            }
        }

        @Override
        public String toString() {
            return "StreamDelta[header=" + header + ", kind=" + kind + ", delta=<redacted chars="
                    + delta.length() + ">]";
        }
    }

    public record ChatComplete(Header header, WireChatResponse response) implements Frame {
        public ChatComplete {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.CHAT_COMPLETE, true, false);
            Objects.requireNonNull(response, "response");
        }
    }

    public record ChatFailure(Header header, String errorType, String errorMessage,
                              boolean retryable) implements Frame {
        public ChatFailure {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.CHAT_FAILURE, true, false);
            requireIdentifier(errorType, 128, "errorType");
            errorMessage = nullableText(errorMessage, 16_384, "errorMessage");
        }

        @Override
        public String toString() {
            return "ChatFailure[header=" + header + ", errorType=" + errorType
                    + ", errorMessage=<redacted>, retryable=" + retryable + "]";
        }
    }

    public record WorkerComplete(Header header, String answer,
                                 List<WireToolExecution> toolExecutions,
                                 List<CommandExecutionObserver.Event> commandObservations,
                                 long commandObservationFailures) implements Frame {
        public WorkerComplete(Header header, String answer) {
            this(header, answer, List.of());
        }

        public WorkerComplete(Header header, String answer, List<WireToolExecution> toolExecutions) {
            this(header, answer, toolExecutions, List.of(), 0);
        }

        public WorkerComplete {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_COMPLETE, false, true);
            answer = nullableText(answer, MAX_RESULT_CHARS, "answer");
            toolExecutions = executionCopy(toolExecutions);
            commandObservations = commandObservationCopy(commandObservations);
            if (commandObservationFailures < 0)
                throw new IllegalArgumentException("commandObservationFailures must be non-negative");
        }

        @Override
        public String toString() {
            return "WorkerComplete[header=" + header + ", answer=<redacted chars="
                    + answer.length() + ">, toolExecutions=" + toolExecutions.size()
                    + ", commandObservations=" + commandObservations.size()
                    + ", commandObservationFailures=" + commandObservationFailures + "]";
        }
    }

    public record WorkerFailure(Header header, String errorType, String errorMessage) implements Frame {
        public WorkerFailure {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_FAILURE, false, true);
            requireIdentifier(errorType, 128, "errorType");
            errorMessage = nullableText(errorMessage, 16_384, "errorMessage");
        }

        @Override
        public String toString() {
            return "WorkerFailure[header=" + header + ", errorType=" + errorType
                    + ", errorMessage=<redacted>]";
        }
    }

    public record SessionCancel(Header header, String reason) implements Frame {
        public SessionCancel {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.SESSION_CANCEL, false, true);
            reason = nullableText(reason, 4096, "reason");
        }

        @Override
        public String toString() {
            return "SessionCancel[header=" + header + ", reason=<redacted>]";
        }
    }

    public record WireMessage(String role, String content, String reasoningContent,
                              List<WireToolCall> toolCalls, String toolCallId,
                              List<WireContentPart> contentParts) {
        public WireMessage(String role, String content, String reasoningContent,
                           List<WireToolCall> toolCalls, String toolCallId) {
            this(role, content, reasoningContent, toolCalls, toolCallId, List.of());
        }

        public WireMessage {
            requireText(role, 32, "message.role");
            role = role.toLowerCase(Locale.ROOT);
            if (!List.of("system", "user", "assistant", "tool").contains(role)) {
                throw new IllegalArgumentException("unsupported message role: " + role);
            }
            content = nullableText(content, MAX_TEXT_CHARS, "message.content");
            reasoningContent = nullableOptionalText(reasoningContent, MAX_TEXT_CHARS, "message.reasoningContent");
            toolCalls = boundedCopy(toolCalls, MAX_TOOL_CALLS, "message.toolCalls");
            toolCallId = nullableOptionalText(toolCallId, 128, "message.toolCallId");
            contentParts = boundedCopy(contentParts, MAX_CONTENT_PARTS, "message.contentParts");
            if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
                throw new IllegalArgumentException("tool message requires toolCallId");
            }
        }

        @Override
        public String toString() {
            return "WireMessage[role=" + role + ", content=<redacted chars=" + content.length()
                    + ">, reasoningContent=" + (reasoningContent == null ? "<absent>" : "<redacted>")
                    + ", toolCalls=" + toolCalls.size() + ", toolCallId="
                    + (toolCallId == null ? "<absent>" : "<configured>")
                    + ", contentParts=" + contentParts.size() + "]";
        }
    }

    public record WireContentPart(String type, String text, String imageBase64,
                                  String imageUrl, String mimeType) {
        public WireContentPart {
            requireText(type, 32, "contentPart.type");
            type = type.toLowerCase(Locale.ROOT);
            text = nullableOptionalText(text, MAX_TEXT_CHARS, "contentPart.text");
            imageBase64 = nullableOptionalText(imageBase64, MAX_RESULT_CHARS, "contentPart.imageBase64");
            imageUrl = nullableOptionalText(imageUrl, 16_384, "contentPart.imageUrl");
            mimeType = nullableOptionalText(mimeType, 128, "contentPart.mimeType");
            switch (type) {
                case "text" -> {
                    if (text == null || imageBase64 != null || imageUrl != null) {
                        throw new IllegalArgumentException("text content part has invalid fields");
                    }
                }
                case "image_base64" -> {
                    if (imageBase64 == null || text != null || imageUrl != null) {
                        throw new IllegalArgumentException("image_base64 content part has invalid fields");
                    }
                }
                case "image_url" -> {
                    if (imageUrl == null || text != null || imageBase64 != null) {
                        throw new IllegalArgumentException("image_url content part has invalid fields");
                    }
                }
                default -> throw new IllegalArgumentException("unsupported content part type: " + type);
            }
        }

        @Override
        public String toString() {
            return "WireContentPart[type=" + type + ", payload=<redacted>, mimeType="
                    + (mimeType == null ? "<absent>" : "<configured>") + "]";
        }
    }

    public record WireTool(String name, String description, JsonNode parameters) {
        public WireTool {
            requireIdentifier(name, 128, "tool.name");
            description = nullableText(description, 16_384, "tool.description");
            Objects.requireNonNull(parameters, "tool.parameters");
            if (!parameters.isObject()) {
                throw new IllegalArgumentException("tool.parameters must be a JSON object");
            }
            if (parameters.toString().length() > MAX_TEXT_CHARS) {
                throw new IllegalArgumentException("tool.parameters exceeds size limit");
            }
            parameters = parameters.deepCopy();
        }

        @Override
        public String toString() {
            return "WireTool[name=" + name + ", description=<redacted>, parameters=<redacted>]";
        }
    }

    public record WireToolCall(String id, String name, String arguments) {
        public WireToolCall {
            requireIdentifier(id, 128, "toolCall.id");
            requireIdentifier(name, 128, "toolCall.name");
            arguments = nullableText(arguments, MAX_TEXT_CHARS, "toolCall.arguments");
        }

        @Override
        public String toString() {
            return "WireToolCall[id=" + id + ", name=" + name + ", arguments=<redacted>]";
        }
    }

    /** Bounded, private verifier evidence emitted by the trusted Worker wrapper. */
    public record WireToolExecution(int ordinal, String callId, String toolName,
                                    String argumentsJson, String resultPreview,
                                    String resultSha256, long resultChars,
                                    long elapsedMillis, boolean timedOut, boolean successful) {
        public WireToolExecution {
            if (ordinal <= 0 || ordinal > MAX_TOOL_EXECUTIONS) {
                throw new IllegalArgumentException("toolExecution.ordinal out of range");
            }
            requireIdentifier(callId, 128, "toolExecution.callId");
            requireIdentifier(toolName, 128, "toolExecution.toolName");
            argumentsJson = nullableText(
                    argumentsJson, MAX_TEXT_CHARS, "toolExecution.argumentsJson");
            resultPreview = nullableText(
                    resultPreview, 16_384, "toolExecution.resultPreview");
            if (resultSha256 == null || !resultSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "toolExecution.resultSha256 must be lowercase SHA-256");
            }
            if (resultChars < 0 || elapsedMillis < 0) {
                throw new IllegalArgumentException(
                        "toolExecution sizes and elapsed time must be non-negative");
            }
            if (resultChars <= 16_384 && resultPreview.length() != resultChars) {
                throw new IllegalArgumentException(
                        "untruncated toolExecution preview length must equal resultChars");
            }
            if (resultChars > 16_384 && resultPreview.length() != 16_384) {
                throw new IllegalArgumentException(
                        "truncated toolExecution preview must use the full preview limit");
            }
            if (timedOut && successful) {
                throw new IllegalArgumentException(
                        "timed-out tool execution cannot be successful");
            }
        }

        @Override
        public String toString() {
            return "WireToolExecution[ordinal=" + ordinal + ", callId=" + callId
                    + ", toolName=" + toolName + ", argumentsJson=<redacted chars="
                    + argumentsJson.length() + ">, resultPreview=<redacted chars="
                    + resultPreview.length() + ">, resultSha256=" + resultSha256
                    + ", resultChars=" + resultChars + ", elapsedMillis=" + elapsedMillis
                    + ", timedOut=" + timedOut + ", successful=" + successful + "]";
        }
    }

    public record WireChatResponse(String role, String content, String reasoningContent,
                                   List<WireToolCall> toolCalls, int inputTokens, int outputTokens,
                                   int cachedInputTokens, String resolvedModel, boolean usagePresent) {
        public WireChatResponse {
            requireText(role, 32, "response.role");
            role = role.toLowerCase(Locale.ROOT);
            if (!"assistant".equals(role)) {
                throw new IllegalArgumentException("response.role must be assistant");
            }
            content = nullableText(content, MAX_TEXT_CHARS, "response.content");
            reasoningContent = nullableOptionalText(reasoningContent, MAX_TEXT_CHARS, "response.reasoningContent");
            toolCalls = boundedCopy(toolCalls, MAX_TOOL_CALLS, "response.toolCalls");
            if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
                throw new IllegalArgumentException("token counts must be non-negative");
            }
            if (resolvedModel != null) {
                requireIdentifier(resolvedModel, 256, "response.resolvedModel");
            }
        }

        @Override
        public String toString() {
            return "WireChatResponse[role=" + role + ", content=<redacted chars=" + content.length()
                    + ">, reasoningContent=" + (reasoningContent == null ? "<absent>" : "<redacted>")
                    + ", toolCalls=" + toolCalls.size() + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens + ", cachedInputTokens=" + cachedInputTokens
                    + ", resolvedModel=" + resolvedModel + ", usagePresent=" + usagePresent + "]";
        }
    }

    public record Capabilities(boolean streaming, boolean tools, boolean reasoning,
                               boolean promptCaching, boolean imageInput, String promptCacheMode,
                               int maxContextWindow) {
        public Capabilities {
            promptCacheMode = promptCacheMode == null ? "none" : promptCacheMode;
            requireIdentifier(promptCacheMode, 64, "promptCacheMode");
            if (!promptCaching && !"none".equals(promptCacheMode)) {
                throw new IllegalArgumentException("promptCacheMode must be none when promptCaching is false");
            }
            if (maxContextWindow <= 0 || maxContextWindow > 10_000_000) {
                throw new IllegalArgumentException("maxContextWindow out of range");
            }
        }
    }

    public record AgentLimits(int tokenBudget, int hardMaxIterations, int stagnationWindow,
                              int contextWindowCapTokens, int maxOutputTokensPerCall) {
        public AgentLimits {
            if (tokenBudget <= 0 || tokenBudget > 10_000_000) {
                throw new IllegalArgumentException("tokenBudget out of range");
            }
            if (hardMaxIterations <= 0 || hardMaxIterations > 100_000) {
                throw new IllegalArgumentException("hardMaxIterations out of range");
            }
            if (stagnationWindow < 2 || stagnationWindow > 10_000
                    || stagnationWindow > hardMaxIterations) {
                throw new IllegalArgumentException(
                        "stagnationWindow out of range or exceeds hardMaxIterations");
            }
            if (contextWindowCapTokens < 8_000 || contextWindowCapTokens > 10_000_000) {
                throw new IllegalArgumentException("contextWindowCapTokens out of range");
            }
            if (maxOutputTokensPerCall <= 0 || maxOutputTokensPerCall > 16_384
                    || maxOutputTokensPerCall > contextWindowCapTokens) {
                throw new IllegalArgumentException("maxOutputTokensPerCall out of range");
            }
        }
    }

    public record Limits(int maxFrameBytes, long maxSessionBytes, int maxMessages, int maxTools) {
        public Limits {
            if (maxFrameBytes <= 0 || maxFrameBytes > BenchmarkFramedChannel.MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("maxFrameBytes out of range");
            }
            if (maxSessionBytes <= 0 || maxSessionBytes > BenchmarkFramedChannel.MAX_SESSION_BYTES) {
                throw new IllegalArgumentException("maxSessionBytes out of range");
            }
            if (maxMessages <= 0 || maxMessages > MAX_MESSAGES || maxTools <= 0 || maxTools > MAX_TOOLS) {
                throw new IllegalArgumentException("message/tool limits out of range");
            }
        }
    }

    /** The selector is an identifier from SessionStart, never an endpoint or host path. */
    public record McpRequest(Header header, String server, JsonNode message) implements Frame {
        public McpRequest(Header header, JsonNode message) { this(header, "benchmark", message); }
        public McpRequest {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.MCP_REQUEST, true, true);
            validateMockServer(server);
            message = boundedMcpMessage(message, false, 65_536);
        }

        @Override public JsonNode message() { return message.deepCopy(); }
    }

    /** A JSON null acknowledges a notification without inventing a JSON-RPC response. */
    public record McpComplete(Header header, String server, JsonNode message) implements Frame {
        public McpComplete(Header header, JsonNode message) { this(header, "benchmark", message); }
        public McpComplete {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.MCP_COMPLETE, true, false);
            validateMockServer(server);
            message = boundedMcpMessage(message, true, 262_144);
        }

        @Override public JsonNode message() { return message.deepCopy(); }
    }

    public static void validateMockServer(String server) {
        if (server == null || !server.matches("[a-z][a-z0-9]{0,31}"))
            throw new IllegalArgumentException("invalid mock server identifier");
    }

    /** Document identifiers go to a closed host fixture, never to an outbound network proxy. */
    public record WebRequest(Header header, WebOperation operation, String input, Integer topK) implements Frame {
        public WebRequest {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.WEB_REQUEST, true, true);
            Objects.requireNonNull(operation, "operation"); Objects.requireNonNull(topK, "topK");
            requireText(input, 4096, "web.input");
            if (operation == WebOperation.SEARCH ? topK < 1 || topK > 20 : topK != 0)
                throw new IllegalArgumentException("invalid Web operation/topK");
        }
        @Override public String toString() { return "WebRequest[header=" + header + ", operation=" + operation + ", input=<redacted>]"; }
    }

    public record WebSearchResult(Integer position, String title, String url, String snippet, String source) {
        public WebSearchResult {
            if (position == null || position < 1 || position > 20) throw new IllegalArgumentException("invalid search position");
            requireSize(title, 4096, "web.title"); requireText(url, 4096, "web.url");
            requireSize(snippet, 4096, "web.snippet"); requireSize(source, 256, "web.source");
            Objects.requireNonNull(title); Objects.requireNonNull(snippet); Objects.requireNonNull(source);
        }
    }

    public record WebPage(String url, String body, String contentType, String charset, Boolean truncated) {
        public WebPage {
            requireText(url, 4096, "web.url"); requireSize(body, 262_144, "web.body");
            Objects.requireNonNull(body); Objects.requireNonNull(truncated);
            requireText(contentType, 256, "web.contentType"); requireText(charset, 64, "web.charset");
        }
        @Override public String toString() { return "WebPage[url=<redacted>, body=<redacted chars=" + body.length() + ">]"; }
    }

    public record WebComplete(Header header, WebOperation operation, List<WebSearchResult> results,
                              WebPage page, String denial) implements Frame {
        public WebComplete {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.WEB_COMPLETE, true, false);
            Objects.requireNonNull(operation); results = List.copyOf(Objects.requireNonNull(results));
            if (results.size() > 20) throw new IllegalArgumentException("too many Web search results");
            switch (operation) {
                case SEARCH -> { if (page != null || denial != null) throw new IllegalArgumentException("invalid search response"); }
                case CHECK_URL -> {
                    if (!results.isEmpty() || page != null || denial == null) throw new IllegalArgumentException("invalid URL check response");
                    requireSize(denial, 4096, "web.denial");
                }
                case FETCH -> { if (!results.isEmpty() || page == null || denial != null) throw new IllegalArgumentException("invalid fetch response"); }
            }
        }
    }

    /** New Web payloads reject missing fields and scalar coercion without changing legacy frame decoding. */
    private static void validateWebPayload(JsonNode node, boolean response) throws IOException {
        requireKeys(node, response ? java.util.Set.of("header", "operation", "results", "page", "denial")
                : java.util.Set.of("header", "operation", "input", "topK"));
        requireKeys(node.path("header"), java.util.Set.of("direction", "type", "callId", "eventSequence"));
        for (String key : List.of("direction", "type", "callId")) requireNode(node.path("header").path(key).isTextual());
        requireNode(node.path("header").path("eventSequence").isIntegralNumber() && node.path("header").path("eventSequence").canConvertToLong());
        requireNode(node.path("operation").isTextual());
        if (!response) {
            requireNode(node.path("input").isTextual());
            requireNode(node.path("topK").isIntegralNumber() && node.path("topK").canConvertToInt());
            return;
        }
        requireNode(node.path("results").isArray());
        requireNode(node.path("denial").isNull() || node.path("denial").isTextual());
        for (JsonNode result : node.path("results")) {
            requireKeys(result, java.util.Set.of("position", "title", "url", "snippet", "source"));
            requireNode(result.path("position").isIntegralNumber() && result.path("position").canConvertToInt());
            for (String key : List.of("title", "url", "snippet", "source")) requireNode(result.path(key).isTextual());
        }
        if (!node.path("page").isNull()) {
            JsonNode page = node.path("page");
            requireKeys(page, java.util.Set.of("url", "body", "contentType", "charset", "truncated"));
            for (String key : List.of("url", "body", "contentType", "charset")) requireNode(page.path(key).isTextual());
            requireNode(page.path("truncated").isBoolean());
        }
    }

    private static void requireKeys(JsonNode node, java.util.Set<String> expected) throws IOException {
        var actual = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        requireNode(node.isObject() && actual.equals(expected));
    }
    private static void requireNode(boolean valid) throws IOException { if (!valid) throw new IOException("invalid Web frame schema"); }

    private static JsonNode boundedMcpMessage(JsonNode message, boolean allowNull, int maxChars) {
        if (message == null || !(message.isObject() || allowNull && message.isNull())
                || message.toString().length() > maxChars) {
            throw new IllegalArgumentException("invalid or oversized MCP message");
        }
        return message.deepCopy();
    }

    public record RawToolResult(int ordinal, String callId, String toolName, String argumentsJson,
                                String result, long elapsedMillis, boolean timedOut, boolean successful) {
        public RawToolResult {
            if (ordinal < 1 || ordinal > MAX_TOOL_EXECUTIONS || elapsedMillis < 0 || timedOut && successful)
                throw new IllegalArgumentException("invalid F3 result ordinal or time");
            requireIdentifier(callId, 128, "raw callId"); requireIdentifier(toolName, 128, "raw toolName");
            requireSize(Objects.requireNonNull(argumentsJson), MAX_TEXT_CHARS, "raw arguments");
            requireSize(Objects.requireNonNull(result), MAX_RESULT_CHARS, "raw result");
        }
    }
    public record F3ToolResult(Header header, RawToolResult result) implements Frame {
        public F3ToolResult {
            validateHeader(header, Direction.WORKER_TO_COORDINATOR, FrameType.F3_TOOL_RESULT, true, true);
            Objects.requireNonNull(result, "F3 raw result");
        }
    }
    public record F3ToolResultAck(Header header) implements Frame {
        public F3ToolResultAck {
            validateHeader(header, Direction.COORDINATOR_TO_WORKER, FrameType.F3_TOOL_RESULT_ACK, true, false);
            if (header.eventSequence() != 1) throw new IllegalArgumentException("F3 acknowledgement sequence");
        }
    }

    private record WireEnvelope(int protocolVersion, Direction direction, FrameType type,
                                String callId, long eventSequence, JsonNode payload) {
    }

    public static byte[] encode(Frame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        Header h = Objects.requireNonNull(frame.header(), "frame.header");
        byte[] encoded = MAPPER.writeValueAsBytes(new WireEnvelope(
                VERSION, h.direction(), h.type(), h.callId(), h.eventSequence(), MAPPER.valueToTree(frame)));
        if ((frame instanceof TeamEvent || frame instanceof TeamEventAck) && encoded.length > MAX_TEAM_FRAME_BYTES)
            throw new TeamEvidenceException(new IOException("Team frame exceeds byte limit"));
        return encoded;
    }

    public static Frame decode(byte[] json) throws IOException {
        Objects.requireNonNull(json, "json");
        JsonNode wire = MAPPER.readTree(json);
        boolean f3 = wire != null && ("F3_TOOL_RESULT".equals(wire.path("type").asText())
                || "F3_TOOL_RESULT_ACK".equals(wire.path("type").asText()));
        boolean team = wire != null && ("TEAM_EVENT".equals(wire.path("type").asText())
                || "TEAM_EVENT_ACK".equals(wire.path("type").asText()));
        if (team && json.length > MAX_TEAM_FRAME_BYTES)
            throw new TeamEvidenceException(new IOException("Team frame exceeds byte limit"));
        try { return decodeValue(wire); }
        catch (IOException | RuntimeException error) {
            if (f3) throw new F3EvidenceException(error);
            if (team) throw new TeamEvidenceException(error);
            if (error instanceof IOException io) throw io;
            throw new IOException("invalid relay frame", error);
        }
    }

    public static final class F3EvidenceException extends IOException {
        private F3EvidenceException(Throwable cause) { super("F3 raw evidence frame rejected", cause); }
    }

    public static final class TeamEvidenceException extends IOException {
        private TeamEvidenceException(Throwable cause) { super("Team observation frame rejected", cause); }
    }

    private static Frame decodeValue(JsonNode wire) throws IOException {
        if (wire != null && ("WEB_REQUEST".equals(wire.path("type").asText())
                || "WEB_COMPLETE".equals(wire.path("type").asText())
                || "PLAN_EVENT".equals(wire.path("type").asText())
                || "TEAM_EVENT".equals(wire.path("type").asText())
                || "TEAM_EVENT_ACK".equals(wire.path("type").asText())
                || "CHAT_REQUEST".equals(wire.path("type").asText())
                || "SESSION_START".equals(wire.path("type").asText()) && "TEAM".equals(wire.path("payload").path("mode").asText())
                || "F3_TOOL_RESULT".equals(wire.path("type").asText())
                || "F3_TOOL_RESULT_ACK".equals(wire.path("type").asText())
                || "WORKER_COMPLETE".equals(wire.path("type").asText())
                || "PLAN_EVENT_ACK".equals(wire.path("type").asText()))) {
            requireKeys(wire, java.util.Set.of("protocolVersion", "direction", "type", "callId", "eventSequence", "payload"));
            requireNode(wire.path("protocolVersion").isIntegralNumber() && wire.path("protocolVersion").canConvertToInt());
            requireNode(wire.path("eventSequence").isIntegralNumber() && wire.path("eventSequence").canConvertToLong());
            for (String key : List.of("direction", "type", "callId")) requireNode(wire.path(key).isTextual());
        }
        WireEnvelope envelope = MAPPER.treeToValue(wire, WireEnvelope.class);
        if (envelope == null) throw new IOException("relay envelope must not be null");
        if (envelope.protocolVersion() != VERSION) {
            throw new IOException("unsupported relay protocol version: " + envelope.protocolVersion());
        }
        if (envelope.direction() == null || envelope.type() == null || envelope.payload() == null
                || !envelope.payload().isObject()) {
            throw new IOException("relay envelope has missing or invalid fields");
        }
        Frame frame;
        try {
            if (envelope.type() == FrameType.F3_TOOL_RESULT || envelope.type() == FrameType.F3_TOOL_RESULT_ACK)
                validateF3Payload(envelope.payload(), envelope.type() == FrameType.F3_TOOL_RESULT);
            if (envelope.type() == FrameType.SESSION_START && ("MOCK_MCP_FILE_ONLY".equals(envelope.payload().path("toolProfile").asText())
                    || "TEAM".equals(envelope.payload().path("mode").asText()))) {
                for (String key : List.of("mode", "toolProfile", "interactionMode", "prompt")) requireNode(envelope.payload().path(key).isTextual());
                requireNode(envelope.payload().path("mockServers").isArray());
                for (JsonNode server : envelope.payload().path("mockServers")) requireNode(server.isTextual());
            }
            if (envelope.type() == FrameType.WORKER_COMPLETE)
                validateWorkerCompletePayload(envelope.payload());
            if (envelope.type() == FrameType.WEB_REQUEST || envelope.type() == FrameType.WEB_COMPLETE)
                validateWebPayload(envelope.payload(), envelope.type() == FrameType.WEB_COMPLETE);
            if (envelope.type() == FrameType.PLAN_EVENT || envelope.type() == FrameType.PLAN_EVENT_ACK
                    || envelope.type() == FrameType.TEAM_EVENT || envelope.type() == FrameType.TEAM_EVENT_ACK) {
                boolean eventFrame = envelope.type() == FrameType.PLAN_EVENT || envelope.type() == FrameType.TEAM_EVENT;
                requireKeys(envelope.payload(), eventFrame
                        ? java.util.Set.of("header", "eventType", "event") : java.util.Set.of("header"));
                JsonNode header = envelope.payload().path("header");
                requireKeys(header, java.util.Set.of("direction", "type", "callId", "eventSequence"));
                for (String key : List.of("direction", "type", "callId")) requireNode(header.path(key).isTextual());
                requireNode(header.path("eventSequence").isIntegralNumber() && header.path("eventSequence").canConvertToLong());
                if (eventFrame) requireNode(envelope.payload().path("eventType").isTextual());
            }
            if (envelope.type() == FrameType.CHAT_REQUEST && envelope.payload().hasNonNull("executionScope"))
                requireNode(envelope.payload().path("executionScope").isTextual());
            frame = switch (envelope.type()) {
                case SESSION_START -> MAPPER.treeToValue(envelope.payload(), SessionStart.class);
                case WORKER_READY -> MAPPER.treeToValue(envelope.payload(), WorkerReady.class);
                case CHAT_REQUEST -> MAPPER.treeToValue(envelope.payload(), ChatRequest.class);
                case STREAM_DELTA -> MAPPER.treeToValue(envelope.payload(), StreamDelta.class);
                case CHAT_COMPLETE -> MAPPER.treeToValue(envelope.payload(), ChatComplete.class);
                case CHAT_FAILURE -> MAPPER.treeToValue(envelope.payload(), ChatFailure.class);
                case MCP_REQUEST -> MAPPER.treeToValue(envelope.payload(), McpRequest.class);
                case MCP_COMPLETE -> MAPPER.treeToValue(envelope.payload(), McpComplete.class);
                case WEB_REQUEST -> MAPPER.treeToValue(envelope.payload(), WebRequest.class);
                case WEB_COMPLETE -> MAPPER.treeToValue(envelope.payload(), WebComplete.class);
                case TURN_COMPLETE -> MAPPER.treeToValue(envelope.payload(), TurnComplete.class);
                case TURN_CONTINUE -> MAPPER.treeToValue(envelope.payload(), TurnContinue.class);
                case APPROVAL_REQUEST -> MAPPER.treeToValue(envelope.payload(), ApprovalRequest.class);
                case APPROVAL_COMPLETE -> MAPPER.treeToValue(envelope.payload(), ApprovalComplete.class);
                case PLAN_EVENT -> MAPPER.treeToValue(envelope.payload(), PlanEvent.class);
                case PLAN_EVENT_ACK -> MAPPER.treeToValue(envelope.payload(), PlanEventAck.class);
                case TEAM_EVENT -> MAPPER.treeToValue(envelope.payload(), TeamEvent.class);
                case TEAM_EVENT_ACK -> MAPPER.treeToValue(envelope.payload(), TeamEventAck.class);
                case F3_TOOL_RESULT -> MAPPER.treeToValue(envelope.payload(), F3ToolResult.class);
                case F3_TOOL_RESULT_ACK -> MAPPER.treeToValue(envelope.payload(), F3ToolResultAck.class);
                case WORKER_COMPLETE -> MAPPER.treeToValue(envelope.payload(), WorkerComplete.class);
                case WORKER_FAILURE -> MAPPER.treeToValue(envelope.payload(), WorkerFailure.class);
                case SESSION_CANCEL -> MAPPER.treeToValue(envelope.payload(), SessionCancel.class);
            };
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid relay frame: " + e.getMessage(), e);
        }
        Header h = frame.header();
        if (h.direction() != envelope.direction() || h.type() != envelope.type()
                || !h.callId().equals(envelope.callId()) || h.eventSequence() != envelope.eventSequence()) {
            throw new IOException("relay envelope and payload headers do not match");
        }
        return frame;
    }

    /** Stateful lifecycle and per-call sequence validation, kept separate from JSON decoding. */
    public static final class Validator {
        private final EndpointRole endpointRole;
        private State state = State.NEW;
        private String activeCallId;
        private String activeMcpServer;
        private java.util.Set<String> mockServers = java.util.Set.of();
        private long nextCallSequence;
        private InteractionMode interactionMode;
        private int turn = 1;
        private ApprovalRequest activeApproval;
        private final java.util.Set<String> interactionIds = new java.util.HashSet<>();
        private ToolProfile profile;
        private AgentMode mode;
        private final java.util.Set<String> planEventIds = new java.util.HashSet<>();
        private final java.util.Set<String> teamEventIds = new java.util.HashSet<>();
        private WebRequest activeWeb;
        private final java.util.Set<String> webIds = new java.util.HashSet<>();
        private final java.util.Set<String> f3ResultIds = new java.util.HashSet<>();

        public Validator() {
            this.endpointRole = null;
        }

        public Validator(EndpointRole endpointRole) {
            this.endpointRole = Objects.requireNonNull(endpointRole, "endpointRole");
        }

        public synchronized void accept(Frame frame) {
            acceptLifecycle(frame);
        }

        public synchronized void acceptSent(Frame frame) {
            requireEndpointDirection(frame, true);
            acceptLifecycle(frame);
        }

        public synchronized void acceptReceived(Frame frame) {
            requireEndpointDirection(frame, false);
            acceptLifecycle(frame);
        }

        private void requireEndpointDirection(Frame frame, boolean sent) {
            Objects.requireNonNull(frame, "frame");
            if (endpointRole == null) {
                throw new IllegalStateException("sent/received validation requires an endpoint role");
            }
            Direction expected;
            if (endpointRole == EndpointRole.COORDINATOR) {
                expected = sent ? Direction.COORDINATOR_TO_WORKER : Direction.WORKER_TO_COORDINATOR;
            } else {
                expected = sent ? Direction.WORKER_TO_COORDINATOR : Direction.COORDINATOR_TO_WORKER;
            }
            if (frame.direction() != expected) {
                throw new IllegalStateException("wrong-direction relay frame " + frame.type()
                        + " for " + endpointRole + " " + (sent ? "send" : "receive"));
            }
        }

        private void acceptLifecycle(Frame frame) {
            Objects.requireNonNull(frame, "frame");
            switch (frame.type()) {
                case SESSION_START -> {
                    requireState(State.NEW, frame);
                    mockServers = java.util.Set.copyOf(((SessionStart) frame).mockServers());
                    interactionMode = ((SessionStart) frame).interactionMode();
                    profile = ((SessionStart) frame).toolProfile();
                    mode = ((SessionStart) frame).mode();
                    state = State.STARTED;
                }
                case WORKER_READY -> {
                    requireState(State.STARTED, frame);
                    state = State.READY;
                }
                case CHAT_REQUEST -> {
                    requireState(State.READY, frame);
                    String scope = ((ChatRequest) frame).executionScope();
                    if (mode == AgentMode.REACT && scope != null
                            || mode == AgentMode.PLAN && (scope == null || scope.startsWith("team:"))
                            || mode == AgentMode.TEAM && (scope == null
                            || !scope.matches("team:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                        throw invalidOrder(frame);
                    activeCallId = frame.callId();
                    nextCallSequence = 1;
                    state = State.CHAT_ACTIVE;
                }
                case PLAN_EVENT -> {
                    requireState(State.READY, frame);
                    if (mode != AgentMode.PLAN || planEventIds.size() >= 16_384 || !planEventIds.add(frame.callId()))
                        throw invalidOrder(frame);
                    activeCallId = frame.callId();
                    state = State.PLAN_EVENT_ACTIVE;
                }
                case TEAM_EVENT -> {
                    requireState(State.READY, frame);
                    if (mode != AgentMode.TEAM || teamEventIds.size() >= MAX_TEAM_EVENTS
                            || !teamEventIds.add(frame.callId())) throw invalidOrder(frame);
                    activeCallId = frame.callId();
                    state = State.TEAM_EVENT_ACTIVE;
                }
                case TEAM_EVENT_ACK -> {
                    requireState(State.TEAM_EVENT_ACTIVE, frame);
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1) throw invalidOrder(frame);
                    activeCallId = null;
                    state = State.READY;
                }
                case F3_TOOL_RESULT -> {
                    requireState(State.READY, frame);
                    if (profile != ToolProfile.MOCK_MCP_FILE_ONLY || mode != AgentMode.REACT
                            || interactionMode != InteractionMode.SINGLE_TURN || f3ResultIds.size() >= MAX_TOOL_EXECUTIONS
                            || !f3ResultIds.add(frame.callId())) throw invalidOrder(frame);
                    activeCallId = frame.callId(); state = State.F3_RESULT_ACTIVE;
                }
                case F3_TOOL_RESULT_ACK -> {
                    requireState(State.F3_RESULT_ACTIVE, frame);
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1) throw invalidOrder(frame);
                    activeCallId = null; state = State.READY;
                }
                case PLAN_EVENT_ACK -> {
                    requireState(State.PLAN_EVENT_ACTIVE, frame);
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1) throw invalidOrder(frame);
                    activeCallId = null;
                    state = State.READY;
                }
                case MCP_REQUEST -> {
                    requireState(State.READY, frame);
                    String server = ((McpRequest) frame).server();
                    if (!mockServers.contains(server)) throw invalidOrder(frame);
                    activeCallId = frame.callId();
                    activeMcpServer = server;
                    nextCallSequence = 1;
                    state = State.MCP_ACTIVE;
                }
                case MCP_COMPLETE -> {
                    requireState(State.MCP_ACTIVE, frame);
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1
                            || !((McpComplete) frame).server().equals(activeMcpServer)) {
                        throw invalidOrder(frame);
                    }
                    activeCallId = null;
                    activeMcpServer = null;
                    state = State.READY;
                }
                case WEB_REQUEST -> {
                    requireState(State.READY, frame);
                    if (profile != ToolProfile.MOCK_WEB || webIds.size() >= MAX_TOOL_EXECUTIONS || !webIds.add(frame.callId()))
                        throw invalidOrder(frame);
                    activeWeb = (WebRequest) frame; activeCallId = frame.callId(); state = State.WEB_ACTIVE;
                }
                case WEB_COMPLETE -> {
                    requireState(State.WEB_ACTIVE, frame);
                    WebComplete complete = (WebComplete) frame;
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1 || complete.operation() != activeWeb.operation()
                            || complete.operation() == WebOperation.SEARCH && complete.results().size() > activeWeb.topK()
                            || complete.operation() == WebOperation.FETCH && !complete.page().url().equals(activeWeb.input())) throw invalidOrder(frame);
                    activeWeb = null; activeCallId = null; state = State.READY;
                }
                case STREAM_DELTA -> acceptCallEvent(frame, false);
                case CHAT_COMPLETE, CHAT_FAILURE -> acceptCallEvent(frame, true);
                case TURN_COMPLETE -> {
                    requireState(State.READY, frame);
                    requireInteraction(frame);
                    if (turn != 1 || !interactionIds.add(frame.callId())) throw invalidOrder(frame);
                    activeCallId = frame.callId();
                    state = State.TURN_ACTIVE;
                }
                case TURN_CONTINUE -> {
                    requireState(State.TURN_ACTIVE, frame);
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1) throw invalidOrder(frame);
                    turn = 2;
                    activeCallId = null;
                    state = State.READY;
                }
                case APPROVAL_REQUEST -> {
                    requireState(State.READY, frame);
                    requireInteraction(frame);
                    ApprovalRequest request = (ApprovalRequest) frame;
                    if (request.turn() != turn || !mockServers.stream().anyMatch(s -> request.toolName().startsWith("mcp__" + s + "__"))
                            || interactionIds.size() >= MAX_TOOL_EXECUTIONS || !interactionIds.add(frame.callId())) throw invalidOrder(frame);
                    activeApproval = request;
                    activeCallId = frame.callId();
                    state = State.APPROVAL_ACTIVE;
                }
                case APPROVAL_COMPLETE -> {
                    requireState(State.APPROVAL_ACTIVE, frame);
                    ApprovalComplete complete = (ApprovalComplete) frame;
                    if (!frame.callId().equals(activeCallId) || frame.eventSequence() != 1 || complete.turn() != turn
                            || !complete.toolName().equals(activeApproval.toolName())
                            || !complete.argumentsSha256().equals(textSha256(activeApproval.argumentsJson()))) throw invalidOrder(frame);
                    activeCallId = null; activeApproval = null;
                    state = State.READY;
                }
                case WORKER_COMPLETE -> {
                    requireState(State.READY, frame);
                    if (interactionMode == InteractionMode.TWO_TURN_APPROVAL && turn != 2) throw invalidOrder(frame);
                    WorkerComplete complete = (WorkerComplete) frame;
                    if (profile != ToolProfile.LOCAL_COMMAND && (!complete.commandObservations().isEmpty()
                            || complete.commandObservationFailures() != 0))
                        throw new IllegalStateException("command observations require LOCAL_COMMAND");
                    state = State.TERMINAL;
                }
                case WORKER_FAILURE -> {
                    // A lost/rejected Team ACK may terminate as evidence-invalid, never resume chat.
                    if (!(mode == AgentMode.TEAM && state == State.TEAM_EVENT_ACTIVE
                            && "REQUEST_FINGERPRINT_UNPROVEN".equals(((WorkerFailure) frame).errorType())))
                        requireState(State.READY, frame);
                    state = State.TERMINAL;
                    activeCallId = null;
                }
                case SESSION_CANCEL -> {
                    if (state == State.NEW || state == State.TERMINAL) {
                        throw invalidOrder(frame);
                    }
                    state = State.TERMINAL;
                    activeCallId = null;
                }
            }
        }

        private void requireInteraction(Frame frame) {
            if (interactionMode != InteractionMode.TWO_TURN_APPROVAL) throw invalidOrder(frame);
        }

        private void acceptCallEvent(Frame frame, boolean terminal) {
            requireState(State.CHAT_ACTIVE, frame);
            if (!frame.callId().equals(activeCallId) || frame.eventSequence() != nextCallSequence) {
                throw new IllegalStateException("out-of-order relay event for call " + frame.callId()
                        + ": expected callId=" + activeCallId + ", sequence=" + nextCallSequence);
            }
            nextCallSequence++;
            if (terminal) {
                state = State.READY;
                activeCallId = null;
            }
        }

        private void requireState(State expected, Frame frame) {
            if (state != expected) {
                throw invalidOrder(frame);
            }
        }

        private IllegalStateException invalidOrder(Frame frame) {
            return new IllegalStateException("out-of-order relay frame " + frame.type() + " in state " + state);
        }

        private enum State { NEW, STARTED, READY, CHAT_ACTIVE, MCP_ACTIVE, WEB_ACTIVE, TURN_ACTIVE, APPROVAL_ACTIVE, PLAN_EVENT_ACTIVE, TEAM_EVENT_ACTIVE, F3_RESULT_ACTIVE, TERMINAL }
    }

    private static List<WireToolExecution> executionCopy(List<WireToolExecution> executions) {
        var copy = boundedCopy(executions, MAX_TOOL_EXECUTIONS, "toolExecutions");
        for (int i = 0; i < copy.size(); i++) if (copy.get(i).ordinal() != i + 1)
            throw new IllegalArgumentException("toolExecutions must use contiguous one-based ordinals");
        return copy;
    }

    private static List<CommandExecutionObserver.Event> commandObservationCopy(
            List<CommandExecutionObserver.Event> observations) {
        var copy = boundedCopy(observations, MAX_COMMAND_OBSERVATIONS, "commandObservations");
        for (var event : copy) {
            if (event.invocationId() <= 0 || event.processId() < 0 || event.timestampMillis() <= 0
                    || event.resultChars() < 0 || event.phase() == null || event.outcome() == null)
                throw new IllegalArgumentException("invalid command observation scalar");
            Objects.requireNonNull(event.command(), "command observation command");
            Objects.requireNonNull(event.workingDirectory(), "command observation workingDirectory");
            Objects.requireNonNull(event.resultSha256(), "command observation resultSha256");
            nullableText(event.command(), MAX_TEXT_CHARS, "command observation command");
            nullableText(event.workingDirectory(), 16_384, "command observation workingDirectory");
            if (!event.resultSha256().isEmpty() && !event.resultSha256().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid command observation digest");
            Objects.requireNonNull(event.arguments(), "command observation arguments");
            for (String argument : boundedCopy(event.arguments(), 16, "command observation arguments")) {
                Objects.requireNonNull(argument, "command observation argument");
                nullableText(argument, MAX_TEXT_CHARS, "command observation argument");
            }
        }
        return copy;
    }

    private static void validateF3Payload(JsonNode payload, boolean result) throws IOException {
        requireKeys(payload, result ? java.util.Set.of("header", "result") : java.util.Set.of("header"));
        JsonNode header = payload.path("header");
        requireKeys(header, java.util.Set.of("direction", "type", "callId", "eventSequence"));
        for (String key : List.of("direction", "type", "callId")) requireNode(header.path(key).isTextual());
        requireNode(header.path("eventSequence").isIntegralNumber() && header.path("eventSequence").canConvertToLong());
        if (!result) return;
        JsonNode raw = payload.path("result");
        requireKeys(raw, java.util.Set.of("ordinal", "callId", "toolName", "argumentsJson", "result", "elapsedMillis", "timedOut", "successful"));
        requireNode(raw.path("ordinal").isIntegralNumber() && raw.path("ordinal").canConvertToInt());
        requireNode(raw.path("elapsedMillis").isIntegralNumber() && raw.path("elapsedMillis").canConvertToLong());
        for (String key : List.of("callId", "toolName", "argumentsJson", "result")) requireNode(raw.path(key).isTextual());
        requireNode(raw.path("timedOut").isBoolean() && raw.path("successful").isBoolean());
    }

    private static void validateWorkerCompletePayload(JsonNode payload) throws IOException {
        requireKeys(payload, java.util.Set.of("header", "answer", "toolExecutions", "commandObservations", "commandObservationFailures"));
        JsonNode header = payload.path("header");
        requireKeys(header, java.util.Set.of("direction", "type", "callId", "eventSequence"));
        for (String key : List.of("direction", "type", "callId")) requireNode(header.path(key).isTextual());
        requireNode(header.path("eventSequence").isIntegralNumber() && header.path("eventSequence").canConvertToLong());
        requireNode(payload.path("answer").isTextual() && payload.path("toolExecutions").isArray());
        requireNode(payload.path("toolExecutions").size() <= MAX_TOOL_EXECUTIONS);
        for (JsonNode tool : payload.path("toolExecutions")) {
            requireKeys(tool, java.util.Set.of("ordinal", "callId", "toolName", "argumentsJson", "resultPreview",
                    "resultSha256", "resultChars", "elapsedMillis", "timedOut", "successful"));
            for (String key : List.of("ordinal", "resultChars"))
                requireNode(tool.path(key).isIntegralNumber() && tool.path(key).canConvertToInt());
            requireNode(tool.path("elapsedMillis").isIntegralNumber() && tool.path("elapsedMillis").canConvertToLong());
            for (String key : List.of("callId", "toolName", "argumentsJson", "resultPreview", "resultSha256"))
                requireNode(tool.path(key).isTextual());
            requireNode(tool.path("timedOut").isBoolean() && tool.path("successful").isBoolean());
        }
        requireNode(payload.path("commandObservationFailures").isIntegralNumber()
                && payload.path("commandObservationFailures").canConvertToLong());
        JsonNode observations = payload.path("commandObservations");
        requireNode(observations.isArray() && observations.size() <= MAX_COMMAND_OBSERVATIONS);
        for (JsonNode event : observations) {
            requireKeys(event, java.util.Set.of("invocationId", "phase", "command", "workingDirectory", "arguments",
                    "processId", "timestampMillis", "exitCode", "outcome", "resultSha256", "resultChars"));
            for (String key : List.of("invocationId", "processId", "timestampMillis"))
                requireNode(event.path(key).isIntegralNumber() && event.path(key).canConvertToLong());
            for (String key : List.of("phase", "command", "workingDirectory", "outcome", "resultSha256"))
                requireNode(event.path(key).isTextual());
            requireNode(event.path("resultChars").isIntegralNumber() && event.path("resultChars").canConvertToInt());
            requireNode(event.path("exitCode").isNull()
                    || event.path("exitCode").isIntegralNumber() && event.path("exitCode").canConvertToInt());
            requireNode(event.path("arguments").isArray() && event.path("arguments").size() <= 16);
            for (JsonNode argument : event.path("arguments")) requireNode(argument.isTextual());
        }
    }

    private static void validateHeader(Header header, Direction direction, FrameType type,
                                       boolean callRequired, boolean zeroSequence) {
        Objects.requireNonNull(header, "header");
        if (header.direction() != direction || header.type() != type) {
            throw new IllegalArgumentException("invalid direction/type for " + type);
        }
        if (callRequired == header.callId().isBlank()) {
            throw new IllegalArgumentException(callRequired ? "callId is required" : "callId must be empty");
        }
        if (zeroSequence && header.eventSequence() != 0) {
            throw new IllegalArgumentException(type + " eventSequence must be zero");
        }
        if (!zeroSequence && header.eventSequence() == 0) {
            throw new IllegalArgumentException(type + " eventSequence must be positive");
        }
    }

    private static void requireText(String value, int limit, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        requireSize(value, limit, label);
    }

    private static void requireIdentifier(String value, int limit, String label) {
        requireText(value, limit, label);
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
    }

    private static void validateRuntimeDate(String value) {
        requireText(value, 10, "runtimeDate");
        try {
            if (!LocalDate.parse(value).toString().equals(value)) {
                throw new IllegalArgumentException("runtimeDate must use ISO YYYY-MM-DD");
            }
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("runtimeDate must use ISO YYYY-MM-DD", error);
        }
    }

    private static String nullableText(String value, int limit, String label) {
        String normalized = value == null ? "" : value;
        requireSize(normalized, limit, label);
        return normalized;
    }

    private static String nullableOptionalText(String value, int limit, String label) {
        if (value != null) {
            requireSize(value, limit, label);
        }
        return value;
    }

    private static void requireSize(String value, int limit, String label) {
        if (value != null && value.length() > limit) {
            throw new IllegalArgumentException(label + " exceeds " + limit + " characters");
        }
    }

    private static <T> List<T> boundedCopy(List<T> values, int limit, String label) {
        List<T> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > limit) {
            throw new IllegalArgumentException(label + " exceeds " + limit + " entries");
        }
        return copy;
    }
}
