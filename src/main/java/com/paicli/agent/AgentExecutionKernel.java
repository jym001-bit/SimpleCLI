package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmTraceLogger;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import com.paicli.tool.TurnToolPolicy;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Shared runtime boundary for one policy-owned model turn.
 *
 * <p>ReAct, Plan tasks, and Team workers retain their own loop and result policy. This kernel
 * owns the repeated request lifecycle they share: cancellation gates, provider usage accounting,
 * and reasoning trace logging. Tool authorization/execution remains in TurnToolPolicy and
 * ToolRegistry, which are already shared by those modes.</p>
 */
public final class AgentExecutionKernel {
    private AgentExecutionKernel() {}

    /** Admit one policy iteration and make its budget/cancellation transition atomically. */
    public static IterationAdmission admitIteration(AgentBudget budget) {
        Objects.requireNonNull(budget, "budget");
        if (CancellationContext.isCancelled()) {
            return new IterationAdmission(IterationState.CANCELLED, budget.iteration(), null);
        }
        AgentBudget.ExitReason exitReason = budget.check();
        if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return new IterationAdmission(IterationState.BUDGET_EXHAUSTED, budget.iteration(), exitReason);
        }
        return new IterationAdmission(IterationState.READY, budget.beginIteration(), exitReason);
    }
    //检查取消、管理一次模型请求
    public static ModelTurn requestModelTurn(
            LlmClient client,
            List<LlmClient.Message> messages,
            List<LlmClient.Tool> tools,
            LlmClient.StreamListener streamHandler,
            AgentBudget budget,
            Logger logger,
            String operation) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(operation, "operation");

        if (CancellationContext.isCancelled()) {
            return new ModelTurn(null, true);
        }

        LlmClient.ChatResponse response;
        try {
            response = requestAndAccount(client, messages, tools, streamHandler, budget, logger, operation);
        } catch (IOException failure) {
            if (CancellationContext.isCancelled()) return new ModelTurn(null, true);
            throw failure;
        }
        return CancellationContext.isCancelled()
                ? new ModelTurn(null, true)
                : new ModelTurn(response, false);
    }

    /**
     * Make exactly one tool-free, best-effort request after a policy has exhausted its budget.
     *
     * <p>Unlike a normal turn this neither admits another iteration nor applies cancellation gates:
     * all three existing finalizers preserve the partial response even if cancellation races with
     * finalization. Provider cancellation/IO exceptions still propagate to the caller. The caller
     * retains result formatting, conversation recording, aggregate usage and UI cleanup.</p>
     */
    public static LlmClient.ChatResponse requestFinalizationTurn(
            LlmClient client,
            List<LlmClient.Message> messages,
            LlmClient.StreamListener streamHandler,
            AgentBudget budget,
            Logger logger,
            String operation) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(operation, "operation");
        return requestAndAccount(client, messages, List.of(), streamHandler, budget, logger, operation);
    }
    //发请求、统计用量、记录日志 记录累计token用量
    private static LlmClient.ChatResponse requestAndAccount(
            LlmClient client, List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
            LlmClient.StreamListener streamHandler, AgentBudget budget, Logger logger,
            String operation) throws IOException {
        LlmClient.ChatResponse response = client.chat(messages, tools, streamHandler);
        // The provider has already spent these tokens even if cancellation arrived during the call.
        budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        LlmTraceLogger.logReasoning(logger, operation, client, response.reasoningContent());
        return response;
    }
    //工具调用
    /** Convert a model decision to invocations and send it through the shared authorization/execution path. */
    public static List<ToolExecutionResult> executeToolCalls(
            TurnToolPolicy policy,
            ToolRegistry registry,
            List<LlmClient.ToolCall> toolCalls,
            TurnToolPolicy.ToolExposure exposure) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(registry, "registry");
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();
        List<ToolInvocation> invocations = toolCalls.stream()
                .map(call -> new ToolInvocation(call.id(), call.function().name(), call.function().arguments()))
                .toList();
        return policy.execute(registry, invocations, exposure);
    }

    public record ModelTurn(LlmClient.ChatResponse response, boolean cancelled) {
        public ModelTurn {
            if (!cancelled && response == null) {
                throw new IllegalArgumentException("a non-cancelled model turn requires a response");
            }
        }
    }

    public enum IterationState { READY, BUDGET_EXHAUSTED, CANCELLED }

    public record IterationAdmission(IterationState state, int iteration, AgentBudget.ExitReason exitReason) {
        public IterationAdmission {
            Objects.requireNonNull(state, "state");
            if (state == IterationState.READY && exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                throw new IllegalArgumentException("ready iteration requires WITHIN_BUDGET");
            }
            if (state == IterationState.BUDGET_EXHAUSTED
                    && (exitReason == null || exitReason == AgentBudget.ExitReason.WITHIN_BUDGET)) {
                throw new IllegalArgumentException("budget exit requires an exit reason");
            }
        }
    }
}
