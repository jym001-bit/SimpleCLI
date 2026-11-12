package com.paicli.eval.runner;

/**
 * 把一次运行归类，便于在 CSV 里快速看出结果性质。
 */
public final class OutcomeClassifier {

    /** 通过。 */
    public static final String PASSED = "passed";
    /** 运行正常结束，但验收没通过：模型表现问题。 */
    public static final String MODEL_FAILED = "model_failed";
    /** 超时：有效运行，但没在时限内完成。 */
    public static final String TIMEOUT = "timeout";
    /** 其他未完成原因：预算耗尽、被兜底逻辑收尾、取消等。 */
    public static final String RUN_FAILED = "run_failed";
    /** 运行无效：基础设施故障，不计入统计。 */
    public static final String INVALID = "invalid";

    private OutcomeClassifier() {
    }

    public static String classify(boolean passed, boolean valid, String exitReason) {
        if (!valid) {
            return INVALID;
        }
        if (passed) {
            return PASSED;
        }
        if ("completed".equals(exitReason)) {
            return MODEL_FAILED;
        }
        if ("timeout".equals(exitReason)) {
            return TIMEOUT;
        }
        return RUN_FAILED;
    }
}
