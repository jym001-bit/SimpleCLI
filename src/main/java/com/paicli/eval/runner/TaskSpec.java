package com.paicli.eval.runner;

import java.util.List;

/**
 * 评测任务定义（与 tasks.json 一一对应）。
 *
 * @param id             任务唯一标识
 * @param type           任务类型标签（纯记录用途，例如 stub / react / plan）
 * @param prompt         发给 agent 的指令
 * @param fixture        fixture 目录（相对当前工作目录）
 * @param runs           运行次数，默认 1
 * @param mode           react / plan / team，默认 react
 * @param approval       auto / deny-dangerous，默认 auto
 * @param maxRequests    请求预算上限，默认 40
 * @param timeoutSeconds 单次运行超时，默认 120
 * @param stub           仅自测用：假模型配置；为 null 时走真实模型
 * @param verify         验收方式
 */
public record TaskSpec(
        String id,
        String type,
        String prompt,
        String fixture,
        Integer runs,
        String mode,
        String approval,
        Integer maxRequests,
        Integer timeoutSeconds,
        StubSpec stub,
        VerifySpec verify
) {

    /** 假模型配置：固定回答 / 延迟 / 死循环造请求。 */
    public record StubSpec(String answer, Long delayMs, Boolean loop) {
    }

    /**
     * 验收方式：命令退出码 + 回答关键词，两者都配置时需同时满足。
     *
     * @param command  要执行的验收命令（可空）
     * @param exitCode 期望退出码，默认 0
     * @param keywords 最终回答必须全部包含的关键词（可空）
     */
    public record VerifySpec(String command, Integer exitCode, List<String> keywords) {
    }
}
