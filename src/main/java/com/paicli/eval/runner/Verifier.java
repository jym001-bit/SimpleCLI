package com.paicli.eval.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 任务验收：命令退出码 + 最终回答关键词，两种方式可单独或同时使用。
 */
public final class Verifier {

    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 300;

    public record Result(boolean ok, String detail) {
    }

    private Verifier() {
    }

    public static Result verify(TaskSpec.VerifySpec spec, String answer, Path workdir) {
        if (spec == null) {
            return new Result(true, "未配置验收方式");
        }
        List<String> problems = new ArrayList<>();
        if (spec.command() != null && !spec.command().isBlank()) {
            int expected = spec.exitCode() == null ? 0 : spec.exitCode();
            int actual = runCommand(spec.command(), workdir, DEFAULT_COMMAND_TIMEOUT_SECONDS);
            if (actual != expected) {
                problems.add("验收命令退出码 " + actual + "（期望 " + expected + "）");
            }
        }
        List<String> missing = missingKeywords(answer, spec.keywords());
        if (!missing.isEmpty()) {
            problems.add("回答缺少关键词: " + String.join(", ", missing));
        }
        return problems.isEmpty()
                ? new Result(true, "ok")
                : new Result(false, String.join("；", problems));
    }

    public static List<String> missingKeywords(String answer, List<String> keywords) {
        List<String> missing = new ArrayList<>();
        if (keywords == null) {
            return missing;
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (answer == null || !answer.contains(keyword)) {
                missing.add(keyword);
            }
        }
        return missing;
    }

    public static int runCommand(String command, Path workdir, int timeoutSeconds) {
        List<String> shell = isWindows()
                ? List.of("cmd.exe", "/d", "/c", command)
                : List.of("/bin/sh", "-c", command);
        try {
            ProcessBuilder builder = new ProcessBuilder(shell);
            if (workdir != null) {
                builder.directory(workdir.toFile());
            }
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -3;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
