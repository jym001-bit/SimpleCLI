package com.paicli.eval.benchmark.relay;

import com.paicli.llm.LlmClient;
import com.paicli.eval.benchmark.BenchmarkProviderFailureClassifier;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** Coordinator-side relay that is the only endpoint allowed to own the real provider client. */
public final class BenchmarkProviderRelay {
    public enum ServeResult {
        CHAT_SERVED,
        MCP_SERVED,
        WEB_SERVED,
        INTERACTION_SERVED,
        PLAN_EVENT_SERVED,
        TEAM_EVENT_SERVED,
        F3_TOOL_RESULT_SERVED,
        WORKER_COMPLETE,
        WORKER_FAILURE
    }

    private final BenchmarkFramedChannel channel;
    private final BenchmarkRelayProtocol.Validator validator;
    private final BenchmarkRelayProtocol.SessionStart session;
    private final LlmClient delegate;
    private final MockMcpEndpoint mockMcp;
    private final MockWebEndpoint mockWeb;
    private final PlanRequestAudit planAudit;
    private final TeamRequestAudit teamAudit;
    private final F2CommandAudit f2CommandAudit;
    private final F3ToolResultAudit f3ToolResultAudit;
    private final java.util.Set<Integer> webToolOrdinals = new java.util.HashSet<>();
    private final java.util.List<WebFetchPermit> webFetchPermits = new java.util.ArrayList<>();
    private int webBatchStart;
    private int mcpRequests;
    private BenchmarkRelayProtocol.Frame terminalFrame;
    private String providerFailureType;
    private final java.util.ArrayList<BenchmarkRelayProtocol.WireToolCall> requestedTools = new java.util.ArrayList<>();
    private List<BenchmarkRelayProtocol.WireToolExecution> firstTurnTools = List.of();
    private long usedTokens;
    private int chatRequests;
    private boolean finalizationUsed;
    private boolean episodeBudgetExhausted;
    private final java.util.Set<Integer> approvalOrdinals = new java.util.HashSet<>();
    private final java.util.List<McpPermit> mcpPermits = new java.util.ArrayList<>();
    private int currentTurn = 1;
    private static final com.fasterxml.jackson.databind.ObjectMapper STRICT_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BenchmarkProviderRelay(BenchmarkFramedChannel channel,
                                   BenchmarkRelayProtocol.Validator validator,
                                   BenchmarkRelayProtocol.SessionStart session,
                                   LlmClient delegate, MockMcpEndpoint mockMcp, MockWebEndpoint mockWeb,
                                   PlanRequestAudit planAudit, F2CommandAudit f2CommandAudit, F3ToolResultAudit f3ToolResultAudit,
                                   TeamRequestAudit teamAudit) {
        this.channel = channel;
        this.validator = validator;
        this.session = session;
        this.delegate = delegate;
        this.mockMcp = mockMcp;
        this.mockWeb = mockWeb;
        this.planAudit = planAudit;
        this.teamAudit = teamAudit;
        this.f2CommandAudit = f2CommandAudit;
        this.f3ToolResultAudit = f3ToolResultAudit;
    }

    /** Host-owned closed mock, never a network endpoint or arbitrary outbound proxy. */
    @FunctionalInterface
    public interface MockMcpEndpoint {
        JsonNode exchange(JsonNode message) throws IOException;
        default List<String> serverNames() { return List.of("benchmark"); }
        default JsonNode exchange(String server, JsonNode message) throws IOException {
            if (!"benchmark".equals(server)) throw new IOException("unregistered mock MCP server");
            return exchange(message);
        }
    }

    /** Closed host-owned fixture only; implementations must not dispatch input URLs onto the public network. */
    public interface MockWebEndpoint {
        default void recordProviderTurn(List<BenchmarkRelayProtocol.WireMessage> messages,
                                        BenchmarkRelayProtocol.WireChatResponse response) throws IOException { }
        List<com.paicli.web.SearchResult> search(String query, int topK) throws IOException;
        String checkUrl(String url) throws IOException;
        com.paicli.web.WebFetcher.RawResponse fetch(String url) throws IOException;
        void recordExchange(int toolOrdinal, BenchmarkRelayProtocol.WebRequest request,
                            BenchmarkRelayProtocol.WebComplete response) throws IOException;
    }

    /** Sends SESSION_START and requires an exact WORKER_READY capability echo. */
    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate) throws IOException {
        return connect(channel, session, delegate, null);
    }

    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate,
                                                 MockMcpEndpoint mockMcp) throws IOException {
        return connect(channel, session, delegate, mockMcp, null);
    }

    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate, MockMcpEndpoint mockMcp,
                                                 MockWebEndpoint mockWeb) throws IOException {
        return connect(channel, session, delegate, mockMcp, mockWeb,
                session.mode() == BenchmarkRelayProtocol.AgentMode.PLAN ? new PlanRequestAudit(session.prompt()) : null);
    }

    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate, MockMcpEndpoint mockMcp,
                                                 MockWebEndpoint mockWeb, PlanRequestAudit planAudit) throws IOException {
        return connect(channel, session, delegate, mockMcp, mockWeb, planAudit, null);
    }

    /** Optional F2-only host association; existing callers retain their exact behavior. */
    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate, MockMcpEndpoint mockMcp,
                                                 MockWebEndpoint mockWeb, PlanRequestAudit planAudit,
                                                 F2CommandAudit f2CommandAudit) throws IOException {
        return connect(channel, session, delegate, mockMcp, mockWeb, planAudit, f2CommandAudit, null);
    }

    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate, MockMcpEndpoint mockMcp,
                                                 MockWebEndpoint mockWeb, PlanRequestAudit planAudit,
                                                 F2CommandAudit f2CommandAudit, F3ToolResultAudit f3ToolResultAudit) throws IOException {
        return connect(channel, session, delegate, mockMcp, mockWeb, planAudit, f2CommandAudit, f3ToolResultAudit,
                session.mode() == BenchmarkRelayProtocol.AgentMode.TEAM ? new TeamRequestAudit(session.prompt()) : null);
    }

    /** Docker shares this exact host audit with tracing; TEAM never borrows PLAN scopes. */
    public static BenchmarkProviderRelay connect(BenchmarkFramedChannel channel,
                                                 BenchmarkRelayProtocol.SessionStart session,
                                                 LlmClient delegate, MockMcpEndpoint mockMcp,
                                                 MockWebEndpoint mockWeb, PlanRequestAudit planAudit,
                                                 F2CommandAudit f2CommandAudit, F3ToolResultAudit f3ToolResultAudit,
                                                 TeamRequestAudit teamAudit) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(delegate, "delegate");
        if ((session.mode() == BenchmarkRelayProtocol.AgentMode.TEAM) != (teamAudit != null))
            throw new IOException("Team mode requires exact host audit binding");
        if (teamAudit != null && (planAudit != null || mockMcp != null || mockWeb != null
                || f2CommandAudit != null || f3ToolResultAudit != null))
            throw new IOException("Team audit requires an exclusive static tool channel");
        if (f2CommandAudit != null) {
            if (mockMcp != null || mockWeb != null || planAudit != null || f3ToolResultAudit != null)
                throw f2CommandAudit.fail("F2 audit must not share MCP, Web or Plan channels");
            f2CommandAudit.bind(session);
        }
        if ((session.toolProfile() == BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY) != (f3ToolResultAudit != null))
            throw new IOException("F3 profile requires its exact host raw-result audit");
        if (f3ToolResultAudit != null) {
            if (mockMcp == null || mockMcp instanceof ScriptedInteraction || mockWeb != null || planAudit != null || f2CommandAudit != null)
                throw f3ToolResultAudit.fail("F3 requires exclusive single-turn MCP and raw-result audit");
            f3ToolResultAudit.bind(session);
        }
        if ((session.mode() == BenchmarkRelayProtocol.AgentMode.PLAN) != (planAudit != null))
            throw new IOException("Plan mode requires exact host audit binding");
        if ((session.toolProfile() == BenchmarkRelayProtocol.ToolProfile.MOCK_WEB) != (mockWeb != null))
            throw new IOException("Web profile requires exactly one host-owned mock");
        if ((session.toolProfile() == BenchmarkRelayProtocol.ToolProfile.MOCK_MCP
                || session.toolProfile() == BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY) != (mockMcp != null)) {
            throw new IOException("MCP profile requires exactly one host-owned mock");
        }
        if (mockMcp != null && !session.mockServers().equals(mockMcp.serverNames()))
            throw new IOException("mock MCP server set differs from frozen session");
        if ((session.interactionMode() == BenchmarkRelayProtocol.InteractionMode.TWO_TURN_APPROVAL)
                != (mockMcp instanceof ScriptedInteraction))
            throw new IOException("scripted interaction must match the frozen session mode");
        ensureIdentity(session, delegate);
        BenchmarkRelayProtocol.Capabilities actual = capabilitiesOf(delegate);
        if (!actual.equals(session.capabilities())) {
            throw new IOException("relay session capabilities do not match provider client");
        }
        ensureDeadline(session);

        BenchmarkRelayProtocol.Validator validator =
                new BenchmarkRelayProtocol.Validator(BenchmarkRelayProtocol.EndpointRole.COORDINATOR);
        acceptSent(validator, session);
        channel.write(session);
        BenchmarkRelayProtocol.Frame frame = requireFrame(channel.read(), "worker ready");
        acceptReceived(validator, frame);
        if (!(frame instanceof BenchmarkRelayProtocol.WorkerReady ready)) {
            throw new IOException("relay protocol error: expected worker ready");
        }
        if (!session.capabilities().equals(ready.capabilities())) {
            throw new IOException("relay worker capabilities do not match session");
        }
        return new BenchmarkProviderRelay(channel, validator, session, delegate, mockMcp, mockWeb, planAudit,
                f2CommandAudit, f3ToolResultAudit, teamAudit);
    }

    public static BenchmarkRelayProtocol.Capabilities capabilitiesOf(LlmClient client) {
        return RelayWireConversions.capabilitiesOf(client);
    }

    /** Serves exactly one chat or terminal worker frame. There is no retry loop. */
    public ServeResult serveNext() throws IOException {
        ensureDeadline(session);
        BenchmarkRelayProtocol.Frame frame;
        try { frame = requireFrame(channel.read(), "worker request"); }
        catch (BenchmarkRelayProtocol.TeamEvidenceException error) {
            if (teamAudit != null) teamAudit.markFailed();
            throw error;
        }
        catch (BenchmarkRelayProtocol.F3EvidenceException error) {
            if (f3ToolResultAudit != null) throw f3ToolResultAudit.fail("F3 raw evidence frame could not be decoded");
            throw error;
        }
        try { acceptReceived(validator, frame); }
        catch (IOException error) {
            if (teamAudit != null) teamAudit.markFailed();
            if (f3ToolResultAudit != null && (frame instanceof BenchmarkRelayProtocol.F3ToolResult
                    || frame instanceof BenchmarkRelayProtocol.F3ToolResultAck))
                throw f3ToolResultAudit.fail("F3 raw evidence frame is out of order");
            throw error;
        }
        if (frame instanceof BenchmarkRelayProtocol.F3ToolResult result) {
            if (f3ToolResultAudit == null) throw new IOException("F3 raw result has no bound audit");
            f3ToolResultAudit.raw(result.result());
            var ack = new BenchmarkRelayProtocol.F3ToolResultAck(
                    responseHeader(BenchmarkRelayProtocol.FrameType.F3_TOOL_RESULT_ACK, frame.callId(), 1));
            acceptSent(validator, ack); channel.write(ack);
            return ServeResult.F3_TOOL_RESULT_SERVED;
        }
        if (frame instanceof BenchmarkRelayProtocol.PlanEvent event) {
            planAudit.accept(PlanObservationWire.decode(event.eventType(), event.event()));
            var ack = new BenchmarkRelayProtocol.PlanEventAck(
                    responseHeader(BenchmarkRelayProtocol.FrameType.PLAN_EVENT_ACK, frame.callId(), 1));
            acceptSent(validator, ack); channel.write(ack);
            return ServeResult.PLAN_EVENT_SERVED;
        }
        if (frame instanceof BenchmarkRelayProtocol.TeamEvent event) {
            teamAudit.accept(TeamObservationWire.decode(event.eventType(), event.event()));
            var ack = new BenchmarkRelayProtocol.TeamEventAck(
                    responseHeader(BenchmarkRelayProtocol.FrameType.TEAM_EVENT_ACK, frame.callId(), 1));
            acceptSent(validator, ack); channel.write(ack);
            return ServeResult.TEAM_EVENT_SERVED;
        }
        if (frame instanceof BenchmarkRelayProtocol.WorkerComplete complete) {
            if (teamAudit != null) teamAudit.assertComplete();
            if (f2CommandAudit != null) f2CommandAudit.complete(complete);
            if (f3ToolResultAudit != null) f3ToolResultAudit.complete(complete);
            if (mockWeb != null) validateToolEvidence(complete.toolExecutions());
            if (mockMcp instanceof ScriptedInteraction scripted) {
                validateToolEvidence(complete.toolExecutions());
                if (complete.toolExecutions().size() < firstTurnTools.size()
                        || !complete.toolExecutions().subList(0, firstTurnTools.size()).equals(firstTurnTools))
                    throw new IOException("first-turn tool evidence was rewritten");
                scripted.recordCompletedTools(complete.toolExecutions().subList(firstTurnTools.size(), complete.toolExecutions().size()));
                scripted.recordExchange(currentTurn, complete, null);
            }
            terminalFrame = frame;
            return ServeResult.WORKER_COMPLETE;
        }
        if (frame instanceof BenchmarkRelayProtocol.WorkerFailure failure) {
            if (f3ToolResultAudit != null && "F3_TOOL_RESULT_EVIDENCE_INVALID".equals(failure.errorType()))
                throw f3ToolResultAudit.fail("F3 Worker raw result observation failed");
            if (planAudit != null && "REQUEST_FINGERPRINT_UNPROVEN".equals(failure.errorType())) planAudit.markFailed();
            if (teamAudit != null && "REQUEST_FINGERPRINT_UNPROVEN".equals(failure.errorType())) teamAudit.markFailed();
            terminalFrame = frame;
            return ServeResult.WORKER_FAILURE;
        }
        if (frame instanceof BenchmarkRelayProtocol.TurnComplete request) {
            ScriptedInteraction scripted = (ScriptedInteraction) mockMcp;
            if (usedTokens >= session.agentLimits().tokenBudget() || chatRequests >= session.agentLimits().hardMaxIterations())
                throw new IOException("episode budget cannot be renewed at the turn boundary");
            validateToolEvidence(request.toolExecutions());
            firstTurnTools = request.toolExecutions();
            scripted.recordCompletedTools(firstTurnTools);
            String userMessage = scripted.nextUserMessage(request.answer());
            var continuation = new BenchmarkRelayProtocol.TurnContinue(
                    responseHeader(BenchmarkRelayProtocol.FrameType.TURN_CONTINUE, request.callId(), 1), 2, userMessage);
            acceptSent(validator, continuation);
            channel.write(continuation);
            scripted.recordExchange(currentTurn, request, continuation);
            currentTurn = 2;
            mcpPermits.clear(); // Unused first-turn approvals cannot cross a new user boundary.
            return ServeResult.INTERACTION_SERVED;
        }
        if (frame instanceof BenchmarkRelayProtocol.ApprovalRequest request) {
            int matched = -1;
            int first = request.turn() == 1 ? 0 : firstTurnTools.size();
            for (int i = first; i < requestedTools.size(); i++) {
                var call = requestedTools.get(i);
                if (!approvalOrdinals.contains(i) && call.name().equals(request.toolName())
                        && call.arguments().equals(request.argumentsJson())) { matched = i; break; }
            }
            if (matched < 0) throw new IOException("approval has no matching provider tool call");
            approvalOrdinals.add(matched);
            var decision = ((ScriptedInteraction) mockMcp).approve(request.toolName(), request.argumentsJson());
            if (decision.approved()) {
                JsonNode arguments;
                try { arguments = STRICT_JSON.readTree(request.argumentsJson()); }
                catch (IOException malformed) { arguments = null; }
                if (arguments == null || !arguments.isObject())
                    decision = new ScriptedInteraction.Decision(false, "approval arguments must be one strict JSON object");
                else mcpPermits.add(new McpPermit(currentTurn, request.toolName(), arguments));
            }
            var complete = new BenchmarkRelayProtocol.ApprovalComplete(
                    responseHeader(BenchmarkRelayProtocol.FrameType.APPROVAL_COMPLETE, request.callId(), 1),
                    request.turn(), request.toolName(), BenchmarkRelayProtocol.textSha256(request.argumentsJson()),
                    decision.approved(), decision.reason());
            acceptSent(validator, complete);
            channel.write(complete);
            ((ScriptedInteraction) mockMcp).recordExchange(currentTurn, request, complete);
            return ServeResult.INTERACTION_SERVED;
        }
        if (frame instanceof BenchmarkRelayProtocol.McpRequest request) {
            if (mockMcp == null || !session.mockServers().contains(request.server()) || ++mcpRequests > 4_096) {
                throw new IOException("MCP relay request denied");
            }
            if (mockMcp instanceof ScriptedInteraction && request.message().path("method").asText().equals("tools/call")) {
                JsonNode params = request.message().path("params");
                String name = "mcp__" + request.server() + "__" + params.path("name").asText();
                int permit = -1;
                for (int i = 0; i < mcpPermits.size(); i++) if (mcpPermits.get(i).turn() == currentTurn && mcpPermits.get(i).toolName().equals(name)
                        && mcpPermits.get(i).arguments().equals(params.path("arguments"))) { permit = i; break; }
                if (permit < 0) throw new IOException("MCP call has no matching one-use host HITL approval");
                mcpPermits.remove(permit);
            }
            int f3Ordinal = f3ToolResultAudit == null ? 0 : f3ToolResultAudit.beforeMcp(request);
            BenchmarkRelayProtocol.McpComplete complete;
            if (f3ToolResultAudit != null) {
                try {
                    JsonNode response = mockMcp.exchange(request.server(), request.message());
                    complete = new BenchmarkRelayProtocol.McpComplete(
                            responseHeader(BenchmarkRelayProtocol.FrameType.MCP_COMPLETE, request.callId(), 1), request.server(), response);
                    f3ToolResultAudit.mcp(request, complete, f3Ordinal);
                } catch (F3ToolResultAudit.Failure failure) {
                    throw failure;
                } catch (IOException | RuntimeException failure) {
                    // Normal invalid Candidate arguments are an MCP error response. An exception
                    // from this host-owned service/recording path is missing evidence, not a zero.
                    throw f3ToolResultAudit.fail("F3 host MCP exchange or observation failed");
                }
            } else {
                JsonNode response = mockMcp.exchange(request.server(), request.message());
                complete = new BenchmarkRelayProtocol.McpComplete(
                        responseHeader(BenchmarkRelayProtocol.FrameType.MCP_COMPLETE, request.callId(), 1), request.server(), response);
            }
            acceptSent(validator, complete);
            channel.write(complete);
            if (mockMcp instanceof ScriptedInteraction scripted) scripted.recordExchange(currentTurn, request, complete);
            return ServeResult.MCP_SERVED;
        }
        if (frame instanceof BenchmarkRelayProtocol.WebRequest request) {
            serveWeb(request);
            return ServeResult.WEB_SERVED;
        }
        if (!(frame instanceof BenchmarkRelayProtocol.ChatRequest request)) {
            throw new IOException("relay protocol error: unexpected worker frame");
        }
        serveChat(request);
        return ServeResult.CHAT_SERVED;
    }

    /** Returns the validated terminal worker frame after {@link #serveNext()} reports one. */
    public BenchmarkRelayProtocol.Frame terminalFrame() {
        return terminalFrame;
    }

    /** Stable host-side category for the last upstream failure; no provider message is retained. */
    public String providerFailureType() {
        return providerFailureType;
    }

    /** Host-observed budget stop, distinct from a normal answer on the last permitted call. */
    public boolean episodeBudgetExhausted() { return episodeBudgetExhausted; }

    private void serveChat(BenchmarkRelayProtocol.ChatRequest request) throws IOException {
        if (teamAudit != null) {
            serveTeamChat(request);
            return;
        }
        boolean hostBudgetFinalization = false;
        if ((mockMcp instanceof ScriptedInteraction || mockWeb != null || planAudit != null || f2CommandAudit != null || f3ToolResultAudit != null) && (usedTokens >= session.agentLimits().tokenBudget()
                || chatRequests >= session.agentLimits().hardMaxIterations())) {
            episodeBudgetExhausted = true;
            // One tool-free best-effort closing call for the whole episode, never a fresh turn budget.
            if (finalizationUsed || !request.tools().isEmpty()) {
                sendFailure(request.callId(), 1, "EPISODE_BUDGET_EXHAUSTED");
                return;
            }
            finalizationUsed = true;
            hostBudgetFinalization = true;
        }
        final List<LlmClient.Message> messages;
        final List<LlmClient.Tool> tools;
        try {
            messages = RelayWireConversions.fromWireMessages(request.messages());
            tools = RelayWireConversions.fromWireTools(request.tools());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("relay request rejected", error);
        }

        Sequence sequence = new Sequence();
        LlmClient.StreamListener listener = new LlmClient.StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                sendDelta(request.callId(), BenchmarkRelayProtocol.DeltaKind.REASONING, delta, sequence);
            }

            @Override
            public void onContentDelta(String delta) {
                sendDelta(request.callId(), BenchmarkRelayProtocol.DeltaKind.CONTENT, delta, sequence);
            }
        };

        try {
            if (planAudit != null) planAudit.begin(request.executionScope(), messages, tools);
            if (f2CommandAudit != null) f2CommandAudit.begin(request, hostBudgetFinalization);
            if (f3ToolResultAudit != null) f3ToolResultAudit.begin(request, hostBudgetFinalization);
            chatRequests++;
            LlmClient.ChatResponse response = delegate.chat(messages, tools, listener);
            if (planAudit != null) planAudit.response(response);
            BenchmarkRelayProtocol.ChatComplete complete = new BenchmarkRelayProtocol.ChatComplete(
                    responseHeader(BenchmarkRelayProtocol.FrameType.CHAT_COMPLETE,
                            request.callId(), sequence.next()),
                    RelayWireConversions.toWireResponse(response));
            if (f2CommandAudit != null) f2CommandAudit.response(complete.response());
            if (f3ToolResultAudit != null) f3ToolResultAudit.response(complete.response());
            if (mockMcp instanceof ScriptedInteraction || mockWeb != null || planAudit != null || f2CommandAudit != null || f3ToolResultAudit != null) {
                if (!response.usagePresent()) {
                    providerFailureType = "PROVIDER_USAGE_INCOMPLETE";
                    sendFailure(request.callId(), complete.eventSequence(), providerFailureType);
                    return;
                }
                usedTokens = Math.addExact(usedTokens, (long) response.inputTokens() + response.outputTokens());
                if (mockWeb != null) {
                    webBatchStart = requestedTools.size();
                    webFetchPermits.clear(); // An unused/denied prior tool must not shadow a later identical call.
                }
                requestedTools.addAll(complete.response().toolCalls());
                if (requestedTools.size() > BenchmarkRelayProtocol.MAX_TOOL_EXECUTIONS)
                    throw new IOException("scripted episode tool evidence limit exceeded");
            }
            acceptSent(validator, complete);
            if (mockMcp instanceof ScriptedInteraction scripted) {
                try { scripted.recordProviderTurn(currentTurn, request.messages(), request.tools(), complete.response()); }
                catch (IOException | RuntimeException auditFailure) {
                    providerFailureType = "FROZEN_MOCK_FAILURE";
                    throw new ScriptedAuditFailure(auditFailure);
                }
            }
            if (mockWeb != null) {
                try { mockWeb.recordProviderTurn(request.messages(), complete.response()); }
                catch (IOException | RuntimeException auditFailure) {
                    providerFailureType = "FROZEN_MOCK_FAILURE";
                    throw new MockWebFailure(auditFailure);
                }
            }
            channel.write(complete);
        } catch (MockWebFailure | ScriptedAuditFailure | F2CommandAudit.Failure | F3ToolResultAudit.Failure error) {
            throw error;
        } catch (UncheckedIOException error) {
            throw error.getCause();
        } catch (IOException error) {
            String failureType = BenchmarkProviderFailureClassifier.classify(error);
            providerFailureType = failureType;
            sendFailure(request.callId(), sequence.next(), failureType);
        } catch (IllegalArgumentException | NullPointerException error) {
            providerFailureType = "LLM_API_ERROR";
            sendFailure(request.callId(), sequence.next(), "INVALID_PROVIDER_RESPONSE");
        } finally {
            if (planAudit != null) planAudit.end();
            if (f2CommandAudit != null) f2CommandAudit.endRequest();
            if (f3ToolResultAudit != null) f3ToolResultAudit.end();
        }
    }

    /** TEAM requests are registered before dispatch, including host-budget denials and provider failures. */
    private void serveTeamChat(BenchmarkRelayProtocol.ChatRequest request) throws IOException {
        Sequence sequence = new Sequence();
        boolean delivered = false;
        boolean completing = false;
        try {
            List<LlmClient.Message> messages = RelayWireConversions.fromWireMessages(request.messages());
            List<LlmClient.Tool> tools = RelayWireConversions.fromWireTools(request.tools());
            teamAudit.begin(request.executionScope(), messages, tools);
            if (usedTokens >= session.agentLimits().tokenBudget()
                    || chatRequests >= session.agentLimits().hardMaxIterations()) {
                episodeBudgetExhausted = true;
                // Planner/reviewer already have no tools: emptiness alone is never a closing permit.
                if (finalizationUsed || !tools.isEmpty() || !teamAudit.isBudgetFinalizationRequest()) {
                    teamAudit.providerFailure("EPISODE_BUDGET_EXHAUSTED");
                    sendFailure(request.callId(), sequence.next(), "EPISODE_BUDGET_EXHAUSTED");
                    return;
                }
                finalizationUsed = true;
            }
            LlmClient.StreamListener listener = new LlmClient.StreamListener() {
                @Override public void onReasoningDelta(String delta) {
                    sendDelta(request.callId(), BenchmarkRelayProtocol.DeltaKind.REASONING, delta, sequence);
                }
                @Override public void onContentDelta(String delta) {
                    sendDelta(request.callId(), BenchmarkRelayProtocol.DeltaKind.CONTENT, delta, sequence);
                }
            };
            teamAudit.dispatched();
            chatRequests++;
            LlmClient.ChatResponse response = delegate.chat(messages, tools, listener);
            teamAudit.returnedResponse(response);
            if (!response.usagePresent()) {
                providerFailureType = "PROVIDER_USAGE_INCOMPLETE";
                teamAudit.providerFailure(providerFailureType);
                sendFailure(request.callId(), sequence.next(), providerFailureType);
                return;
            }
            usedTokens = Math.addExact(usedTokens, (long) response.inputTokens() + response.outputTokens());
            var complete = new BenchmarkRelayProtocol.ChatComplete(
                    responseHeader(BenchmarkRelayProtocol.FrameType.CHAT_COMPLETE, request.callId(), sequence.next()),
                    RelayWireConversions.toWireResponse(response));
            completing = true;
            acceptSent(validator, complete);
            channel.write(complete);
            delivered = true;
            // Successful pipe write is delivery evidence, not proof of semantic consumption by the model.
            teamAudit.response(response);
        } catch (TeamRequestAudit.Failure error) {
            throw error;
        } catch (UncheckedIOException error) {
            teamAudit.providerFailure("RELAY_DELIVERY_FAILED");
            teamAudit.markFailed();
            throw error.getCause();
        } catch (IOException error) {
            if (delivered || completing) {
                teamAudit.providerFailure("RELAY_DELIVERY_FAILED");
                teamAudit.markFailed();
                throw error;
            }
            providerFailureType = BenchmarkProviderFailureClassifier.classify(error);
            teamAudit.providerFailure(providerFailureType);
            sendFailure(request.callId(), sequence.next(), providerFailureType);
        } catch (IllegalArgumentException | NullPointerException | ArithmeticException error) {
            teamAudit.providerFailure("INVALID_PROVIDER_RESPONSE");
            providerFailureType = "LLM_API_ERROR";
            sendFailure(request.callId(), sequence.next(), "INVALID_PROVIDER_RESPONSE");
        } finally {
            teamAudit.end();
        }
    }

    private void validateToolEvidence(List<BenchmarkRelayProtocol.WireToolExecution> executions) throws IOException {
        if (providerFailureType != null || requestedTools.size() != executions.size())
            throw new IOException("incomplete scripted episode evidence");
        for (int i = 0; i < requestedTools.size(); i++) {
            var requested = requestedTools.get(i);
            var executed = executions.get(i);
            if (!requested.id().equals(executed.callId()) || !requested.name().equals(executed.toolName())
                    || !requested.arguments().equals(executed.argumentsJson()))
                throw new IOException("scripted tool evidence differs from provider request");
        }
    }

    /** Host failures are typed so Docker never converts a broken fixture into a Candidate zero. */
    public static final class MockWebFailure extends IOException {
        private MockWebFailure(Throwable cause) { super("host Web fixture failed", cause); }
    }

    public static final class ScriptedAuditFailure extends IOException {
        private ScriptedAuditFailure(Throwable cause) { super("host scripted provider audit failed", cause); }
    }

    private void serveWeb(BenchmarkRelayProtocol.WebRequest request) throws IOException {
        if (mockWeb == null) throw new IOException("Web relay unavailable");
        int ordinal = claimWebTool(request);
        BenchmarkRelayProtocol.WebComplete complete;
        try {
            List<BenchmarkRelayProtocol.WebSearchResult> results = List.of();
            BenchmarkRelayProtocol.WebPage page = null;
            String denial = null;
            switch (request.operation()) {
                case SEARCH -> results = mockWeb.search(request.input(), request.topK()).stream()
                        .map(r -> new BenchmarkRelayProtocol.WebSearchResult(r.position(), r.title(), r.url(), r.snippet(), r.source())).toList();
                case CHECK_URL -> {
                    String reason = mockWeb.checkUrl(request.input());
                    denial = reason == null ? "" : reason;
                    if (denial.isEmpty()) webFetchPermits.add(new WebFetchPermit(ordinal, request.input().trim()));
                }
                case FETCH -> {
                    var raw = mockWeb.fetch(request.input());
                    page = new BenchmarkRelayProtocol.WebPage(raw.url(), raw.body(), raw.contentType(), raw.charset(), raw.truncated());
                }
            }
            complete = new BenchmarkRelayProtocol.WebComplete(
                    responseHeader(BenchmarkRelayProtocol.FrameType.WEB_COMPLETE, request.callId(), 1), request.operation(), results, page, denial);
            acceptSent(validator, complete);
            mockWeb.recordExchange(ordinal, request, complete);
        } catch (IOException | RuntimeException error) {
            providerFailureType = "FROZEN_MOCK_FAILURE";
            throw new MockWebFailure(error);
        }
        channel.write(complete); // A Worker closing its pipe is not a host fixture failure.
    }

    /** Match effective product arguments without trusting arbitrary Worker-originated backend calls. */
    private int claimWebTool(BenchmarkRelayProtocol.WebRequest request) throws IOException {
        if (request.operation() == BenchmarkRelayProtocol.WebOperation.FETCH) {
            for (int i = 0; i < webFetchPermits.size(); i++) {
                var permit = webFetchPermits.get(i);
                if (permit.url().equals(request.input())) {
                    webFetchPermits.remove(i); return permit.ordinal();
                }
            }
            throw new IOException("Web fetch has no unused successful URL check");
        }
        String tool = request.operation() == BenchmarkRelayProtocol.WebOperation.SEARCH ? "web_search" : "web_fetch";
        for (int i = webBatchStart; i < requestedTools.size(); i++) {
            var call = requestedTools.get(i);
            if (webToolOrdinals.contains(i + 1) || !call.name().equals(tool)) continue;
            JsonNode args;
            try { args = STRICT_JSON.readTree(call.arguments()); }
            catch (IOException malformedCandidateArgs) { continue; }
            if (args == null || !args.isObject()) continue;
            String value = args.path(tool.equals("web_search") ? "query" : "url").asText(null);
            if (value == null) continue;
            if (tool.equals("web_search")) {
                value = value.trim();
                int topK = 5;
                try { topK = Integer.parseInt(args.path("top_k").asText().trim()); }
                catch (NumberFormatException ignored) { }
                if (topK != request.topK()) continue;
            }
            if (value.equals(request.input())) { webToolOrdinals.add(i + 1); return i + 1; }
        }
        throw new IOException("Web request differs from the provider tool call");
    }

    private record WebFetchPermit(int ordinal, String url) { }

    private void sendDelta(String callId, BenchmarkRelayProtocol.DeltaKind kind,
                           String delta, Sequence sequence) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        try {
            BenchmarkRelayProtocol.StreamDelta frame = new BenchmarkRelayProtocol.StreamDelta(
                    responseHeader(BenchmarkRelayProtocol.FrameType.STREAM_DELTA,
                            callId, sequence.next()), kind, delta);
            if (f3ToolResultAudit != null) f3ToolResultAudit.delta(frame);
            acceptSent(validator, frame);
            channel.write(frame);
        } catch (IOException | IllegalArgumentException error) {
            IOException io = error instanceof IOException existing
                    ? existing : new IOException("relay stream delta rejected", error);
            throw new UncheckedIOException(io);
        }
    }

    private void sendFailure(String callId, long sequence, String errorType) throws IOException {
        BenchmarkRelayProtocol.ChatFailure failure = new BenchmarkRelayProtocol.ChatFailure(
                responseHeader(BenchmarkRelayProtocol.FrameType.CHAT_FAILURE, callId, sequence),
                errorType, "provider request failed", false);
        acceptSent(validator, failure);
        channel.write(failure);
    }

    private static BenchmarkRelayProtocol.Header responseHeader(BenchmarkRelayProtocol.FrameType type,
                                                                 String callId, long sequence) {
        return new BenchmarkRelayProtocol.Header(
                BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER, type, callId, sequence);
    }

    private static void ensureIdentity(BenchmarkRelayProtocol.SessionStart session,
                                       LlmClient delegate) throws IOException {
        if (!session.provider().equals(delegate.getProviderName())
                || !session.model().equals(delegate.getModelName())) {
            throw new IOException("relay provider identity does not match session");
        }
    }

    private static void ensureDeadline(BenchmarkRelayProtocol.SessionStart session) throws IOException {
        if (System.currentTimeMillis() >= session.deadlineEpochMillis()) {
            throw new IOException("relay session deadline exceeded");
        }
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

    private static final class Sequence {
        private long value = 1;

        private long next() {
            return value++;
        }
    }

    private record McpPermit(int turn, String toolName, JsonNode arguments) { }
}
