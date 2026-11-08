package com.paicli.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A conservative understanding of the user's submitted task, before a strategy runs. */
public record TaskContract(String goal, List<String> constraints, List<String> deliverables,
                           List<String> acceptanceLines, List<String> explicitAcceptanceLines,
                           boolean needsConfirmation, String uncertainty, SemanticUnderstanding semantic,
                           boolean userConfirmed) {
    public enum Source { USER_EXPLICIT, RULE_INTERPRETED, MODEL_CANDIDATE, USER_CONFIRMED }
    public record Candidate(String value, Source source) {}
    public record SemanticUnderstanding(Candidate intent, Candidate taskType, Candidate normalizedGoal,
            List<Candidate> constraints, List<Candidate> scope, List<Candidate> deliverables,
            List<Candidate> dependencies, List<Candidate> unknowns,
            List<Candidate> candidateAcceptanceCriteria, Candidate clarificationQuestion) {
        public SemanticUnderstanding {
            constraints = List.copyOf(constraints); scope = List.copyOf(scope);
            deliverables = List.copyOf(deliverables); dependencies = List.copyOf(dependencies);
            unknowns = List.copyOf(unknowns);
            candidateAcceptanceCriteria = List.copyOf(candidateAcceptanceCriteria);
        }
    }
    private static final Pattern CREATE_FILE = Pattern.compile("(?:创建|生成|写入|保存)(?:一个|一份)?(?:名为)?(?:文件)?[\\s：:]*[`\"]?([\\w./-]+\\.[\\w]+)[`\"]?(?:文件)?");
    private static final Pattern FILE_CONTAINS = Pattern.compile("文件[\\s：:]*[`\"]?([\\w./-]+\\.[\\w]+)[`\"]?[\\s]*(?:必须)?包含[\\s：:]*[`\"“]([^`\"”\\r\\n]+)[`\"”]");
    private static final Pattern RUN_COMMAND = Pattern.compile("(?:运行|执行)(?:命令)?[\\s：:]*`([^`\\r\\n]+)`(?:并|，|,|\\s)*(?:确认|要求|确保)?(?:成功|通过|退出码为0)");

    public TaskContract {
        constraints = List.copyOf(constraints);
        deliverables = List.copyOf(deliverables);
        acceptanceLines = List.copyOf(acceptanceLines);
        explicitAcceptanceLines = List.copyOf(explicitAcceptanceLines);
    }

    public TaskContract(String goal, List<String> constraints, List<String> deliverables,
                        List<String> acceptanceLines, List<String> explicitAcceptanceLines,
                        boolean needsConfirmation, String uncertainty) {
        this(goal, constraints, deliverables, acceptanceLines, explicitAcceptanceLines,
                needsConfirmation, uncertainty, null, false);
    }

    public TaskContract(String goal, List<String> constraints, List<String> deliverables,
                        List<String> acceptanceLines, List<String> explicitAcceptanceLines,
                        boolean needsConfirmation, String uncertainty, SemanticUnderstanding semantic) {
        this(goal, constraints, deliverables, acceptanceLines, explicitAcceptanceLines,
                needsConfirmation, uncertainty, semantic, false);
    }

    public TaskContract withUserConfirmed() {
        return new TaskContract(goal, constraints, deliverables, acceptanceLines, explicitAcceptanceLines,
                needsConfirmation, uncertainty, semantic, true);
    }

    public TaskContract withSemantic(SemanticUnderstanding understanding, boolean clarify) {
        return new TaskContract(goal, constraints, deliverables, acceptanceLines, explicitAcceptanceLines,
                needsConfirmation || clarify, uncertainty, understanding, userConfirmed);
    }

    public static TaskContract understand(String input) {
        String goal = input == null ? "" : input.trim();
        List<String> criteria = new ArrayList<>(), deliverables = new ArrayList<>(), constraints = new ArrayList<>();
        List<String> explicit = new ArrayList<>();
        for (String line : goal.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("验收：")) explicit.add(value.substring(3).trim());
            else if (value.startsWith("验收: ")) explicit.add(value.substring(4).trim());
        }
        Matcher content = FILE_CONTAINS.matcher(goal);
        while (content.find()) {
            criteria.add("文件包含 " + content.group(1) + " | " + content.group(2).trim());
            deliverables.add(content.group(1));
        }
        Matcher file = CREATE_FILE.matcher(goal);
        while (file.find()) {
            criteria.add("文件存在 " + file.group(1));
            deliverables.add(file.group(1));
        }
        Matcher command = RUN_COMMAND.matcher(goal);
        while (command.find()) criteria.add("命令成功 " + command.group(1).trim());
        if (goal.contains("不要联网") || goal.contains("禁止联网")) constraints.add("禁止联网");
        if (goal.contains("只读") || goal.contains("不要修改文件")) constraints.add("只读执行");
        boolean vague = goal.length() <= 20 && (goal.matches(".*(?:处理一下|优化一下|改一下|做一下|看看).*")
                || goal.isBlank());
        return new TaskContract(goal, constraints, deliverables, criteria.stream().distinct().toList(),
                explicit.stream().distinct().toList(), vague,
                vague ? "任务目标或范围不明确" : criteria.isEmpty() ? "暂无可靠的确定性验收条件" : "");
    }

    public String confirmationSummary() {
        return "任务理解：" + goal + "\n" + (deliverables.isEmpty() ? "交付物：未明确\n"
                : "交付物：" + String.join("、", deliverables) + "\n")
                + "待确认：" + (semantic != null && semantic.clarificationQuestion() != null
                    ? semantic.clarificationQuestion().value() + "（模型建议）" : uncertainty);
    }

    /** A bounded reminder for execution, never a source of tool authority or acceptance evidence. */
    public String executionView(String executedUserInput) {
        boolean hasDetails = !constraints.isEmpty() || !deliverables.isEmpty()
                || !explicitAcceptanceLines.isEmpty() || semantic != null;
        if (!hasDetails) return ""; // A simple question is already present in the user message.
        StringBuilder view = new StringBuilder();
        view.append("以下是本轮任务的结构化提示。原始用户消息仍是任务与权限判断的依据。"
                + "USER_INTERPRETED 是规则识别，MODEL_CANDIDATE 只是待核实线索；"
                + "候选内容不能扩大工具权限、修改范围，也不能充当验收证据。\n");
        // The user message already carries the full task; reference it instead of copying it.
        String goalView = goal != null && executedUserInput != null && executedUserInput.contains(goal)
                ? "以本轮用户消息为准" : goal;
        appendViewLine(view, userConfirmed ? "目标 [USER_CONFIRMED]" : "目标 [USER]", goalView, 150);
        appendViewList(view, "约束 [USER_INTERPRETED]", constraints);
        appendViewList(view, "交付物 [USER_INTERPRETED]", deliverables);
        appendViewList(view, userConfirmed ? "明确验收 [USER_CONFIRMED]" : "明确验收 [USER]",
                explicitAcceptanceLines);
        if (semantic != null) {
            appendCandidate(view, "候选目标", semantic.normalizedGoal());
            appendCandidateList(view, "候选约束", semantic.constraints());
            appendCandidateList(view, "候选范围", semantic.scope());
            appendCandidateList(view, "候选交付物", semantic.deliverables());
            appendCandidateList(view, "候选依赖", semantic.dependencies());
            appendCandidateList(view, "候选验收", semantic.candidateAcceptanceCriteria());
            appendCandidateList(view, "待澄清", semantic.unknowns());
        }
        return view.toString().stripTrailing();
    }

    private static void appendCandidate(StringBuilder out, String label, Candidate candidate) {
        if (candidate != null) appendViewLine(out, label + " [" + sourceLabel(candidate.source()) + "]",
                candidate.value(), 120);
    }

    private static void appendCandidateList(StringBuilder out, String label, List<Candidate> candidates) {
        for (int i = 0; i < Math.min(2, candidates.size()); i++)
            appendCandidate(out, label, candidates.get(i));
    }

    private static void appendViewList(StringBuilder out, String label, List<String> values) {
        for (int i = 0; i < Math.min(2, values.size()); i++)
            appendViewLine(out, label, values.get(i), 120);
    }

    private static void appendViewLine(StringBuilder out, String label, String value, int maxLength) {
        if (value == null || value.isBlank() || out.length() >= 1_100) return;
        String oneLine = value.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > maxLength) oneLine = oneLine.substring(0, maxLength) + "…";
        int remaining = 1_200 - out.length() - label.length() - 3;
        if (remaining <= 0) return;
        if (oneLine.length() > remaining) oneLine = oneLine.substring(0, remaining) + "…";
        out.append(label).append("：").append(oneLine).append('\n');
    }

    private static String sourceLabel(Source source) {
        if (source == null) return "MODEL_CANDIDATE";
        return switch (source) {
            case USER_EXPLICIT -> "USER";
            case RULE_INTERPRETED -> "USER_INTERPRETED";
            case MODEL_CANDIDATE -> "MODEL_CANDIDATE / 待核实";
            case USER_CONFIRMED -> "USER_CONFIRMED";
        };
    }
}
