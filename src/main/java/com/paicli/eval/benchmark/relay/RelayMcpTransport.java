package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/** Uses PaiCLI's real JSON-RPC/MCP client while the deterministic service stays on the host. */
public final class RelayMcpTransport implements McpTransport {
    private final RelayLlmClient relay;
    private final String server;
    private Consumer<JsonNode> receiver;
    private boolean closed;

    public RelayMcpTransport(RelayLlmClient relay) {
        this(relay, "benchmark");
    }

    public RelayMcpTransport(RelayLlmClient relay, String server) {
        this.relay = Objects.requireNonNull(relay, "relay");
        BenchmarkRelayProtocol.validateMockServer(server);
        if (!relay.session().mockServers().contains(server))
            throw new IllegalArgumentException("mock server was not registered by the host");
        this.server = server;
    }

    @Override public synchronized void onReceive(Consumer<JsonNode> receiver) {
        if (closed || this.receiver != null) throw new IllegalStateException("MCP receiver already bound or closed");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    @Override public synchronized void send(JsonNode message) throws IOException {
        if (closed || receiver == null) throw new IOException("MCP transport is not ready");
        JsonNode response = relay.exchangeMcp(server, message);
        if (!response.isNull()) receiver.accept(response);
    }

    @Override public String transportName() { return "benchmark-host-relay"; }

    @Override public synchronized void close() { closed = true; }
}
