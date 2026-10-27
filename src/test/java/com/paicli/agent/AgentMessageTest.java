package com.paicli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentMessageTest {

    @Test
    void terminationMetadataDoesNotDependOnRenderedWordsOrMessageType() {
        for (AgentBudget.ExitReason reason : AgentBudget.ExitReason.values()) {
            if (reason == AgentBudget.ExitReason.WITHIN_BUDGET) continue;
            AgentMessage partial = AgentMessage.execution("worker", AgentRole.WORKER,
                    ExecutionOutcome.budgetExhausted(reason, "模型声称完成"));
            assertEquals(AgentMessage.Type.RESULT, partial.type(), "保留消息协议兼容");
            assertFalse(partial.outcome().completed());
            assertEquals(reason.name(), partial.outcome().reason().name());
        }
        AgentMessage cancelled = AgentMessage.execution("worker", AgentRole.WORKER,
                new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "已取消"));
        assertEquals(AgentMessage.Type.ERROR, cancelled.type());
        assertEquals(ExecutionOutcome.Reason.CANCELLED, cancelled.outcome().reason());
        assertEquals(ExecutionOutcome.Reason.MODEL_ERROR,
                AgentMessage.error("worker", AgentRole.WORKER, "故障").outcome().reason());
        assertTrue(AgentMessage.result("worker", AgentRole.WORKER, "部分完成只是普通措辞").outcome().completed());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOutcome.budgetExhausted(
                AgentBudget.ExitReason.WITHIN_BUDGET, "不能伪造预算停止"));
    }

    @Test
    void shouldCreateTaskMessage() {
        AgentMessage msg = AgentMessage.task("orchestrator", "分析代码结构");
        assertEquals("orchestrator", msg.fromAgent());
        assertNull(msg.fromRole());
        assertEquals("分析代码结构", msg.content());
        assertEquals(AgentMessage.Type.TASK, msg.type());
    }

    @Test
    void shouldCreateResultMessage() {
        AgentMessage msg = AgentMessage.result("worker-1", AgentRole.WORKER, "代码结构如下...");
        assertEquals("worker-1", msg.fromAgent());
        assertEquals(AgentRole.WORKER, msg.fromRole());
        assertEquals("代码结构如下...", msg.content());
        assertEquals(AgentMessage.Type.RESULT, msg.type());
    }

    @Test
    void shouldCreateFeedbackMessage() {
        AgentMessage msg = AgentMessage.feedback("reviewer", "缺少错误处理");
        assertEquals("reviewer", msg.fromAgent());
        assertEquals(AgentRole.REVIEWER, msg.fromRole());
        assertEquals("缺少错误处理", msg.content());
        assertEquals(AgentMessage.Type.FEEDBACK, msg.type());
    }

    @Test
    void shouldCreateApprovalMessage() {
        AgentMessage msg = AgentMessage.approval("reviewer", "质量达标");
        assertEquals(AgentMessage.Type.APPROVAL, msg.type());
        assertEquals(AgentRole.REVIEWER, msg.fromRole());
    }

    @Test
    void shouldCreateRejectionMessage() {
        AgentMessage msg = AgentMessage.rejection("reviewer", "存在严重问题");
        assertEquals(AgentMessage.Type.REJECTION, msg.type());
        assertEquals(AgentRole.REVIEWER, msg.fromRole());
        assertEquals("存在严重问题", msg.content());
    }

    @Test
    void shouldCreateErrorMessage() {
        AgentMessage msg = AgentMessage.error("worker-1", AgentRole.WORKER, "LLM 调用失败: 500");
        assertEquals("worker-1", msg.fromAgent());
        assertEquals(AgentRole.WORKER, msg.fromRole());
        assertEquals("LLM 调用失败: 500", msg.content());
        assertEquals(AgentMessage.Type.ERROR, msg.type());
    }

    @Test
    void shouldHaveSixMessageTypes() {
        AgentMessage.Type[] types = AgentMessage.Type.values();
        assertEquals(6, types.length);
    }

    @Test
    void shouldValueOfTypeByName() {
        assertSame(AgentMessage.Type.TASK, AgentMessage.Type.valueOf("TASK"));
        assertSame(AgentMessage.Type.RESULT, AgentMessage.Type.valueOf("RESULT"));
        assertSame(AgentMessage.Type.FEEDBACK, AgentMessage.Type.valueOf("FEEDBACK"));
        assertSame(AgentMessage.Type.APPROVAL, AgentMessage.Type.valueOf("APPROVAL"));
        assertSame(AgentMessage.Type.REJECTION, AgentMessage.Type.valueOf("REJECTION"));
        assertSame(AgentMessage.Type.ERROR, AgentMessage.Type.valueOf("ERROR"));
    }
}
