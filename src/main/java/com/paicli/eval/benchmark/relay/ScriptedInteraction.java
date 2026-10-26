package com.paicli.eval.benchmark.relay;

import java.io.IOException;
import java.util.List;

/** Host-only two-turn episode driver. No instance or private definition enters the Candidate. */
public interface ScriptedInteraction extends BenchmarkProviderRelay.MockMcpEndpoint {
    /** Host observation of the actual provider input/response, not Candidate-supplied audit JSON. */
    default void recordProviderTurn(int turn, List<BenchmarkRelayProtocol.WireMessage> messages,
                                    List<BenchmarkRelayProtocol.WireTool> tools,
                                    BenchmarkRelayProtocol.WireChatResponse response) throws IOException { }

    String nextUserMessage(String firstAnswer) throws IOException;

    Decision approve(String toolName, String argumentsJson) throws IOException;

    /** Newly completed native calls, after the relay matched every id/name/argument to upstream calls. */
    void recordCompletedTools(List<BenchmarkRelayProtocol.WireToolExecution> tools) throws IOException;

    /** Private host audit of the ordered MCP, approval, and user-boundary frames (not LLM payloads). */
    void recordExchange(int turn, BenchmarkRelayProtocol.Frame request, BenchmarkRelayProtocol.Frame response) throws IOException;

    record Decision(boolean approved, String reason) { }
}
