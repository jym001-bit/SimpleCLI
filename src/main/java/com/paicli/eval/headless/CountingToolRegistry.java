package com.paicli.eval.headless;

import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在 HITL 工具注册表外层统计工具调用次数（按 invocation 计，不按批次计）。
 */
final class CountingToolRegistry extends HitlToolRegistry {

    private final AtomicInteger executions = new AtomicInteger();

    CountingToolRegistry(HitlHandler hitlHandler) {
        super(hitlHandler);
    }

    @Override
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations != null && !invocations.isEmpty()) {
            executions.addAndGet(invocations.size());
        }
        return super.executeTools(invocations);
    }

    int executionCount() {
        return executions.get();
    }
}
