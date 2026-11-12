package com.paicli.eval.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 任务验收：命令退出码、回答关键词、改动文件范围、指定文件未被改动。
 *
 * <p>四种检查可以单独或组合使用；任何一项不满足即判失败。</p>
 */
public final class Verifier {

    private static final com.fasterxml.jackson.databind.ObjectMapper ANSWER_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 300;
    private static final int MAX_REPORTED_FILES = 8;

    public record Result(boolean ok, String detail) {
    }

    private Verifier() {
    }

    public static Result verify(TaskSpec.VerifySpec spec, String answer, Path workdir) {
        return verify(spec, answer, workdir, List.of());
    }

    public static Result verify(TaskSpec.VerifySpec spec, String answer, Path workdir,
                                List<String> changedFiles) {
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

        if (spec.fileContains() != null) {
            for (Map.Entry<String, List<String>> entry : spec.fileContains().entrySet()) {
                Path file = workdir == null ? null : workdir.resolve(entry.getKey());
                if (file == null || !Files.isRegularFile(file)) {
                    problems.add("缺少预期文件: " + entry.getKey());
                    continue;
                }
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException e) {
                    problems.add("读取失败: " + entry.getKey());
                    continue;
                }
                for (String token : entry.getValue()) {
                    if (!content.contains(token)) {
                        problems.add("文件 " + entry.getKey() + " 缺少内容: " + token);
                    }
                }
            }
        }

        if (spec.answerJson() != null) {
            problems.addAll(checkAnswerJson(spec.answerJson(), answer));
        }

        List<String> changed = changedFiles == null ? List.of() : changedFiles;
        if (spec.allowedChangedFiles() != null) {
            List<String> outside = new ArrayList<>();
            for (String file : changed) {
                if (!matchesAny(file, spec.allowedChangedFiles())) {
                    outside.add(file);
                }
            }
            if (!outside.isEmpty()) {
                problems.add("改动超出允许范围: " + summarize(outside));
            }
        }

        if (spec.unchangedFiles() != null) {
            List<String> touched = new ArrayList<>();
            for (String file : spec.unchangedFiles()) {
                if (changed.contains(file)) {
                    touched.add(file);
                }
            }
            if (!touched.isEmpty()) {
                problems.add("不允许改动的文件被修改: " + summarize(touched));
            }
        }

        return problems.isEmpty()
                ? new Result(true, "ok")
                : new Result(false, String.join("；", problems));
    }

    private static List<String> checkAnswerJson(TaskSpec.AnswerSpec spec, String answer) {
        List<String> problems = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            problems.add("回答为空，无法解析 JSON");
            return problems;
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = ANSWER_MAPPER.readTree(answer);
        } catch (IOException e) {
            problems.add("回答不是合法 JSON: " + e.getMessage());
            return problems;
        }
        if (root == null || !root.isObject()) {
            problems.add("回答不是 JSON 对象");
            return problems;
        }
        if (spec.requiredAll() != null) {
            for (String field : spec.requiredAll()) {
                if (!isNonEmpty(root.get(field))) {
                    problems.add("回答缺少必填字段或为空: " + field);
                }
            }
        }
        if (spec.requiredAnyOf() != null && !spec.requiredAnyOf().isEmpty()) {
            boolean any = false;
            for (String field : spec.requiredAnyOf()) {
                if (isNonEmpty(root.get(field))) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                problems.add("以下字段至少需要一个非空: " + String.join(", ", spec.requiredAnyOf()));
            }
        }
        if (spec.expected() != null) {
            for (Map.Entry<String, String> entry : spec.expected().entrySet()) {
                com.fasterxml.jackson.databind.JsonNode node = root.get(entry.getKey());
                String actual = node == null ? "" : node.asText();
                if (!entry.getValue().equalsIgnoreCase(actual)) {
                    problems.add("字段 " + entry.getKey() + " 期望 " + entry.getValue() + "，实际 " + actual);
                }
            }
        }
        return problems;
    }

    private static boolean isNonEmpty(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isBlank();
        }
        if (node.isArray() || node.isObject()) {
            return node.size() > 0;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return true;
    }

    public static List<String> missingKeywords(String answer, List<String> keywords) {        List<String> missing = new ArrayList<>();
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

    private static boolean matchesAny(String path, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String normalized = pattern.replace('\\', '/');
            if (normalized.equals(path)) {
                return true;
            }
            String regex = normalized.replace(".", "\\.").replace("*", ".*");
            if (path.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private static String summarize(List<String> files) {
        if (files.size() <= MAX_REPORTED_FILES) {
            return String.join(", ", files);
        }
        return String.join(", ", files.subList(0, MAX_REPORTED_FILES)) + " 等 " + files.size() + " 个";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
