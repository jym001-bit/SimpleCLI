package com.paicli.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 读取 EvalRunnerMain 的结果 JSON，按模式汇总（仅统计 valid 运行）。
 */
public final class ModeSummaryMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModeSummaryMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[summary] 参数错误: " + e.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            System.err.println("[summary] 失败: " + t);
            System.exit(3);
        }
    }

    static void run(String[] args) throws IOException {
        Path result = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--result" -> result = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (result == null) {
            throw new IllegalArgumentException("必须提供 --result");
        }
        RunSummary summary = MAPPER.readValue(result.toFile(), RunSummary.class);
        List<RunResult> valid = summary.runs().stream().filter(RunResult::valid).toList();

        StringBuilder text = new StringBuilder();
        text.append("result: ").append(result.toAbsolutePath().normalize()).append('\n');
        text.append("agent_commit: ").append(summary.agentCommit()).append('\n');
        text.append("eval_rules_commit: ").append(summary.evalRulesCommit()).append('\n');
        text.append("runs: total=").append(summary.totalRuns())
                .append(" valid=").append(valid.size())
                .append(" invalid=").append(summary.totalRuns() - valid.size()).append('\n');

        appendModeTable(text, valid);
        appendTaskModeTable(text, valid);
        appendReasonTable(text, valid);
        appendTopRequests(text, valid);

        String rendered = text.toString();
        System.out.print(rendered);
        if (out != null) {
            Path parent = out.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, rendered, StandardCharsets.UTF_8);
        }
    }

    private static void appendModeTable(StringBuilder text, List<RunResult> valid) {
        Map<String, List<RunResult>> byMode = new LinkedHashMap<>();
        for (String mode : new String[]{"react", "plan", "team"}) {
            byMode.put(mode, new ArrayList<>());
        }
        for (RunResult run : valid) {
            byMode.computeIfAbsent(run.mode(), key -> new ArrayList<>()).add(run);
        }

        text.append("\n== 按模式汇总（仅 valid） ==\n");
        text.append(String.format(Locale.ROOT, "%-6s %3s %6s %18s %22s%n",
                "mode", "n", "pass", "requests med/min/max", "elapsed(s) med/min/max"));
        for (Map.Entry<String, List<RunResult>> entry : byMode.entrySet()) {
            List<RunResult> runs = entry.getValue();
            if (runs.isEmpty()) {
                continue;
            }
            long passed = runs.stream().filter(RunResult::passed).count();
            Stats.Summary requests = Stats.of(runs.stream().map(RunResult::requests).toList());
            Stats.Summary elapsed = Stats.of(runs.stream().map(RunResult::elapsedSeconds).toList());
            text.append(String.format(Locale.ROOT, "%-6s %3d %6d %8.1f / %3.0f / %3.0f %10.2f / %6.2f / %6.2f%n",
                    entry.getKey(), runs.size(), passed,
                    requests.median(), requests.min(), requests.max(),
                    elapsed.median(), elapsed.min(), elapsed.max()));
        }
    }

    private static void appendTaskModeTable(StringBuilder text, List<RunResult> valid) {
        Map<String, List<RunResult>> byTaskMode = new TreeMap<>();
        for (RunResult run : valid) {
            byTaskMode.computeIfAbsent(run.taskId() + "\t" + run.mode(), key -> new ArrayList<>()).add(run);
        }
        text.append("\n== 任务 × 模式（仅 valid） ==\n");
        text.append(String.format(Locale.ROOT, "%-26s %-6s %3s %6s %18s %22s%n",
                "task", "mode", "n", "pass", "requests med/min/max", "elapsed(s) med/min/max"));
        for (Map.Entry<String, List<RunResult>> entry : byTaskMode.entrySet()) {
            String[] key = entry.getKey().split("\t");
            List<RunResult> runs = entry.getValue();
            long passed = runs.stream().filter(RunResult::passed).count();
            Stats.Summary requests = Stats.of(runs.stream().map(RunResult::requests).toList());
            Stats.Summary elapsed = Stats.of(runs.stream().map(RunResult::elapsedSeconds).toList());
            text.append(String.format(Locale.ROOT, "%-26s %-6s %3d %6d %8.1f / %3.0f / %3.0f %10.2f / %6.2f / %6.2f%n",
                    key[0], key[1], runs.size(), passed,
                    requests.median(), requests.min(), requests.max(),
                    elapsed.median(), elapsed.min(), elapsed.max()));
        }
    }

    private static void appendReasonTable(StringBuilder text, List<RunResult> valid) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (RunResult run : valid) {
            counts.computeIfAbsent(run.mode(), key -> new TreeMap<>())
                    .merge(run.exitReason(), 1, Integer::sum);
        }
        text.append("\n== 结束原因（仅 valid） ==\n");
        for (Map.Entry<String, Map<String, Integer>> entry : counts.entrySet()) {
            text.append(entry.getKey()).append(": ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> reason : entry.getValue().entrySet()) {
                parts.add(reason.getKey() + "=" + reason.getValue());
            }
            text.append(String.join(", ", parts)).append('\n');
        }
    }

    private static void appendTopRequests(StringBuilder text, List<RunResult> valid) {
        List<RunResult> sorted = new ArrayList<>(valid);
        sorted.sort(Comparator.comparingInt(RunResult::requests).reversed()
                .thenComparing(RunResult::taskId)
                .thenComparing(RunResult::mode));
        text.append("\n== 请求数 Top3（仅 valid） ==\n");
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            RunResult run = sorted.get(i);
            text.append(String.format(Locale.ROOT, "%d. %s[%s]#%d requests=%d elapsed=%.2fs traceId=%s%n",
                    i + 1, run.taskId(), run.mode(), run.run(), run.requests(), run.elapsedSeconds(),
                    run.traceId() == null || run.traceId().isBlank() ? "none" : run.traceId()));
        }
    }
}
