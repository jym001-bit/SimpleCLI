package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.eval.benchmark.ScopedRequestFingerprints;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.PlanRequestAudit;
import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.llm.LlmClient;
import com.paicli.plan.PlanExecutionObserver;
import com.paicli.plan.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

import static com.paicli.plan.PlanExecutionObserver.*;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.textSha256;

/** Deterministic private E1 source/reference prototype; catalog registration is not production admission. */
final class E1CaseMaterializer {
    static final List<String> RUNTIME_PATHS = List.of("validators/final/_private/e1_replay.py", "validators/final/_private/e1_verify.py");
    private static final ObjectMapper JSON = new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private E1CaseMaterializer() { }

    /** Package-local prototype gate: new, empty, canonical, owner-only and outside every Git tree. */
    static void materializePrototype(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        Path root = writer.root();
        if (!"E1".equals(variant.caseId()) || !root.equals(root.toRealPath()) || Files.isSymbolicLink(root)
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("E1 prototype requires a canonical private root");
        for (Path p = root; p != null; p = p.getParent()) for (String vcs : List.of(".git", ".hg", ".svn"))
            if (Files.exists(p.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("E1 prototype must stay outside version control");
        try (var entries = Files.list(root)) { if (entries.findAny().isPresent()) throw new IOException("E1 prototype root must be empty"); }
        var oracle = oracle(variant);
        writer.text("prompts/final/E1.md", oracle.prompt());
        writer.executable("validators/final/E1", wrapper());
        materialize(writer, oracle);
        var scoring = scoring(FinalCaseContractCompiler.sha256(root.resolve("validators/final/E1")),
                ImplementedCaseMaterializers.digestFiles(root, RUNTIME_PATHS));
        writer.json("validators/final/_private/scoring-contracts/E1.json", scoring);
        writer.json("e1-prototype-manifest.json", Map.ofEntries(
                Map.entry("schemaVersion", 1), Map.entry("caseId", "E1"), Map.entry("variantId", variant.variantId()),
                Map.entry("caseWeight", 4), Map.entry("toolProfile", "FILE_ONLY"),
                Map.entry("implementationStatus", "IMPLEMENTED"), Map.entry("runnerIntegrationStatus", "NOT_INTEGRATED"),
                Map.entry("publicationEligible", false), Map.entry("realProviderCalls", 0),
                Map.entry("referenceOrigin", "SYNTHETIC_TRANSCRIPT_NOT_RUNTIME_OR_MODEL_EVIDENCE"),
                Map.entry("sourceSha256", FinalCaseContractCompiler.sha256(root.resolve(E1FrozenOracle.PATH))),
                Map.entry("missing", List.of("complete-28-case-suite", "production-batch-admission", "remaining-failure-semantics"))));
    }

    /** Called after the catalog skeleton wrote the exact prompt and wrapper; never overwrites either. */
    static void materializeRegistered(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        var oracle = oracle(variant);
        if (!"E1".equals(variant.caseId()) || !oracle.prompt().equals(Files.readString(writer.root().resolve("prompts/final/E1.md"))))
            throw new IOException("E1 catalog prompt differs from frozen source");
        materialize(writer, oracle);
    }

    static E1FrozenOracle oracle(SeededVariant variant) {
        var files = new LinkedHashMap<String, String>();
        for (int branch = 0; branch < 2; branch++) {
            String side = branch == 0 ? "left" : "right";
            int count = variant.number(branch * 2, 6, 14);
            var csv = new StringBuilder("id,amount_cents\n");
            for (int i = 0; i < count; i++) {
                int amount = variant.number((i * 2 + branch * 7) % 30, 25, 9000);
                if (i % 4 == 0) amount = -amount;
                if (i == count - 1) amount = 0;
                csv.append(branch == 0 ? "左_" : "右_").append(variant.shortToken(branch * 8))
                        .append('_').append(i + 1).append(',').append(amount).append('\n');
            }
            files.put(side + ".csv", csv.toString());
        }
        return new E1FrozenOracle(2, "E1", "FILE_ONLY", E1FrozenOracle.PROFILE, variant.variantId(), files);
    }

    static String wrapper() {
        return """
                #!/bin/sh
                set -eu
                [ "$#" -eq 2 ] || exit 2
                validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
                exec python3 -B "$validator_root/_private/e1_verify.py" "$1" "$2"
                """;
    }

    private static void materialize(PrivateSourceWriter writer, E1FrozenOracle oracle) throws IOException {
        for (String path : RUNTIME_PATHS) try (var in = E1CaseMaterializer.class.getResourceAsStream("/benchmark/" + path.substring(path.lastIndexOf('/') + 1))) {
            if (in == null) throw new IOException("E1 verifier resource unavailable");
            writer.text(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (var entry : oracle.files().entrySet()) {
            writer.text("fixtures/final/E1/" + entry.getKey(), entry.getValue());
            writer.text("references/final/E1/workspace/" + entry.getKey(), entry.getValue());
        }
        Path source = writer.json(E1FrozenOracle.PATH, oracle);
        if (!oracle.equals(E1FrozenOracle.parse(Files.readAllBytes(source)))) throw new IOException("E1 source read-back mismatch");
        var reference = new Reference(oracle);
        writer.text("references/final/E1/workspace/report.json", reference.report);
        writer.json("references/final/E1/evidence.json", reference.envelope(FinalCaseContractCompiler.sha256(source)));
    }

    static ScoringContract scoring(String verifierSha, String runtimeSha) {
        return new ScoringContract(ScoringContract.CURRENT_SCHEMA_VERSION, "E1", 100,
                List.of("graph", "overlapping_lifecycles", "source_observed", "branch_results", "complete_dependency_inputs", "artifact")
                        .stream().map(id -> new ScoringContract.AssertionRule("E1." + id, "strictTask", true)).toList(),
                List.of("workspace_mutation", "forbidden_tool_or_path").stream().map(id -> new ScoringContract.HardGateRule("E1." + id)).toList(),
                List.of(new ScoringContract.ComponentRule("strictTask", 100, ScoreSource.DETERMINISTIC)), verifierSha, runtimeSha);
    }

    /** Fabricated, deterministic reference for testing the verifier, never labelled as an observed run. */
    private static final class Reference {
        final E1FrozenOracle oracle;
        final String executionId, graph, report;
        final List<PlanRequestAudit.ObservedEvent> events = new ArrayList<>();
        final List<PlanRequestAudit.ProviderTurn> turns = new ArrayList<>();
        final List<ScopedRequestFingerprints.Request> requests = new ArrayList<>();
        final List<BenchmarkRelayProtocol.WireToolExecution> tools = new ArrayList<>();
        final Map<String, String> outputs = new LinkedHashMap<>();
        final Map<String, String> inputs = new LinkedHashMap<>();
        final List<LlmClient.Tool> definitions;
        long tick;

        Reference(E1FrozenOracle oracle) throws IOException {
            this.oracle = oracle;
            executionId = UUID.nameUUIDFromBytes(("e1-synthetic-reference/" + oracle.variantId()).getBytes(StandardCharsets.UTF_8)).toString();
            definitions = List.of(new LlmClient.Tool("read_file", "synthetic reference read", JSON.createObjectNode()),
                    new LlmClient.Tool("write_file", "synthetic reference write", JSON.createObjectNode()));
            graph = JSON.writeValueAsString(Map.of("tasks", List.of(
                    Map.of("id", "left", "description", "LEFT", "type", "FILE_READ", "dependencies", List.of()),
                    Map.of("id", "right", "description", "RIGHT", "type", "FILE_READ", "dependencies", List.of()),
                    Map.of("id", "merge", "description", "MERGE", "type", "FILE_WRITE", "dependencies", List.of("left", "right")))));
            var left = oracle.referenceBranch("left"); var right = oracle.referenceBranch("right");
            outputs.put("task_1", JSON.writeValueAsString(left)); outputs.put("task_2", JSON.writeValueAsString(right));
            outputs.put("task_3", "synthetic merge completed");
            report = JSON.writeValueAsString(Map.of("left", left, "right", right,
                    "combined_cents", (Integer)left.get("sum_cents") + (Integer)right.get("sum_cents")));
            for (int i = 1; i <= 3; i++) inputs.put("task_" + i, context(i));
            var planning = text(graph);
            turn("planner", List.of(message("system", "synthetic planner", null, List.of()),
                    message("user", "请为以下任务制定执行计划：\n" + oracle.prompt(), null, List.of())), List.of(), planning);
            observe(new PlanStarted(executionId, tick + 1, "synthetic-plan", TextFingerprint.of(oracle.prompt()), List.of(
                    node(1, "LEFT", Task.TaskType.FILE_READ, List.of()), node(2, "RIGHT", Task.TaskType.FILE_READ, List.of()),
                    node(3, "MERGE", Task.TaskType.FILE_WRITE, List.of("task_1", "task_2"))), List.of("task_1", "task_2", "task_3")));
            enter(1, 11); enter(2, 12);
            var leftCall = call("read_file", Map.of("path", "left.csv"));
            var rightCall = call("read_file", Map.of("path", "right.csv"));
            var mergeCall = call("write_file", Map.of("path", "report.json", "content", report));
            var l = toolResponse(leftCall); var r = toolResponse(rightCall); var m = toolResponse(mergeCall);
            turn(scope(1), initial(1), definitions, l); turn(scope(2), initial(2), definitions, r);
            String leftRead = "文件内容:\n" + oracle.files().get("left.csv"), rightRead = "文件内容:\n" + oracle.files().get("right.csv");
            batch(1, leftCall, leftRead); batch(2, rightCall, rightRead);
            turn(scope(1), continued(1, l, leftRead), definitions, text(outputs.get("task_1"))); exit(1);
            turn(scope(2), continued(2, r, rightRead), definitions, text(outputs.get("task_2"))); exit(2);
            enter(3, 1); turn(scope(3), initial(3), definitions, m);
            batch(3, mergeCall, "文件已写入: report.json");
            turn(scope(3), continued(3, m, "文件已写入: report.json"), definitions, text(outputs.get("task_3"))); exit(3);
        }

        Map<String, Object> envelope(String sourceSha) {
            var toolMaps = tools.stream().map(t -> JSON.convertValue(t, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})).toList();
            var result = new LinkedHashMap<String, Object>();
            result.put("schemaVersion", 5); result.put("caseId", "E1"); result.put("repeat", 1);
            result.put("mode", "plan"); result.put("toolProfile", "FILE_ONLY");
            result.put("answer", "synthetic reference; not a model run"); result.put("toolExecutions", toolMaps);
            for (String kind : List.of("Workspace", "Bundle")) for (String field : List.of("TreeSha256", "FileCount", "TotalBytes"))
                result.put("verifier" + kind + field, null);
            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("calls", 7); metrics.put("successfulCalls", 7); metrics.put("inputTokens", 7); metrics.put("outputTokens", 7);
            metrics.put("toolCalls", tools.size()); metrics.put("resolvedModel", "synthetic-reference");
            metrics.put("cachedInputTokens", 0); metrics.put("elapsedMillis", 0); metrics.put("systemPromptSha256", null);
            metrics.put("resolvedModelConsistent", true); metrics.put("usageComplete", true); metrics.put("requestFingerprintComplete", true);
            metrics.put("initialToolSchemaSha256", requests.get(0).toolSchemaSha256()); result.put("llmMetrics", metrics);
            result.put("plan", Map.of("schemaVersion", 1, "caseId", "E1", "profile", E1FrozenOracle.PROFILE,
                    "relayVersion", BenchmarkRelayProtocol.VERSION, "sourceSha256", sourceSha, "promptSha256", textSha256(oracle.prompt()),
                    "audit", new PlanRequestAudit.Snapshot(2, "PLAN", false, events, turns),
                    "scopedRequestFingerprints", new ScopedRequestFingerprints(1, "PLAN", requests)));
            return result;
        }
        private TaskNode node(int n, String description, Task.TaskType type, List<String> deps) {
            return new TaskNode("task_" + n, type, TextFingerprint.of(description), deps);
        }
        private void enter(int n, int thread) {
            String id = "task_" + n;
            observe(new TaskEntered(executionId, tick + 1, id, thread));
            var deps = n == 3 ? List.of(new DependencyInput("task_1", Task.TaskStatus.COMPLETED, TextFingerprint.of(outputs.get("task_1"))),
                    new DependencyInput("task_2", Task.TaskStatus.COMPLETED, TextFingerprint.of(outputs.get("task_2")))) : List.<DependencyInput>of();
            observe(new TaskInputPrepared(executionId, tick + 1, id, TextFingerprint.of(inputs.get(id)), 0, deps));
        }
        private void exit(int n) { observe(new TaskExited(executionId, tick + 1, "task_" + n, ExitKind.RETURNED, TextFingerprint.of(outputs.get("task_" + n)), null)); }
        private void observe(PlanExecutionObserver.Event event) {
            events.add(new PlanRequestAudit.ObservedEvent(events.size() + 1, ++tick, event.getClass().getSimpleName(), JSON.valueToTree(event)));
        }
        private String scope(int n) { return executionId + ":task_" + n; }
        private void turn(String scope, List<LlmClient.Message> messages, List<LlmClient.Tool> schemas, LlmClient.ChatResponse response) throws IOException {
            String user = messages.stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
            var binding = new ScopedRequestFingerprints.Binding(scope, textSha256(scope.equals("planner") ? oracle.prompt() : graph), textSha256(user));
            turns.add(new PlanRequestAudit.ProviderTurn(turns.size() + 1, events.size(), ++tick, ++tick, binding, messages, schemas, response));
            requests.add(new ScopedRequestFingerprints.Request(requests.size() + 1, binding,
                    hash(messages.stream().filter(m -> "system".equals(m.role())).toList()), hash(schemas)));
        }
        private void batch(int n, LlmClient.ToolCall call, String text) {
            var f = call.function();
            observe(new ToolBatchReturned(executionId, tick + 1, "task_" + n, 1, List.of(new ToolResult(0, call.id(), f.name(),
                    TextFingerprint.of(f.arguments()), TextFingerprint.of(text), true, false, 0))));
            tools.add(new BenchmarkRelayProtocol.WireToolExecution(tools.size() + 1, call.id(), f.name(), f.arguments(), text,
                    textSha256(text), text.length(), 1, false, true));
        }
        private String context(int n) {
            String description = List.of("LEFT", "RIGHT", "MERGE").get(n - 1);
            String value = "总目标：" + oracle.prompt() + "\n当前任务：" + description + "\n";
            if (n != 3) value += "依赖任务：无\n";
            else value += "依赖任务结果：\n- task_1 / LEFT / 状态=COMPLETED\n" + outputs.get("task_1")
                    + "\n- task_2 / RIGHT / 状态=COMPLETED\n" + outputs.get("task_2") + "\n";
            return value + "请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。";
        }
        private List<LlmClient.Message> initial(int n) { return List.of(message("system", "synthetic task " + n, null, List.of()), message("user", inputs.get("task_" + n), null, List.of())); }
        private List<LlmClient.Message> continued(int n, LlmClient.ChatResponse response, String text) {
            var messages = new ArrayList<>(initial(n)); messages.add(message("assistant", response.content(), null, response.toolCalls()));
            messages.add(message("tool", text, response.toolCalls().get(0).id(), List.of())); return List.copyOf(messages);
        }
        private static LlmClient.Message message(String role, String content, String id, List<LlmClient.ToolCall> calls) { return new LlmClient.Message(role, content, null, calls, id, List.of()); }
        private static LlmClient.ChatResponse text(String text) { return new LlmClient.ChatResponse("assistant", text, null, List.of(), 1, 1, 0, "synthetic-reference", true); }
        private static LlmClient.ChatResponse toolResponse(LlmClient.ToolCall call) { return new LlmClient.ChatResponse("assistant", "", null, List.of(call), 1, 1, 0, "synthetic-reference", true); }
        private static LlmClient.ToolCall call(String name, Object args) throws IOException { return new LlmClient.ToolCall("shared", new LlmClient.ToolCall.Function(name, JSON.writeValueAsString(args))); }
        private static String hash(Object value) throws IOException {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
