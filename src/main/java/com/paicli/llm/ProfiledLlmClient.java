package com.paicli.llm;

import java.io.IOException;
import java.util.List;

/** Keep the provider's transport and tool-call adapter, overriding only declared capabilities. */
final class ProfiledLlmClient implements LlmClient {
    private final AbstractOpenAiCompatibleClient delegate;
    private final String model;
    private final ModelProfile profile;

    ProfiledLlmClient(AbstractOpenAiCompatibleClient delegate, String model, ModelProfile profile) {
        this.delegate = delegate;
        this.model = model;
        this.profile = profile;
    }

    AbstractOpenAiCompatibleClient transport() { return delegate; }

    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return delegate.chat(messages, tools);
    }
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return delegate.chat(messages, tools, listener);
    }
    public void cancelInFlightCalls() { delegate.cancelInFlightCalls(); }
    public String getModelName() { return model; }
    public String getProviderName() { return delegate.getProviderName(); }
    public int maxContextWindow() {
        return profile.contextWindow() == null ? delegate.maxContextWindow() : profile.contextWindow();
    }
    public boolean supportsImageInput() { return delegate.effectiveImageInput(); }
    public boolean looksLikeUnexecutedToolCall(String content) { return delegate.looksLikeUnexecutedToolCall(content); }
    public boolean supportsTools() { return delegate.supportsTools(); }
    public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
    public String promptCacheMode() { return delegate.promptCacheMode(); }
}
