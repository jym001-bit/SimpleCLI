package com.paicli.eval.headless;

/**
 * 请求预算耗尽：由 {@link CountingLlmClient} 在超过 --max-requests 时抛出，
 * 由 {@link HeadlessMain} 捕获并映射为退出码 2。
 */
public final class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
