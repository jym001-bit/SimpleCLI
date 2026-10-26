package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.paicli.eval.benchmark.BenchmarkToolExecutionEvidence;
import com.paicli.eval.benchmark.mock.F3SupportBundleMock;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.safety.F3Definition;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Deterministic synthetic reference only: never calls a provider, Worker, or test helper. */
final class F3ReferenceEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper SORTED = new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String MODEL = "synthetic-f3-reference";
    private static final String TOOL = "mcp__support__get_case";
    record Reference(ObjectNode development, List<Map<String, Object>> tools, Map<String, Object> metrics) { }
    private F3ReferenceEvidence() { }

    static Reference build(F3Definition definition) throws IOException {
        var b = new Builder(definition);
        b.read("README.md"); b.caseRead(); b.read(F3Definition.SERVICE_FILE); b.read(F3Definition.EVENTS_FILE);
        b.tool("write_file", Map.of("path", F3Definition.BUNDLE, "content", definition.expectedBundle()),
                "文件已写入: " + F3Definition.BUNDLE);
        b.files.put(F3Definition.BUNDLE, definition.expectedBundle()); b.read(F3Definition.BUNDLE); b.finish();
        ObjectNode development = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "F3_DEVELOPMENT_CONTROL")
                .put("sourceSha256", hash(JSON.writeValueAsBytes(definition))).put("answer", definition.finalAnswer());
        development.set("definition", JSON.valueToTree(definition)); development.set("audit", b.audit);
        development.set("mockAudit", JSON.valueToTree(b.mock.audit())); development.set("toolExecutions", b.trace);
        development.set("workspaceBefore", JSON.valueToTree(definition.files())); development.set("workspaceAfter", JSON.valueToTree(b.files));
        development.putObject("homeAfter");
        List<Map<String, Object>> tools = JSON.convertValue(b.trace, new TypeReference<>() { });
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("calls", b.turns.size()); metrics.put("successfulCalls", b.turns.size());
        metrics.put("inputTokens", 3L * b.turns.size()); metrics.put("outputTokens", 2L * b.turns.size());
        metrics.put("cachedInputTokens", 0); metrics.put("toolCalls", b.raw.size()); metrics.put("elapsedMillis", 0);
        metrics.put("resolvedModel", MODEL); metrics.put("resolvedModelConsistent", true); metrics.put("usageComplete", true);
        var system = JSON.createArrayNode().add(b.history.get(0));
        metrics.put("systemPromptSha256", canonicalHash(system)); metrics.put("initialToolSchemaSha256", canonicalHash(b.catalog));
        metrics.put("requestFingerprintComplete", true);
        return new Reference(development, List.copyOf(tools), Collections.unmodifiableMap(metrics));
    }

    /** Convert nodes to recursive maps before sorting; ObjectNode insertion order is not a fingerprint. */
    private static String canonicalHash(JsonNode node) throws IOException {
        return hash(SORTED.writeValueAsBytes(SORTED.convertValue(node, Object.class)));
    }

    private static final class Builder {
        final F3Definition definition;
        final F3SupportBundleMock mock;
        final Map<String, String> files;
        final ObjectNode audit;
        final ArrayNode turns, exchanges, raw;
        final ArrayNode trace = JSON.createArrayNode(), history = JSON.createArrayNode(), catalog = JSON.createArrayNode();
        int rpcId;
        Builder(F3Definition definition) throws IOException {
            this.definition = definition; mock = new F3SupportBundleMock(definition); files = new LinkedHashMap<>(definition.files());
            audit = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "F3_HOST_TOOL_RESULT_AUDIT")
                    .put("promptSha256", BenchmarkRelayProtocol.textSha256(definition.prompt())).put("failed", false);
            turns = audit.putArray("providerTurns"); exchanges = audit.putArray("mcpExchanges"); raw = audit.putArray("toolResults");
            history.add(message("system", "Synthetic F3 verifier reference; not a product/provider execution.", null, null));
            history.add(message("user", definition.prompt(), null, null));
            for (String name : List.of("read_file", "write_file", "list_dir", "glob_files", "grep_code", "create_project", TOOL))
                catalog.addObject().put("name", name).put("description", "Synthetic reference schema; not a runtime catalog attestation")
                        .putObject("parameters").put("type", "object");
            exchange("initialize", SORTED.valueToTree(Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of()),
                    "clientInfo", Map.of("name", "synthetic-reference", "version", "1"))), 0, 0, true);
            exchange("notifications/initialized", JSON.createObjectNode(), 0, 0, false);
            exchange("tools/list", JSON.createObjectNode(), 0, 0, true);
        }
        ObjectNode request(int ordinal) {
            ObjectNode request = JSON.createObjectNode(); request.set("header", header("WORKER_TO_COORDINATOR", "CHAT_REQUEST", "provider-" + ordinal, 0));
            request.set("messages", history.deepCopy()); request.set("tools", catalog.deepCopy()); return request;
        }
        void tool(String name, Map<String, ?> args, String result) throws IOException {
            int ordinal = raw.size() + 1, turnOrdinal = turns.size() + 1;
            String id = "tool-" + ordinal, arguments = SORTED.writeValueAsString(args);
            ArrayNode calls = JSON.createArrayNode().add(JSON.createObjectNode().put("id", id).put("name", name).put("arguments", arguments));
            ObjectNode turn = turns.addObject().put("ordinal", turnOrdinal);
            turn.set("request", request(turnOrdinal)); turn.set("response", response("", calls)); turn.putArray("streamDeltas");
            raw.addObject().put("ordinal", ordinal).put("callId", id).put("toolName", name).put("argumentsJson", arguments)
                    .put("result", result).put("elapsedMillis", 0).put("timedOut", false).put("successful", true);
            trace.add(JSON.valueToTree(BenchmarkToolExecutionEvidence.from(ordinal, id, name, arguments, result, 0, false, true)));
            history.add(message("assistant", "", calls, null)); history.add(message("tool", result, null, id));
        }
        void read(String path) throws IOException { tool("read_file", Map.of("path", path), "文件内容:\n" + files.get(path)); }
        void caseRead() throws IOException {
            Map<String, String> args = Map.of("case_id", definition.caseId());
            tool(TOOL, args, definition.caseResultJson());
            ObjectNode params = JSON.createObjectNode().put("name", "get_case"); params.set("arguments", JSON.valueToTree(args));
            exchange("tools/call", params, turns.size(), raw.size(), true);
        }
        void exchange(String method, JsonNode params, int turn, int tool, boolean idPresent) throws IOException {
            ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0");
            if (idPresent) request.put("id", ++rpcId); request.put("method", method); request.set("params", params);
            JsonNode response = mock.exchange("support", request);
            int ordinal = exchanges.size() + 1; String id = "mcp-" + ordinal;
            ObjectNode exchange = exchanges.addObject().put("ordinal", ordinal).put("providerTurn", turn).put("toolOrdinal", tool);
            ObjectNode req = exchange.putObject("request").put("server", "support");
            req.set("header", header("WORKER_TO_COORDINATOR", "MCP_REQUEST", id, 0)); req.set("message", request);
            ObjectNode res = exchange.putObject("response").put("server", "support");
            res.set("header", header("COORDINATOR_TO_WORKER", "MCP_COMPLETE", id, 1)); res.set("message", response);
        }
        void finish() {
            int ordinal = turns.size() + 1;
            ObjectNode req = request(ordinal), turn = turns.addObject().put("ordinal", ordinal);
            turn.set("request", req); turn.set("response", response(definition.finalAnswer(), JSON.createArrayNode())); turn.putArray("streamDeltas");
            ObjectNode terminal = audit.putObject("terminal").put("answer", definition.finalAnswer()).put("commandObservationFailures", 0);
            terminal.set("header", header("WORKER_TO_COORDINATOR", "WORKER_COMPLETE", "", 0));
            terminal.set("toolExecutions", trace.deepCopy()); terminal.putArray("commandObservations");
        }
    }
    private static ObjectNode header(String direction, String type, String id, int sequence) {
        return JSON.createObjectNode().put("direction", direction).put("type", type).put("callId", id).put("eventSequence", sequence);
    }
    private static ObjectNode message(String role, String content, ArrayNode calls, String id) {
        ObjectNode m = JSON.createObjectNode().put("role", role).put("content", content).putNull("reasoningContent").put("toolCallId", id);
        m.set("toolCalls", calls == null ? JSON.createArrayNode() : calls.deepCopy()); m.putArray("contentParts"); return m;
    }
    private static ObjectNode response(String content, ArrayNode calls) {
        ObjectNode r = JSON.createObjectNode().put("role", "assistant").put("content", content).putNull("reasoningContent")
                .put("inputTokens", 3).put("outputTokens", 2).put("cachedInputTokens", 0).put("resolvedModel", MODEL).put("usagePresent", true);
        r.set("toolCalls", calls); return r;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
