package com.paicli.eval.benchmark;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;

/** Private, stdin/stdout-only protocol between the coordinator and one worker JVM. */
final class BenchmarkProtocol {
    static final int VERSION = 6;
    static final int MAX_REQUEST_BYTES = 1_048_576;
    static final int MAX_RESPONSE_BYTES = 8 * 1_048_576;
    static final int MAX_TOKEN_BUDGET = 10_000_000;
    static final int MAX_HARD_ITERATIONS = 100_000;
    static final int MAX_STAGNATION_WINDOW = 10_000;
    static final int MAX_OUTPUT_TOKENS_PER_CALL = 16_384;

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BenchmarkProtocol() {
    }

    static byte[] writeRequest(WorkerRequest request) throws IOException {
        byte[] encoded = MAPPER.writeValueAsBytes(request);
        requireWithinLimit(encoded, MAX_REQUEST_BYTES, "worker request");
        return encoded;
    }

    static WorkerRequest readRequest(byte[] json) throws IOException {
        requireWithinLimit(json, MAX_REQUEST_BYTES, "worker request");
        return MAPPER.readValue(json, WorkerRequest.class);
    }

    static byte[] writeResponse(WorkerResponse response) throws IOException {
        byte[] encoded = MAPPER.writeValueAsBytes(response);
        requireWithinLimit(encoded, MAX_RESPONSE_BYTES, "worker response");
        return encoded;
    }

    static WorkerResponse readResponse(byte[] json) throws IOException {
        requireWithinLimit(json, MAX_RESPONSE_BYTES, "worker response");
        return MAPPER.readValue(json, WorkerResponse.class);
    }

    private static void requireWithinLimit(byte[] value, int limit, String label)
            throws IOException {
        if (value == null) {
            throw new IOException(label + " is missing");
        }
        if (value.length > limit) {
            throw new IOException(label + " exceeds " + limit + " bytes");
        }
    }

    static String normalizeProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "deepseek", "deepseek-v4", "deepseek-v4-flash" -> "deepseek";
            case "glm", "zhipu", "bigmodel" -> "glm";
            case "hunyuan", "hy4", "hy4-preview", "tencent-hunyuan", "tencent_hunyuan" -> "hunyuan";
            default -> throw new IllegalArgumentException("unsupported benchmark provider: " + raw);
        };
    }

    record WorkerRequest(
            @JsonProperty(value = "protocolVersion", required = true) int protocolVersion,
            @JsonProperty(value = "provider", required = true) String provider,
            @JsonProperty(value = "model", required = true) String model,
            @JsonProperty("baseUrl") String baseUrl,
            @JsonProperty(value = "apiKey", required = true) String apiKey,
            @JsonProperty(value = "mode", required = true) String mode,
            @JsonProperty("toolProfile") BenchmarkToolProfile toolProfile,
            @JsonProperty(value = "agentLimits", required = true) AgentLimits agentLimits,
            @JsonProperty(value = "runtimeDate", required = true) String runtimeDate,
            @JsonProperty(value = "prompt", required = true) String prompt,
            @JsonProperty(value = "workspace", required = true) String workspace,
            @JsonProperty(value = "home", required = true) String home,
            @JsonProperty(value = "episodeDirectory", required = true) String episodeDirectory) {

        WorkerRequest {
            if (protocolVersion != VERSION) {
                throw new IllegalArgumentException("unsupported worker protocol version: " + protocolVersion);
            }
            provider = normalizeProvider(provider);
            toolProfile = toolProfile == null ? BenchmarkToolProfile.FILE_ONLY : toolProfile;
            if (agentLimits == null) {
                throw new IllegalArgumentException("agentLimits must not be null");
            }
            requireText(model, "model");
            requireText(apiKey, "apiKey");
            requireText(mode, "mode");
            requireRuntimeDate(runtimeDate);
            requireText(prompt, "prompt");
            requireText(workspace, "workspace");
            requireText(home, "home");
            requireText(episodeDirectory, "episodeDirectory");
        }

        @Override
        public String toString() {
            return "WorkerRequest[protocolVersion=" + protocolVersion
                    + ", provider=" + provider
                    + ", model=" + model
                    + ", baseUrl=" + (baseUrl == null || baseUrl.isBlank() ? "<default>" : "<configured>")
                    + ", apiKey=" + SecretRedactor.REDACTED
                    + ", mode=" + mode
                    + ", toolProfile=" + toolProfile
                    + ", agentLimits=" + agentLimits
                    + ", runtimeDate=" + runtimeDate
                    + ", workspace=" + workspace
                    + ", home=" + home
                    + ", episodeDirectory=" + episodeDirectory + "]";
        }
    }

    /** Frozen per-case limits. Formal runs must copy these values from the case contract. */
    record AgentLimits(
            @JsonProperty(value = "tokenBudget", required = true) int tokenBudget,
            @JsonProperty(value = "hardMaxIterations", required = true) int hardMaxIterations,
            @JsonProperty(value = "stagnationWindow", required = true) int stagnationWindow,
            @JsonProperty(value = "contextWindowCapTokens", required = true)
            int contextWindowCapTokens,
            @JsonProperty(value = "maxOutputTokensPerCall", required = true)
            int maxOutputTokensPerCall) {

        AgentLimits {
            if (tokenBudget <= 0 || tokenBudget > MAX_TOKEN_BUDGET) {
                throw new IllegalArgumentException("tokenBudget must be between 1 and 10000000");
            }
            if (hardMaxIterations <= 0 || hardMaxIterations > MAX_HARD_ITERATIONS) {
                throw new IllegalArgumentException(
                        "hardMaxIterations must be between 1 and 100000");
            }
            if (stagnationWindow < 2
                    || stagnationWindow > MAX_STAGNATION_WINDOW
                    || stagnationWindow > hardMaxIterations) {
                throw new IllegalArgumentException(
                        "stagnationWindow must be between 2 and 10000 and not exceed hardMaxIterations");
            }
            if (contextWindowCapTokens < ContextWindowCappedLlmClient.MIN_CONTEXT_WINDOW_CAP_TOKENS
                    || contextWindowCapTokens > 10_000_000) {
                throw new IllegalArgumentException(
                        "contextWindowCapTokens must be between 8000 and 10000000");
            }
            if (maxOutputTokensPerCall <= 0
                    || maxOutputTokensPerCall > MAX_OUTPUT_TOKENS_PER_CALL
                    || maxOutputTokensPerCall > contextWindowCapTokens) {
                throw new IllegalArgumentException(
                        "maxOutputTokensPerCall must be between 1 and 16384 and not exceed contextWindowCapTokens");
            }
        }

        static AgentLimits developmentDefaults() {
            return new AgentLimits(
                    BenchmarkWorkerProcess.TOKEN_BUDGET,
                    BenchmarkWorkerProcess.HARD_MAX_ITERATIONS,
                    BenchmarkWorkerProcess.STAGNATION_WINDOW,
                    1_000_000,
                    MAX_OUTPUT_TOKENS_PER_CALL);
        }
    }

    record WorkerResponse(
            @JsonProperty(value = "protocolVersion", required = true) int protocolVersion,
            @JsonProperty(value = "success", required = true) boolean success,
            @JsonProperty(value = "answer", required = true) String answer,
            @JsonProperty(value = "errorType", required = true) String errorType,
            @JsonProperty(value = "errorMessage", required = true) String errorMessage,
            @JsonProperty("metrics") TracingLlmClient.Metrics metrics) {

        WorkerResponse {
            if (protocolVersion != VERSION) {
                throw new IllegalArgumentException("unsupported worker response version: " + protocolVersion);
            }
            answer = answer == null ? "" : answer;
            errorType = errorType == null ? "" : errorType;
            errorMessage = errorMessage == null ? "" : errorMessage;
            if (success && (!errorType.isBlank() || !errorMessage.isBlank())) {
                throw new IllegalArgumentException("successful worker response must not contain an error");
            }
        }

        static WorkerResponse success(String answer, TracingLlmClient.Metrics metrics) {
            return new WorkerResponse(VERSION, true, answer, "", "", metrics);
        }

        static WorkerResponse failure(String type, String message, TracingLlmClient.Metrics metrics) {
            return new WorkerResponse(
                    VERSION,
                    false,
                    "",
                    type == null || type.isBlank() ? "WORKER_ERROR" : type,
                    SecretRedactor.redact(message == null ? "" : message),
                    metrics);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private static void requireRuntimeDate(String value) {
        requireText(value, "runtimeDate");
        try {
            java.time.LocalDate.parse(value);
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("runtimeDate must be an ISO-8601 date", error);
        }
    }
}
