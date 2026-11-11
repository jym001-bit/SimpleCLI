package com.paicli.eval.headless;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 给真实 LlmClient 套一层计数器：请求次数 + 累计 token，并实施请求预算。
 *
 * <p>三种模式（ReAct/Plan/Team）共用同一个实例，所以统计到的是整条任务的模型请求总量。</p>
 */
final class CountingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final int maxRequests;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();

    CountingLlmClient(LlmClient delegate, int maxRequests) {
        this.delegate = delegate;
        this.maxRequests = maxRequests;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        beforeRequest();
        return record(delegate.chat(messages, tools));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        beforeRequest();
        return record(delegate.chat(messages, tools, listener));
    }

    private void beforeRequest() {
        if (maxRequests <= 0) {
            requests.incrementAndGet();
            return;
        }
        int used = requests.get();
        if (used >= maxRequests) {
            throw new BudgetExceededException("请求预算已耗尽: 上限 " + maxRequests);
        }
        requests.incrementAndGet();
    }

    private ChatResponse record(ChatResponse response) {
        if (response != null) {
            inputTokens.addAndGet(response.inputTokens());
            outputTokens.addAndGet(response.outputTokens());
            cachedInputTokens.addAndGet(response.cachedInputTokens());
        }
        return response;
    }

    int requests() {
        return requests.get();
    }

    long inputTokens() {
        return inputTokens.get();
    }

    long outputTokens() {
        return outputTokens.get();
    }

    long cachedInputTokens() {
        return cachedInputTokens.get();
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
}
