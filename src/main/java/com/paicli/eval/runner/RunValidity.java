package com.paicli.eval.runner;

import java.util.Set;

/**
 * 判断一次运行是否有效（口径见仓库根目录 EVAL_RULES.md）。
 *
 * <p>有效：agent 得到了完整机会跑完，结果能反映它的真实表现，即使答错、超预算或触发自身兜底。
 * 无效：基础设施故障或评测流程干预，结果不能用于通过率与平均值统计。</p>
 */
public final class RunValidity {

    /** 无效原因：LLM/网络/引擎异常、无结果、超时、外部取消。 */
    private static final Set<String> INVALID_REASONS =
            Set.of("error", "no_result", "timeout", "cancelled");

    private RunValidity() {
    }

    public static boolean isValid(String exitReason) {
        if (exitReason == null || exitReason.isBlank()) {
            return false;
        }
        return !INVALID_REASONS.contains(exitReason);
    }
}
