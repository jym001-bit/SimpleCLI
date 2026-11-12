package com.paicli.llm;

import okhttp3.Request;
import java.util.UUID;

/** OpenCode Go's DeepSeek route, retaining DeepSeek reasoning and tool handling. */
public final class OpenCodeGoClient extends DeepSeekClient {
    public static final String BASE_URL = "https://opencode.ai/inference/go/openai/v1";
    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    private final String sessionId = UUID.randomUUID().toString();

    public OpenCodeGoClient(String key, String model, String baseUrl) {
        super(key, model == null || model.isBlank() ? DEFAULT_MODEL : model, endpoint(baseUrl));
    }

    private static String endpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? BASE_URL : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    @Override public String getProviderName() { return "opencode-go"; }

    @Override public boolean supportsImageInput() {
        return DEFAULT_MODEL.equals(getModelName());
    }

    @Override protected void customizeRequest(Request.Builder request) {
        super.customizeRequest(request);
        request.header("User-Agent", "SimpleCLI/16.1.0")
                .header("x-opencode-session", sessionId);
    }
}
