package com.paicli.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** User-owned overrides; null fields retain the provider/template behavior. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelProfile(String template, Integer contextWindow, Boolean imageInput,
                           String thinking, String reasoningEffort, Boolean reasoningHistory,
                           Boolean dsml, Integer maxOutputTokens, String source) {
    public ModelProfile {
        if (contextWindow != null && (contextWindow < 8_000 || contextWindow > 10_000_000))
            throw new IllegalArgumentException("context 必须在 8000 到 10000000 之间");
        if (maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > 1_000_000))
            throw new IllegalArgumentException("max-output 必须在 1 到 1000000 之间");
        if (contextWindow != null && maxOutputTokens != null && maxOutputTokens > contextWindow)
            throw new IllegalArgumentException("max-output 不能超过 context");
        if (thinking != null && !java.util.Set.of("enabled", "disabled").contains(thinking))
            throw new IllegalArgumentException("thinking 必须是 auto、enabled 或 disabled");
        if (reasoningEffort != null && !java.util.Set.of("low", "medium", "high", "max").contains(reasoningEffort))
            throw new IllegalArgumentException("effort 必须是 auto、low、medium、high 或 max");
        if ("disabled".equals(thinking) && reasoningEffort != null)
            throw new IllegalArgumentException("关闭思考时不能指定 effort");
    }

    public static ModelProfile discovered() {
        return new ModelProfile(null, 128_000, false, null, null, null, null, null, "discovered");
    }
}
