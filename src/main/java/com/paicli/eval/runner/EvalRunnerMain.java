package com.paicli.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.headless.HeadlessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 评测 runner：读任务定义 → 复制 fixture 到临时目录 → 子进程跑 headless → 验收 → 记录 → 清理。
 *
 * <p>每次运行都用单调时钟计时；基础设施故障（LLM/网络/引擎异常）判为无效并自动重试，
 * 无效尝试逐条追加到 invalid_runs.csv，原始记录不删除、不覆盖。</p>
 */
public final class EvalRunnerMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USAGE = """
            用法: java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.EvalRunnerMain [选项]

              --tasks <文件>      任务定义 JSON（必填）
              --out <文件>        结果 JSON（必填）
              --csv <文件>        额外输出 CSV（可选）
              --invalid-csv <文件> 无效运行记录，默认与 --out 同目录的 invalid_runs.csv
              --jar <文件>        headless 使用的 fat jar，默认 target/paicli-1.0-SNAPSHOT.jar
              --env-file <文件>   读取 KEY=VALUE 并注入子进程环境（不落盘）
              --provider <名字>   覆盖模型供应商
              --model <名字>      覆盖模型名
              --hard-timeout <秒> 单次运行硬超时，默认 300
              --max-retries <N>   无效运行最多重试次数，默认 3
              --only-task <id>    只跑指定任务（用于补跑单格）
              --only-run <N>      只跑第 N 次，配合 --only-task 使用
              --keep-temp         保留临时目录

            退出码: 0 全部通过 / 1 存在失败 / 2 参数错误 / 3 运行异常
            """;

    private EvalRunnerMain() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[runner] 参数错误: " + e.getMessage());
            System.err.println(USAGE);
            code = 2;
        } catch (Throwable t) {
            System.err.println("[runner] 运行异常: " + t);
            t.printStackTrace();
            code = 3;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        Options options = Options.parse(args);
        Path tasksFile = options.tasks.toAbsolutePath().normalize();
        Path jar = options.jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(tasksFile)) {
            throw new IllegalArgumentException("任务文件不存在: " + tasksFile);
        }
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("找不到 jar: " + jar + "，请先执行 mvn -DskipTests package");
        }

        Map<String, String> environment = options.envFile == null ? Map.of() : readEnvFile(options.envFile);

        TaskFile taskFile = MAPPER.readValue(tasksFile.toFile(), TaskFile.class);
        if (taskFile == null || taskFile.tasks() == null || taskFile.tasks().isEmpty()) {
            throw new IllegalArgumentException("任务文件为空: " + tasksFile);
        }
        validate(taskFile.tasks());

        Path out = options.out.toAbsolutePath().normalize();
        Path invalidCsv = options.invalidCsv != null
                ? options.invalidCsv.toAbsolutePath().normalize()
                : defaultInvalidCsv(out);

        long startNanos = System.nanoTime();
        List<RunResult> results = new ArrayList<>();
        int invalidAttempts = 0;
        for (TaskSpec task : taskFile.tasks()) {
            for (int run : selectedRuns(task, options)) {
                RunResult finalResult = null;
                int maxAttempts = options.maxRetries + 1;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    RunResult result = runOnce(task, run, attempt, jar, options, environment);
                    finalResult = result;
                    if (result.valid()) {
                        break;
                    }
                    invalidAttempts++;
                    InvalidRunCsv.append(invalidCsv, result, "auto-retry");
                    System.out.printf(Locale.ROOT,
                            "[runner] %s#%d attempt=%d invalid reason=%s%s%n",
                            result.taskId(), result.run(), result.attempt(), result.exitReason(),
                            attempt < maxAttempts ? " -> 重试" : " -> 已达重试上限，保留无效记录");
                }
                results.add(finalResult);
                System.out.printf(Locale.ROOT,
                        "[runner] %s#%d passed=%s valid=%s outcome=%s exit=%d reason=%s requests=%d tokens=%d/%d toolCalls=%d elapsed=%.2fs%s%n",
                        finalResult.taskId(), finalResult.run(), finalResult.passed(), finalResult.valid(),
                        finalResult.outcome(), finalResult.headlessExitCode(), finalResult.exitReason(),
                        finalResult.requests(), finalResult.inputTokens(), finalResult.outputTokens(),
                        finalResult.toolCalls(), finalResult.elapsedSeconds(),
                        finalResult.failureReason() == null ? "" : " 原因=" + finalResult.failureReason());
            }
        }

        int passed = (int) results.stream().filter(r -> r.valid() && r.passed()).count();
        int invalid = (int) results.stream().filter(r -> !r.valid()).count();
        int failed = results.size() - passed;
        double duration = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        createParent(out);
        RunSummary summary = new RunSummary(
                tasksFile.toString(),
                jar.toString(),
                Instant.now().toString(),
                duration,
                results.size(),
                passed,
                failed,
                invalid,
                results
        );
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), summary);
        if (options.csv != null) {
            Path csv = options.csv.toAbsolutePath().normalize();
            createParent(csv);
            writeCsv(csv, results);
        }
        System.out.printf(Locale.ROOT,
                "[runner] 共 %d 格，通过 %d，失败 %d，无效 %d（重试无效尝试 %d 次），耗时 %.2fs，结果: %s%n",
                results.size(), passed, failed, invalid, invalidAttempts, duration, out);
        return failed == 0 && invalid == 0 ? 0 : 1;
    }

    private static List<Integer> selectedRuns(TaskSpec task, Options options) {
        int runs = task.runs() == null || task.runs() < 1 ? 1 : task.runs();
        if (options.onlyTask == null) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= runs; i++) {
                all.add(i);
            }
            return all;
        }
        if (!options.onlyTask.equals(task.id())) {
            return List.of();
        }
        if (options.onlyRun == null) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= runs; i++) {
                all.add(i);
            }
            return all;
        }
        if (options.onlyRun < 1 || options.onlyRun > runs) {
            throw new IllegalArgumentException("--only-run 超出范围: " + options.onlyRun + "，任务 " + task.id() + " 只有 " + runs + " 次");
        }
        return List.of(options.onlyRun);
    }

    private static RunResult runOnce(TaskSpec task, int runIndex, int attempt, Path jar,
                                     Options options, Map<String, String> environment) {
        Path work = null;
        Path meta = null;
        long startNanos = System.nanoTime();
        try {
            work = Files.createTempDirectory("paicli-eval-");
            meta = Files.createTempDirectory("paicli-eval-meta-");
            if (task.fixture() != null && !task.fixture().isBlank()) {
                Path fixture = Path.of(task.fixture()).toAbsolutePath().normalize();
                FixtureCopier.copy(fixture, work);
            }
            Path home = work.resolve(".eval-home");
            Files.createDirectories(home);
            Path resultFile = meta.resolve("headless-result.json");
            int taskTimeout = task.timeoutSeconds() == null ? 120 : task.timeoutSeconds();
            int headlessTimeout = Math.max(1, Math.min(taskTimeout, options.hardTimeout - 5));

            Map<String, String> before = WorkspaceSnapshot.capture(work);
            List<String> command = buildCommand(task, jar, work, home, resultFile, headlessTimeout, options);
            ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
            builder.redirectOutput(meta.resolve("headless.stdout.log").toFile());
            builder.redirectError(meta.resolve("headless.stderr.log").toFile());
            if (!environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            Process process = builder.start();

            boolean exited = process.waitFor(options.hardTimeout, TimeUnit.SECONDS);
            int exitCode;
            if (exited) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                exitCode = process.isAlive() ? -1 : process.exitValue();
            }
            double elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0;

            if (!exited) {
                return new RunResult(
                        task.id(), task.type(), runIndex, attempt,
                        false, false, OutcomeClassifier.classify(false, false, "timeout"),
                        exitCode, "timeout",
                        0, 0L, 0L, elapsed, 0, "",
                        "硬超时（>" + options.hardTimeout + "s），已强制结束",
                        false, "硬超时，未执行验收",
                        "硬超时（>" + options.hardTimeout + "s）"
                );
            }

            Map<String, String> after = WorkspaceSnapshot.capture(work);
            List<String> changedFiles = WorkspaceSnapshot.changed(before, after);

            HeadlessResult headless = readResult(resultFile);
            String exitReason = headless == null ? "no_result" : headless.exitReason();
            boolean valid = RunValidity.isValid(exitReason);
            boolean completed = headless != null && headless.completed();

            Verifier.Result verify;
            if (completed) {
                copyHiddenFiles(task.verify(), work);
                verify = Verifier.verify(task.verify(),
                        headless == null ? null : headless.answer(), work, changedFiles);
            } else {
                verify = new Verifier.Result(false, "运行未正常结束，跳过验收");
            }

            boolean passed = completed && verify.ok();
            String outcome = OutcomeClassifier.classify(passed, valid, exitReason);
            String failureReason = passed ? null : buildFailure(headless, exitCode, verify, exitReason);

            return new RunResult(
                    task.id(), task.type(), runIndex, attempt,
                    passed, valid, outcome,
                    exitCode, exitReason,
                    headless == null ? 0 : headless.requests(),
                    headless == null ? 0L : headless.inputTokens(),
                    headless == null ? 0L : headless.outputTokens(),
                    elapsed,
                    headless == null ? 0 : headless.toolCalls(),
                    headless == null ? "" : headless.answer(),
                    headless == null ? "未生成结果 JSON" : headless.error(),
                    verify.ok(), verify.detail(), failureReason
            );
        } catch (IOException e) {
            throw new IllegalStateException("任务 " + task.id() + " 运行失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务 " + task.id() + " 被中断", e);
        } finally {
            if (!options.keepTemp) {
                FixtureCopier.deleteRecursively(work);
                FixtureCopier.deleteRecursively(meta);
            }
        }
    }

    private static void copyHiddenFiles(TaskSpec.VerifySpec spec, Path work) throws IOException {
        if (spec == null || spec.hiddenFiles() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : spec.hiddenFiles().entrySet()) {
            Path source = Path.of(entry.getValue()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new IOException("隐藏测试文件不存在: " + source);
            }
            Path target = work.resolve(entry.getKey()).normalize();
            if (!target.startsWith(work)) {
                throw new IOException("隐藏测试目标路径越界: " + entry.getKey());
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String buildFailure(HeadlessResult headless, int exitCode,
                                       Verifier.Result verify, String exitReason) {
        List<String> reasons = new ArrayList<>();
        if (headless == null) {
            reasons.add("未生成结果 JSON（退出码 " + exitCode + "）");
        } else if (!headless.completed()) {
            String detail = headless.error() == null || headless.error().isBlank()
                    ? exitReason
                    : exitReason + ": " + headless.error();
            reasons.add("运行未正常结束（" + detail + "）");
        }
        if (!verify.ok()) {
            reasons.add(verify.detail());
        }
        return reasons.isEmpty() ? "未通过" : String.join("；", reasons);
    }

    private static HeadlessResult readResult(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), HeadlessResult.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> buildCommand(TaskSpec task, Path jar, Path work, Path home,
                                             Path resultFile, int headlessTimeout, Options options) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djava.awt.headless=true");
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(jar.toString());
        command.add("com.paicli.eval.headless.HeadlessMain");
        command.add("--prompt");
        command.add(task.prompt());
        command.add("--workdir");
        command.add(work.toString());
        command.add("--mode");
        command.add(task.mode() == null ? "react" : task.mode());
        command.add("--approval");
        command.add(task.approval() == null ? "auto" : task.approval());
        command.add("--max-requests");
        command.add(String.valueOf(task.maxRequests() == null ? 40 : task.maxRequests()));
        command.add("--timeout");
        command.add(String.valueOf(headlessTimeout));
        command.add("--out");
        command.add(resultFile.toString());
        if (options.provider != null) {
            command.add("--provider");
            command.add(options.provider);
        }
        if (options.model != null) {
            command.add("--model");
            command.add(options.model);
        }

        TaskSpec.StubSpec stub = task.stub();
        if (stub != null) {
            command.add("--stub");
            if (stub.answer() != null) {
                command.add("--stub-answer");
                command.add(stub.answer());
            }
            if (stub.delayMs() != null) {
                command.add("--stub-delay-ms");
                command.add(String.valueOf(stub.delayMs()));
            }
            if (Boolean.TRUE.equals(stub.loop())) {
                command.add("--stub-loop");
            }
            if (Boolean.TRUE.equals(stub.fail())) {
                command.add("--stub-fail");
            }
        }
        return command;
    }

    private static Path defaultInvalidCsv(Path out) {
        Path parent = out.getParent();
        return parent == null ? Path.of("invalid_runs.csv") : parent.resolve("invalid_runs.csv");
    }

    private static Map<String, String> readEnvFile(Path envFile) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
        return values;
    }

    private static void writeCsv(Path path, List<RunResult> results) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("taskId,run,attempt,passed,valid,outcome,requests,inputTokens,outputTokens,totalTokens,elapsedSeconds,exitReason\n");
        for (RunResult result : results) {
            builder.append(csv(result.taskId())).append(',')
                    .append(result.run()).append(',')
                    .append(result.attempt()).append(',')
                    .append(result.passed()).append(',')
                    .append(result.valid()).append(',')
                    .append(result.outcome()).append(',')
                    .append(result.requests()).append(',')
                    .append(result.inputTokens()).append(',')
                    .append(result.outputTokens()).append(',')
                    .append(result.inputTokens() + result.outputTokens()).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", result.elapsedSeconds())).append(',')
                    .append(csv(result.exitReason())).append('\n');
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path candidate = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return Files.isExecutable(candidate) ? candidate.toString() : "java";
    }

    private static void validate(List<TaskSpec> tasks) {
        for (TaskSpec task : tasks) {
            if (task == null) {
                throw new IllegalArgumentException("任务列表包含 null");
            }
            if (task.id() == null || task.id().isBlank()) {
                throw new IllegalArgumentException("任务缺少 id");
            }
            if (task.prompt() == null || task.prompt().isBlank()) {
                throw new IllegalArgumentException("任务 " + task.id() + " 缺少 prompt");
            }
            if (task.fixture() != null && !task.fixture().isBlank()
                    && !Files.isDirectory(Path.of(task.fixture()))) {
                throw new IllegalArgumentException("任务 " + task.id() + " 的 fixture 不存在: " + task.fixture());
            }
        }
    }

    private static final class Options {
        private Path tasks;
        private Path out;
        private Path csv;
        private Path invalidCsv;
        private Path jar = Path.of("target", "paicli-1.0-SNAPSHOT.jar");
        private Path envFile;
        private String provider;
        private String model;
        private int hardTimeout = 300;
        private int maxRetries = 3;
        private String onlyTask;
        private Integer onlyRun;
        private boolean keepTemp;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--tasks" -> options.tasks = Path.of(value(args, ++i, arg));
                    case "--out" -> options.out = Path.of(value(args, ++i, arg));
                    case "--csv" -> options.csv = Path.of(value(args, ++i, arg));
                    case "--invalid-csv" -> options.invalidCsv = Path.of(value(args, ++i, arg));
                    case "--jar" -> options.jar = Path.of(value(args, ++i, arg));
                    case "--env-file" -> options.envFile = Path.of(value(args, ++i, arg));
                    case "--provider" -> options.provider = value(args, ++i, arg);
                    case "--model" -> options.model = value(args, ++i, arg);
                    case "--hard-timeout" -> options.hardTimeout = intValue(args, ++i, arg);
                    case "--max-retries" -> options.maxRetries = intValue(args, ++i, arg);
                    case "--only-task" -> options.onlyTask = value(args, ++i, arg);
                    case "--only-run" -> options.onlyRun = intValue(args, ++i, arg);
                    case "--keep-temp" -> options.keepTemp = true;
                    default -> throw new IllegalArgumentException("未知参数: " + arg);
                }
            }
            if (options.tasks == null || options.out == null) {
                throw new IllegalArgumentException("必须提供 --tasks 与 --out");
            }
            if (options.hardTimeout < 10) {
                throw new IllegalArgumentException("--hard-timeout 至少 10 秒");
            }
            if (options.maxRetries < 0) {
                throw new IllegalArgumentException("--max-retries 不能为负数");
            }
            if (options.onlyRun != null && options.onlyTask == null) {
                throw new IllegalArgumentException("--only-run 必须配合 --only-task");
            }
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " 缺少取值");
            }
            return args[index];
        }

        private static int intValue(String[] args, int index, String option) {
            String raw = value(args, index, option);
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " 需要整数: " + raw);
            }
        }
    }
}
