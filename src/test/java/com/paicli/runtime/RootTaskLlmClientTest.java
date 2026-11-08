package com.paicli.runtime;

import com.paicli.agent.TaskUnderstandingEngine;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RootTaskLlmClientTest {
    private static final String COMPLEX = "帮我优化一下这个 Spring Boot 项目的登录模块，保持现有接口兼容，不要联网。";

    @Test void understandingAndExecutionShareAdmissionAndTrustedUsage() throws Exception {
        String old = System.getProperty("paicli.root.model.request.limit");
        System.setProperty("paicli.root.model.request.limit", "2");
        try {
            AtomicInteger calls = new AtomicInteger();
            LlmClient client = RootTaskLlmClient.wrap(provider(calls, true));
            try (RootTaskScope root = new RootTaskScope(); var ignored = root.bind()) {
                assertNotNull(new TaskUnderstandingEngine(client).understand(COMPLEX).semantic());
                client.chat(List.of(LlmClient.Message.user("execute")), List.of());
                assertEquals(2, calls.get());
                assertEquals(2, root.requests());
                assertEquals(8, root.inputTokens());
                assertEquals(4, root.outputTokens());
                assertThrows(RootTaskScope.RootRequestBudgetExceededException.class,
                        () -> client.chat(List.of(LlmClient.Message.user("blocked")), List.of()));
                assertEquals(2, calls.get());
            }
        } finally { restore("paicli.root.model.request.limit", old); }
    }

    @Test void optionalUnderstandingYieldsLastRequestAndCancellationIsIsolated() throws Exception {
        String old = System.getProperty("paicli.root.model.request.limit");
        System.setProperty("paicli.root.model.request.limit", "1");
        try {
            AtomicInteger calls = new AtomicInteger();
            LlmClient client = RootTaskLlmClient.wrap(provider(calls, false));
            try (RootTaskScope first = new RootTaskScope(); var ignored = first.bind()) {
                assertNull(new TaskUnderstandingEngine(client).understand(COMPLEX).semantic());
                assertEquals(0, calls.get());
                first.token().cancel();
                assertThrows(CancellationException.class,
                        () -> client.chat(List.of(LlmClient.Message.user("cancelled")), List.of()));
            }
            try (RootTaskScope second = new RootTaskScope(); var ignored = second.bind()) {
                client.chat(List.of(LlmClient.Message.user("independent")), List.of());
                assertEquals(1, second.requests());
                assertEquals(1, calls.get());
                assertEquals(0, second.inputTokens(), "Usage without provider confirmation is unknown");
            }
        } finally { restore("paicli.root.model.request.limit", old); }
    }

    @Test void cancellationDuringUnderstandingCannotFallBackIntoExecution() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient provider = new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                calls.incrementAndGet();
                CancellationContext.current().cancel();
                return new ChatResponse("assistant", "{}", List.of(), 0, 0);
            }
            @Override public String getModelName() { return "mock"; }
            @Override public String getProviderName() { return "mock"; }
        };
        LlmClient client = RootTaskLlmClient.wrap(provider);
        try (RootTaskScope root = new RootTaskScope(); var ignored = root.bind()) {
            assertThrows(CancellationException.class,
                    () -> new TaskUnderstandingEngine(client).understand(COMPLEX));
            assertThrows(CancellationException.class,
                    () -> client.chat(List.of(LlmClient.Message.user("must not execute")), List.of()));
            assertEquals(1, calls.get());
        }
    }

    private static LlmClient provider(AtomicInteger calls, boolean usage) {
        return new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                calls.incrementAndGet();
                return new ChatResponse("assistant", "{\"intent\":\"优化\"}", null,
                        List.of(), 4, 2, 0, "mock", usage);
            }
            @Override public String getModelName() { return "mock"; }
            @Override public String getProviderName() { return "mock"; }
        };
    }

    private static void restore(String key, String old) {
        if (old == null) System.clearProperty(key); else System.setProperty(key, old);
    }
}
