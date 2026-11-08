package com.paicli.eval.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.observability.LangfuseTelemetry;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in real-model evaluation. Never called by Maven's unit-test lifecycle. */
public final class LiveHarnessMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SAFE_TOOLS = Set.of("read_file");

    private LiveHarnessMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--rejudge-l05".equals(args[0])) {
            rejudgeHistory(args);
            return;
        }
        Path casesFile = Path.of(args.length > 3 ? args[3] : "benchmarks/harness-live/cases.json");
        Path output = args.length > 0 ? Path.of(args[0]) : Path.of("target/harness-live/results.json");
        Set<String> selected = args.length > 1 && !args[1].isBlank()
                ? Set.of(args[1].split(",")) : Set.of();
        boolean parallel = args.length > 2 && "parallel".equalsIgnoreCase(args[2]);
        boolean capture = args.length > 4 && "capture-context".equals(args[4]);
        if (capture && (parallel || !selected.equals(Set.of("L05"))))
            throw new IllegalArgumentException("Context capture is limited to the isolated L05 fixture");
        System.setProperty("paicli.harness.execution", parallel ? "parallel" : "sequential");
        Path workspace = Path.of("target/harness-live/fixture").toAbsolutePath().normalize();
        prepareFixture(workspace);
        System.setProperty("paicli.memory.dir", Path.of("target/harness-live/memory").toAbsolutePath().toString());
        PaiCliConfig config = PaiCliConfig.load();
        LlmClient base = LlmClientFactory.create("deepseek", config.getStartupModel("deepseek"), config);
        if (base == null) throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");

        List<Map<String, Object>> results = new ArrayList<>();
        Instant started = Instant.now();
        try {
            List<JsonNode> selectedCases = new ArrayList<>();
            for (JsonNode test : JSON.readTree(casesFile.toFile())) {
                String id = test.path("id").asText();
                if (selected.isEmpty() || selected.contains(id)) selectedCases.add(test);
            }
            if (parallel) {
                var executor = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1,
                        Math.min(4, selectedCases.size())));
                try {
                    List<java.util.concurrent.Future<Map<String, Object>>> futures = selectedCases.stream()
                            .map(test -> executor.submit(() -> runCase(test, base, workspace, false))).toList();
                    for (var future : futures) results.add(future.get());
                } finally { executor.shutdownNow(); }
            } else {
                for (JsonNode test : selectedCases) results.add(runCase(test, base, workspace, capture));
            }
            for (Map<String, Object> result : results) {
                writeResult(output, started, base, results, casesFile);
                System.out.println(result.get("caseId") + " " + result.get("status") + " calls=" + result.get("modelCalls")
                        + " tools=" + result.get("toolCalls") + " trace="
                        + (result.get("traceId") == null ? "unavailable" : result.get("traceId")));
            }
        } finally {
            LangfuseTelemetry.shutdown();
            writeResult(output, started, base, results, casesFile);
        }
    }

    private static Map<String, Object> runCase(JsonNode test, LlmClient base, Path workspace, boolean capture) {
        String id = test.path("id").asText();
        String taskId = UUID.randomUUID().toString();
        MetricsClient client = new MetricsClient(base, capture || test.has("orderedRecovery"));
        SafeRegistry registry = new SafeRegistry(client);
        registry.setProjectPath(workspace.toString());
        Agent agent = new Agent(client, registry);
        agent.setRenderer(new QuietRenderer());
        agent.setReturnFinalResponseWhenStreamed(true);
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", id);
        result.put("taskId", taskId);
        result.put("category", test.path("category").asText());
        result.put("startedUtc", Instant.now().toString());
        try {
            LangfuseTelemetry.TracedResult<CaseExecution> traced = LangfuseTelemetry.evaluate(taskId,
                    () -> LangfuseTelemetry.observeTurn("live-evaluation", null,
                            () -> executeCase(test, agent)));
            CaseExecution execution = traced.value();
            result.put("traceId", traced.traceId());
            result.put("answer", execution.answer());
            result.put("compacted", execution.compacted());
            result.put("memorySeeded", execution.memorySeeded());
            result.put("compactionBeforeTokensEstimated", execution.beforeTokens());
            result.put("compactionAfterTokensEstimated", execution.afterTokens());
            result.put("compressionRatioEstimated", execution.beforeTokens() != null && execution.beforeTokens() > 0
                    ? 1d - (double) execution.afterTokens() / execution.beforeTokens() : null);
            result.put("summaryFactChecks", execution.summaryFactChecks());
            String judgement = judge(test, execution, registry.events);
            if (test.has("orderedRecovery")) {
                var verdict = OrderedRecoveryJudge.assess(test.get("orderedRecovery"), registry.recoveryEvents);
                result.put("orderedRecoveryVerdict", verdict);
                if ("passed".equals(judgement) && verdict.status() != OrderedRecoveryJudge.Status.PASSED)
                    judgement = "ordered recovery " + verdict.status() + ": " + verdict.reason();
            }
            result.put("status", judgement.equals("passed") ? "passed" : "failed");
            result.put("judgement", judgement);
            result.put("errorCategory", execution.answer().startsWith("❌ 调用 LLM 失败") ? "MODEL_IO_ERROR" : null);
        } catch (RuntimeException failure) {
            result.put("status", "error");
            result.put("judgement", "execution threw " + failure.getClass().getSimpleName());
            result.put("errorCategory", failure.getClass().getSimpleName());
            result.put("traceId", null);
        }
        result.put("modelCalls", client.calls);
        result.put("modelFailedCalls", client.failedCalls);
        result.put("modelUsageMissingCalls", client.missingUsage);
        result.put("inputTokens", client.missingUsage == 0 ? client.inputTokens : null);
        result.put("uncachedInputTokens", client.missingUsage == 0 ? client.uncachedInputTokens : null);
        result.put("outputTokens", client.missingUsage == 0 ? client.outputTokens : null);
        result.put("cachedInputTokens", client.missingUsage == 0 ? client.cachedInputTokens : null);
        result.put("tokenUsageSource", client.missingUsage == 0 ? "provider_response" : "incomplete");
        result.put("usageSamples", client.usageSamples);
        Double estimatedCost = estimateCost(base.getModelName(), client.usageSamples,
                startedAt);
        result.put("estimatedCostUsd", estimatedCost);
        result.put("costBasis", estimatedCost == null ? "unavailable"
                : "official DeepSeek V4.1 Flash peak rate snapshot; token counts from provider responses");
        result.put("toolCalls", registry.events.size());
        result.put("tools", registry.eventReports);
        if (capture) result.put("requests", client.requestReports);
        result.put("durationMillis", (System.nanoTime() - startNanos) / 1_000_000);
        result.put("provider", base.getProviderName());
        result.put("model", base.getModelName());
        result.put("configuration", Map.of("allowedTools", List.of("read_file"),
                "hardMaxIterations", test.path("hardMaxIterations").asInt(0)));
        return result;
    }

    private static CaseExecution executeCase(JsonNode test, Agent agent) {
        boolean memorySeeded = false;
        boolean compacted = false;
        Integer beforeTokens = null, afterTokens = null;
        Map<String, Boolean> summaryFactChecks = new LinkedHashMap<>();
        if (test.path("seedMemory").asBoolean(false)) {
            String outcome = agent.getMemoryManager().storeFact("当前项目测试代号是 NOVA-9142", "project").describe();
            memorySeeded = outcome != null && !outcome.isBlank();
        }
        if (test.path("manualCompaction").asBoolean(false)) {
            JsonNode turns = test.path("primingTurns");
            if (turns.isArray()) {
                for (JsonNode turn : turns) agent.run(turn.asText());
            }
            beforeTokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(agent.getConversationHistory());
            compacted = agent.compactHistoryNow().compacted();
            afterTokens = com.paicli.memory.TokenBudget.estimateMessagesTokens(agent.getConversationHistory());
            String summary = agent.getConversationHistory().stream().map(LlmClient.Message::content)
                    .filter(text -> text != null && text.startsWith("[已压缩的历史对话摘要]"))
                    .findFirst().orElse("");
            for (JsonNode fact : test.path("requiredSummaryFacts")) {
                summaryFactChecks.put(fact.asText(), summary.contains(fact.asText()));
            }
        }
        String previousLimit = System.getProperty("paicli.react.hard.max.iterations");
        int hardLimit = test.path("hardMaxIterations").asInt(0);
        if (hardLimit > 0) System.setProperty("paicli.react.hard.max.iterations", String.valueOf(hardLimit));
        try {
            String answer = agent.run(test.path("prompt").asText());
            return new CaseExecution(answer == null ? "" : answer.trim(), compacted, memorySeeded,
                    beforeTokens, afterTokens, summaryFactChecks);
        } finally {
            if (hardLimit > 0) {
                if (previousLimit == null) System.clearProperty("paicli.react.hard.max.iterations");
                else System.setProperty("paicli.react.hard.max.iterations", previousLimit);
            }
        }
    }

    private static String judge(JsonNode test, CaseExecution execution,
                                List<ToolRegistry.ToolExecutionResult> events) {
        String answer = execution.answer();
        if (answer.startsWith("❌ 调用 LLM 失败")) return "model IO error";
        if (test.has("expectedJson")) {
            try {
                if (!JSON.readTree(answer).equals(test.get("expectedJson"))) return "JSON value mismatch";
            } catch (IOException invalid) { return "final answer is not strict JSON"; }
        } else if ("PARTIAL".equals(test.path("expected").asText())) {
            if (!answer.startsWith("⚠️ 部分完成（") || !answer.contains("硬轮数上限"))
                return "iteration limit did not produce partial result";
        } else if (!test.path("expected").asText().equals(answer)) {
            return "exact answer mismatch";
        }
        if (events.size() < test.path("minToolCalls").asInt(0)) return "too few actual tool results";
        if (test.has("maxToolCalls") && events.size() > test.path("maxToolCalls").asInt())
            return "unexpected tool call";
        long failed = events.stream().filter(event -> !event.successful()).count();
        if (failed < test.path("minFailedToolCalls").asInt(0)) return "tool error path was not exercised";
        Set<String> paths = new LinkedHashSet<>();
        for (ToolRegistry.ToolExecutionResult event : events) paths.add(pathOf(event.argumentsJson()));
        for (JsonNode path : test.path("requiredPaths")) {
            if (!paths.contains(path.asText())) return "required file was not requested: " + path.asText();
        }
        if (test.path("manualCompaction").asBoolean(false) && !execution.compacted())
            return "compaction did not occur";
        if (execution.summaryFactChecks().containsValue(false)) return "required fact missing from summary";
        if (test.path("seedMemory").asBoolean(false) && !execution.memorySeeded())
            return "memory seed failed";
        return "passed";
    }

    private static String pathOf(String arguments) {
        try {
            String path = JSON.readTree(arguments).path("path").asText("");
            return path.replace('\\', '/');
        } catch (Exception invalid) { return ""; }
    }

    private static void prepareFixture(Path workspace) throws IOException {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("alpha.txt"), "ALPHA=17\n");
        Files.writeString(workspace.resolve("beta.txt"), "BETA=25\n");
        Files.writeString(workspace.resolve("fallback.txt"), "STATUS=available\n");
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 300; index++) longText.append("line-").append(index).append(": stable test context.\n");
        longText.append("MARKER=ORBIT-7315\n");
        Files.writeString(workspace.resolve("long.txt"), longText);
    }

    private static void writeResult(Path output, Instant started, LlmClient client,
                                    List<Map<String, Object>> results, Path casesFile) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "real-model-harness-evaluation");
        report.put("judgeVersion", "ordered-recovery-v2");
        report.put("startedUtc", started.toString());
        report.put("updatedUtc", Instant.now().toString());
        report.put("provider", client.getProviderName());
        report.put("model", client.getModelName());
        report.put("harnessVersion", System.getProperty("paicli.harness.version", "unknown"));
        report.put("executionMode", "parallel".equals(System.getProperty("paicli.harness.execution"))
                ? "parallel" : "sequential");
        report.put("datasetSha256", sha256(Files.readAllBytes(casesFile)));
        report.put("attempts", results);
        report.put("total", results.size());
        report.put("passed", results.stream().filter(result -> "passed".equals(result.get("status"))).count());
        Path temporary = Files.createTempFile(parent, "live-results-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
            Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Re-score saved evidence without invoking a model or overwriting historical reports. */
    private static void rejudgeHistory(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("--rejudge-l05 before.json after.json output.json");
        JsonNode test = null;
        for (JsonNode candidate : JSON.readTree(Path.of("benchmarks/harness-live/cases.json").toFile()))
            if ("L05".equals(candidate.path("id").asText())) test = candidate;
        if (test == null || !test.has("orderedRecovery")) throw new IllegalStateException("L05 ordered criteria missing");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            JsonNode report = JSON.readTree(Path.of(args[i]).toFile());
            for (JsonNode attempt : report.path("attempts")) {
                if (!"L05".equals(attempt.path("caseId").asText())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", args[i]); row.put("originalStatus", attempt.path("status").asText());
                row.put("traceId", attempt.path("traceId").asText());
                row.put("revised", rejudgeL05(test, attempt)); rows.add(row);
            }
        }
        Path output = Path.of(args[3]);
        if (Files.exists(output)) throw new IOException("Refusing to overwrite an existing rejudgement report");
        if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("judgeVersion", "ordered-recovery-v2", "results", rows));
    }

    static OrderedRecoveryJudge.Verdict rejudgeL05(JsonNode test, JsonNode attempt) throws IOException {
        Map<Integer, List<LlmClient.Message>> requests = new LinkedHashMap<>();
        for (JsonNode request : attempt.path("requests")) {
            List<LlmClient.Message> messages = new ArrayList<>();
            for (JsonNode message : request.path("messages")) messages.add(JSON.treeToValue(message, LlmClient.Message.class));
            requests.put(request.path("turn").asInt(), messages);
        }
        List<OrderedRecoveryJudge.Event> events = new ArrayList<>();
        for (JsonNode event : attempt.path("tools")) {
            int turn = event.path("decisionTurn").asInt();
            events.add(new OrderedRecoveryJudge.Event(event.path("callId").asText(null), event.path("name").asText(),
                    event.path("argumentsJson").asText(null), event.path("successful").asBoolean(),
                    event.path("result").asText(null), turn, requests.get(turn)));
        }
        if (!test.path("expected").asText().equals(attempt.path("answer").asText()))
            return new OrderedRecoveryJudge.Verdict(OrderedRecoveryJudge.Status.FAILED, "final answer mismatch");
        return OrderedRecoveryJudge.assess(test.get("orderedRecovery"), events);
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Double estimateCost(String model, List<Map<String, Integer>> samples, Instant started) {
        // Snapshot is intentionally date-scoped; future runs without a refreshed official price
        // snapshot report unavailable instead of silently applying stale prices.
        LocalDate runDate = started.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalTime runTime = started.atZone(java.time.ZoneOffset.UTC).toLocalTime();
        boolean peak = (runTime.isAfter(LocalTime.of(1, 0)) && runTime.isBefore(LocalTime.of(4, 0)))
                || (runTime.isAfter(LocalTime.of(6, 0)) && runTime.isBefore(LocalTime.of(10, 0)));
        boolean weekday = runDate.getDayOfWeek().getValue() <= 5;
        boolean supportedAlias = model != null && Set.of("deepseek-flash", "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp").contains(model.toLowerCase(java.util.Locale.ROOT));
        if (!runDate.equals(LocalDate.of(2026, 10, 26)) || !peak || !weekday || !supportedAlias
                || samples.isEmpty()) return null;
        double usd = 0;
        for (Map<String, Integer> usage : samples) {
            usd += usage.get("inputUncached") * 0.30 / 1_000_000d;
            usd += usage.get("inputCached") * 0.006 / 1_000_000d;
            usd += usage.get("output") * 1.20 / 1_000_000d;
        }
        return Math.round(usd * 1_000_000_000d) / 1_000_000_000d;
    }

    private record CaseExecution(String answer, boolean compacted, boolean memorySeeded,
                                 Integer beforeTokens, Integer afterTokens, Map<String, Boolean> summaryFactChecks) {}

    private static final class SafeRegistry extends ToolRegistry {
        private final List<ToolExecutionResult> events = new ArrayList<>();
        private final List<OrderedRecoveryJudge.Event> recoveryEvents = new ArrayList<>();
        private final List<Map<String, Object>> eventReports = new ArrayList<>();
        private final MetricsClient client;
        private SafeRegistry(MetricsClient client) { this.client = client; }

        @Override public List<LlmClient.Tool> getToolDefinitions() {
            return super.getToolDefinitions().stream().filter(tool -> SAFE_TOOLS.contains(tool.name())).toList();
        }

        @Override public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
            if (calls.stream().allMatch(call -> SAFE_TOOLS.contains(call.name()))) return super.executeTools(calls);
            List<ToolExecutionResult> denied = new ArrayList<>();
            for (ToolInvocation call : calls) {
                if (SAFE_TOOLS.contains(call.name())) denied.addAll(super.executeTools(List.of(call)));
                else denied.add(new ToolExecutionResult(call.id(), call.name(), call.argumentsJson(),
                        "评测工具范围拒绝此操作", 0, false, List.of(), false, List.of()));
            }
            return denied;
        }

        @Override public void onPolicyToolResults(List<ToolExecutionResult> results) {
            events.addAll(results);
            for (ToolExecutionResult event : results) {
                List<LlmClient.Message> decision = client.recordRequests
                        ? client.requestMessages.get(client.calls - 1) : null;
                recoveryEvents.add(new OrderedRecoveryJudge.Event(event.id(), event.name(), event.argumentsJson(),
                        event.successful(), event.result(), client.calls, decision));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", event.name()); row.put("path", pathOf(event.argumentsJson()));
                row.put("successful", event.successful()); row.put("timedOut", event.timedOut());
                row.put("elapsedMillis", event.elapsedMillis()); row.put("callId", event.id());
                row.put("argumentsJson", event.argumentsJson()); row.put("result", event.result());
                row.put("decisionTurn", client.calls);
                eventReports.add(row);
            }
        }
    }

    private static final class MetricsClient implements LlmClient {
        private final LlmClient delegate;
        private int calls;
        private int failedCalls;
        private int missingUsage;
        private long inputTokens;
        private long uncachedInputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private final List<Map<String, Integer>> usageSamples = new ArrayList<>();
        private final boolean recordRequests;
        private final List<List<Message>> requestMessages = new ArrayList<>();
        private final List<Map<String, Object>> requestReports = new ArrayList<>();

        private MetricsClient(LlmClient delegate, boolean recordRequests) {
            this.delegate = delegate; this.recordRequests = recordRequests;
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            calls++;
            Map<String, Object> request = new LinkedHashMap<>();
            if (recordRequests) {
                requestMessages.add(List.copyOf(messages));
                request.put("turn", calls); request.put("messages", List.copyOf(messages));
                request.put("tools", tools == null ? List.of() : List.copyOf(tools));
                request.put("messageTokensEstimated", com.paicli.memory.TokenBudget.estimateMessagesTokens(messages));
                requestReports.add(request);
            }
            try {
                ChatResponse response = delegate.chat(messages, tools, listener);
                if (recordRequests) request.put("response", response);
                if (response.usagePresent()) {
                    int input = Math.max(0, response.inputTokens());
                    int cached = Math.min(input, Math.max(0, response.cachedInputTokens()));
                    int output = Math.max(0, response.outputTokens());
                    inputTokens += input;
                    uncachedInputTokens += input - cached;
                    outputTokens += output;
                    cachedInputTokens += cached;
                    usageSamples.add(Map.of("inputTotal", input, "inputUncached", input - cached,
                            "inputCached", cached, "output", output));
                } else missingUsage++;
                return response;
            } catch (IOException failure) { failedCalls++; throw failure; }
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
        @Override public boolean supportsTools() { return delegate.supportsTools(); }
        @Override public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
        @Override public boolean supportsImageInput() { return delegate.supportsImageInput(); }
        @Override public boolean looksLikeUnexecutedToolCall(String content) {
            return delegate.looksLikeUnexecutedToolCall(content);
        }
        @Override public String promptCacheMode() { return delegate.promptCacheMode(); }
        @Override public void cancelInFlightCalls() { delegate.cancelInFlightCalls(); }
    }

    private static final class QuietRenderer implements Renderer {
        private final PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        @Override public void start() {}
        @Override public void close() { sink.close(); }
        @Override public boolean rendersReasoning() { return false; }
        @Override public PrintStream stream() { return sink; }
        @Override public void appendToolCalls(List<LlmClient.ToolCall> calls) {}
        @Override public void appendDiff(String path, String before, String after) {}
        @Override public void updateStatus(StatusInfo status) {}
        @Override public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("Live evaluation has no interactive approval");
        }
        @Override public int openPalette(String title, List<String> items) { return -1; }
    }
}
