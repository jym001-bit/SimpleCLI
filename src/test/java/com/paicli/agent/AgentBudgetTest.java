package com.paicli.agent;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBudgetTest {

    @Test
    void initiallyWithinBudget() {
        AgentBudget budget = new AgentBudget(1000, 3, 50);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void tokenBudgetExceededAfterAccumulation() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(60, 30);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordTokens(20, 0);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void stagnationDetectedAfterRepeatedToolCalls() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        List<LlmClient.ToolCall> sameCall = List.of(
                new LlmClient.ToolCall("call_1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"))
        );

        budget.recordToolCalls(sameCall);
        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void stagnationResetsWhenToolCallsDiffer() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"a.txt\"}")));
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"a.txt\"}")));
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"b.txt\"}")));
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void hardIterationLimitTriggersAfterEnoughIterations() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 3);
        budget.beginIteration();
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    @Test
    void stagnationTakesPrecedenceOverTokenBudget() {
        AgentBudget budget = new AgentBudget(100, 2, 50);
        budget.recordTokens(200, 0);
        budget.recordToolCalls(List.of(toolCall("x", "{}")));
        budget.recordToolCalls(List.of(toolCall("x", "{}")));
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(0, 3, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 3, 0));
    }

    @Test
    void describeExitContainsRelevantNumbers() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(80, 40);
        String message = budget.describeExit(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED);
        assertTrue(message.contains("120"));
        assertTrue(message.contains("100"));
    }

    @Test
    void defaultTokenBudgetIsUnlimited() {
        // 默认不再用 80% × window 当硬限——长上下文 + 套餐用户场景下太容易撞墙。
        // 默认死循环防护交给 stagnation；硬轮数只在用户显式配置时启用。
        AgentBudget budget = AgentBudget.fromSystemProperties();

        assertEquals(Integer.MAX_VALUE, budget.tokenBudget());
    }

    @Test
    void defaultStagnationWindowLeavesRoomForRunawayReminder() {
        String old = System.getProperty("paicli.react.stagnation.window");
        try {
            System.clearProperty("paicli.react.stagnation.window");
            AgentBudget budget = AgentBudget.fromSystemProperties();

            // 重复先达到提醒阈值、被提醒之后仍原样重复，才轮到停滞兜底
            assertTrue(budget.stagnationWindow() > RunawayGuard.DEFAULT_REMIND_AFTER);
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.stagnation.window");
            } else {
                System.setProperty("paicli.react.stagnation.window", old);
            }
        }
    }

    @Test
    void defaultIterationLimitIsUnlimited() {
        String old = System.getProperty("paicli.react.hard.max.iterations");
        try {
            System.clearProperty("paicli.react.hard.max.iterations");
            AgentBudget budget = AgentBudget.fromSystemProperties();

            assertEquals(AgentBudget.UNLIMITED_ITERATIONS, budget.hardMaxIterations());
            assertTrue(!budget.hasHardIterationLimit());
            for (int i = 0; i < 100; i++) {
                budget.beginIteration();
            }
            assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.hard.max.iterations");
            } else {
                System.setProperty("paicli.react.hard.max.iterations", old);
            }
        }
    }

    @Test
    void systemPropertyCanEnableHardIterationLimit() {
        String old = System.getProperty("paicli.react.hard.max.iterations");
        try {
            System.setProperty("paicli.react.hard.max.iterations", "2");
            AgentBudget budget = AgentBudget.fromSystemProperties();

            assertTrue(budget.hasHardIterationLimit());
            budget.beginIteration();
            budget.beginIteration();
            assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.hard.max.iterations");
            } else {
                System.setProperty("paicli.react.hard.max.iterations", old);
            }
        }
    }

    @Test
    void systemPropertyCanStillOverrideDynamicTokenBudget() {
        String old = System.getProperty("paicli.react.token.budget");
        try {
            System.setProperty("paicli.react.token.budget", "12345");
            AgentBudget budget = AgentBudget.fromSystemProperties();

            assertEquals(12345, budget.tokenBudget());
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.token.budget");
            } else {
                System.setProperty("paicli.react.token.budget", old);
            }
        }
    }

    private LlmClient.ToolCall toolCall(String name, String args) {
        return new LlmClient.ToolCall("call_" + name + "_" + args.hashCode(),
                new LlmClient.ToolCall.Function(name, args));
    }
}
