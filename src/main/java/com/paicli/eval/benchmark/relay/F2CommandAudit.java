package com.paicli.eval.benchmark.relay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single-use host association for a bound F2 episode. Records actual provider
 * tool requests and the original terminal frame, not reconstructed Candidate files.
 * Command callbacks remain process-local diagnostics, not independent OS audit.
 */
public final class F2CommandAudit {
    private final String prompt;
    private final List<BenchmarkRelayProtocol.WireToolCall> requestedTools = new ArrayList<>();
    private boolean bound;
    private boolean activeRequest;
    private boolean responseSeen;
    private boolean failed;
    private int budgetFinalizationToolsFrom = -1;
    private BenchmarkRelayProtocol.WorkerComplete terminal;

    public F2CommandAudit(String frozenPrompt) {
        prompt = Objects.requireNonNull(frozenPrompt, "frozen F2 prompt");
        if (prompt.isBlank() || prompt.length() > BenchmarkRelayProtocol.MAX_TEXT_CHARS)
            throw new IllegalArgumentException("invalid frozen F2 prompt");
    }

    /** Called by the host relay before transmitting the session or contacting the provider. */
    synchronized void bind(BenchmarkRelayProtocol.SessionStart session) throws Failure {
        require(!failed && !bound && session != null, "F2 audit cannot be rebound");
        require(session.mode() == BenchmarkRelayProtocol.AgentMode.REACT
                && session.toolProfile() == BenchmarkRelayProtocol.ToolProfile.LOCAL_COMMAND
                && session.interactionMode() == BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN
                && session.mockServers().isEmpty() && prompt.equals(session.prompt()), "F2 session binding mismatch");
        bound = true;
    }

    synchronized void begin(BenchmarkRelayProtocol.ChatRequest request) throws Failure {
        begin(request, false);
    }

    /** The relay alone identifies its one admitted, tool-free budget closing request. */
    synchronized void begin(BenchmarkRelayProtocol.ChatRequest request, boolean hostBudgetFinalization) throws Failure {
        require(!failed && bound && !activeRequest && terminal == null && request != null,
                "F2 provider request lifecycle mismatch");
        require(budgetFinalizationToolsFrom < 0, "F2 provider request follows budget finalization");
        var firstUser = request.messages().stream().filter(m -> "user".equals(m.role())).findFirst();
        require(request.executionScope() == null && firstUser.isPresent()
                && prompt.equals(firstUser.get().content()) && firstUser.get().contentParts().isEmpty(),
                "F2 provider input differs from frozen prompt");
        if (hostBudgetFinalization) {
            require(request.tools().isEmpty(), "F2 budget finalization exposes tools");
            budgetFinalizationToolsFrom = requestedTools.size();
        }
        activeRequest = true;
        responseSeen = false;
    }

    synchronized void response(BenchmarkRelayProtocol.WireChatResponse response) throws Failure {
        require(!failed && bound && activeRequest && !responseSeen && terminal == null && response != null,
                "F2 provider response lifecycle mismatch");
        var calls = response.toolCalls();
        require(calls.size() <= BenchmarkRelayProtocol.MAX_TOOL_EXECUTIONS - requestedTools.size(),
                "F2 provider tool observation limit exceeded");
        requestedTools.addAll(calls);
        responseSeen = true;
    }

    /** A provider exception may end a request without a response; that is not an audit defect. */
    synchronized void endRequest() {
        activeRequest = false;
        responseSeen = false;
    }

    public synchronized void complete(BenchmarkRelayProtocol.WorkerComplete complete) throws Failure {
        require(!failed && bound && !activeRequest && terminal == null && complete != null,
                "F2 terminal lifecycle mismatch");
        var executions = complete.toolExecutions();
        int requiredTools = budgetFinalizationToolsFrom < 0 ? requestedTools.size() : budgetFinalizationToolsFrom;
        // ReAct ignores any tools returned by its tool-free budget closing call. Preserve those
        // actual provider requests, but do not fabricate their execution or erase prior obligations.
        // The host still classifies this episode as a budget failure, never a scoring success.
        require(executions.size() >= requiredTools && executions.size() <= requestedTools.size(),
                "F2 terminal omitted or invented provider tools");
        for (int i = 0; i < executions.size(); i++) {
            var requested = requestedTools.get(i);
            var actual = executions.get(i);
            require(requested.id().equals(actual.callId()) && requested.name().equals(actual.toolName())
                    && requested.arguments().equals(actual.argumentsJson()), "F2 terminal tool differs from provider request");
        }
        // Preserve object identity for the owning Session; command pairing is independently replayed later.
        terminal = complete;
    }

    public synchronized List<BenchmarkRelayProtocol.WireToolCall> requestedTools() { return List.copyOf(requestedTools); }
    public synchronized BenchmarkRelayProtocol.WorkerComplete terminal() { return terminal; }
    public synchronized boolean failed() { return failed; }

    /** No terminal requirement: provider/budget/process failures are classified by the host first. */
    public synchronized void requireHealthy() throws Failure {
        require(!failed, "F2 host audit is invalid");
    }

    synchronized Failure fail(String message) {
        failed = true;
        return new Failure(message);
    }

    private void require(boolean condition, String message) throws Failure {
        if (!condition) throw fail(message);
    }

    /** Host binding/audit defects must not be reclassified as provider or Candidate failures. */
    public static final class Failure extends IOException {
        private Failure(String message) { super(message); }
    }
}
