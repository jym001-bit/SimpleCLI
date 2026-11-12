package com.paicli.eval.runner;

import java.util.Set;

/**
 * 判断一次运行是否有效。
 *
 * <p>有效 = 运行过程本身没有基础设施故障；即使模型答得不对或超时，也算有效，
 * 因为那是被测对象的表现，不是评测环境的问题。</p>
 */
public final class RunValidity {

    /** 基础设施故障：LLM 调用失败、网络异常、引擎异常、根本没产出结果。 */
    private static final Set<String> INVALID_REASONS = Set.of("error", "no_result");

    private RunValidity() {
    }

    public static boolean isValid(String exitReason) {
        if (exitReason == null || exitReason.isBlank()) {
            return false;
        }
        return !INVALID_REASONS.contains(exitReason);
    }
}
