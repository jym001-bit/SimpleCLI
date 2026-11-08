package com.paicli.eval.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.*;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.*;
import com.paicli.observability.LangfuseTelemetry;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in three-mode read-only comparison; never part of the Maven test lifecycle. */
public final class CrossModeReadOnlyMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CALLS = 30, MAX_INPUT_BYTES = 80_000, MAX_OUTPUT_TOKENS = 8_192;
    private CrossModeReadOnlyMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("cases-file output-file required");
        System.setProperty("paicli.llm.retry.max-attempts", "1");
        System.setProperty("paicli.react.hard.max.iterations", "5");
        System.setProperty("paicli.react.token.budget", "100000");
        System.setProperty("paicli.plan.max.replans", "0");
        String model = "deepseek-flash";
        PaiCliConfig config = PaiCliConfig.load();
        String key = config.getApiKey("deepseek");
        if (key == null || key.isBlank()) throw new IllegalStateException("DeepSeek credential unavailable");
        LlmClient provider = new DeepSeekClient(key, model, MAX_OUTPUT_TOKENS);
        MeteredClient client = new MeteredClient(provider);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<Map<String,Object>> runs = new ArrayList<>();
        List<JsonNode> cases = new ArrayList<>();
        for (JsonNode node : JSON.readTree(Path.of(args[0]).toFile())) cases.add(node);
        try {
            for (JsonNode test : cases) for (String mode : List.of("react", "plan", "team")) {
                String runId = UUID.randomUUID().toString();
                long started = System.nanoTime();
                int beforeCalls = client.calls.get();
                long beforeInput = client.input.get(), beforeOutput = client.output.get(), beforeCache = client.cached.get();
                ReadOnlyRegistry registry = new ReadOnlyRegistry();
                registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
                String prompt = test.path("prompt").asText();
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("runId", runId); row.put("caseId", test.path("id").asText()); row.put("mode", mode);
                row.put("startedUtc", Instant.now().toString()); row.put("model", model);
                try {
                    var traced = LangfuseTelemetry.evaluate(runId, () -> LangfuseTelemetry.observeTurn(
                            "cross-mode-" + mode, null, () -> execute(mode, prompt, client, registry)));
                    ExecutionOutcome outcome = traced.value();
                    row.put("traceId", traced.traceId()); row.put("reason", outcome.reason().name());
                    row.put("answer", outcome.content());
                    boolean facts = true;
                    for (JsonNode value : test.path("facts"))
                        if (!outcome.content().contains(value.asText())) facts = false;
                    Set<String> paths = new HashSet<>();
                    for (var event : registry.events) if (event.successful() && "read_file".equals(event.name())) {
                        try { paths.add(JSON.readTree(event.argumentsJson()).path("path").asText()); }
                        catch (Exception ignored) { }
                    }
                    boolean files = true;
                    for (JsonNode path : test.path("requiredFiles")) if (!paths.contains(path.asText())) files = false;
                    row.put("factsMatched", facts); row.put("filesRead", files);
                    row.put("passed", outcome.completed() && facts && files && registry.denied.get() == 0);
                } catch (Exception error) {
                    row.put("reason", "EXECUTION_ERROR"); row.put("errorType", error.getClass().getSimpleName());
                    row.put("passed", false); row.put("traceId", null);
                }
                row.put("modelCalls", client.calls.get() - beforeCalls);
                row.put("inputTokens", client.input.get() - beforeInput);
                row.put("cachedInputTokens", client.cached.get() - beforeCache);
                row.put("outputTokens", client.output.get() - beforeOutput);
                row.put("toolCalls", registry.events.size()); row.put("deniedTools", registry.denied.get());
                row.put("durationMillis", (System.nanoTime() - started) / 1_000_000);
                runs.add(row);
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                        "kind", "cross-mode-readonly-evaluation", "model", model,
                        "maxCalls", MAX_CALLS, "maxInputBytesPerCall", MAX_INPUT_BYTES,
                        "maxOutputTokensPerCall", MAX_OUTPUT_TOKENS, "runs", runs));
                System.out.println(row.get("caseId") + " " + mode + " passed=" + row.get("passed")
                        + " calls=" + row.get("modelCalls"));
            }
        } finally { LangfuseTelemetry.shutdown(); }
    }

    private static ExecutionOutcome execute(String mode, String prompt, LlmClient client, ToolRegistry registry) {
        return switch (mode) {
            case "react" -> new Agent(client, registry).runWithOutcome(prompt);
            case "plan" -> new PlanExecuteAgent(client, registry, null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()).runWithOutcome(prompt);
            case "team" -> new AgentOrchestrator(client, registry, new com.paicli.memory.MemoryManager(client))
                    .runWithOutcome(prompt);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static final class ReadOnlyRegistry extends ToolRegistry {
        private final List<ToolExecutionResult> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger denied = new AtomicInteger();
        @Override public List<LlmClient.Tool> getToolDefinitions() {
            return super.getToolDefinitions().stream().filter(t -> "read_file".equals(t.name())).toList();
        }
        @Override public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
            List<ToolExecutionResult> result = new ArrayList<>();
            for (ToolInvocation call : calls) {
                if ("read_file".equals(call.name())) result.addAll(super.executeTools(List.of(call)));
                else { denied.incrementAndGet(); result.add(new ToolExecutionResult(call.id(), call.name(),
                        call.argumentsJson(), "read-only evaluation denied tool", 0, false, List.of(), false, List.of())); }
            }
            return result;
        }
        @Override public void onPolicyToolResults(List<ToolExecutionResult> results) { events.addAll(results); }
    }

    private static final class MeteredClient implements LlmClient {
        private final LlmClient delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong input = new AtomicLong(), output = new AtomicLong(), cached = new AtomicLong();
        MeteredClient(LlmClient delegate) { this.delegate = delegate; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            int size = JSON.writeValueAsBytes(List.of(messages, tools == null ? List.of() : tools)).length;
            if (size > MAX_INPUT_BYTES) throw new IOException("evaluation input byte limit reached");
            while (true) {
                int used = calls.get();
                if (used >= MAX_CALLS) throw new IOException("evaluation model-call limit reached");
                if (calls.compareAndSet(used, used + 1)) break;
            }
            ChatResponse response = delegate.chat(messages, tools, listener);
            if (response.usagePresent()) { input.addAndGet(response.inputTokens());
                cached.addAndGet(response.cachedInputTokens()); output.addAndGet(response.outputTokens()); }
            return response;
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
        @Override public boolean supportsTools() { return delegate.supportsTools(); }
        @Override public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
        @Override public boolean supportsImageInput() { return delegate.supportsImageInput(); }
        @Override public boolean looksLikeUnexecutedToolCall(String content) { return delegate.looksLikeUnexecutedToolCall(content); }
        @Override public String promptCacheMode() { return delegate.promptCacheMode(); }
        @Override public void cancelInFlightCalls() { delegate.cancelInFlightCalls(); }
    }
}
