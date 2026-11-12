package com.paicli.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 参考解法校验：证明验收标准本身没写错。
 *
 * <ul>
 *   <li>改动类任务（有 patch）：不加补丁必须<b>失败</b>，加补丁必须<b>通过</b>；</li>
 *   <li>只读类任务（有 answer）：参考答案必须命中全部关键词；</li>
 *   <li>配置了 mutateFile 的任务：故意篡改该文件后验收必须<b>失败</b>。</li>
 * </ul>
 */
public final class ReferenceCheckMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USAGE = """
            用法: java -cp target/paicli-1.0-SNAPSHOT.jar com.paicli.eval.runner.ReferenceCheckMain [选项]

              --tasks <文件>   任务定义 JSON（必填）
              --keep-temp      保留临时目录，便于排查

            退出码: 0 全部符合预期 / 1 有不符合项 / 2 参数错误 / 3 运行异常
            """;

    private ReferenceCheckMain() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[reference] 参数错误: " + e.getMessage());
            System.err.println(USAGE);
            code = 2;
        } catch (Throwable t) {
            System.err.println("[reference] 运行异常: " + t);
            t.printStackTrace();
            code = 3;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        Options options = Options.parse(args);
        TaskFile taskFile = MAPPER.readValue(options.tasks.toFile(), TaskFile.class);
        if (taskFile == null || taskFile.tasks() == null) {
            throw new IllegalArgumentException("任务文件为空: " + options.tasks);
        }

        int checks = 0;
        int failures = 0;
        for (TaskSpec task : taskFile.tasks()) {
            TaskSpec.ReferenceSpec reference = task.reference();
            if (reference == null) {
                System.out.printf(Locale.ROOT, "[reference] %-22s SKIP（没有参考解法）%n", task.id());
                continue;
            }
            if (reference.patch() != null) {
                Outcome baseline = verifyCopy(task, null, null, null, options.keepTemp);
                checks++;
                boolean baselineOk = !baseline.verify().ok();
                if (!baselineOk) {
                    failures++;
                }
                System.out.printf(Locale.ROOT,
                        "[reference] %-22s 不打补丁 -> %s（期望失败）%s%n",
                        task.id(), baseline.verify().ok() ? "通过" : "失败",
                        baselineOk ? "" : "  <-- 不符合预期: " + baseline.verify().detail());

                Outcome patched = verifyCopy(task, reference.patch(), null, null, options.keepTemp);
                checks++;
                boolean patchedOk = patched.verify().ok();
                if (!patchedOk) {
                    failures++;
                }
                System.out.printf(Locale.ROOT,
                        "[reference] %-22s 打补丁   -> %s（期望通过）%s%n",
                        task.id(), patched.verify().ok() ? "通过" : "失败",
                        patchedOk ? "" : "  <-- 不符合预期: " + patched.verify().detail());
            } else if (reference.answer() != null) {
                String answer = Files.readString(Path.of(reference.answer()), StandardCharsets.UTF_8);
                Verifier.Result result = Verifier.verify(task.verify(), answer, null, List.of());
                checks++;
                if (!result.ok()) {
                    failures++;
                }
                System.out.printf(Locale.ROOT,
                        "[reference] %-22s 参考答案 -> %s（期望通过）%s%n",
                        task.id(), result.ok() ? "通过" : "失败",
                        result.ok() ? "" : "  <-- 不符合预期: " + result.detail());

                Verifier.Result empty = Verifier.verify(task.verify(), "", null, List.of());
                checks++;
                boolean emptyOk = !empty.ok();
                if (!emptyOk) {
                    failures++;
                }
                System.out.printf(Locale.ROOT,
                        "[reference] %-22s 空答案   -> %s（期望失败）%s%n",
                        task.id(), empty.ok() ? "通过" : "失败",
                        emptyOk ? "" : "  <-- 不符合预期: " + empty.detail());
            }

            if (task.mutateFile() != null && !task.mutateFile().isBlank()) {
                String answer = reference.answer() == null
                        ? null
                        : Files.readString(Path.of(reference.answer()), StandardCharsets.UTF_8);
                Outcome mutated = verifyCopy(task, reference.patch(), task.mutateFile(), answer, options.keepTemp);
                checks++;
                boolean mutationOk = !mutated.verify().ok();
                if (!mutationOk) {
                    failures++;
                }
                System.out.printf(Locale.ROOT,
                        "[reference] %-22s 篡改 %s -> %s（期望失败）%s%n",
                        task.id(), task.mutateFile(), mutated.verify().ok() ? "通过" : "失败",
                        mutationOk ? "" : "  <-- 不符合预期: " + mutated.verify().detail());
            }
        }

        System.out.printf(Locale.ROOT, "[reference] 共 %d 项检查，%d 项符合预期，%d 项不符合%n",
                checks, checks - failures, failures);
        return failures == 0 ? 0 : 1;
    }

    private record Outcome(Verifier.Result verify) {
    }

    private static Outcome verifyCopy(TaskSpec task, String patch, String mutateFile,
                                      String answer, boolean keepTemp) throws IOException {
        Path work = Files.createTempDirectory("paicli-ref-");
        try {
            Path fixture = Path.of(task.fixture()).toAbsolutePath().normalize();
            FixtureCopier.copy(fixture, work);
            Map<String, String> before = WorkspaceSnapshot.capture(work);

            if (patch != null) {
                applyPatch(work, Path.of(patch).toAbsolutePath().normalize());
            }
            if (mutateFile != null) {
                Path target = work.resolve(mutateFile);
                Files.createDirectories(target.getParent());
                Files.writeString(target,
                        Files.exists(target) ? Files.readString(target) + "\ntampered by reference check\n"
                                : "tampered by reference check\n",
                        StandardCharsets.UTF_8);
            }

            Map<String, String> after = WorkspaceSnapshot.capture(work);
            List<String> changed = WorkspaceSnapshot.changed(before, after);
            copyHiddenFiles(task.verify(), work);
            Verifier.Result result = Verifier.verify(task.verify(), answer, work, changed);
            return new Outcome(result);
        } finally {
            if (!keepTemp) {
                FixtureCopier.deleteRecursively(work);
            }
        }
    }

    private static void applyPatch(Path work, Path patch) throws IOException {
        int init = runProcess(work, List.of("git", "init", "-q"));
        if (init != 0) {
            throw new IOException("git init 失败: " + init);
        }
        int applied = runProcess(work, List.of("git", "apply", "--whitespace=nowarn", patch.toString()));
        if (applied != 0) {
            throw new IOException("git apply 失败(" + applied + "): " + patch);
        }
    }

    private static int runProcess(Path workdir, List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workdir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断: " + command, e);
        }
    }

    private static void copyHiddenFiles(TaskSpec.VerifySpec spec, Path work) throws IOException {
        if (spec == null || spec.hiddenFiles() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : spec.hiddenFiles().entrySet()) {
            Path source = Path.of(entry.getValue()).toAbsolutePath().normalize();
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

    private static final class Options {
        private Path tasks;
        private boolean keepTemp;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--tasks" -> {
                        if (++i >= args.length) {
                            throw new IllegalArgumentException("--tasks 缺少取值");
                        }
                        options.tasks = Path.of(args[i]);
                    }
                    case "--keep-temp" -> options.keepTemp = true;
                    default -> throw new IllegalArgumentException("未知参数: " + arg);
                }
            }
            if (options.tasks == null) {
                throw new IllegalArgumentException("必须提供 --tasks");
            }
            return options;
        }
    }
}
