package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records benchmark-safe LLM call metadata without persisting prompt, response,
 * reasoning, tool arguments, or credentials. The exact system prompt and exposed
 * tool schema are represented only by canonical SHA-256 digests. Full conversational
 * evidence stays in the separately protected {@code ConversationLedger}.
 */
public final class TracingLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final LlmClient delegate;
    private final Path traceFile;
    private final int requestedContextWindowCapTokens;
    private final int effectiveContextWindowCapTokens;
    private final int maxOutputTokensPerCall;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong maxObservedInputTokensPerCall = new AtomicLong();
    private final AtomicLong maxObservedTotalTokensPerCall = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong elapsedMillis = new AtomicLong();
    private final AtomicInteger failedCalls = new AtomicInteger();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>("");
    private final AtomicReference<String> lastFailureType = new AtomicReference<>("");
    private final AtomicReference<String> evaluationInvalidFailureType =
            new AtomicReference<>("");
    private int successfulCalls;
    private int missingResolvedModelCalls;
    private int missingUsageCalls;
    private int contextCapViolationCalls;
    private final Set<String> resolvedModels = new LinkedHashSet<>();
    private int requestEvidenceCalls;
    private int missingSystemPromptCalls;
    private final Set<String> systemPromptDigests = new LinkedHashSet<>();
    private String initialToolSchemaDigest;
    private final com.paicli.eval.benchmark.relay.PlanRequestAudit planAudit;
    private final com.paicli.eval.benchmark.relay.TeamRequestAudit teamAudit;
    private final java.util.List<ScopedRequestFingerprints.Request> scopedRequests = new java.util.ArrayList<>();

    public TracingLlmClient(LlmClient delegate, Path traceFile) throws IOException {
        this(delegate, traceFile, null);
    }

    /** Plan attribution is supplied by the host relay, never inferred from model text or a free-form label. */
    public TracingLlmClient(LlmClient delegate, Path traceFile,
                            com.paicli.eval.benchmark.relay.PlanRequestAudit planAudit) throws IOException {
        this(delegate, traceFile, planAudit, null);
    }

    /** A TEAM audit must be the same host-owned instance used by the relay, not a supplied scope label. */
    public TracingLlmClient(LlmClient delegate, Path traceFile,
                            com.paicli.eval.benchmark.relay.PlanRequestAudit planAudit,
                            com.paicli.eval.benchmark.relay.TeamRequestAudit teamAudit) throws IOException {
        if (planAudit != null && teamAudit != null) throw new IllegalArgumentException("conflicting mode audits");
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (traceFile == null) {
            throw new IllegalArgumentException("traceFile must not be null");
        }
        this.delegate = delegate;
        this.planAudit = planAudit;
        this.teamAudit = teamAudit;
        if (delegate instanceof ContextWindowCappedLlmClient capped) {
            this.requestedContextWindowCapTokens = capped.requestedContextWindowCapTokens();
            this.effectiveContextWindowCapTokens = capped.effectiveContextWindowCapTokens();
            this.maxOutputTokensPerCall = capped.maxOutputTokensPerCall();
        } else {
            // A provider capability is not proof that a request-scoped hard gate was installed.
            this.requestedContextWindowCapTokens = 0;
            this.effectiveContextWindowCapTokens = 0;
            this.maxOutputTokensPerCall = 0;
        }
        this.traceFile = traceFile.toAbsolutePath().normalize();
        Path parent = this.traceFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(this.traceFile)) {
            Files.createFile(this.traceFile);
        }
        try {
            Files.setPosixFilePermissions(this.traceFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms still get a regular private run artifact directory.
        }
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return trace(messages, tools, null);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return trace(messages, tools, listener == null ? StreamListener.NO_OP : listener);
    }

    private ChatResponse trace(List<Message> messages,
                               List<Tool> tools,
                               StreamListener listener) throws IOException {
        int call = calls.incrementAndGet();
        RequestFingerprint requestFingerprint = fingerprint(messages, tools);
        recordRequestEvidence(call, requestFingerprint);
        ScopedRequestFingerprints.Binding scope = planAudit != null ? planAudit.currentBinding()
                : teamAudit != null ? teamAudit.currentBinding() : null;
        if (scope != null && requestFingerprint.systemPromptSha256() != null) {
            synchronized (this) {
                scopedRequests.add(new ScopedRequestFingerprints.Request(call, scope,
                        requestFingerprint.systemPromptSha256(), requestFingerprint.toolSchemaSha256()));
            }
        }
        long started = System.nanoTime();
        try {
            ChatResponse response = listener == null
                    ? delegate.chat(messages, tools)
                    : delegate.chat(messages, tools, listener);
            long elapsed = elapsedSince(started);
            inputTokens.addAndGet(Math.max(0, response.inputTokens()));
            if (response.usagePresent()) {
                long observedInput = Math.max(0, response.inputTokens());
                long observedOutput = Math.max(0, response.outputTokens());
                maxObservedInputTokensPerCall.accumulateAndGet(observedInput, Math::max);
                maxObservedTotalTokensPerCall.accumulateAndGet(
                        saturatedAdd(observedInput, observedOutput), Math::max);
            }
            outputTokens.addAndGet(Math.max(0, response.outputTokens()));
            cachedInputTokens.addAndGet(Math.max(0, response.cachedInputTokens()));
            int responseToolCalls = response.toolCalls() == null ? 0 : response.toolCalls().size();
            toolCalls.addAndGet(responseToolCalls);
            elapsedMillis.addAndGet(elapsed);
            recordResponseEvidence(response);
            Map<String, Object> event = baseEvent(call, messages, tools, elapsed);
            event.put("status", "SUCCESS");
            event.put("returnedTools", responseToolNames(response));
            event.put("inputTokens", Math.max(0, response.inputTokens()));
            event.put("outputTokens", Math.max(0, response.outputTokens()));
            event.put("cachedInputTokens", Math.max(0, response.cachedInputTokens()));
            event.put("resolvedModel", normalizedResolvedModel(response.resolvedModel()));
            event.put("usagePresent", response.usagePresent());
            event.put("systemPromptSha256", requestFingerprint.systemPromptSha256());
            event.put("toolSchemaSha256", requestFingerprint.toolSchemaSha256());
            if (scope != null) event.put("executionScope", scope);
            append(event);
            return response;
        } catch (IOException e) {
            failedCalls.incrementAndGet();
            if (e instanceof ContextWindowCappedLlmClient.ContextWindowCapExceededException cap) {
                synchronized (this) {
                    contextCapViolationCalls++;
                }
                if (cap.observedInputTokens() >= 0) {
                    maxObservedInputTokensPerCall.accumulateAndGet(
                            cap.observedInputTokens(), Math::max);
                }
                if (cap.observedTotalTokens() >= 0) {
                    maxObservedTotalTokensPerCall.accumulateAndGet(
                            cap.observedTotalTokens(), Math::max);
                }
            }
            lastErrorMessage.set(e.getMessage() == null ? "" : e.getMessage());
            String failureType = BenchmarkProviderFailureClassifier.classify(e);
            lastFailureType.set(failureType);
            if (BenchmarkProviderEvidenceGate.isEvaluationInvalidFailureType(failureType)) {
                evaluationInvalidFailureType.compareAndSet("", failureType);
            }
            long elapsed = elapsedSince(started);
            elapsedMillis.addAndGet(elapsed);
            Map<String, Object> event = baseEvent(call, messages, tools, elapsed);
            event.put("status", "ERROR");
            event.put("errorType", e.getClass().getSimpleName());
            event.put("systemPromptSha256", requestFingerprint.systemPromptSha256());
            event.put("toolSchemaSha256", requestFingerprint.toolSchemaSha256());
            append(event);
            throw e;
        }
    }

    private Map<String, Object> baseEvent(int call,
                                          List<Message> messages,
                                          List<Tool> tools,
                                          long elapsed) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("call", call);
        event.put("provider", getProviderName());
        event.put("model", getModelName());
        event.put("messageCount", messages == null ? 0 : messages.size());
        event.put("exposedTools", toolNames(tools));
        event.put("elapsedMillis", elapsed);
        return event;
    }

    private synchronized void append(Map<String, Object> event) throws IOException {
        Files.writeString(
                traceFile,
                MAPPER.writeValueAsString(event) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static List<String> toolNames(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream().map(Tool::name).sorted().toList();
    }

    private static List<String> responseToolNames(ChatResponse response) {
        if (response == null || response.toolCalls() == null || response.toolCalls().isEmpty()) {
            return List.of();
        }
        return response.toolCalls().stream()
                .map(call -> call.function().name())
                .sorted()
                .toList();
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private synchronized void recordResponseEvidence(ChatResponse response) {
        successfulCalls++;
        String resolvedModel = normalizedResolvedModel(response.resolvedModel());
        if (resolvedModel == null) {
            missingResolvedModelCalls++;
        } else {
            resolvedModels.add(resolvedModel);
        }
        if (!response.usagePresent()) {
            missingUsageCalls++;
        }
    }

    private synchronized void recordRequestEvidence(int call, RequestFingerprint fingerprint) {
        requestEvidenceCalls++;
        if (fingerprint.systemPromptSha256() == null) {
            missingSystemPromptCalls++;
        } else {
            systemPromptDigests.add(fingerprint.systemPromptSha256());
        }
        if (call == 1) {
            initialToolSchemaDigest = fingerprint.toolSchemaSha256();
        }
    }

    private static RequestFingerprint fingerprint(List<Message> messages, List<Tool> tools)
            throws IOException {
        List<Message> systemMessages = messages == null
                ? List.of()
                : messages.stream()
                .filter(message -> message != null && "system".equals(message.role()))
                .toList();
        String systemDigest = systemMessages.isEmpty() ? null : sha256(systemMessages);
        return new RequestFingerprint(systemDigest, sha256(canonicalTools(tools)));
    }

    private static List<ToolFingerprint> canonicalTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .map(tool -> {
                    Tool value = Objects.requireNonNull(tool, "tool");
                    JsonNode parameters = value.parameters() == null
                            ? MAPPER.nullNode()
                            : canonicalJson(value.parameters());
                    return new ToolFingerprint(value.name(), value.description(), parameters);
                })
                .toList();
    }

    private static JsonNode canonicalJson(JsonNode value) {
        if (value.isObject()) {
            var object = MAPPER.createObjectNode();
            List<String> names = new java.util.ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> object.set(name, canonicalJson(value.get(name))));
            return object;
        }
        if (value.isArray()) {
            var array = MAPPER.createArrayNode();
            value.forEach(element -> array.add(canonicalJson(element)));
            return array;
        }
        return value.deepCopy();
    }

    private static String sha256(Object value) throws IOException {
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(value);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String normalizedResolvedModel(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() || normalized.length() > 256 ? null : normalized;
    }

    public synchronized Metrics metrics() {
        boolean modelConsistent = successfulCalls > 0
                && missingResolvedModelCalls == 0
                && resolvedModels.size() == 1;
        ScopedRequestFingerprints scoped = planAudit != null
                ? new ScopedRequestFingerprints(1, "PLAN", scopedRequests)
                : teamAudit != null ? new ScopedRequestFingerprints(2, "TEAM", scopedRequests) : null;
        boolean requestFingerprintComplete = calls.get() > 0
                && requestEvidenceCalls == calls.get()
                && missingSystemPromptCalls == 0
                && (scoped == null ? systemPromptDigests.size() == 1
                    : !(planAudit != null ? planAudit.failed() : teamAudit.failed())
                        && scoped.completeFor(calls.get()))
                && initialToolSchemaDigest != null;
        boolean contextCapSatisfied = calls.get() > 0
                && successfulCalls == calls.get()
                && missingUsageCalls == 0
                && contextCapViolationCalls == 0
                && effectiveContextWindowCapTokens > 0
                && effectiveContextWindowCapTokens == requestedContextWindowCapTokens
                && maxOutputTokensPerCall > 0
                && maxOutputTokensPerCall <= effectiveContextWindowCapTokens
                && maxObservedTotalTokensPerCall.get() <= effectiveContextWindowCapTokens;
        return new Metrics(
                calls.get(),
                inputTokens.get(),
                outputTokens.get(),
                cachedInputTokens.get(),
                toolCalls.get(),
                elapsedMillis.get(),
                successfulCalls,
                modelConsistent ? resolvedModels.iterator().next() : null,
                modelConsistent,
                successfulCalls > 0 && missingUsageCalls == 0,
                requestFingerprintComplete && systemPromptDigests.size() == 1 ? systemPromptDigests.iterator().next() : null,
                initialToolSchemaDigest,
                requestFingerprintComplete,
                requestedContextWindowCapTokens,
                effectiveContextWindowCapTokens,
                maxOutputTokensPerCall,
                maxObservedInputTokensPerCall.get(),
                maxObservedTotalTokensPerCall.get(),
                contextCapSatisfied, scoped);
    }

    public int failedCalls() {
        return failedCalls.get();
    }

    public String lastErrorMessage() {
        return lastErrorMessage.get();
    }

    /** Stable benchmark policy category for the last failed provider call. */
    public String lastFailureType() {
        return lastFailureType.get();
    }

    /** First provider-evidence failure that invalidates the episode; it cannot be overwritten. */
    public String evaluationInvalidFailureType() {
        return evaluationInvalidFailureType.get();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public void cancelInFlightCalls() {
        delegate.cancelInFlightCalls();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsImageInput() {
        return delegate.supportsImageInput();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    public record Metrics(int calls,
                          long inputTokens,
                          long outputTokens,
                          long cachedInputTokens,
                          long toolCalls,
                          long elapsedMillis,
                          int successfulCalls,
                          String resolvedModel,
                          boolean resolvedModelConsistent,
                          boolean usageComplete,
                          String systemPromptSha256,
                          String initialToolSchemaSha256,
                          boolean requestFingerprintComplete,
                          int requestedContextWindowCapTokens,
                          int effectiveContextWindowCapTokens,
                          int maxOutputTokensPerCall,
                          long maxObservedInputTokensPerCall,
                          long maxObservedTotalTokensPerCall,
                          boolean contextCapSatisfied,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                          ScopedRequestFingerprints scopedRequestFingerprints) {
        public Metrics(int calls, long inputTokens, long outputTokens, long cachedInputTokens,
                       long toolCalls, long elapsedMillis, int successfulCalls, String resolvedModel,
                       boolean resolvedModelConsistent, boolean usageComplete, String systemPromptSha256,
                       String initialToolSchemaSha256, boolean requestFingerprintComplete,
                       int requestedContextWindowCapTokens, int effectiveContextWindowCapTokens,
                       int maxOutputTokensPerCall, long maxObservedInputTokensPerCall,
                       long maxObservedTotalTokensPerCall, boolean contextCapSatisfied) {
            this(calls, inputTokens, outputTokens, cachedInputTokens, toolCalls, elapsedMillis,
                    successfulCalls, resolvedModel, resolvedModelConsistent, usageComplete,
                    systemPromptSha256, initialToolSchemaSha256, requestFingerprintComplete,
                    requestedContextWindowCapTokens, effectiveContextWindowCapTokens, maxOutputTokensPerCall,
                    maxObservedInputTokensPerCall, maxObservedTotalTokensPerCall, contextCapSatisfied, null);
        }
        public Metrics {
            if (requestedContextWindowCapTokens < 0 || effectiveContextWindowCapTokens < 0
                    || maxOutputTokensPerCall < 0 || maxObservedInputTokensPerCall < 0
                    || maxObservedTotalTokensPerCall < 0) {
                throw new IllegalArgumentException("context cap metrics must be non-negative");
            }
            if (requestedContextWindowCapTokens > 0
                    && effectiveContextWindowCapTokens > requestedContextWindowCapTokens) {
                throw new IllegalArgumentException(
                        "effective context cap must not exceed requested context cap");
            }
            if (maxOutputTokensPerCall > effectiveContextWindowCapTokens
                    && effectiveContextWindowCapTokens > 0) {
                throw new IllegalArgumentException(
                        "output cap must not exceed effective context cap");
            }
            if (contextCapSatisfied && (effectiveContextWindowCapTokens <= 0
                    || maxOutputTokensPerCall <= 0
                    || maxObservedTotalTokensPerCall > effectiveContextWindowCapTokens)) {
                throw new IllegalArgumentException("invalid satisfied context cap metrics");
            }
        }

        /** Binary/source compatibility for the pre-v6 metrics constructor; cap proof fails closed. */
        public Metrics(int calls,
                       long inputTokens,
                       long outputTokens,
                       long cachedInputTokens,
                       long toolCalls,
                       long elapsedMillis,
                       int successfulCalls,
                       String resolvedModel,
                       boolean resolvedModelConsistent,
                       boolean usageComplete,
                       String systemPromptSha256,
                       String initialToolSchemaSha256,
                       boolean requestFingerprintComplete,
                       int requestedContextWindowCapTokens,
                       int effectiveContextWindowCapTokens,
                       long maxObservedInputTokensPerCall,
                       boolean ignoredLegacyContextCapSatisfied) {
            this(calls, inputTokens, outputTokens, cachedInputTokens, toolCalls, elapsedMillis,
                    successfulCalls, resolvedModel, resolvedModelConsistent, usageComplete,
                    systemPromptSha256, initialToolSchemaSha256, requestFingerprintComplete,
                    requestedContextWindowCapTokens, effectiveContextWindowCapTokens,
                    0, maxObservedInputTokensPerCall, maxObservedInputTokensPerCall, false);
        }

        public Metrics(int calls,
                       long inputTokens,
                       long outputTokens,
                       long cachedInputTokens,
                       long toolCalls,
                       long elapsedMillis,
                       int successfulCalls,
                       String resolvedModel,
                       boolean resolvedModelConsistent,
                       boolean usageComplete,
                       String systemPromptSha256,
                       String initialToolSchemaSha256,
                       boolean requestFingerprintComplete) {
            this(calls, inputTokens, outputTokens, cachedInputTokens, toolCalls, elapsedMillis,
                    successfulCalls, resolvedModel, resolvedModelConsistent, usageComplete,
                    systemPromptSha256, initialToolSchemaSha256, requestFingerprintComplete,
                    0, 0, 0, 0, 0, false);
        }

        public Metrics(int calls,
                       long inputTokens,
                       long outputTokens,
                       long cachedInputTokens,
                       long toolCalls,
                       long elapsedMillis,
                       int successfulCalls,
                       String resolvedModel,
                       boolean resolvedModelConsistent,
                       boolean usageComplete) {
            this(calls, inputTokens, outputTokens, cachedInputTokens, toolCalls, elapsedMillis,
                    successfulCalls, resolvedModel, resolvedModelConsistent, usageComplete,
                    null, null, false, 0, 0, 0, 0, 0, false);
        }

        public Metrics(int calls,
                       long inputTokens,
                       long outputTokens,
                       long cachedInputTokens,
                       long toolCalls,
                       long elapsedMillis) {
            this(calls, inputTokens, outputTokens, cachedInputTokens, toolCalls, elapsedMillis,
                    calls, null, false, false, null, null, false,
                    0, 0, 0, 0, 0, false);
        }
    }

    private record RequestFingerprint(String systemPromptSha256, String toolSchemaSha256) {
    }

    private record ToolFingerprint(String name, String description, JsonNode parameters) {
    }
}
