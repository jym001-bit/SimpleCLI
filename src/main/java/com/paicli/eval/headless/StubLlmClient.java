package com.paicli.eval.headless;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.List;

/**
 * 评测自测用的假模型：不联网、不读 Key、不花钱，用来验证 headless 与 runner 的流程。
 *
 * <ul>
 *   <li>默认返回固定回答；</li>
 *   <li>{@code delayMs > 0} 时先睡眠，用来触发超时；</li>
 *   <li>{@code loop == true} 时永远返回一个不存在的工具调用，用来触发请求预算。</li>
 * </ul>
 */
final class StubLlmClient implements LlmClient {

    private final String answer;
    private final long delayMs;
    private final boolean loop;

    StubLlmClient(String answer, long delayMs, boolean loop) {
        this.answer = answer == null ? "" : answer;
        this.delayMs = Math.max(0L, delayMs);
        this.loop = loop;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return respond();
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return respond();
    }

    private ChatResponse respond() throws IOException {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ChatResponse("assistant", "", null, null, 0, 0, 0);
            }
        }
        if (loop) {
            return new ChatResponse("assistant", "", null,
                    List.of(new ToolCall("stub-call", new ToolCall.Function("stub_noop", "{}"))),
                    5, 3, 0);
        }
        return new ChatResponse("assistant", answer, null, null, 12, 8, 0);
    }

    @Override
    public String getModelName() {
        return "stub-model";
    }

    @Override
    public String getProviderName() {
        return "stub";
    }

    @Override
    public boolean supportsTools() {
        return true;
    }
}
