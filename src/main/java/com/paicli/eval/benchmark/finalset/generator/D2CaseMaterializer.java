package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D2FrozenOracle;
import com.paicli.eval.benchmark.mock.D2ReadOnlyJoinMock;
import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private three-service world plus a separately implemented, deterministic verifier. */
final class D2CaseMaterializer {
    static final String RUNTIME_PATH = "validators/final/_private/d2_verify.py";
    private static final ObjectMapper JSON = new ObjectMapper();
    private D2CaseMaterializer() {}

    static String wrapper() {
        return """
                #!/bin/sh
                set -eu
                [ "$#" -eq 2 ] || exit 2
                validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
                exec python3 "$validator_root/_private/d2_verify.py" "$1" "$2"
                """;
    }

    static String prompt(SeededVariant variant) {
        return new D2ReadOnlyJoinMock(D2ReadOnlyJoinMock.fromEntropy(variant.entropy())).prompt();
    }

    static void materialize(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        try (var input = D2CaseMaterializer.class.getResourceAsStream("/benchmark/d2_verify.py")) {
            if (input == null) throw new IOException("D2 verifier resource missing");
            writer.text(RUNTIME_PATH, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        String readme = "# Frozen MCP-only task\n\nUse directory, ticket and calendar to answer the read-only query.\n";
        var fixture = writer.text("fixtures/final/D2/README.md", readme);
        writer.text("references/final/D2/workspace/README.md", readme);
        var definition = D2ReadOnlyJoinMock.fromEntropy(variant.entropy());
        var oracle = new D2FrozenOracle("D2", "MOCK_MCP", variant.variantId(),
                Map.of("README.md", FinalCaseContractCompiler.sha256(fixture)), definition);
        var oracleFile = writer.json(D2FrozenOracle.PATH, oracle);
        if (!oracle.equals(D2FrozenOracle.parse(Files.readAllBytes(oracleFile))))
            throw new IOException("D2 oracle changed during read-back");
        var mock = oracle.newService();
        for (String server : mock.serverNames()) {
            mock.exchange(server, JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of("protocolVersion", "2025-03-26"))));
            mock.exchange(server, JSON.valueToTree(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of())));
        }
        for (String server : mock.serverNames())
            mock.exchange(server, JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of())));
        var person = definition.people().stream().filter(p -> p.department().equals(definition.department())).findFirst().orElseThrow();
        var incident = definition.incidents().stream().filter(i -> i.assigneeId().equals(person.ticketAssigneeId())
                && i.status().equals("open") && i.validUntil().compareTo(definition.asOf()) > 0).findFirst().orElseThrow();
        var meeting = definition.events().stream().filter(e -> e.personId().equals(person.calendarPersonId())
                && e.incidentRef().equals(incident.calendarReference()) && e.status().equals("scheduled")
                && e.startsAt().compareTo(definition.asOf()) > 0).min(java.util.Comparator.comparing(D2ReadOnlyJoinMock.CalendarEvent::startsAt)).orElseThrow();
        var tools = new ArrayList<Map<String, Object>>();
        for (String server : mock.serverNames()) {
            var tool = definition.tools().stream().filter(t -> t.operation().server().equals(server) && !t.operation().write()).findFirst().orElseThrow();
            Map<String, Object> args = switch (server) {
                case "directory" -> Map.of("full_name", definition.fullName());
                case "ticket" -> Map.of("assignee_id", person.ticketAssigneeId());
                default -> Map.of("person_id", person.calendarPersonId(), "incident_ref", incident.calendarReference());
            };
            var result = mock.exchange(server, JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                    "params", Map.of("name", tool.name(), "arguments", args))));
            tools.add(ImplementedCaseMaterializers.toolEvent(tools.size() + 1, "mcp__" + server + "__" + tool.name(), args,
                    result.path("result").path("content").get(0).path("text").textValue()));
        }
        String answer = JSON.writeValueAsString(Map.of("employee_id", person.employeeId(), "incident_id", incident.id(),
                "calendar_event_id", meeting.id(), "starts_at", meeting.startsAt()));
        var evidence = ImplementedCaseMaterializers.referenceEnvelope("D2", answer, tools);
        evidence.put("schemaVersion", 3);
        var metrics = new LinkedHashMap<>((Map<String, Object>) evidence.get("llmMetrics"));
        metrics.put("toolCalls", tools.size());
        evidence.put("llmMetrics", metrics);
        evidence.put("mockMcp", Map.of("schemaVersion", 2, "caseId", "D2", "profile", D2FrozenOracle.PROFILE,
                "mockSourceSha256", FinalCaseContractCompiler.sha256(oracleFile), "events", mock.audit(),
                "sideEffects", mock.sideEffects(), "initialStateDigests", mock.initialStateDigests(), "finalStateDigests", mock.stateDigests()));
        writer.json("references/final/D2/evidence.json", evidence);
    }

    static ScoringContract scoring(String verifierSha, String runtimeSha) {
        // Same strict task semantics as the pre-existing diagnostic. No post-result partial credit.
        return new ScoringContract(ScoringContract.CURRENT_SCHEMA_VERSION, "D2", 100,
                List.of("audit_binding", "entity_join", "answer", "read_only").stream()
                        .map(id -> new ScoringContract.AssertionRule("D2." + id, "strictTask", true)).toList(),
                List.of("write_call", "local_surface_violation", "workspace_mutation").stream()
                        .map(id -> new ScoringContract.HardGateRule("D2." + id)).toList(),
                List.of(new ScoringContract.ComponentRule("strictTask", 100, ScoreSource.DETERMINISTIC)), verifierSha, runtimeSha);
    }
}
