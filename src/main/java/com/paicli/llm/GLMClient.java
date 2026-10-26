package com.paicli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class GLMClient extends AbstractOpenAiCompatibleClient {

    private static final String CODING_API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private static final String MULTIMODAL_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-5.1";
    private static final int MAX_BENCHMARK_OUTPUT_TOKENS = 16_384;
    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final Integer benchmarkMaxOutputTokens;

    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, null, null);
    }

    public GLMClient(String apiKey, String model) {
        this(apiKey, model, null, null);
    }

    /** Creates an immutable provider instance for a frozen benchmark output limit. */
    public GLMClient(String apiKey, String model, int benchmarkMaxOutputTokens) {
        this(apiKey, model, null,
                Integer.valueOf(requireBenchmarkMaxOutputTokens(benchmarkMaxOutputTokens)));
    }

    GLMClient(String apiKey, String model, String apiUrl) {
        this(apiKey, model, apiUrl, null);
    }

    GLMClient(String apiKey, String model, String apiUrl, int benchmarkMaxOutputTokens) {
        this(apiKey, model, apiUrl,
                Integer.valueOf(requireBenchmarkMaxOutputTokens(benchmarkMaxOutputTokens)));
    }

    private GLMClient(String apiKey, String model, String apiUrl,
                      Integer benchmarkMaxOutputTokens) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : selectApiUrl(this.model);
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
        return isGlm53();
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        if (benchmarkMaxOutputTokens != null) {
            requestBody.put("max_tokens", benchmarkMaxOutputTokens);
        }
        if (!isGlm53()) {
            return;
        }
        requestBody.put("temperature", 1.0);
        requestBody.put("top_p", 0.95);
        requestBody.put("reasoning_effort", "max");
        ObjectNode thinking = requestBody.putObject("thinking");
        thinking.put("type", "enabled");
        thinking.put("clear_thinking", false);
        requestBody.put("tool_stream", true);
        requestBody.putObject("stream_options").put("include_usage", true);
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        return isGlm53() ? 1_000_000 : 200_000;
    }

    @Override
    public boolean supportsImageInput() {
        return true;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "glm-prompt-cache";
    }

    @Override
    protected String toImageUrl(LlmClient.ContentPart part) {
        if (isGlm5v() && "image_base64".equals(part.type())) {
            return part.imageBase64();
        }
        return super.toImageUrl(part);
    }

    private static String selectApiUrl(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.startsWith("glm-5v")) {
            return MULTIMODAL_API_URL;
        }
        return CODING_API_URL;
    }

    private boolean isGlm5v() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5v");
    }

    private boolean isGlm53() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5.3");
    }

    private static int requireBenchmarkMaxOutputTokens(int value) {
        if (value <= 0 || value > MAX_BENCHMARK_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "benchmarkMaxOutputTokens must be between 1 and 16384");
        }
        return value;
    }
}
