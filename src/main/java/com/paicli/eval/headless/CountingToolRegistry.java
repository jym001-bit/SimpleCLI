package com.paicli.eval.headless;

import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.HitlToolRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统计工具调用次数的注册表。
 *
 * <p>统计点选在 {@code onPolicyToolResults}：三种执行方式都会经过 TurnToolPolicy，
 * 这里拿到的是本批全部结果（含被策略拒绝的调用，顺序不变），不会漏掉未真正执行的调用。</p>
 */
final class CountingToolRegistry extends HitlToolRegistry {

    private final AtomicInteger executions = new AtomicInteger();

    CountingToolRegistry(HitlHandler hitlHandler) {
        super(hitlHandler);
    }

    @Override
    public void onPolicyToolResults(List<ToolExecutionResult> results) {
        if (results != null && !results.isEmpty()) {
            executions.addAndGet(results.size());
        }
        super.onPolicyToolResults(results);
    }

    int executionCount() {
        return executions.get();
    }
}
