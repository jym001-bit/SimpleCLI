package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.policy.PathGuard;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** User-authored, deterministic acceptance conditions for a single ReAct turn. */
public final class TaskAcceptance {
    public enum Verdict { VERIFIED, FAILED, INSUFFICIENT, UNVERIFIABLE }
    public record Check(String condition, String source, Verdict verdict, String evidence) {}
    public record Result(Verdict verdict, List<Check> checks) {
        public Result { checks = List.copyOf(checks); }
        public String terminalSummary() {
            return switch (verdict) {
                case VERIFIED -> "✅ 任务验收：已由文件状态或真实命令结果验证";
                case FAILED -> "❌ 任务验收：未通过；" + details();
                case INSUFFICIENT -> "⚠️ 任务验收：证据不足；" + details();
                case UNVERIFIABLE -> "ℹ️ 任务验收：未提供可确定性检查的条件，最终回答未经客观验证";
            };
        }
        private String details() {
            return checks.stream().filter(check -> check.verdict() == verdict)
                    .map(Check::evidence).findFirst().orElse("缺少验收证据");
        }
    }

    private enum Kind { FILE_EXISTS, FILE_CONTAINS, COMMAND_SUCCESS, INVALID }
    private record Criterion(Kind kind, String target, String expected, String description, String source) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_VERIFIED_FILE_BYTES = 5L * 1024 * 1024;
    private final PathGuard paths;
    private final List<Criterion> criteria;
    private final List<ToolExecutionResult> observedTools = new ArrayList<>();

    private TaskAcceptance(TaskContract contract, String projectPath) {
        this.paths = new PathGuard(projectPath);
        this.criteria = parse(contract);
    }

    public static TaskAcceptance fromUserInput(String submittedInput, String projectPath) {
        return fromContract(TaskContract.understand(submittedInput), projectPath);
    }

    public static TaskAcceptance fromContract(TaskContract contract, String projectPath) {
        return new TaskAcceptance(java.util.Objects.requireNonNull(contract, "contract"), projectPath);
    }

    /** Only explicit user lines become criteria; model-generated suggestions never do. */
    private static List<Criterion> parse(TaskContract contract) {
        List<Criterion> parsed = new ArrayList<>();
        for (String line : contract.explicitAcceptanceLines())
            addCriterion(parsed, line, contract.userConfirmed() ? "USER_CONFIRMED" : "USER");
        for (String line : contract.acceptanceLines()) {
            addCriterion(parsed, line, "USER_INTERPRETED");
        }
        return List.copyOf(parsed);
    }

    private static void addCriterion(List<Criterion> parsed, String body, String source) {
        if (parsed.stream().anyMatch(item -> item.description().equals(body))) return;
            if (body.startsWith("文件存在 ") && !body.substring(5).isBlank()) {
                parsed.add(new Criterion(Kind.FILE_EXISTS, body.substring(5).trim(), null, body, source));
            } else if (body.startsWith("文件包含 ") && body.substring(5).contains(" | ")) {
                String[] parts = body.substring(5).split(" \\| ", 2);
                parsed.add(parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()
                        ? new Criterion(Kind.FILE_CONTAINS, parts[0].trim(), parts[1], body, source)
                        : new Criterion(Kind.INVALID, null, null, body, source));
            } else if (body.startsWith("命令成功 ") && !body.substring(5).isBlank()) {
                parsed.add(new Criterion(Kind.COMMAND_SUCCESS, body.substring(5).trim(), null, body, source));
            } else {
                parsed.add(new Criterion(Kind.INVALID, null, null, body, source));
            }
    }

    public synchronized void observe(List<ToolExecutionResult> results) {
        if (results != null) observedTools.addAll(results);
    }

    public static Result notEvaluated() {
        return new Result(Verdict.INSUFFICIENT, List.of(new Check("任务未正常结束", "HARNESS",
                Verdict.INSUFFICIENT, "执行未正常结束，未进行最终验收")));
    }

    public synchronized Result verify() {
        if (criteria.isEmpty()) return new Result(Verdict.UNVERIFIABLE, List.of());
        List<Check> checks = criteria.stream().map(this::check).toList();
        Verdict verdict = checks.stream().anyMatch(check -> check.verdict() == Verdict.FAILED)
                ? Verdict.FAILED : checks.stream().anyMatch(check -> check.verdict() == Verdict.INSUFFICIENT)
                ? Verdict.INSUFFICIENT : Verdict.VERIFIED;
        return new Result(verdict, checks);
    }

    private Check check(Criterion criterion) {
        if (criterion.kind() == Kind.INVALID)
            return result(criterion, Verdict.INSUFFICIENT, "无法解析用户验收条件");
        if (criterion.kind() == Kind.COMMAND_SUCCESS) return checkCommand(criterion);
        try {
            Path file = paths.resolveSafe(criterion.target());
            if (!Files.isRegularFile(file)) return result(criterion, Verdict.FAILED, "目标文件不存在：" + criterion.target());
            if (criterion.kind() == Kind.FILE_EXISTS)
                return result(criterion, Verdict.VERIFIED, "已检查实际文件：" + criterion.target());
            if (Files.size(file) > MAX_VERIFIED_FILE_BYTES)
                return result(criterion, Verdict.INSUFFICIENT, "文件过大，未检查内容：" + criterion.target());
            boolean contains = Files.readString(file).contains(criterion.expected());
            return result(criterion, contains ? Verdict.VERIFIED : Verdict.FAILED,
                    (contains ? "实际文件包含指定内容：" : "实际文件缺少指定内容：") + criterion.target());
        } catch (IOException | RuntimeException error) {
            return result(criterion, Verdict.INSUFFICIENT,
                    "无法安全检查文件：" + criterion.target() + "（" + error.getClass().getSimpleName() + "）");
        }
    }

    private Check checkCommand(Criterion criterion) {
        ToolExecutionResult last = null;
        for (ToolExecutionResult tool : observedTools) {
            if (!"execute_command".equals(tool.name())) continue;
            try {
                JsonNode arguments = JSON.readTree(tool.argumentsJson());
                if (arguments != null && criterion.target().equals(arguments.path("command").asText(null))) last = tool;
            } catch (IOException | RuntimeException ignored) {
                // An unparseable invocation is not evidence for the exact requested command.
            }
        }
        if (last == null) return result(criterion, Verdict.INSUFFICIENT,
                "没有观察到指定命令的实际执行结果：" + criterion.target());
        return result(criterion, last.successful() ? Verdict.VERIFIED : Verdict.FAILED,
                (last.successful() ? "命令实际成功" : "命令执行失败或被拒绝") + "，工具调用 ID：" + last.id());
    }

    private static Check result(Criterion criterion, Verdict verdict, String evidence) {
        return new Check(criterion.description(), criterion.source(), verdict, evidence);
    }
}
