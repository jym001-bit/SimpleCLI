package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.InternalUserMessages;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillRegistry;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把本批工具结果里成功的 load_skill 整理成一条 user 消息，由调用方紧跟在工具结果之后追加，
 * 让同一轮的下一次 LLM 请求就能看到 SKILL.md 正文。
 *
 * <p>正文不放进工具结果本身：工具结果统一经 {@link ToolResultBoundary} 包成 untrusted-data，
 * 模型会被要求不执行其中的指令，而内置、用户级和已信任的项目级 Skill 是本地可信的操作指引。
 * 未信任的项目级 Skill 由 load_skill 执行器直接把正文放进工具结果，这里不注入。</p>
 *
 * <p>已激活集合与预算都从调用方传入的消息历史推导，不持有任何共享状态：ReAct 会话、
 * 每个 Plan 任务和每个 Team worker 各自只看自己的历史。正文还在历史里时重复加载不再注入第二份；
 * 压缩或 {@code /clear} 把正文移出上下文后，模型可以重新加载，预算也随之释放。</p>
 */
public final class LoadedSkillMessages {

    static final String TOOL_NAME = "load_skill";
    static final int MAX_BODY_CHARS = 5 * 1024;
    static final String BUDGET_PROPERTY = "paicli.skill.body.budget";
    static final String BUDGET_ENV = "PAICLI_SKILL_BODY_BUDGET";
    static final int DEFAULT_BUDGET_CHARS = 16 * 1024;

    private static final String SECTION_PREFIX = InternalUserMessages.SKILL_SECTION_PREFIX;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoadedSkillMessages() {
    }

    /**
     * @param results 调用方应改用它追加工具结果：正文仍在上下文、超出预算的 load_skill 结果会被改写
     * @param message 可直接作为 user 消息内容的 Skill 指引；没有需要注入的正文时为空串
     */
    public record Injection(List<ToolRegistry.ToolExecutionResult> results, String message) {
        public Injection {
            results = results == null ? List.of() : List.copyOf(results);
            message = message == null ? "" : message;
        }
    }

    /** 不看历史的便捷入口，只在同一批内去重。 */
    public static String from(List<ToolRegistry.ToolExecutionResult> results, SkillRegistry registry) {
        return prepare(results, registry, List.of()).message();
    }

    public static Injection prepare(List<ToolRegistry.ToolExecutionResult> results,
                                    SkillRegistry registry,
                                    List<LlmClient.Message> history) {
        if (results == null || results.isEmpty() || registry == null) {
            return new Injection(results, "");
        }
        Set<String> active = activeSkills(history);
        int usedChars = injectedChars(history);
        int budgetChars = budgetChars();
        Map<String, String> sections = new LinkedHashMap<>();
        List<ToolRegistry.ToolExecutionResult> rewritten = new ArrayList<>(results.size());
        for (ToolRegistry.ToolExecutionResult result : results) {
            Skill skill = injectableSkill(result, registry);
            if (skill == null) {
                rewritten.add(result);
            } else if (active.contains(skill.name()) || sections.containsKey(skill.name())) {
                rewritten.add(withResult(result, "Skill '" + skill.name() + "' 的正文还在当前上下文里，就是前面的 \""
                        + SECTION_PREFIX + skill.name() + "\" 段，直接按指引继续。", true));
            } else {
                String section = section(skill);
                if (usedChars + section.length() > budgetChars) {
                    rewritten.add(withResult(result, "load_skill 未加载 '" + skill.name() + "'：本会话已加载的 Skill 正文已占用 "
                            + usedChars + "/" + budgetChars + " 字符预算。先按已加载的指引完成当前任务；"
                            + "确实需要这个 Skill 时向用户说明，由用户 /compact 或 /clear 释放上下文后再加载。", false));
                } else {
                    usedChars += section.length();
                    sections.put(skill.name(), section);
                    rewritten.add(result);
                }
            }
        }
        if (sections.isEmpty()) {
            return new Injection(rewritten, "");
        }
        StringBuilder message = new StringBuilder(InternalUserMessages.SKILL_INJECTION_MARKER)
                .append('\n')
                .append("以下是刚才 load_skill 加载的 Skill 指引，请按指引继续当前任务。\n\n");
        sections.values().forEach(section -> message.append(section).append("\n\n"));
        return new Injection(rewritten, message.append("---").toString());
    }

    /** 历史里还留着正文的 Skill 名。 */
    static Set<String> activeSkills(List<LlmClient.Message> history) {
        Set<String> names = new LinkedHashSet<>();
        if (history != null) {
            history.forEach(message -> names.addAll(InternalUserMessages.skillNames(message)));
        }
        return names;
    }

    private static int injectedChars(List<LlmClient.Message> history) {
        if (history == null) {
            return 0;
        }
        return history.stream()
                .filter(InternalUserMessages::isSkillInjection)
                .mapToInt(message -> message.content().length())
                .sum();
    }

    static int budgetChars() {
        String configured = System.getProperty(BUDGET_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(BUDGET_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_BUDGET_CHARS;
        }
        try {
            return Math.max(MAX_BODY_CHARS, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_BUDGET_CHARS;
        }
    }

    private static String section(Skill skill) {
        StringBuilder section = new StringBuilder(SECTION_PREFIX).append(skill.name())
                .append("（来源：").append(skill.displaySource()).append("）\n");
        if (skill.directory() != null) {
            section.append("Skill 目录：").append(skill.directory()).append('\n')
                    .append("文中 references/、scripts/ 等相对路径都以 Skill 目录为基准：读取附属文件用 load_skill(name=\"")
                    .append(skill.name()).append("\", file=\"references/xxx.md\")，执行脚本用 execute_command 并写出绝对路径。\n");
        }
        return section.append('\n').append(truncatedBody(skill).trim()).toString();
    }

    static String truncatedBody(Skill skill) {
        String body = skill.body() == null ? "" : skill.body();
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int lineEnd = body.lastIndexOf('\n', MAX_BODY_CHARS - 1);
        String shown = lineEnd >= MAX_BODY_CHARS / 2 ? body.substring(0, lineEnd + 1) : body.substring(0, MAX_BODY_CHARS);
        String hint = "load_skill(name=\"" + skill.name() + "\", file=\"SKILL.md\"";
        int continueLine = continuationLine(skill, shown);
        hint += continueLine > 0 ? ", offset=" + continueLine + ")" : ") 配合 offset/limit 分段";
        return shown + "\n\n...(正文过长已截断，剩余部分用 " + hint + " 继续读取)";
    }

    /** SKILL.md 中截断处下一行的行号；文件读不到或与正文对不上时返回 0。 */
    private static int continuationLine(Skill skill, String shown) {
        if (skill.skillMdPath() == null || !shown.endsWith("\n")) {
            return 0;
        }
        try {
            String content = Files.readString(skill.skillMdPath()).replace("\r\n", "\n").replace('\r', '\n');
            if (!content.endsWith(skill.body())) {
                return 0;
            }
            String beforeBody = content.substring(0, content.length() - skill.body().length());
            return lineCount(beforeBody) + lineCount(shown) + 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int lineCount(String text) {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    private static Skill injectableSkill(ToolRegistry.ToolExecutionResult result, SkillRegistry registry) {
        if (result == null || !TOOL_NAME.equals(result.name()) || !result.successful()) {
            return null;
        }
        JsonNode args = parse(result.argumentsJson());
        // 带 file 的调用是读附属文件，内容已在工具结果里
        if (args.hasNonNull("file")) {
            return null;
        }
        JsonNode name = args.path("name");
        // 与 load_skill 执行器一致，按原值查找，避免工具报“未找到”却注入了正文
        if (!name.isTextual() || name.asText().isBlank()) {
            return null;
        }
        Skill skill = registry.findSkill(name.asText());
        return skill != null && registry.isTrusted(skill) ? skill : null;
    }

    private static JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static ToolRegistry.ToolExecutionResult withResult(ToolRegistry.ToolExecutionResult result,
                                                               String text, boolean successful) {
        return new ToolRegistry.ToolExecutionResult(result.id(), result.name(), result.argumentsJson(), text,
                result.elapsedMillis(), result.timedOut(), result.imageParts(), successful, result.discoveredUrls());
    }
}
