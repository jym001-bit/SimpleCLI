package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskUnderstandingEngineTest {
    private static final String COMPLEX = "帮我优化一下这个 Spring Boot 项目的登录模块，保持现有接口兼容，不要联网。";

    @Test void simpleQuestionAndExplicitFileTaskUseNoModelRequest() {
        Script client = new Script("{}");
        var engine = new TaskUnderstandingEngine(client);
        assertNull(engine.understand("法国首都是什么？").semantic());
        assertNull(engine.understand("创建文件 report.txt").semantic());
        assertEquals(0, client.calls);
    }

    @Test void complexTaskUsesOneToolFreeRequestAndKeepsCandidatesSeparate() {
        Script client = new Script("""
                {"intent":"优化登录模块","taskType":"代码优化","normalizedGoal":"改善登录模块",
                 "scope":["认证逻辑"],"constraints":["可以修改接口"],"deliverables":["代码修改"],
                 "unknowns":["优化方向"],"candidateAcceptanceCriteria":["登录测试通过"],
                 "clarificationQuestion":"优先优化安全、性能还是结构？","clarificationNeeded":true}
                """);
        TaskContract contract = new TaskUnderstandingEngine(client).understand(COMPLEX);
        assertEquals(1, client.calls);
        assertTrue(client.noTools);
        assertEquals(COMPLEX, contract.goal());
        assertTrue(contract.constraints().contains("禁止联网"));
        assertFalse(contract.constraints().contains("可以修改接口"));
        assertEquals(TaskContract.Source.MODEL_CANDIDATE, contract.semantic().constraints().get(0).source());
        assertTrue(contract.needsConfirmation());
        assertTrue(contract.acceptanceLines().isEmpty());
        assertTrue(contract.explicitAcceptanceLines().isEmpty());
    }

    @Test void malformedOrMissingFieldsDoNotInventRequirements() {
        Script bad = new Script("not-json");
        assertNull(new TaskUnderstandingEngine(bad).understand(COMPLEX).semantic());
        Script missing = new Script("{\"intent\":\"优化\"}");
        TaskContract contract = new TaskUnderstandingEngine(missing).understand(COMPLEX);
        assertNotNull(contract.semantic());
        assertNull(contract.semantic().taskType());
        assertTrue(contract.semantic().scope().isEmpty());
        assertTrue(contract.semantic().candidateAcceptanceCriteria().isEmpty());
    }

    @Test void providerFailureFallsBackButCancellationStopsRootTask() {
        Script failure = new Script(null);
        assertNull(new TaskUnderstandingEngine(failure).understand(COMPLEX).semantic());
        assertEquals(1, failure.calls);
        var token = CancellationContext.startRun();
        try {
            token.cancel();
            Script cancelled = new Script("{}");
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> new TaskUnderstandingEngine(cancelled).understand(COMPLEX));
            assertEquals(0, cancelled.calls);
        } finally { CancellationContext.clear(token); }
    }

    private static final class Script implements LlmClient {
        final String output;
        int calls;
        boolean noTools;
        Script(String output) { this.output = output; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls++;
            noTools = tools.isEmpty();
            if (output == null) throw new IOException("controlled failure");
            return new ChatResponse("assistant", output, null, 4, 2);
        }
        @Override public String getModelName() { return "controlled"; }
        @Override public String getProviderName() { return "offline"; }
    }
}
