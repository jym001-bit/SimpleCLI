package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentExecutionKernelTest {
    private com.paicli.runtime.CancellationToken token;

    @AfterEach
    void clearCancellation() {
        if (token != null) CancellationContext.clear(token);
    }

    @Test
    void modelTurnAccountsProviderUsageOnce() throws Exception {
        LlmClient client = mock(LlmClient.class);
        AgentBudget budget = budget();
        LlmClient.ChatResponse response = response();
        when(client.chat(anyList(), any(), nullable(LlmClient.StreamListener.class))).thenReturn(response);

        AgentExecutionKernel.ModelTurn turn = request(client, budget);

        assertFalse(turn.cancelled());
        assertSame(response, turn.response());
        assertEquals(31, budget.totalInputTokens());
        assertEquals(7, budget.totalOutputTokens());
        assertEquals(12, budget.totalCachedInputTokens());
        verify(client, times(1)).chat(anyList(), any(), nullable(LlmClient.StreamListener.class));
    }

    @Test
    void cancellationBeforeRequestDoesNotCallModel() throws Exception {
        LlmClient client = mock(LlmClient.class);
        AgentBudget budget = budget();
        token = CancellationContext.startRun();
        token.cancel();

        AgentExecutionKernel.ModelTurn turn = request(client, budget);

        assertTrue(turn.cancelled());
        assertNull(turn.response());
        assertEquals(0, budget.totalInputTokens());
        verifyNoInteractions(client);
    }

    @Test
    void cancellationDuringRequestStillAccountsBilledUsageAndPreventsPolicyProcessing() throws Exception {
        LlmClient client = mock(LlmClient.class);
        AgentBudget budget = budget();
        token = CancellationContext.startRun();
        when(client.chat(anyList(), any(), nullable(LlmClient.StreamListener.class))).thenAnswer(invocation -> {
            token.cancel();
            return response();
        });

        AgentExecutionKernel.ModelTurn turn = request(client, budget);

        assertTrue(turn.cancelled());
        assertNull(turn.response());
        assertEquals(31, budget.totalInputTokens());
        assertEquals(7, budget.totalOutputTokens());
        verify(client, times(1)).chat(anyList(), any(), nullable(LlmClient.StreamListener.class));
    }

    @Test
    void providerFailurePropagatesToPolicyOwnedErrorHandling() throws Exception {
        LlmClient client = mock(LlmClient.class);
        AgentBudget budget = budget();
        when(client.chat(anyList(), any(), nullable(LlmClient.StreamListener.class)))
                .thenThrow(new IOException("provider unavailable"));

        IOException error = assertThrows(IOException.class, () -> request(client, budget));

        assertEquals("provider unavailable", error.getMessage());
        assertEquals(0, budget.totalInputTokens());
        verify(client, times(1)).chat(anyList(), any(), nullable(LlmClient.StreamListener.class));
    }

    @Test
    void cancelledHttpFailureReturnsCancellationWithoutInventingUsage() throws Exception {
        LlmClient client = mock(LlmClient.class);
        AgentBudget budget = budget();
        token = CancellationContext.startRun();
        when(client.chat(anyList(), any(), nullable(LlmClient.StreamListener.class))).thenAnswer(invocation -> {
            token.cancel();
            throw new IOException("socket closed after cancellation");
        });

        AgentExecutionKernel.ModelTurn turn = request(client, budget);

        assertTrue(turn.cancelled());
        assertNull(turn.response());
        assertEquals(0, budget.totalInputTokens(), "未收到 Usage 时不得编造 Token");
        verify(client, times(1)).chat(anyList(), any(), nullable(LlmClient.StreamListener.class));
    }

    @Test
    void finalizationAlwaysUsesNoToolsAndAccountsUsageExactlyOnceEvenDuringCancellation() throws Exception {
        LlmClient client = mock(LlmClient.class);
        AgentBudget budget = budget();
        LlmClient.ChatResponse response = response();
        token = CancellationContext.startRun();
        token.cancel();
        when(client.chat(anyList(), eq(List.of()), nullable(LlmClient.StreamListener.class)))
                .thenReturn(response);

        LlmClient.ChatResponse result = AgentExecutionKernel.requestFinalizationTurn(
                client, List.of(LlmClient.Message.user("finish partial result")), null, budget,
                LoggerFactory.getLogger(AgentExecutionKernelTest.class), "finalization test");

        assertSame(response, result);
        assertEquals(31, budget.totalInputTokens());
        assertEquals(7, budget.totalOutputTokens());
        assertEquals(12, budget.totalCachedInputTokens());
        verify(client, times(1)).chat(anyList(), eq(List.of()), nullable(LlmClient.StreamListener.class));
    }

    @Test
    void iterationAdmissionOwnsBudgetCheckAndCounterTransition() {
        AgentBudget budget = new AgentBudget(Integer.MAX_VALUE, 5, 1);

        AgentExecutionKernel.IterationAdmission first = AgentExecutionKernel.admitIteration(budget);
        AgentExecutionKernel.IterationAdmission next = AgentExecutionKernel.admitIteration(budget);

        assertEquals(AgentExecutionKernel.IterationState.READY, first.state());
        assertEquals(1, first.iteration());
        assertEquals(AgentExecutionKernel.IterationState.BUDGET_EXHAUSTED, next.state());
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, next.exitReason());
        assertEquals(1, next.iteration());
    }

    @Test
    void iterationAdmissionStopsBeforeIncrementWhenCancelled() {
        AgentBudget budget = budget();
        token = CancellationContext.startRun();
        token.cancel();

        AgentExecutionKernel.IterationAdmission admission = AgentExecutionKernel.admitIteration(budget);

        assertEquals(AgentExecutionKernel.IterationState.CANCELLED, admission.state());
        assertEquals(0, admission.iteration());
        assertNull(admission.exitReason());
        assertEquals(0, budget.iteration());
    }

    private AgentExecutionKernel.ModelTurn request(LlmClient client, AgentBudget budget) throws IOException {
        return AgentExecutionKernel.requestModelTurn(client, List.of(LlmClient.Message.user("question")),
                List.of(), null, budget, LoggerFactory.getLogger(AgentExecutionKernelTest.class), "test");
    }

    private static AgentBudget budget() {
        return new AgentBudget(Integer.MAX_VALUE, 5, AgentBudget.UNLIMITED_ITERATIONS);
    }

    private static LlmClient.ChatResponse response() {
        return new LlmClient.ChatResponse("assistant", "answer", "reasoning", List.of(),
                31, 7, 12, "test-model", true);
    }
}
