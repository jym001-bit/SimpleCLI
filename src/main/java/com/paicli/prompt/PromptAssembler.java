package com.paicli.prompt;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class PromptAssembler {
    public static final String RUNTIME_DATE_PROPERTY = "paicli.prompt.runtime.date";
    public static final String RUNTIME_ZONE_PROPERTY = "paicli.prompt.runtime.zone";
    private final PromptRepository repository;

    public PromptAssembler(PromptRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    public String assemble(PromptMode mode, PromptContext context) {
        Objects.requireNonNull(mode, "mode");
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        String base = repository.loadRequired("base.md");
        if (!ctx.toolsEnabled()) {
            base = stripToolSections(base);
        }
        validateLanguageSection(base, "base.md");

        StringBuilder prompt = new StringBuilder();
        append(prompt, base);
        if (!ctx.toolsEnabled()) {
            append(prompt, noToolsSection());
        }
        append(prompt, repository.loadRequired("personalities/calm.md"));
        append(prompt, applyVariables(repository.loadRequired(mode.resourcePath()), ctx));
        append(prompt, repository.loadRequired("approvals/" + approvalMode(ctx) + ".md"));
        append(prompt, repository.loadRequired("context/context-management.md"));
        append(prompt, repository.loadRequired("handoff.md"));
        // 以下按稳定度从高到低排列，保证前缀缓存覆盖到尽量靠后：Skill 索引只在 /skill 启停或重载时变，
        // 运行时日期按天变，Project Context 里的相关记忆随每条用户输入变化，必须放最后
        append(prompt, dynamicSection("Skills", ctx.skillIndex()));
        append(prompt, runtimeContext());
        append(prompt, dynamicSection("Project Context", ctx.projectMemoryContext(), ctx.memoryContext(),
                ctx.externalContext()));

        String assembled = prompt.toString().trim();
        validateLanguageSection(assembled, "assembled prompt");
        return assembled;
    }

    private String approvalMode(PromptContext context) {
        String mode = context.approvalMode();
        if (mode == null || mode.isBlank()) {
            return "suggest";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "never" -> normalized;
            default -> "suggest";
        };
    }

    private static String runtimeContext() {
        ZoneId zone = configuredRuntimeZone();
        LocalDate date = configuredRuntimeDate(zone);
        return "## Runtime Context\n\n"
                + "- 当前日期: " + date + "\n"
                + "- 当前时区: " + zone;
    }

    private static ZoneId configuredRuntimeZone() {
        String configured = System.getProperty(RUNTIME_ZONE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(
                    "invalid " + RUNTIME_ZONE_PROPERTY + ": " + configured, error);
        }
    }

    private static LocalDate configuredRuntimeDate(ZoneId zone) {
        String configured = System.getProperty(RUNTIME_DATE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return LocalDate.now(zone);
        }
        try {
            return LocalDate.parse(configured.trim());
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(
                    "invalid " + RUNTIME_DATE_PROPERTY + ": " + configured, error);
        }
    }

    private static String applyVariables(String template, PromptContext context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.variables().entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        result = result.replace("{{taskType}}", context.variable("taskType"));
        result = result.replace("{{taskDescription}}", context.variable("taskDescription"));
        return result;
    }

    private static String dynamicSection(String title, String... values) {
        StringBuilder body = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!body.isEmpty()) {
                    body.append("\n\n");
                }
                body.append(value.trim());
            }
        }
        if (body.isEmpty()) {
            return "";
        }
        return "## " + title + "\n\n" + body;
    }

    private static String stripToolSections(String base) {
        String withoutTools = base.replaceFirst("(?s)\\n## Tools\\n.*?(?=\\n## Browser Policy\\n)", "\n");
        return withoutTools.replaceFirst("(?s)\\n## Tool Policy\\n.*?(?=\\n## Browser Policy\\n)", "\n");
    }

    private static String noToolsSection() {
        return """
                ## Tool Availability

                当前模型不支持 PaiCLI 原生工具调用。本轮不要声称已经读取、搜索、执行或修改了任何本地文件、命令、浏览器、MCP resource 或外部工具结果。

                绝对不要输出伪造的工具标签或 XML，例如 `<toolcall>`、`<read_file>`、`<list_dir>`。如果用户请求必须依赖本地文件、代码搜索、命令执行或联网工具，请直接说明当前 provider 不支持工具调用，并提示切换到支持 tools 的 provider 后重试。
                """;
    }

    private static void append(StringBuilder sb, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(section.trim());
    }

    private static void validateLanguageSection(String prompt, String source) {
        if (prompt == null || !prompt.contains("## Language")) {
            throw new IllegalStateException("Prompt " + source + " must contain a '## Language' section");
        }
    }
}
