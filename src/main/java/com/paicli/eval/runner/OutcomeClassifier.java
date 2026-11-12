package com.paicli.eval.runner;

/**
 * 把一次运行归类成三种结果，便于在 CSV 里区分“模型没做对”和“压根没跑成”。
 */
public final class OutcomeClassifier {

    /** 通过。 */
    public static final String PASSED = "passed";
    /** 运行正常结束，但验收没通过：属于模型表现问题。 */
    public static final String MODEL_FAILED = "model_failed";
    /** 没正常跑完：网络错误、超时、预算耗尽、异常等，属于运行问题。 */
    public static final String RUN_FAILED = "run_failed";

    private OutcomeClassifier() {
    }

    public static String classify(boolean passed, String exitReason) {
        if (passed) {
            return PASSED;
        }
        if ("completed".equals(exitReason)) {
            return MODEL_FAILED;
        }
        return RUN_FAILED;
    }
}
