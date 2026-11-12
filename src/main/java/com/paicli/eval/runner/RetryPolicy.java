package com.paicli.eval.runner;

/**
 * 无效运行的重试策略：基础设施错误与超时分开计数、分开限额。
 *
 * <p>默认：基础设施错误最多重试 3 次，超时最多重试 1 次（超时通常意味着模型/流程本身慢，
 * 无限重试只会放大成本）。</p>
 */
public final class RetryPolicy {

    private final int maxInfraRetries;
    private final int maxTimeoutRetries;

    public RetryPolicy(int maxInfraRetries, int maxTimeoutRetries) {
        if (maxInfraRetries < 0 || maxTimeoutRetries < 0) {
            throw new IllegalArgumentException("retry limits must be >= 0");
        }
        this.maxInfraRetries = maxInfraRetries;
        this.maxTimeoutRetries = maxTimeoutRetries;
    }

    public boolean canRetry(String exitReason, int infraRetriesUsed, int timeoutRetriesUsed) {
        if ("timeout".equals(exitReason)) {
            return timeoutRetriesUsed < maxTimeoutRetries;
        }
        return infraRetriesUsed < maxInfraRetries;
    }

    public int maxInfraRetries() {
        return maxInfraRetries;
    }

    public int maxTimeoutRetries() {
        return maxTimeoutRetries;
    }
}
