package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D4FrozenOracle;
import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.tool.ToolRegistry;
import com.paicli.web.NetworkPolicy;
import com.paicli.web.SearchProvider;
import com.paicli.web.SearchResult;
import com.paicli.web.WebFetcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;

/** Seeded closed Web source and explicitly synthetic reference, never an Agent/model score. */
final class D4CaseMaterializer {
    static final List<String> RUNTIME_PATHS = List.of("validators/final/_private/d4_replay.py", "validators/final/_private/d4_verify.py");
    private static final ObjectMapper JSON = new ObjectMapper();
    private D4CaseMaterializer() { }

    static String wrapper() {
        return """
                #!/bin/sh
                set -eu
                [ "$#" -eq 2 ] || exit 2
                validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
                exec python3 -B "$validator_root/_private/d4_verify.py" "$1" "$2"
                """;
    }

    static String prompt(SeededVariant variant) { return new D4WebMock(variant.entropy()).prompt(); }

    static void materialize(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        for (String path : RUNTIME_PATHS) try (var input = D4CaseMaterializer.class.getResourceAsStream("/benchmark/" + path.substring(path.lastIndexOf('/') + 1))) {
            if (input == null) throw new IOException("D4 verifier resource missing");
            writer.text(path, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        String readme = "# Frozen Web-only task\n\nUse structured search results, fetch the supporting pages and cite current facts. Do not use local files or commands.\n";
        var fixture = writer.text("fixtures/final/D4/README.md", readme);
        writer.text("references/final/D4/workspace/README.md", readme);
        var oracle = new D4FrozenOracle(2, "D4", "MOCK_WEB", variant.variantId(),
                Map.of("README.md", FinalCaseContractCompiler.sha256(fixture)), D4WebMock.fromEntropy(variant.entropy()));
        var oracleFile = writer.json(D4FrozenOracle.PATH, oracle);
        if (!oracle.equals(D4FrozenOracle.parse(Files.readAllBytes(oracleFile)))) throw new IOException("D4 oracle read-back changed");
        var reference = new ReferenceTranscript(oracle.newService());
        String answer = reference.run();
        var tools = reference.tools.stream().map(t -> JSON.convertValue(t, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})).toList();
        var evidence = ImplementedCaseMaterializers.referenceEnvelope("D4", answer, tools);
        evidence.put("schemaVersion", 4);
        @SuppressWarnings("unchecked") var metrics = new LinkedHashMap<>((Map<String, Object>) evidence.get("llmMetrics"));
        metrics.put("toolCalls", tools.size()); evidence.put("llmMetrics", metrics);
        String fullPrompt = "# D4 " + FinalSourceRecipeCatalog.require("D4").title() + "\n\n" + reference.mock.prompt()
                + "\n\nVariant: " + variant.variantId() + "\n";
        evidence.put("mockWeb", Map.of("schemaVersion", 1, "caseId", "D4", "profile", D4FrozenOracle.PROFILE,
                "relayVersion", VERSION, "mockSourceSha256", FinalCaseContractCompiler.sha256(oracleFile),
                "promptSha256", textSha256(fullPrompt), "events", reference.mock.audit(),
                "relayEvents", reference.mock.relayAudit(), "providerTurns", reference.mock.providerAudit()));
        writer.json("references/final/D4/evidence.json", evidence);
    }

    static ScoringContract scoring(String verifierSha, String runtimeSha) {
        return new ScoringContract(ScoringContract.CURRENT_SCHEMA_VERSION, "D4", 100,
                List.of("audit_binding", "search", "coverage", "tool_views", "tool_success", "answer").stream()
                        .map(id -> new ScoringContract.AssertionRule("D4." + id, "strictTask", true)).toList(),
                List.of("unauthorized_url", "local_surface", "workspace_mutation").stream()
                        .map(id -> new ScoringContract.HardGateRule("D4." + id)).toList(),
                List.of(new ScoringContract.ComponentRule("strictTask", 100, ScoreSource.DETERMINISTIC)), verifierSha, runtimeSha);
    }

    /** Real tool formatting/HTML extraction over a closed backend; no model or network. */
    private static final class ReferenceTranscript {
        private final D4WebMock mock;
        private final List<WireToolExecution> tools = new ArrayList<>();
        private final List<WireMessage> observed = new ArrayList<>();
        private int ordinal, sequence;
        ReferenceTranscript(D4WebMock mock) { this.mock = mock; }
        String run() throws IOException {
            var d = mock.definition();
            var search = new WireToolCall("ref-search", "web_search", JSON.writeValueAsString(Map.of("query", d.project(), "top_k", 5)));
            var release = new WireToolCall("ref-release", "web_fetch", JSON.writeValueAsString(Map.of("url", d.releaseUrl())));
            var migration = new WireToolCall("ref-migration", "web_fetch", JSON.writeValueAsString(Map.of("url", d.migrationUrl())));
            var registry = new ReferenceTools();
            respond("", List.of(search)); call(registry, search);
            respond("", List.of(release, migration)); call(registry, release); call(registry, migration);
            String answer = JSON.writeValueAsString(Map.of("project", d.project(), "release", d.release(),
                    "timeout_seconds", d.timeoutSeconds(), "cache_entries", d.cacheEntries(),
                    "sources", Map.of("timeout_seconds", d.releaseUrl(), "cache_entries", d.migrationUrl())));
            respond(answer, List.of()); return answer;
        }
        private void respond(String content, List<WireToolCall> calls) {
            mock.recordProviderTurn(observed, new WireChatResponse("assistant", content, null, calls, 0, 0, 0, "synthetic-reference", true));
        }
        private void call(ToolRegistry registry, WireToolCall call) throws IOException {
            ordinal++;
            var output = registry.executeToolOutput(call.name(), call.arguments());
            if (!output.successful()) throw new IOException("D4 synthetic reference tool failed");
            String text = output.text();
            tools.add(new WireToolExecution(ordinal, call.id(), call.name(), call.arguments(), text, textSha256(text), text.length(), 1, false, true));
            observed.add(new WireMessage("tool", text, null, List.of(), call.id()));
        }
        private WebComplete exchange(WebOperation operation, String input, int topK) throws IOException {
            var request = new WebRequest(new Header(Direction.WORKER_TO_COORDINATOR, FrameType.WEB_REQUEST, "ref-web-" + ++sequence, 0), operation, input, topK);
            List<WebSearchResult> results = List.of(); WebPage page = null; String denial = null;
            switch (operation) {
                case SEARCH -> results = mock.search(input, topK).stream().map(r -> new WebSearchResult(r.position(), r.title(), r.url(), r.snippet(), r.source())).toList();
                case CHECK_URL -> { denial = mock.checkUrl(input); if (denial == null) denial = ""; }
                case FETCH -> { var raw = mock.fetch(input); page = new WebPage(raw.url(), raw.body(), raw.contentType(), raw.charset(), raw.truncated()); }
            }
            var response = new WebComplete(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.WEB_COMPLETE, request.callId(), 1), operation, results, page, denial);
            mock.recordExchange(ordinal, request, response); return response;
        }
        private final class ReferenceTools extends ToolRegistry {
            ReferenceTools() {
                installWebDependencies(new SearchProvider() {
                    public String name() { return "d4-offline"; }
                    public boolean isReady() { return true; }
                    public String unavailableHint() { return "offline fixture unavailable"; }
                    public List<SearchResult> search(String query, int topK) throws IOException {
                        return exchange(WebOperation.SEARCH, query, topK).results().stream()
                                .map(r -> new SearchResult(r.position(), r.title(), r.url(), r.snippet(), r.source())).toList();
                    }
                }, new WebFetcher() {
                    public RawResponse fetch(String url) throws IOException {
                        var p = exchange(WebOperation.FETCH, url, 0).page();
                        return new RawResponse(p.url(), p.body(), p.contentType(), p.charset(), p.truncated());
                    }
                }, new NetworkPolicy() {
                    public String checkUrl(String url) {
                        try { String denial = exchange(WebOperation.CHECK_URL, url, 0).denial(); return denial.isEmpty() ? null : denial; }
                        catch (IOException failure) { throw new IllegalStateException("reference Web check failed", failure); }
                    }
                });
            }
        }
    }
}
