package com.paicli.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.headless.HeadlessResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 评测 runner：读任务定义 → 复制 fixture 到临时目录 → 子进程跑 headless → 验收 → 记录 → 清理。
 *
 * <p>每个任务都在独立临时目录里运行，并通过 -Duser.home 隔离记忆/审计等用户级文件。</p>
 */
public final class EvalRunnerMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USAGE = """
            用法: java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.EvalRunnerMain [选项]

              --tasks <文件>   任务定义 JSON（必填）
              --out <文件>     结果 JSON（必填）
              --jar <文件>     headless 使用的 fat jar，默认 target/paicli-1.0-SNAPSHOT.jar
              --keep-temp      保留每个任务的临时目录，便于排查

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

        TaskFile taskFile = MAPPER.readValue(tasksFile.toFile(), TaskFile.class);
        if (taskFile == null || taskFile.tasks() == null || taskFile.tasks().isEmpty()) {
            throw new IllegalArgumentException("任务文件为空: " + tasksFile);
        }
        validate(taskFile.tasks());

        long startNanos = System.nanoTime();
        List<RunResult> results = new ArrayList<>();
        for (TaskSpec task : taskFile.tasks()) {
            int runs = task.runs() == null || task.runs() < 1 ? 1 : task.runs();
            for (int run = 1; run <= runs; run++) {
                RunResult result = runOnce(task, run, jar, options.keepTemp);
                results.add(result);
                System.out.printf(Locale.ROOT,
                        "[runner] %s#%d passed=%s exit=%d reason=%s requests=%d tokens=%d/%d toolCalls=%d elapsed=%.2fs%s%n",
                        result.taskId(), result.run(), result.passed(), result.headlessExitCode(),
                        result.exitReason(), result.requests(), result.inputTokens(),
                        result.outputTokens(), result.toolCalls(), result.elapsedSeconds(),
                        result.failureReason() == null ? "" : " 原因=" + result.failureReason());
            }
        }

        int passed = (int) results.stream().filter(RunResult::passed).count();
        int failed = results.size() - passed;
        double duration = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        Path out = options.out.toAbsolutePath().normalize();
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        RunSummary summary = new RunSummary(
                tasksFile.toString(),
                jar.toString(),
                Instant.now().toString(),
                duration,
                results.size(),
                passed,
                failed,
                results
        );
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), summary);
        System.out.printf(Locale.ROOT, "[runner] 共 %d 次运行，通过 %d，失败 %d，耗时 %.2fs，结果: %s%n",
                results.size(), passed, failed, duration, out);
        return failed == 0 ? 0 : 1;
    }

    private static RunResult runOnce(TaskSpec task, int runIndex, Path jar, boolean keepTemp) {
        Path work = null;
        try {
            work = Files.createTempDirectory("paicli-eval-");
            if (task.fixture() != null && !task.fixture().isBlank()) {
                Path fixture = Path.of(task.fixture()).toAbsolutePath().normalize();
                FixtureCopier.copy(fixture, work);
            }
            Path home = work.resolve(".eval-home");
            Files.createDirectories(home);
            Path resultFile = work.resolve("headless-result.json");
            int timeoutSeconds = task.timeoutSeconds() == null ? 120 : task.timeoutSeconds();

            List<String> command = buildCommand(task, jar, work, home, resultFile, timeoutSeconds);
            ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile());
            builder.redirectOutput(work.resolve("headless.stdout.log").toFile());
            builder.redirectError(work.resolve("headless.stderr.log").toFile());
            Process process = builder.start();

            int hardTimeoutSeconds = Math.max(timeoutSeconds + 60, 90);
            boolean exited = process.waitFor(hardTimeoutSeconds, TimeUnit.SECONDS);
            int exitCode;
            if (exited) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                exitCode = process.isAlive() ? -1 : process.exitValue();
            }

            HeadlessResult headless = readResult(resultFile);
            Verifier.Result verify = Verifier.verify(task.verify(),
                    headless == null ? null : headless.answer(), work);
            boolean completed = headless != null && headless.completed();
            boolean passed = completed && verify.ok();
            String failureReason = passed ? null : buildFailure(headless, exitCode, verify);

            return new RunResult(
                    task.id(),
                    task.type(),
                    runIndex,
                    passed,
                    exitCode,
                    headless == null ? "no_result" : headless.exitReason(),
                    headless == null ? 0 : headless.requests(),
                    headless == null ? 0L : headless.inputTokens(),
                    headless == null ? 0L : headless.outputTokens(),
                    headless == null ? 0.0 : headless.elapsedSeconds(),
                    headless == null ? 0 : headless.toolCalls(),
                    headless == null ? "" : headless.answer(),
                    headless == null ? "未生成结果 JSON" : headless.error(),
                    verify.ok(),
                    verify.detail(),
                    failureReason
            );
        } catch (IOException e) {
            throw new IllegalStateException("任务 " + task.id() + " 运行失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务 " + task.id() + " 被中断", e);
        } finally {
            if (!keepTemp) {
                FixtureCopier.deleteRecursively(work);
            }
        }
    }

    private static String buildFailure(HeadlessResult headless, int exitCode, Verifier.Result verify) {
        List<String> reasons = new ArrayList<>();
        if (headless == null) {
            reasons.add("未生成结果 JSON（退出码 " + exitCode + "）");
        } else if (!headless.completed()) {
            String detail = headless.error() == null || headless.error().isBlank()
                    ? headless.exitReason()
                    : headless.exitReason() + ": " + headless.error();
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

    private static List<String> buildCommand(TaskSpec task, Path jar, Path work,
                                             Path home, Path resultFile, int timeoutSeconds) {
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
        command.add(String.valueOf(timeoutSeconds));
        command.add("--out");
        command.add(resultFile.toString());

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
        }
        return command;
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
            if (task.fixture() != null && !task.fixture().isBlank()) {
                Path fixture = Path.of(task.fixture());
                if (!Files.isDirectory(fixture)) {
                    throw new IllegalArgumentException("任务 " + task.id() + " 的 fixture 不存在: " + fixture);
                }
            }
        }
    }

    private static final class Options {
        private Path tasks;
        private Path out;
        private Path jar = Path.of("target", "paicli-1.0-SNAPSHOT.jar");
        private boolean keepTemp;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--tasks" -> options.tasks = Path.of(value(args, ++i, arg));
                    case "--out" -> options.out = Path.of(value(args, ++i, arg));
                    case "--jar" -> options.jar = Path.of(value(args, ++i, arg));
                    case "--keep-temp" -> options.keepTemp = true;
                    default -> throw new IllegalArgumentException("未知参数: " + arg);
                }
            }
            if (options.tasks == null || options.out == null) {
                throw new IllegalArgumentException("必须提供 --tasks 与 --out");
            }
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " 缺少取值");
            }
            return args[index];
        }
    }
}
