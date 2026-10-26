package com.paicli.eval.benchmark;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.eval.benchmark.relay.BenchmarkFramedChannel;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.RelayLlmClient;
import com.paicli.eval.benchmark.relay.RelayMcpTransport;
import com.paicli.eval.benchmark.relay.RelayHitlHandler;
import com.paicli.mcp.McpClient;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.prompt.PromptAssembler;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Networkless Candidate Worker entry point. Stdout is reserved exclusively for relay frames. */
public final class BenchmarkRelayWorkerMain {
    static final Path CONTAINER_WORKSPACE = Path.of("/workspace");

    private BenchmarkRelayWorkerMain() {
    }

    public static void main(String[] args) {
        PrintStream protocolOut = System.out;
        System.setOut(silentPrintStream());
        try {
            run(System.in, protocolOut, CONTAINER_WORKSPACE);
        } catch (Exception error) {
            System.err.println("benchmark relay worker terminated before a safe terminal frame");
        }
    }

    static void run(InputStream input, OutputStream output, Path workspace) throws IOException {
        BenchmarkFramedChannel channel = new BenchmarkFramedChannel(input, output);
        RelayLlmClient relay = RelayLlmClient.accept(channel);
        try (FrozenRuntime ignored = configureFrozenRuntime(relay.session())) {
            Path realWorkspace = canonicalWorkspace(workspace);
            BenchmarkToolProfile profile = BenchmarkToolProfile.valueOf(relay.session().toolProfile().name());
            boolean scripted = relay.session().interactionMode() == BenchmarkRelayProtocol.InteractionMode.TWO_TURN_APPROVAL;
            BenchmarkToolRegistry tools = scripted
                    ? new BenchmarkToolRegistry(profile, new RelayHitlHandler(relay)) : new BenchmarkToolRegistry(profile);
            tools.setProjectPath(realWorkspace.toString());
            tools.setSanitizeCommandEnvironment(true);
            ToolEvidenceCollector toolEvidence = new ToolEvidenceCollector();
            tools.setExecutionObserver(toolEvidence::record);
            if (profile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY) {
                tools.setExecutionObserver(result -> {
                    try { relay.observeF3ToolResult(result); toolEvidence.record(result); }
                    catch (RuntimeException error) { relay.markF3ToolObservationFailed(); throw error; }
                });
            }
            CommandEvidenceCollector commandEvidence = new CommandEvidenceCollector();
            if (profile == BenchmarkToolProfile.LOCAL_COMMAND)
                tools.setCommandExecutionObserver(commandEvidence::record);

            var mocks = new java.util.LinkedHashMap<String, McpClient>();
            try {
                for (String server : relay.session().mockServers()) {
                    McpClient client = new McpClient(server, new RelayMcpTransport(relay, server));
                    mocks.put(server, client);
                    client.initialize();
                }
                if (!mocks.isEmpty()) tools.bindMockMcp(mocks);
                if (profile == BenchmarkToolProfile.MOCK_WEB) {
                    var web = new com.paicli.eval.benchmark.relay.RelayWebDependencies(relay);
                    tools.bindMockWeb(web.searchProvider(), web.fetcher(), web.networkPolicy());
                }
                if (scripted) executeScripted(relay, tools, toolEvidence, ignored);
                else {
                    String answer = executeMode(relay.session().mode(), relay, tools, relay.session().prompt());
                    relay.complete(answer, toolEvidence.snapshot(), commandEvidence.snapshot(),
                            tools.getCommandObservationFailures());
                }
            } finally {
                mocks.values().forEach(McpClient::close);
            }
        } catch (Exception error) {
            try {
                relay.fail("CANDIDATE_WORKER_ERROR", "candidate worker execution failed");
            } catch (IOException | RuntimeException ignored) {
                throw error instanceof IOException io
                        ? io
                        : new IOException("candidate worker failed before terminal frame", error);
            }
        }
    }

    private static void executeScripted(RelayLlmClient relay, BenchmarkToolRegistry tools,
                                        ToolEvidenceCollector evidence, FrozenRuntime runtime) throws IOException {
        Agent agent = new Agent(relay, tools);
        agent.setRenderer(new SilentRenderer());
        agent.setReturnFinalResponseWhenStreamed(true);
        agent.setExternalContextSupplier(tools::promptPolicy);
        String prompt = relay.session().prompt();
        String proposal = agent.runExplicitTask(prompt, prompt);
        if (relay.remainingTokens() == 0 || relay.remainingCalls() == 0) {
            relay.fail("EPISODE_BUDGET_EXHAUSTED", "episode budget exhausted before second turn");
            return;
        }
        String next = relay.continueTurn(proposal, evidence.snapshot());
        runtime.set("paicli.react.token.budget", Long.toString(relay.remainingTokens()));
        runtime.set("paicli.react.hard.max.iterations", Integer.toString(relay.remainingCalls()));
        // Reuse the actual product Agent and its conversationHistory, not a reconstructed transcript.
        String answer = agent.runExplicitTask(next, next);
        relay.complete(answer, evidence.snapshot());
    }

    private static String executeMode(BenchmarkRelayProtocol.AgentMode mode,
                                      RelayLlmClient relay,
                                      BenchmarkToolRegistry tools,
                                      String prompt) {
        return switch (mode) {
            case REACT -> {
                Agent agent = new Agent(relay, tools);
                agent.setRenderer(new SilentRenderer());
                agent.setReturnFinalResponseWhenStreamed(true);
                agent.setExternalContextSupplier(tools::promptPolicy);
                yield agent.runExplicitTask(prompt, prompt);
            }
            case PLAN -> {
                PlanExecuteAgent agent = new PlanExecuteAgent(
                        relay,
                        tools,
                        null,
                        (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                        silentPrintStream());
                agent.setExternalContextSupplier(tools::promptPolicy);
                agent.setExecutionObserver(relay::observePlan);
                yield agent.runExplicitTask(prompt, prompt);
            }
            case TEAM -> {
                AgentOrchestrator agent = new AgentOrchestrator(
                        relay, tools, new MemoryManager(relay), silentPrintStream());
                agent.setExternalContextSupplier(tools::promptPolicy);
                relay.setTeamObservationFailureSupplier(agent::getExecutionObservationFailures);
                agent.setExecutionObserver(relay::observeTeam);
                try {
                    yield agent.runExplicitTask(prompt, prompt);
                } finally {
                    relay.verifyTeamObservationFailures(agent.getExecutionObservationFailures());
                }
            }
        };
    }

    private static FrozenRuntime configureFrozenRuntime(BenchmarkRelayProtocol.SessionStart session) {
        FrozenRuntime runtime = new FrozenRuntime(List.of(
                PromptAssembler.RUNTIME_DATE_PROPERTY,
                PromptAssembler.RUNTIME_ZONE_PROPERTY,
                "paicli.react.token.budget",
                "paicli.react.hard.max.iterations",
                "paicli.react.stagnation.window",
                "paicli.compaction.session-memory.enabled"));
        runtime.set(PromptAssembler.RUNTIME_DATE_PROPERTY, session.runtimeDate());
        runtime.set(PromptAssembler.RUNTIME_ZONE_PROPERTY, session.runtimeZone());
        runtime.set("paicli.react.token.budget", Integer.toString(session.agentLimits().tokenBudget()));
        runtime.set("paicli.react.hard.max.iterations",
                Integer.toString(session.agentLimits().hardMaxIterations()));
        runtime.set("paicli.react.stagnation.window",
                Integer.toString(session.agentLimits().stagnationWindow()));
        runtime.set("paicli.compaction.session-memory.enabled", "false");
        return runtime;
    }

    private static Path canonicalWorkspace(Path workspace) throws IOException {
        if (workspace == null || !workspace.isAbsolute() || Files.isSymbolicLink(workspace)
                || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("candidate workspace is unavailable");
        }
        return workspace.toRealPath();
    }

    private static PrintStream silentPrintStream() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static final class CommandEvidenceCollector {
        private final List<com.paicli.tool.CommandExecutionObserver.Event> observations = new ArrayList<>();

        private synchronized void record(com.paicli.tool.CommandExecutionObserver.Event event) {
            if (observations.size() >= BenchmarkRelayProtocol.MAX_COMMAND_OBSERVATIONS)
                throw new IllegalStateException("command observation evidence limit exceeded");
            observations.add(java.util.Objects.requireNonNull(event));
        }

        private synchronized List<com.paicli.tool.CommandExecutionObserver.Event> snapshot() {
            return List.copyOf(observations);
        }
    }

    private static final class ToolEvidenceCollector {
        private final List<BenchmarkRelayProtocol.WireToolExecution> executions = new ArrayList<>();

        private synchronized void record(com.paicli.tool.ToolRegistry.ToolExecutionResult result) {
            if (executions.size() >= BenchmarkRelayProtocol.MAX_TOOL_EXECUTIONS) {
                throw new IllegalStateException("benchmark tool execution evidence limit exceeded");
            }
            String fullResult = result.result() == null ? "" : result.result();
            String preview = fullResult.length() <= 16_384
                    ? fullResult
                    : fullResult.substring(0, 16_384);
            executions.add(new BenchmarkRelayProtocol.WireToolExecution(
                    executions.size() + 1,
                    result.id(),
                    result.name(),
                    result.argumentsJson(),
                    preview,
                    sha256(fullResult),
                    fullResult.length(),
                    result.elapsedMillis(),
                    result.timedOut(),
                    result.successful()));
        }

        private synchronized List<BenchmarkRelayProtocol.WireToolExecution> snapshot() {
            return List.copyOf(executions);
        }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }

    private static final class SilentRenderer implements Renderer {
        private final PrintStream sink = silentPrintStream();

        @Override public void start() { }
        @Override public void close() { sink.close(); }
        @Override public PrintStream stream() { return sink; }
        @Override public boolean rendersReasoning() { return false; }
        @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) { }
        @Override public void appendDiff(String filePath, String before, String after) { }
        @Override public void updateStatus(StatusInfo status) { }
        @Override public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("benchmark worker has no interactive approval channel");
        }
        @Override public int openPalette(String title, List<String> items) { return -1; }
    }

    private static final class FrozenRuntime implements AutoCloseable {
        private final java.util.Map<String, String> previous = new java.util.LinkedHashMap<>();

        private FrozenRuntime(List<String> keys) {
            for (String key : keys) {
                previous.put(key, System.getProperty(key));
            }
        }

        private void set(String key, String value) {
            System.setProperty(key, value);
        }

        @Override
        public void close() {
            for (var entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
