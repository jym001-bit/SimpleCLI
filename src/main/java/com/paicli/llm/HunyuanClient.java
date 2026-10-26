package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class HunyuanClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://tokenhub.tencentmaas.com/v1";
    private static final String DEFAULT_MODEL = "hy4-preview";
    private static final int MAX_BENCHMARK_OUTPUT_TOKENS = 16_384;

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final Integer benchmarkMaxOutputTokens;

    public HunyuanClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL, null);
    }

    public HunyuanClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, null);
    }

    /** Creates an immutable provider instance for a frozen benchmark output limit. */
    public HunyuanClient(String apiKey, String model, String baseUrl,
                         int benchmarkMaxOutputTokens) {
        this(apiKey, model, baseUrl,
                Integer.valueOf(requireBenchmarkMaxOutputTokens(benchmarkMaxOutputTokens)));
    }

    private HunyuanClient(String apiKey, String model, String baseUrl,
                          Integer benchmarkMaxOutputTokens) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
        this.benchmarkMaxOutputTokens = benchmarkMaxOutputTokens;
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (benchmarkMaxOutputTokens != null) {
            requestBody.put("max_tokens", benchmarkMaxOutputTokens);
        }
        requestBody.put("temperature", 0.9);
        requestBody.put("reasoning_effort", "high");
        requestBody.putObject("thinking").put("type", "enabled");
        requestBody.putObject("stream_options").put("include_usage", true);
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "hunyuan";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsImageInput() {
        return false;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "hunyuan-prefix-cache";
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }

    private static int requireBenchmarkMaxOutputTokens(int value) {
        if (value <= 0 || value > MAX_BENCHMARK_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "benchmarkMaxOutputTokens must be between 1 and 16384");
        }
        return value;
    }
}
