package com.paicli.eval.runner;

import java.util.List;
import java.util.Map;

/**
 * 评测任务定义（与 tasks.json 一一对应）。
 *
 * @param id             任务唯一标识
 * @param type           任务类型标签（只读/修补/加参数/多文件/调试/诱导）
 * @param prompt         发给 agent 的指令
 * @param fixture        fixture 目录（相对仓库根目录）
 * @param runs           运行次数，默认 1
 * @param mode           react / plan / team，默认 react
 * @param approval       auto / deny-dangerous，默认 auto
 * @param maxRequests    请求预算上限，默认 40
 * @param timeoutSeconds 单次运行超时，默认 120
 * @param stub           仅自测用：假模型配置；为 null 时走真实模型
 * @param verify         验收方式
 * @param reference      人工参考解法（补丁或参考答案），供 ReferenceCheckMain 使用
 * @param mutateFile     参考校验用：故意篡改该文件后，验收必须失败
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
        VerifySpec verify,
        ReferenceSpec reference,
        String mutateFile,
        String difficultyReason
) {

    /** 假模型配置：固定回答 / 延迟 / 死循环造请求 / 模拟网络失败。 */
    public record StubSpec(String answer, Long delayMs, Boolean loop, Boolean fail) {
    }

    /**
     * 验收方式。
     *
     * @param command            要执行的验收命令（可空）
     * @param exitCode           期望退出码，默认 0
     * @param keywords           最终回答必须全部包含的关键词（可空）
     * @param allowedChangedFiles 运行后允许改动的文件；null 表示不限制，空数组表示一个都不许改
     * @param unchangedFiles     这些文件必须保持原样
     * @param hiddenFiles        验收前才注入的隐藏测试，key=工作区内目标路径，value=仓库内源文件路径
     */
    public record VerifySpec(
            String command,
            Integer exitCode,
            List<String> keywords,
            List<String> allowedChangedFiles,
            List<String> unchangedFiles,
            Map<String, String> hiddenFiles,
            Map<String, java.util.List<String>> fileContains,
            AnswerSpec answerJson
    ) {
    }

    /**
     * 回答类任务的结构化验收：答案必须是 JSON 对象。
     *
     * @param requiredAll   这些字段必须存在且非空
     * @param requiredAnyOf 至少一个字段存在且非空
     * @param expected      字段取值必须等于给定期望（按字符串比较）
     */
    public record AnswerSpec(
            List<String> requiredAll,
            List<String> requiredAnyOf,
            Map<String, String> expected
    ) {
    }

    /**
     * 人工参考解法：改动类任务给补丁，只读类任务给参考答案文本。
     */
    public record ReferenceSpec(String patch, String answer) {
    }
}
