package com.paicli.hitl;

/**
 * auto 模式下，对需要确认的工具调用做一次自动审查：判定为安全就直接执行，否则转人工审批。
 *
 * <p>实现必须 fail-closed：任何异常、超时或无法解析的结果都返回 {@link Review#ask}。</p>
 */
@FunctionalInterface
public interface AutoApprovalReviewer {

    Review review(String toolName, String argumentsJson);

    record Review(boolean allowed, String reason) {
        public static Review allow(String reason) {
            return new Review(true, reason);
        }

        public static Review ask(String reason) {
            return new Review(false, reason);
        }
    }
}
