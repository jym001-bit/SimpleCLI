package com.paicli.eval.headless;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * headless 入口参数。
 *
 * <p>只服务评测/自动化场景，不接入交互式 CLI 的参数体系，也不读取 JLine 终端。</p>
 */
public final class HeadlessOptions {

    public enum Mode {
        REACT, PLAN, TEAM
    }

    public enum Approval {
        AUTO, DENY_DANGEROUS
    }

    public static final String USAGE = """
            用法: java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.headless.HeadlessMain [选项]

              --prompt <文本>                  任务指令（与 --prompt-file 二选一）
              --prompt-file <路径>             从 UTF-8 文件读取任务指令
              --workdir <目录>                 工作目录/项目根，默认当前目录
              --mode react|plan|team           执行模式，默认 react
              --max-requests <N>               请求预算上限，默认 40；0 表示不限
              --timeout <秒>                   整体超时，默认 300
              --approval auto|deny-dangerous   审批策略，默认 auto
              --out <文件>                     结果 JSON 路径，默认 headless-result.json
              --provider <名字>                指定模型供应商，例如 opencode-go
              --model <名字>                   指定模型名（可选，默认用供应商默认值）
              --stub                           使用固定回答的假模型（不联网、不花钱）
              --stub-answer <文本>             stub 的最终回答
              --stub-delay-ms <N>              stub 每次响应前延迟毫秒（超时自测用）
              --stub-loop                      stub 永远返回假工具调用（预算自测用）
              --help                           打印本说明

            退出码: 0 正常完成 / 2 预算耗尽 / 3 超时 / 4 异常 / 5 参数错误 / 6 已取消
            """;

    String prompt;
    Path promptFile;
    Path workdir = Path.of(".");
    Mode mode = Mode.REACT;
    int maxRequests = 40;
    int timeoutSeconds = 300;
    Approval approval = Approval.AUTO;
    Path out;
    String provider;
    String model;
    boolean help;
    boolean stub;
    String stubAnswer = "stub answer";
    long stubDelayMs;
    boolean stubLoop;

    private HeadlessOptions() {
    }

    public static HeadlessOptions parse(String[] args) {
        HeadlessOptions options = new HeadlessOptions();
        if (args == null) {
            return options;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--prompt" -> options.prompt = value(args, ++i, arg);
                case "--prompt-file" -> options.promptFile = Path.of(value(args, ++i, arg));
                case "--workdir" -> options.workdir = Path.of(value(args, ++i, arg));
                case "--mode" -> options.mode = parseMode(value(args, ++i, arg));
                case "--max-requests" -> options.maxRequests = parseInt(value(args, ++i, arg), arg);
                case "--timeout" -> options.timeoutSeconds = parseInt(value(args, ++i, arg), arg);
                case "--approval" -> options.approval = parseApproval(value(args, ++i, arg));
                case "--out" -> options.out = Path.of(value(args, ++i, arg));
                case "--provider" -> options.provider = value(args, ++i, arg);
                case "--model" -> options.model = value(args, ++i, arg);
                case "--stub" -> options.stub = true;
                case "--stub-answer" -> options.stubAnswer = value(args, ++i, arg);
                case "--stub-delay-ms" -> options.stubDelayMs = parseInt(value(args, ++i, arg), arg);
                case "--stub-loop" -> options.stubLoop = true;
                case "--help", "-h" -> options.help = true;
                default -> throw new IllegalArgumentException("未知参数: " + arg);
            }
        }
        options.validate();
        return options;
    }

    public String resolvePrompt() {
        if (prompt != null && !prompt.isBlank()) {
            return prompt;
        }
        if (promptFile != null) {
            try {
                return Files.readString(promptFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("读取 --prompt-file 失败: " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("必须提供 --prompt 或 --prompt-file");
    }

    private void validate() {
        if (help) {
            return;
        }
        if ((prompt == null || prompt.isBlank()) && promptFile == null) {
            throw new IllegalArgumentException("必须提供 --prompt 或 --prompt-file");
        }
        if (maxRequests < 0) {
            throw new IllegalArgumentException("--max-requests 不能为负数");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("--timeout 必须大于 0");
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 缺少取值");
        }
        return args[index];
    }

    private static int parseInt(String raw, String option) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " 需要整数: " + raw);
        }
    }

    private static Mode parseMode(String raw) {
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--mode 只支持 react|plan|team: " + raw);
        }
    }

    private static Approval parseApproval(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "auto", "auto-approve" -> Approval.AUTO;
            case "deny", "deny-dangerous" -> Approval.DENY_DANGEROUS;
            default -> throw new IllegalArgumentException("--approval 只支持 auto|deny-dangerous: " + raw);
        };
    }
}
