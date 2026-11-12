package com.paicli.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 验收探针：不运行 SimpleCLI，直接拿一份候选答案/工作区跑验收。
 *
 * <p>用途：演示“错误但看起来像样”的答案会被判失败。</p>
 */
public final class VerifyProbeMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VerifyProbeMain() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("[probe] 参数错误: " + e.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            System.err.println("[probe] 失败: " + t);
            System.exit(3);
        }
    }

    static int run(String[] args) throws IOException {
        Path tasks = null;
        String taskId = null;
        Path answer = null;
        String patch = null;
        boolean applyHidden = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tasks" -> tasks = Path.of(args[++i]);
                case "--task" -> taskId = args[++i];
                case "--answer" -> answer = Path.of(args[++i]);
                case "--patch" -> patch = args[++i];
                case "--no-hidden" -> applyHidden = false;
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (tasks == null || taskId == null || answer == null) {
            throw new IllegalArgumentException("需要 --tasks、--task、--answer");
        }
        TaskFile file = MAPPER.readValue(tasks.toFile(), TaskFile.class);
        final String wanted = taskId;
        TaskSpec task = file.tasks().stream()
                .filter(t -> wanted.equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到任务: " + wanted));

        Path work = Files.createTempDirectory("paicli-probe-");
        try {
            FixtureCopier.copy(Path.of(task.fixture()).toAbsolutePath().normalize(), work);
            Map<String, String> before = WorkspaceSnapshot.capture(work);
            if (patch != null) {
                applyPatch(work, Path.of(patch).toAbsolutePath().normalize());
            }
            Map<String, String> after = WorkspaceSnapshot.capture(work);
            List<String> changed = WorkspaceSnapshot.changed(before, after);
            if (applyHidden) {
                copyHiddenFiles(task.verify(), work);
            }
            String answerText = Files.readString(answer, StandardCharsets.UTF_8);
            Verifier.Result result = Verifier.verify(task.verify(), answerText, work, changed);
            System.out.printf("[probe] %s -> %s（%s）%n", task.id(), result.ok() ? "通过" : "失败", result.detail());
            return result.ok() ? 0 : 1;
        } finally {
            FixtureCopier.deleteRecursively(work);
        }
    }

    private static void applyPatch(Path work, Path patch) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("git", "apply", "--whitespace=nowarn", patch.toString())
                .directory(work.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            int code = builder.start().waitFor();
            if (code != 0) {
                throw new IOException("git apply 失败(" + code + "): " + patch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git apply 被中断", e);
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
}
