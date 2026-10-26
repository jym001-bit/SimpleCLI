package com.paicli.eval.benchmark;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolOutput;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.protocol.McpToolDescriptor;

import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Local development-suite tool surface; both exposure and execution are fail-closed. */
final class BenchmarkToolRegistry extends HitlToolRegistry {
    private final BenchmarkToolProfile profile;
    private Consumer<ToolExecutionResult> executionObserver = ignored -> { };
    private Function<String, ToolOutput> frozenCodeSearch;
    private Set<String> mockTools = Set.of();
    private boolean mockCatalogBound;
    private boolean mockWebBound;

    BenchmarkToolRegistry() {
        this(BenchmarkToolProfile.FILE_ONLY);
    }

    BenchmarkToolRegistry(BenchmarkToolProfile profile) {
        this(profile, new HitlHandler() {
            @Override public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.reject("no approval channel"); }
            @Override public boolean isEnabled() { return false; }
            @Override public void setEnabled(boolean enabled) { throw new UnsupportedOperationException("frozen non-HITL profile"); }
        });
    }

    BenchmarkToolRegistry(BenchmarkToolProfile profile, HitlHandler handler) {
        super(java.util.Objects.requireNonNull(handler));
        this.profile = profile == null ? BenchmarkToolProfile.FILE_ONLY : profile;
    }

    BenchmarkToolProfile profile() {
        return profile;
    }

    String promptPolicy() {
        String tools = allowedTools().stream().sorted().toList().toString();
        return "## Benchmark Tool Profile\n\n"
                + "Frozen profile: " + profile.name() + ". Only these tools are available: " + tools + ". "
                + "Any generic PaiCLI prompt reference to a tool outside this list is unavailable "
                + "for this benchmark episode and must not be called.";
    }

    void setExecutionObserver(Consumer<ToolExecutionResult> observer) {
        executionObserver = observer == null ? ignored -> { } : observer;
    }

    void setFrozenCodeSearch(Function<String, ToolOutput> search) {
        frozenCodeSearch = search;
    }

    /** Only the one catalog discovered through the host relay becomes executable. */
    void bindMockMcp(McpClient client) throws IOException {
        bindMockMcp(java.util.Map.of("benchmark", client));
    }

    /** Bind the complete host-declared set atomically; never expose a partially loaded catalog. */
    void bindMockMcp(java.util.Map<String, McpClient> clients) throws IOException {
        if (profile != BenchmarkToolProfile.MOCK_MCP && profile != BenchmarkToolProfile.MOCK_MCP_FILE_ONLY || mockCatalogBound) {
            throw new IOException("mock MCP catalog cannot be rebound");
        }
        if (clients.isEmpty() || clients.size() > com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.MAX_MCP_SERVERS)
            throw new IOException("invalid mock MCP server count");
        if (profile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY && !clients.keySet().equals(Set.of("support")))
            throw new IOException("F3 requires exactly its frozen support MCP server");
        List<McpToolDescriptor> catalog = new java.util.ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (var entry : clients.entrySet()) {
            String server = entry.getKey();
            try { com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.validateMockServer(server); }
            catch (IllegalArgumentException error) { throw new IOException("invalid mock MCP server", error); }
            var serverTools = entry.getValue().listTools();
            if (serverTools.isEmpty() || serverTools.size() > 32) throw new IOException("invalid mock MCP catalog size");
            for (McpToolDescriptor tool : serverTools) {
                if (!server.equals(tool.serverName())
                        || !tool.name().matches("[a-z][a-z0-9_]{0,63}")
                        || !McpToolDescriptor.namespaced(server, tool.name()).equals(tool.namespacedName())
                        || !names.add(tool.namespacedName()))
                    throw new IOException("invalid mock MCP catalog entry");
            }
            catalog.addAll(serverTools);
        }
        if (catalog.size() > com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.MAX_TOOLS)
            throw new IOException("combined mock MCP catalog too large");
        for (McpToolDescriptor tool : catalog) {
            McpClient client = clients.get(tool.serverName());
            registerMcpToolOutput(tool, args -> {
                try { return client.callToolOutput(tool.name(), args); }
                catch (IOException error) { return ToolOutput.failure("benchmark MCP request failed"); }
            });
        }
        mockTools = Set.copyOf(names);
        mockCatalogBound = true;
    }

    private Set<String> allowedTools() {
        if (profile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY) {
            if (!mockCatalogBound) return Set.of();
            var names = new java.util.HashSet<>(profile.allowedTools()); names.addAll(mockTools); return Set.copyOf(names);
        }
        if (profile == BenchmarkToolProfile.MOCK_WEB && !mockWebBound) return Set.of();
        return profile == BenchmarkToolProfile.MOCK_MCP ? mockTools : profile.allowedTools();
    }

    /** Offline native-control path; Docker requires a separate host relay before it can use this profile. */
    synchronized void bindMockWeb(com.paicli.web.SearchProvider search, com.paicli.web.WebFetcher fetcher,
                                  com.paicli.web.NetworkPolicy policy) throws IOException {
        if (profile != BenchmarkToolProfile.MOCK_WEB || mockWebBound)
            throw new IOException("mock Web dependencies cannot be rebound");
        installWebDependencies(search, fetcher, policy);
        mockWebBound = true;
    }

    @Override
    public void onPolicyToolResults(List<ToolExecutionResult> results) {
        for (ToolExecutionResult result : results) {
            executionObserver.accept(result);
        }
    }

    @Override
    public List<LlmClient.Tool> getToolDefinitions() {
        return super.getToolDefinitions().stream()
                .filter(tool -> allowedTools().contains(tool.name()))
                .toList();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (!allowedTools().contains(name)) {
            return ToolOutput.failure("benchmark tool policy denied: " + name);
        }
        if ("search_code".equals(name)) {
            if (frozenCodeSearch == null) {
                return ToolOutput.failure("benchmark frozen semantic index is unavailable");
            }
            try {
                ToolOutput output = frozenCodeSearch.apply(argumentsJson);
                return output == null
                        ? ToolOutput.failure("benchmark frozen semantic index returned no result")
                        : output;
            } catch (RuntimeException error) {
                return ToolOutput.failure("benchmark frozen semantic index failed");
            }
        }
        return super.executeToolOutput(name, argumentsJson);
    }
}
