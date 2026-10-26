package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.agent.AgentRole;
import com.paicli.agent.TeamExecutionObserver;
import com.paicli.eval.benchmark.ScopedRequestFingerprints;
import com.paicli.eval.benchmark.relay.TeamRequestAudit;
import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.eval.benchmark.team.E2FrozenOracle;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

import static com.paicli.agent.TeamExecutionObserver.TextFingerprint;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.textSha256;

/** Deterministic private E2 source/reference prototype; catalog registration is not production admission. */
final class E2CaseMaterializer {
    static final List<String> RUNTIME_PATHS = List.of("validators/final/_private/e2_team_verify.py");
    private static final ObjectMapper JSON = new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String PLANNER_PLAN = "{\"steps\":[{\"id\":\"c\",\"description\":\"CODE\",\"type\":\"FILE_WRITE\",\"dependencies\":[]},"
            + "{\"id\":\"t\",\"description\":\"TEST\",\"type\":\"FILE_WRITE\",\"dependencies\":[]},"
            + "{\"id\":\"d\",\"description\":\"DOC\",\"type\":\"FILE_WRITE\",\"dependencies\":[\"c\",\"t\"]}]}";
    private static final List<String> STEP_DESCRIPTIONS = List.of("CODE", "TEST", "DOC");
    private static final List<String> STEP_PATHS = List.of("src/triage.py", "tests/test_triage.py", "docs/notes.md");
    private E2CaseMaterializer() { }

    /** Package-local prototype gate: new, empty, canonical, owner-only and outside every Git tree. */
    static void materializePrototype(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        Path root = writer.root();
        if (!"E2".equals(variant.caseId()) || !root.equals(root.toRealPath()) || Files.isSymbolicLink(root)
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("E2 prototype requires a canonical private root");
        for (Path p = root; p != null; p = p.getParent()) for (String vcs : List.of(".git", ".hg", ".svn"))
            if (Files.exists(p.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("E2 prototype must stay outside version control");
        try (var entries = Files.list(root)) { if (entries.findAny().isPresent()) throw new IOException("E2 prototype root must be empty"); }
        var oracle = oracle(variant);
        writer.text("prompts/final/E2.md", oracle.prompt());
        writer.executable("validators/final/E2", wrapper());
        materialize(writer, oracle);
        var scoring = scoring(FinalCaseContractCompiler.sha256(root.resolve("validators/final/E2")),
                ImplementedCaseMaterializers.digestFiles(root, RUNTIME_PATHS));
        writer.json("validators/final/_private/scoring-contracts/E2.json", scoring);
        writer.json("e2-prototype-manifest.json", Map.ofEntries(
                Map.entry("schemaVersion", 1), Map.entry("caseId", "E2"), Map.entry("variantId", variant.variantId()),
                Map.entry("caseWeight", 4), Map.entry("toolProfile", "LOCAL_COMMAND"),
                Map.entry("implementationStatus", "IMPLEMENTED"), Map.entry("runnerIntegrationStatus", "NOT_INTEGRATED"),
                Map.entry("publicationEligible", false), Map.entry("realProviderCalls", 0),
                Map.entry("referenceOrigin", "SYNTHETIC_TRANSCRIPT_NOT_RUNTIME_OR_MODEL_EVIDENCE"),
                Map.entry("sourceSha256", FinalCaseContractCompiler.sha256(root.resolve(E2FrozenOracle.PATH))),
                Map.entry("missing", List.of("complete-28-case-suite", "production-batch-admission",
                        "command-provenance-not-integrated", "reference-is-synthetic-not-provider-evidence"))));
    }

    /** Called after the catalog skeleton wrote the exact prompt and wrapper; never overwrites either. */
    static void materializeRegistered(PrivateSourceWriter writer, SeededVariant variant) throws IOException {
        var oracle = oracle(variant);
        if (!"E2".equals(variant.caseId()) || !oracle.prompt().equals(Files.readString(writer.root().resolve("prompts/final/E2.md"))))
            throw new IOException("E2 catalog prompt differs from frozen source");
        materialize(writer, oracle);
    }

    static E2FrozenOracle oracle(SeededVariant variant) {
        List<int[]> thresholds = new ArrayList<>();
        for (int i = 0; i < 3; i++) thresholds.add(new int[] {variant.number(i * 3 + 1, 5, 400), i});
        thresholds.sort(Comparator.comparingInt(row -> row[0]));
        StringBuilder rules = new StringBuilder("# classify(amount) 规格说明\n\n"
                + "`classify(amount)` 按下表从高到低返回层级名（小写）：\n\n"
                + "| 下界（含） | 层级 |\n|---|---|\n");
        for (int[] row : thresholds) rules.append("| ").append(row[0]).append(" | tier_").append(row[1]).append(" |\n");
        rules.append("\n低于最低下界的输入返回 `none`。函数必须为纯 Python 3，不依赖第三方库。\n");
        StringBuilder examples = new StringBuilder();
        for (int[] row : thresholds) examples.append(row[0]).append(" tier_").append(row[1]).append('\n');
        String check = "#!/bin/sh\nset -eu\n[ \"$#\" -eq 1 ] || { echo \"usage: check.sh <module.py>\" >&2; exit 2; }\n"
                + "python3 - \"$1\" <<'PY'\nimport importlib.util, sys\nspec = importlib.util.spec_from_file_location(\"triage\", sys.argv[1])\n"
                + "module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)\n"
                + "rows = \"\"\"" + examples + "\"\"\"\nfor line in rows.strip().splitlines():\n    bound, tier = line.split()\n"
                + "    got = module.classify(int(bound))\n    assert got == tier, (bound, got, tier)\n"
                + "lowest = int(rows.strip().splitlines()[0].split()[0])\n"
                + "assert module.classify(lowest - 1) == \"none\"\nprint(\"check ok\")\nPY\n";
        var files = new LinkedHashMap<String, String>();
        files.put("README.md", "# E2 Team 代码/测试/文档协作\n\n按 spec/rules.md 实现 classify(amount)，"
                + "为它编写测试并用 check.sh 真实验证，最后把代码与测试结果整理进文档。\n\nVariant: "
                + variant.variantId() + "\n");
        files.put("spec/rules.md", rules.toString());
        files.put("check.sh", check);
        return new E2FrozenOracle(1, "E2", "LOCAL_COMMAND", E2FrozenOracle.PROFILE, variant.variantId(), files,
                List.of(new E2FrozenOracle.ExpectedWrite("src/triage.py", "WORKER"),
                        new E2FrozenOracle.ExpectedWrite("tests/test_triage.py", "WORKER"),
                        new E2FrozenOracle.ExpectedWrite("docs/notes.md", "WORKER")),
                true, List.of("src/triage.py", "tests/test_triage.py", "docs/notes.md"), 3,
                "docs/notes.md", true);
    }

    static String wrapper() {
        return """
                #!/bin/sh
                set -eu
                [ "$#" -eq 2 ] || exit 2
                validator_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
                exec python3 -B "$validator_root/_private/e2_team_verify.py" "$validator_root/_private/oracles/E2.json" "$1" "$2"
                """;
    }

    private static void materialize(PrivateSourceWriter writer, E2FrozenOracle oracle) throws IOException {
        for (String path : RUNTIME_PATHS) try (var in = E2CaseMaterializer.class.getResourceAsStream("/benchmark/" + path.substring(path.lastIndexOf('/') + 1))) {
            if (in == null) throw new IOException("E2 verifier resource unavailable");
            writer.text(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (var entry : oracle.files().entrySet()) {
            writer.text("fixtures/final/E2/" + entry.getKey(), entry.getValue());
            writer.text("references/final/E2/workspace/" + entry.getKey(), entry.getValue());
        }
        for (String path : oracle.requireWorkspaceFiles()) {
            writer.text("references/final/E2/workspace/" + path, referenceOutput(path));
        }
        Path source = writer.json(E2FrozenOracle.PATH, oracle);
        if (!oracle.equals(E2FrozenOracle.parse(Files.readAllBytes(source)))) throw new IOException("E2 source read-back mismatch");
        var reference = new Reference(oracle);
        writer.json("references/final/E2/evidence.json", reference.envelope(FinalCaseContractCompiler.sha256(source)));
    }

    private static String referenceOutput(String path) {
        return switch (path) {
            case "src/triage.py" -> "def classify(amount):\n    # seeded tier table per spec/rules.md\n    return \"none\"\n";
            case "tests/test_triage.py" -> "from src.triage import classify\n\ndef test_tiers():\n    assert classify(5) != \"none\"\n";
            default -> "## CODE\nCODE result\n\n## TEST\nTEST result\n";
        };
    }

    static ScoringContract scoring(String verifierSha, String runtimeSha) {
        return new ScoringContract(ScoringContract.CURRENT_SCHEMA_VERSION, "E2", 100,
                List.of("lifecycle", "attribution_consistent", "expected_writes", "no_conflicts",
                        "workspace_files", "reviews_approved", "doc_consumes_dependencies",
                        "no_unrequested_files")
                        .stream().map(id -> new ScoringContract.AssertionRule("E2." + id, "strictTask", true)).toList(),
                List.of("workspace_mutation", "forbidden_tool_or_path").stream().map(id -> new ScoringContract.HardGateRule("E2." + id)).toList(),
                List.of(new ScoringContract.ComponentRule("strictTask", 100, ScoreSource.DETERMINISTIC)), verifierSha, runtimeSha);
    }

    /** Fabricated, deterministic reference for testing the verifier, never labelled as an observed run. */
    private static final class Reference {
        final E2FrozenOracle oracle;
        final String runId;
        final List<TeamRequestAudit.ObservedEvent> events = new ArrayList<>();
        final List<TeamRequestAudit.ProviderAttempt> attempts = new ArrayList<>();
        final List<TeamRequestAudit.WriteAttribution> writes = new ArrayList<>();
        final List<ScopedRequestFingerprints.Request> requests = new ArrayList<>();
        final Map<String, String> inputs = new LinkedHashMap<>();
        final Map<String, String> stepResults = new LinkedHashMap<>();
        long tick;

        Reference(E2FrozenOracle oracle) throws IOException {
            this.oracle = oracle;
            this.runId = UUID.nameUUIDFromBytes(("e2-synthetic-reference/" + oracle.variantId()).getBytes(StandardCharsets.UTF_8)).toString();
            List<TeamExecutionObserver.StepNode> steps = List.of(stepNode(1), stepNode(2), stepNode(3));
            for (int step = 1; step <= 3; step++)
                stepResults.put("step_" + step, STEP_DESCRIPTIONS.get(step - 1) + " result");
            var plannerIdentity = identity("planner", null, 1, AgentRole.PLANNER);
            inputs.put("planner", "请为以下任务制定执行计划：\n" + oracle.prompt());
            for (int step = 1; step <= 3; step++) {
                String context = "总任务上下文：\n" + (step == 3
                        ? "已完成的依赖步骤 [step_1]: CODE\n结果：" + stepResults.get("step_1")
                          + "\n已完成的依赖步骤 [step_2]: TEST\n结果：" + stepResults.get("step_2") + "\n\n" : "");
                inputs.put("step_" + step, context + "\n\n当前任务：" + STEP_DESCRIPTIONS.get(step - 1));
            }
            observe(new TeamExecutionObserver.RunStarted(runId, ++tick,
                    textValue(oracle.prompt()), textValue(oracle.prompt()), true, 3, 2));
            enter(plannerIdentity, 4);
            respond(plannerIdentity, PLANNER_PLAN, List.of());
            exit(plannerIdentity, textValue(PLANNER_PLAN));
            observe(new TeamExecutionObserver.PlanPrepared(runId, ++tick, plannerIdentity.activationId(),
                    textValue(PLANNER_PLAN), steps));
            for (int step = 1; step <= 3; step++) {
                String stepId = "step_" + step;
                List<TeamExecutionObserver.DependencyInput> dependencies = step == 3
                        ? List.of(new TeamExecutionObserver.DependencyInput("step_1", TeamExecutionObserver.ProductStatus.COMPLETED,
                                        textValue(stepResults.get("step_1")), textValue(stepResults.get("step_1")), false),
                                new TeamExecutionObserver.DependencyInput("step_2", TeamExecutionObserver.ProductStatus.COMPLETED,
                                        textValue(stepResults.get("step_2")), textValue(stepResults.get("step_2")), false))
                        : List.of();
                observe(new TeamExecutionObserver.StepEntered(runId, ++tick, stepId, step, 90L + step,
                        textValue(inputs.get(stepId)), dependencies));
                var worker = identity("worker-" + step, stepId, 1, AgentRole.WORKER);
                enter(worker, 6);
                String path = STEP_PATHS.get(step - 1);
                String body = switch (step) {
                    case 1 -> "def classify(amount):\n    # seeded tier table per spec/rules.md\n    return \"none\"\n";
                    case 2 -> "from src.triage import classify\n\ndef test_tiers():\n    assert classify(5) != \"none\"\n";
                    default -> "## CODE\n" + stepResults.get("step_1") + "\n\n## TEST\n" + stepResults.get("step_2") + "\n";
                };
                var call = new LlmClient.ToolCall("tool-" + step, new LlmClient.ToolCall.Function("write_file",
                        JSON.writeValueAsString(Map.of("path", path, "content", body))));
                respond(worker, "", List.of(call));
                observe(new TeamExecutionObserver.ToolBatchReturned(worker, ++tick, 1,
                        List.of(new TeamExecutionObserver.ToolResult(0, call.id(), "write_file",
                                textValue(call.function().arguments()), textValue("文件已写入: " + path),
                                true, false, 0))));
                writes.add(new TeamRequestAudit.WriteAttribution(writes.size() + 1, scopeOf(worker),
                        AgentRole.WORKER, stepId, 1, call.id(), path, true));
                String finalContent = step == 3 ? body : stepResults.get(stepId);
                respond(worker, finalContent, List.of());
                exit(worker, textValue(finalContent));
                var reviewer = identity("reviewer-" + step, stepId, 1, AgentRole.REVIEWER);
                enter(reviewer, 2);
                String review = "{\"approved\":true,\"summary\":\"synthetic review\",\"issues\":[]}";
                respond(reviewer, review, List.of());
                exit(reviewer, textValue(review));
                observe(new TeamExecutionObserver.ReviewEvaluated(runId, ++tick, stepId, 1,
                        worker.activationId(), reviewer.activationId(),
                        TeamExecutionObserver.ReviewDecision.APPROVED, textValue("synthetic review"), textValue("")));
                observe(new TeamExecutionObserver.StepExited(runId, ++tick, stepId,
                        TeamExecutionObserver.ProductStatus.COMPLETED, TeamExecutionObserver.StepExitReason.APPROVED,
                        worker.activationId(), reviewer.activationId(), textValue(finalContent), null));
            }
            observe(new TeamExecutionObserver.RunExited(runId, ++tick,
                    TeamExecutionObserver.RunExitReason.COMPLETED,
                    textValue("synthetic team run completed"), null));
        }

        Map<String, Object> envelope(String sourceSha) {
            var snapshot = new TeamRequestAudit.Snapshot(1, "TEAM", false, events, attempts, writes, List.of());
            var fingerprints = new ScopedRequestFingerprints(2, "TEAM", requests);
            var result = new LinkedHashMap<String, Object>();
            result.put("schemaVersion", 5);
            result.put("caseId", "E2");
            result.put("repeat", 1);
            result.put("mode", "team");
            result.put("toolProfile", "LOCAL_COMMAND");
            result.put("answer", "synthetic reference; not a model run");
            result.put("toolExecutions", List.of());
            for (String kind : List.of("Workspace", "Bundle")) for (String field : List.of("TreeSha256", "FileCount", "TotalBytes"))
                result.put("verifier" + kind + field, null);
            var metrics = new LinkedHashMap<String, Object>();
            metrics.put("calls", attempts.size());
            metrics.put("successfulCalls", attempts.size());
            metrics.put("inputTokens", attempts.size());
            metrics.put("outputTokens", attempts.size());
            metrics.put("toolCalls", writes.size());
            metrics.put("resolvedModel", "synthetic-reference");
            metrics.put("cachedInputTokens", 0);
            metrics.put("elapsedMillis", 0);
            metrics.put("systemPromptSha256", null);
            metrics.put("resolvedModelConsistent", true);
            metrics.put("usageComplete", true);
            metrics.put("requestFingerprintComplete", true);
            metrics.put("initialToolSchemaSha256", requests.get(0).toolSchemaSha256());
            metrics.put("scopedRequestFingerprints", fingerprints);
            result.put("llmMetrics", metrics);
            result.put("teamAudit", JSON.convertValue(snapshot, JsonNode.class));
            result.put("scopedRequestFingerprints", fingerprints);
            result.put("sourceSha256", sourceSha);
            result.put("promptSha256", textSha256(oracle.prompt()));
            return result;
        }

        private TeamExecutionObserver.StepNode stepNode(int n) {
            return new TeamExecutionObserver.StepNode("step_" + n, textValue(STEP_DESCRIPTIONS.get(n - 1)),
                    textValue("FILE_WRITE"), List.of());
        }

        private TeamExecutionObserver.ActivationIdentity identity(String tag, String stepId, int attempt, AgentRole role) {
            return new TeamExecutionObserver.ActivationIdentity(runId, stepId, attempt, role,
                    UUID.nameUUIDFromBytes(("e2/" + oracle.variantId() + "/" + tag).getBytes(StandardCharsets.UTF_8)).toString(),
                    UUID.nameUUIDFromBytes(("actor/" + oracle.variantId() + "/" + tag).getBytes(StandardCharsets.UTF_8)).toString(),
                    0);
        }

        private String scopeOf(TeamExecutionObserver.ActivationIdentity identity) { return "team:" + identity.activationId(); }

        private void enter(TeamExecutionObserver.ActivationIdentity identity, int messagesBefore) {
            observe(new TeamExecutionObserver.ActivationEntered(identity, ++tick, 91L, messagesBefore));
            String userText = inputs.get(identity.stepId() == null ? "planner" : identity.stepId());
            observe(new TeamExecutionObserver.ActivationInputPrepared(identity, ++tick,
                    identity.role() == AgentRole.PLANNER ? 1 : messagesBefore, textValue(userText), 0));
        }

        private void exit(TeamExecutionObserver.ActivationIdentity identity, TextFingerprint result) {
            observe(new TeamExecutionObserver.ActivationExited(identity, ++tick,
                    TeamExecutionObserver.ExitKind.NORMAL, result, null));
        }

        private void respond(TeamExecutionObserver.ActivationIdentity identity,
                             String content, List<LlmClient.ToolCall> toolCalls) throws IOException {
            String userText = inputs.get(identity.stepId() == null ? "planner" : identity.stepId());
            var system = new LlmClient.Message("system", "synthetic " + identity.role().name().toLowerCase(Locale.ROOT), null, List.of(), null, List.of());
            var user = new LlmClient.Message("user", userText, null, List.of(), null, List.of());
            var messages = new ArrayList<LlmClient.Message>(List.of(system, user));
            if (!toolCalls.isEmpty()) {
                messages.add(new LlmClient.Message("assistant", "", null, toolCalls, null, List.of()));
                messages.add(new LlmClient.Message("tool", "文件已写入", toolCalls.get(0).id(), List.of(), null, List.of()));
            }
            var response = new LlmClient.ChatResponse("assistant", content, null, toolCalls, 1, 1, 0, "synthetic-reference", true);
            String scope = scopeOf(identity);
            var binding = new ScopedRequestFingerprints.Binding(scope, textSha256(oracle.prompt()), textSha256(userText));
            attempts.add(new TeamRequestAudit.ProviderAttempt(attempts.size() + 1, identity, scope, binding,
                    true, true, response, response, null, ++tick, ++tick, events.size(), List.copyOf(messages),
                    toolCalls.isEmpty() ? List.of() : List.of(new LlmClient.Tool("write_file", "synthetic", JSON.createObjectNode()))));
            requests.add(new ScopedRequestFingerprints.Request(requests.size() + 1, binding,
                    textSha256(system.content()), textSha256("write_file")));
        }

        private void observe(TeamExecutionObserver.Event event) {
            events.add(new TeamRequestAudit.ObservedEvent(events.size() + 1, ++tick,
                    event.getClass().getSimpleName(), JSON.valueToTree(event)));
        }

        private static TextFingerprint textValue(String text) { return TextFingerprint.of(text); }
    }
}
