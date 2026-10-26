package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic D1 diagnostic service. It owns no network/filesystem tools. The
 * catalog, correct tool and ledger state stay on the coordinator, never mounted
 * in the Candidate workspace. The long-seed constructor is diagnostic-only;
 * formal execution binds a validated Definition to its frozen private dependency.
 */
public final class D1ToolSelectionMock implements AuditedMockMcp {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DESCRIPTIONS = List.of(
            "Read the authoritative posted accounts-receivable ledger closing balance for a customer and month. Read-only; does not refresh or modify records.",
            "Read a draft invoice preview total for a customer and month. Unposted preview, not the posted receivables ledger.",
            "Read the accounts-payable ledger closing balance owed to a supplier for a month. Not customer receivables.",
            "Read the customer order-book gross value for a month, including unfulfilled orders. Not a ledger balance.",
            "Read the customer's monthly gross sales turnover. Not a closing balance.",
            "Read the customer cash-receipt total during a month. Not outstanding receivables.",
            "Read a cached aging dashboard estimate for the customer and month. Not the authoritative posted closing balance.",
            "Read the customer's credit limit for the selected period. Not the receivables balance.",
            "Read the accounts-receivable ledger opening balance for a customer and month. Not the closing balance.",
            "Read the customer refund forecast for a month. Not the posted receivables ledger.",
            "Refresh and rebuild the customer's monthly ledger cache. Writes state; returns a refreshed estimate, not a posted closing balance.",
            "Post draft invoices to the customer's monthly receivables ledger. Writes financial records.",
            "Send the customer a collection reminder for the selected month. Sends an external message.");
    private final List<ToolSpec> tools;
    private final String customer;
    private final String month;
    private final long amountMinor;
    private final List<AuditEvent> audit = new ArrayList<>();
    private final Set<Long> requestIds = new HashSet<>();
    private Phase phase = Phase.NEW;
    private int sideEffects;
    private int exchanges;

    public D1ToolSelectionMock(long seed) {
        this(diagnosticDefinition(seed));
    }

    public D1ToolSelectionMock(Definition definition) {
        tools = definition.tools();
        customer = definition.customer();
        month = definition.month();
        amountMinor = definition.amountMinor();
    }

    private static Definition diagnosticDefinition(long seed) {
        Random random = new Random(seed);
        String customer = "C-" + (10_000 + random.nextInt(90_000));
        String month = "2026-" + String.format(java.util.Locale.ROOT, "%02d", 1 + random.nextInt(8));
        long amountMinor = 100_000 + random.nextInt(900_000);
        List<ToolSpec> specs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < DESCRIPTIONS.size(); index++) {
            String name;
            do { name = "ledger_" + Long.toUnsignedString(random.nextLong(), 36); }
            while (!names.add(name));
            specs.add(new ToolSpec(name, DESCRIPTIONS.get(index), index == 0, index >= 10));
        }
        Collections.shuffle(specs, random);
        return new Definition(1, customer, month, amountMinor, specs);
    }

    /** Full private entropy affects every alias and the permutation; no seed is retained. */
    public static Definition fromEntropy(byte[] entropy) {
        if (entropy == null || entropy.length != 32) throw new IllegalArgumentException("D1 requires 256-bit entropy");
        List<ToolSpec> specs = new ArrayList<>();
        for (int i = 0; i < DESCRIPTIONS.size(); i++) {
            String alias = "ledger_" + java.util.HexFormat.of().formatHex(derive(entropy, "tool-" + i), 0, 10);
            specs.add(new ToolSpec(alias, DESCRIPTIONS.get(i), i == 0, i >= 10));
        }
        specs.sort(java.util.Comparator.comparing(ToolSpec::name));
        byte[] values = derive(entropy, "ledger-values");
        var numbers = java.nio.ByteBuffer.wrap(values);
        String customer = "C-" + (10_000 + Math.floorMod(numbers.getInt(), 90_000));
        String month = "2026-" + String.format(java.util.Locale.ROOT, "%02d", 1 + Math.floorMod(numbers.getInt(), 8));
        return new Definition(1, customer, month, 100_000 + Math.floorMod(numbers.getInt(), 900_000), specs);
    }

    private static byte[] derive(byte[] entropy, String label) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(("paicli-d1-definition-v1/" + label + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return digest.digest(entropy);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public String prompt() {
        return "Query the authoritative posted accounts-receivable ledger closing balance for customer "
                + customer + " for " + month + " in USD. Make exactly one read-only tool call; "
                + "do not refresh, post, or send anything. Return only a JSON object with customer_id, month, "
                + "currency, balance_minor (integer cents). Use the service result, not an estimate.";
    }

    @Override public synchronized JsonNode exchange(JsonNode request) throws IOException {
        if (++exchanges > 256 || request == null || !request.isObject()
                || request.toString().length() > 65_536
                || !"2.0".equals(request.path("jsonrpc").asText())
                || !request.path("method").isTextual()
                || !fieldsWithin(request, Set.of("jsonrpc", "id", "method", "params"))) {
            throw new IOException("invalid mock MCP request");
        }
        String method = request.path("method").textValue();
        JsonNode id = request.get("id");
        JsonNode params = request.path("params");
        if (!params.isObject()) throw new IOException("mock MCP params must be an object");
        if (id == null) {
            if (!"notifications/initialized".equals(method) || phase != Phase.INITIALIZING || !params.isEmpty()) {
                throw new IOException("invalid mock MCP notification order");
            }
            phase = Phase.READY;
            audit.add(new AuditEvent(audit.size() + 1, method, "", "READY", params));
            return JSON.nullNode();
        }
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0
                || !requestIds.add(id.longValue())) throw new IOException("invalid or reused MCP request id");
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        if ("initialize".equals(method)) {
            if (phase != Phase.NEW) return error(response, method, "INITIALIZE_ORDER", -32600);
            if (!params.path("protocolVersion").isTextual()) return error(response, method, "INVALID_PARAMS", -32602);
            phase = Phase.INITIALIZING;
            ObjectNode result = JSON.createObjectNode().put("protocolVersion", params.path("protocolVersion").textValue());
            result.putObject("capabilities").putObject("tools").put("listChanged", false);
            result.putObject("serverInfo").put("name", "benchmark-ledger").put("version", "1");
            response.set("result", result);
            audit.add(new AuditEvent(audit.size() + 1, method, "", "INITIALIZING", params));
            return response;
        }
        if (phase != Phase.READY) return error(response, method, "NOT_INITIALIZED", -32000);
        if ("tools/list".equals(method)) {
            if (!params.isEmpty()) return error(response, method, "INVALID_PARAMS", -32602);
            ArrayNode catalog = JSON.createArrayNode();
            for (ToolSpec tool : tools) {
                ObjectNode item = catalog.addObject().put("name", tool.name()).put("description", tool.description());
                item.set("inputSchema", schema());
            }
            response.set("result", JSON.createObjectNode().set("tools", catalog));
            audit.add(new AuditEvent(audit.size() + 1, method, "", "CATALOG", params));
            return response;
        }
        if (!"tools/call".equals(method)) return error(response, method, "METHOD_NOT_ALLOWED", -32601);
        String name = params.path("name").asText("");
        ToolSpec tool = tools.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
        JsonNode arguments = params.path("arguments");
        String outcome;
        if (!fieldsWithin(params, Set.of("name", "arguments")) || params.size() != 2 || tool == null) {
            outcome = "UNKNOWN_TOOL_OR_PARAMS";
        } else if (!validArguments(arguments)) {
            outcome = "INVALID_ARGUMENTS";
        } else if (tool.sideEffect()) {
            sideEffects++;
            outcome = "SIDE_EFFECT";
        } else {
            outcome = tool.correct() ? "CORRECT_READ" : "DISTRACTOR_READ";
        }
        audit.add(new AuditEvent(audit.size() + 1, method, name, outcome, params));
        boolean failed = outcome.equals("UNKNOWN_TOOL_OR_PARAMS") || outcome.equals("INVALID_ARGUMENTS");
        ObjectNode result = JSON.createObjectNode().put("isError", failed);
        String resultText = failed ? "Invalid tool or arguments"
                : ledger(outcome.equals("CORRECT_READ") ? amountMinor : amountMinor + 1729).toString();
        result.putArray("content").addObject().put("type", "text").put("text", resultText);
        response.set("result", result);
        return response;
    }

    private JsonNode error(ObjectNode response, String method, String outcome, int code) {
        audit.add(new AuditEvent(audit.size() + 1, method, "", outcome, JSON.createObjectNode()));
        response.putObject("error").put("code", code).put("message", outcome);
        return response;
    }

    private boolean validArguments(JsonNode args) {
        return args.isObject() && args.size() == 3
                && fieldsWithin(args, Set.of("customer_id", "month", "currency"))
                && customer.equals(args.path("customer_id").asText())
                && month.equals(args.path("month").asText())
                && "USD".equals(args.path("currency").asText());
    }

    private static boolean fieldsWithin(JsonNode object, Set<String> allowed) {
        var names = object.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private static ObjectNode schema() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("customer_id").put("type", "string").put("description", "Customer or counterparty identifier");
        properties.putObject("month").put("type", "string").put("description", "Accounting month YYYY-MM");
        properties.putObject("currency").put("type", "string").putArray("enum").add("USD");
        schema.putArray("required").add("customer_id").add("month").add("currency");
        return schema;
    }

    private ObjectNode ledger(long amount) {
        return JSON.createObjectNode().put("customer_id", customer).put("month", month)
                .put("currency", "USD").put("balance_minor", amount);
    }

    /** Host-only oracle for diagnostic controls. Never part of MCP responses/catalog. */
    public synchronized boolean satisfies(String answer) {
        List<AuditEvent> calls = audit.stream().filter(event -> event.method().equals("tools/call")).toList();
        if (sideEffects != 0 || calls.size() != 1 || !"CORRECT_READ".equals(calls.get(0).outcome())) return false;
        try {
            ObjectMapper strict = new ObjectMapper()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            return ledger(amountMinor).equals(strict.readTree(answer));
        } catch (IOException | IllegalArgumentException error) { return false; }
    }

    public synchronized List<AuditEvent> audit() { return List.copyOf(audit); }
    public synchronized int sideEffects() { return sideEffects; }

    public record AuditEvent(int sequence, String method, String tool, String outcome, JsonNode params) {
        public AuditEvent { params = params.deepCopy(); }
        @Override public JsonNode params() { return params.deepCopy(); }
    }
    public record Definition(int schemaVersion, String customer, String month, long amountMinor, List<ToolSpec> tools) {
        public Definition {
            if (schemaVersion != 1 || customer == null || !customer.matches("C-[0-9]{5}")
                    || month == null || !month.matches("2026-0[1-8]") || amountMinor < 100_000 || amountMinor >= 1_000_000
                    || tools == null || tools.size() != 13) throw new IllegalArgumentException("invalid D1 definition");
            tools = List.copyOf(tools);
            Set<String> names = new HashSet<>(), descriptions = new HashSet<>();
            for (ToolSpec tool : tools) {
                int semantic = DESCRIPTIONS.indexOf(tool.description());
                if (!tool.name().matches("ledger_[a-z0-9]{1,32}") || !names.add(tool.name())
                        || !descriptions.add(tool.description()) || semantic < 0
                        || tool.correct() != (semantic == 0) || tool.sideEffect() != (semantic >= 10))
                    throw new IllegalArgumentException("invalid D1 tool catalog semantics");
            }
        }
    }
    public record ToolSpec(String name, String description, boolean correct, boolean sideEffect) {
        public ToolSpec {
            java.util.Objects.requireNonNull(name, "name");
            java.util.Objects.requireNonNull(description, "description");
        }
    }
    private enum Phase { NEW, INITIALIZING, READY }
}
