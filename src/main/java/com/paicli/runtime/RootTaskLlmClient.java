package com.paicli.runtime;

import com.paicli.llm.LlmClient;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Counts every logical model call against the active root task before invoking the provider. */
public final class RootTaskLlmClient implements LlmClient {
    private final LlmClient delegate;

    private RootTaskLlmClient(LlmClient delegate) { this.delegate = delegate; }

    public static LlmClient wrap(LlmClient client) {
        Objects.requireNonNull(client);
        return client instanceof RootTaskLlmClient ? client : new RootTaskLlmClient(client);
    }

    @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
            throws IOException {
        if (CancellationContext.isCancelled())
            throw new java.util.concurrent.CancellationException("Root task cancelled");
        RootTaskScope scope = RootTaskScope.current();
        if (scope != null) scope.admitRequest();
        ChatResponse response = delegate.chat(messages, tools, listener);
        if (scope != null) scope.recordUsage(response);
        return response;
    }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public String getProviderName() { return delegate.getProviderName(); }
    @Override public boolean managesRequestRetries() { return delegate.managesRequestRetries(); }
    @Override public void cancelInFlightCalls() { delegate.cancelInFlightCalls(); }
    @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
    @Override public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
    @Override public boolean supportsTools() { return delegate.supportsTools(); }
    @Override public boolean supportsImageInput() { return delegate.supportsImageInput(); }
    @Override public boolean looksLikeUnexecutedToolCall(String content) {
        return delegate.looksLikeUnexecutedToolCall(content);
    }
    @Override public String promptCacheMode() { return delegate.promptCacheMode(); }
}
