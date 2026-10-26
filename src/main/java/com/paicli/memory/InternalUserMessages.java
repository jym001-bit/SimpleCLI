package com.paicli.memory;

import com.paicli.llm.LlmClient;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 运行时以 user 角色注入、但不是用户输入的消息：load_skill 的正文注入和 runaway guard 提醒。
 *
 * <p>压缩器按 user 消息切分轮次，必须先把这两类排除掉：否则一次 load_skill 就多出一个假轮次，
 * 挤掉真实的用户上下文；Skill 正文还会被喂给摘要模型压成一两句，甚至被记成用户要求。
 * 压缩时 Skill 注入直接移出上下文，模型需要时重新 load_skill。</p>
 */
public final class InternalUserMessages {

    /** Skill 注入消息的首行标记，由 {@code LoadedSkillMessages} 写入。 */
    public static final String SKILL_INJECTION_MARKER = "[PAICLI_SKILL_INJECTION]";
    /** Skill 注入里每个 Skill 段的标题前缀，后跟 Skill 名和“（来源：…）”。 */
    public static final String SKILL_SECTION_PREFIX = "## 已加载 Skill：";
    /** {@code RunawayGuard} 提醒的固定前缀，提示词里也按这个前缀向模型解释。 */
    public static final String RUNAWAY_REMINDER_PREFIX = "[runaway guard]";

    private InternalUserMessages() {
    }

    public static boolean isSkillInjection(LlmClient.Message message) {
        return startsWith(message, SKILL_INJECTION_MARKER);
    }

    /** Skill 注入消息里包含的 Skill 名；不是 Skill 注入时为空集合。 */
    public static Set<String> skillNames(LlmClient.Message message) {
        Set<String> names = new LinkedHashSet<>();
        if (!isSkillInjection(message)) {
            return names;
        }
        for (String line : message.content().split("\n")) {
            if (line.startsWith(SKILL_SECTION_PREFIX)) {
                String rest = line.substring(SKILL_SECTION_PREFIX.length());
                int sourceStart = rest.indexOf('（');
                names.add((sourceStart < 0 ? rest : rest.substring(0, sourceStart)).trim());
            }
        }
        return names;
    }

    public static boolean isRuntimeReminder(LlmClient.Message message) {
        return startsWith(message, RUNAWAY_REMINDER_PREFIX);
    }

    /** 不应计入用户轮次、也不应交给摘要模型的内部消息。 */
    public static boolean isInternal(LlmClient.Message message) {
        return isSkillInjection(message) || isRuntimeReminder(message);
    }

    private static boolean startsWith(LlmClient.Message message, String prefix) {
        return message != null
                && "user".equals(message.role())
                && message.content() != null
                && message.content().startsWith(prefix);
    }
}
